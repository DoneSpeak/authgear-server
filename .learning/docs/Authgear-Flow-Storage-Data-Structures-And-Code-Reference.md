# Authgear Flow 存储 - 数据结构与代码参考

> 本文档提供关键数据结构的详细字段说明和核心代码实现参考

---

## 一、核心数据结构字段详解

### 1.1 Flow 结构体

```go
// pkg/lib/authenticationflow/workflow.go

type Flow struct {
    FlowID     string   // 流程唯一标识
    StateToken string   // 当前状态令牌
    Intent     Intent   // 意图（定义流程类型）
    Nodes      []Node   // 已执行的节点路径
}
```

#### FlowID 生成规则

```go
// pkg/lib/authenticationflow/id.go

const (
    idAlphabet string = base32.Alphabet  // "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    idLength   int    = 32               // 32 字符长度
)

func newFlowID() string {
    return fmt.Sprintf("authflow_%v", corerand.StringWithAlphabet(idLength, idAlphabet, rng))
}

// 示例输出: authflow_XKJ2PL9MNR5VQ8T3WBYC6DFGHJ4KL7Z
```

**特点**:
- 前缀 `authflow_` 便于识别
- Base32 编码，URL 安全
- 32 字符随机部分，保证唯一性
- 使用加密安全随机数生成器

#### StateToken 生成规则

```go
func newStateToken() string {
    return fmt.Sprintf("authflowstate_%v", corerand.StringWithAlphabet(idLength, idAlphabet, rng))
}

// 示例输出: authflowstate_ABC123XYZ789DEF456GHJ...
```

**特点**:
- 前缀 `authflowstate_` 便于识别
- 每次创建新 Flow 时生成
- 相同 Flow 更新状态时也生成新的 StateToken

---

### 1.2 Node 结构体详解

```go
// pkg/lib/authenticationflow/node.go

type NodeType string

const (
    NodeTypeSimple  NodeType = "SIMPLE"    // 简单节点
    NodeTypeSubFlow NodeType = "SUB_FLOW"  // 子流程节点
)

type Node struct {
    Type    NodeType   `json:"type"`              // 节点类型标识
    Simple  NodeSimple `json:"simple,omitempty"`  // 简单节点数据（接口类型）
    SubFlow *Flow     `json:"flow,omitempty"`   // 子流程数据（指向完整 Flow）
}
```

#### 简单节点示例

```json
{
    "type": "SIMPLE",
    "simple": {
        "type": "identify",
        "options": [
            {"identification": "email"},
            {"identification": "phone"}
        ]
    }
}
```

#### 子流程节点示例（递归结构）

```json
{
    "type": "SUB_FLOW",
    "flow": {
        "flow_id": "authflow_xxx",
        "state_token": "authflowstate_yyy",
        "intent": {...},
        "nodes": [...]
    }
}
```

**递归特性**: Node 可以嵌套 Flow，Flow 又可以包含 Node，形成树状执行路径。

---

### 1.3 Session 结构体完整字段

```go
// pkg/lib/authenticationflow/session.go

type Session struct {
    // ==================== 核心标识 ====================
    FlowID string `json:"flow_id"`  // 关联的 Flow ID

    // ==================== 外部会话关联 ====================
    OAuthSessionID string `json:"oauth_session_id,omitempty"`  // OAuth 会话ID
    SAMLSessionID  string `json:"saml_session_id,omitempty"`   // SAML 会话ID

    // ==================== OAuth/OIDC 参数 ====================
    ClientID    string   `json:"client_id,omitempty"`    // OAuth 客户端ID
    RedirectURI string   `json:"redirect_uri,omitempty"`   // 回调地址
    Prompt      []string `json:"prompt,omitempty"`       // OIDC prompt 参数
    State       string   `json:"state,omitempty"`        // OAuth state 参数
    XState      string   `json:"x_state,omitempty"`      // 扩展 state
    UILocales   string   `json:"ui_locales,omitempty"`   // UI 语言偏好

    // ==================== 安全验证 ====================
    BotProtectionVerificationResult *BotProtectionVerificationResult `json:"bot_protection_verification_result,omitempty"`
    IDToken                         string                           `json:"id_token,omitempty"`
    SuppressIDPSessionCookie        bool                             `json:"suppress_idp_session_cookie,omitempty"`

    // ==================== 用户提示 ====================
    UserIDHint string `json:"user_id_hint,omitempty"`  // 用户ID提示
    LoginHint  string `json:"login_hint,omitempty"`    // 登录提示（如邮箱）
}
```

