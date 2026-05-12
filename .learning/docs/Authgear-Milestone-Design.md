# Authgear Milestone 设计原理

## 概述

**Milestone（里程碑）** 模式是 Authgear 认证流程运行时（Authentication Flow Runtime）中的核心设计抽象。它作为**语义标记接口（semantic marker interface）**，实现了以下功能：

1. **能力声明（Capability declaration）** - 节点（Node）和意图（Intent）声明它们执行的操作
2. **跨层通信（Cross-layer communication）** - 父意图查询子节点已完成的任务
3. **流程状态内省（Flow state introspection）** - 无需检查实现细节即可确定已完成的工作
4. **双向数据流（Bidirectional data flow）** - 父节点可以更新子里程碑的信息

## 核心接口

```go
// Milestone 是一个标记接口。
// 设计用途是查找特定里程碑是否存在于当前流程或其子流程中。
type Milestone interface {
    Milestone()
}
```

空的 `Milestone()` 方法作为编译时标记。具体的里程碑通过特定能力方法扩展此接口。

## 为什么需要 Milestone（Node 本身不够用吗）

### 1. 接口隔离原则（Interface Segregation）

**Node** 代表流程的结构构建块。每个节点都有类型并参与流程的执行树。

**Milestone** 代表该节点的**语义能力**。多个节点可以实现同一个里程碑：

```go
// 多个节点都可以完成认证
var _ MilestoneDidAuthenticate = &NodeDoUseAuthenticatorPassword{}
var _ MilestoneDidAuthenticate = &NodeDoConsumeRecoveryCode{}
var _ MilestoneDidAuthenticate = &NodeDoUseIdentityPasskey{}

// 父意图通过能力查询，而非具体类型
milestone, flows, ok := FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
```

### 2. 多态查询（Polymorphic Querying）

如果没有 Milestone，父意图需要知道所有具体的节点类型：

```go
// 没有 Milestone - 脆弱且紧耦合
for _, node := range flows.Nearest.Nodes {
    switch n := node.Simple.(type) {
    case *NodeDoUseAuthenticatorPassword:
        // 处理密码认证
    case *NodeDoConsumeRecoveryCode:
        // 处理恢复码认证
    case *NodeDoUseIdentityPasskey:
        // 处理 Passkey 认证
    // ... 必须列出每个可能的认证器
    }
}

// 有 Milestone - 可扩展且松耦合
if m, _, ok := FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows); ok {
    amr := m.MilestoneDidAuthenticate()
    // 适用于任何认证器，包括未来的新类型
}
```

### 3. 流程间通信（Flow-to-Flow Communication）

子流程通过 Milestone 向父流程暴露能力：

```go
// 父意图检查子流程是否创建了身份
m1, m1Flows, ok := FindMilestoneInCurrentFlow[MilestoneFlowCreateIdentity](flows)
if ok {
    created, _, _ := m1.MilestoneFlowCreateIdentity(m1Flows)
    identityInfo := created.MilestoneDoCreateIdentity()
}
```

### 4. 双向更新（Bidirectional Updates）

Milestone 使父节点能够更新子节点的信息：

```go
type MilestoneDoCreateUser interface {
    MilestoneDoCreateUser() (userID string, createUser bool)
    MilestoneDoCreateUserUseExisting(userID string)  // 更新方法
}

// 父节点发现子节点创建了用户，然后告诉它改用已有用户
milestone.MilestoneDoCreateUserUseExisting(existingUserID)
```

## Milestone 分类

### 能力发现型 Milestone

用于查找已执行特定操作的节点：

```go
// 认证里程碑
type MilestoneDidAuthenticate interface {
    MilestoneDidAuthenticate() (amr []string)
    MilestoneDidAuthenticateAuthenticator() (*authenticator.Info, bool)
    MilestoneDidAuthenticateAuthentication() (*model.Authentication, bool)
}

// 身份管理
type MilestoneDoCreateIdentity interface {
    MilestoneDoCreateIdentity() *identity.Info
    MilestoneDoCreateIdentityIdentification() model.Identification
}

// 用户识别
type MilestoneDoUseUser interface {
    MilestoneDoUseUser() string
}
```

