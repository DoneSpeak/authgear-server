# Authgear Authentication Flow Input 设计理念

## 概述

Authgear 的 Authentication Flow 使用了一种**Schema-First**的输入设计理念，通过 `InputSchema` 定义 JSON 校验规则，并生成对应的 `Input` 结构体。输入从 HTTP API 流经服务层，通过 `InputReactor` 模式传递给 Intent 和 Node。

---

## 1. 核心设计理念

### 1.1 Schema-First 验证

输入验证采用 Schema-First 设计，每个输入类型都有对应的 `InputSchema` 定义 JSON 校验规则：

```go
type InputSchema interface {
    GetJSONPointer() jsonpointer.T           // 指向 flow 配置中的位置
    GetFlowRootObject() config.AuthenticationFlowObject  // 关联的 flow 配置对象
    SchemaBuilder() validation.SchemaBuilder // JSON Schema 构建器
    MakeInput(ctx context.Context, rawMessage json.RawMessage) (Input, error)  // 创建 Input 实例
}
```

### 1.2 接口化输入检测

系统使用细粒度的接口进行输入能力检测，而非类型断言：

```go
// 输入能力接口示例
type inputTakeIdentificationMethod interface {
    GetIdentificationMethod() model.AuthenticationFlowIdentification
}

type inputTakeLoginID interface {
    GetLoginID() string
}

type inputTakePassword interface {
    GetPassword() string
}
```

### 1.3 InputReactor 模式

Intent 和 Node 统一实现 `InputReactor` 接口来接收输入：

```go
type InputReactor interface {
    // CanReactTo 返回 InputSchema，告知调用者需要什么输入
    CanReactTo(ctx context.Context, deps *Dependencies, flows Flows) (InputSchema, error)
    // ReactTo 处理输入并返回结果（新节点、子流程等）
    ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (ReactToResult, error)
}
```

### 1.4 延迟执行与事务边界

输入处理产生的结果可以包含延迟执行的一次性函数，这些函数在只读事务外执行：

```go
type AcceptResult struct {
    // 需要在工作流之外执行的一次性函数（如发送邮件、短信）
    DeferredOneTimeFunc func(ctx context.Context, deps *Dependencies) error
}
```

---

## 2. Input 类型定义与结构

### 2.1 核心接口

```go
// Input - 标记接口，所有输入类型都必须实现
// 具体输入结构体通过 InputSchema.MakeInput() 创建
type Input interface {
    Input()
}
```

### 2.2 InputSchema 实现示例

```go
type InputSchemaStepIdentify struct {
    JSONPointer               jsonpointer.T                // JSON Pointer 指向配置位置
    FlowRootObject            config.AuthenticationFlowObject
    Options                   []IdentificationOption         // 可用的识别选项
    ShouldBypassBotProtection bool
    BotProtectionCfg          *config.BotProtectionConfig
    IsExternalJWTAllowed      bool
}

// SchemaBuilder 动态构建 JSON Schema
func (i *InputSchemaStepIdentify) SchemaBuilder() validation.SchemaBuilder {
    b := validation.SchemaBuilder{}.Type(validation.TypeObject)
    
    // 根据可用选项动态构建枚举
    identifications := i.getIdentificationEnums()
    if len(identifications) > 0 {
        b.Properties().Property("identification", 
            validation.SchemaBuilder{}.Enum(identifications...))
        b.Required("identification")
    }
    
    // 根据条件添加其他属性
    if i.ShouldBypassBotProtection {
        b.Properties().Property("bot_protection", 
            InputSchemaTakeBotProtection.SchemaBuilder())
    }
    
    return b
}

// MakeInput 解析原始 JSON 为具体的 Input 结构体
func (i *InputSchemaStepIdentify) MakeInput(ctx context.Context, rawMessage json.RawMessage) (Input, error) {
    var input InputStepIdentify
    if err := json.Unmarshal(rawMessage, &input); err != nil {
        return nil, err
    }
    return &input, nil
}
```

### 2.3 Input 结构体示例

```go
type InputStepIdentify struct {
    Identification model.AuthenticationFlowIdentification `json:"identification,omitempty"`
    IDToken        string                                 `json:"id_token,omitempty"`
    LoginID        string                                 `json:"login_id,omitempty"`
    ExternalJWT    string                                 `json:"external_jwt,omitempty"`
    Alias          string                                 `json:"alias,omitempty"`
    RedirectURI    string                                 `json:"redirect_uri,omitempty"`
    ResponseMode   string                                 `json:"response_mode,omitempty"`
    BotProtection  *InputTakeBotProtectionBody              `json:"bot_protection,omitempty"`
    ServerName     string                                 `json:"server_name"`
    Username       string                                 `json:"username"`
    Password       string                                 `json:"password"`
}

// 实现 Input 标记接口
func (*InputStepIdentify) Input() {}
```

---

## 3. Input 处理逻辑

### 3.1 Accept 循环