#### SessionOptions（创建参数）

```go
type SessionOptions struct {
    OAuthSessionID string
    SAMLSessionID  string

    ClientID    string
    RedirectURI string
    Prompt      []string
    State       string
    XState      string
    UILocales   string

    BotProtectionVerificationResult *BotProtectionVerificationResult
    IDToken                         string
    SuppressIDPSessionCookie        bool
    UserIDHint                      string
    LoginHint                       string
}
```

---

### 1.4 FlowResponse（API 响应）

```go
// pkg/lib/authenticationflow/flow.go

// FlowResponse 是 API 返回给客户端的数据结构
type FlowResponse struct {
    StateToken string      `json:"state_token"`        // 状态令牌
    Type       FlowType    `json:"type,omitempty"`     // 流程类型
    Name       string      `json:"name,omitempty"`     // 流程名称
    Action     *FlowAction `json:"action,omitempty"`   // 当前可执行操作
}
```

#### FlowAction（操作定义）

```go
type FlowActionType string

const (
    FlowActionTypeFinished FlowActionType = "finished"
)

type FlowAction struct {
    Type           FlowActionType                         `json:"type"`
    Identification model.AuthenticationFlowIdentification `json:"identification,omitempty"`
    Authentication model.AuthenticationFlowAuthentication `json:"authentication,omitempty"`
    Data           Data                                   `json:"data,omitempty"`
}
```

---

## 二、Redis 键命名详解

### 2.1 键格式规范

```
通用格式: app:{appID}:authenticationflow_{type}:{identifier}

组成部分:
├── app:{appID}                    # 应用ID前缀（多租户隔离）
├── authenticationflow_{type}      # 命名空间 + 数据类型
│   ├── _flow                      # 流程存在性标记
│   ├── _state                     # 流程状态数据
│   ├── _session                   # 会话数据
│   └── -events                    # WebSocket 事件频道
└── {identifier}                   # 标识符（flowID 或 stateToken）
```

### 2.2 实际键示例

```go
// 假设
appID := "my-application"
flowID := "authflow_XKJ2PL9MNR5VQ8T3WBYC6DFGHJ4KL7Z"
stateToken := "authflowstate_ABC123XYZ789DEF456GHJ2KL3MN4PQ5"
websocketChannel := "ws_9F8E7D6C5B4A3210ZYXWVUTSRQPONMLK"

// 生成的键
flowKey := "app:my-application:authenticationflow_flow:authflow_XKJ2PL9MNR5VQ8T3WBYC6DFGHJ4KL7Z"
stateKey := "app:my-application:authenticationflow_state:authflowstate_ABC123XYZ789DEF456GHJ2KL3MN4PQ5"
sessionKey := "app:my-application:authenticationflow_session:authflow_XKJ2PL9MNR5VQ8T3WBYC6DFGHJ4KL7Z"
eventChannel := "app:my-application:authenticationflow-events:ws_9F8E7D6C5B4A3210ZYXWVUTSRQPONMLK"
```

---

## 三、核心存储操作代码参考

### 3.1 Store 接口定义

```go
// pkg/lib/authenticationflow/service.go

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

### 3.2 StoreImpl 实现

```go
// pkg/lib/authenticationflow/store.go

