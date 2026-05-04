# ReactTo 如何决定下一个 Step

## 核心机制：ReactTo 是"状态机"，根据当前状态决定下一个节点

ReactTo 方法的逻辑遵循固定的模式：**根据当前状态 → 决定下一个节点**

---

## 示例1: IntentLoginFlowSteps 决定下一个 Step

**配置：**
```yaml
steps:
  - type: identify          ← Step 0
  - type: authenticate      ← Step 1
  - type: authenticate      ← Step 2
```

**代码逻辑：**

```go
// intent_login_flow_steps.go:53-130
func (i *IntentLoginFlowSteps) ReactTo(...) (authflow.ReactToResult, error) {
    // 1. 从配置获取 steps 列表
    steps := current.GetSteps()

    // 2. 根据 NextStepIndex 获取当前 step
    step := steps[i.NextStepIndex]

    // 3. 根据 step.Type 创建对应的 Intent（这就是下一个 step！）
    switch step.Type {
    case config.AuthenticationFlowLoginFlowStepTypeIdentify:
        // 返回 IntentLoginFlowStepIdentify（SubFlow 节点）
        result = authflow.NewSubFlow(&IntentLoginFlowStepIdentify{...})

    case config.AuthenticationFlowLoginFlowStepTypeAuthenticate:
        // 返回 IntentLoginFlowStepAuthenticate（SubFlow 节点）
        result = authflow.NewSubFlow(&IntentLoginFlowStepAuthenticate{...})
    }

    // 4. 推进到下一个 step
    i.NextStepIndex = i.NextStepIndex + 1
    return result, nil
}
```

**关键点：**
- `NextStepIndex` 是状态，记录当前执行到第几步
- 根据 `step.Type` 从配置映射到对应的 Intent
- 返回 `NewSubFlow(intent)` 创建子 Flow 节点

---

## 示例2: IntentLoginFlowStepIdentify 决定下一个节点

**输入 email 后的执行流程：**

```go
// intent_login_flow_step_identify.go:155-227
func (i *IntentLoginFlowStepIdentify) ReactTo(...) (authflow.ReactToResult, error) {
    // 检查当前状态
    _, _, identityUsed := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
    _, _, loginHintChecked := authflow.FindMilestoneInCurrentFlow[MilestoneCheckLoginHint](flows)
    _, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)

    switch {
    // 状态1: Nodes 为空，需要选择 identification 方法
    case len(flows.Nearest.Nodes) == 0:
        // 用户输入了 { "identification": "email" }
        identification := inputTakeIdentificationMethod.GetIdentificationMethod()

        // 根据 identification 返回对应的 Intent
        switch identification {
        case model.AuthenticationFlowIdentificationEmail:
            // 返回 IntentUseIdentityLoginID（处理 email/phone/username）
            return authflow.NewSubFlow(&IntentUseIdentityLoginID{
                JSONPointer:    authflow.JSONPointerForOneOf(i.JSONPointer, idx),
                Identification: identification,
            }), nil

        case model.AuthenticationFlowIdentificationOAuth:
            return authflow.NewSubFlow(&IntentOAuth{...}), nil

        case model.AuthenticationFlowIdentificationPasskey:
            return authflow.NewSubFlow(&IntentUseIdentityPasskey{...}), nil
        }

    // 状态2: 已识别用户，但未检查 login_hint
    case identityUsed && !loginHintChecked:
        // 返回 NodeCheckLoginHint（Simple 节点）
        return authflow.NewNodeSimple(&NodeCheckLoginHint{...}), nil

    // 状态3: 已识别用户且已检查 login_hint，需要继续后续 steps
    case identityUsed && !nestedStepsHandled:
        // 返回 IntentLoginFlowSteps（回到 step 循环）
        return authflow.NewSubFlow(&IntentLoginFlowSteps{
            FlowReference: i.FlowReference,
            JSONPointer:   i.jsonPointer(step, identification),
        }), nil
    }
}
```

**关键点：**
- 用 Milestone 标记状态（identityUsed, loginHintChecked 等）
- 根据状态组合决定下一个节点
- 不是直接替换自己，而是创建并返回新的节点

---

## 示例3: IntentLoginFlowStepAuthenticate 决定认证方式

