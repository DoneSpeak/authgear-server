# Accept 循环设计原理深度解析

## 核心问题

**用户疑问：**
> 每次 input 都是对最新 node 的操作，前面的 node 都已完成。每次 input 要么成功要么失败，成功则继续推进，没有 accept 循环的必要才对，但代码确实需要循环执行，这是什么原因？

这是一个非常好的问题。表面上看，单次请求处理单次输入似乎足够，但 Authgear Flow 的设计有其深层原因。

---

## 为什么需要循环？

### 场景 1：创建 Flow 时的自动推进（Nil Input 处理）

当客户端创建新 Flow 时：

```bash
POST /api/v1/authentication_flows
{
    "flow_type": "login",
    "flow_name": "email_password_primary_oob_otp_email",
    "input": {
        "identification": "email",
        "login_id": "user@example.com"
    }
}
```

服务端代码：

```go
// service.go:208-216
func (s *Service) createNewFlow(...) {
    flow = NewFlow(session.FlowID, publicFlow)
    
    // 关键：传入 nil input
    var rawMessage json.RawMessage  // nil!
    
    for shouldAccept {
        shouldAccept = false
        err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
        // ...
    }
}
```

**循环过程：**

```
循环 1: FindInputReactor 找到 IntentLoginFlowStepIdentify
        CanReactTo 返回 InputSchema (可接收 nil input)
        ReactTo 创建 NodeDoUseIdentity
        appendNode, changed = true
        继续循环 (因为没有返回特殊错误)

循环 2: FindInputReactor 检查最后一个 node (NodeDoUseIdentity)
        NodeDoUseIdentity.CanReactTo 返回 ErrEOF (已完成)
        回退检查 Intent: IntentLoginFlowStepIdentify
        Intent.CanReactTo 返回 nil (已完成 IDENTIFY)
        继续检查 IntentLoginFlow (root)
        ...
        直到无法继续
```

**关键点：** 创建 Flow 时传入的是 `nil input`，但 Flow 需要自动推进到第一个需要用户输入的状态。这需要多次循环。

---

### 场景 2：一个 Input 触发连锁反应

假设流程配置如下：

```yaml
- name: simple_auto_flow
  type: LOGIN
  steps:
    - type: IDENTIFY
      oneOf:
        - identification: email
    - type: CREATE_IDENTITY  # 自动步骤
    - type: USER_PROFILE     # 自动步骤
```

当用户提交 `{identification: "email", login_id: "user@example.com"}` 后：

**单次 Accept 循环可能产生多个 Node：**

```
循环 1: ReactTo (IDENTIFY) 
        -> 返回 NodeDoUseIdentity
        -> appendNode
        
循环 2: FindInputReactor 检查 NodeDoUseIdentity (返回 ErrEOF)
        -> 检查 IntentLoginFlowStepIdentify (已完成)
        -> 检查 IntentLoginFlowSteps (下一步是 CREATE_IDENTITY)
        -> IntentLoginFlowSteps.CanReactTo 返回 InputSchema
        -> ReactTo 处理 nil input
        -> 返回 IntentCreateIdentity
        
循环 3: FindInputReactor 检查 IntentCreateIdentity
        -> CanReactTo 返回 nil (可自动处理)
        -> ReactTo 创建 identity
        -> 返回 NodeDoCreateIdentity
        
循环 4: ...继续自动推进到 USER_PROFILE
```

**关键洞察：**
- 一个用户 input 可能触发一连串的**自动步骤**
- 每个自动步骤都需要走完整的 Find -> CanReactTo -> ReactTo -> Append 流程
- 这些都在同一个 HTTP 请求内完成

---

### 场景 3：SubFlow 创建后的立即处理

这是 `primary_oob_otp_email` 的典型场景：

```
用户提交: {authentication: "primary_oob_otp_email"}

循环 1: IntentLoginFlowStepAuthenticate.ReactTo
        -> 返回 NewSubFlow(IntentUseAuthenticatorOOBOTP)
        -> appendNode (NodeTypeSubFlow)
        
循环 2: FindInputReactor 检查最后一个 node (是 SubFlow)
        -> NodeTypeSubFlow -> 进入 SubFlow
        -> FindInputReactor 检查 SubFlow.Intent
        -> IntentUseAuthenticatorOOBOTP.CanReactTo
        -> !authenticatorSelected，返回 InputSchema
        -> ReactTo 返回 NodeDidSelectAuthenticator
        -> appendNode 到 SubFlow
        
循环 3: FindInputReactor 检查 SubFlow 的最后一个 node
        -> NodeDidSelectAuthenticator.CanReactTo 返回 ErrEOF
        -> 回退检查 IntentUseAuthenticatorOOBOTP
        -> authenticatorSelected=true, !claimVerified
        -> CanReactTo 返回 nil (等待 Milestone)
        -> ReactTo 返回 IntentAuthenticationOOB (新 SubFlow)
        
循环 4: FindInputReactor 进入 IntentAuthenticationOOB
        -> CanReactTo 只有一个 channel
        -> ReactTo 创建 NodeAuthenticationOOB
        -> Node 可以接收 input (IsCode/IsResend/IsCheck)
        
循环 5: FindInputReactor 检查 NodeAuthenticationOOB
        -> CanReactTo 返回 InputSchema
        -> 但此时 input 已经用完（是 nil）
        -> ReactTo 返回 ErrIncompatibleInput
        -> 退出循环
```

