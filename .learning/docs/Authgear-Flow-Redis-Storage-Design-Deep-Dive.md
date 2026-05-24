# Authgear Flow Redis 存储设计深度调研

> 调研日期: 2025-05-18
> 调研范围: Authgear Server 认证流程 (Authentication Flow) 的 Redis 存储管理机制

---

## 一、概述

Authgear 使用 Redis 作为认证流程 (Authentication Flow) 的状态存储后端。这种设计选择基于以下考量：

1. **高性能**: 认证流程需要频繁的读写操作，Redis 的低延迟特性非常适合
2. **过期管理**: 流程状态有明确的生命周期，Redis 的 TTL 机制天然支持
3. **分布式支持**: 多实例部署时需要共享状态，Redis 提供集中式存储
4. **WebSocket 支持**: Redis Pub/Sub 支持实时事件推送

---

## 二、核心设计原则

### 2.1 双键设计模式 (Dual-Key Pattern)

这是 Authgear Flow 存储最核心的设计模式：

```
┌─────────────────────────────────────────────────────────────┐
│                      Flow 存储双键模式                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Flow Key (存在性标记)         State Key (状态数据)            │
│   ┌─────────────────────┐      ┌─────────────────────┐       │
│   │ app:{id}:flow:{fid} │      │ app:{id}:state:{st} │       │
│   │ Value = Key本身      │      │ Value = JSON 序列化  │       │
│   │ TTL = 20分钟         │      │   Flow 对象         │       │
│   │                     │      │ TTL = 20分钟         │       │
│   └─────────────────────┘      └─────────────────────┘       │
│           │                            │                    │
│           │  验证存在性                   │  读取/写入状态        │
│           └──────────────┬─────────────┘                    │
│                          │                                 │
│                    GetFlowByStateToken                       │
│                    1. 读 stateKey → Flow                   │
│                    2. 验证 flowKey 存在                     │
│                    3. 两者都存在 → 有效                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**设计意图**:

- **flowKey**: 轻量级存在性标记，用于快速验证流程是否有效
- **stateKey**: 存储完整的状态数据，每次状态变更生成新的 StateToken
- **删除优化**: 删除流程只需删除 flowKey，所有历史 stateKey 自动过期

### 2.2 不可变状态设计 (Immutable State)

每次流程状态更新时，生成新的 StateToken 并创建新的 stateKey：

```go
// workflow.go - Flow 结构体
func NewFlow(flowID string, intent Intent) *Flow {
    return &Flow{
        FlowID:     flowID,           // 保持不变
        StateToken: newStateToken(),  // 每次新建都生成新的
        Intent:     intent,
    }
}
```

**优势**:

- 历史状态可追溯（在 TTL 期内）
- 防止并发修改冲突
- 支持幂等性操作

### 2.3 会话与状态分离 (Session-State Separation)

```
┌──────────────────────────────────────────────────────────────┐
│                    数据分离架构                                │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   Session (长期数据)              Flow State (短期数据)          │
│   ┌─────────────────────┐       ┌─────────────────────┐       │
│   │ FlowID              │◄──────│ FlowID              │       │
│   │ OAuthSessionID      │       │ StateToken (变)      │       │
│   │ ClientID            │       │ Intent              │       │
│   │ RedirectURI         │       │ Nodes (执行路径)     │       │
│   │ ...                 │       │                     │       │
│   └─────────────────────┘       └─────────────────────┘       │
│                                                              │
│   Key: session:{flowID}          Key: state:{stateToken}       │
│   生命周期: 与流程相同              生命周期: 每次变更创建新的    │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**设计理由**:

- Session 包含 OAuth/SAML 相关的长期配置信息
- Flow State 包含执行路径，频繁变更
- 分离后可以减少 Session 的读写次数

---

## 三、关键数据结构详解

### 3.1 Flow 结构体 (流程状态)