```go
// intent_login_flow_step_authenticate.go:203-328
func (i *IntentLoginFlowStepAuthenticate) ReactTo(...) (authflow.ReactToResult, error) {
    // 检查状态
    _, _, deviceTokenInspected := authflow.FindMilestoneInCurrentFlow[MilestoneDeviceTokenInspected](flows)
    _, _, authenticationMethodSelected := authflow.FindMilestoneInCurrentFlow[MilestoneFlowSelectAuthenticationMethod](flows)
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneFlowAuthenticate](flows)

    switch {
    // 状态1: 未检查 device token
    case i.DeviceTokenEnabled && !deviceTokenInspected:
        return authflow.NewSubFlow(&IntentInspectDeviceToken{...}), nil

    // 状态2: 未选择认证方式
    case !authenticationMethodSelected:
        // 用户输入 { "authentication": "primary_password" }
        authentication := inputTakeAuthenticationMethod.GetAuthenticationMethod()

        // 根据认证方式创建对应的 Intent
        switch authentication {
        case model.AuthenticationFlowAuthenticationPrimaryPassword:
            return authflow.NewSubFlow(&IntentUseAuthenticatorPassword{...}), nil

        case model.AuthenticationFlowAuthenticationSecondaryTOTP:
            return authflow.NewSubFlow(&IntentUseAuthenticatorTOTP{...}), nil

        case model.AuthenticationFlowAuthenticationPrimaryOOBOTPEmail:
            return authflow.NewSubFlow(&IntentUseAuthenticatorOOBOTP{...}), nil
        }

    // 状态3: 已选择但未认证（实际认证过程）
    case !authenticated:
        // 等待用户输入密码/TOTP等
        // 子 Flow 的 ReactTo 处理

    // 状态4: 已认证，继续后续 steps
    case !nestedStepsHandled:
        return authflow.NewSubFlow(&IntentLoginFlowSteps{...}), nil
    }
}
```

**关键点：**
- 根据配置生成 `Options` 列表（可用认证方式）
- 根据用户选择的 `authentication` 创建对应 Intent
- 认证完成后返回 `IntentLoginFlowSteps` 继续后续步骤

---

## 状态判断的三种方式

### 方式1: 检查 Nodes 数量
```go
case len(flows.Nearest.Nodes) == 0:
    // 还没有任何节点，需要创建第一个节点
```

### 方式2: 检查 Milestone
```go
_, _, identityUsed := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
case identityUsed && !loginHintChecked:
    // 已使用身份，但未检查 login_hint
```

### 方式3: Intent 内部状态
```go
if i.NextStepIndex < len(steps) {
    // 还有后续步骤
}
```

---

## 详细案例：配置到 Node 的完整映射

以这个配置为例：

```yaml
- name: default_login_flow
  type: login
  steps:
    - type: identify
      one_of:
        - identification: email
    - type: authenticate
      one_of:
        - authentication: primary_password
```

用户选择 `email` 后，系统如何知道下一步是输入密码？

### 阶段1: 配置解析 → Options

```go
// intent_login_flow_step_authenticate.go:93-111
func NewIntentLoginFlowStepAuthenticate(...) (*IntentLoginFlowStepAuthenticate, error) {
    // 1. 从配置获取当前 step
    current, err := i.currentFlowObject(deps, flows, originNode)
    step := i.step(current)  // step.Type = "authenticate"

    // 2. 根据配置生成可用选项列表
    options, deviceTokenEnabled, err := getAuthenticationOptionsForLogin(ctx, deps, flows, i.UserID, step)

    // options 包含用户可用的认证方式
    // 如: [primary_password, secondary_totp, ...]
    i.Options = options
}
```

**配置 → Options 的映射：**

```
配置 (YAML)                              Options (代码)
─────────────────────────────────────────────────────────────────
steps:                                   
  - type: authenticate                     ↓ 解析
    one_of:                                
      - authentication: primary_password   →  AuthenticateOption{
      - authentication: secondary_totp         Authentication: "primary_password",
                                               ...
                                           }
                                           →  AuthenticateOption{
                                               Authentication: "secondary_totp",
                                               ...
                                           }
```

### 阶段2: Options → InputSchema

```go
// intent_login_flow_step_authenticate.go:176-185
// CanReactTo 返回 InputSchema，包含可用选项
return &InputSchemaLoginFlowStepAuthenticate{
    FlowRootObject:     flowRootObject,
    JSONPointer:        i.JSONPointer,      // "/steps/1"
    Options:            i.Options,          // [primary_password, ...]
    DeviceTokenEnabled: i.DeviceTokenEnabled,
}, nil
```

**用户看到的 UI：**
```json
{
  "action": {
    "type": "authenticate",
    "data": {
      "options": [
        { "authentication": "primary_password", "label": "Password" },
        { "authentication": "secondary_totp", "label": "Authenticator" }
      ]
    }
  }
}
```