type StoreImpl struct {
    Redis *appredis.Handle  // Redis 连接
    AppID config.AppID      // 应用ID（用于键前缀）
}
```

### 3.3 CreateFlow 完整实现

```go
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    // 1. 序列化 Flow 为 JSON
    bytes, err := json.Marshal(flow)
    if err != nil {
        return err
    }

    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        // 2. 构建键名
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        stateKey := redisFlowStateKey(s.AppID, flow.StateToken)
        ttl := Lifetime  // 20分钟

        // 3. 写入 flowKey（存在性标记）
        // 值就是键本身，表示该 flowID 存在
        _, err := conn.SetEx(ctx, flowKey, []byte(flowKey), ttl).Result()
        if err != nil {
            return err
        }

        // 4. 写入 stateKey（实际状态数据）
        _, err = conn.SetEx(ctx, stateKey, bytes, ttl).Result()
        if err != nil {
            return err
        }

        return nil
    })
}
```

### 3.4 GetFlowByStateToken 完整实现

```go
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    stateKey := redisFlowStateKey(s.AppID, stateToken)
    var flow Flow

    err := s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        // 1. 从 stateKey 读取序列化数据
        bytes, err := conn.Get(ctx, stateKey).Bytes()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound  // 状态不存在
        }
        if err != nil {
            return err
        }

        // 2. 反序列化为 Flow 对象
        err = json.Unmarshal(bytes, &flow)
        if err != nil {
            return err
        }

        // 3. 【关键】验证 flowKey 存在（双键验证）
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err = conn.Get(ctx, flowKey).Result()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound  // 流程已被删除
        }
        if err != nil {
            return err
        }

        return nil
    })

    return &flow, err
}
```

### 3.5 DeleteFlow 实现（关键优化点）

```go
func (s *StoreImpl) DeleteFlow(ctx context.Context, flow *Flow) error {
	// ⚠️ 重要：我们不删除状态键（state keys）
	// 因为流程执行过程中会产生多个历史状态
	// 删除 flowKey 就足够让 GetFlowByStateToken 返回 ErrFlowNotFound
	// 历史状态键会在 TTL 到期后自动清理

	return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
		flowKey := redisFlowKey(s.AppID, flow.FlowID)

		_, err := conn.Del(ctx, flowKey).Result()
		if err != nil {
			return err
		}

		return nil
	})
}
```

---

### 3.6 Flow 的保存与更新机制（核心设计模式）

#### 3.6.1 不可变状态设计

Authgear 的 Flow 存储采用**不可变状态（Immutable State）**模式。不同于传统的"读取-修改-保存"更新方式，每次状态变更都会创建一个新的 StateToken 和对应的状态记录。

```go
// 核心思想：每次交互生成新的 StateToken
// 旧的状态记录保留，直到 TTL 过期自动清理

// pkg/lib/authenticationflow/workflow.go
func NewFlow(flowID string, publicFlow PublicFlow) *Flow {
	return &Flow{
		FlowID:     flowID,                           // 保持不变
		StateToken: newStateToken(),                  // 每次都生成新的
		Intent:     publicFlow.FlowFlowRootObject(),
	}
}
```

#### 3.6.2 更新流程的实际实现

在服务层中，Flow 的"更新"实际上是通过重新调用 `CreateFlow` 实现的：

```go
// pkg/lib/authenticationflow/service.go

// feedInput 中的 Flow 保存逻辑（第 533-538 行）
func (s *Service) feedInput(...) (flow *Flow, flowAction *FlowAction, err error) {
	// ... 执行 Accept 处理输入 ...

	// err is nil or err is ErrEOF.
	// We persist the flow state.
	err = s.Store.CreateFlow(ctx, flow)  // 使用 CreateFlow "更新"状态
	if err != nil {
		return
	}
	// ...
}

// createNewFlow 中的保存逻辑（第 251-255 行）
func (s *Service) createNewFlow(...) (flow *Flow, flowAction *FlowAction, err error) {
	// ... 执行 Accept 推进流程 ...

	// err is nil or err is ErrEOF.
	// We persist the flow state.
	err = s.Store.CreateFlow(ctx, flow)  // 同样使用 CreateFlow
	if err != nil {
		return
	}
	// ...
}

