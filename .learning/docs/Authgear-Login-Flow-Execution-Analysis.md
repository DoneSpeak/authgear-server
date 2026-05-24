# Authgear Login Flow 执行流程深度解析

## 概述

本文档详细解析 Authgear Server 中 Login Flow 从 `Service.FeedInput()` 到完成请求的完整执行链路，包括关键类的构建过程和相互调用关系。

---

## 一、整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           HTTP Handler Layer                                 │
│                    (pkg/auth/handler/api/authenticationflow_v1.go)          │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Service Layer                                      │
│                    (pkg/lib/authenticationflow/service.go)                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐   │
│  │  CreateNewFlow  │  │   FeedInput     │  │    FeedSyntheticInput       │   │
│  │   (创建新流程)   │  │  (处理输入)      │  │    (合成输入)                │   │
│  └────────┬────────┘  └────────┬────────┘  └─────────────────────────────┘   │
└───────────┼────────────────────┼────────────────────────────────────────────┘
            │                    │
            ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Core Engine                                        │
│                    (pkg/lib/authenticationflow/)                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐   │
│  │     Accept      │  │  FindInputReactor│  │    ApplyRunEffects          │   │
│  │   (执行核心)     │  │  (查找反应器)    │  │    (应用效果)               │   │
│  └────────┬────────┘  └────────┬────────┘  └─────────────────────────────┘   │
└───────────┼────────────────────┼────────────────────────────────────────────┘
            │                    │
            ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Declarative Layer                                    │
│         (pkg/lib/authenticationflow/declarative/)                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                    Login Flow Intents                                    │ │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐│ │
│  │  │IntentLogin  │ │IntentLogin  │ │IntentLogin  │ │ IntentLoginFlowStep ││ │
│  │  │   Flow      │ │ FlowSteps   │ │StepIdentify │ │    Authenticate     ││ │
│  │  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └─────────────────────┘│ │
│  │         │               │               │                                │ │
│  │         └───────────────┴───────────────┘                                │ │
│  │                         │                                                │ │
│  │                         ▼                                                │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │ │
│  │  │                      Login Nodes                                   │   │ │
│  │  │ ┌────────────────┐ ┌────────────────┐ ┌────────────────────────┐ │   │ │
│  │  │ │NodeDoUseIdentity│ │NodeDoCreate   │ │ NodeCheckLoginHint      │ │   │ │
│  │  │ │                │ │   Session      │ │                         │ │   │ │
│  │  │ └────────────────┘ └────────────────┘ └────────────────────────┘ │   │ │
│  │  └─────────────────────────────────────────────────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心执行流程：从 FeedInput 到完成

### 阶段 1：Service 层入口

#### 调用入口
```go
// pkg/lib/authenticationflow/service.go:306
func (s *Service) FeedInput(ctx context.Context, stateToken string, rawMessage json.RawMessage) (output *ServiceOutput, err error)
```

#### 执行流程
```
FeedInput
    │
    ├─ 1. 解析/获取 StateToken
    │      └─ resolveStateTokenFromInput() (如果是 AccountRecovery)
    │
    ├─ 2. 从 Store 获取 Flow
    │      └─ s.Store.GetFlowByStateToken(ctx, stateToken)
    │
    ├─ 3. 获取 Session 并更新 Context
    │      └─ getSessionAndUpdateContext()
    │         └─ session.MakeContext(ctx, s.Deps) 注入会话信息
    │
    ├─ 4. 调用核心 feedInput
    │      └─ s.feedInput(ctx, session, stateToken, rawMessage)
    │
    ├─ 5. 处理特殊错误 (switchFlow/rewriteFlow)
    │      └─ 如果是账户关联场景，可能需要切换 Flow
    │
    └─ 6. 处理 EOF (流程完成)
           ├─ finishFlow() 应用所有 Effect
           ├─ DeleteSession() 清理会话
           └─ DeleteFlow() 清理流程
```

---

### 阶段 2：核心执行引擎 (feedInput)

```go
// pkg/lib/authenticationflow/service.go:485
func (s *Service) feedInput(ctx context.Context, session *Session, stateToken string, rawMessage json.RawMessage) (flow *Flow, flowAction *FlowAction, err error)
```

#### 执行循环
```go
var shouldAccept = true
for shouldAccept {
    shouldAccept = false
    var acceptResult *AcceptResult = NewAcceptResult()
    flows := NewFlows(flow)
    
    err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
        // 1. 应用之前积累的 RunEffects
        err = ApplyRunEffects(ctx, s.Deps, flows)
        
        // 2. 核心：执行 Accept 循环
        err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
        
        // 3. 获取当前 FlowAction (下一步要做什么)
        flowAction, err = s.getFlowAction(ctx, session, flow)
        
        if isEOF { return ErrEOF }
        return nil
    })
    
    // 4. 处理 AcceptResult (如 BotProtection 验证结果)
    acceptErr := s.processAcceptResult(ctx, session, flows, acceptResult)
    
    // 5. 如果需要暂停重试，继续循环
    if errors.Is(err, ErrPauseAndRetryAccept) {
        shouldAccept = true
        err = nil
    }
}
```