### 流程委托型 Milestone

父意图通过这类 Milestone 委托给子流程：

```go
// 父节点请求子节点查找认证
type MilestoneFlowAuthenticate interface {
    MilestoneFlowAuthenticate(flows authflow.Flows) (MilestoneDidAuthenticate, authflow.Flows, bool)
}

// 父节点请求子节点创建身份
type MilestoneFlowCreateIdentity interface {
    MilestoneFlowCreateIdentity(flows authflow.Flows) (created MilestoneDoCreateIdentity, newFlows authflow.Flows, ok bool)
}
```

### 状态更新型 Milestone

使父节点能够修改子节点状态：

```go
type MilestoneSwitchToExistingUser interface {
    MilestoneSwitchToExistingUser(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, newUserID string) error
}

type MilestoneVerifyClaim interface {
    MilestoneVerifyClaim()
    MilestoneVerifyClaimUpdateUserID(deps *authflow.Dependencies, flows authflow.Flows, newUserID string) error
}
```

## 查找 Milestone

### 仅在当前流程中查找

```go
// 仅在当前流程中查找最后一个里程碑，不递归到子流程
func FindMilestoneInCurrentFlow[T Milestone](flows Flows) (T, authflow.Flows, bool)
```

用于父节点检查其直接子节点：

```go
// 意图检查用户是否在当前步骤中被识别
_, _, userIdentified := FindMilestoneInCurrentFlow[MilestoneDoUseUser](flows)
```

### 在所有流程中查找

```go
// 在整个流程树中查找所有里程碑
func FindAllMilestones[T Milestone](w *Flow) []T
```

用于跨整个流程收集信息：

```go
// 收集所有已使用的认证器
milestones := FindAllMilestones[MilestoneDidAuthenticate](flows.Root)
for _, m := range milestones {
    amr = append(amr, m.MilestoneDidAuthenticate()...)
}
```

## 实现模式

### 节点实现

```go
type NodeDoCreateUser struct {
    UserID string `json:"user_id,omitempty"`
}

var _ authflow.Milestone = &NodeDoCreateUser{}
var _ MilestoneDoUseUser = &NodeDoCreateUser{}
var _ MilestoneDoCreateUser = &NodeDoCreateUser{}

// 标记方法
func (*NodeDoCreateUser) Milestone() {}

// 能力方法
func (n *NodeDoCreateUser) MilestoneDoUseUser() string { return n.UserID }
func (n *NodeDoCreateUser) MilestoneDoCreateUser() (string, bool) { return n.UserID, true }
func (n *NodeDoCreateUser) MilestoneDoCreateUserUseExisting(userID string) {
    n.UserID = userID
}
```

### 意图实现

```go
type IntentSignupFlow struct {
    // ...
}

var _ authflow.Milestone = &IntentSignupFlow{}
var _ MilestoneSwitchToExistingUser = &IntentSignupFlow{}

func (*IntentSignupFlow) Milestone() {}

func (i *IntentSignupFlow) MilestoneSwitchToExistingUser(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, newUserID string) error {
    milestone, _, ok := FindMilestoneInCurrentFlow[MilestoneDoCreateUser](flows)
    if ok {
        milestone.MilestoneDoCreateUserUseExisting(newUserID)
    }
    return nil
}
```

## 设计原则

### 1. 接口组合（Interface Composition）

Milestone 将小而专注的接口组合在一起：

```go
// 基本能力
type MilestoneDoUseUser interface {
    authflow.Milestone
    MilestoneDoUseUser() string
}

// 创建用户的扩展能力
type MilestoneDoCreateUser interface {
    authflow.Milestone
    MilestoneDoCreateUser() (userID string, createUser bool)
    MilestoneDoCreateUserUseExisting(userID string)
}
```