// feedSyntheticInput 中的保存逻辑（第 594-599 行）
func (s *Service) feedSyntheticInput(...) (flow *Flow, flowAction *FlowAction, err error) {
	// ... 执行合成输入处理 ...

	// err is nil or err is ErrEOF.
	// We persist the flow state.
	err = s.Store.CreateFlow(ctx, flow)  // 同样使用 CreateFlow
	if err != nil {
		return
	}
	// ...
}
```

#### 3.6.3 CreateFlow 如何支持"更新"

关键在于 `CreateFlow` 实现的双键设计：

```go
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
	bytes, err := json.Marshal(flow)
	if err != nil {
		return err
	}

	return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
		flowKey := redisFlowKey(s.AppID, flow.FlowID)      // 保持不变
		stateKey := redisFlowStateKey(s.AppID, flow.StateToken)  // 每次都是新的
		ttl := Lifetime  // 20分钟

		// flowKey 被覆盖写入（幂等）
		_, err := conn.SetEx(ctx, flowKey, []byte(flowKey), ttl).Result()
		if err != nil {
			return err
		}

		// stateKey 是新键，创建新的状态记录
		_, err = conn.SetEx(ctx, stateKey, bytes, ttl).Result()
		if err != nil {
			return err
		}

		return nil
	})
}
```

#### 3.6.4 多状态键的积累与清理

随着时间推移，一个 Flow 会产生多个历史状态键：

```
时间线:
T0: 创建 Flow
    ├── flowKey: app:{id}:authenticationflow_flow:authflow_XXX (Value: "...")
    └── stateKey: app:{id}:authenticationflow_state:authflowstate_001 (Value: Flow JSON #1)

T1: 第一次输入处理
    ├── flowKey: app:{id}:authenticationflow_flow:authflow_XXX (重新写入，TTL 刷新)
    ├── stateKey: app:{id}:authenticationflow_state:authflowstate_001 (Value: Flow JSON #1, 保留)
    └── stateKey: app:{id}:authenticationflow_state:authflowstate_002 (Value: Flow JSON #2, 新键)

T2: 第二次输入处理
    ├── flowKey: app:{id}:authenticationflow_flow:authflow_XXX (重新写入，TTL 刷新)
    ├── stateKey: app:{id}:authenticationflow_state:authflowstate_001 (保留)
    ├── stateKey: app:{id}:authenticationflow_state:authflowstate_002 (保留)
    └── stateKey: app:{id}:authenticationflow_state:authflowstate_003 (Value: Flow JSON #3, 新键)

T3: 20分钟后（TTL 到期）
    └── 所有键自动过期清理
```

#### 3.6.5 历史状态的安全性

保留历史状态的设计带来以下优势：

1. **防止并发冲突**：如果用户同时打开两个页面，使用旧的 StateToken 仍能获取对应的历史状态
2. **容错性**：网络重试时，使用旧 StateToken 不会导致状态丢失
3. **简单的一致性模型**：无需处理复杂的并发锁或事务

```go
// GetFlowByStateToken 可以获取任意历史状态
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
	// stateToken 可以是任意历史状态令牌
	// 只要 flowKey 还存在（流程未结束），就能获取对应状态
	
	stateKey := redisFlowStateKey(s.AppID, stateToken)
	// ... 读取并验证 flowKey 存在性
}
```

#### 3.6.6 为什么没有 UpdateFlow 方法

```go
// Store 接口设计意图

type Store interface {
	// ...
	
	// Flow 操作
	CreateFlow(ctx context.Context, flow *Flow) error       // 创建/更新统一入口
	GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error)
	DeleteFlow(ctx context.Context, flow *Flow) error     // 只删除 flowKey，历史状态由 TTL 清理
	
	// 注意：没有 UpdateFlow 方法！
	// 更新 = CreateFlow(flowWithNewStateToken)
}
```

这种设计的权衡：

| 方面 | 优势 | 代价 |
|------|------|------|
| 实现复杂度 | 简单，无并发控制问题 | 存储多个历史状态副本 |
| 内存占用 | - | 短期增加（直到 TTL 到期）|
| 容错性 | 高，支持并发和重试 | - |
| 调试 | 可追溯完整状态历史 | - |

---

### 3.7 Session 操作实现

```go
// CreateSession - 创建新会话
func (s *StoreImpl) CreateSession(ctx context.Context, session *Session) error {
    bytes, err := json.Marshal(session)
    if err != nil {
        return err
    }

    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        sessionKey := redisFlowSessionKey(s.AppID, session.FlowID)
        ttl := Lifetime

        _, err := conn.SetEx(ctx, sessionKey, bytes, ttl).Result()
        return err
    })
}