---

### 阶段 3：Accept 执行核心

```go
// pkg/lib/authenticationflow/accept.go:39
func Accept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, rawMessage json.RawMessage) error
```

#### 核心执行循环 (doAccept)

```
doAccept 循环 (最多 100 次迭代)
    │
    ├─ 1. FindInputReactor() - 查找可以接收输入的组件
    │      ├─ 检查最后一个 Node 是否可以响应
    │      └─ 检查 Intent 是否可以响应
    │
    ├─ 2. 解析 Input (通过 InputSchema.MakeInput)
    │      └─ 将原始 JSON 转换为具体的 Input 结构体
    │
    ├─ 3. 调用 ReactTo()
    │      └─ inputReactor.ReactTo(ctx, deps, flows, input)
    │         返回 ReactToResult (新的 Node 或 SubFlow)
    │
    ├─ 4. 处理 ReactTo 结果
    │      ├─ ErrIncompatibleInput → 跳过
    │      ├─ ErrSameNode → 标记 changed，停止
    │      ├─ ErrReplaceNode → 替换当前节点
    │      ├─ 返回 Node → appendNode()
    │      └─ 返回 SubFlow → 创建嵌套流程
    │
    └─ 5. 更新 StateToken (如果 changed)
           └─ flows.Nearest.StateToken = newStateToken()
```

---

### 阶段 4：FindInputReactor 详解

```go
// pkg/lib/authenticationflow/input.go:78
func FindInputReactor(ctx context.Context, deps *Dependencies, flows Flows) (reactor *FindInputReactorResult, err error)
```

#### 查找策略
```
FindInputReactor
    │
    └─ FindInputReactorForFlow
         │
         ├─ 1. 检查最后一个 Node
         │      │
         │      ├─ NodeTypeSimple:
         │      │   如果 Node 实现了 InputReactor，调用 CanReactTo()
         │      │
         │      └─ NodeTypeSubFlow:
         │          递归进入子流程 FindInputReactorForFlow(subFlow)
         │
         └─ 2. 检查 Intent
                └─ flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
```

---

## 三、Login Flow 关键类构建过程

### 1. IntentLoginFlow - 流程入口

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow.go

type IntentLoginFlow struct {
    TargetUserID  string                 `json:"target_user_id,omitempty"`  // 用于 Reauth 等场景
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`  // 指向配置
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`    // 当前位置
}

// 构建过程
func init() {
    authflow.RegisterFlow(&IntentLoginFlow{})  // 注册到 Flow Registry
}

// 生命周期
func (i *IntentLoginFlow) CanReactTo(...) {
    // 检查是否已完成 (通过查找 MilestoneDoCreateSession)
    _, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateSession](flows)
    if ok { return nil, authflow.ErrEOF }
    return nil, nil
}

func (i *IntentLoginFlow) ReactTo(...) {
    switch {
    case len(flows.Nearest.Nodes) == 0:
        // 第 1 步：初始化
        return NewNodePreInitialize(...)
    case len(flows.Nearest.Nodes) == 1:
        // 第 2 步：启动子流程 IntentLoginFlowSteps
        return authflow.NewSubFlow(&IntentLoginFlowSteps{...})
    case len(flows.Nearest.Nodes) == 2:
        // 第 3 步：创建会话
        return NewNodeDoCreateSession(...)
    }
}
```

---

### 2. IntentLoginFlowSteps - 步骤管理器

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go

type IntentLoginFlowSteps struct {
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`
    NextStepIndex int                     `json:"next_step_index"`  // 当前步骤索引
}

// 构建过程
func NewIntentLoginFlowSteps(...) {
    return &IntentLoginFlowSteps{
        FlowReference: flowReference,
        JSONPointer:   jsonpointer.T{},  // 根指针
        NextStepIndex: 0,
    }
}

// 步骤路由
func (i *IntentLoginFlowSteps) ReactTo(...) {
    step := steps[i.NextStepIndex].(*config.AuthenticationFlowLoginFlowStep)
    
    switch step.Type {
    case config.AuthenticationFlowLoginFlowStepTypeIdentify:
        // 创建 IntentLoginFlowStepIdentify
        result = authflow.NewSubFlow(NewIntentLoginFlowStepIdentify(...))
        
    case config.AuthenticationFlowLoginFlowStepTypeAuthenticate:
        // 创建 IntentLoginFlowStepAuthenticate
        result = authflow.NewSubFlow(NewIntentLoginFlowStepAuthenticate(...))
        
    case config.AuthenticationFlowLoginFlowStepTypeCheckAccountStatus:
        result = authflow.NewSubFlow(&IntentLoginFlowStepCheckAccountStatus{...})
        
    // ... 其他步骤类型
    }
    
    i.NextStepIndex++  // 关键：移动到下一位
    return result, nil
}
```

---

### 3. IntentLoginFlowStepIdentify - 身份识别步骤

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go

type IntentLoginFlowStepIdentify struct {
    FlowReference authflow.FlowReference      `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T               `json:"json_pointer,omitempty"`
    StepName      string                      `json:"step_name,omitempty"`
    Options       []IdentificationOption      `json:"options"`  // 可用识别选项
}

