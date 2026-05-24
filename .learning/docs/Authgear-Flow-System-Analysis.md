# Authgear Flow 系统深入分析

## 1. Flow 系统概述

Flow 系统是 Authgear 的核心认证流程引擎，负责协调和管理用户认证过程的各个步骤。它基于 Intent-Node 架构，支持声明式的流程配置。

### 1.1 支持的 Flow 类型

```go
// pkg/lib/authenticationflow/flow.go
type FlowType string

const (
    FlowTypeSignup          FlowType = "signup"           // 注册流程
    FlowTypePromote         FlowType = "promote"          // 身份提升流程
    FlowTypeLogin           FlowType = "login"            // 登录流程
    FlowTypeSignupLogin     FlowType = "signup_login"     // 注册/登录统一流程
    FlowTypeReauth          FlowType = "reauth"           // 重新认证流程
    FlowTypeAccountRecovery FlowType = "account_recovery" // 账户恢复流程
)
```

### 1.2 Flow 核心概念

- **Intent**：流程意图，表示一个需要完成的动作或目标
- **Node**：流程节点，表示流程执行过程中的一个状态或操作点
- **Milestone**：里程碑，标记流程中的关键完成点
- **Input**：用户输入，驱动流程状态转换
- **Step**：配置中的步骤，定义流程的阶段

## 2. Flow 系统架构

### 2.1 Intent-Node 架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                               Intent                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                         ReactTo 循环                                   │  │
│  │  ┌─────────────┐     CanReactTo      ┌─────────────┐     Input       │  │
│  │  │             │ ◄────────────────── │             │ ◄──────────── │  │
│  │  │   Intent    │                     │    Node     │                │  │
│  │  │             │ ──────────────────► │             │                │  │
│  │  └─────────────┘    ReactTo          └─────────────┘                │  │
│  │         │                                                           │  │
│  │         ▼                                                           │  │
│  │    SubFlow/NextIntent                                                │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件关系

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Flow 运行时层                                        │
│                    (pkg/lib/authenticationflow)                             │
│                                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐   │
│  │   Flow      │  │   Intent    │  │    Node     │  │    Milestone    │   │
│  │  (流程实例)  │  │   (意图)    │  │   (节点)    │  │    (里程碑)     │   │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────┘   │
│         │                │               │                  │             │
└─────────┼────────────────┼───────────────┼──────────────────┼─────────────┘
          │                │               │                  │
          ▼                ▼               ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Flow 声明式层                                        │
│              (pkg/lib/authenticationflow/declarative)                         │
│                                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐    │
│  │  IntentLoginFlow │  │ IntentSignupFlow │  │ IntentAccountRecovery  │    │
│  │  IntentReauthFlow│  │ IntentPromoteFlow│  │ ...                    │    │
│  └──────────────────┘  └──────────────────┘  └──────────────────────────┘    │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                     Step Intents                                       │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │  │
│  │  │StepIdentify  │ │StepAuthenticate│ │StepCreate   │ │StepVerify    │ │  │
│  │  │              │ │              │ │Authenticator│ │              │ │  │
│  │  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 3. Intent 系统详解

### 3.1 Intent 接口定义

```go
// pkg/lib/authenticationflow/intent.go
type Intent interface {
    Kinder           // Kind() string - 类型标识
    InputReactor     // CanReactTo/ReactTo - 输入处理
}

// 扩展接口
type EffectGetter interface {
    GetEffects(ctx context.Context, deps *Dependencies, flows Flows) ([]Effect, error)
}

type DataOutputer interface {
    OutputData(ctx context.Context, deps *Dependencies, flows Flows) (Data, error)
}

type Milestone interface {
    Milestone()
}
```

### 3.2 Flow Intent 示例

