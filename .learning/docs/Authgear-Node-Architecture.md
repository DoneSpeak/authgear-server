# Authgear Authentication Flow Node 架构设计

## 1. Node 基础设计

### 1.1 Node 类型定义

Authgear 的认证流程使用两种基本 Node 类型：

```go
type NodeType string

const (
    NodeTypeSimple  NodeType = "SIMPLE"   // 简单节点：执行具体操作
    NodeTypeSubFlow NodeType = "SUB_FLOW" // 子流程节点：嵌套完整流程
)

type Node struct {
    Type    NodeType   `json:"type"`
    Simple  NodeSimple `json:"simple,omitempty"`
    SubFlow *Flow      `json:"flow,omitempty"`
}
```

### 1.2 NodeSimple 接口

所有简单节点必须实现的基本接口：

```go
type NodeSimple interface {
    Kinder  // Kind() string 方法，返回节点类型标识
}
```

### 1.3 创建节点

```go
// 创建简单节点
func NewNodeSimple(simple NodeSimple) *Node {
    return &Node{
        Type:   NodeTypeSimple,
        Simple: simple,
    }
}

// 创建子流程节点
func NewSubFlow(intent Intent) *Node {
    return &Node{
        Type: NodeTypeSubFlow,
        SubFlow: &Flow{
            Intent: intent,
        },
    }
}
```

## 2. Node 能力接口（Capability-Based Design）

Node 通过实现不同的接口来声明其能力：

| 接口 | 功能 | 典型实现 |
|------|------|----------|
| `InputReactor` | 响应用户输入 | `CanReactTo()` / `ReactTo()` |
| `EffectGetter` | 执行副作用（数据库操作） | `GetEffects()` |
| `DataOutputer` | 返回 API 响应数据 | `OutputData()` |
| `Milestone` | 标记流程状态 | `Milestone()` |
| `CookieGetter` | 设置 HTTP Cookie | `GetCookies()` |
| `AuthenticationInfoEntryGetter` | 返回 OAuth 认证信息 | - |

### 2.1 InputReactor 接口

```go
type InputReactor interface {
    CanReactTo(ctx context.Context, deps *Dependencies, flows Flows) (InputSchema, error)
    ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (ReactToResult, error)
}
```

**ReactTo 返回的特殊错误：**
- `ErrEOF` - 流程完成
- `ErrIncompatibleInput` - 输入类型不匹配
- `ErrReplaceNode` - 替换当前节点（如验证码重发）
- `ErrSameNode` - 节点不变但状态已更新

### 2.2 EffectGetter 接口

```go
type EffectGetter interface {
    GetEffects(ctx context.Context, deps *Dependencies, flows Flows) ([]Effect, error)
}

// Effect 类型包括：
// - RunEffect: 立即执行的副作用
// - EffectOnCommit: 事务提交后执行
// - EffectOnRollback: 事务回滚时执行
```

### 2.3 延迟执行函数（DelayedOneTimeFunction）

用于需要在事务外执行的操作（如发送邮件）：

```go
type DelayedOneTimeFunction func(ctx context.Context, deps *Dependencies) error

type NodeWithDelayedOneTimeFunction struct {
    Node                   *Node
    DelayedOneTimeFunction DelayedOneTimeFunction
}
```

## 3. Node vs SubFlow 决策逻辑

### 3.1 何时使用 Node

- **原子操作**：单一、明确的职责（如创建用户、验证 OTP）
- **无副作用或单一副作用**：数据库写操作明确
- **即时完成**：不需要多步骤交互

### 3.2 何时使用 SubFlow

- **复杂交互流程**：需要多步骤用户输入（如 OAuth 授权）
- **可复用流程**：身份创建、冲突检查等通用流程
- **递归结构**：需要嵌套其他步骤或分支

### 3.3 决策示例

```go
// OAuth 登录 - 使用 SubFlow 因为需要跳转和回调
func (n *NodeOAuth) reactTo(...) (authflow.ReactToResult, error) {
    if n.NewUserID != "" {
        // 创建身份需要检查冲突，使用子流程
        return authflow.NewSubFlow(&IntentCheckConflictAndCreateIdenity{
            JSONPointer: n.JSONPointer,
            UserID:      n.NewUserID,
            Request:     NewCreateOAuthIdentityRequest(spec),
        }), nil
    }
    // 登录使用已有身份，直接返回简单节点
    return NewNodeDoUseIdentityWithUpdate(ctx, deps, flows, exactMatch, spec)
}
```

## 4. 持久化机制

### 4.1 Redis 存储结构

```go
const Lifetime = duration.UserInteraction  // 用户交互生命周期 TTL

// 存储键格式：
// Flow:     app:{appID}:authenticationflow_flow:{flowID}
// State:    app:{appID}:authenticationflow_state:{stateToken}
// Session:  app:{appID}:authenticationflow_session:{flowID}
```