### 2. 能力优于类型（Capability Over Type）

通过能力而非具体类型进行查询：

```go
// 好：通过能力查询
if m, _, ok := FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows); ok {
    amr := m.MilestoneDidAuthenticate()
}

// 坏：类型断言链
for _, node := range flows.Nearest.Nodes {
    if pwd, ok := node.Simple.(*NodeDoUseAuthenticatorPassword); ok {
        // 只处理密码
    }
}
```

### 3. 不可变状态配合更新方法

Milestone 暴露不可变状态，但提供更新方法进行协调变更：

```go
type MilestoneDoCreateIdentity interface {
    MilestoneDoCreateIdentity() *identity.Info                    // 读取
    MilestoneDoCreateIdentitySkipCreate()                          // 状态更新
    MilestoneDoCreateIdentityUpdate(newInfo *identity.Info)        // 状态更新
}
```

## 使用示例

### 示例 1：检查流程完成

```go
// 登录流程检查会话是否已创建
func (*IntentLoginFlow) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    _, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateSession](flows)
    if ok {
        return nil, authflow.ErrEOF  // 流程完成
    }
    return nil, nil
}
```

### 示例 2：账户关联（Account Linking）

```go
// 账户关联意图从子里程碑查找冲突信息
milestone, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneUseAccountLinkingIdentification](flows)
if !ok {
    panic(fmt.Errorf("expected milestone MilestoneUseAccountLinkingIdentification not found"))
}
conflict := milestone.MilestoneUseAccountLinkingIdentification()
```

### 示例 3：AMR 收集

```go
// 收集流程中使用的所有认证方法
func collectAMR(flows authflow.Flows) []string {
    var amr []string
    err := authflow.TraverseFlow(authflow.Traverser{
        NodeSimple: func(nodeSimple authflow.NodeSimple, w *authflow.Flow) error {
            if n, ok := nodeSimple.(MilestoneDidAuthenticate); ok {
                amr = append(amr, n.MilestoneDidAuthenticate()...)
            }
            return nil
        },
    }, flows.Root)
    // ...
    return amr
}
```

## 总结

Milestone 模式对 Authgear 的认证流程架构至关重要，因为它：

1. **解耦（Decouples）** 父意图与子节点的实现
2. **实现多态（Enables polymorphism）** - 不同节点类型执行相似操作
3. **支持可扩展性（Supports extensibility）** - 新增认证器/身份源无需修改父节点
4. **促进双向通信（Facilitates bidirectional communication）** - 流程层之间可以交互
5. **提供语义清晰度（Provides semantic clarity）** - 明确已执行的操作

没有 Milestone，流程系统将需要大量的类型 switch 语句，并且对变更非常脆弱。Milestone 将流程从刚性的树结构转变为灵活的基于能力的系统。

## Milestone 存储机制

### 核心原则：Milestone 不存储，Node/Intent 存储

**重要澄清**：Milestone 本身是一个纯粹的标记接口（marker interface），它不会将自己的信息单独保存到 Node 或 Redis 中。实际存储的是**实现 Milestone 接口的 Node 或 Intent 结构体的数据字段**。

```go
// Milestone 只是一个标记接口，没有数据需要存储
type Milestone interface {
    Milestone()
}

// 实际存储的是实现 Milestone 的 Node 的数据字段
type NodeDoCreateUser struct {
    UserID       string `json:"user_id"`        // 这个字段会被存储
    SkipCreation bool   `json:"skip_creation,omitempty"`  // 这个字段会被存储
}
```

### 存储架构

#### 1. 内存中的 Flow 结构

```go
// Flow 是运行时内存中的流程表示
type Flow struct {
    FlowID     string  // 流程唯一标识
    StateToken string  // 状态令牌，用于查找
    Intent     Intent  // 根意图
    Nodes      []Node  // 节点树
}

// Node 包含两种类型
type Node struct {
    Type    NodeType   // SIMPLE 或 SUB_FLOW
    Simple  NodeSimple // 叶子节点（实现 Milestone）
    SubFlow *Flow      // 嵌套子流程
}
```