// 构建过程 (在 IntentLoginFlowSteps 中创建)
func NewIntentLoginFlowStepIdentify(ctx, deps, flows, i, originNode) {
    // 1. 获取当前 Flow 配置对象
    current, _ := i.currentFlowObject(deps, flows, originNode)
    step := i.step(current)
    
    // 2. 构建识别选项
    options := []IdentificationOption{}
    for _, b := range step.OneOf {
        switch b.Identification {
        case model.AuthenticationFlowIdentificationEmail:
            options = append(options, NewIdentificationOptionLoginID(...))
        case model.AuthenticationFlowIdentificationOAuth:
            options = append(options, NewIdentificationOptionsOAuth(...))
        case model.AuthenticationFlowIdentificationPasskey:
            options = append(options, NewIdentificationOptionPasskey(...))
        // ... 其他识别方式
        }
    }
    
    i.Options = options
    return i, nil
}

// 执行流程
func (i *IntentLoginFlowStepIdentify) ReactTo(...) {
    switch {
    case len(flows.Nearest.Nodes) == 0:
        // 等待用户选择识别方式
        var inputTakeIdentificationMethod inputTakeIdentificationMethod
        if authflow.AsInput(input, &inputTakeIdentificationMethod) {
            identification := inputTakeIdentificationMethod.GetIdentificationMethod()
            
            // 根据选择创建对应的 Intent
            switch identification {
            case model.AuthenticationFlowIdentificationEmail:
                return authflow.NewSubFlow(&IntentUseIdentityLoginID{...})
            case model.AuthenticationFlowIdentificationOAuth:
                return authflow.NewSubFlow(&IntentOAuth{...})
            case model.AuthenticationFlowIdentificationPasskey:
                return authflow.NewSubFlow(&IntentUseIdentityPasskey{...})
            // ...
            }
        }
        
    case identityUsed && !loginHintChecked:
        // 检查 login_hint
        return authflow.NewNodeSimple(NewNodeCheckLoginHint(...))
        
    case identityUsed && !nestedStepsHandled:
        // 处理嵌套步骤
        return authflow.NewSubFlow(&IntentLoginFlowSteps{...})
    }
}
```

---

### 4. IntentLoginFlowStepAuthenticate - 认证步骤

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_authenticate.go

type IntentLoginFlowStepAuthenticate struct {
    FlowReference                  authflow.FlowReference    `json:"flow_reference,omitempty"`
    JSONPointer                    jsonpointer.T             `json:"json_pointer,omitempty"`
    StepName                       string                    `json:"step_name,omitempty"`
    UserID                         string                    `json:"user_id,omitempty"`
    Options                        []AuthenticateOption      `json:"options"`
    DeviceTokenEnabled             bool                      `json:"device_token_enabled"`
    DidAuthenticatedBeforeThisStep bool                      `json:"did_authenticated_before_this_step"`
}

// 构建过程
func NewIntentLoginFlowStepAuthenticate(ctx, deps, flows, i, originNode) {
    // 1. 获取认证选项
    options, deviceTokenEnabled, _ := getAuthenticationOptionsForLogin(ctx, deps, flows, i.UserID, step)
    i.Options = options
    i.DeviceTokenEnabled = deviceTokenEnabled
    
    // 2. 检查之前是否已经认证过
    didAuthenticatedMilestones := authflow.FindAllMilestones[MilestoneDidAuthenticate](flows.Root)
    i.DidAuthenticatedBeforeThisStep = len(didAuthenticatedMilestones) > 0
    
    return i, nil
}

// 执行流程 (状态机)
func (i *IntentLoginFlowStepAuthenticate) ReactTo(...) {
    // 检查各种 Milestone 状态
    _, _, deviceTokenInspected := authflow.FindMilestoneInCurrentFlow[MilestoneDeviceTokenInspected](flows)
    _, _, authenticationMethodSelected := authflow.FindMilestoneInCurrentFlow[MilestoneFlowSelectAuthenticationMethod](flows)
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneFlowAuthenticate](flows)
    _, _, deviceTokenCreatedIfRequested := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateDeviceTokenIfRequested](flows)
    _, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)
    
    switch {
    case i.DeviceTokenEnabled && !deviceTokenInspected:
        // 检查设备令牌
        return authflow.NewSubFlow(&IntentInspectDeviceToken{...})
        
    case !authenticationMethodSelected:
        // 等待用户选择认证方式
        if len(i.Options) == 0 {
            // 没有可用选项，尝试创建认证器
            return authflow.NewSubFlow(&IntentLoginFlowStepCreateAuthenticator{...})
        }
        // 返回选项让用户选择
        
    case !authenticated:
        // 根据选择的认证方式创建对应 Intent
        switch authentication {
        case model.AuthenticationFlowAuthenticationPrimaryPassword:
            return authflow.NewSubFlow(&IntentUseAuthenticatorPassword{...})
        case model.AuthenticationFlowAuthenticationPrimaryPasskey:
            return authflow.NewSubFlow(&IntentUseAuthenticatorPasskey{...})
        case model.AuthenticationFlowAuthenticationPrimaryOOBOTPEmail:
            return authflow.NewSubFlow(&IntentUseAuthenticatorOOBOTP{...})
        // ...
        }
        
    case i.DeviceTokenEnabled && !deviceTokenCreatedIfRequested:
        // 创建设备令牌
        return authflow.NewSubFlow(&IntentCreateDeviceTokenIfRequested{...})
        
    case !nestedStepsHandled:
        // 处理嵌套步骤
        return authflow.NewSubFlow(&IntentLoginFlowSteps{...})
        
    default:
        return nil, authflow.ErrEOF  // 步骤完成
    }
}
```