### 4.2 序列化/反序列化（Registry 模式）

```go
// 全局注册表
var nodeRegistry = map[string]nodeFactory{}
var intentRegistry = map[string]intentFactory{}

// 节点注册（在 init() 中）
func init() {
    authflow.RegisterNode(&NodeDoCreateUser{})
}

// JSON 格式（类型鉴别器 + 数据）
type nodeSimpleJSON struct {
    Kind string          `json:"kind"`
    Data json.RawMessage `json:"data"`
}
```

### 4.3 存储操作

```go
// 创建流程
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    bytes, _ := json.Marshal(flow)
    // SETEX with TTL
    conn.SetEx(ctx, flowKey, []byte(flowKey), ttl)
    conn.SetEx(ctx, stateKey, bytes, ttl)
}

// 获取流程（通过 StateToken）
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    // 1. 获取状态数据
    bytes, _ := conn.Get(ctx, stateKey).Bytes()
    json.Unmarshal(bytes, &flow)
    // 2. 验证 flowID 存在（防止已删除流程）
    conn.Get(ctx, flowKey).Result()
}

// 删除流程（软删除 - 只删 flowKey）
func (s *StoreImpl) DeleteFlow(ctx context.Context, flow *Flow) error {
    // 不删除 state 键（可能很多），删除 flowKey 即可使 GetFlowByStateToken 返回 ErrFlowNotFound
    conn.Del(ctx, flowKey)
}
```

### 4.4 更新机制

**Flow 是否会被更新？**
- **是**：每次 `Accept` 操作后，如果流程状态改变，会生成新的 `StateToken` 并保存到 Redis
- **StateToken 机制**：每次变更生成新 token，旧 token 自动过期（TTL 相同）
- **Session 更新**：支持显式 `UpdateSession` 操作（使用 `SETXX` 确保键存在）

```go
// Accept 循环中的更新逻辑
defer func() {
    if changed {
        flows.Nearest.StateToken = newStateToken()  // 生成新状态令牌
    }
}()
```

## 5. 所有 Node 类型清单

### 5.1 Action Nodes（Do* 模式 - 执行副作用）

| Node | 职能 | 存储数据 | 实现接口 | 特殊功能 |
|------|------|----------|----------|----------|
| `NodeDoCreateUser` | 创建用户 | `UserID`, `SkipCreation` | `Milestone`, `MilestoneDoUseUser`, `MilestoneDoCreateUser`, `EffectGetter` | 支持跳过创建（使用现有用户） |
| `NodeDoCreateIdentity` | 创建身份 | `SkipCreate`, `Identity`, `IdentitySpec` | `Milestone`, `MilestoneDoCreateIdentity`, `EffectGetter`, `InputReactor` | 支持跳过创建、更新身份 |
| `NodeDoCreateAuthenticator` | 创建认证器 | - | `EffectGetter` | - |
| `NodeDoCreateSession` | 创建会话 | `Session`, `SessionCookie` | `Milestone`, `MilestoneDoCreateSession`, `EffectGetter`, `CookieGetter` | 设置 Cookie |
| `NodeDoCreatePasskey` | 创建 Passkey | `Identity`, `Authenticator` | `Milestone`, `MilestoneDoCreateIdentity`, `EffectGetter` | 同时创建身份和认证器 |
| `NodeDoCreateDeviceToken` | 创建设备令牌 | - | `EffectGetter`, `CookieGetter` | 设置设备 Cookie |
| `NodeDoUseIdentity` | 使用身份（登录） | - | `Milestone`, `MilestoneDoUseUser`, `MilestoneDoUseIdentity`, `EffectGetter` | 支持匿名用户升级 |
| `NodeDoUseIdentityPasskey` | 使用 Passkey 登录 | `Options` | `InputReactor`, `DataOutputer`, `Milestone`, `EffectGetter` | Passkey 认证 |
| `NodeDoUseIdentityWithUpdate` | 使用并更新身份 | - | `Milestone`, `MilestoneDoUseUser`, `EffectGetter` | OAuth 身份更新 |
| `NodeDoUseAuthenticatorPassword` | 使用密码认证 | - | `InputReactor`, `Milestone`, `MilestoneDidAuthenticate`, `EffectGetter` | 密码验证 |
| `NodeDoUseAuthenticatorPasskey` | 使用 Passkey 认证 | - | `InputReactor`, `Milestone`, `MilestoneDidAuthenticate`, `EffectGetter` | Passkey 验证 |
| `NodeDoUseAuthenticatorSimple` | 简单认证器（TOTP/恢复码） | - | `Milestone`, `MilestoneDidAuthenticate`, `EffectGetter` | TOTP、恢复码认证 |
| `NodeDoUseDeviceToken` | 使用设备令牌 | - | `Milestone`, `MilestoneDidAuthenticate` | 免密登录 |
| `NodeDoUseAnonymousUser` | 使用匿名身份 | `UserID` | `Milestone`, `MilestoneDoUseUser` | 匿名用户识别 |
| `NodeDoUseAccountRecoveryIdentity` | 使用恢复身份 | `Identity` | `Milestone`, `EffectGetter` | 恢复流程 |
| `NodeDoUseIDToken` | 使用 ID Token | `IdentitySpec`, `IdentityUpdate`, `ProviderIDToken`, `IdentityID` | `Milestone`, `MilestoneDoCreateIdentity`, `MilestoneDoUseUser`, `EffectGetter` | ID Token 登录 |
| `NodeDoResetPassword` | 重置密码 | - | `EffectGetter` | 账户恢复 |
| `NodeDoMarkClaimVerified` | 标记声明已验证 | `Claim` | `EffectGetter` | 验证流程 |
| `NodeDoSendAccountRecoveryCode` | 发送恢复码 | - | `EffectGetter` | 恢复流程 |
| `NodeDoConsumeRecoveryCode` | 消费恢复码 | - | `Milestone`, `MilestoneDidAuthenticate`, `EffectGetter` | 认证流程 |
| `NodeDoReplaceRecoveryCode` | 替换恢复码 | - | `EffectGetter` | 恢复码重置 |
| `NodeDoUpdateUserProfile` | 更新用户资料 | - | `EffectGetter` | 注册后更新 |
| `NodeDoUpdateAuthenticator` | 更新认证器 | - | `EffectGetter` | 修改认证器 |
| `NodeDoUpdatePreferredChannel` | 更新首选渠道 | - | `EffectGetter` | 更新 OOB 渠道 |
| `NodeDoPopulateStandardAttributes` | 填充标准属性 | - | `EffectGetter` | 注册时设置属性 |
| `NodeDoForceChangePassword` | 强制修改密码 | - | `EffectGetter` | 密码过期处理 |
| `NodeDoClearDeviceTokenCookie` | 清除设备令牌 | - | `EffectGetter`, `CookieGetter` | 清除 Cookie |
| `NodeDoJustInTimeCreateAuthenticator` | JIT 创建认证器 | - | `EffectGetter` | 自动创建 MFA |

