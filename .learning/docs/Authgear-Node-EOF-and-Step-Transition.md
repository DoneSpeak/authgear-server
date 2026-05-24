# Authgear Node EOF 判定与步骤切换机制

本文档详细解析：
1. 如何判定 Node 已经 ErrEOF
2. Nodes 和 Intent 都完成会怎样
3. 如何移动到下一个步骤

---

## 1. Node ErrEOF 判定机制

### 1.1 判定流程图

```
FindInputReactorForNode(node)
    │
    ▼
┌─────────────────────────────────────┐
│ Node.Type == NodeTypeSimple ?       │
└─────────────────────────────────────┘
    │
    ├── No (SubFlow) ──▶ 递归进入 SubFlow 的 Intent
    │
    └── Yes ──▶ node.Simple.(InputReactor) 类型断言
                    │
                    ├── 未实现 InputReactor ──▶ 返回 ErrEOF
                    │                          (Node 无法接收输入，视为完成)
                    │
                    └── 实现了 InputReactor ──▶ 调用 CanReactTo()
                                                    │
                                                    ├── 返回 nil ──▶ 返回 InputReactor
                                                    │               (Node 可以接收输入)
                                                    │
                                                    └── 返回 ErrEOF ──▶ 返回 ErrEOF
                                                    │                   (Node 已完成)
                                                    │
                                                    └── 返回其他错误 ──▶ 返回错误
```

### 1.2 代码实现

```go
// pkg/lib/authenticationflow/input.go:116-138

func FindInputReactorForNode(ctx context.Context, deps *Dependencies, flows Flows, n *Node) (*FindInputReactorResult, error) {
    switch n.Type {
    case NodeTypeSimple:
        // 1. 检查 Node 是否实现了 InputReactor 接口
        reactor, ok := n.Simple.(InputReactor)
        if !ok {
            // 未实现 = 无法接收输入 = 已完成
            return nil, ErrEOF
        }

        // 2. 调用 CanReactTo 判断是否能接收输入
        inputSchema, err := reactor.CanReactTo(ctx, deps, flows)
        if err == nil {
            // 可以接收输入
            return &FindInputReactorResult{
                Flows:        flows,
                InputReactor: reactor,
                InputSchema:  inputSchema,
            }, nil
        }
        // 返回错误（可能是 ErrEOF 或其他错误）
        return nil, err
        
    case NodeTypeSubFlow:
        // 递归进入 SubFlow
        return FindInputReactorForFlow(ctx, deps, flows.Replace(n.SubFlow))
    }
}
```

### 1.3 Node 返回 ErrEOF 的典型场景

| 场景 | CanReactTo 返回值 | 说明 |
|------|-------------------|------|
| NodeDoUseIdentity | `nil, ErrEOF` | 已完成身份识别，不再接收输入 |
| NodeDidSelectAuthenticator | `nil, ErrEOF` | 已选择认证器，不再接收输入 |
| NodeDoMarkClaimVerified | `nil, ErrEOF` | 已标记声明验证，不再接收输入 |
| NodeDoUseAuthenticatorSimple | `nil, ErrEOF` | 已完成认证使用，不再接收输入 |

**示例：**

```go
// pkg/lib/authenticationflow/declarative/node_do_use_identity.go
func (n *NodeDoUseIdentity) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 这个 Node 只是记录身份识别结果，不需要接收任何输入
    return nil, authflow.ErrEOF  // 直接返回 EOF
}
```

---

## 2. Nodes 和 Intent 都完成的处理

### 2.1 整体判定流程

```
FindInputReactorForFlow(flow)
    │
    ▼
检查最后一个 Node
    │
    ├── Node 可以接收输入 ──▶ 使用该 Node 作为 Reactor
    │
    └── Node 返回 ErrEOF ──▶ fallthrough 检查 Intent
                                │
                                ├── Intent 可以接收输入 ──▶ 使用 Intent 作为 Reactor
                                │
                                └── Intent 返回 ErrEOF ──▶ 整个 Flow 完成
                                                            (返回 ErrEOF 给上层)
```

### 2.2 代码实现