**结果：** 一个 `{authentication: "primary_oob_otp_email"}` input 触发了 **5 次循环**，完成了：
1. 创建 IntentUseAuthenticatorOOBOTP SubFlow
2. 选择认证器 (NodeDidSelectAuthenticator)
3. 创建 IntentAuthenticationOOB SubFlow
4. 创建 NodeAuthenticationOOB

---

## 为什么需要找到最近的 CanReactTo？

### 核心逻辑代码

```go
// input.go:86-114
func FindInputReactorForFlow(...) (*FindInputReactorResult, error) {
    if len(flows.Nearest.Nodes) > 0 {
        // 关键：优先检查最后一个 node
        lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
        findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
        if err == nil {
            return findInputReactorResult, nil  // 最后一个 node 可以接收输入
        }
        // ErrEOF 则 fallthrough 检查 Intent
        if !errors.Is(err, ErrEOF) {
            return nil, err
        }
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
```

### 设计原因

**1. 最新状态优先原则**

```
Nodes 链: [A, B, C, D]
         ^
         最早加入
                    ^
                    最新加入，代表当前状态
```

- Node A, B, C 都是已完成的步骤
- Node D 是当前活跃状态（刚添加或正在处理）
- 只有 Node D 可能需要接收输入

**2. 栈式执行模型**

Authgear Flow 采用类似**调用栈**的执行模型：

```
Flow 调用栈:

Layer 3: IntentAuthenticationOOB (SubFlow)
         └─ Nodes: [NodeAuthenticationOOB]
                     ↑ 当前活跃

Layer 2: IntentUseAuthenticatorOOBOTP (SubFlow)
         └─ Nodes: [NodeDidSelectAuthenticator]
                     (ErrEOF，已完成)

Layer 1: IntentLoginFlowStepAuthenticate
         └─ Nodes: [...]

Layer 0: IntentLoginFlow (Root)
```

- 执行总是从最深层（最近添加）的节点开始
- 如果深层完成（ErrEOF），才退到上一层

**3. 避免重复处理已完成步骤**

```go
// NodeDoUseIdentity 已实现 Milestone，返回 ErrEOF
func (*NodeDoUseIdentity) CanReactTo(...) (InputSchema, error) {
    // 已完成工作，不再接收输入
    return nil, authflow.ErrEOF
}
```

- 已完成的 Node 返回 `ErrEOF`
- 系统自动回退到父级 Intent
- 不会重复处理已完成步骤

---

## 循环终止条件

```go
// accept.go:122-268
for {
    loopCount += 1
    if loopCount > MAX_LOOP {  // 100 次上限
        panic(fmt.Errorf("number of loops reached limit"))
    }
    
    findInputReactorResult, err = FindInputReactor(...)
    
    input, err = inputFn(findInputReactorResult.InputSchema)
    
    reactToResult, err = findInputReactorResult.InputReactor.ReactTo(...)
    
    // 终止条件 1: ErrIncompatibleInput
    if errors.Is(err, ErrIncompatibleInput) {
        err = nil
        return  // 退出循环
    }
    
    // 终止条件 2: ErrSameNode
    if errors.Is(err, ErrSameNode) {
        changed = true
        return  // 退出循环
    }
    
    // 终止条件 3: ErrReplaceNode
    if errors.Is(err, ErrReplaceNode) {
        changed = true
        return  // 退出循环
    }
    
    // 终止条件 4: 其他错误
    if err != nil {
        return  // 退出循环
    }
    
    // 继续条件: 成功返回 Node，追加后继续
    appendNode(...)
    changed = true
    // 继续循环
}
```

---

## 总结：为什么需要循环

### 错误理解的假设

用户的假设基于**"单步执行"**模型：

```
Input -> 处理 Node A -> 完成
```

### 实际执行的复杂性

Authgear 采用**"自动推进"**模型：

```
Input -> 创建 Node A -> Node A 自动完成 
     -> 创建 Node B -> Node B 自动完成
     -> 创建 Node C -> Node C 等待 input
     -> 没有更多 input -> 返回
```

### 循环的三大价值

| 价值 | 说明 | 示例 |
|------|------|------|
| **自动推进** | 一个 input 触发多步骤自动完成 | nil input 创建 Flow 时推进到第一步 |
| **SubFlow 展开** | 创建 SubFlow 后立即处理其内部 | 选择 OOB OTP 后创建并展开 SubFlow |
| **链式反应** | 步骤间的自动依赖处理 | CREATE_IDENTITY 后自动到 USER_PROFILE |

### 核心设计哲学

**"尽可能的自动化，只在需要时等待用户"**

```go
// 伪代码展示设计意图
for {
    reactor := FindCanReactTo()  // 找能处理的
    
    if reactor.CanAutoProcess() {
        result := reactor.AutoProcess()  // 自动处理
        AppendNode(result)
        continue  // 继续，可能有更多自动步骤
    }
    
    if reactor.NeedUserInput() {
        if hasInput {
            result := reactor.Process(input)
            AppendNode(result)
            continue  // 处理后可能有后续自动步骤
        } else {
            return  // 等待用户输入
        }
    }
}
```

这就是为什么 `primary_oob_otp_email` 选择后，系统能自动创建 SubFlow、自动选择认证器、自动发送邮件——所有这些都在一个 HTTP 请求内通过 Accept 循环自动完成。