以 Login Flow 为例：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow.go
type IntentLoginFlow struct {
    TargetUserID  string                 `json:"target_user_id,omitempty"`
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`
}

func (*IntentLoginFlow) Kind() string {
    return "IntentLoginFlow"
}

func (*IntentLoginFlow) FlowType() authflow.FlowType {
    return authflow.FlowTypeLogin
}

func (i *IntentLoginFlow) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 检查流程是否已完成
    _, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateSession](flows)
    if ok {
        return nil, authflow.ErrEOF  // 流程结束
    }
    return nil, nil  // 等待输入
}

func (i *IntentLoginFlow) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    switch {
    case len(flows.Nearest.Nodes) == 0:
        // 第一个节点：初始化
        return NewNodePreInitialize(ctx, deps, flows)
    case len(flows.Nearest.Nodes) == 1:
        // 第二个节点：启动步骤子流程
        return authflow.NewSubFlow(&IntentLoginFlowSteps{
            FlowReference: i.FlowReference,
            JSONPointer:   i.JSONPointer,
        }), nil
    case len(flows.Nearest.Nodes) == 2:
        // 第三个节点：创建会话
        userID, err := i.userID(flows)
        // ... 创建会话节点
    }
    return nil, authflow.ErrIncompatibleInput
}
```

### 3.3 Step Intent 示例

Identify 步骤：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go
type IntentLoginFlowStepIdentify struct {
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`
    StepName      string                 `json:"step_name,omitempty"`
    Options       []IdentificationOption `json:"options"`
}

func (i *IntentLoginFlowStepIdentify) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    _, _, identityUsed := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
    _, _, loginHintChecked := authflow.FindMilestoneInCurrentFlow[MilestoneCheckLoginHint](flows)
    _, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)

    switch {
    case len(flows.Nearest.Nodes) == 0:
        // 第一阶段：等待用户选择身份验证方式
        return &InputSchemaStepIdentify{...}, nil
    case identityUsed && !loginHintChecked:
        // 第二阶段：检查 login hint
        return nil, nil
    case identityUsed && !nestedStepsHandled:
        // 第三阶段：处理嵌套步骤
        return nil, nil
    default:
        return nil, authflow.ErrEOF  // 步骤完成
    }
}

func (i *IntentLoginFlowStepIdentify) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    _, _, identityUsed := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
    
    switch {
    case len(flows.Nearest.Nodes) == 0:
        var inputTakeIdentificationMethod inputTakeIdentificationMethod
        if authflow.AsInput(input, &inputTakeIdentificationMethod) {
            identification := inputTakeIdentificationMethod.GetIdentificationMethod()
            
            // 根据选择的验证方式启动不同的子流程
            switch identification {
            case model.AuthenticationFlowIdentificationEmail:
                return authflow.NewSubFlow(&IntentUseIdentityLoginID{...}), nil
            case model.AuthenticationFlowIdentificationOAuth:
                return authflow.NewSubFlow(&IntentOAuth{...}), nil
            case model.AuthenticationFlowIdentificationPasskey:
                return authflow.NewSubFlow(&IntentUseIdentityPasskey{...}), nil
            case model.AuthenticationFlowIdentificationLDAP:
                return authflow.NewSubFlow(&IntentLDAP{...}), nil
            }
        }
    // ... 其他阶段处理
    }
}
```

## 4. Milestone 系统详解

### 4.1 Milestone 接口

```go
// pkg/lib/authenticationflow/declarative/milestone.go

type Milestone interface {
    Milestone()
}

// 身份相关 Milestone
type MilestoneIdentificationMethod interface {
    Milestone
    MilestoneIdentificationMethod() model.AuthenticationFlowIdentification
}

type MilestoneFlowUseIdentity interface {
    Milestone
    MilestoneFlowUseIdentity(flows Flows) (MilestoneDoUseIdentity, Flows, bool)
}

type MilestoneDoUseIdentity interface {
    Milestone
    MilestoneDoUseIdentity() *identity.Info
}

// 认证相关 Milestone
type MilestoneAuthenticationMethod interface {
    Milestone
    MilestoneAuthenticationMethod() model.AuthenticationFlowAuthentication
}

type MilestoneDoAuthenticate interface {
    Milestone
    MilestoneDoAuthenticate() *authenticator.Info
}

// 会话相关 Milestone
type MilestoneDoCreateSession interface {
    Milestone
    MilestoneDoCreateSession() *idpsession.IDPSession
}

// 嵌套步骤 Milestone
type MilestoneNestedSteps interface {
    Milestone
    MilestoneNestedSteps()
}
```

### 4.2 Milestone 的作用

```
Flow 执行过程：

IntentLoginFlow
    └── NodePreInitialize
        └── IntentLoginFlowSteps
            └── IntentLoginFlowStepIdentify (MilestoneIdentificationMethod)
                └── IntentUseIdentityPasskey (MilestoneFlowUseIdentity)
                    └── NodeDoUseIdentityPasskey (MilestoneDoUseIdentity)
                        └── IntentLoginFlowSteps (MilestoneNestedSteps)
                            └── IntentLoginFlowStepAuthenticate
                                └── IntentUseAuthenticatorPasskey
                                    └── NodeDoUseAuthenticatorPasskey (MilestoneDoAuthenticate)
                                        └── IntentLoginFlow
                                            └── NodeDoCreateSession (MilestoneDoCreateSession)
                                                └── EOF (Flow End)
```

## 5. Flow 配置详解

### 5.1 配置结构

```yaml
# 示例登录流程配置
authentication:
  flows:
    login_flows:
      - name: default
        steps:
          - type: identify
            one_of:
              - identification: email
                steps:
                  - type: authenticate
                    one_of:
                      - authentication: primary_password
                      - authentication: primary_oob_otp_email
              - identification: passkey
          - type: check_account_status
          - type: terminate_other_sessions
```

### 5.2 配置 Go 结构

```go
// pkg/lib/config/authentication_flow.go

type AuthenticationFlowLoginFlow struct {
    Name  string                             `json:"name"`
    Steps []*AuthenticationFlowLoginFlowStep `json:"steps"`
}

type AuthenticationFlowLoginFlowStep struct {
    Name string                          `json:"name,omitempty"`
    Type config.AuthenticationFlowStepType `json:"type"`
    
    // 分支配置
    OneOf []*AuthenticationFlowLoginFlowIdentify `json:"one_of,omitempty"`
}

type AuthenticationFlowLoginFlowIdentify struct {
    Identification model.AuthenticationFlowIdentification `json:"identification"`
    BotProtection  *AuthenticationFlowBotProtection       `json:"bot_protection,omitempty"`
    Steps          []*AuthenticationFlowLoginFlowStep     `json:"steps,omitempty"`
    AccountLinking *AuthenticationFlowAccountLinking      `json:"account_linking,omitempty"`
}
```

### 5.3 支持的 Step 类型

**Login Flow Steps：**
- `identify` - 身份识别
- `authenticate` - 身份认证
- `check_account_status` - 检查账户状态
- `terminate_other_sessions` - 终止其他会话
- `change_password` - 修改密码
- `prompt_create_passkey` - 提示创建 Passkey

**Signup Flow Steps：**
- `identify` - 身份识别
- `create_authenticator` - 创建认证器
- `verify` - 验证身份
- `fill_in_user_profile` - 填写用户资料
- `view_recovery_code` - 查看恢复码
- `prompt_create_passkey` - 提示创建 Passkey

## 6. Flow 执行流程

### 6.1 流程启动

```go
// pkg/lib/authenticationflow/service.go
func (s *Service) CreateFlow(ctx context.Context, flowType FlowType, flowName string, userAgentID string) (*Flow, error) {
    // 1. 查找流程配置
    flowRef := FlowReference{Type: flowType, Name: flowName}
    
    // 2. 实例化流程 Intent
    publicFlow, err := InstantiateFlow(flowRef, jsonpointer.T{})
    
    // 3. 创建新流程实例
    flow := NewFlow(s.Config, s.Clock, s.IDPSessionService, publicFlow, userAgentID)
    
    // 4. 执行流程直到需要输入
    return s.advanceFlow(ctx, flow, nil, nil)
}
```

### 6.2 流程推进（真实实现）

真实的流程推进由 `Accept` / `doAccept` 函数实现：

```go
// pkg/lib/authenticationflow/accept.go

// Accept executes the flow to the deepest using input.
func Accept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, rawMessage json.RawMessage) error {
    return accept(ctx, deps, flows, result, func(inputSchema InputSchema) (Input, error) {
        if rawMessage != nil && inputSchema != nil {
            input, err := inputSchema.MakeInput(ctx, rawMessage)
            if err != nil {
                return nil, err
            }
            return input, nil
        }
        return nil, nil
    })
}

func doAccept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(inputSchema InputSchema) (Input, error)) (err error) {
    var changed bool

    defer func() {
        if changed {
            flows.Nearest.StateToken = newStateToken()
        }
        if !changed && err == nil {
            err = ErrNoChange
        }
    }()

    loopCount := 0
    var nextNodeType string
    for {
        loopCount += 1
        if loopCount > MAX_LOOP {
            panic(fmt.Errorf("number of loops reached limit. next node is %s", nextNodeType))
        }

        // 1. 找到可以接收输入的 InputReactor
        var findInputReactorResult *FindInputReactorResult
        findInputReactorResult, err = FindInputReactor(ctx, deps, flows)
        if err != nil {
            return
        }

        // 2. 通过 InputSchema 构建 Input
        var input Input
        input, err = inputFn(findInputReactorResult.InputSchema)
        // 处理验证错误...

        // 3. 调用 ReactTo 推进流程
        var reactToResult ReactToResult
        reactToResult, err = findInputReactorResult.InputReactor.ReactTo(
            ctx, deps, findInputReactorResult.Flows, input)

        // 4. 处理各种错误情况
        if errors.Is(err, ErrIncompatibleInput) {
            err = nil
            return
        }
        if errors.Is(err, ErrSameNode) {
            err = nil
            changed = true
            return
        }
        if errors.Is(err, ErrReplaceNode) {
            // 替换节点...
            return
        }
        if err != nil {
            return
        }

        // 5. 将返回的 Node 追加到流程
        var nextNode Node
        switch reactToResult := reactToResult.(type) {
        case *Node:
            nextNode = *reactToResult
        case *NodeWithDelayedOneTimeFunction:
            nextNode = *reactToResult.Node
            result.DelayedOneTimeFunctions = append(result.DelayedOneTimeFunctions, reactToResult.DelayedOneTimeFunction)
        }

        err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
        if err != nil {
            return
        }
        changed = true
    }
}

func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
    flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)

    // 立即执行 RunEffect
    err := TraverseNode(Traverser{
        NodeSimple: func(nodeSimple NodeSimple, w *Flow) error {
            effectGetter, ok := nodeSimple.(EffectGetter)
            if !ok {
                return nil
            }
            effs, err := effectGetter.GetEffects(ctx, deps, flows.Replace(w))
            if err != nil {
                return err
            }
            for _, eff := range effs {
                if runEff, ok := eff.(RunEffect); ok {
                    err = runEff.doNotCallThisDirectly(ctx, deps)
                    if err != nil {
                        return err
                    }
                }
            }
            return nil
        },
    }, flows.Nearest, &node)

    return err
}
```

## 7. 新增 Flow 类型的步骤

### 7.1 定义 Flow 类型常量

```go
// pkg/lib/authenticationflow/flow.go
const (
    FlowTypeSignup          FlowType = "signup"
    // ... 现有类型 ...
    FlowTypeCustom          FlowType = "custom"  // 新增类型
)

var AllFlowTypes []FlowType = []FlowType{
    FlowTypeSignup,
    // ... 现有类型 ...
    FlowTypeCustom,
}
```

### 7.2 实现 Flow Intent

```go
// pkg/lib/authenticationflow/declarative/intent_custom_flow.go

type IntentCustomFlow struct {
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`
    // 自定义字段
}

func init() {
    authflow.RegisterFlow(&IntentCustomFlow{})
}

func (*IntentCustomFlow) Kind() string {
    return "IntentCustomFlow"
}

func (*IntentCustomFlow) FlowType() authflow.FlowType {
    return authflow.FlowTypeCustom
}

func (i *IntentCustomFlow) FlowInit(r authflow.FlowReference, startFrom jsonpointer.T) {
    i.FlowReference = r
}

func (i *IntentCustomFlow) FlowFlowReference() authflow.FlowReference {
    return i.FlowReference
}

func (i *IntentCustomFlow) FlowRootObject(deps *authflow.Dependencies) (config.AuthenticationFlowObject, error) {
    return GetFlowRootObject(deps.Config, i.FlowReference)
}

func (i *IntentCustomFlow) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 检查流程是否完成
    _, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateSession](flows)
    if ok {
        return nil, authflow.ErrEOF
    }
    
    // 根据当前状态决定需要什么输入
    switch len(flows.Nearest.Nodes) {
    case 0:
        return nil, nil  // 等待初始化
    case 1:
        return nil, nil  // 等待执行步骤
    default:
        return nil, authflow.ErrEOF
    }
}

