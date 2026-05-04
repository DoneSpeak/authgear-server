# Authentication Flow Context 技术设计与使用文档

## 目录

1. [概述](#概述)
2. [Context 数据模型](#context-数据模型)
3. [Context 生命周期](#context-生命周期)
4. [Context 创建与初始化](#context-创建与初始化)
5. [从 Context 获取数据](#从-context-获取数据)
6. [Context 更新机制](#context-更新机制)
7. [持久化机制](#持久化机制)
8. [代码示例](#代码示例)
9. [最佳实践](#最佳实践)

---

## 概述

Authentication Flow Context 是 Authgear 认证流程中的核心数据载体，负责在认证流程的各个阶段传递关键信息。它基于 Go 标准库的 `context.Context` 机制实现，通过 `WithValue` 方式存储和传递数据。

**设计目标：**
- 隔离不同认证流程的状态（Session 不包含 Web Session ID，确保 Webapp 在 authflow 中没有特权）
- 支持 OAuth/OIDC 协议参数传递
- 提供 Bot Protection、ID Token 等安全相关的上下文信息
- 支持多语言和本地化

---

## Context 数据模型

### 2.1 Session 结构体

Context 的核心数据结构是 `Session`，定义在 `pkg/lib/authenticationflow/session.go`：

```go
type Session struct {
    // 流程标识
    FlowID string `json:"flow_id"`

    // 外部会话关联
    OAuthSessionID string `json:"oauth_session_id,omitempty"`
    SAMLSessionID  string `json:"saml_session_id,omitempty"`

    // OAuth/OIDC 参数
    ClientID    string   `json:"client_id,omitempty"`
    RedirectURI string   `json:"redirect_uri,omitempty"`
    Prompt      []string `json:"prompt,omitempty"`
    State       string   `json:"state,omitempty"`
    XState      string   `json:"x_state,omitempty"`
    UILocales   string   `json:"ui_locales,omitempty"`

    // 安全与流程控制
    BotProtectionVerificationResult *BotProtectionVerificationResult `json:"bot_protection_verification_result,omitempty"`
    IDToken                         string                           `json:"id_token,omitempty"`
    SuppressIDPSessionCookie        bool                             `json:"suppress_idp_session_cookie,omitempty"`
    UserIDHint                      string                           `json:"user_id_hint,omitempty"`
    LoginHint                       string                           `json:"login_hint,omitempty"`
}
```

### 2.2 字段说明

| 字段 | 类型 | 用途 |
|------|------|------|
| `FlowID` | string | 唯一标识认证流程，格式 `authflow_{32位base32随机字符串}` |
| `OAuthSessionID` | string | 关联的 OAuth 会话 ID，用于 OAuth 授权流程 |
| `SAMLSessionID` | string | 关联的 SAML 会话 ID，用于 SAML 认证流程 |
| `ClientID` | string | OAuth 客户端 ID，标识发起认证的应用 |
| `RedirectURI` | string | 认证完成后的重定向地址 |
| `Prompt` | []string | OIDC prompt 参数，控制认证行为 |
| `State` / `XState` | string | OAuth state 参数，用于 CSRF 防护 |
| `UILocales` | string | 用户界面语言偏好 |
| `BotProtectionVerificationResult` | *BotProtectionVerificationResult | Bot Protection 验证结果 |
| `IDToken` | string | 用于 ID Token 提示（ID Token hint）场景 |
| `SuppressIDPSessionCookie` | bool | 是否抑制 IDP 会话 Cookie |
| `UserIDHint` | string | 用户 ID 提示，用于指定用户认证 |
| `LoginHint` | string | 登录提示（如邮箱、手机号） |

### 2.3 Context Key 定义

在 `pkg/lib/authenticationflow/context.go` 中定义了 8 个私有的 context key：

```go
type contextKeyTypeOAuthSessionID struct{}
var contextKeyOAuthSessionID = contextKeyTypeOAuthSessionID{}

type contextKeyTypeSAMLSessionID struct{}
var contextKeySAMLSessionID = contextKeyTypeSAMLSessionID{}

type contextKeyTypeBotProtectionVerificationResult struct{}
var contextKeyBotProtectionVerificationResult = contextKeyTypeBotProtectionVerificationResult{}

type contextKeyTypeIDToken struct{}
var contextKeyIDToken = contextKeyTypeIDToken{}

type contextKeyTypeSuppressIDPSessionCookie struct{}
var contextKeySuppressIDPSessionCookie = contextKeyTypeSuppressIDPSessionCookie{}

type contextKeyTypeUserIDHint struct{}
var contextKeyUserIDHint = contextKeyTypeUserIDHint{}

type contextKeyTypeLoginHint struct{}
var contextKeyLoginHint = contextKeyTypeLoginHint{}

type contextKeyTypeFlowID struct{}
var contextKeyFlowID = contextKeyTypeFlowID{}
```

---

## Context 生命周期

### 3.1 生命周期阶段

```
创建(Create) → 初始化(MakeContext) → 使用(流程执行) → 更新(Update) → 销毁(Delete)
```

### 3.2 各阶段说明

| 阶段 | 触发时机 | 关键操作 | 代码位置 |
|------|----------|----------|----------|
| **创建** | 调用 `CreateNewFlow()` | `NewSession()` 生成新 Session | `service.go:89` |
| **初始化** | Session 创建后 | `MakeContext()` 注入 context values | `service.go:90` |
| **恢复** | 已有流程继续 | `getSessionAndUpdateContext()` | `service.go:705-714` |
| **更新** | Bot Protection 验证完成 | `UpdateSession()` | `service.go:176-182` |
| **销毁** | 流程完成 (ErrEOF) | `DeleteSession()` + `DeleteFlow()` | `service.go:143-151` |

---

## Context 创建与初始化

### 4.1 Session 创建流程

```go
// service.go:83-103
func (s *Service) CreateNewFlow(ctx context.Context, publicFlow PublicFlow, sessionOptions *SessionOptions) (output *ServiceOutput, err error) {
    // 1. 验证参数
    err = s.validateNewFlow(publicFlow, sessionOptions)
    if err != nil {
        return
    }

    // 2. 创建 Session 实例
    session := NewSession(sessionOptions)
    
    // 3. 用 Session 数据丰富 Context
    ctx = session.MakeContext(ctx, s.Deps)
    
    // 4. 持久化到 Redis
    err = s.Store.CreateSession(ctx, session)
    
    // 5. 创建 Flow 并执行
    return s.createNewFlowWithSession(ctx, publicFlow, session)
}
```

### 4.2 MakeContext 详细逻辑

```go
// session.go:126-160
func (s *Session) MakeContext(ctx context.Context, deps *Dependencies) context.Context {
    // 1. 设置会话关联 ID
    ctx = context.WithValue(ctx, contextKeyOAuthSessionID, s.OAuthSessionID)
    ctx = context.WithValue(ctx, contextKeySAMLSessionID, s.SAMLSessionID)

    // 2. 设置 OpenTelemetry Client ID（用于链路追踪）
    if s.ClientID != "" {
        otelauthgear.SetClientID(ctx, s.ClientID)
    }

    // 3. 设置 UI 参数（供后续节点使用）
    ctx = uiparam.WithUIParam(ctx, &uiparam.T{
        ClientID:  s.ClientID,
        Prompt:    s.Prompt,
        UILocales: s.UILocales,
        State:     s.State,
        XState:    s.XState,
    })

    // 4. 设置语言偏好
    if s.UILocales != "" {
        // 优先使用 UILocales 参数
        tags := intl.ParseUILocales(s.UILocales)
        ctx = intl.WithPreferredLanguageTags(ctx, tags)
    } else {
        // 回退到 Accept-Language Header
        acceptLanguage := deps.HTTPRequest.Header.Get("Accept-Language")
        tags := intl.ParseAcceptLanguage(acceptLanguage)
        ctx = intl.WithPreferredLanguageTags(ctx, tags)
    }

    // 5. 设置安全相关 context values
    ctx = context.WithValue(ctx, contextKeyBotProtectionVerificationResult, s.BotProtectionVerificationResult)
    ctx = context.WithValue(ctx, contextKeyIDToken, s.IDToken)
    ctx = context.WithValue(ctx, contextKeySuppressIDPSessionCookie, s.SuppressIDPSessionCookie)
    ctx = context.WithValue(ctx, contextKeyUserIDHint, s.UserIDHint)
    ctx = context.WithValue(ctx, contextKeyLoginHint, s.LoginHint)

    // 6. 设置 Flow ID
    ctx = context.WithValue(ctx, contextKeyFlowID, s.FlowID)

    return ctx
}
```

---

## 从 Context 获取数据

### 5.1 Getter 函数

在 `pkg/lib/authenticationflow/context.go` 中提供了安全的 getter 函数：

```go
// 获取 OAuth Session ID
func GetOAuthSessionID(ctx context.Context) string {
    return ctx.Value(contextKeyOAuthSessionID).(string)
}

// 获取 SAML Session ID
func GetSAMLSessionID(ctx context.Context) string {
    return ctx.Value(contextKeySAMLSessionID).(string)
}

// 获取 Bot Protection 验证结果（带类型检查）
func GetBotProtectionVerificationResult(ctx context.Context) *BotProtectionVerificationResult {
    result, ok := ctx.Value(contextKeyBotProtectionVerificationResult).(*BotProtectionVerificationResult)
    if !ok {
        return nil
    }
    return result
}

// 获取 ID Token
func GetIDToken(ctx context.Context) string {
    return ctx.Value(contextKeyIDToken).(string)
}

// 获取 Cookie 抑制标志
func GetSuppressIDPSessionCookie(ctx context.Context) bool {
    return ctx.Value(contextKeySuppressIDPSessionCookie).(bool)
}

// 获取 User ID Hint
func GetUserIDHint(ctx context.Context) string {
    return ctx.Value(contextKeyUserIDHint).(string)
}

// 获取 Login Hint
func GetLoginHint(ctx context.Context) string {
    return ctx.Value(contextKeyLoginHint).(string)
}

// 获取 Flow ID
func GetFlowID(ctx context.Context) string {
    return ctx.Value(contextKeyFlowID).(string)
}
```

### 5.2 使用示例

```go
// 在 Intent 或 Node 中获取 Context 数据
func (i *IntentLogin) ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (*Node, error) {
    // 获取 Flow ID 用于日志
    flowID := authenticationflow.GetFlowID(ctx)
    
    // 获取 OAuth 相关信息
    clientID := authenticationflow.GetOAuthSessionID(ctx)
    
    // 获取 Bot Protection 验证结果
    bpResult := authenticationflow.GetBotProtectionVerificationResult(ctx)
    if bpResult != nil && bpResult.Outcome == BotProtectionVerificationOutcomeFailed {
        // 处理验证失败
    }
    
    // 获取 User ID Hint
    userIDHint := authenticationflow.GetUserIDHint(ctx)
    
    // ...
}
```

---

## Context 更新机制

### 6.1 更新触发场景

Context 中的 Session 数据主要在以下场景更新：

1. **Bot Protection 验证完成** - 更新验证结果

### 6.2 更新流程

```go
// service.go:170-199
func (s *Service) processAcceptResult(
    ctx context.Context,
    session *Session,
    flows Flows,
    acceptResult *AcceptResult,
) error {
    // 1. Bot Protection 验证结果更新
    if acceptResult.BotProtectionVerificationResult != nil {
        session.SetBotProtectionVerificationResult(acceptResult.BotProtectionVerificationResult)
        
        // 2. 持久化更新到 Redis
        updateSessionErr := s.Store.UpdateSession(ctx, session)
        if updateSessionErr != nil {
            return updateSessionErr
        }
    }
    
    // 3. 执行延迟一次性函数
    for _, fn := range acceptResult.DelayedOneTimeFunctions {
        err := fn(ctx, s.Deps)
        if err != nil {
            // 错误处理：恢复数据库状态
            err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
                runEffectErr := ApplyRunEffects(ctx, s.Deps, flows)
                if runEffectErr != nil {
                    return errors.Join(runEffectErr, err)
                }
                return newAuthenticationFlowError(flows, err)
            })
            return err
        }
    }
    return nil
}
```

### 6.3 Accept 循环中的 Context

```go
// accept.go:107-269
func doAccept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(inputSchema InputSchema) (Input, error)) (err error) {
    loopCount := 0
    for {
        loopCount += 1
        if loopCount > MAX_LOOP { // MAX_LOOP = 100
            panic(fmt.Errorf("number of loops reached limit"))
        }
        
        // 1. 查找输入响应器
        findInputReactorResult, err := FindInputReactor(ctx, deps, flows)
        
        // 2. 构造输入
        input, err := inputFn(findInputReactorResult.InputSchema)
        
        // 3. 响应输入（ReactTo 可以使用 ctx 中的数据）
        reactToResult, err := findInputReactorResult.InputReactor.ReactTo(ctx, deps, findInputReactorResult.Flows, input)
        
        // 4. 处理特殊错误（包括 Bot Protection）
        // ...
        
        // 5. 追加新节点
        err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
    }
}
```

---

## 持久化机制

### 7.1 Redis 存储结构

```
app:{AppID}:authenticationflow_session:{FlowID}     -> Session JSON
app:{AppID}:authenticationflow_state:{StateToken}     -> Flow JSON
app:{AppID}:authenticationflow_flow:{FlowID}         -> FlowID 存在性标记
```

### 7.2 Store 接口

```go
// service.go:44-53
type Store interface {
    CreateSession(ctx context.Context, session *Session) error
    GetSession(ctx context.Context, flowID string) (*Session, error)
    DeleteSession(ctx context.Context, session *Session) error
    UpdateSession(ctx context.Context, session *Session) error

    CreateFlow(ctx context.Context, flow *Flow) error
    GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error)
    DeleteFlow(ctx context.Context, flow *Flow) error
}
```

### 7.3 实现细节

```go
// store.go

// CreateSession - 使用 SETEX 创建带 TTL 的 Session
func (s *StoreImpl) CreateSession(ctx context.Context, session *Session) error {
    bytes, err := json.Marshal(session)
    // ...
    sessionKey := redisFlowSessionKey(s.AppID, session.FlowID)
    ttl := Lifetime // duration.UserInteraction
    _, err = conn.SetEx(ctx, sessionKey, bytes, ttl).Result()
}

// GetSession - 获取并反序列化 Session
func (s *StoreImpl) GetSession(ctx context.Context, flowID string) (*Session, error) {
    sessionKey := redisFlowSessionKey(s.AppID, flowID)
    bytes, err := conn.Get(ctx, sessionKey).Bytes()
    // ...
    err = json.Unmarshal(bytes, &session)
}

// UpdateSession - 使用 SETXX 更新已存在的 Session
func (s *StoreImpl) UpdateSession(ctx context.Context, session *Session) error {
    bytes, err := json.Marshal(session)
    // ...
    _, err = conn.SetXX(ctx, sessionKey, bytes, ttl).Result()
}

// DeleteSession - 删除 Session
func (s *StoreImpl) DeleteSession(ctx context.Context, session *Session) error {
    _, err := conn.Del(ctx, sessionKey).Result()
}
```

### 7.4 TTL 配置

```go
// store.go:17
const Lifetime = duration.UserInteraction
```

Session 和 Flow 使用相同的 TTL，确保在用户的交互周期内数据可用。

---

## 代码示例

### 8.1 创建新流程

```go
// 构造 SessionOptions
opts := &authenticationflow.SessionOptions{
    OAuthSessionID: oauthSessionID,
    ClientID:       clientID,
    RedirectURI:    redirectURI,
    Prompt:         []string{"login"},
    State:          state,
    UILocales:      "zh-HK",
    LoginHint:      "user@example.com",
}

// 创建流程
output, err := service.CreateNewFlow(ctx, publicFlow, opts)
if err != nil {
    return err
}

// 获取 StateToken 返回给客户端
stateToken := output.Flow.StateToken
```

### 8.2 获取现有流程

```go
// 通过 StateToken 获取流程
output, err := service.Get(ctx, stateToken)
if err != nil {
    return err
}

// output.Session 包含 Session 数据
// output.Flow 包含 Flow 状态
```

### 8.3 提交输入

```go
// 提交用户输入
output, err := service.FeedInput(ctx, stateToken, rawInputJSON)
if errors.Is(err, authenticationflow.ErrEOF) {
    // 流程完成
    // output.Cookies 包含需要设置的 Cookie
}
```

### 8.4 在 Node 中使用 Context

```go
type NodeDoUseAuthenticatorPassword struct {
    UserID       string
    AuthenticatorID string
}

func (n *NodeDoUseAuthenticatorPassword) ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (*Node, error) {
    // 获取 Bot Protection 验证结果
    bpResult := authenticationflow.GetBotProtectionVerificationResult(ctx)
    
    // 获取 Login Hint
    loginHint := authenticationflow.GetLoginHint(ctx)
    
    // 检查是否需要跳过某些逻辑
    suppressCookie := authenticationflow.GetSuppressIDPSessionCookie(ctx)
    
    // ... 业务逻辑
}
```

---

## 最佳实践

### 9.1 Context 使用原则

1. **只读访问**：Context 数据在流程执行期间应视为只读，除了通过 `UpdateSession` 进行的显式更新

2. **安全获取**：使用提供的 getter 函数，避免直接 `ctx.Value()` 类型断言

3. **检查 nil**：对于可能为空的值（如 `*BotProtectionVerificationResult`），在使用前检查 nil

### 9.2 Session 更新原则

1. **最小更新**：只更新需要变更的字段

2. **及时持久化**：调用 `SetXXX()` 后必须通过 `Store.UpdateSession()` 持久化

3. **事务考虑**：在数据库事务中更新 Session 时，注意 Session 存储（Redis）与数据库事务的独立性

### 9.3 扩展 Context

如需添加新的 Context 字段：

1. 在 `Session` 结构体添加字段
2. 在 `SessionOptions` 添加对应字段
3. 在 `MakeContext` 中添加 `context.WithValue`
4. 添加对应的 getter 函数
5. 更新 `NewSession` 初始化逻辑

```go
// 示例：添加新字段
// 1. Session 结构体
type Session struct {
    // ... 已有字段
    NewField string `json:"new_field,omitempty"`
}

// 2. Context key
type contextKeyTypeNewField struct{}
var contextKeyNewField = contextKeyTypeNewField{}

// 3. Getter 函数
func GetNewField(ctx context.Context) string {
    return ctx.Value(contextKeyNewField).(string)
}

// 4. MakeContext 中添加
ctx = context.WithValue(ctx, contextKeyNewField, s.NewField)
```

---

## 相关文件

| 文件 | 说明 |
|------|------|
| `pkg/lib/authenticationflow/session.go` | Session 结构体、MakeContext |
| `pkg/lib/authenticationflow/context.go` | Context key 和 getter 函数 |
| `pkg/lib/authenticationflow/service.go` | 服务层、生命周期管理 |
| `pkg/lib/authenticationflow/store.go` | Redis 持久化实现 |
| `pkg/lib/authenticationflow/accept.go` | Accept 循环、流程执行 |
| `pkg/lib/authenticationflow/workflow.go` | Flow 结构体 |