### 阶段3: 用户选择 → Intent 创建

用户提交：`{ "authentication": "primary_password" }`

```go
// intent_login_flow_step_authenticate.go:256-273
var inputTakeAuthenticationMethod inputTakeAuthenticationMethod
if authflow.AsInput(input, &inputTakeAuthenticationMethod) {
    // 1. 获取用户选择
    authentication := inputTakeAuthenticationMethod.GetAuthenticationMethod()
    // authentication = "primary_password"

    // 2. 在配置中查找索引
    idx, err := i.getIndex(step, authentication)  // idx = 0

    // 3. 根据 authentication 创建对应的 Intent
    switch authentication {
    case model.AuthenticationFlowAuthenticationPrimaryPassword:
        // 创建 IntentUseAuthenticatorPassword
        return authflow.NewSubFlow(&IntentUseAuthenticatorPassword{
            JSONPointer:    authflow.JSONPointerForOneOf(i.JSONPointer, idx),  // "/steps/1/one_of/0"
            UserID:         i.UserID,
            Authentication: authentication,  // "primary_password"
        }), nil
    }
}
```

**关键映射表：**

| 配置值 | authentication 常量 | Intent 类型 |
|--------|---------------------|-------------|
| `primary_password` | `AuthenticationFlowAuthenticationPrimaryPassword` | `IntentUseAuthenticatorPassword` |
| `secondary_totp` | `AuthenticationFlowAuthenticationSecondaryTOTP` | `IntentUseAuthenticatorTOTP` |
| `primary_passkey` | `AuthenticationFlowAuthenticationPrimaryPasskey` | `IntentUseAuthenticatorPasskey` |
| `primary_oob_otp_email` | `AuthenticationFlowAuthenticationPrimaryOOBOTPEmail` | `IntentUseAuthenticatorOOBOTP` |
| `recovery_code` | `AuthenticationFlowAuthenticationRecoveryCode` | `IntentUseRecoveryCode` |

### 阶段4: Intent → Node → appendNode

```go
// node.go:47-55
func NewSubFlow(intent Intent) *Node {
    return &Node{
        Type: NodeTypeSubFlow,           // 这是一个子 Flow 节点
        SubFlow: &Flow{
            Intent: intent,               // IntentUseAuthenticatorPassword
        },
    }
}

// accept.go:263-267
err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
// appendNode: flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)
```

**此时 Flow 结构：**

```
Flow (IntentLoginFlowStepAuthenticate)
  ├── Nodes: [
  │     Node{
  │       Type: NodeTypeSubFlow,
  │       SubFlow: Flow{
  │         Intent: IntentUseAuthenticatorPassword{  // ← 新追加
  │           UserID: "user123",
  │           Authentication: "primary_password",
  │           JSONPointer: "/steps/1/one_of/0"
  │         },
  │         Nodes: []
  │       }
  │     }
  │  ]
```

### 阶段5: 进入子 Flow 处理密码

FindInputReactor 发现子 Flow 的 Nodes 为空，所以检查其 Intent：

```go
// intent_use_authenticator_password.go:48-68
func (n *IntentUseAuthenticatorPassword) CanReactTo(...) (authflow.InputSchema, error) {
    // 检查是否已认证
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
    if authenticated {
        return nil, authflow.ErrEOF  // 已认证，不需要输入
    }

    // 返回密码输入 Schema
    return &InputSchemaTakePassword{
        JSONPointer: n.JSONPointer,  // "/steps/1/one_of/0"
    }, nil
}
```

**用户收到响应：**
```json
{
  "action": {
    "type": "password",
    "data": {
      "password_policy": { ... }
    }
  }
}
```

用户提交：`{ "password": "secret123" }`

```go
// intent_use_authenticator_password.go:70+
func (i *IntentUseAuthenticatorPassword) ReactTo(...) (authflow.ReactToResult, error) {
    var inputTakePassword inputTakePassword
    if authflow.AsInput(input, &inputTakePassword) {
        password := inputTakePassword.GetPassword()  // "secret123"

        // 验证密码
        info, verifyResult, err := deps.Authenticators.VerifyOneWithSpec(...)

        // 返回验证成功节点
        return NewNodeDoUseAuthenticatorPasswordReactToResult(info, ...), nil
    }
}
```

**最终 Flow 结构：**