func (i *IntentCustomFlow) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    switch len(flows.Nearest.Nodes) {
    case 0:
        // 初始化节点
        return NewNodeCustomPreInitialize(ctx, deps, flows)
    case 1:
        // 启动步骤子流程
        return authflow.NewSubFlow(&IntentCustomFlowSteps{
            FlowReference: i.FlowReference,
            JSONPointer:   i.JSONPointer,
        }), nil
    case 2:
        // 创建会话
        userID, err := i.userID(flows)
        if err != nil {
            return nil, err
        }
        n, err := NewNodeDoCreateSession(ctx, deps, flows, &NodeDoCreateSession{
            UserID:       userID,
            CreateReason: session.CreateReasonLogin,
        })
        if err != nil {
            return nil, err
        }
        return authflow.NewNodeSimple(n), nil
    }
    return nil, authflow.ErrIncompatibleInput
}

func (i *IntentCustomFlow) GetEffects(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) ([]authflow.Effect, error) {
    return []authflow.Effect{
        authflow.OnCommitEffect(func(ctx context.Context, deps *authflow.Dependencies) error {
            // 提交时的副作用
            userID, err := i.userID(flows)
            if err != nil {
                return err
            }
            now := deps.Clock.NowUTC()
            return deps.Users.UpdateLoginTime(ctx, userID, now)
        }),
    }, nil
}
```

### 7.3 实现步骤 Intent

```go
// pkg/lib/authenticationflow/declarative/intent_custom_flow_steps.go