// GetSession - 获取会话
func (s *StoreImpl) GetSession(ctx context.Context, flowID string) (*Session, error) {
    sessionKey := redisFlowSessionKey(s.AppID, flowID)
    var session Session

    err := s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        bytes, err := conn.Get(ctx, sessionKey).Bytes()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }
        if err != nil {
            return err
        }

        return json.Unmarshal(bytes, &session)
    })

    return &session, err
}

// UpdateSession - 更新会话（使用 SetXX，必须存在才更新）
func (s *StoreImpl) UpdateSession(ctx context.Context, session *Session) error {
    bytes, err := json.Marshal(session)
    if err != nil {
        return err
    }

    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        sessionKey := redisFlowSessionKey(s.AppID, session.FlowID)
        ttl := Lifetime

        // SetXX: 仅当 key 存在时才设置
        _, err = conn.SetXX(ctx, sessionKey, bytes, ttl).Result()
        return err
    })
}
```

---

## 四、JSON 序列化示例

### 4.1 完整 Flow JSON 示例

```json
{
    "flow_id": "authflow_XKJ2PL9MNR5VQ8T3WBYC6DFGHJ4KL7Z",
    "state_token": "authflowstate_ABC123XYZ789DEF456GHJ2KL3MN4PQ5",
    "intent": {
        "type": "login",
        "name": "default"
    },
    "nodes": [
        {
            "type": "SIMPLE",
            "simple": {
                "type": "identify",
                "options": [
                    {"identification": "email"},
                    {"identification": "phone"}
                ]
            }
        },
        {
            "type": "SIMPLE",
            "simple": {
                "type": "authenticate",
                "authentication": "password",
                "user_id": "user_123"
            }
        }
    ]
}
```

### 4.2 完整 Session JSON 示例

```json
{
    "flow_id": "authflow_XKJ2PL9MNR5VQ8T3WBYC6DFGHJ4KL7Z",
    "oauth_session_id": "oauthsession_987ZYX654WVU321TSR",
    "client_id": "my-client-app",
    "redirect_uri": "https://example.com/callback",
    "prompt": ["login"],
    "state": "xyz123",
    "ui_locales": "zh-CN,en",
    "login_hint": "user@example.com"
}
```

---

## 五、TTL 和过期策略

### 5.1 TTL 常量定义

```go
// pkg/util/duration/duration.go

const (
    // UserInteraction 是正常用户交互应该在之内完成的持续时间
    // 参考: OWASP Forgot Password Cheat Sheet
    // https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html
    UserInteraction = 20 * time.Minute
)
```

### 5.2 存储层 TTL 使用

```go
// pkg/lib/authenticationflow/store.go

const Lifetime = duration.UserInteraction  // 20分钟

// 所有 Redis 写入操作都使用此 TTL:
// - SET key value EX 1200  (20分钟 = 1200秒)
```

### 5.3 TTL 更新机制

```
流程:
1. 创建 Flow: flowKey 和 stateKey 设置 TTL=20分钟
2. 获取 Flow: 不更新 TTL（读取操作不影响过期时间）
3. 输入处理: 创建新 Flow（新 stateKey）+ 新 TTL
4. 删除 Flow: 只删除 flowKey，stateKey 依赖 TTL 过期

注意: Authgear 设计为每次交互创建新状态令牌
      因此 TTL 会在每次交互时自动刷新
```

---

## 六、错误处理规范

### 6.1 错误类型定义

```go
// pkg/lib/authenticationflow/errors.go