```go
// Accept 是处理输入的主循环
func Accept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, rawMessage json.RawMessage) error {
    return accept(ctx, deps, flows, result, func(inputSchema InputSchema) (Input, error) {
        if rawMessage != nil && inputSchema != nil {
            // 使用 InputSchema 验证并解析输入
            input, err := inputSchema.MakeInput(ctx, rawMessage)
            if err != nil {
                return nil, err
            }
            return input, nil
        }
        return nil, nil
    })
}
```

### 3.2 处理流程

1. **查找 InputReactor**: `FindInputReactor()` 定位能够接收输入的 Intent 或 Node
2. **Schema 验证**: 调用 `CanReactTo()` 获取 `InputSchema`
3. **输入解析**: `MakeInput()` 验证并解析原始 JSON 为 Input 结构体
4. **输入处理**: `ReactTo()` 处理输入并返回结果
5. **循环继续**: 处理结果可能产生新的 Node，循环继续直到完成

```go
func accept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn inputMaker) error {
    for {
        // 1. 查找能够接收输入的 reactor
        reactor, inputSchema, err := FindInputReactor(ctx, deps, flows)
        if err != nil {
            return err
        }

        // 2. 创建输入（验证 + 解析）
        input, err := inputFn(inputSchema)
        if err != nil {
            return err
        }

        // 3. 处理输入
        reactToResult, err := reactor.ReactTo(ctx, deps, flows, input)
        if err != nil {
            return err
        }

        // 4. 根据结果更新 flow 状态
        switch r := reactToResult.(type) {
        case *ReactToResultNewNode:
            flows.NeedUpdate = true
            flows.Root.Nodes = append(flows.Root.Nodes, r.Node)
        case *ReactToResultNewFlow:
            flows.NeedUpdate = true
            flows.Root.Nodes = append(flows.Root.Nodes, NewNodeSimple(r.NewIntent))
        case *ReactToResultEndToEndFlow:
            // 处理端到端子流程
        }

        // 5. 检查是否应该停止
        if errors.Is(err, ErrEOF) || errors.Is(err, ErrNoChange) {
            return nil
        }
    }
}
```

### 3.3 输入类型检测

```go
// AsInput 使用反射检测输入是否实现了指定接口
func AsInput(i Input, iface interface{}) bool {
    if i == nil {
        return false
    }
    val := reflect.ValueOf(iface)
    typ := val.Type()
    targetType := typ.Elem()
    
    for {
        // 检查类型是否可赋值
        if reflect.TypeOf(i).AssignableTo(targetType) {
            val.Elem().Set(reflect.ValueOf(i))
            return true
        }
        // 解包包装过的输入
        if x, ok := i.(InputUnwrapper); ok {
            i = x.Unwrap()
        } else {
            break
        }
    }
    return false
}
```

### 3.4 合成输入（Synthetic Input）

系统支持内部注入输入，无需 HTTP 往返：

```go
func AcceptSyntheticInput(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, syntheticInput Input) error {
    return accept(ctx, deps, flows, result, func(inputSchema InputSchema) (Input, error) {
        // 直接使用提供的合成输入，不进行 JSON 解析
        return syntheticInput, nil
    })
}
```

---

## 4. 存储机制

### 4.1 Flow 持久化结构

工作流状态只持久化 Intent 和 Node 数据，Input 是临时的，不存储：

```go
type flowJSON struct {
    FlowID     string     `json:"flow_id,omitempty"`
    StateToken string     `json:"state_token,omitempty"`
    Intent     intentJSON `json:"intent"`
    Nodes      []Node     `json:"nodes,omitempty"`
}

type intentJSON struct {
    Kind string          `json:"kind"`
    Data json.RawMessage `json:"data"`
}
```

### 4.2 Node 序列化

```go
type nodeJSON struct {
    Type    NodeType        `json:"type"`
    Simple  *nodeSimpleJSON `json:"simple,omitempty"`
    SubFlow *Flow           `json:"flow,omitempty"`
}

type nodeSimpleJSON struct {
    Kind string          `json:"kind"`
    Data json.RawMessage `json:"data"`
}
```

### 4.3 多态序列化

使用 kind 字段实现多态反序列化：

```go
func (f *Flow) MarshalJSON() ([]byte, error) {
    return json.Marshal(flowJSON{
        FlowID:     f.FlowID,
        StateToken: f.StateToken,
        Intent: intentJSON{
            Kind: f.Intent.Kind(),
            Data: mustMarshal(f.Intent),
        },
        Nodes: f.Nodes,
    })
}
```

---

## 5. 序列化与反序列化

### 5.1 InputSchema 的 MakeInput 方法

这是反序列化的核心入口：

```go
func (i *InputSchemaTakeLoginID) MakeInput(ctx context.Context, rawMessage json.RawMessage) (Input, error) {
    // 1. 先进行 JSON Schema 验证
    if err := i.SchemaBuilder().Validate(rawMessage); err != nil {
        return nil, err
    }
    
    // 2. 反序列化为具体结构体
    var input InputTakeLoginID
    if err := json.Unmarshal(rawMessage, &input); err != nil {
        return nil, err
    }
    
    return &input, nil
}
```

### 5.2 延迟序列化（Lazy Serialization）