type IntentCustomFlowSteps struct {
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`
}

func (i *IntentCustomFlowSteps) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 遍历配置中的步骤，找到当前需要执行的步骤
    currentStep, err := i.currentStep(deps, flows)
    if err != nil {
        return nil, err
    }
    
    // 所有步骤完成后返回 EOF
    if currentStep == nil {
        return nil, authflow.ErrEOF
    }
    
    return nil, nil
}

func (i *IntentCustomFlowSteps) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    currentStep, err := i.currentStep(deps, flows)
    if err != nil {
        return nil, err
    }
    
    switch currentStep.GetType() {
    case config.AuthenticationFlowStepTypeIdentify:
        return authflow.NewSubFlow(&IntentCustomFlowStepIdentify{
            FlowReference: i.FlowReference,
            JSONPointer:   i.JSONPointer,
            StepName:      currentStep.GetName(),
        }), nil
    case config.AuthenticationFlowStepTypeAuthenticate:
        return authflow.NewSubFlow(&IntentCustomFlowStepAuthenticate{
            FlowReference: i.FlowReference,
            JSONPointer:   i.JSONPointer,
            StepName:      currentStep.GetName(),
        }), nil
    // ... 其他步骤类型
    }
    
    return nil, authflow.ErrIncompatibleInput
}
```

### 7.4 定义配置结构

```go
// pkg/lib/config/authentication_flow.go

var _ = Schema.Add("AuthenticationFlowCustomFlow", `
{
    "type": "object",
    "required": ["name", "steps"],
    "properties": {
        "name": { "$ref": "#/$defs/AuthenticationFlowObjectName" },
        "steps": {
            "type": "array",
            "minItems": 1,
            "items": { "$ref": "#/$defs/AuthenticationFlowCustomFlowStep" }
        }
    }
}
`)

var _ = Schema.Add("AuthenticationFlowCustomFlowStep", `
{
    "type": "object",
    "required": ["type"],
    "properties": {
        "name": { "$ref": "#/$defs/AuthenticationFlowObjectName" },
        "type": {
            "type": "string",
            "enum": ["identify", "authenticate", "custom_step"]
        }
    }
}
`)

type AuthenticationFlowCustomFlow struct {
    Name  string                             `json:"name"`
    Steps []*AuthenticationFlowCustomFlowStep `json:"steps"`
}

type AuthenticationFlowCustomFlowStep struct {
    Name string `json:"name,omitempty"`
    Type string `json:"type"`
}

func (s *AuthenticationFlowCustomFlowStep) GetName() string {
    return s.Name
}

func (s *AuthenticationFlowCustomFlowStep) GetType() AuthenticationFlowStepType {
    return AuthenticationFlowStepType(s.Type)
}
```

### 7.5 注册 Flow 配置

```go
// 在 AuthenticationFlowConfig 中添加
type AuthenticationFlowConfig struct {
    SignupFlows          []*AuthenticationFlowSignupFlow          `json:"signup_flows,omitempty"`
    // ... 现有字段 ...
    CustomFlows          []*AuthenticationFlowCustomFlow          `json:"custom_flows,omitempty"`  // 新增
}

// 实现配置查找
func (c *AuthenticationFlowConfig) FindCustomFlow(name string) *AuthenticationFlowCustomFlow {
    for _, f := range c.CustomFlows {
        if f.Name == name {
            return f
        }
    }
    return nil
}
```

### 7.6 实现工具函数

```go
// pkg/lib/authenticationflow/declarative/utils_custom.go

func GetCustomFlowRootObject(cfg *config.AppConfig, flowRef authflow.FlowReference) (config.AuthenticationFlowObject, error) {
    for _, f := range cfg.Authentication.Flows.CustomFlows {
        if f.Name == flowRef.Name {
            return f, nil
        }
    }
    return nil, authflow.ErrFlowNotFound
}
```

### 7.7 添加 Identification 类型（如需要）

```go
// pkg/api/model/identification.go

const (
    AuthenticationFlowIdentificationEmail    AuthenticationFlowIdentification = "email"
    // ... 现有类型 ...
    AuthenticationFlowIdentificationCustom   AuthenticationFlowIdentification = "custom"  // 新增
)

func (m AuthenticationFlowIdentification) PrimaryAuthentications() []AuthenticationFlowAuthentication {
    switch m {
    // ... 现有 case ...
    case AuthenticationFlowIdentificationCustom:
        return []AuthenticationFlowAuthentication{
            AuthenticationFlowAuthenticationPrimaryCustom,
        }
    }
}
```

## 8. Flow 与 Identity/Authenticator 的交互

### 8.1 Identify 步骤交互

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Identify Step                                         │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  Input: Identification Method Selection                               │  │
│  │  (email/phone/username/oauth/passkey/ldap/custom)                     │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                         │
│                                    ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  IntentUseIdentityXXXX (类型特定的 Intent)                            │  │
│  │                                                                      │  │
│  │  1. 收集凭证输入 (如 password/assertion_response)                      │  │
│  │  2. 调用 Identity Service 查找/验证身份                              │  │
│  │     - deps.Identities.SearchBySpec()                                 │  │
│  │  3. 如有需要，调用 Authenticator Service 验证                         │  │
│  │     - deps.Authenticators.VerifyOneWithSpec()                        │  │
│  │  4. 创建 NodeDoUseIdentityXXXX (MilestoneDoUseIdentity)               │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                         │
│                                    ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  Output: Identity Info (user_id, identity_id, claims)                 │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Authenticate 步骤交互

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Authenticate Step                                      │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  Input: Authentication Method Selection                               │  │
│  │  (primary_password/primary_oob_otp/primary_passkey/secondary_totp...) │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                         │
│                                    ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  IntentUseAuthenticatorXXXX (类型特定的 Intent)                       │  │
│  │                                                                      │  │
│  │  1. 根据 user_id 列出可用的 Authenticators                            │  │
│  │     - deps.Authenticators.List()                                     │  │
│  │  2. 过滤出目标类型的 Authenticators                                   │  │
│  │     - authenticator.KeepType(model.AuthenticatorTypeXXXX)            │  │
│  │  3. 收集认证输入 (如 password/code/assertion_response)                 │  │
│  │  4. 调用 Authenticator Service 验证                                   │  │
│  │     - deps.Authenticators.VerifyOneWithSpec()                        │  │
│  │  5. 创建 NodeDoUseAuthenticatorXXXX (MilestoneDoAuthenticate)       │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                         │
│                                    ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  Output: Authenticator Info (authenticated)                           │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 9. 关键设计原则

1. **声明式配置**：流程通过配置定义，而非硬编码
2. **Intent-Node 模式**：Intent 表示意图，Node 表示状态
3. **Milestone 机制**：标记关键完成点，支持流程状态查询
4. **输入驱动**：流程通过用户输入推进
5. **子流程支持**：支持嵌套子流程，实现复杂场景
6. **与 Identity/Authenticator 解耦**：Flow 协调 Identity 和 Authenticator，但不依赖具体实现