```go
// pkg/lib/authenticationflow/input.go:86-114

func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) (*FindInputReactorResult, error) {
    // 1. 优先检查最后一个 Node
    if len(flows.Nearest.Nodes) > 0 {
        lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
        findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
        if err == nil {
            // Node 可以接收输入
            return findInputReactorResult, nil
        }
        // 返回非 ErrEOF 的错误，直接返回
        if !errors.Is(err, ErrEOF) {
            return nil, err
        }
        // err is ErrEOF，继续检查 Intent
    }

    // 2. 检查 Intent 是否能接收输入
    inputSchema, err := flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
    if err == nil {
        return &FindInputReactorResult{
            Flows:        flows,
            InputReactor: flows.Nearest.Intent,
            InputSchema:  inputSchema,
        }, nil
    }

    // 3. Intent 也返回错误（可能是 ErrEOF 或其他错误）
    // 不管 err 是 ErrEOF 还是其他错误，直接返回
    return nil, err  // <-- 这里如果 err == ErrEOF，整个 Flow 完成
}
```

### 2.3 Accept 循环中的处理

```go
// pkg/lib/authenticationflow/accept.go:122-131

func doAccept(...) (err error) {
    for {
        // 查找可以接收输入的 Reactor
        var findInputReactorResult *FindInputReactorResult
        findInputReactorResult, err = FindInputReactor(ctx, deps, flows)
        
        // 关键：如果返回 ErrEOF，循环结束
        if err != nil {
            return  // <-- 包括 ErrEOF 在内的所有错误都会退出循环
        }
        
        // 继续处理输入...
    }
}
```

### 2.4 Nodes 和 Intent 都完成的结果

| 层级 | 结果 | 处理方式 |
|------|------|----------|
| 当前 Flow | `ErrEOF` | Accept 循环退出，返回 ErrEOF |
| 父级 Intent | 收到 `ErrEOF` | 在自己的 CanReactTo 中通过 Milestone 检测子流程完成 |
| 整个流程 | 可能继续或结束 | 取决于父级 Intent 是否还有后续步骤 |

---

## 3. 如何移动到下一个步骤

### 3.1 整体架构

步骤切换由 **父级 Intent**（通常是 `IntentLoginFlowSteps`）控制，通过 `NextStepIndex` 跟踪当前步骤：

```
IntentLoginFlowSteps (父级，控制步骤切换)
    │
    ├── Step 0: IntentLoginFlowStepIdentify ──▶ 完成 ──┐
    │                                                   │
    ├── Step 1: IntentLoginFlowStepAuthenticate ──▶ 完成 ──┤── 父级检测到子流程完成
    │                                                       │   通过 MilestoneNestedSteps
    └── Step 2: IntentLoginFlowStepAuthenticate ──▶ 进行中 │
                                                          │
    NextStepIndex = 2  --------------------------------───┘
```

### 3.2 父级 Intent 的核心逻辑

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go:34-51

type IntentLoginFlowSteps struct {
    FlowReference authflow.FlowReference `json:"flow_reference,omitempty"`
    JSONPointer   jsonpointer.T          `json:"json_pointer,omitempty"`
    NextStepIndex int                    `json:"next_step_index"`  // <-- 关键：跟踪当前步骤
}

func (i *IntentLoginFlowSteps) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 获取配置中的步骤列表
    steps := current.GetSteps()
    
    // 检查是否还有未执行的步骤
    if i.NextStepIndex < len(steps) {
        return nil, nil  // <-- 还有步骤，可以接收 nil 输入继续
    }
    
    // 所有步骤完成
    return nil, authflow.ErrEOF
}