```go
// workflow.go
package authenticationflow

type Flow struct {
    FlowID     string   // 流程唯一标识，整个生命周期保持不变
    StateToken string   // 状态令牌，每次更新都生成新的
    Intent     Intent   // 意图对象，定义流程类型和目标
    Nodes      []Node   // 执行节点列表，记录执行路径
}
```

**字段说明**:


| 字段           | 类型       | 说明                              | 序列化        |
| ------------ | -------- | ------------------------------- | ---------- |
| `FlowID`     | `string` | 格式: `authflow_{32位base32}`      | 是          |
| `StateToken` | `string` | 格式: `authflowstate_{32位base32}` | 是          |
| `Intent`     | `Intent` | 接口类型，具体类型取决于流程                  | 是 (通过接口实现) |
| `Nodes`      | `[]Node` | 执行节点数组，支持嵌套子流程                  | 是          |


### 3.2 Node 结构体 (执行节点)

```go
// node.go
package authenticationflow

type NodeType string

const (
    NodeTypeSimple  NodeType = "SIMPLE"   // 简单节点
    NodeTypeSubFlow NodeType = "SUB_FLOW" // 子流程节点
)

type Node struct {
    Type    NodeType   `json:"type"`              // 节点类型
    Simple  NodeSimple `json:"simple,omitempty"`  // 简单节点数据
    SubFlow *Flow     `json:"flow,omitempty"`   // 子流程数据（递归结构）
}
```

**嵌套设计**: SubFlow 类型支持在 Node 中嵌套完整的 Flow 结构，实现子流程支持。

### 3.3 Session 结构体 (会话信息)

```go
// session.go
package authenticationflow

type Session struct {
    // 核心标识
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

    // 安全和验证
    BotProtectionVerificationResult *BotProtectionVerificationResult `json:"bot_protection_verification_result,omitempty"`
    IDToken                         string                           `json:"id_token,omitempty"`
    SuppressIDPSessionCookie        bool                             `json:"suppress_idp_session_cookie,omitempty"`

    // 用户提示
    UserIDHint string `json:"user_id_hint,omitempty"`
    LoginHint  string `json:"login_hint,omitempty"`
}
```

**字段分类说明**:


| 分类        | 字段                                                     | 用途              |
| --------- | ------------------------------------------------------ | --------------- |
| **关联**    | `OAuthSessionID`, `SAMLSessionID`                      | 关联外部认证会话        |
| **OAuth** | `ClientID`, `RedirectURI`, `Prompt`, `State`, `XState` | OAuth/OIDC 协议参数 |
| **安全**    | `BotProtectionVerificationResult`                      | 防机器人验证结果        |
| **提示**    | `UserIDHint`, `LoginHint`                              | 用户识别提示          |


### 3.4 存储键命名规范

```go
// store.go - Redis 键生成函数

func redisFlowKey(appID config.AppID, flowID string) string {
    return fmt.Sprintf("app:%s:authenticationflow_flow:%s", appID, flowID)
    // 示例: app:myapp:authenticationflow_flow:authflow_abc123...
}

func redisFlowStateKey(appID config.AppID, stateToken string) string {
    return fmt.Sprintf("app:%s:authenticationflow_state:%s", appID, stateToken)
    // 示例: app:myapp:authenticationflow_state:authflowstate_xyz789...
}

func redisFlowSessionKey(appID config.AppID, flowID string) string {
    return fmt.Sprintf("app:%s:authenticationflow_session:%s", appID, flowID)
    // 示例: app:myapp:authenticationflow_session:authflow_abc123...
}
```

**命名规则**:

- 前缀: `app:{appID}` - 支持多租户隔离
- 命名空间: `authenticationflow_{type}` - 明确数据类型
- 标识符: `{flowID}` 或 `{stateToken}` - 唯一标识

---

## 四、关键操作流程

### 4.1 创建流程 (Create Flow)