输出中的 `Output` 字段延迟序列化：

```go
type ServiceOutput struct {
    Flow           *Flow
    Output         interface{}    // 可以是任意类型，延迟序列化
    Cookies        []*http.Cookie
}

// 在 HTTP 响应时进行最终序列化
func (o *ServiceOutput) marshalOutput() (json.RawMessage, error) {
    return json.Marshal(o.Output)
}
```

---

## 6. HTTP Endpoint API 接口

### 6.1 输入端点

```go
// POST /api/v1/authentication_flows/states/input
func ConfigureAuthenticationFlowV1InputRoute(route httproute.Route) httproute.Route {
    return route.WithMethods("OPTIONS", "POST").
        WithPathPattern("/api/v1/authentication_flows/states/input")
}
```

### 6.2 请求 Schema

支持单输入或批量输入：

```json
{
    "type": "object",
    "oneOf": [
        {
            "properties": {
                "state_token": { "type": "string" },
                "input": { "type": "object" }
            },
            "required": ["input"]
        },
        {
            "properties": {
                "state_token": { "type": "string" },
                "batch_input": {
                    "type": "array",
                    "items": { "type": "object" },
                    "minItems": 1
                }
            },
            "required": ["batch_input"]
        }
    ]
}
```

### 6.3 服务层接口

```go
type AuthenticationFlowV1WorkflowService interface {
    // 创建新流程
    CreateNewFlow(ctx context.Context, intent authflow.PublicFlow, sessionOptions *authflow.SessionOptions) (*authflow.ServiceOutput, error)
    // 获取流程状态
    Get(ctx context.Context, stateToken string) (*authflow.ServiceOutput, error)
    // 提交输入
    FeedInput(ctx context.Context, stateToken string, rawMessage json.RawMessage) (*authflow.ServiceOutput, error)
}
```

### 6.4 批量输入处理

```go
func batchInput0(ctx context.Context, service AuthenticationFlowV1WorkflowService, rawMessages []json.RawMessage, stateToken string) (*authflow.ServiceOutput, error) {
    var cookies []*http.Cookie
    
    for _, rawMessage := range rawMessages {
        // 逐个提交输入，每次使用最新的 state_token
        output, err := service.FeedInput(ctx, stateToken, rawMessage)
        if err != nil && !errors.Is(err, authflow.ErrEOF) {
            return nil, err
        }
        
        // 更新 state_token 为下一次请求准备
        stateToken = output.Flow.StateToken
        cookies = append(cookies, output.Cookies...)
    }
    
    output.Cookies = cookies
    return output, nil
}
```

### 6.5 无状态设计

每次输入提交返回新的 `state_token`，实现无状态 API：

```go
// 客户端必须保存最新的 state_token 用于下一次请求
type ServiceOutput struct {
    Flow *Flow
    // Flow.StateToken 每次请求都会更新
}
```

---

## 7. 客户端接口理解

### 7.1 动态 Schema 生成

系统根据当前 flow 状态动态生成输入 schema，客户端可以通过以下方式理解接口：

1. **获取 Flow 状态**: 调用 `Get()` 或创建新 flow
2. **查看 Available Steps**: 从 `Output` 中查看当前可用的步骤
3. **理解 Input 要求**: 系统返回的数据包含当前步骤需要的输入字段

### 7.2 示例：识别步骤的输入要求

```json
{
    "step": {
        "identification": "email",
        "login_id": "user@example.com",
        "bot_protection": {
            "type": "recaptchav2",
            "required": true
        }
    }
}
```

客户端根据返回的结构动态构建 UI 和输入表单。

### 7.3 输入验证错误

当输入不符合 schema 时，返回详细错误：

```json
{
    "error": {
        "code": "invalid_input",
        "message": "input validation failed",
        "details": {
            "field": "identification",
            "violation": "value must be one of: [email phone oauth]"
        }
    }
}
```

---

## 8. 关键文件清单

| 文件 | 用途 |
|------|------|
| `pkg/lib/authenticationflow/input.go` | 核心 Input 接口和类型检测 |
| `pkg/lib/authenticationflow/accept.go` | 输入处理主循环 |
| `pkg/lib/authenticationflow/service.go` | 服务层接口 |
| `pkg/lib/authenticationflow/marshal.go` | 序列化/反序列化 |
| `pkg/lib/authenticationflow/declarative/input_*.go` | 具体输入实现 |
| `pkg/lib/authenticationflow/declarative/input_interface.go` | 输入能力接口定义 |
| `pkg/auth/handler/api/authenticationflow_v1_input.go` | HTTP 输入端点 |
| `pkg/auth/handler/api/authenticationflow_v1.go` | 批量输入处理 |

---

## 9. 设计优势

1. **类型安全**: 使用接口而非字符串键访问输入数据
2. **动态验证**: Schema 根据 flow 配置动态构建
3. **统一模型**: Intent 和 Node 统一使用 InputReactor 接口
4. **可测试性**: 合成输入支持内部测试无需 HTTP
5. **可扩展性**: 新输入类型只需实现 Input 接口和相关 schema