---

### 5. NodeDoCreateSession - 会话创建节点

```go
// pkg/lib/authenticationflow/declarative/node_do_create_session.go

type NodeDoCreateSession struct {
    UserID       string               `json:"user_id"`
    CreateReason session.CreateReason `json:"create_reason"`  // "login", "signup", "reauth"
    SkipCreate   bool                 `json:"skip_create"`     // 是否跳过创建 (如 Biometric)
    
    Session                 *idpsession.IDPSession    `json:"session,omitempty"`
    SessionCookie           *http.Cookie              `json:"session_cookie,omitempty"`
    AuthenticationInfoEntry *authenticationinfo.Entry `json:"authentication_info_entry,omitempty"`
}

// 构建过程
func NewNodeDoCreateSession(ctx, deps, flows, n) {
    // 1. 收集 AMR (认证方法引用)
    amr, _ := CollectAMR(ctx, deps, flows)
    
    // 2. 收集身份信息
    identitySpecs, _ := collectIdentitySpecs(ctx, deps, flows)
    
    // 3. 构建认证信息
    authnInfo := authenticationinfo.T{
        UserID:          n.UserID,
        AuthenticatedAt: deps.Clock.NowUTC(),
        AMR:             amr,
        IdentitySpecs:   identitySpecs,
    }
    
    // 4. 创建会话
    if !n.SkipCreate {
        attrs := session.NewAttrs(n.UserID)
        attrs.SetAMR(amr)
        s, token := deps.IDPSessions.MakeSession(attrs)
        n.Session = s
        n.SessionCookie = deps.Cookies.ValueCookie(deps.SessionCookie.Def, token)
    }
    
    // 5. 创建认证信息条目
    n.AuthenticationInfoEntry = authenticationinfo.NewEntry(authnInfo, ...)
    
    return n, nil
}

// Effect 应用
func (n *NodeDoCreateSession) GetEffects(...) {
    return []authflow.Effect{
        // 保存认证信息
        authflow.OnCommitEffect(func(ctx context.Context, deps *authflow.Dependencies) error {
            return deps.AuthenticationInfos.Save(ctx, n.AuthenticationInfoEntry)
        }),
        // 创建 IDP 会话
        authflow.OnCommitEffect(func(ctx context.Context, deps *authflow.Dependencies) error {
            if n.Session == nil { return nil }
            return deps.IDPSessions.Create(ctx, n.Session)
        }),
    }, nil
}
```

---

## 四、类调用关系图

### 完整调用链

```
HTTP Request
    │
    ▼
service.FeedInput(stateToken, rawInput)
    │
    ├─ Store.GetFlowByStateToken() → Flow
    │
    ├─ getSessionAndUpdateContext() → Session + Context
    │
    ▼
service.feedInput()
    │
    ├─ NewFlows(flow) → Flows{Root: flow, Nearest: flow}
    │
    ├─ Database.ReadOnly() {
    │       │
    │       ├─ ApplyRunEffects(ctx, deps, flows)
    │       │   └─ 遍历所有 Node，应用 RunEffect
    │       │
    │       └─ Accept(ctx, deps, flows, acceptResult, rawInput)
    │           │
    │           ├─ FindInputReactor(ctx, deps, flows)
    │           │   ├─ 检查最后一个 Node (CanReactTo)
    │           │   └─ 检查 Intent (CanReactTo)
    │           │       └─ 返回 InputReactor + InputSchema
    │           │
    │           ├─ inputSchema.MakeInput(rawInput) → Input
    │           │
    │           └─ inputReactor.ReactTo(ctx, deps, flows, input)
    │               │
    │               ├─ IntentLoginFlow.ReactTo()
    │               │   ├─ Node 0: NodePreInitialize
    │               │   ├─ Node 1: SubFlow(IntentLoginFlowSteps)
    │               │   └─ Node 2: NodeDoCreateSession
    │               │
    │               ├─ IntentLoginFlowSteps.ReactTo()
    │               │   ├─ SubFlow(IntentLoginFlowStepIdentify)
    │               │   ├─ SubFlow(IntentLoginFlowStepAuthenticate)
    │               │   └─ ... 其他步骤
    │               │
    │               ├─ IntentLoginFlowStepIdentify.ReactTo()
    │               │   ├─ SubFlow(IntentUseIdentityLoginID/IntentOAuth/...)
    │               │   ├─ Node(NodeCheckLoginHint)
    │               │   └─ SubFlow(IntentLoginFlowSteps) 嵌套步骤
    │               │
    │               └─ IntentLoginFlowStepAuthenticate.ReactTo()
    │                   ├─ SubFlow(IntentInspectDeviceToken)
    │                   ├─ SubFlow(IntentUseAuthenticatorPassword/Passkey/...)
    │                   ├─ SubFlow(IntentCreateDeviceTokenIfRequested)
    │                   └─ SubFlow(IntentLoginFlowSteps) 嵌套步骤
    │
    ├─ processAcceptResult() 处理 BotProtection 等
    │
    └─ Store.CreateFlow() 持久化 Flow 状态
        │
        ▼
    返回 ServiceOutput {
        Session: session,
        Flow: flow,
        FlowAction: flowAction,  // 下一步操作
        Cookies: cookies,        // 会话 Cookie
    }
```