```
┌─────────────────────────────────────────────────────────────┐
│                      创建流程时序图                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Client          Service          Store          Redis       │
│    │                │                │                │   │
│    │ CreateNewFlow  │                │                │   │
│    │───────────────>│                │                │   │
│    │                │                │                │   │
│    │                │ NewSession()   │                │   │
│    │                │───────────────>│                │   │
│    │                │ CreateSession  │                │   │
│    │                │───────────────>│                │   │
│    │                │                │ SET session:{fid} │   │
│    │                │                │───────────────>│   │
│    │                │                │                │   │
│    │                │ NewFlow()      │                │   │
│    │                │───────────────>│                │   │
│    │                │ CreateFlow     │                │   │
│    │                │───────────────>│                │   │
│    │                │                │                │   │
│    │                │                │  1. SET flow:{fid}  │   │
│    │                │                │  2. SET state:{st}  │   │
│    │                │                │────────────────────>│   │
│    │                │                │                │   │
│    │     StateToken │                │                │   │
│    │<───────────────│                │                │   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**关键代码**:

```go
// store.go
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    bytes, err := json.Marshal(flow)  // JSON 序列化
    if err != nil {
        return err
    }

    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        stateKey := redisFlowStateKey(s.AppID, flow.StateToken)
        ttl := Lifetime  // 20分钟

        // 1. 写入 flowKey (存在性标记)
        _, err := conn.SetEx(ctx, flowKey, []byte(flowKey), ttl).Result()
        if err != nil {
            return err
        }

        // 2. 写入 stateKey (实际状态数据)
        _, err = conn.SetEx(ctx, stateKey, bytes, ttl).Result()
        if err != nil {
            return err
        }

        return nil
    })
}
```

### 4.2 获取流程 (Get Flow)

```go
// store.go
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    stateKey := redisFlowStateKey(s.AppID, stateToken)
    var flow Flow
    err := s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        // 1. 读取状态数据
        bytes, err := conn.Get(ctx, stateKey).Bytes()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }

        // 2. 反序列化
        err = json.Unmarshal(bytes, &flow)
        if err != nil {
            return err
        }

        // 3. 验证流程存在性（双键验证）
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err = conn.Get(ctx, flowKey).Result()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound  // 流程已被删除
        }

        return nil
    })
    return &flow, err
}
```

**双键验证逻辑**:

1. 先通过 `stateToken` 获取 `stateKey` 中的数据
2. 从数据中提取 `FlowID`
3. 验证对应的 `flowKey` 是否存在
4. 只有两者都存在，才认为流程有效

### 4.3 删除流程 (Delete Flow)

```go
// store.go
func (s *StoreImpl) DeleteFlow(ctx context.Context, flow *Flow) error {
    // 我们不删除状态键，因为有很多历史状态
    // 删除 flowKey 就足够让 GetFlowByStateToken 返回 ErrFlowNotFound
    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err := conn.Del(ctx, flowKey).Result()
        return err
    })
}
```

**删除策略**:

- 仅删除 `flowKey`（存在性标记）
- 所有历史 `stateKey` 保留，通过 TTL 自动过期
- 这种设计避免了批量删除的开销

### 4.4 输入处理流程 (Feed Input)

```
┌──────────────────────────────────────────────────────────────┐
│                    输入处理完整流程                             │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  1. GetFlowByStateToken(stateToken)                          │
│           ↓                                                  │
│  2. GetSession(flowID)  ← 从 session:{flowID} 获取           │
│           ↓                                                  │
│  3. ApplyRunEffects()  ← 执行运行期副作用                      │
│           ↓                                                  │
│  4. Accept(input)  ← 处理输入，驱动状态机                      │
│           ↓                                                  │
│  5. processAcceptResult()                                    │
│     - 更新 Session（如防机器人验证结果）                        │
│     - 执行 DelayedOneTimeFunctions                           │
│           ↓                                                  │
│  6. CreateFlow(newFlow)  ← 保存新状态（新 StateToken）          │
│           ↓                                                  │
│  7. 如果 ErrEOF（流程结束）:                                   │
│     - ApplyAllEffects()  ← 执行所有提交期副作用                 │
│     - CollectCookies()                                       │
│     - DeleteSession()                                        │
│     - DeleteFlow()                                           │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 五、TTL 与生命周期管理

