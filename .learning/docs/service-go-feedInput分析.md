# service.go feedInput 方法代码分析

## 方法位置
`pkg/lib/authenticationflow/service.go:485-544`

---

## 1. 如何找到当前 Flow Step

### 核心函数: `FindInputReactor` (input.go:78)

寻找当前 step 的流程遵循**深度优先**原则：

```
FindInputReactor
  └── FindInputReactorForFlow
        ├── 1. 检查最后一个 Node (如果存在)
        │     └── FindInputReactorForNode
        │           ├── NodeTypeSimple: 检查 Simple 节点是否实现 InputReactor
        │           └── NodeTypeSubFlow: 递归进入子 Flow
        │
        └── 2. 检查 Intent (如果所有 Node 都无法响应)
              └── flows.Nearest.Intent.CanReactTo()
```

### 判断标准
一个节点/意图能成为当前 step 的条件：
- 实现 `InputReactor` 接口
- `CanReactTo()` 返回 nil（表示可以处理输入）

### 代码参考

```86:114:pkg/lib/authenticationflow/input.go
func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) (*FindInputReactorResult, error) {
	if len(flows.Nearest.Nodes) > 0 {
		// We check the last node if it can react to input first.
		lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
		findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
		if err == nil {
			return findInputReactorResult, nil
		}
		// Return non ErrEOF error.
		if !errors.Is(err, ErrEOF) {
			return nil, err
		}
		// err is ErrEOF, fallthrough
	}

	// Otherwise we check if the intent can react to input.
	inputSchema, err := flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
	if err == nil {
		return &FindInputReactorResult{
			Flows:        flows,
			InputReactor: flows.Nearest.Intent,
			InputSchema:  inputSchema,
		}, nil
	}

	// err != nil here.
	// Regardless of whether err is ErrEOF, we return err.
	return nil, err
}
```

---

## 2. 如何执行当前 Step 的 Flow Action (reactTo)

### 流程

```
feedInput
  └── accept
        └── doAccept (accept.go:107)
              ├── 1. FindInputReactor - 找到当前 step
              ├── 2. inputFn - 将 rawMessage 转换为 Input
              ├── 3. ReactTo - 执行节点响应
              └── 4. appendNode - 将结果添加到 Flow
```

### ReactTo 执行 (input.go:25-28)

```go
type InputReactor interface {
	CanReactTo(ctx context.Context, deps *Dependencies, flows Flows) (InputSchema, error)
	ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (ReactToResult, error)
}
```

### ReactTo 返回值处理 (accept.go:151-197)

| 返回结果 | 处理方式 |
|---------|---------|
| `*Node` | 追加新节点到 `flows.Nearest.Nodes` |
| `*NodeWithDelayedOneTimeFunction` | 追加节点 + 记录延迟执行函数 |
| `ErrSameNode` | 标记 changed=true，停止循环 |
| `ErrReplaceNode` | 替换最后一个节点 |
| `ErrIncompatibleInput` | 停止循环，返回 nil |
| `ErrBotProtectionVerification` | 特殊处理机器人保护验证 |

---

## 3. Flow 更新机制

### 何时更新 Flow

**持久化时机**: `feedInput` 方法最后通过 `s.Store.CreateFlow(ctx, flow)` 保存

```534:536:pkg/lib/authenticationflow/service.go
	err = s.Store.CreateFlow(ctx, flow)
	if err != nil {
		return
	}
```

### Flow 中增加了什么信息

#### 1. 节点追加 (accept.go:271-272)

```go
func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
	flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)
	// ... 执行节点的 RunEffect
}
```

#### 2. StateToken 更新 (accept.go:110-113)

```go
defer func() {
	if changed {
		flows.Nearest.StateToken = newStateToken()  // 每次变更生成新 token
	}
	// ...
}()
```

#### 3. Effects 执行

- **RunEffect**: 在 ReadOnly 事务中执行 (accept.go:286-291)
- **OnCommitEffect**: 在 WithTx 事务中执行 (effect.go:81-129)

### 与 Session 信息的关系