---

### Milestone 依赖关系

```
IntentLoginFlow
    └─ 依赖 MilestoneDoCreateSession (检查是否完成)

IntentLoginFlowSteps
    └─ 依赖 MilestoneNestedSteps (标记步骤完成)

IntentLoginFlowStepIdentify
    ├─ 依赖 MilestoneFlowUseIdentity (检查身份是否已使用)
    ├─ 依赖 MilestoneCheckLoginHint (检查 login_hint)
    └─ 依赖 MilestoneNestedSteps (检查嵌套步骤)

IntentLoginFlowStepAuthenticate
    ├─ 依赖 MilestoneDeviceTokenInspected
    ├─ 依赖 MilestoneFlowSelectAuthenticationMethod (选择认证方式)
    ├─ 依赖 MilestoneFlowAuthenticate (执行认证)
    ├─ 依赖 MilestoneDidAuthenticate (已认证)
    ├─ 依赖 MilestoneDoCreateDeviceTokenIfRequested
    └─ 依赖 MilestoneNestedSteps

NodeDoCreateSession
    └─ 实现 MilestoneDoCreateSession
```

---

## 五、关键数据结构

### Flow 结构
```go
type Flow struct {
    FlowID     string   // 流程唯一 ID
    StateToken string   // 状态令牌 (每次更新都会重新生成)
    Intent     Intent   // 根意图 (如 IntentLoginFlow)
    Nodes      []Node   // 已执行的节点列表
}
```

### Flows 上下文
```go
type Flows struct {
    Root    *Flow  // 根流程
    Nearest *Flow  // 当前正在执行的流程 (支持嵌套)
}
```

### Node 结构
```go
type Node struct {
    Type    NodeType   // SIMPLE 或 SUB_FLOW
    Simple  NodeSimple // 简单节点 (如 NodeDoCreateSession)
    SubFlow *Flow      // 子流程 (如 IntentLoginFlowSteps 作为子流程)
}
```

### ReactToResult 接口
```go
type ReactToResult interface {
    reactToResult()  // 标记接口
}

// 实现:
// - *Node (通过 NewNodeSimple/NewSubFlow 创建)
// - *NodeWithDelayedOneTimeFunction
```

---

## 六、特殊场景处理

### 1. 账户关联 (Account Linking)

```
IntentOAuth (第三方登录)
    │
    ├─ 发现身份冲突
    │   └─ 返回 ErrAccountLinking
    │
    ▼
IntentAccountLinking (切换流程)
    │
    ├─ 显示冲突身份选项
    │
    ├─ 用户选择身份
    │   └─ 需要验证
    │
    └─ 启动 Login Flow 验证身份
        │
        └─ 验证成功后合并身份
```

### 2. Flow 切换 (Switch Flow)

```go
// 通过 ErrSwitchFlow 触发
err = &ErrorSwitchFlow{
    FlowReference: authflow.FlowReference{
        Type: authflow.FlowTypeLogin,
        Name: loginFlow,
    },
    SyntheticInput: input,  // 自动填充的输入
}

// Service 处理
if errors.As(err, &errSwitchFlow) {
    output, err = s.switchFlow(ctx, session, errSwitchFlow)
}
```

### 3. Flow 重写 (Rewrite Flow)

```go
// 通过 ErrRewriteFlow 触发
err = &ErrorRewriteFlow{
    Intent:         newIntent,
    Nodes:          newNodes,
    SyntheticInput: input,
}

// Service 处理
if errors.As(err, &errRewriteFlow) {
    output, err = s.rewriteFlow(ctx, session, errRewriteFlow)
}
```

---

## 七、效果应用机制

### Effect 类型

```go
// 立即执行 (在 ReadOnly 事务中)
type RunEffect interface {
    Effect
    doNotCallThisDirectly(ctx context.Context, deps *Dependencies) error
}

// 提交时执行 (在写事务中)
type OnCommitEffect interface {
    Effect
    doNotCallThisDirectly(ctx context.Context, deps *Dependencies) error
}
```

### Effect 应用时机