#### 2. Redis 存储结构

Flow 通过 JSON 序列化存储到 Redis，使用两个 Key：

```go
// 1. Flow Key - 标记流程存在（Value 只是占位符）
// Key: app:{appID}:authenticationflow_flow:{flowID}
// TTL: duration.UserInteraction (通常 5 分钟)

// 2. State Key - 存储实际的流程状态数据
// Key: app:{appID}:authenticationflow_state:{stateToken}
// Value: JSON 序列化的 Flow 对象
// TTL: 同上
```

存储代码（`pkg/lib/authenticationflow/store.go`）：

```go
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    bytes, err := json.Marshal(flow)  // 整个 Flow 序列化为 JSON
    if err != nil {
        return err
    }

    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        stateKey := redisFlowStateKey(s.AppID, flow.StateToken)
        ttl := Lifetime

        // Flow Key 仅用于存在性检查
        _, err := conn.SetEx(ctx, flowKey, []byte(flowKey), ttl).Result()
        
        // State Key 存储实际的流程数据
        _, err = conn.SetEx(ctx, stateKey, bytes, ttl).Result()
        return nil
    })
}
```

### 序列化机制

#### Node 序列化

每个 Node 通过 `Kind()` 方法识别类型，数据通过 JSON 标签序列化：

```go
// pkg/lib/authenticationflow/marshal.go

func (n *Node) MarshalJSON() ([]byte, error) {
    nodeJSON := nodeJSON{
        Type: n.Type,
    }

    switch n.Type {
    case NodeTypeSimple:
        // 序列化 NodeSimple 的数据
        nodeSimpleBytes, err := json.Marshal(n.Simple)
        
        nodeSimpleJSON := nodeSimpleJSON{
            Kind: n.Simple.Kind(),  // 类型标识，如 "NodeDoCreateUser"
            Data: nodeSimpleBytes,   // 实际的 JSON 数据
        }
        nodeJSON.Simple = &nodeSimpleJSON
    }
    
    return json.Marshal(nodeJSON)
}
```

#### 注册机制

Node 和 Intent 需要在 `init()` 中注册才能被正确反序列化：

```go
// pkg/lib/authenticationflow/declarative/node_do_create_user.go

func init() {
    authflow.RegisterNode(&NodeDoCreateUser{})  // 注册到全局注册表
}

type NodeDoCreateUser struct {
    UserID       string `json:"user_id"`
    SkipCreation bool   `json:"skip_creation,omitempty"`
}

func (n *NodeDoCreateUser) Kind() string {
    return "NodeDoCreateUser"  // 用于序列化时标识类型
}
```

### 完整案例：NodeDoCreateUser

#### 代码实现

```go
// pkg/lib/authenticationflow/declarative/node_do_create_user.go

package declarative

import (
    "context"
    authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
)

func init() {
    authflow.RegisterNode(&NodeDoCreateUser{})
}

// NodeDoCreateUser 实现多个 Milestone 接口
type NodeDoCreateUser struct {
    UserID       string `json:"user_id"`                 // 存储：用户ID
    SkipCreation bool   `json:"skip_creation,omitempty"` // 存储：是否跳过创建
}

// 接口实现声明
var _ authflow.NodeSimple = &NodeDoCreateUser{}
var _ authflow.Milestone = &NodeDoCreateUser{}
var _ MilestoneDoUseUser = &NodeDoCreateUser{}      // Milestone：使用用户
var _ MilestoneDoCreateUser = &NodeDoCreateUser{}   // Milestone：创建用户
var _ authflow.EffectGetter = &NodeDoCreateUser{}

// Milestone 标记方法
func (*NodeDoCreateUser) Milestone() {}

// MilestoneDoUseUser 接口方法
func (n *NodeDoCreateUser) MilestoneDoUseUser() string { 
    return n.UserID 
}

// MilestoneDoCreateUser 接口方法 - 读取状态
func (n *NodeDoCreateUser) MilestoneDoCreateUser() (string, bool) {
    if n.SkipCreation {
        return n.UserID, false  // 不创建新用户，但保留 userID
    }
    return n.UserID, true  // 创建新用户
}

// MilestoneDoCreateUser 接口方法 - 更新状态（双向数据流）
func (n *NodeDoCreateUser) MilestoneDoCreateUserUseExisting(userID string) {
    n.UserID = userID         // 更新 UserID
    n.SkipCreation = true     // 标记跳过创建
}

// Kind 返回节点类型标识，用于序列化
func (n *NodeDoCreateUser) Kind() string {
    return "NodeDoCreateUser"
}
```

