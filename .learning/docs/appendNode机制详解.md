# appendNode 机制详解

## 核心问题解答

### 问题1: appendNode 做了什么？

```go
// accept.go:271-272
func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
	flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)
	// ... 执行新节点的 RunEffect
}
```

**答案：将新节点追加到当前 Flow 的 Nodes 数组末尾，并执行其 RunEffect。**

注意：这是**追加**（append）不是替换（replace）。Flow 的 Nodes 数组会不断增长，记录整个流程的执行路径。

---

### 问题2: 为什么 FindInputReactor 找到的是"最新节点"，但 ReactTo 后要 append 新节点？

**关键理解：ReactTo 返回的是"下一个节点"，不是"修改当前节点"**

```
流程执行机制（以登录为例）：

┌─────────────────────────────────────────────────────────────────────┐
│ 第1轮 accept 循环                                                   │
│                                                                    │
│ 1. FindInputReactor 找到 IntentLoginFlowStepIdentify (Intent)       │
│    - 因为 flows.Nearest.Nodes 为空                                 │
│                                                                    │
│ 2. 用户输入: { "identification": "email" }                          │
│                                                                    │
│ 3. IntentLoginFlowStepIdentify.ReactTo()                           │
│    └── 返回 authflow.NewSubFlow(&IntentUseIdentityLoginID{...})      │
│        (这是一个 NodeTypeSubFlow 类型的节点)                        │
│                                                                    │
│ 4. appendNode 将 IntentUseIdentityLoginID 子 Flow 追加到 Nodes      │
│                                                                    │
│ 结果: Nodes = [Node{type: SubFlow, SubFlow.Intent: IntentUseIdentityLoginID}] │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 第2轮 accept 循环 (内层循环，进入子 Flow)                             │
│                                                                    │
│ 1. FindInputReactor 找到 IntentUseIdentityLoginID (子 Flow 的 Intent)│
│    - 因为子 Flow 的 Nodes 为空                                       │
│                                                                    │
│ 2. 用户输入: { "login_id": "user@example.com" }                     │
│                                                                    │
│ 3. IntentUseIdentityLoginID.ReactTo()                               │
│    └── 返回 NewNodeDoUseIdentityReactToResult(...)                  │
│        (这是一个 NodeTypeSimple 类型的节点)                           │
│                                                                    │
│ 4. appendNode 将 NodeDoUseIdentity 追加到子 Flow 的 Nodes            │
│                                                                    │
│ 结果: 子 Flow Nodes = [Node{type: Simple, Simple: NodeDoUseIdentity}]│
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 第3轮 accept 循环                                                   │
│                                                                    │
│ 1. FindInputReactor 检查子 Flow 的最后一个节点                        │
│    - NodeDoUseIdentity 不需要输入 → 返回 ErrEOF                      │
│                                                                    │
│ 2. 检查子 Flow 的 IntentUseIdentityLoginID                          │
│    - CanReactTo 发现已有 MilestoneDoUseIdentity → 返回 ErrEOF        │
│                                                                    │
│ 3. 子 Flow 结束，回到外层 IntentLoginFlowStepIdentify               │
│                                                                    │
│ 4. IntentLoginFlowStepIdentify.ReactTo() 继续执行下一个分支           │
│    └── 返回 authflow.NewSubFlow(&IntentLoginFlowSteps{...})         │
│        (进入 authenticate 步骤)                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 问题3: 输入 email 后，password 节点是怎么来的？

**完整流程跟踪（基于 YAML 配置）：**

```yaml
- name: default_login_flow
  type: login
  steps:
    - type: identify          ← Step 0
      one_of:
        - identification: email
    - type: authenticate      ← Step 1
      one_of:
        - authentication: primary_password
    - type: authenticate      ← Step 2
      one_of:
        - authentication: secondary_totp
```

**执行过程：**

```
1. 初始状态
   Flow.Intent = IntentLoginFlowSteps{NextStepIndex: 0}
   Flow.Nodes = []

2. 第1轮 accept
   FindInputReactor: IntentLoginFlowSteps (Nodes为空)
   ReactTo: 返回 IntentLoginFlowStepIdentify (SubFlow)
   appendNode: Nodes = [Node{SubFlow, Intent: IntentLoginFlowStepIdentify}]

3. 第2轮 accept (进入子 Flow IntentLoginFlowStepIdentify)
   FindInputReactor: IntentLoginFlowStepIdentify (子 Flow Nodes为空)
   ReactTo: 返回 IntentUseIdentityLoginID (SubFlow, 因为选了 email)
   appendNode: 子 Flow Nodes = [Node{SubFlow, Intent: IntentUseIdentityLoginID}]

4. 第3轮 accept (进入子 Flow IntentUseIdentityLoginID)
   FindInputReactor: IntentUseIdentityLoginID (子 Flow Nodes为空)
   用户输入: { "login_id": "user@example.com" }
   ReactTo: 返回 NodeDoUseIdentity (Simple 节点，标识用户已找到)
   appendNode: 子 Flow Nodes = [Node{Simple: NodeDoUseIdentity}]