```
┌─────────────────────────────────────────────────────────────────┐
│                         Session                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  FlowID, UserID, ClientID, RedirectURI...                │    │
│  │  BotProtectionVerificationResult                         │    │
│  └─────────────────────────────────────────────────────────┘    │
│                           │                                     │
│                           │ 1:N 关系                            │
│                           ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Flows (通过 FlowID 关联)                                 │    │
│  │  ┌─────────────────────────────────────────────────┐   │    │
│  │  │  Flow 1: Intent + Nodes + StateToken            │   │    │
│  │  └─────────────────────────────────────────────────┘   │    │
│  │  ┌─────────────────────────────────────────────────┐   │    │
│  │  │  Flow 2: Intent + Nodes + StateToken            │   │    │
│  │  └─────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

**区别**:
- **Session**: 长期存在，存储用户会话信息（UserID、ClientID等）
- **Flow**: 短期存在，存储当前认证流程的节点状态
- 多个 Flow 可以共享同一个 Session

---

## 4. shouldAccept 循环的目的

### 代码位置

```491:526:pkg/lib/authenticationflow/service.go
func (s *Service) feedInput(...) (flow *Flow, flowAction *FlowAction, err error) {
	var shouldAccept = true
	for shouldAccept {
		shouldAccept = false
		var acceptResult *AcceptResult = NewAcceptResult()
		flows := NewFlows(flow)
		err = s.Database.ReadOnly(ctx, func(ctx context.Context) error {
			// Apply the run-effects.
			err = ApplyRunEffects(ctx, s.Deps, flows)
			// ...
			err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
			// ...
		})
		acceptErr := s.processAcceptResult(ctx, session, flows, acceptResult)
		// ...
		if errors.Is(err, ErrPauseAndRetryAccept) {
			shouldAccept = true
			err = nil
		}
	}
}
```

### 解决的问题

#### 1. **支持延迟函数执行后重试**

某些节点需要执行一些**必须在事务外**完成的操作（如发送邮件、验证码验证），完成后需要重新进入 accept 循环。

触发 `ErrPauseAndRetryAccept` 的场景：

```go
// node_pre_authenticate.go:90
return nil, authflow.ErrPauseAndRetryAccept

// node_post_identified.go:96
return nil, authflow.ErrPauseAndRetryAccept

// node_pre_initialize.go:86
return nil, authflow.ErrPauseAndRetryAccept
```

#### 2. **流程控制分离**

```
┌─────────────────────────────────────────────────────────────────────┐
│                    shouldAccept 循环                                 │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  ReadOnly 事务                                                │  │
│  │  ┌─────────────────────────────────────────────────────────┐ │  │
│  │  │  Accept 循环 (doAccept)                                    │ │  │
│  │  │  ├── FindInputReactor                                     │ │  │
│  │  │  ├── ReactTo                                              │ │  │
│  │  │  └── appendNode (添加节点)                                 │ │  │
│  │  └─────────────────────────────────────────────────────────┘ │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                           │                                         │
│  ┌────────────────────────▼────────────────────────────────────┐  │
│  │  事务外: processAcceptResult                                   │  │
│  │  ├── 执行 DelayedOneTimeFunctions                              │  │
│  │  └── 更新 Session (BotProtection 验证结果等)                    │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                           │                                         │
│                    ┌──────▼──────┐                                   │
│                    │ 需要重试?    │                                   │
│                    │ (ErrPause...)│                                   │
│                    └──────┬──────┘                                   │
│                           是                                         │
│                           ▼                                          │
│                    继续 shouldAccept 循环                              │
└─────────────────────────────────────────────────────────────────────┘
```

### 关键设计要点

1. **事务边界清晰**: ReadOnly 事务只处理纯状态变更，副作用在事务外执行
2. **状态隔离**: 每次循环重新创建 `flows` 对象，避免状态污染
3. **错误处理**: `processAcceptResult` 处理延迟函数的错误，失败时恢复数据库状态

### processAcceptResult 详解 (service.go:170-199)

```go
func (s *Service) processAcceptResult(...) error {
	// 1. 保存机器人保护验证结果到 Session
	if acceptResult.BotProtectionVerificationResult != nil {
		session.SetBotProtectionVerificationResult(...)
		s.Store.UpdateSession(ctx, session)
	}

	// 2. 执行延迟函数
	for _, fn := range acceptResult.DelayedOneTimeFunctions {
		err := fn(ctx, s.Deps)
		if err != nil {
			// 失败时：在 ReadOnly 事务中恢复数据库状态
			s.Database.ReadOnly(ctx, func(ctx context.Context) error {
				ApplyRunEffects(ctx, s.Deps, flows)  // 重新应用 RunEffects 恢复状态
				return newAuthenticationFlowError(flows, err)
			})
		}
	}
}
```

---

## 总结

| 问题 | 答案 |
|------|------|
| 如何找到当前 step | 深度优先：最后节点 → 递归子 Flow → Intent |
| 如何执行 action | `ReactTo()` 方法，返回新节点或特殊错误 |
| Flow 更新时机 | `Accept` 完成后，`CreateFlow` 持久化到 Redis |
| 增加的信息 | 新节点、新 StateToken、Effects 执行结果 |
| Session 关系 | Session 长期存在，Flow 短期存在，1:N 关系 |
| shouldAccept 目的 | 支持延迟函数执行后重试，分离事务内外逻辑 |