func (i *IntentLoginFlowSteps) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    steps := current.GetSteps()
    step := steps[i.NextStepIndex].(*config.AuthenticationFlowLoginFlowStep)

    var result authflow.ReactToResult
    switch step.Type {
    case config.AuthenticationFlowLoginFlowStepTypeIdentify:
        result = authflow.NewSubFlow(stepIdentify)
    case config.AuthenticationFlowLoginFlowStepTypeAuthenticate:
        result = authflow.NewSubFlow(stepAuthenticate)
    // ... 其他步骤类型
    }

    // 关键：步骤索引 +1
    i.NextStepIndex = i.NextStepIndex + 1  // <-- 移动到下一步
    return result, nil
}
```

### 3.3 子流程完成如何触发父级步骤切换

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go:124-152

func (i *IntentLoginFlowStepIdentify) CanReactTo(...) (authflow.InputSchema, error) {
    _, _, identityUsed := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
    _, _, loginHintChecked := authflow.FindMilestoneInCurrentFlow[MilestoneCheckLoginHint](flows)
    _, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)

    switch {
    case len(flows.Nearest.Nodes) == 0:
        return &InputSchemaStepIdentify{...}, nil  // 第一步：选择 identification
    case identityUsed && !loginHintChecked:
        return nil, nil  // 检查 login_hint
    case identityUsed && !nestedStepsHandled:   // <-- 关键条件
        // 子流程（嵌套 steps）未处理，需要启动子流程
        return nil, nil
    default:
        return nil, authflow.ErrEOF  // <-- 当前步骤完成
    }
}

func (i *IntentLoginFlowStepIdentify) ReactTo(...) (authflow.ReactToResult, error) {
    case identityUsed && !nestedStepsHandled:
        // 启动嵌套的子流程（即下一个 authenticate 步骤）
        return authflow.NewSubFlow(&IntentLoginFlowSteps{
            FlowReference: i.FlowReference,
            JSONPointer:   i.jsonPointer(step, identification),  // 指向嵌套 steps
        }), nil
}
```

### 3.4 步骤切换的完整流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         步骤切换完整流程                                     │
└─────────────────────────────────────────────────────────────────────────────┘

IntentLoginFlowSteps (父级)
    │
    ▼ CanReactTo: NextStepIndex = 0 < len(steps)
   返回 nil, nil  (还有步骤)
    │
    ▼ ReactTo
   创建 IntentLoginFlowStepIdentify (子级)
   NextStepIndex++ (现在 = 1)
    │
    ▼ 进入子级 Intent

IntentLoginFlowStepIdentify
    │
    ▼ CanReactTo: Nodes == 0
   返回 InputSchema (让用户选择 email/phone/oauth)
    │
    ▼ ReactTo: 用户选择 email
   创建 NodeDoUseIdentity
    │
    ▼ CanReactTo: identityUsed=true, nestedStepsHandled=false
   返回 nil, nil
    │
    ▼ ReactTo
   创建子 IntentLoginFlowSteps (处理嵌套 authenticate 步骤)
    │
    ▼ ...子流程执行中...

   [子流程完成]
    │
    ▼ 子 IntentLoginFlowSteps 返回 ErrEOF

IntentLoginFlowStepIdentify
    │
    ▼ CanReactTo: identityUsed=true, nestedStepsHandled=true
   其他 Milestones 也完成
   返回 nil, ErrEOF  (当前步骤完成)

   [返回到父级]
    │

IntentLoginFlowSteps (父级)
    │
    ▼ Accept 循环再次调用 CanReactTo
   NextStepIndex = 1 < len(steps)
   返回 nil, nil  (还有步骤)
    │
    ▼ ReactTo
   创建 IntentLoginFlowStepAuthenticate (下一个主步骤)
   NextStepIndex++ (现在 = 2)
    │
    ▼ ...继续执行...
```

### 3.5 Milestone 在步骤切换中的作用

```go
// pkg/lib/authenticationflow/declarative/milestone.go

type MilestoneNestedSteps interface {
    authflow.Milestone
    MilestoneNestedSteps()  // 标记嵌套 steps 是否已处理
}

// IntentLoginFlowSteps 实现了这个 Milestone
func (*IntentLoginFlowSteps) MilestoneNestedSteps() {}
```

父级 Intent 通过查找 `MilestoneNestedSteps` 来判断子流程是否完成：

```go
// 在父级 Intent 的 CanReactTo 中
_, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)

if !nestedStepsHandled {
    // 子流程还未处理，需要创建 SubFlow
    return nil, nil
}
// 子流程已完成，继续检查其他条件
```

---

## 4. 关键概念总结

### 4.1 EOF 传播链

```
Node.CanReactTo() ──▶ ErrEOF
    │
    ▼
FindInputReactorForNode() ──▶ ErrEOF
    │
    ▼