#### Redis 中的存储示例

当包含 `NodeDoCreateUser` 的 Flow 被存储时，Redis 中的 JSON 结构如下：

```json
{
  "flow_id": "authflow_123456",
  "state_token": "state_abc789",
  "intent": {
    "kind": "IntentSignupFlow",
    "data": {
      "json_pointer": "",
      "identification": "email"
    }
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoCreateUser",
        "data": {
          "user_id": "user_xyz123",
          "skip_creation": false
        }
      }
    },
    {
      "type": "SUB_FLOW",
      "flow": {
        "intent": {
          "kind": "IntentCreateIdentityLoginID"
        },
        "nodes": [...]
      }
    }
  ]
}
```

#### 存储键示例

```
# Flow Key（存在性标记）
Key:   app:myapp:authenticationflow_flow:authflow_123456
Value: "app:myapp:authenticationflow_flow:authflow_123456"
TTL:   300 seconds

# State Key（实际数据）
Key:   app:myapp:authenticationflow_state:state_abc789
Value: {上述 JSON 结构}
TTL:   300 seconds
```

### Milestone 运行时查询流程

```go
// 场景：父 Intent 需要检查子 Node 是否创建了用户
func (i *IntentSignupFlow) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // Step 1: 在内存中的 Node 树里查找实现 MilestoneDoCreateUser 的节点
    milestone, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateUser](flows)
    
    if ok {
        // Step 2: 调用 Milestone 方法获取数据（从 Node 的字段读取）
        userID, shouldCreate := milestone.MilestoneDoCreateUser()
        
        // Step 3: 根据状态决定下一步
        if shouldCreate {
            // 继续流程...
        }
    }
    
    return nil, nil
}
```

### 双向更新的存储交互

```go
// 场景：账户关联时发现已有用户，需要更新子节点的 UserID
func (i *IntentSignupFlow) MilestoneSwitchToExistingUser(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, newUserID string) error {
    // 1. 查找实现 MilestoneDoCreateUser 的节点（从内存中的 Node 树）
    milestone, _, ok := FindMilestoneInCurrentFlow[MilestoneDoCreateUser](flows)
    if !ok {
        return nil
    }
    
    // 2. 调用更新方法修改 Node 的字段（内存中）
    milestone.MilestoneDoCreateUserUseExisting(newUserID)
    // 这会将 NodeDoCreateUser.UserID 改为 newUserID
    // 并将 NodeDoCreateUser.SkipCreation 设为 true
    
    // 3. 后续 Flow 保存时，修改后的 Node 数据会被序列化到 Redis
    return nil
}
```

### Redis 反序列化流程

当客户端请求继续一个已存在的 Flow 时，系统需要从 Redis 中恢复内存中的 Flow 对象树。这是存储的逆过程。

#### 读取入口

```go
// pkg/lib/authenticationflow/store.go

func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    stateKey := redisFlowStateKey(s.AppID, stateToken)
    var flow Flow
    err := s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        // 1. 从 Redis 获取 JSON 字节
        bytes, err := conn.Get(ctx, stateKey).Bytes()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }

        // 2. JSON 反序列化为 Flow 对象
        err = json.Unmarshal(bytes, &flow)
        if err != nil {
            return err
        }

        // 3. 验证 Flow Key 存在（确认流程未过期）
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err = conn.Get(ctx, flowKey).Result()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }
        return nil
    })
    return &flow, err
}
```