### 5.2 Interactive/Prompt Nodes（等待用户输入）

| Node | 职能 | 存储数据 | 实现接口 | 特殊功能 |
|------|------|----------|----------|----------|
| `NodeVerifyClaim` | OTP 验证（邮箱/短信） | `JSONPointer`, `UserID`, `Purpose`, `ClaimName`, `ClaimValue`, `Channel`, `WebsocketChannelName` | `InputReactor`, `DataOutputer` | 支持重发、检查状态、WebSocket 通知 |
| `NodePromptCreatePasskey` | 提示创建 Passkey | `JSONPointer`, `Options`, `Data` | `InputReactor`, `DataOutputer` | Passkey 注册 UI |
| `NodeAuthenticationOOB` | OOB OTP 认证 | `JSONPointer`, `UserID`, `Authenticator`, `Channel`, `WebsocketChannelName` | `InputReactor`, `DataOutputer`, `Milestone`, `EffectGetter` | 发送/验证 OOB 码 |
| `NodeOAuth` | OAuth 流程处理 | `JSONPointer`, `NewUserID`, `Alias`, `RedirectURI`, `ResponseMode` | `InputReactor`, `DataOutputer` | 跳转授权、回调处理 |
| `NodeLoginFlowChangePassword` | 密码修改 UI | `JSONPointer`, `UserID`, `Authenticator` | `InputReactor`, `DataOutputer`, `Milestone`, `EffectGetter` | 修改密码界面 |
| `NodeLoginFlowTerminateOtherSessions` | 终止其他会话 UI | `JSONPointer` | `InputReactor`, `DataOutputer` | 会话管理 |
| `NodeLookupIdentityOAuth` | OAuth 身份查找 | `JSONPointer`, `Alias` | `InputReactor`, `DataOutputer` | 查询 OAuth 身份 |
| `NodeUseAccountRecoveryCode` | 恢复码输入 | `JSONPointer`, `UserID` | `InputReactor`, `DataOutputer` | 恢复码验证 |
| `NodeUseAccountRecoveryDestination` | 恢复目标选择 | `Destinations` | `InputReactor`, `DataOutputer` | 选择恢复方式 |
| `NodeUseAccountLinkingIdentification` | 账户关联识别 | `IdentitySpec`, `ConflictingIdentity`, `ConflictingUserID` | `InputReactor`, `DataOutputer`, `Milestone` | 处理身份冲突 |

