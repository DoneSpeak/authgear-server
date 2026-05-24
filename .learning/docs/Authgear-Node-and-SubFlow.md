# Authgear 认证流程：Node 与 SubFlow 深度解析

本文档详细介绍 Authgear 认证流程框架中 `Node` 和 `SubFlow` 的核心概念、区别以及协作机制。

---

## 目录

1. [核心概念](#核心概念)
2. [Node 的两种类型](#node-的两种类型)
3. [SubFlow 的本质](#subflow-的本质)
4. [多步骤校验的组织方式](#多步骤校验的组织方式)
5. [Milestone 机制](#milestone-机制)
6. [状态流转：CanReactTo + ReactTo](#状态流转-canreactto--reactto)
7. [实际案例：OTP 验证流程](#实际案例otp-验证流程)
8. [总结：设计模式核心要点](#总结设计模式核心要点)

---

## 核心概念

### Flow 结构定义

```go
// pkg/lib/authenticationflow/workflows.go
type Flows struct {
    Root    *Flow    // 整个流程树的根
    Nearest *Flow    // 当前正在处理的子流程（可能是 Root 或 SubFlow）
}

type Flow struct {
    FlowID    string    // 流程唯一标识
    StateToken string   // 状态令牌（用于序列化/恢复）
    Intent    Intent    // 当前流程的意图（逻辑控制器）
    Nodes     []*Node   // 已执行的节点列表（按顺序追加）
}
```

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         Flow (根流程)                            │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Intent (如 IntentUseAuthenticatorOOBOTP)               │    │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────────────────────┐   │    │
│  │  │ Node 1  │→│ Node 2  │→│  SubFlow Node            │   │    │
│  │  │(选择器) │  │(标记器) │  │  ┌─────────────────┐    │   │    │
│  │  └─────────┘  └─────────┘  │  │ Intent (子流程)  │    │   │    │
│  │                           │  │ ┌─────┐┌─────┐    │   │    │
│  │                           │  │ │Node ││Node │    │   │    │
│  │                           │  │ └─────┘└─────┘    │   │    │
│  │                           │  └───────────────────┘   │   │    │
│  │                           └──────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Node 的两种类型

### 类型定义

```go
// pkg/lib/authenticationflow/node.go
type NodeType string

const (
    NodeTypeSimple  NodeType = "SIMPLE"   // 普通节点
    NodeTypeSubFlow NodeType = "SUB_FLOW" // 子流程节点
)

type Node struct {
    Type    NodeType   `json:"type"`
    Simple  NodeSimple `json:"simple,omitempty"`   // 普通节点实现
    SubFlow *Flow      `json:"flow,omitempty"`     // 子流程（包含独立的 Intent + Nodes）
}
```

### NewNodeSimple：创建普通节点

```go
// pkg/lib/authenticationflow/node.go
func NewNodeSimple(simple NodeSimple) *Node {
    return &Node{
        Type:   NodeTypeSimple,
        Simple: simple,
    }
}
```

**特点**：

- 单步操作，一次完成
- 无内部状态机
- 直接产生里程碑或执行副作用

**示例节点**：

- `NodeDidSelectAuthenticator`：标记已选择认证器
- `NodeDoMarkClaimVerified`：标记声明已验证
- `NodeDoUseAuthenticatorSimple`：执行认证操作

### NewSubFlow：创建子流程节点

```go
// pkg/lib/authenticationflow/node.go
func NewSubFlow(intent Intent) *Node {
    return &Node{
        Type: NodeTypeSubFlow,
        SubFlow: &Flow{
            // FlowID 和 StateToken 在此不关键
            Intent: intent,  // 子流程有自己的 Intent，独立管理内部状态
        },
    }
}
```

**特点**：

- 多步子流程，包含多个阶段
- 拥有独立的 `CanReactTo/ReactTo` 循环
- 内部可产生多个里程碑

**示例子流程 Intent**：

- `IntentAuthenticationOOB`：处理 OTP 发送和验证的完整流程
- `IntentSignupFlowSteps`：处理注册步骤的子流程

---

## SubFlow 的本质

### 为什么需要 SubFlow？

考虑 **OOB OTP 验证**的需求：

1. 可能需要用户选择通道（SMS / Email / Whatsapp）
2. 发送 OTP 到指定通道
3. 等待用户输入验证码
4. 验证 OTP 码
5. 更新最后使用通道

这是一个**有内部状态转换的复杂过程**，不能用单个 Node 完成。

### SubFlow 与父流程的关系

```
父流程 Intent
    │
    ▼ ReactTo 返回 NewSubFlow(IntentAuthenticationOOB)
    │
    ├─→ Node{type: SUB_FLOW, SubFlow: &Flow{Intent: IntentAuthenticationOOB, Nodes: []}}
    │                                    │
    │                                    ▼ 独立的 CanReactTo/ReactTo 循环
    │                                    ├─ 阶段1: 选择通道
    │                                    ├─ 阶段2: 发送 OTP (NodeAuthenticationOOB)
    │                                    ├─ 阶段3: 验证 OTP
    │                                    └─ 阶段4: 更新最后使用通道
    │
    ▼ 子流程完成后，父流程继续
```

### 关键设计

SubFlow 是一个**完整的 Flow**，它有自己的：

- `Intent`：控制子流程的逻辑
- `Nodes` 列表：记录子流程内部的执行历史
- `CanReactTo/ReactTo` 循环：独立处理输入和状态流转

父流程只需要：

1. 启动 SubFlow（通过 `NewSubFlow`）
2. 等待 SubFlow 产生特定的 Milestone
3. 无需关心 SubFlow 内部有多少步骤

---

## 多步骤校验的组织方式

### Node 链表结构

流程状态通过**不可变的 Node 链表**记录：

```go
// 初始状态
Nodes: []

// 用户选择认证器后
Nodes: [NodeDidSelectAuthenticator{}]

// 启动 OTP 子流程后
Nodes: [
    NodeDidSelectAuthenticator{},
    Node{type: SUB_FLOW, SubFlow: &Flow{
        Intent: IntentAuthenticationOOB{},
        Nodes: [],  // 子流程内部节点
    }},
]

// 子流程内部发送 OTP 后
Nodes: [
    NodeDidSelectAuthenticator{},
    Node{type: SUB_FLOW, SubFlow: &Flow{
        Intent: IntentAuthenticationOOB{},
        Nodes: [NodeAuthenticationOOB{}],  // 子流程内部节点
    }},
]

// 用户验证 OTP 后，子流程产生验证完成节点
Nodes: [
    NodeDidSelectAuthenticator{},
    Node{type: SUB_FLOW, SubFlow: &Flow{
        Intent: IntentAuthenticationOOB{},
        Nodes: [
            NodeAuthenticationOOB{},
            NodeDoMarkClaimVerified{},  // 标记声明已验证
            NodeDoUpdateLastUsedChannel{},  // 更新最后使用通道
        ],
    }},
]
```

### 嵌套结构的优势

1. **封装性**：父流程不需要知道子流程内部细节
2. **可复用性**：`IntentAuthenticationOOB` 可以被多个父流程复用
3. **状态隔离**：子流程的状态变化不影响父流程的其他部分
4. **序列化友好**：整个树结构可以 JSON 序列化保存到数据库

---

## Milestone 机制

### Milestone 接口

```go
// pkg/lib/authenticationflow/milestone.go
type Milestone interface {
    Milestone()  // 空方法，用于标记类型
}
```

### 具体 Milestone 定义

```go
// pkg/lib/authenticationflow/declarative/milestone.go

// 声明已验证里程碑
type MilestoneDoMarkClaimVerified interface {
    authflow.Milestone
    MilestoneDoMarkClaimVerified()
    MilestoneDoMarkClaimVerifiedUpdateUserID(newUserID string)
}

// OTP 验证完成里程碑
type MilestoneOOBOTPVerified interface {
    authflow.Milestone
    MilestoneOOBOTPVerifiedChannel() model.AuthenticatorOOBChannel
}

// 最后使用通道已更新里程碑
type MilestoneOOBOTPLastUsedChannelUpdated interface {
    authflow.Milestone
    MilestoneOOBOTPLastUsedChannelUpdated()
}
```

### 查找 Milestone 的核心函数

```go
// pkg/lib/authenticationflow/milestone.go
func FindMilestoneInCurrentFlow[T Milestone](flows Flows) (T, Flows, bool) {
    newFlows := flows
    w := flows.Nearest
    var t T
    found := false
    
    // 从后往前遍历当前流程的所有节点
    for _, node := range w.Nodes {
        n := node
        switch n.Type {
        case NodeTypeSimple:
            // 检查 Node 是否实现了该 Milestone
            if m, ok := n.Simple.(T); ok {
                t = m
                newFlows = flows.Replace(w)
                found = true
            }
        case NodeTypeSubFlow:
            // 检查 SubFlow 的 Intent 是否实现了该 Milestone
            if m, ok := n.SubFlow.Intent.(T); ok {
                t = m
                newFlows = flows.Replace(n.SubFlow)
                found = true
            }
        }
    }
    return t, newFlows, found
}
```

### Milestone 的工作方式

1. **节点产生 Milestone**：节点实现 Milestone 接口
  ```go
   // NodeDoMarkClaimVerified 实现了 MilestoneDoMarkClaimVerified
   func (*NodeDoMarkClaimVerified) MilestoneDoMarkClaimVerified() {}
  ```
2. **Intent 查询 Milestone**：检查某个步骤是否完成
  ```go
   _, _, claimVerified := authflow.FindMilestoneInCurrentFlow[MilestoneDoMarkClaimVerified](flows)
  ```
3. **从后往前查找**：总是找最后一个匹配的 Milestone，表示最新状态

---

## 状态流转：CanReactTo + ReactTo

### InputReactor 接口

```go
// pkg/lib/authenticationflow/input.go
type InputReactor interface {
    // 检查当前状态，决定需要什么输入
    CanReactTo(ctx context.Context, deps *Dependencies, flows Flows) (InputSchema, error)
    
    // 处理输入，返回下一步操作
    ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (ReactToResult, error)
}
```

### 阶段检测模式

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go
func (n *IntentUseAuthenticatorOOBOTP) CanReactTo(...) (authflow.InputSchema, error) {
    // 检查三个里程碑状态
    _, _, authenticatorSelected := authflow.FindMilestoneInCurrentFlow[MilestoneDidSelectAuthenticator](flows)
    _, _, claimVerified := authflow.FindMilestoneInCurrentFlow[MilestoneDoMarkClaimVerified](flows)
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)

    switch {
    case !authenticatorSelected:
        // 阶段1：认证器未选择，返回选择界面
        return &InputSchemaUseAuthenticatorOOBOTP{...}, nil
        
    case !claimVerified:
        // 阶段2：声明未验证，启动子流程（不需要用户输入）
        return nil, nil
        
    case !authenticated:
        // 阶段3：未认证，完成认证（不需要用户输入）
        return nil, nil
        
    default:
        // 所有阶段完成，返回 ErrEOF 表示流程结束
        return nil, authflow.ErrEOF
    }
}
```

### 输入处理模式

```go
func (n *IntentUseAuthenticatorOOBOTP) ReactTo(...) (authflow.ReactToResult, error) {
    switch {
    case !authenticatorSelected:
        // 处理认证器选择，返回选择节点
        return authflow.NewNodeSimple(&NodeDidSelectAuthenticator{...}), nil
        
    case !claimVerified:
        // 启动 OTP 验证子流程
        return authflow.NewSubFlow(&IntentAuthenticationOOB{
            JSONPointer: n.JSONPointer,
            UserID:      n.UserID,
            Purpose:     otp.PurposeOOBOTP,
            Info:        info,
            Form:        otpForm,
        }), nil
        
    case !authenticated:
        // 使用认证器完成认证
        return authflow.NewNodeSimple(&NodeDoUseAuthenticatorSimple{...}), nil
    }
    
    return nil, authflow.ErrIncompatibleInput
}
```

### 协作流程

```
┌─────────────┐     CanReactTo      ┌─────────────┐
│   Intent    │────────────────────→│  检查里程碑  │
│             │                     │  判断阶段    │
└─────────────┘                     └─────────────┘
        │                                  │
        │                                  ▼
        │                           ┌─────────────┐
        │                           │ 返回InputSchema│
        │                           │ 或 nil      │
        │                           │ 或 ErrEOF   │
        │                           └─────────────┘
        │                                  │
        │◄─────────────────────────────────┘
        │
        │ 如果有输入
        ▼ ReactTo
┌─────────────┐
│  处理输入    │
│  返回新Node │
│  (Simple或   │
│   SubFlow)  │
└─────────────┘
```

---

## SubFlow 的执行机制与多次调用原理

### doAccept 循环：SubFlow 被多次调用的核心

SubFlow 的 Intent（如 `IntentAuthenticationOOB`）之所以会被多次调用，是因为 `doAccept` 函数的**无限循环机制**。

#### 核心执行循环

```go
// pkg/lib/authenticationflow/accept.go:107-268
func doAccept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(inputSchema InputSchema) (Input, error)) (err error) {
    var changed bool
    loopCount := 0
    var nextNodeType string
    
    for {  // 核心：无限循环，直到遇到 return
        loopCount += 1
        if loopCount > MAX_LOOP {
            panic(fmt.Errorf("number of loops reached limit. next node is %s", nextNodeType))
        }
        
        // 1. 找到可以接收输入的组件（Intent 或 Node）
        var findInputReactorResult *FindInputReactorResult
        findInputReactorResult, err = FindInputReactor(ctx, deps, flows)
        if err != nil {
            return
        }
        
        // 2. 调用该组件的 ReactTo，获得下一个 Node
        var reactToResult ReactToResult
        reactToResult, err = findInputReactorResult.InputReactor.ReactTo(...)
        
        // 3. 将返回的 Node 追加到流程中
        var nextNode Node
        nextNode = *reactToResult
        err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
        changed = true
        // 继续循环（不会 return，除非出错或特殊状态）
    }
}
```

### FindInputReactor：递归进入 SubFlow

```go
// pkg/lib/authenticationflow/input.go:86-138
func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) (*FindInputReactorResult, error) {
    if len(flows.Nearest.Nodes) > 0 {
        // 优先检查最后一个 Node 是否能接收输入
        lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
        findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
        if err == nil {
            return findInputReactorResult, nil
        }
        // 如果不是 ErrEOF，直接返回错误
        if !errors.Is(err, ErrEOF) {
            return nil, err
        }
        // 是 ErrEOF，说明最后一个 Node 已完成，继续检查 Intent
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

func FindInputReactorForNode(ctx context.Context, deps *Dependencies, flows Flows, n *Node) (*FindInputReactorResult, error) {
    switch n.Type {
    case NodeTypeSimple:
        reactor, ok := n.Simple.(InputReactor)
        if !ok {
            return nil, ErrEOF  // 普通节点不能接收输入
        }
        // ...
        
    case NodeTypeSubFlow:
        // 关键：递归进入子流程！
        return FindInputReactorForFlow(ctx, deps, flows.Replace(n.SubFlow))
    }
}
```

### appendNode：Node 追加到哪个 Flow

```go
// pkg/lib/authenticationflow/accept.go:271-303
func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
    // 关键：追加到 flows.Nearest（当前正在处理的子流程）
    flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)

    // 触发节点的副作用（如发送邮件）
    err := TraverseNode(Traverser{
        NodeSimple: func(nodeSimple NodeSimple, w *Flow) error {
            effectGetter, ok := nodeSimple.(EffectGetter)
            if !ok {
                return nil
            }
            effs, err := effectGetter.GetEffects(ctx, deps, flows.Replace(w))
            // ... 执行 RunEffect
            return nil
        },
    }, flows.Nearest, &node)
    
    return nil
}
```

**关键点**：`appendNode` 将 Node 追加到 `flows.Nearest.Nodes`，而 `flows.Nearest` 可能是：

- 根流程（如果没有活跃的 SubFlow）
- 某个 SubFlow（如果当前正在处理 SubFlow）

#### 详细实现机制

**1. Flows.Replace 方法**：切换上下文的关键

```go
// pkg/lib/authenticationflow/workflows.go:3-18
type Flows struct {
    Root    *Flow    // 整个流程树的根（始终不变）
    Nearest *Flow    // 当前正在处理的流程（可能是根流程或某个 SubFlow）
}

// Replace 方法返回新的 Flows 实例，Neareast 被替换，但 Root 保持不变
func (w Flows) Replace(nearest *Flow) Flows {
    w.Nearest = nearest
    return w
}
```

**设计原理**：`Flows` 是值类型。`Replace` 创建新的实例，切换 `Nearest` 指向，但 `Root` 保持不变。这使得递归调用可以在自己的上下文中执行，不影响上层调用者。

**2. 递归进入 SubFlow 时的上下文切换**：

```go
// pkg/lib/authenticationflow/input.go:133-134
case NodeTypeSubFlow:
    // 关键：递归进入子流程，同时切换 flows.Nearest 指向子流程
    return FindInputReactorForFlow(ctx, deps, flows.Replace(n.SubFlow))
```

**上下文切换示意**：

```
flows (Nearest = 父流程)
    │
    ▼ 遇到 SubFlow Node
    │
    ├─ flows.Replace(n.SubFlow)  ◀── 创建新 Flows 实例
    │   ├─ Root:    父流程.Root（不变）
    │   └─ Nearest: n.SubFlow.Flow（指向子流程）
    │
    ▼ 递归调用 FindInputReactorForFlow(新 flows)
        │
        └─ 现在 flows.Nearest 指向子流程
            ├─ 检查子流程的最后一个 Node
            └─ 或调用子流程 Intent.CanReactTo
```

**3. FindInputReactorResult 传递正确的 Flows**：

```go
// pkg/lib/authenticationflow/input.go:101-109
inputSchema, err := flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
if err == nil {
    return &FindInputReactorResult{
        Flows:        flows,        // 返回包含正确 Nearest 的 Flows
        InputReactor: flows.Nearest.Intent,
        InputSchema:  inputSchema,
    }, nil
}
```

**4. appendNode 使用返回的 Flows**：

```go
// pkg/lib/authenticationflow/accept.go:128-152
// 调用 FindInputReactor，返回的 result 包含正确的 Flows（Nearest 可能指向 SubFlow）
findInputReactorResult, err = FindInputReactor(ctx, deps, flows)

// 调用 ReactTo，使用 result 中的 Flows
reactToResult, err = findInputReactorResult.InputReactor.ReactTo(
    ctx, deps, findInputReactorResult.Flows, input
)

// 关键：使用 findInputReactorResult.Flows 调用 appendNode
// 如果 FindInputReactor 进入了 SubFlow，则 Flows.Nearest 指向 SubFlow
err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
```

```go
// pkg/lib/authenticationflow/accept.go:271-272
func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
    // 追加到 flows.Nearest.Nodes
    // 如果 FindInputReactor 进入了 SubFlow，则 Nearest 指向 SubFlow
    // 如果没有进入 SubFlow，则 Nearest 指向根流程
    flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)
    // ...
}
```

**完整数据流示意**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              初始状态                                         │
│  Flows {                                                                    │
│      Root:    根流程（包含父流程 Intent 和已创建的 Nodes）                      │
│      Nearest: 根流程                                                          │
│  }                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼ 调用 FindInputReactor(ctx, deps, flows)
    │
    ├─ FindInputReactorForFlow(flows)  [Nearest = 根流程]
    │   ├─ 检查根流程的最后一个 Node：SubFlow Node
    │   │   └─ NodeTypeSubFlow
    │   │   └─ 调用 FindInputReactorForNode(flows, subFlowNode)
    │   │       │
    │   │       ▼ 遇到 SubFlow
    │   │       │
    │   │       └─ flows.Replace(n.SubFlow)  ◀── 创建新 Flows
    │   │           ├─ Root:    根流程（不变）
    │   │           └─ Nearest: SubFlow.Flow（指向子流程）
    │   │           │
    │   │           ▼ 递归 FindInputReactorForFlow(新 flows)
    │   │               │
    │   │               ├─ 检查 SubFlow 的 Nodes（假设为空）
    │   │               │
    │   │               └─ 调用 SubFlow.Intent.CanReactTo(ctx, deps, 新 flows)
    │   │                   └─ 返回 nil（可以继续）
    │   │               │
    │   │               └─ 返回 &FindInputReactorResult{
    │   │                       Flows:        新 flows,  ◀── Nearest 指向 SubFlow
    │   │                       InputReactor: SubFlow.Intent,
    │   │                       InputSchema:  nil,
    │   │                   }
    │   │
    │   └─ 返回上述结果
    │
    ▼ 回到 doAccept
    │
    ├─ findInputReactorResult.Flows.Nearest = SubFlow  ◀── 关键！
    │
    ├─ 调用 SubFlow.Intent.ReactTo(ctx, deps, findInputReactorResult.Flows, input)
    │   └─ 返回 NodeAuthenticationOOB
    │
    ├─ appendNode(ctx, deps, findInputReactorResult.Flows, NodeAuthenticationOOB)
    │   │
    │   └─ findInputReactorResult.Flows.Nearest.Nodes = append(...)
    │       │
    │       ▼ 追加到 SubFlow 的 Nodes！
    │       │
    │       SubFlow.Nodes = [NodeAuthenticationOOB]  ◀── Node 被正确追加到子流程
    │
    └─ 继续循环...
```

### 核心机制总结


| 组件                                          | 作用                                                            | 代码位置                     |
| ------------------------------------------- | ------------------------------------------------------------- | ------------------------ |
| `Flows.Replace`                             | 切换 `Nearest` 指向，保持 `Root` 不变，返回新的 `Flows` 实例                  | `workflows.go:15-18`     |
| `FindInputReactorResult.Flows`              | 传递包含正确 `Nearest` 的上下文                                         | `input.go:73`            |
| `FindInputReactorForNode` (NodeTypeSubFlow) | 递归进入 SubFlow，同时切换上下文                                          | `input.go:133-134`       |
| `appendNode`                                | 使用 `findInputReactorResult.Flows.Nearest.Nodes` 追加，确保追加到正确的流程 | `accept.go:263, 271-272` |


**设计精妙之处**：通过 `Flows` 的值语义和 `Replace` 方法，实现了上下文的"切换"而非"修改"。每次递归调用都有自己的 `Flows` 实例，互不影响，而上层调用者通过 `FindInputReactorResult` 获得正确的上下文用于后续操作。

### SubFlow 执行流程图解

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            第 1 次 Accept 调用                               │
│  用户选择认证器后，调用 Accept                                                  │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼ doAccept 循环开始
    │
    ├─ FindInputReactor
    │   ├─ FindInputReactorForFlow (Nearest = 父流程 IntentUseAuthenticatorOOBOTP)
    │   │   ├─ 检查父流程的最后一个 Node：NodeDidSelectAuthenticator
    │   │   │   └─ 不是 InputReactor，返回 ErrEOF
    │   │   │
    │   │   └─ 调用父流程 Intent.CanReactTo
    │   │       └─ 检查里程碑：claimVerified = false
    │   │       └─ 返回 nil（可以继续，不需要用户输入）
    │   │
    │   └─ 返回：InputReactor = IntentUseAuthenticatorOOBOTP
    │
    ├─ IntentUseAuthenticatorOOBOTP.ReactTo
    │   └─ 检查里程碑：!claimVerified
    │   └─ 返回 NewSubFlow(&IntentAuthenticationOOB{...})  ◀── 创建子流程
    │
    ├─ appendNode 到父流程
    │   └─ 父流程 Nodes = [NodeDidSelectAuthenticator, Node{type: SUB_FLOW, SubFlow: &Flow{Intent: IntentAuthenticationOOB}}]
    │
    └─ 继续循环（changed = true）
        │
        ▼ 第 2 次循环
        │
        ├─ FindInputReactor
        │   ├─ FindInputReactorForFlow (Nearest = 父流程)
        │   │   ├─ 检查最后一个 Node：SubFlow Node  ◀── 发现子流程
        │   │   │   └─ NodeTypeSubFlow
        │   │   │   └─ 递归调用 FindInputReactorForNode
        │   │   │       └─ FindInputReactorForFlow(flows.Replace(n.SubFlow))
        │   │   │           │
        │   │   │           ▼ flows.Nearest 现在指向子流程
        │   │   │           │
        │   │   │           ├─ 子流程没有 Nodes
        │   │   │           │
        │   │   │           └─ 调用子流程 Intent.CanReactTo  ◀── 第 1 次调用 IntentAuthenticationOOB
        │   │   │               └─ 检查 MilestoneOOBOTPVerified = false
        │   │   │               └─ 返回 nil（可以继续）
        │   │
        │   └─ 返回：InputReactor = IntentAuthenticationOOB（子流程）
        │
        ├─ IntentAuthenticationOOB.ReactTo  ◀── 第 1 次调用 ReactTo
        │   └─ 未验证，创建 NodeAuthenticationOOB
        │   └─ 返回 NewNodeAuthenticationOOB(...)
        │
        ├─ appendNode 到子流程  ◀── 关键：flows.Nearest 指向子流程
        │   └─ 子流程 Nodes = [NodeAuthenticationOOB]
        │
        └─ 继续循环（changed = true）
            │
            ▼ 第 3 次循环
            │
            ├─ FindInputReactor
            │   └─ ... 进入子流程 ...
            │       ├─ 检查最后一个 Node：NodeAuthenticationOOB
            │       │   └─ 是 InputReactor
            │       │   └─ 调用 NodeAuthenticationOOB.CanReactTo
            │       │       └─ 返回 InputSchemaNodeAuthenticationOOB（等待用户输入验证码）
            │       │
            │       └─ 返回：InputReactor = NodeAuthenticationOOB
            │
            └─ NodeAuthenticationOOB.ReactTo  ◀── 等待用户输入
                └─ 用户还没有输入，这次 Accept 结束，等待下次用户提交验证码
```

用户提交验证码后，再次调用 `Accept`：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            第 2 次 Accept 调用                               │
│  用户提交验证码                                                                │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼ doAccept 循环开始
    │
    ├─ FindInputReactor
    │   └─ ... 进入子流程 ...
    │       └─ 检查最后一个 Node：NodeAuthenticationOOB
    │           └─ NodeAuthenticationOOB.CanReactTo 返回需要输入
    │           └─ 返回：InputReactor = NodeAuthenticationOOB
    │
    ├─ NodeAuthenticationOOB.ReactTo（用户输入验证码）
    │   └─ 验证 OTP 成功
    │   └─ 返回 NodeDoMarkClaimVerified
    │   └─ 同时 NodeAuthenticationOOB 实现 MilestoneOOBOTPVerified
    │
    ├─ appendNode 到子流程
    │   └─ 子流程 Nodes = [NodeAuthenticationOOB, NodeDoMarkClaimVerified]
    │
    └─ 继续循环（changed = true）
        │
        ▼ 第 5 次循环
        │
        ├─ FindInputReactor
        │   └─ ... 进入子流程 ...
        │       ├─ 检查最后一个 Node：NodeDoMarkClaimVerified（不是 InputReactor），返回 ErrEOF
        │       │
        │       └─ 调用子流程 Intent.CanReactTo  ◀── 第 2 次调用 IntentAuthenticationOOB.CanReactTo
        │           └─ 检查 MilestoneOOBOTPVerified = true（NodeAuthenticationOOB 实现了）
        │           └─ 检查 MilestoneOOBOTPLastUsedChannelUpdated = false
        │           └─ 返回 nil（可以继续）
        │
        ├─ IntentAuthenticationOOB.ReactTo  ◀── 第 2 次调用 ReactTo
        │   └─ 返回 NodeDoUpdateLastUsedChannel
        │
        ├─ appendNode 到子流程
        │   └─ 子流程 Nodes = [NodeAuthenticationOOB, NodeDoMarkClaimVerified, NodeDoUpdateLastUsedChannel]
        │
        └─ 继续循环（changed = true）
            │
            ▼ 第 6 次循环
            │
            ├─ FindInputReactor
            │   └─ ... 进入子流程 ...
            │       ├─ 检查最后一个 Node：NodeDoUpdateLastUsedChannel（不是 InputReactor），返回 ErrEOF
            │       │
            │       └─ 调用子流程 Intent.CanReactTo  ◀── 第 3 次调用 IntentAuthenticationOOB.CanReactTo
            │           └─ 检查 MilestoneOOBOTPVerified = true
            │           └─ 检查 MilestoneOOBOTPLastUsedChannelUpdated = true（NodeDoUpdateLastUsedChannel 实现了）
            │           └─ 所有里程碑完成，返回 ErrEOF  ◀── 子流程完成
            │
            └─ 子流程结束，回到父流程的循环
                │
                ▼ 第 7 次循环（flows.Nearest 回到父流程）
                │
                ├─ FindInputReactor
                │   └─ FindInputReactorForFlow (Nearest = 父流程)
                │       ├─ 检查最后一个 Node：SubFlow Node
                │       │   └─ 递归进入子流程
                │       │       └─ 调用子流程 Intent.CanReactTo
                │       │           └─ 子流程返回 ErrEOF（已完成）
                │       │       └─ 子流程的 FindInputReactor 返回 ErrEOF
                │       │
                │       └─ 回到父流程，调用父流程 Intent.CanReactTo
                │           └─ 现在 claimVerified = true（子流程产生了 MilestoneDoMarkClaimVerified）
                │           └─ 检查 MilestoneDidAuthenticate = false
                │           └─ 返回 nil（可以继续）
                │
                ├─ IntentUseAuthenticatorOOBOTP.ReactTo
                │   └─ 返回 NodeDoUseAuthenticatorSimple
                │
                ... 后续步骤 ...
```

### IntentAuthenticationOOB 被调用的时机总结


| 调用次数  | 调用方法             | 触发条件                                  | 返回值/行为                                          |
| ----- | ---------------- | ------------------------------------- | ----------------------------------------------- |
| 第 1 次 | `CanReactTo`     | 子流程刚创建，无 Nodes                        | 返回 `nil`（继续），随后创建 `NodeAuthenticationOOB`       |
| 第 2 次 | `CanReactTo`     | `NodeAuthenticationOOB` 完成后（用户验证 OTP） | 返回 `nil`（继续），随后创建 `NodeDoUpdateLastUsedChannel` |
| 第 3 次 | `CanReactTo`     | `NodeDoUpdateLastUsedChannel` 完成后     | 返回 `ErrEOF`（子流程完成）                              |
| -     | `ReactTo`（第 1 次） | 子流程启动，需要发送 OTP                        | 创建 `NodeAuthenticationOOB`                      |
| -     | `ReactTo`（第 2 次） | OTP 验证完成，需要更新最后使用通道                   | 创建 `NodeDoUpdateLastUsedChannel`                |


### 关键机制总结


| 机制                             | 说明                                                                                                 |
| ------------------------------ | -------------------------------------------------------------------------------------------------- |
| `**doAccept` 循环**              | 无限循环处理输入，直到出错或流程完成，最多执行 100 次                                                                      |
| `**FindInputReactor`**         | 从当前流程找到可以接收输入的组件，优先检查最后一个 Node                                                                     |
| `**flows.Replace(n.SubFlow)`** | 切换 `flows.Nearest` 指向子流程，后续操作都在子流程中                                                                |
| **递归进入 SubFlow**               | 遇到 `NodeTypeSubFlow` 时，递归调用 `FindInputReactorForFlow`                                              |
| `**appendNode` 目标**            | Node 被追加到 `flows.Nearest.Nodes`，可能是父流程或子流程                                                         |
| **多次调用原因**                     | 每次循环都会重新调用 `FindInputReactor`，而它会递归进入 SubFlow，检查其 Intent 的 `CanReactTo`。只要子流程返回的不是 `ErrEOF`，循环就会继续 |


---

## 实际案例：OTP 验证流程

### 完整流程图

```
IntentUseAuthenticatorOOBOTP (父流程 Intent)
    │
    ▼ CanReactTo: 检查 MilestoneDidSelectAuthenticator
    ├─ 未选择 → 返回 InputSchemaUseAuthenticatorOOBOTP
    │
    ▼ ReactTo (用户选择认证器后)
    ├─ 返回 NodeDidSelectAuthenticator (产生 MilestoneDidSelectAuthenticator)
    │
    ▼ CanReactTo: 检查 MilestoneDoMarkClaimVerified
    ├─ 未验证 → 返回 nil (继续)
    │
    ▼ ReactTo
    └─ 返回 NewSubFlow(&IntentAuthenticationOOB{...})
        │
        ▼ IntentAuthenticationOOB.CanReactTo
        ├─ 检查 MilestoneOOBOTPVerified
        │   └─ 未验证 → 检查通道数量
        │       ├─ 单通道 → 返回 nil
        │       └─ 多通道 → 返回 InputSchemaTakeOOBOTPChannel
        │
        ▼ IntentAuthenticationOOB.ReactTo
        ├─ 创建 NodeAuthenticationOOB (发送 OTP)
        │   │
        │   ▼ NodeAuthenticationOOB.CanReactTo
        │   └─ 返回 InputSchemaNodeAuthenticationOOB (等待验证码)
        │
        │   ▼ NodeAuthenticationOOB.ReactTo (用户输入验证码)
        │   ├─ 验证成功 → 返回 NodeDoMarkClaimVerified
        │   │                    │
        │   │                    ▼ 产生 MilestoneDoMarkClaimVerified
        │   │                    ▼ 同时 NodeAuthenticationOOB 实现 MilestoneOOBOTPVerified
        │   │
        ▼ IntentAuthenticationOOB.CanReactTo
        ├─ 检查 MilestoneOOBOTPVerified ✓
        ├─ 检查 MilestoneOOBOTPLastUsedChannelUpdated
        │   └─ 未更新 → 返回 nil
        │
        ▼ IntentAuthenticationOOB.ReactTo
        └─ 返回 NodeDoUpdateLastUsedChannel
               │
               ▼ 产生 MilestoneOOBOTPLastUsedChannelUpdated
               │
        ▼ IntentAuthenticationOOB.CanReactTo
        ├─ 所有里程碑完成 → 返回 ErrEOF (子流程结束)
        │
        ▼ 回到父流程
    ▼ IntentUseAuthenticatorOOBOTP.CanReactTo
    ├─ 检查 MilestoneDoMarkClaimVerified ✓ (由子流程产生)
    ├─ 检查 MilestoneDidAuthenticate
    │   └─ 未认证 → 返回 nil
    │
    ▼ ReactTo
    └─ 返回 NodeDoUseAuthenticatorSimple
           │
           ▼ 产生 MilestoneDidAuthenticate
           │
    ▼ CanReactTo
    └─ 所有里程碑完成 → 返回 ErrEOF (整个认证完成)
```

### 关键代码片段

**父流程启动子流程**：

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go:164-178
case !claimVerified:
    info := m.MilestoneDidSelectAuthenticator()
    claimName, _ := info.OOBOTP.ToClaimPair()
    purpose := otp.PurposeOOBOTP
    otpForm := getOTPForm(purpose, claimName, deps.Config.Authenticator.OOB.Email)
    
    // 创建 OTP 验证子流程 Intent
    return authflow.NewSubFlow(&IntentAuthenticationOOB{
        JSONPointer:    n.JSONPointer,
        UserID:         n.UserID,
        Purpose:        purpose,
        Authentication: n.Authentication,
        Info:           info,
        Form:           otpForm,
    }), nil
```

**子流程内部状态流转**：

```go
// pkg/lib/authenticationflow/declarative/intent_authn_oob.go:58-95
func (i *IntentAuthenticationOOB) CanReactTo(...) (authflow.InputSchema, error) {
    _, _, verified := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPVerified](flows)
    if !verified {
        // 阶段1：选择通道
        channels := i.getChannels(deps)
        if len(channels) == 1 {
            return nil, nil  // 单通道直接继续
        }
        return &InputSchemaTakeOOBOTPChannel{...}, nil
    }

    _, _, lastUsedChannelUpdated := authflow.FindMilestoneInCurrentFlow[MilestoneOOBOTPLastUsedChannelUpdated](flows)
    if !lastUsedChannelUpdated {
        return nil, nil  // 阶段2：更新最后使用通道
    }

    return nil, authflow.ErrEOF  // 子流程结束
}
```

**OTP 验证节点**：

```go
// pkg/lib/authenticationflow/declarative/node_authn_oob.go:89-131
func (n *NodeAuthenticationOOB) ReactTo(...) (authflow.ReactToResult, error) {
    var inputNodeAuthenticationOOB inputNodeAuthenticationOOB
    if !authflow.AsInput(input, &inputNodeAuthenticationOOB) {
        return nil, authflow.ErrIncompatibleInput
    }

    switch {
    case inputNodeAuthenticationOOB.IsCode():
        code := inputNodeAuthenticationOOB.GetCode()
        // 验证 OTP...
        
        // 验证成功，返回标记声明已验证的节点
        verifiedClaim := deps.Verification.NewVerifiedClaim(ctx, n.UserID, string(claimName), claimValue)
        verifiedClaim.SetVerifiedByChannel(n.Channel)
        return authflow.NewNodeSimple(&NodeDoMarkClaimVerified{
            Claim: verifiedClaim,
        }), nil
    }
}

// 同时实现 MilestoneOOBOTPVerified
func (n *NodeAuthenticationOOB) MilestoneOOBOTPVerifiedChannel() model.AuthenticatorOOBChannel {
    return n.Channel
}
```

---

## 总结：设计模式核心要点


| 机制                             | 作用                        | 典型使用场景           |
| ------------------------------ | ------------------------- | ---------------- |
| **Node 链表**                    | 按顺序记录已执行的步骤，形成不可变历史       | 流程暂停/恢复、审计追踪     |
| **Milestone 接口**               | 标记某个关键步骤是否完成              | 跨层级状态感知、条件判断     |
| **FindMilestoneInCurrentFlow** | 从 Node 链表中查找特定里程碑         | 阶段检测、流程控制        |
| **SubFlow**                    | 封装复杂的、有内部状态的多步骤流程         | OTP 验证、注册流程、账户恢复 |
| **CanReactTo**                 | 检查当前状态，决定需要用户输入什么         | 动态表单生成、流程分支      |
| **ReactTo**                    | 处理输入，产生新的 Node 或 SubFlow  | 状态推进、副作用执行       |
| **ErrEOF**                     | 表示当前 Intent/Node 的所有步骤已完成 | 流程结束检测           |


### 核心设计思想

1. **不可变历史**：通过追加 Node 记录流程历史，而非修改状态
2. **分层封装**：SubFlow 封装复杂逻辑，父流程通过 Milestone 感知结果
3. **声明式状态**：通过查询 Milestone 判断状态，而非维护显式状态机
4. **统一接口**：所有 Intent 和 Node 都实现 `InputReactor`，形成一致的交互模式

### 优势

- **可测试性**：每个 Intent 和 Node 都是独立的，可单元测试
- **可观测性**：完整的执行历史可用于调试和审计
- **可扩展性**：新的认证方式只需实现新的 Intent/Node
- **可靠性**：状态持久化到数据库，支持流程中断恢复

---

## 相关文档

- [Authgear Flow Runtime Mechanism](./Authgear-Flow-Runtime-Mechanism.md) - 运行时机制详解
- [代码位置](../../pkg/lib/authenticationflow/) - 框架源代码