```
1. ApplyRunEffects (每次 Accept 前)
   └─ 遍历所有 Node，执行 RunEffect
   
2. ApplyAllEffects (流程完成时 finishFlow)
   ├─ RunEffect (再次执行)
   └─ OnCommitEffect (在写事务中执行)
   
3. CollectCookies (流程完成时)
   └─ 遍历所有 Node，收集 CookieGetter 提供的 Cookie
```

---

## 八、完整登录流程示例

### 场景：用户使用邮箱+密码登录

#### Step 1: 创建流程
```
HTTP POST /api/v1/authentication_flow

请求体:
{
    "flow_type": "login",
    "flow_name": "default"
}

响应:
{
    "state_token": "st_abc123",
    "type": "login",
    "name": "default",
    "action": {
        "type": "identify",
        "data": {
            "options": [
                {"identification": "email"},
                {"identification": "oauth", "providers": [...]}
            ]
        }
    }
}
```

**内部执行：**
```go
// 1. HTTP Handler 调用
flowRef := authflow.FlowReference{Type: "login", Name: "default"}
publicFlow, _ := authflow.InstantiateFlow(flowRef, jsonpointer.T{})
// publicFlow = &IntentLoginFlow{FlowReference: flowRef}

// 2. Service 创建流程
output, _ := service.CreateNewFlow(ctx, publicFlow, sessionOptions)

// 3. 内部状态
Flow{
    FlowID: "flow-uuid-1",
    StateToken: "st_abc123",
    Intent: &IntentLoginFlow{
        FlowReference: {Type: "login", Name: "default"},
        JSONPointer: {},
    },
    Nodes: [
        Node{Type: "SIMPLE", Simple: &NodePreInitialize{}},
        Node{
            Type: "SUB_FLOW",
            SubFlow: &Flow{
                Intent: &IntentLoginFlowSteps{NextStepIndex: 0},
                Nodes: [],
            }
        }
    ],
}
```

---

#### Step 2: 用户选择邮箱识别
```
HTTP POST /api/v1/authentication_flow

请求体:
{
    "state_token": "st_abc123",
    "identification": "email"
}

响应:
{
    "state_token": "st_def456",  // 新 token
    "type": "login",
    "action": {
        "type": "identify",
        "identification": "email",
        "data": {
            "email": "user@example.com"  // 等待输入
        }
    }
}
```

**内部执行：**
```go
// 1. Service 处理输入
output, _ := service.FeedInput(ctx, "st_abc123", rawInput)
// rawInput = {"identification": "email"}

// 2. FindInputReactor 找到 IntentLoginFlowStepIdentify
//    它返回 InputSchema 期望 inputTakeIdentificationMethod

// 3. IntentLoginFlowStepIdentify.ReactTo()
//    检查 flows.Nearest.Nodes == 0
//    识别出 identification = "email"
//    返回 SubFlow(&IntentUseIdentityLoginID{...})

// 4. Accept 添加新 Node
Flow{
    ...
    Nodes: [
        ...,  // 之前的节点
        Node{
            Type: "SUB_FLOW",
            SubFlow: &Flow{
                Intent: &IntentUseIdentityLoginID{
                    JSONPointer: "/steps/0/one_of/0",
                    Identification: "email",
                },
                Nodes: [],
            }
        }
    ],
}
```

---

#### Step 3: 用户输入邮箱
```
HTTP POST /api/v1/authentication_flow

请求体:
{
    "state_token": "st_def456",
    "login_id": "user@example.com"
}

响应:
{
    "state_token": "st_ghi789",
    "type": "login",
    "action": {
        "type": "authenticate",
        "data": {
            "options": [
                {"authentication": "primary_password"},
                {"authentication": "primary_oob_otp_email"}
            ]
        }
    }
}
```

**内部执行：**
```go
// 1. IntentUseIdentityLoginID.ReactTo()
//    创建 NodeDoUseIdentity 记录用户身份
//    触发 MilestoneFlowUseIdentity

// 2. 返回上级 IntentLoginFlowStepIdentify
//    发现 identityUsed = true, loginHintChecked = false
//    创建 NodeCheckLoginHint

// 3. 继续执行到 IntentLoginFlowSteps
//    NextStepIndex = 1 (进入 authenticate 步骤)
//    创建 SubFlow(&IntentLoginFlowStepAuthenticate{...})

// 4. IntentLoginFlowStepAuthenticate.CanReactTo()
//    发现 authenticationMethodSelected = false
//    返回 InputSchema 要求用户选择认证方式
```

---

#### Step 4: 用户选择密码认证并输入
```
HTTP POST /api/v1/authentication_flow

请求体:
{
    "state_token": "st_ghi789",
    "authentication": "primary_password",
    "password": "user_password"
}

响应:
{
    "state_token": "st_jkl012",
    "type": "login",
    "action": {
        "type": "finished",
        "data": {
            "finish_redirect_uri": "/settings"
        }
    },
    // 同时 Set-Cookie: session=xxx
}
```