#### 反序列化层次结构

反序列化是递归的，从外到内逐层构建对象树：

```
JSON Bytes
    ↓
Flow.UnmarshalJSON() ──→ 使用注册表实例化 Intent
    ↓
[]Node ──→ 每个 Node.UnmarshalJSON()
    ↓
NodeSimple (类型: SIMPLE) ──→ InstantiateNode(Kind) ──→ 填充 Data
    ↓
或 *Flow (类型: SUB_FLOW) ──→ 递归调用 Flow.UnmarshalJSON()
```

#### Flow 反序列化

```go
// pkg/lib/authenticationflow/marshal.go

func (w *Flow) UnmarshalJSON(d []byte) (err error) {
    flowJSON := flowJSON{}
    // 先反序列化为中间结构（[]Node 会触发 Node.UnmarshalJSON）
    err = json.Unmarshal(d, &flowJSON)
    if err != nil {
        return
    }

    // 1. 从注册表实例化 Intent（工厂模式）
    intent, err := InstantiateIntent(flowJSON.Intent.Kind)
    if err != nil {
        return
    }

    // 2. 将 Intent 的 JSON 数据填充到实例
    err = json.Unmarshal(flowJSON.Intent.Data, intent)
    if err != nil {
        return
    }

    // 3. 组装 Flow
    w.FlowID = flowJSON.FlowID
    w.StateToken = flowJSON.StateToken
    w.Intent = intent
    w.Nodes = flowJSON.Nodes  // Node 数组已在上面步骤中完成反序列化
    return nil
}
```

#### Node 反序列化

```go
func (n *Node) UnmarshalJSON(d []byte) (err error) {
    nodeJSON := nodeJSON{}
    // 反序列化（SubFlow 会递归触发 Flow.UnmarshalJSON）
    err = json.Unmarshal(d, &nodeJSON)
    if err != nil {
        return
    }

    n.Type = nodeJSON.Type

    switch nodeJSON.Type {
    case NodeTypeSimple:
        // 1. 从注册表创建空实例（只有类型，没有数据）
        nodeSimple, err := InstantiateNode(nodeJSON.Simple.Kind)
        if err != nil {
            return
        }

        // 2. 将 JSON Data 填充到实例的字段
        err = json.Unmarshal(nodeJSON.Simple.Data, nodeSimple)
        if err != nil {
            return
        }
        n.Simple = nodeSimple
        
    case NodeTypeSubFlow:
        // SubFlow 已在 json.Unmarshal 时递归完成
        n.SubFlow = nodeJSON.SubFlow
    }
    return nil
}
```

#### 注册表实例化

```go
// pkg/lib/authenticationflow/marshal.go

// 全局注册表（由 init() 函数填充）
var nodeRegistry = map[string]nodeFactory{}

// 工厂函数类型
type nodeFactory func() NodeSimple

// 从注册表创建实例
func InstantiateNode(kind string) (NodeSimple, error) {
    factory, ok := nodeRegistry[kind]
    if !ok {
        return nil, fmt.Errorf("unknown node kind: %v", kind)
    }
    // 调用工厂函数创建新实例
    return factory(), nil
}

// 注册函数（在 Node 的 init() 中调用）
func RegisterNode(node NodeSimple) {
    nodeType := reflect.TypeOf(node).Elem()
    nodeKind := node.Kind()
    
    factory := nodeFactory(func() NodeSimple {
        // 使用反射创建新实例
        return reflect.New(nodeType).Interface().(NodeSimple)
    })
    
    if _, hasKind := nodeRegistry[nodeKind]; hasKind {
        panic(fmt.Errorf("duplicated node kind: %v", nodeKind))
    }
    nodeRegistry[nodeKind] = factory
}
```