5. 第4轮 accept
   FindInputReactor: NodeDoUseIdentity (最后一个节点)
   - NodeDoUseIdentity.CanReactTo() → ErrEOF (不需要输入)
   
   回退到 IntentUseIdentityLoginID
   - CanReactTo() → ErrEOF (已有 MilestoneDoUseIdentity)
   
   子 Flow 结束

6. 第5轮 accept (回到 IntentLoginFlowStepIdentify)
   FindInputReactor: IntentLoginFlowStepIdentify
   ReactTo: 发现 identityUsed && !nestedStepsHandled
   └── 返回 IntentLoginFlowSteps{NextStepIndex: 1} (SubFlow)
   
   appendNode: Nodes = [
     Node{SubFlow, Intent: IntentLoginFlowStepIdentify},
     Node{SubFlow, Intent: IntentLoginFlowSteps}  ← 新追加！
   ]

7. 第6轮 accept (进入 IntentLoginFlowSteps)
   FindInputReactor: IntentLoginFlowSteps (Nodes为空)
   NextStepIndex=1 → Step 1 是 authenticate
   ReactTo: 返回 IntentLoginFlowStepAuthenticate (SubFlow)
   appendNode: 子 Flow Nodes = [Node{SubFlow, Intent: IntentLoginFlowStepAuthenticate}]

8. 第7轮 accept (进入 IntentLoginFlowStepAuthenticate)
   FindInputReactor: IntentLoginFlowStepAuthenticate
   ReactTo: 返回 IntentUseAuthenticatorPassword (因为配置是 primary_password)
   appendNode: 子 Flow Nodes = [
     Node{SubFlow, Intent: IntentLoginFlowStepAuthenticate},
     Node{SubFlow, Intent: IntentUseAuthenticatorPassword}  ← 密码验证节点！
   ]

9. 第8轮 accept (进入 IntentUseAuthenticatorPassword)
   FindInputReactor: IntentUseAuthenticatorPassword
   用户输入: { "password": "secret" }
   ReactTo: 验证密码，返回 NodeDoUseAuthenticatorPassword
   appendNode: 子 Flow Nodes = [..., Node{Simple: NodeDoUseAuthenticatorPassword}]
```

---

## 关键代码分析

### FindInputReactor 递归逻辑 (input.go:86-114)

```go
func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) (*FindInputReactorResult, error) {
	// 1. 先检查最后一个节点
	if len(flows.Nearest.Nodes) > 0 {
		lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
		findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
		if err == nil {
			return findInputReactorResult, nil  // 找到了！
		}
		if !errors.Is(err, ErrEOF) {
			return nil, err  // 真正错误
		}
		// err == ErrEOF，继续检查 Intent
	}

	// 2. 检查 Intent 是否能响应输入
	inputSchema, err := flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
	if err == nil {
		return &FindInputReactorResult{
			Flows:        flows,
			InputReactor: flows.Nearest.Intent,
			InputSchema:  inputSchema,
		}, nil
	}

	return nil, err  // ErrEOF 或其他错误
}
```

### ReactTo 返回结果处理 (accept.go:242-267)

```go
// ReactTo 执行后，返回的结果可以是：
var reactToResult ReactToResult
reactToResult, err = findInputReactorResult.InputReactor.ReactTo(...)

// 结果类型1: *Node (普通节点)
// 结果类型2: *NodeWithDelayedOneTimeFunction (带延迟函数的节点)
switch reactToResult := reactToResult.(type) {
case *Node:
	nextNode = *reactToResult
case *NodeWithDelayedOneTimeFunction:
	nextNode = *reactToResult.Node
	result.DelayedOneTimeFunctions = append(...)
}

// 追加到 Flow 的 Nodes
err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
```

### NewSubFlow 创建子 Flow 节点 (node.go:47-55)

```go
func NewSubFlow(intent Intent) *Node {
	return &Node{
		Type: NodeTypeSubFlow,
		SubFlow: &Flow{
			// FlowID and StateToken do not matter here.
			Intent: intent,  // 子 Flow 的 Intent 决定下一步行为
		},
	}
}
```

---

## 设计模式总结

| 概念 | 作用 | 示例 |
|------|------|------|
| **Intent** | 流程的"意图"，控制整体流程走向 | IntentLoginFlowSteps |
| **Node** | 流程中的"节点"，记录执行路径 | NodeDoUseIdentity |
| **SubFlow** | 子流程，嵌套的 Flow | IntentUseIdentityLoginID |
| **Milestone** | 里程碑，标记重要完成状态 | MilestoneDoUseIdentity |
| **ReactTo** | 响应输入，返回下一个节点 | 根据输入决定下一步 |
| **CanReactTo** | 检查是否能处理输入 | 返回 InputSchema 或 ErrEOF |

**核心设计：**
1. Flow 是一个树形结构，Nodes 数组记录执行路径
2. 每个节点/意图决定"下一步是什么"
3. FindInputReactor 深度优先查找需要输入的节点
4. ReactTo 根据业务逻辑创建并返回下一个节点
5. appendNode 将新节点追加到路径中