### 5.3 State/Milestone Nodes（标记流程状态）

| Node | 职能 | 存储数据 | 实现接口 | 特殊功能 |
|------|------|----------|----------|----------|
| `NodeSentinel` | 流程终止标记 | - | `NodeSimple` | 空节点表示结束 |
| `NodeDidSelectAuthenticator` | 标记已选择认证器 | `Authenticator` | `Milestone`, `MilestoneDidSelectAuthenticator` | 认证器选择状态 |
| `NodeDidReauthenticate` | 标记已重新认证 | - | `Milestone` | 重新认证状态 |
| `NodeDidConfirmTerminateOtherSessions` | 标记已确认终止会话 | - | `Milestone` | 会话终止确认 |
| `NodePostIdentified` | 识别后处理 | `Identification`, `UpdateIdentityID`, `UpdateIdentitySpec`, `UserID`, `Identity`, `IdentityIDToken`, `AuthenticatorIDToken` | `InputReactor`, `Milestone`, `EffectGetter` | 识别后步骤路由 |
| `NodePreAuthenticate` | 认证前准备 | - | `Milestone`, `InputReactor` | 认证前钩子 |
| `NodePreInitialize` | 初始化前准备 | `IsPreInitializeInvoked`, `Constraints`, `BotProtectionRequirements`, `RateLimits` | `InputReactor`, `Milestone`, `EffectGetter`, `MilestoneConstraintsProvider` | 支持限流、约束 |
| `NodeCheckLoginHint` | 验证登录提示 | `LoginHint`, `UserID` | `InputReactor` | OAuth login_hint 验证 |
| `NodeSkipCreationByExistingAuthenticator` | 跳过重复创建 | - | `NodeSimple` | MFA 去重 |
| `NodeOptOutPasskeyUpsell` | 标记 Passkey 拒绝 | - | `NodeSimple` | 记录用户选择 |
| `NodePromoteIdentityOAuth` | OAuth 身份升级 | `IdentitySpec` | `InputReactor` | 匿名用户升级 |

## 6. 设计理念与评估

### 6.1 设计理念

1. **能力接口设计（Capability-Based）**
   - 通过接口组合声明能力，而非类型继承
   - 节点按需实现接口，避免臃肿基类

2. **Milestone 模式**
   - 语义标记接口，解耦父意图与子节点
   - 支持双向通信（查询能力 + 更新状态）
   - 多态查询，避免类型 switch

3. **副作用分离**
   - `EffectGetter` 收集副作用，统一执行
   - `DelayedOneTimeFunction` 处理事务外操作
   - 支持事务回滚时的补偿

4. **注册表模式**
   - 全局 `nodeRegistry` / `intentRegistry`
   - 类型鉴别器 JSON 序列化
   - 支持动态节点创建

5. **不可变状态 + 更新方法**
   - 节点状态序列化到 Redis
   - Milestone 提供更新接口
   - StateToken 每次变更重新生成

### 6.2 解决的问题

| 问题 | 解决方案 |
|------|----------|
| 流程状态持久化 | Redis JSON 序列化 + 注册表反序列化 |
| 多步骤交互流程 | SubFlow 嵌套 + StateToken 状态管理 |
| 副作用控制 | Effect 分类（Run/OnCommit/OnRollback）|
| 节点间通信 | Milestone 能力查询 + 双向更新 |
| 可扩展性 | 注册表模式，新增节点无需修改核心 |
| 事务边界 | DelayedOneTimeFunction 处理事务外操作 |

### 6.3 设计优点

1. **松耦合**：Milestone 接口解耦节点实现
2. **可测试**：接口易 mock，副作用可隔离
3. **可扩展**：新增节点类型只需注册，无需修改现有代码
4. **类型安全**：Go 接口编译时检查
5. **状态可控**：每次 Accept 生成新 StateToken，支持幂等
6. **副作用清晰**：Effect 显式声明，便于追踪和回滚

### 6.4 设计缺点

1. **复杂度高**：大量接口和类型，学习曲线陡峭
2. **隐式依赖**：Milestone 查找逻辑分散，不易追踪
3. **调试困难**：流程执行是递归+循环，状态难以可视化
4. **性能开销**：每次操作序列化/反序列化整个 Flow
5. **代码冗长**：每个节点需要实现多个接口方法
6. **类型注册风险**：init() 顺序依赖，重复注册 panic

### 6.5 改进建议

1. **可视化工具**：提供 Flow 执行路径追踪
2. **代码生成**：减少样板代码（Kind 方法、注册逻辑）
3. **增量序列化**：只序列化变更节点
4. **Milestone 文档**：集中文档化所有 Milestone 契约
5. **类型安全增强**：使用泛型简化 Milestone 查找