var ErrFlowNotFound = errors.New("flow not found")
var ErrFlowNotAllowed = errors.New("flow not allowed")
```

### 6.2 错误映射规则

| Redis 错误 | 业务错误 | 处理建议 |
|------------|----------|----------|
| `goredis.Nil` (键不存在) | `ErrFlowNotFound` | 返回 404 给客户端 |
| 连接错误 | 原始错误 | 返回 500，记录日志 |
| 序列化错误 | 原始错误 | 返回 500，记录日志 |

### 6.3 错误处理示例

```go
flow, err := store.GetFlowByStateToken(ctx, stateToken)
if err != nil {
    if errors.Is(err, ErrFlowNotFound) {
        // 返回 404，提示用户重新创建流程
        return nil, NewHTTPError(http.StatusNotFound, "flow not found")
    }
    // 其他错误，返回 500
    return nil, err
}
```

---

## 七、复刻实现的代码模板

### 7.1 最小可复刻版本

```go
package flowstorage

import (
    "context"
    "encoding/json"
    "errors"
    "fmt"
    "time"

    "github.com/redis/go-redis/v9"
)

const (
    Lifetime = 20 * time.Minute
    idLength = 32
)

// === 核心数据结构 ===

type Flow struct {
    FlowID     string          `json:"flow_id"`
    StateToken string          `json:"state_token"`
    Intent     json.RawMessage `json:"intent"`  // 简化处理，实际应为接口
    Nodes      []Node          `json:"nodes"`
}

type Node struct {
    Type   string          `json:"type"`
    Data   json.RawMessage `json:"data,omitempty"`
    SubFlow *Flow          `json:"flow,omitempty"`
}

type Session struct {
    FlowID      string `json:"flow_id"`
    ClientID    string `json:"client_id,omitempty"`
    RedirectURI string `json:"redirect_uri,omitempty"`
}

// === 存储实现 ===

type Store struct {
    client *redis.Client
    appID  string
}

func NewStore(client *redis.Client, appID string) *Store {
    return &Store{client: client, appID: appID}
}

func (s *Store) flowKey(flowID string) string {
    return fmt.Sprintf("app:%s:flow:%s", s.appID, flowID)
}

func (s *Store) stateKey(stateToken string) string {
    return fmt.Sprintf("app:%s:state:%s", s.appID, stateToken)
}

func (s *Store) sessionKey(flowID string) string {
    return fmt.Sprintf("app:%s:session:%s", s.appID, flowID)
}

// CreateFlow 双键写入
func (s *Store) CreateFlow(ctx context.Context, flow *Flow) error {
    data, err := json.Marshal(flow)
    if err != nil {
        return err
    }

    pipe := s.client.Pipeline()
    pipe.SetEX(ctx, s.flowKey(flow.FlowID), s.flowKey(flow.FlowID), Lifetime)
    pipe.SetEX(ctx, s.stateKey(flow.StateToken), data, Lifetime)

    _, err = pipe.Exec(ctx)
    return err
}

// GetFlowByStateToken 双键验证读取
func (s *Store) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    // 1. 读取状态
    data, err := s.client.Get(ctx, s.stateKey(stateToken)).Bytes()
    if errors.Is(err, redis.Nil) {
        return nil, errors.New("flow not found")
    }
    if err != nil {
        return nil, err
    }

    var flow Flow
    if err := json.Unmarshal(data, &flow); err != nil {
        return nil, err
    }

    // 2. 验证流程存在
    exists, err := s.client.Exists(ctx, s.flowKey(flow.FlowID)).Result()
    if err != nil {
        return nil, err
    }
    if exists == 0 {
        return nil, errors.New("flow not found")
    }

    return &flow, nil
}

// DeleteFlow 只删除 flowKey
func (s *Store) DeleteFlow(ctx context.Context, flowID string) error {
    return s.client.Del(ctx, s.flowKey(flowID)).Err()
}

// Session 操作
func (s *Store) CreateSession(ctx context.Context, session *Session) error {
    data, err := json.Marshal(session)
    if err != nil {
        return err
    }
    return s.client.SetEX(ctx, s.sessionKey(session.FlowID), data, Lifetime).Err()
}

func (s *Store) GetSession(ctx context.Context, flowID string) (*Session, error) {
    data, err := s.client.Get(ctx, s.sessionKey(flowID)).Bytes()
    if errors.Is(err, redis.Nil) {
        return nil, errors.New("session not found")
    }
    if err != nil {
        return nil, err
    }

    var session Session
    err = json.Unmarshal(data, &session)
    return &session, err
}
```

---

*文档结束*