#### 完整反序列化示例

假设 Redis 中有以下 JSON：

```json
{
  "flow_id": "authflow_123456",
  "state_token": "state_abc789",
  "intent": {
    "kind": "IntentSignupFlow",
    "data": {"json_pointer": "", "identification": "email"}
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoCreateUser",
        "data": {"user_id": "user_xyz123", "skip_creation": false}
      }
    }
  ]
}
```

反序列化过程：

```go
// 1. 调用 json.Unmarshal(bytes, &flow)
//    - 触发 Flow.UnmarshalJSON

// 2. Flow.UnmarshalJSON 内部
//    - json.Unmarshal(d, &flowJSON) 触发 Node 数组反序列化
//    - 对每个 Node 触发 Node.UnmarshalJSON

// 3. Node.UnmarshalJSON（第一个 Node）
//    - 读取 Type: "SIMPLE"
//    - 读取 Kind: "NodeDoCreateUser"
//    - 调用 InstantiateNode("NodeDoCreateUser")
//      - 在 nodeRegistry 中查找工厂
//      - 返回 &NodeDoCreateUser{}（空实例）
//    - 调用 json.Unmarshal(nodeJSON.Simple.Data, nodeSimple)
//      - 填充 UserID: "user_xyz123"
//      - 填充 SkipCreation: false

// 4. Flow.UnmarshalJSON 继续
//    - InstantiateIntent("IntentSignupFlow")
//    - 填充 Intent 数据

// 5. 结果：完整的内存对象树
//    flow := &Flow{
//        FlowID: "authflow_123456",
//        Intent: &IntentSignupFlow{...},
//        Nodes: []Node{
//            {Type: "SIMPLE", Simple: &NodeDoCreateUser{
//                UserID: "user_xyz123",
//                SkipCreation: false,
//            }},
//        },
//    }
```

#### 反序列化后的 Milestone 可用性

一旦 Flow 从 Redis 恢复为内存对象，Milestone 系统立即可以工作：

```go
// Flow 从 Redis 加载后
flow, _ := store.GetFlowByStateToken(ctx, "state_abc789")

// 可以直接使用 Milestone 查询
milestone, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateUser](
    authflow.Flows{Nearest: flow}
)
if ok {
    userID, _ := milestone.MilestoneDoCreateUser()
    // userID == "user_xyz123"（从 Redis 恢复的数据）
}
```

#### 关键设计要点

1. **延迟实例化（Late Instantiation）**
   - 先读取 Kind，从注册表创建正确类型的空实例
   - 再将 JSON Data 填充到实例字段
   - 避免手动维护复杂的反序列化逻辑

2. **递归结构支持**
   - SubFlow（子流程）通过递归调用自动反序列化
   - Node 树可以是任意深度的嵌套结构

3. **类型安全**
   - 注册表确保只有已注册的 Node/Intent 类型能被反序列化
   - 未知的 Kind 会返回错误，防止数据损坏

4. **性能考虑**
   - 使用 `json.RawMessage` 延迟解析 Data 字段
   - 只在确定类型后才解析具体结构

### 关键结论

1. **Milestone 是查询机制，不是存储机制**
   - Milestone 接口只定义能力方法，没有存储语义
   - 数据存储在实现 Milestone 的 Node/Intent 结构体中

2. **存储是透明的 JSON 序列化**
   - Node 的数据字段通过 `json` 标签自动序列化
   - 类型通过 `Kind()` 方法在注册表中识别

3. **运行时查询基于内存中的对象图**
   - `FindMilestoneInCurrentFlow` 遍历内存中的 `flows.Nearest.Nodes`
   - 使用 Go 的类型断言检查 Node 是否实现特定 Milestone

4. **双向更新修改的是内存状态**
   - Milestone 的更新方法直接修改 Node 的字段
   - 修改后的状态在下次 Flow 保存时持久化到 Redis