```
Flow (IntentLoginFlowStepAuthenticate)
  ├── Nodes: [
  │     Node{
  │       Type: NodeTypeSubFlow,
  │       SubFlow: Flow{
  │         Intent: IntentUseAuthenticatorPassword,
  │         Nodes: [
  │           Node{  // ← 密码验证成功节点
  │             Type: NodeTypeSimple,
  │             Simple: NodeDoUseAuthenticatorPassword{
  │               Authenticator: {...},
  │               MilestoneDidAuthenticate: true
  │             }
  │           }
  │         ]
  │       }
  │     }
  │  ]
```

---

## 配置到 Node 的映射关系总结

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           配置 (authentication_flow.yaml)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  step:                                                                      │
│    type: authenticate                                                        │
│    one_of:                                                                  │
│      - authentication: primary_password     ← 配置字符串                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 解析
┌─────────────────────────────────────────────────────────────────────────────┐
│                         config.AuthenticationFlowLoginFlowStep               │
├─────────────────────────────────────────────────────────────────────────────┤
│  step.Type = "authenticate"                                                  │
│  step.OneOf[0].Authentication = "primary_password"                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 生成选项
┌─────────────────────────────────────────────────────────────────────────────┐
│                      AuthenticateOption 列表 (i.Options)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  Options = [                                                                 │
│    { Authentication: "primary_password", ... },                          │
│    { Authentication: "secondary_totp", ... }                                  │
│  ]                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 用户选择
┌─────────────────────────────────────────────────────────────────────────────┐
│                    inputTakeAuthenticationMethod.GetAuthenticationMethod()   │
├─────────────────────────────────────────────────────────────────────────────┤
│  authentication = "primary_password"                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ switch-case 映射
┌─────────────────────────────────────────────────────────────────────────────┐
│                        IntentUseAuthenticatorPassword                         │
├─────────────────────────────────────────────────────────────────────────────┤
│  type IntentUseAuthenticatorPassword struct {                                │
│    JSONPointer:    "/steps/1/one_of/0"                                       │
│    UserID:         "user123"                                                 │
│    Authentication: "primary_password"  ← 保存用户选择                          │
│  }                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ NewSubFlow
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Node (NodeTypeSubFlow)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  Node{                                                                       │
│    Type: NodeTypeSubFlow,                                                    │
│    SubFlow: &Flow{                                                           │
│      Intent: IntentUseAuthenticatorPassword                                  │
│    }                                                                         │
│  }                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ appendNode
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Flow.Nodes = append(Flow.Nodes, node)                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 总结：ReactTo 决策流程

```
ReactTo 被调用
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. 判断当前状态                                              │
│    - Nodes 数量                                              │
│    - Milestone 完成情况                                       │
│    - Intent 内部状态（如 NextStepIndex）                       │
└─────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. 根据状态决定下一个节点                                     │
│                                                              │
│    如果是 step 切换：                                         │
│    - 从配置读取 step.Type                                     │
│    - 创建对应 Intent（IntentLoginFlowStepIdentify 等）       │
│    - 返回 authflow.NewSubFlow(intent)                         │
│                                                              │
│    如果是具体动作：                                           │
│    - 根据用户输入（email/password）                            │
│    - 创建对应处理节点（IntentUseIdentityLoginID 等）          │
│    - 返回 authflow.NewSubFlow(intent)                         │
│                                                              │
│    如果是简单状态变更：                                       │
│    - 创建 Simple 节点（NodeCheckLoginHint 等）                 │
│    - 返回 authflow.NewNodeSimple(node)                       │
└─────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. 更新自身状态（如有）                                       │
│    - NextStepIndex++                                          │
│    - 其他内部字段更新                                          │
└─────────────────────────────────────────────────────────────┘
     │
     ▼
返回 authflow.ReactToResult
```

---

## 关键规律

| 场景 | ReactTo 返回 | 说明 |
|------|-------------|------|
| 进入子流程 | `NewSubFlow(intent)` | 创建子 Flow，递归处理 |
| 创建简单节点 | `NewNodeSimple(node)` | 添加 Milestone 标记 |
| 完成当前节点 | `ErrEOF` | 告诉 FindInputReactor 找父节点 |
| 输入不匹配 | `ErrIncompatibleInput` | 告诉框架这个输入不处理 |
| 需要重试 | `ErrPauseAndRetryAccept` | 执行延迟函数后重试 |

**最重要的理解：**
- ReactTo 不修改自己，而是创建并返回"下一个节点"
- 通过 Milestone 在节点间传递状态
- Flow 的 Nodes 数组记录完整的执行路径（历史）