### 5.1 TTL 设置

```go
// store.go
const Lifetime = duration.UserInteraction  // 20分钟

// duration/duration.go
const (
    // UserInteraction 是正常用户交互应该在之内完成的持续时间
    // 参考: OWASP Forgot Password Cheat Sheet
    UserInteraction = 20 * time.Minute
)
```

### 5.2 生命周期状态图

```
┌──────────┐     Create      ┌──────────┐
│   初始   │ ──────────────> │  进行中   │
│  (None)  │                 │ (Active) │
└──────────┘                 └────┬─────┘
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
              ▼                   ▼                   ▼
        ┌──────────┐       ┌──────────┐       ┌──────────┐
        │   完成   │       │   超时   │       │   删除   │
        │(Finished)│       │(Expired) │       │(Deleted) │
        └──────────┘       └──────────┘       └──────────┘
              │                   │                   │
              │                   │                   │
              └───────────────────┴───────────────────┘
                                  │
                                  ▼
                           ┌──────────┐
                           │  清理完成  │
                           │ (Cleaned)│
                           └──────────┘
```

---

## 六、WebSocket 事件存储

### 6.1 发布机制

```go
// websocket.go
type WebsocketEventStore struct {
    AppID       config.AppID
    RedisHandle *appredis.Handle
    Store       Store
    publisher   *pubsub.Publisher
}

func (s *WebsocketEventStore) Publish(ctx context.Context, websocketChannelName string, e Event) error {
    channelName := s.ChannelName(websocketChannelName)
    b, err := json.Marshal(e)
    if err != nil {
        return err
    }
    return s.publisher.Publish(ctx, channelName, b)
}

func (s *WebsocketEventStore) ChannelName(websocketChannelName string) string {
    return fmt.Sprintf("app:%s:authenticationflow-events:%s", s.AppID, websocketChannelName)
}
```

**Redis Pub/Sub 频道命名**:

```
app:{appID}:authenticationflow-events:{websocketChannelName}
```

### 6.2 使用场景

- 实时通知客户端流程状态变化
- 支持多设备同步
- 替代轮询，降低服务器负载

---

## 七、如果要复刻此设计，需要特别注意什么？

### 7.1 必需要点


| 要点           | 说明                            | 重要性   |
| ------------ | ----------------------------- | ----- |
| **双键设计**     | 必须实现 flowKey + stateKey 的双键模式 | ⭐⭐⭐⭐⭐ |
| **不可变状态**    | 每次更新生成新 StateToken，不修改旧状态     | ⭐⭐⭐⭐⭐ |
| **TTL 管理**   | 所有键必须有合理的 TTL，防止内存泄漏          | ⭐⭐⭐⭐⭐ |
| **JSON 序列化** | Flow 结构体需要支持完整的 JSON 序列化      | ⭐⭐⭐⭐  |
| **事务边界**     | 区分运行期副作用和提交期副作用               | ⭐⭐⭐⭐  |


### 7.2 常见陷阱

```
⚠️ 陷阱 1: 忘记双键验证
   错误: 只验证 stateKey 存在就认为流程有效
   正确: 必须同时验证 flowKey 和 stateKey

⚠️ 陷阱 2: 可变状态设计
   错误: 直接修改 Redis 中的状态
   正确: 创建新状态，让旧状态通过 TTL 过期

⚠️ 陷阱 3: 键名冲突
   错误: 不使用 appID 前缀
   正确: 必须使用 app:{appID} 前缀实现多租户隔离

⚠️ 陷阱 4: 删除不彻底
   错误: 删除时清理所有历史 stateKey
   正确: 只删除 flowKey，依赖 TTL 清理 stateKey

⚠️ 陷阱 5: 会话与状态耦合
   错误: 把 Session 数据放在 Flow State 中
   正确: 分离 Session 和 Flow State，独立存储
```

### 7.3 实现建议