**内部执行：**
```go
// 1. IntentLoginFlowStepAuthenticate.ReactTo()
//    authenticationMethodSelected = true
//    根据 authentication 创建 IntentUseAuthenticatorPassword

// 2. IntentUseAuthenticatorPassword.ReactTo()
//    验证密码成功
//    创建 NodeDoUseAuthenticatorPassword
//    触发 MilestoneDidAuthenticate

// 3. 回到 IntentLoginFlow
//    发现 len(Nodes) == 2 (PreInitialize + Steps)
//    创建 NodeDoCreateSession

// 4. NodeDoCreateSession 执行
//    - 收集 AMR: ["pwd"]
//    - 创建 IDPSession
//    - 生成 Session Cookie
//    - 触发 MilestoneDoCreateSession

// 5. IntentLoginFlow.CanReactTo()
//    发现 MilestoneDoCreateSession 存在
//    返回 ErrEOF (流程完成)

// 6. Service.finishFlow()
//    - ApplyAllEffects() 保存会话到数据库
//    - CollectCookies() 返回 Cookie
//    - DeleteSession() 清理临时会话
//    - DeleteFlow() 清理流程状态
```

---

## 九、错误处理机制

### 错误类型层级

```
┌─────────────────────────────────────────────────────────────────┐
│                     业务错误 (APIError)                         │
├─────────────────────────────────────────────────────────────────┤
│ ErrInvalidCredentials     │ 密码错误                           │
│ ErrUserNotFound           │ 用户不存在                         │
│ ErrNoAuthenticator          │ 没有可用的认证方式                  │
│ lockout.ErrAccountLockout │ 账户被锁定                         │
│ botprotection.Err...      │ 人机验证失败                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     流程控制错误                                 │
├─────────────────────────────────────────────────────────────────┤
│ ErrEOF                    │ 流程正常结束                       │
│ ErrNoChange               │ 输入未导致状态变化                 │
│ ErrIncompatibleInput      │ 输入与期望不匹配                   │
│ ErrSameNode               │ 节点需要保持但标记变化              │
│ ErrReplaceNode            │ 需要替换当前节点                   │
│ ErrPauseAndRetryAccept    │ 暂停后重试 Accept                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     特殊流程错误                                 │
├─────────────────────────────────────────────────────────────────┤
│ ErrorSwitchFlow           │ 需要切换到另一个 Flow              │
│ ErrorRewriteFlow          │ 需要重写当前 Flow                  │
│ ErrorAccountLinking       │ 触发账户关联流程                   │
│ ErrorBotProtectionVerification │ 需要人机验证                    │
└─────────────────────────────────────────────────────────────────┘
```

### 错误处理流程

```go
// doAccept 中的错误处理
func doAccept(...) {
    for {
        findInputReactorResult, err := FindInputReactor(...)
        if err != nil { return }  // 无法找到响应者，返回错误
        
        input, err := inputFn(...)
        if err != nil { return }  // 输入解析失败
        
        reactToResult, err := inputReactor.ReactTo(...)
        
        // 1. ErrIncompatibleInput - 跳过这个响应者
        if errors.Is(err, ErrIncompatibleInput) {
            err = nil
            return  // 停止循环，等待新的输入
        }
        
        // 2. ErrSameNode - 标记变化但停止
        if errors.Is(err, ErrSameNode) {
            err = nil
            changed = true
            return  // 停止循环，防止无限响应
        }
        
        // 3. ErrReplaceNode - 替换节点
        if errors.Is(err, ErrReplaceNode) {
            err = nil
            changed = true
            // 替换最后一个节点
            flows.Nearest.Nodes[len-1] = *nodeToReplace
            return
        }
        
        // 4. 其他错误 - 包装后返回
        if err != nil {
            err = newAuthenticationFlowError(flows, err)
            return
        }
        
        // 成功：添加新节点
        appendNode(...)
        changed = true
    }
}
```

### Service 层错误处理

```go
func (s *Service) FeedInput(...) {
    flow, flowAction, err := s.feedInput(...)
    
    // 处理特殊流程切换
    var errSwitchFlow *ErrorSwitchFlow
    var errRewriteFlow *ErrorRewriteFlow
    
    for errors.As(err, &errSwitchFlow) || errors.As(err, &errRewriteFlow) {
        if errors.As(err, &errSwitchFlow) {
            // 切换到新 Flow (如从 Signup 切换到 Login)
            output, err = s.switchFlow(ctx, session, errSwitchFlow)
        }
        if errors.As(err, &errRewriteFlow) {
            // 重写当前 Flow (如账户关联时修改用户ID)
            output, err = s.rewriteFlow(ctx, session, errRewriteFlow)
        }
    }
    
    // EOF 表示流程完成
    isEOF := errors.Is(err, ErrEOF)
    if isEOF {
        // 应用所有 Effect，清理状态
        cookies, _ := s.finishFlow(ctx, flow)
        s.Store.DeleteSession(ctx, session)
        s.Store.DeleteFlow(ctx, flow)
    }
    
    return output, err
}
```

---

## 十、状态持久化与存储

### 存储模型

