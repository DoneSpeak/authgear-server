# Authgear Server Input 请求处理与 OTP 流程深度分析

本文档详细解析 Authgear Server 中从 HTTP 请求到处理完成的完整流程，以 OTP 验证码场景为例，分析数据存储更新机制以及单一/多种 OTP 选项的处理逻辑。

---

## 目录

1. [架构概览](#架构概览)
2. [HTTP 请求入口](#http-请求入口)
3. [核心处理流程](#核心处理流程)
4. [OTP 场景完整流程](#otp-场景完整流程)
5. [单一与多种 OTP 选项处理](#单一与多种-otp-选项处理)
6. [数据存储更新机制](#数据存储更新机制)
7. [关键类与方法索引](#关键类与方法索引)

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Authgear Input 处理架构                            │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐      ┌──────────────────┐      ┌─────────────────────┐
│   HTTP Handler  │─────▶│  Service Layer   │─────▶│   Core Accept Loop  │
│                 │      │                  │      │                     │
│ • InputHandler  │      │ • FeedInput      │      │ • FindInputReactor  │
│ • ServeHTTP     │      │ • feedInput      │      │ • ReactTo           │
└─────────────────┘      └──────────────────┘      │ • appendNode        │
                                                     └─────────────────────┘
                                                              │
                                                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Intent & Node Processing                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  IntentUseAuthenticatorOOBOTP ──▶ IntentAuthenticationOOB ──▶ NodeAuthOOB │
│       (选择认证器)                    (发送/验证 OTP)              (验证码)   │
└─────────────────────────────────────────────────────────────────────────────┘
                                                              │
                                                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Data Storage (Redis)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│  • Flow State: app:{appID}:authenticationflow_state:{stateToken}            │
│  • Session:    app:{appID}:authenticationflow_session:{flowID}              │
│  • Flow Ref:   app:{appID}:authenticationflow_flow:{flowID}               │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## HTTP 请求入口

### 1. 端点定义

```go
// pkg/auth/handler/api/authenticationflow_v1_input.go:17-18
func ConfigureAuthenticationFlowV1InputRoute(route httproute.Route) httproute.Route {
    return route.WithMethods("OPTIONS", "POST")
              .WithPathPattern("/api/v1/authentication_flows/states/input")
}
```

### 2. 请求结构

```go
// pkg/auth/handler/api/authenticationflow_v1_input.go:53-57
type AuthenticationFlowV1NonRestfulInputRequest struct {
    StateToken string            `json:"state_token,omitempty"`
    Input      json.RawMessage   `json:"input,omitempty"`
    BatchInput []json.RawMessage `json:"batch_input,omitempty"`
}
```

**示例请求（OTP 验证码输入）：**

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_xxx",
  "input": {
    "code": "123456"
  }
}
```

### 3. Handler 处理流程

```go
// pkg/auth/handler/api/authenticationflow_v1_input.go:65-118

func (h *AuthenticationFlowV1InputHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // 1. 解析请求 JSON
    var request AuthenticationFlowV1NonRestfulInputRequest
    err = httputil.BindJSONBody(r, w, ...)
    
    // 2. 调用 input() 或 batchInput()
    if request.Input != nil {
        h.input(ctx, w, r, request)
    } else {
        h.batchInput(ctx, w, r, request)
    }
}

func (h *AuthenticationFlowV1InputHandler) input0(...) (*authflow.ServiceOutput, error) {
    // 3. 核心调用：Service.FeedInput()
    output, err := h.Workflows.FeedInput(ctx, stateToken, request.Input)
    return output, err
}
```

**Handler 调用链：**

```
ServeHTTP() 
    ├── input() 
    │     └── input0()
    │           └── Service.FeedInput()
    └── batchInput()
          └── batchInput0()
```

---

## 核心处理流程

### 1. Service 层入口

```go
// pkg/lib/authenticationflow/service.go:306-387

func (s *Service) FeedInput(ctx context.Context, stateToken string, rawMessage json.RawMessage) (output *ServiceOutput, err error) {
    // 1. 从 state_token 获取 Flow
    flow, err := s.Store.GetFlowByStateToken(ctx, stateToken)
    if err != nil {
        return
    }
    
    // 2. 获取 Session 并更新上下文
    ctx, session, err := s.getSessionAndUpdateContext(ctx, flow.FlowID)
    
    // 3. 执行 feedInput（核心处理）
    flow, flowAction, err = s.feedInput(ctx, session, stateToken, rawMessage)
    
    // 4. 处理特殊错误（SwitchFlow, RewriteFlow）
    // ...
    
    // 5. 处理流程完成（ErrEOF）
    if isEOF {
        err = s.Database.WithTx(ctx, func(ctx context.Context) error {
            cookies, err = s.finishFlow(ctx, flow)  // 应用所有 Effects
            return err
        })
        s.Store.DeleteSession(ctx, session)  // 删除 Session
        s.Store.DeleteFlow(ctx, flow)        // 删除 Flow
    }
    
    return output, err
}
```

### 2. FeedInput 核心逻辑

```go
// pkg/lib/authenticationflow/service.go:485-544

func (s *Service) feedInput(ctx context.Context, session *Session, stateToken string, rawMessage json.RawMessage) (flow *Flow, flowAction *FlowAction, err error) {
    // 获取 Flow
    flow, err = s.Store.GetFlowByStateToken(ctx, stateToken)
    
    // Accept 循环
    var shouldAccept = true
    for shouldAccept {
        shouldAccept = false
        var acceptResult *AcceptResult = NewAcceptResult()
        flows := NewFlows(flow)
        
        err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
            // 应用 Run Effects
            err = ApplyRunEffects(ctx, s.Deps, flows)
            
            // 核心调用：Accept
            err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
            
            // 获取 FlowAction（用于返回给客户端）
            flowAction, err = s.getFlowAction(ctx, session, flow)
            return nil
        })
        
        // 处理 AcceptResult（DelayedOneTimeFunctions 等）
        acceptErr := s.processAcceptResult(ctx, session, flows, acceptResult)
        
        // 特殊处理：需要重试
        if errors.Is(err, ErrPauseAndRetryAccept) {
            shouldAccept = true
            err = nil
        }
    }
    
    // 持久化 Flow 状态
    err = s.Store.CreateFlow(ctx, flow)
    return
}
```

### 3. Accept 核心循环

```go
// pkg/lib/authenticationflow/accept.go:106-269

func doAccept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(InputSchema) (Input, error)) (err error) {
    var changed bool
    defer func() {
        // 如果状态改变，生成新的 state_token
        if changed {
            flows.Nearest.StateToken = newStateToken()
        }
    }()
    
    for {
        // 1. 查找可以接收输入的 Reactor
        var findInputReactorResult *FindInputReactorResult
        findInputReactorResult, err = FindInputReactor(ctx, deps, flows)
        
        // 2. 解析输入数据
        var input Input
        input, err = inputFn(findInputReactorResult.InputSchema)
        
        // 3. 调用 ReactTo 处理输入
        var reactToResult ReactToResult
        reactToResult, err = findInputReactorResult.InputReactor.ReactTo(ctx, deps, findInputReactorResult.Flows, input)
        
        // 4. 处理返回结果
        var nextNode Node
        switch reactToResult := reactToResult.(type) {
        case *Node:
            nextNode = *reactToResult
        case *NodeWithDelayedOneTimeFunction:
            nextNode = *reactToResult.Node
            result.DelayedOneTimeFunctions = append(result.DelayedOneTimeFunctions, reactToResult.DelayedOneTimeFunction)
        }
        
        // 5. 追加新节点到 Flow
        err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
        changed = true
    }
}
```

### 4. FindInputReactor 查找逻辑

```go
// pkg/lib/authenticationflow/input.go:86-114

func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) (*FindInputReactorResult, error) {
    // 策略：优先检查最后一个 Node
    if len(flows.Nearest.Nodes) > 0 {
        lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
        findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
        if err == nil {
            return findInputReactorResult, nil
        }
        // ErrEOF 则 fallthrough 检查 Intent
        if !errors.Is(err, ErrEOF) {
            return nil, err
        }
    }
    
    // 检查 Intent 是否能接收输入
    inputSchema, err := flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
    if err == nil {
        return &FindInputReactorResult{
            Flows:        flows,
            InputReactor: flows.Nearest.Intent,
            InputSchema:  inputSchema,
        }, nil
    }
    return nil, err
}
```

**查找策略说明：**

| 顺序 | 目标 | 说明 |
|------|------|------|
| 1 | 最后一个 Node | 优先检查最近活跃的节点 |
| 2 | Intent | 如果所有 Nodes 都已完成（ErrEOF），检查 Intent |

---

## OTP 场景完整流程

以 `primary_oob_otp_email` 为例，展示从选择 OTP 方式到输入验证码的完整流程。

### 1. 流程阶段概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          OTP 认证完整流程                                    │
└─────────────────────────────────────────────────────────────────────────────┘

阶段1: 选择 OTP 方式
┌─────────────────────────┐
│ IntentLoginFlowStepAuthenticate │
│ CanReactTo: options=[oob_otp_email, password]
│ ReactTo: 用户选择 oob_otp_email
└───────────┬─────────────┘
            │ 创建 SubFlow
            ▼
阶段2: 选择认证器（如有多选项）
┌─────────────────────────┐
│ IntentUseAuthenticatorOOBOTP  │
│ CanReactTo: authenticatorSelected=false
│ ReactTo: 返回 NodeDidSelectAuthenticator
└───────────┬─────────────┘
            │
            ▼
阶段3: 启动 OTP 验证子流程
┌─────────────────────────┐
│ IntentAuthenticationOOB  │
│ CanReactTo: 单一/多通道处理
│ ReactTo: 返回 NodeAuthenticationOOB
└───────────┬─────────────┘
            │
            ▼
阶段4: 等待验证码输入
┌─────────────────────────┐
│ NodeAuthenticationOOB    │
│ CanReactTo: 返回 InputSchema（等待 code）
│ ReactTo: 验证 code，返回 NodeDoMarkClaimVerified
└───────────┬─────────────┘
            │
            ▼
阶段5: 完成认证
┌─────────────────────────┐
│ IntentUseAuthenticatorOOBOTP  │
│ CanReactTo: claimVerified=true, authenticated=false
│ ReactTo: 返回 NodeDoUseAuthenticatorSimple
└─────────────────────────┘
```

### 2. 各阶段详细解析

#### 阶段 1：IntentLoginFlowStepAuthenticate

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_authenticate.go

func (i *IntentLoginFlowStepAuthenticate) CanReactTo(ctx, deps, flows) (authflow.InputSchema, error) {
    _, _, deviceTokenInspected := authflow.FindMilestoneInCurrentFlow[MilestoneFlowDeviceTokenInspected](flows)
    _, _, authenticationMethodSelected := authflow.FindMilestoneInCurrentFlow[MilestoneFlowSelectAuthenticationMethod](flows)
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
    _, _, deviceTokenCreatedIfRequested := authflow.FindMilestoneInCurrentFlow[MilestoneDidCreateDeviceToken](flows)
    _, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)
    
    switch {
    case !deviceTokenInspected:
        return nil, nil  // 检查设备令牌
    case !authenticationMethodSelected:
        // 返回可选认证方式列表
        return &InputSchemaLoginFlowStepAuthenticate{Options: i.Options}, nil
    // ... 其他状态
    }
}

func (i *IntentLoginFlowStepAuthenticate) ReactTo(ctx, deps, flows, input) (authflow.ReactToResult, error) {
    // 用户选择 authentication 方式
    var inputTakeAuthenticationMethod inputTakeAuthenticationMethod
    if authflow.AsInput(input, &inputTakeAuthenticationMethod) {
        // 启动对应的认证器 Intent
        switch authentication {
        case model.AuthenticationFlowAuthenticationPrimaryOOBOTPEmail:
            return authflow.NewSubFlow(&IntentUseAuthenticatorOOBOTP{
                JSONPointer:    i.JSONPointer,
                UserID:         i.UserID,
                Authentication: authentication,
                Options:        options,
            }), nil
        }
    }
}
```

#### 阶段 2：IntentUseAuthenticatorOOBOTP（选择认证器）

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go:86-120

func (n *IntentUseAuthenticatorOOBOTP) CanReactTo(ctx, deps, flows) (authflow.InputSchema, error) {
    _, _, authenticatorSelected := authflow.FindMilestoneInCurrentFlow[MilestoneDidSelectAuthenticator](flows)
    _, _, claimVerified := authflow.FindMilestoneInCurrentFlow[MilestoneDoMarkClaimVerified](flows)
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
    
    switch {
    case !authenticatorSelected:
        // 阶段1：需要选择认证器
        return &InputSchemaUseAuthenticatorOOBOTP{Options: n.Options}, nil
    case !claimVerified:
        // 阶段2：需要验证 claim
        return nil, nil
    case !authenticated:
        // 阶段3：需要完成认证
        return nil, nil
    default:
        return nil, authflow.ErrEOF
    }
}

func (n *IntentUseAuthenticatorOOBOTP) ReactTo(ctx, deps, flows, input) (authflow.ReactToResult, error) {
    switch {
    case !authenticatorSelected:
        // 处理用户选择
        var inputTakeAuthenticationOptionIndex inputTakeAuthenticationOptionIndex
        if authflow.AsInput(input, &inputTakeAuthenticationOptionIndex) {
            index := inputTakeAuthenticationOptionIndex.GetIndex()
            info, isNew, err := n.pickAuthenticator(ctx, deps, n.Options, index)
            
            if isNew {
                return authflow.NewNodeSimple(&NodeDoJustInTimeCreateAuthenticator{...}), nil
            }
            return authflow.NewNodeSimple(&NodeDidSelectAuthenticator{Authenticator: info}), nil
        }
        
    case !claimVerified:
        // 启动 OTP 验证子流程
        return authflow.NewSubFlow(&IntentAuthenticationOOB{...}), nil
        
    case !authenticated:
        // 完成认证
        return authflow.NewNodeSimple(&NodeDoUseAuthenticatorSimple{...}), nil
    }
}
```

#### 阶段 3：IntentAuthenticationOOB（单一/多通道处理）

```go
// pkg/lib/authenticationflow/declarative/intent_authn_oob.go:65-95

func (i *IntentAuthenticationOOB) CanReactTo(ctx, deps, flows) (authflow.InputSchema, error) {
    _, _, verified := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPVerified](flows)
    if !verified {
        // 关键：判断是否有多个通道
        channels := i.getChannels(deps)
        if len(channels) == 1 {
            // 只有一个通道时，不需要用户输入，直接继续
            return nil, nil
        }
        // 多个通道，让用户选择
        return &InputSchemaTakeOOBOTPChannel{Channels: channels}, nil
    }
    // ...
}

func (i *IntentAuthenticationOOB) ReactTo(ctx, deps, flows, input) (authflow.ReactToResult, error) {
    if !verified {
        channels := i.getChannels(deps)
        var channel model.AuthenticatorOOBChannel
        
        switch {
        case len(channels) == 1:
            // 单一通道：直接使用
            channel = channels[0]
        case authflow.AsInput(input, &inputTakeOOBOTPChannel):
            // 多种通道：从输入获取用户选择
            channel = inputTakeOOBOTPChannel.GetChannel()
        }
        
        // 创建 OOB 认证节点，发送 OTP
        node, err := NewNodeAuthenticationOOB(ctx, deps, &NodeAuthenticationOOB{
            UserID:  i.UserID,
            Purpose: i.Purpose,
            Form:    i.Form,
            Info:    i.Info,
            Channel: channel,
        })
        return node, nil
    }
    // ...
}
```

#### 阶段 4：NodeAuthenticationOOB（验证码处理）

```go
// pkg/lib/authenticationflow/declarative/node_authn_oob.go:76-185

func (n *NodeAuthenticationOOB) CanReactTo(ctx, deps, flows) (authflow.InputSchema, error) {
    // 返回等待验证码的 InputSchema
    return &InputSchemaNodeAuthenticationOOB{
        OTPForm: n.Form,
    }, nil
}

func (n *NodeAuthenticationOOB) ReactTo(ctx, deps, flows, input) (authflow.ReactToResult, error) {
    switch {
    case inputNodeAuthenticationOOB.IsCode():
        code := inputNodeAuthenticationOOB.GetCode()
        
        // 验证 OTP 码
        _, _, err := deps.Authenticators.VerifyOneWithSpec(ctx, n.UserID, n.Info.Type, ...)
        if apierrors.IsKind(err, otp.InvalidOTPCode) {
            return nil, n.invalidOTPCodeError()
        }
        
        // 验证成功，创建 Milestone 节点
        verifiedClaim := deps.Verification.NewVerifiedClaim(ctx, n.UserID, claimName, claimValue)
        return authflow.NewNodeSimple(&NodeDoMarkClaimVerified{Claim: verifiedClaim}), nil
        
    case inputNodeAuthenticationOOB.IsResend():
        // 重新发送验证码
        code, err := n.GenerateCode(ctx, deps)
        return &authflow.NodeWithDelayedOneTimeFunction{
            Node: newSimpleNode,
            DelayedOneTimeFunction: func(ctx context.Context, deps *authflow.Dependencies) error {
                return n.SendCode(ctx, deps, code)
            },
        }, authflow.ErrReplaceNode
    }
}
```

---

## 单一与多种 OTP 选项处理

### 1. 场景对比

| 场景 | 配置示例 | 用户交互 |
|------|----------|----------|
| **单一选项** | 用户只有邮箱，且只配置了 email 通道 | 自动选择，无需用户输入 |
| **多种选项** | 用户有邮箱和手机，或配置了 sms/email 多通道 | 显示选项列表，用户主动选择 |

### 2. 单一选项处理（自动选择）

```go
// pkg/lib/authenticationflow/declarative/intent_authn_oob.go:65-77

func (i *IntentAuthenticationOOB) CanReactTo(...) (authflow.InputSchema, error) {
    channels := i.getChannels(deps)  // 获取可用通道
    
    if len(channels) == 1 {
        // 只有一个通道，不需要用户输入
        // The rationale is that the only possible input is that channel.
        // So it is trivial that we can proceed without the input.
        return nil, nil
    }
    // 多个通道，返回 InputSchema
    return &InputSchemaTakeOOBOTPChannel{Channels: channels}, nil
}
```

**流程图（单一选项）：**

```
IntentAuthenticationOOB
    │
    ▼ CanReactTo
   channels = [email]  // 只有一个
    │
    ▼ return nil, nil  // 不需要输入
    │
    ▼ ReactTo
   自动选择 channel = email
    │
    ▼ 创建 NodeAuthenticationOOB
   发送 OTP 到邮箱
```

### 3. 多种选项处理（用户选择）

```go
// pkg/lib/authenticationflow/declarative/intent_authn_oob.go:78-87

if len(channels) > 1 {
    return &InputSchemaTakeOOBOTPChannel{
        JSONPointer:    i.JSONPointer,
        FlowRootObject: flowRootObject,
        Channels:       channels,
    }, nil
}
```

**流程图（多种选项）：**

```
IntentAuthenticationOOB
    │
    ▼ CanReactTo
   channels = [email, sms, whatsapp]  // 多个
    │
    ▼ return InputSchemaTakeOOBOTPChannel
    │
    ▼ 客户端显示选项列表
   ┌─────────────────────┐
   │ • Email (u***@example.com)    │
   │ • SMS (+86 13****5678)        │
   │ • WhatsApp                    │
   └─────────────────────┘
    │
    ▼ 用户选择 SMS
    │
    ▼ ReactTo
   从 input 获取 channel = sms
    │
    ▼ 创建 NodeAuthenticationOOB
   发送 OTP 到手机
```

### 4. 选项生成逻辑

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go:196-255

func (n *IntentUseAuthenticatorOOBOTP) pickAuthenticator(ctx, deps, options, index) (info *authenticator.Info, isNew bool, err error) {
    for idx, c := range options {
        if idx == index {
            switch {
            case c.AuthenticatorID != "":
                // 使用已有认证器
                info, err = deps.Authenticators.Get(ctx, c.AuthenticatorID)
                return
                
            case c.IdentityID != "":
                // 即时创建认证器（Just-in-time）
                identityInfo, err := deps.Identities.Get(ctx, c.IdentityID)
                info, err = n.createAuthenticator(ctx, deps, identityInfo)
                
                // 检查是否与已有认证器重复
                allAuthenticators, _ := deps.Authenticators.List(ctx, n.UserID)
                for _, authenticator := range allAuthenticators {
                    if authenticator.Equal(info) {
                        info = authenticator
                        isNew = false
                        return
                    }
                }
                isNew = true
                return
            }
        }
    }
}
```

---

## 数据存储更新机制

### 1. 存储架构

```
Redis Key 命名规范:

┌─────────────────────────────────────────────────────────────────────────────┐
│                           Redis 存储结构                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Flow State (流程状态)                                                      │
│  Key: app:{appID}:authenticationflow_state:{stateToken}                    │
│  Value: JSON 序列化的 Flow 对象                                             │
│  TTL: 24 小时                                                               │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ {                                                                   │   │
│  │   "flow_id": "authflow_xxx",                                        │   │
│  │   "state_token": "authflowstate_xxx",                               │   │
│  │   "intent": {...},                                                   │   │
│  │   "nodes": [                                                         │   │
│  │     {type: "SIMPLE", simple: {kind: "NodeDoUseIdentity", ...}},      │   │
│  │     {type: "SIMPLE", simple: {kind: "NodeDidSelectAuthenticator",...}},│  │
│  │     {type: "SUB_FLOW", flow: {intent: {...}, nodes: [...]}}         │   │
│  │   ]                                                                  │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Flow Reference (流程存在标记)                                              │
│  Key: app:{appID}:authenticationflow_flow:{flowID}                           │
│  Value: "" (空字符串)                                                        │
│  TTL: 24 小时                                                               │
│                                                                             │
│  Session (会话信息)                                                         │
│  Key: app:{appID}:authenticationflow_session:{flowID}                      │
│  Value: JSON 序列化的 Session 对象                                          │
│  TTL: 24 小时                                                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2. 核心存储操作

```go
// pkg/lib/authenticationflow/store.go

type StoreImpl struct {
    Redis *appredis.Handle
    AppID config.AppID
}

// 创建/更新 Flow
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    bytes, err := json.Marshal(flow)
    if err != nil {
        return err
    }
    
    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        stateKey := redisFlowStateKey(s.AppID, flow.StateToken)
        ttl := Lifetime  // 24 小时
        
        // 存储 flow 存在标记
        _, err := conn.SetEx(ctx, flowKey, []byte(flowKey), ttl).Result()
        
        // 存储 flow 完整状态
        _, err = conn.SetEx(ctx, stateKey, bytes, ttl).Result()
        
        return nil
    })
}

// 读取 Flow
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    stateKey := redisFlowStateKey(s.AppID, stateToken)
    var flow Flow
    err := s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        // 1. 获取 flow JSON 数据
        bytes, err := conn.Get(ctx, stateKey).Bytes()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }
        
        err = json.Unmarshal(bytes, &flow)
        
        // 2. 验证 flow 是否有效
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err = conn.Get(ctx, flowKey).Result()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }
        
        return nil
    })
    return &flow, err
}

// 删除 Flow
func (s *StoreImpl) DeleteFlow(ctx context.Context, flow *Flow) error {
    // 不删除 states（可能有多个），只删除 flowID
    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err := conn.Del(ctx, flowKey).Result()
        return err
    })
}
```

### 3. 数据更新时机

```go
// pkg/lib/authenticationflow/accept.go:106-118

func doAccept(...) (err error) {
    var changed bool
    defer func() {
        // 每次 Accept 成功处理输入后，生成新的 state_token
        if changed {
            flows.Nearest.StateToken = newStateToken()
        }
    }()
    
    for {
        // 查找 Reactor -> ReactTo -> appendNode
        // ...
        changed = true  // 标记状态已改变
    }
}
```

**存储更新流程：**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          数据存储更新流程                                    │
└─────────────────────────────────────────────────────────────────────────────┘

1. 接收 Input 请求
   │
   ▼
2. Service.FeedInput()
   │
   ├── 3. GetFlowByStateToken()  // 从 Redis 读取当前状态
   │
   ├── 4. feedInput()  // 核心处理
   │      │
   │      ├── Accept()  // 处理输入
   │      │      │
   │      │      ├── FindInputReactor()  // 查找 Reactor
   │      │      ├── ReactTo()           // 处理输入
   │      │      └── appendNode()        // 追加新节点
   │      │
   │      └── CreateFlow()  // 保存新状态到 Redis
   │             │
   │             ├── 生成新 state_token
   │             ├── 序列化 Flow 为 JSON
   │             └── 写入 Redis（覆盖旧状态）
   │
   └── 5. 返回结果
           │
           └── 包含新的 state_token 给客户端
```

### 4. 存储数据变化示例（OTP 流程）

**阶段 1：用户选择 primary_oob_otp_email 后**

```json
{
  "flow_id": "flow_xxx",
  "state_token": "authflowstate_aaa",
  "intent": {
    "kind": "IntentLoginFlowStepAuthenticate",
    "data": { "options": [{"authentication": "primary_oob_otp_email"}] }
  },
  "nodes": [
    {"type": "SIMPLE", "simple": {"kind": "NodeDoUseIdentity", ...}},
    {"type": "SIMPLE", "simple": {"kind": "NodeDidSelectAuthenticator", "authenticator": {...}}}
  ]
}
```

**阶段 2：创建 OTP SubFlow 后**

```json
{
  "flow_id": "flow_xxx",
  "state_token": "authflowstate_bbb",  // 新生成
  "intent": { "kind": "IntentLoginFlowStepAuthenticate", ... },
  "nodes": [
    {"type": "SIMPLE", "simple": {"kind": "NodeDoUseIdentity", ...}},
    {"type": "SIMPLE", "simple": {"kind": "NodeDidSelectAuthenticator", ...}},
    {
      "type": "SUB_FLOW",
      "flow": {
        "intent": {"kind": "IntentUseAuthenticatorOOBOTP", ...},
        "nodes": [
          {"type": "SUB_FLOW", "flow": {
            "intent": {"kind": "IntentAuthenticationOOB", ...},
            "nodes": [
              {"type": "SIMPLE", "simple": {"kind": "NodeAuthenticationOOB", ...}}
            ]
          }}
        ]
      }
    }
  ]
}
```

**阶段 3：验证码验证成功后**

```json
{
  "flow_id": "flow_xxx",
  "state_token": "authflowstate_ccc",  // 再次更新
  "nodes": [
    // ... 前面的节点
    {
      "type": "SUB_FLOW",
      "flow": {
        "intent": {"kind": "IntentAuthenticationOOB", ...},
        "nodes": [
          {"type": "SIMPLE", "simple": {"kind": "NodeAuthenticationOOB", ...}},
          {"type": "SIMPLE", "simple": {"kind": "NodeDoMarkClaimVerified", "claim": {...}}}
        ]
      }
    }
  ]
}
```

---

## 关键类与方法索引

### HTTP Handler 层

| 类/方法 | 文件路径 | 职责 |
|---------|----------|------|
| `AuthenticationFlowV1InputHandler` | `pkg/auth/handler/api/authenticationflow_v1_input.go` | 处理输入 HTTP 请求 |
| `ServeHTTP()` | 同上 | HTTP 入口，解析请求 |
| `input()` / `input0()` | 同上 | 调用 Service 层处理 |

### Service 层

| 类/方法 | 文件路径 | 职责 |
|---------|----------|------|
| `Service` | `pkg/lib/authenticationflow/service.go` | 认证流程服务 |
| `FeedInput()` | 同上 | 接收输入的主入口 |
| `feedInput()` | 同上 | FeedInput 内部实现 |
| `feedSyntheticInput()` | 同上 | 处理合成输入 |
| `finishFlow()` | 同上 | 完成流程，应用 Effects |
| `getFlowAction()` | 同上 | 获取当前 FlowAction |

### Core Accept 层

| 类/方法 | 文件路径 | 职责 |
|---------|----------|------|
| `Accept()` | `pkg/lib/authenticationflow/accept.go` | 接受输入并执行流程 |
| `doAccept()` | 同上 | Accept 核心循环 |
| `appendNode()` | 同上 | 追加新节点到 Flow |
| `FindInputReactor()` | `pkg/lib/authenticationflow/input.go` | 查找可接收输入的 Reactor |
| `FindInputReactorForFlow()` | 同上 | 在 Flow 中查找 Reactor |
| `FindInputReactorForNode()` | 同上 | 在 Node 中查找 Reactor |

### OTP 相关 Intents 和 Nodes

| 类 | 文件路径 | 职责 |
|----|----------|------|
| `IntentUseAuthenticatorOOBOTP` | `pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go` | 处理 OOB OTP 认证器选择和使用 |
| `IntentAuthenticationOOB` | `pkg/lib/authenticationflow/declarative/intent_authn_oob.go` | 处理 OTP 发送和验证子流程 |
| `NodeAuthenticationOOB` | `pkg/lib/authenticationflow/declarative/node_authn_oob.go` | 处理具体 OTP 码验证 |
| `NodeDidSelectAuthenticator` | `pkg/lib/authenticationflow/declarative/node_did_select_authenticator.go` | 标记已选择认证器 |
| `NodeDoMarkClaimVerified` | `pkg/lib/authenticationflow/declarative/node_do_mark_claim_verified.go` | 标记 Claim 已验证 |
| `NodeDoUseAuthenticatorSimple` | `pkg/lib/authenticationflow/declarative/node_do_use_authenticator_simple.go` | 标记认证完成 |

### 数据存储层

| 类/方法 | 文件路径 | 职责 |
|---------|----------|------|
| `StoreImpl` | `pkg/lib/authenticationflow/store.go` | Redis 存储实现 |
| `CreateFlow()` | 同上 | 创建/更新 Flow |
| `GetFlowByStateToken()` | 同上 | 通过 state_token 获取 Flow |
| `DeleteFlow()` | 同上 | 删除 Flow |
| `CreateSession()` | 同上 | 创建 Session |
| `GetSession()` | 同上 | 获取 Session |

### Input Schema 和 Input

| 类 | 文件路径 | 职责 |
|----|----------|------|
| `InputSchemaUseAuthenticatorOOBOTP` | `pkg/lib/authenticationflow/declarative/input_use_authenticator_oob_otp.go` | OOB OTP 选择输入模式 |
| `InputSchemaTakeOOBOTPChannel` | `pkg/lib/authenticationflow/declarative/input_take_oob_otp_channel.go` | OTP 通道选择输入模式 |
| `InputSchemaNodeAuthenticationOOB` | `pkg/lib/authenticationflow/declarative/input_node_auth_oob.go` | OTP 码输入模式 |
| `InputNodeAuthenticationOOB` | 同上 | OTP 码输入数据结构 |

---

## 总结

### 核心设计原则

1. **状态机驱动**：通过 `Milestone` 接口跟踪流程状态，而非依赖多个 Node 实例
2. **递归 SubFlow**：复杂流程通过嵌套 SubFlow 实现，如 `IntentUseAuthenticatorOOBOTP` 包含 `IntentAuthenticationOOB`
3. **自动 vs 手动**：单一选项自动处理，多种选项需要用户输入
4. **延迟执行**：通过 `DelayedOneTimeFunction` 延迟发送 OTP，避免在只读事务中执行副作用
5. **幂等 State Token**：每次状态改变生成新的 `state_token`，旧 token 失效

### 关键流程回顾

```
HTTP Input 请求
    │
    ▼
AuthenticationFlowV1InputHandler.ServeHTTP()
    │
    ▼
Service.FeedInput()
    ├── GetFlowByStateToken()  // 读取当前状态
    │
    ├── feedInput()
    │      │
    │      └── Accept Loop
    │             ├── FindInputReactor()  // 查找目标 Reactor
    │             │       ├── 优先检查最后一个 Node
    │             │       └── 回退检查 Intent
    │             │
    │             ├── ReactTo()  // 处理输入
    │             │       ├── 验证 OTP 码
    │             │       ├── 创建 Milestone Node
    │             │       └── 返回下一个节点
    │             │
    │             └── appendNode()  // 追加到 Flow.Nodes
    │
    └── CreateFlow()  // 保存新状态到 Redis
            └── 生成新的 state_token

```

---

## 相关文档

- [Authgear Flow Runtime Mechanism](Authgear-Flow-Runtime-Mechanism.md) - 运行时机制详解
- [Authgear 2FA Configuration And Implementation](Authgear-2FA-Configuration-And-Implementation.md) - 2FA 配置与实现
- [Authgear Nested Steps Implementation](Authgear-Nested-Steps-Implementation.md) - 嵌套步骤实现机制