FindInputReactorForFlow() 检查 Intent
    │
    ▼
Intent.CanReactTo() ──▶ ErrEOF
    │
    ▼
FindInputReactorForFlow() ──▶ ErrEOF
    │
    ▼
doAccept() 循环结束 ──▶ 返回 ErrEOF
    │
    ▼
父级 Intent 检测到子流程完成
    │
    ▼
父级继续执行或返回 ErrEOF
```

### 4.2 步骤切换的核心机制

| 机制 | 作用 | 代码位置 |
|------|------|----------|
| `NextStepIndex` | 跟踪当前步骤索引 | `intent_login_flow_steps.go:20` |
| `MilestoneNestedSteps` | 标记嵌套步骤是否完成 | `milestone.go:71-74` |
| `FindMilestoneInCurrentFlow` | 在 Nodes 中查找 Milestone | `milestone.go:16-41` |
| `NewSubFlow()` | 创建子流程 | `flow.go` |
| `ErrEOF` | 信号：当前层级完成 | `errors.go` |

### 4.3 三种 EOF 场景对比

| 场景 | 触发条件 | 结果 |
|------|----------|------|
| Node EOF | Node 未实现 InputReactor 或 CanReactTo 返回 ErrEOF | 检查 Intent |
| Intent EOF | Intent.CanReactTo 返回 ErrEOF | 当前 Flow 完成，返回给父级 |
| Root Intent EOF | 最外层 Intent 返回 ErrEOF | 整个流程完成，调用 finishFlow() |

---

## 5. 代码追踪示例

假设当前 Flow 状态：

```
Intent: IntentLoginFlowSteps (NextStepIndex = 1)
Nodes: [
    0: NodeDoUseIdentity,
    1: NodeDidSelectAuthenticator,
    2: NodeAuthenticationOOB  (SubFlow IntentAuthenticationOOB)
]
```

执行过程：

```
1. FindInputReactor()
   └── FindInputReactorForFlow()
       ├── 检查 Node 2 (NodeAuthenticationOOB)
       │   └── NodeAuthenticationOOB.CanReactTo()
       │       └── 返回 InputSchemaNodeAuthenticationOOB, nil  (等待验证码)
       └── 返回 NodeAuthenticationOOB 作为 Reactor

2. ReactTo() 接收验证码
   └── 验证成功
   └── 返回 NodeDoMarkClaimVerified

3. appendNode() 追加新 Node
   └── Nodes 变为 [..., NodeDoMarkClaimVerified]

4. 下一轮 FindInputReactor()
   └── 检查 Node 3 (NodeDoMarkClaimVerified)
       └── NodeDoMarkClaimVerified.CanReactTo()
           └── 返回 nil, ErrEOF
   └── fallthrough 检查 Intent
       └── IntentAuthenticationOOB.CanReactTo()
           └── MilestoneOOBOTPVerified = true
           └── 返回 nil, nil  (需要更新 channel)
       
5. ReactTo() 更新 channel
   └── 返回 NodeDoUpdateLastUsedChannel

6. 再下一轮 FindInputReactor()
   └── NodeDoUpdateLastUsedChannel.CanReactTo()
       └── 返回 nil, ErrEOF
   └── IntentAuthenticationOOB.CanReactTo()
       └── 所有 Milestones 完成
       └── 返回 nil, ErrEOF  <-- Intent 完成
   
7. FindInputReactorForFlow() 返回 ErrEOF
   └── doAccept() 循环结束，返回 ErrEOF
   
8. 回到父级 IntentLoginFlowSteps
   └── CanReactTo() 再次调用
       └── NextStepIndex = 2 (已增加)
       └── 如果还有步骤，继续执行
       └── 如果没有步骤，返回 ErrEOF
```

---

## 相关文档

- [Authgear Input Processing And OTP Flow Analysis](Authgear-Input-Processing-And-OTP-Flow-Analysis.md) - 输入处理与 OTP 流程
- [Authgear Flow Runtime Mechanism](Authgear-Flow-Runtime-Mechanism.md) - 运行时机制详解
- [Authgear Nested Steps Implementation](Authgear-Nested-Steps-Implementation.md) - 嵌套步骤实现机制