```go
// Session 存储临时会话信息
type Session struct {
    FlowID      string
    CreatedAt   time.Time
    ExpireAt    time.Time
    
    // 上下文信息
    RedirectURI      string
    ClientID         string
    SuppressIDPSessionCookie bool
    
    // BotProtection 验证结果
    BotProtectionVerificationResult *BotProtectionVerificationResult
}

// Flow 存储流程状态
type Flow struct {
    FlowID     string
    StateToken string  // 作为查询键
    Intent     Intent  // JSON 序列化存储
    Nodes      []Node  // JSON 序列化存储
}
```

### 存储接口

```go
type Store interface {
    // Session 操作
    CreateSession(ctx context.Context, session *Session) error
    GetSession(ctx context.Context, flowID string) (*Session, error)
    DeleteSession(ctx context.Context, session *Session) error
    UpdateSession(ctx context.Context, session *Session) error
    
    // Flow 操作
    CreateFlow(ctx context.Context, flow *Flow) error
    GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error)
    DeleteFlow(ctx context.Context, flow *Flow) error
}
```

### 持久化流程

```
1. CreateNewFlow
   ├─ CreateSession()      → 写入 Redis/DB
   └─ CreateFlow()         → 写入 Redis/DB
   
2. FeedInput (循环)
   ├─ GetFlowByStateToken() → 读取 Flow
   ├─ GetSession()         → 读取 Session
   ├─ Accept()             → 修改 Flow 内存状态
   ├─ CreateFlow()         → 保存新状态 (覆盖写入)
   └─ UpdateSession()      → 如有必要
   
3. 流程完成 (EOF)
   ├─ finishFlow()
   │   ├─ ApplyAllEffects() → 数据库事务写入
   │   └─ CollectCookies()
   ├─ DeleteSession()        → 删除临时会话
   └─ DeleteFlow()           → 删除流程状态
```

---

## 十一、并发安全机制

### StateToken 防并发

```go
// 每次状态变更都生成新的 StateToken
func doAccept(...) {
    defer func() {
        if changed {
            // 生成新的 StateToken
            flows.Nearest.StateToken = newStateToken()
        }
    }()
    
    for {
        // 执行循环...
        changed = true  // 当添加节点时
    }
}
```

**并发场景处理：**
```
客户端 A                    客户端 B
   │                          │
   ├─ 获取 st_001 ────────────┤
   │                          │
   ├─ FeedInput(st_001, ...)   │
   │   └─ 生成 st_002          │
   │                          │
   │                          ├─ FeedInput(st_001, ...) ← 过期 token
   │                          │   └─ 返回 ErrFlowNotFound
   │                          │
   ├─ FeedInput(st_002, ...)   │
   │   └─ 生成 st_003          │
```

### 数据库事务隔离

```go
func (s *Service) feedInput(...) {
    // 读操作在 ReadOnly 事务中
    err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
        // 应用之前积累的 RunEffect
        err = ApplyRunEffects(ctx, s.Deps, flows)
        
        // 执行 Accept (纯内存操作)
        err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
        
        return err
    })
    
    // 写操作单独执行
    err = s.Store.CreateFlow(ctx, flow)  // 覆盖写入
}

func (s *Service) finishFlow(...) {
    // 最终效果在写事务中执行
    err = s.Database.WithTx(ctx, func(ctx context.Context) error {
        // Apply OnCommitEffect
        err = ApplyAllEffects(ctx, s.Deps, NewFlows(flow))
        return err
    })
}
```

---

## 十二、总结

### 核心设计模式

1. **递归下降执行**：通过 `FindInputReactor` 递归查找可响应的组件
2. **Milestone 状态追踪**：通过 Milestone 接口查询执行状态
3. **Effect 副作用隔离**：区分立即执行和提交时执行的效果
4. **StateToken 状态管理**：每次变更生成新的 StateToken 防止并发问题
5. **SubFlow 嵌套**：支持步骤内的嵌套子流程

### 完整执行流程口诀

```
HTTP请求 → InstantiateFlow → CreateNewFlow → Accept循环 → FeedInput循环 → finishFlow
              │                  │              │             │             │
              │                  │              │             │             └─ 提交事务
              │                  │              │             │                清理状态
              │                  │              │             │
              │                  │              │             └─ 查找响应者
              │                  │              │                解析输入
              │                  │              │                ReactTo执行
              │                  │              │                保存状态
              │                  │              │
              │                  │              └─ 查找Intent/Node
              │                  │                 添加Node
              │                  │                 应用RunEffect
              │                  │
              │                  └─ 创建Session
              │                     创建Flow
              │                     启动Accept
              │
              └─ Registry查找
                 反射创建
                 FlowInit初始化
```

### 关键扩展点

| 扩展点 | 接口 | 用途 |
|--------|------|------|
| 新的识别方式 | `Intent` + `MilestoneFlowUseIdentity` | 添加新的登录方式 |
| 新的认证方式 | `Intent` + `MilestoneFlowAuthenticate` | 添加新的验证方式 |
| 新的步骤类型 | 实现 `InputReactor` | 自定义流程步骤 |
| 副作用处理 | `EffectGetter` | 在节点中添加副作用 |
| 数据输出 | `DataOutputer` | 向客户端返回数据 |

---

*文档生成时间：2026-05-18*
*基于 Authgear Server Login Flow 代码分析*