1. **键命名**: 遵循 `app:{appID}:{namespace}:{type}:{id}` 格式
2. **序列化**: 使用 JSON，确保所有嵌套结构都能正确序列化
3. **错误处理**: 明确区分 `ErrFlowNotFound` 和其他错误
4. **监控**: 添加指标监控 Redis 操作延迟和错误率
5. **测试**: 模拟 Redis 故障和 TTL 过期场景

---

## 八、数据流向总结图

```
┌──────────────────────────────────────────────────────────────────────┐
│                        完整数据流向图                                  │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   客户端                                                              │
│     │                                                                │
│     │ 1. POST /api/v1/authentication_flows                           │
│     │    { "type": "login", "name": "default" }                      │
│     ▼                                                                │
│   API Handler                                                        │
│     │                                                                │
│     │ 2. service.CreateNewFlow()                                     │
│     ▼                                                                │
│   Service                                                           │
│     │                                                                │
│     ├──► 3. store.CreateSession(session) ─────► Redis: SET session:{fid}
│     │                                                                │
│     ├──► 4. InstantiateFlow() → Intent                              │
│     │                                                                │
│     ├──► 5. Accept() → 驱动状态机                                    │
│     │                                                                │
│     └──► 6. store.CreateFlow(flow) ───────────► Redis: SET flow:{fid}  │
│                                                  SET state:{st}      │
│     │                                                                │
│     │ 7. 返回 { "state_token": "..." }                                │
│     ▼                                                                │
│   客户端  ◄──────────────────────────────────────────────────────────┤
│     │                                                                │
│     │ 8. POST /api/v1/authentication_flows/input                     │
│     │    { "state_token": "...", "input": {...} }                    │
│     ▼                                                                │
│   API Handler                                                        │
│     │                                                                │
│     │ 9. service.FeedInput()                                         │
│     ▼                                                                │
│   Service                                                           │
│     │                                                                │
│     ├──► 10. store.GetFlowByStateToken(st) ◄── Redis: GET state:{st} │
│     │                              └──► GET flow:{fid} (验证)        │
│     │                                                                │
│     ├──► 11. store.GetSession(fid) ◄────────── Redis: GET session:{fid}
│     │                                                                │
│     ├──► 12. Accept(input) → 驱动状态机                                │
│     │                                                                │
│     ├──► 13. store.CreateFlow(newFlow) ─────► Redis: SET flow:{fid}  │
│     │                                            SET state:{new_st}│
│     │                                                                │
│     └──► 14. 如果结束: store.DeleteSession/Flow ──► DEL flow:{fid}   │
│                                                    (保留 state keys)  │
│     │                                                                │
│     ▼                                                                │
│   客户端  ◄──────────────────────────────────────────────────────────┤
│     │                                                                │
│     │ 15. WebSocket: wss://...?channel={ws_channel}                 │
│     ▼                                                                │
│   WebSocket Handler                                                  │
│     │                                                                │
│     └──► 16. Subscribe to Redis Pub/Sub                              │
│                Channel: app:{id}:authenticationflow-events:{channel}   │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 九、相关源码文件


| 文件路径                                      | 说明                 |
| ----------------------------------------- | ------------------ |
| `pkg/lib/authenticationflow/store.go`     | 核心存储实现 (StoreImpl) |
| `pkg/lib/authenticationflow/workflow.go`  | Flow 结构体定义         |
| `pkg/lib/authenticationflow/session.go`   | Session 结构体定义      |
| `pkg/lib/authenticationflow/node.go`      | Node 结构体定义         |
| `pkg/lib/authenticationflow/id.go`        | ID 生成工具            |
| `pkg/lib/authenticationflow/websocket.go` | WebSocket 事件存储     |
| `pkg/lib/authenticationflow/service.go`   | 服务层，协调存储操作         |
| `pkg/lib/authenticationflow/flow.go`      | Flow 类型和注册         |
| `pkg/lib/infra/redis/appredis/handle.go`  | Redis 连接管理         |
| `pkg/util/duration/duration.go`           | TTL 常量定义           |


---

*文档结束*