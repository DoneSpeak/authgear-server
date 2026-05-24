# Authgear 嵌套 Steps 实现机制深度解析

本文档详细解析 Authgear Server 中嵌套 `steps` 的实现机制，特别是 `identification: email` 后的 `authenticate` 子步骤是如何被触发和执行的。

---

## 目录

1. [配置示例分析](#配置示例分析)
2. [核心概念：嵌套 Steps](#核心概念嵌套-steps)
3. [执行流程详解](#执行流程详解)
4. [关键代码解析](#关键代码解析)
5. [Milestone 状态跟踪](#milestone-状态跟踪)
6. [总结](#总结)

---

## 配置示例分析

用户提供的配置：

```yaml
login_flows:
- name: default_login_flow
  steps:
  - type: identify
    one_of:
    - identification: oauth          # 分支1: OAuth登录
    - identification: passkey       # 分支2: Passkey登录
    - identification: email         # 分支3: 邮箱识别
      steps:                        # <-- 嵌套步骤开始
      - type: authenticate
        one_of:
        - authentication: primary_passkey
        - authentication: primary_password
          steps:                    # <-- 二级嵌套
          - type: authenticate
            one_of:
            - authentication: secondary_totp
        - authentication: primary_oob_otp_email
          steps:                    # <-- 二级嵌套
          - type: authenticate
            one_of:
            - authentication: secondary_totp
```

**关键问题**：当用户选择 `identification: email` 后，系统如何知道要执行其下的嵌套 `authenticate` 步骤？

---

## 核心概念：嵌套 Steps

### 1. 配置结构定义

```go
// pkg/lib/config/authentication_flow.go:314-337
var _ = Schema.Add("AuthenticationFlowLoginFlowIdentify", `
{
	"type": "object",
	"required": ["identification"],
	"properties": {
		"identification": {
			"type": "string",
			"enum": ["email", "phone", "username", "oauth", "passkey", "ldap", "id_token"]
		},
		"bot_protection": { "$ref": "#/$defs/AuthenticationFlowBotProtection" },
		"steps": {                    // <-- 关键：identification 可以包含嵌套 steps
			"type": "array",
			"items": { "$ref": "#/$defs/AuthenticationFlowLoginFlowStep" }
		}
	}
}
`)
```

同样，`authenticate` 分支也支持嵌套：

```go
// pkg/lib/config/authentication_flow.go:340-367
var _ = Schema.Add("AuthenticationFlowLoginFlowAuthenticate", `
{
	"type": "object",
	"required": ["authentication"],
	"properties": {
		"authentication": { ... },
		"bot_protection": { ... },
		"target_step": { ... },
		"steps": {                    // <-- authenticate 也可以嵌套 steps
			"type": "array",
			"items": { "$ref": "#/$defs/AuthenticationFlowLoginFlowStep" }
		}
	}
}
`)
```

### 2. 运行时结构

```
Flow Root (IntentLoginFlow)
  └─ IntentLoginFlowSteps                    // 处理所有顶层 steps
      └─ Step 0: IntentLoginFlowStepIdentify  // 处理 identify 步骤
          ├─ 用户选择 email
          ├─ 创建 NodeDoUseIdentity
          └─ 触发嵌套 IntentLoginFlowSteps   // 关键：递归处理嵌套 steps
              └─ Step 0: IntentLoginFlowStepAuthenticate  // 处理嵌套的 authenticate
```

---

## 执行流程详解

### 阶段1：初始化 Identify 步骤

当 Flow 创建后，首先进入 `IntentLoginFlowStepIdentify`：

```
[Flow 初始化]
Intent: IntentLoginFlowSteps (6=0)
Nodes: []

[进入 Step 0: Identify]
Intent: IntentLoginFlowStepIdentify
Nodes: []
```

### 阶段2：用户选择 Email

用户提交 `{identification: "email"}` 后：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go:167-184
case len(flows.Nearest.Nodes) == 0:
    // 用户选择了 email
    switch identification {
    case model.AuthenticationFlowIdentificationEmail:
        return authflow.NewSubFlow(&IntentUseIdentityLoginID{
            JSONPointer:    authflow.JSONPointerForOneOf(i.JSONPointer, idx),
            Identification: identification,
        }), nil
    }
```

**执行结果**：创建 `NodeDoUseIdentity`，记录用户身份：

```
Intent: IntentLoginFlowStepIdentify
Nodes: [NodeDoUseIdentity]
      └─ identity: {type: "login_id", login_id: "user@example.com"}
```

### 阶段3：触发嵌套 Steps（核心逻辑）

这是最关键的部分。在 `IntentLoginFlowStepIdentify.CanReactTo` 中：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go:124-152
func (i *IntentLoginFlowStepIdentify) CanReactTo(...) (authflow.InputSchema, error) {
    _, _, identityUsed := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
    _, _, loginHintChecked := authflow.FindMilestoneInCurrentFlow[MilestoneCheckLoginHint](flows)
    _, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)

    switch {
    case len(flows.Nearest.Nodes) == 0:
        // 第一阶段：选择 identification 方法
        return &InputSchemaStepIdentify{...}, nil
    case identityUsed && !loginHintChecked:
        // 第二阶段：检查 login_hint
        return nil, nil
    case identityUsed && !nestedStepsHandled:   // <-- 关键条件
        // 第三阶段：处理嵌套 steps
        return nil, nil
    default:
        return nil, authflow.ErrEOF
    }
}
```

当 `identityUsed=true` 且 `nestedStepsHandled=false` 时，进入嵌套步骤处理。

对应的 `ReactTo`：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go:218-223
case identityUsed && !nestedStepsHandled:
    identification := i.identificationMethod(flows)
    return authflow.NewSubFlow(&IntentLoginFlowSteps{
        FlowReference: i.FlowReference,
        JSONPointer:   i.jsonPointer(step, identification),  // <-- 指向 /steps/0/oneOf/2 (email)
    }), nil
```

**关键**：`i.jsonPointer()` 返回的是 `email` 分支的 JSONPointer，该分支包含嵌套的 `steps`。

### 阶段4：执行嵌套的 Authenticate

`IntentLoginFlowSteps` 被创建后，它会读取配置中的嵌套 steps：

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go:34-64
func (i *IntentLoginFlowSteps) CanReactTo(...) (authflow.InputSchema, error) {
    current, err := i.currentFlowObject(deps, flows, i)
    steps := current.GetSteps()  // <-- 获取嵌套 steps 配置

    if i.NextStepIndex < len(steps) {
        return nil, nil  // 还有更多步骤要执行
    }
    return nil, authflow.ErrEOF  // 所有步骤完成
}

func (i *IntentLoginFlowSteps) ReactTo(...) (authflow.ReactToResult, error) {
    steps := current.GetSteps()
    step := steps[i.NextStepIndex].(*config.AuthenticationFlowLoginFlowStep)

    switch step.Type {
    case config.AuthenticationFlowLoginFlowStepTypeIdentify:
        // ...
    case config.AuthenticationFlowLoginFlowStepTypeAuthenticate:
        // 创建 IntentLoginFlowStepAuthenticate 来处理嵌套的 authenticate
        result = authflow.NewSubFlow(stepAuthenticate)
    }

    i.NextStepIndex = i.NextStepIndex + 1  // 移动到下一步
    return result, nil
}
```

### 完整执行流程图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           用户选择 email 后                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    IntentLoginFlowStepIdentify                            │
│  CanReactTo: identityUsed=true, nestedStepsHandled=false                  │
│  ReactTo: 返回 NewSubFlow(IntentLoginFlowSteps)                           │
│       JSONPointer: "/steps/0/oneOf/2"  (指向 email 分支)                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         IntentLoginFlowSteps                              │
│  currentFlowObject() 读取 email 分支的 steps 配置                          │
│  发现有一个 authenticate 子步骤                                           │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  IntentLoginFlowStepAuthenticate                          │
│  处理嵌套的 authenticate 步骤，提供选项：                                  │
│  - primary_passkey                                                      │
│  - primary_password                                                     │
│  - primary_oob_otp_email                                                │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                   用户选择 primary_password 后                           │
│              发现还有嵌套的 secondary_totp 步骤                            │
│  IntentLoginFlowStepAuthenticate 再次创建 IntentLoginFlowSteps           │
│       JSONPointer: "/steps/0/oneOf/2/steps/0/oneOf/1/steps/0"            │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 关键代码解析

### 1. 获取当前 Flow 对象（含嵌套配置）

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go:132-142
func (i *IntentLoginFlowSteps) currentFlowObject(deps *authflow.Dependencies, flows authflow.Flows, origin authflow.NodeOrIntent) (config.AuthenticationFlowStepsObject, error) {
    rootObject, err := findNearestFlowObjectInFlow(deps, flows, origin)
    if err != nil {
        return nil, err
    }
    // 使用 JSONPointer 从根对象导航到当前配置位置
    current, err := authflow.FlowObject(rootObject, i.JSONPointer)
    if err != nil {
        return nil, err
    }
    return current.(config.AuthenticationFlowStepsObject), nil
}
```

### 2. JSONPointer 导航

JSONPointer 是嵌套 steps 定位的关键：

```
/steps/0                    -> 第1个顶层步骤 (identify)
/steps/0/oneOf/2            -> identify 的第3个选项 (email)
/steps/0/oneOf/2/steps/0    -> email 的第1个嵌套步骤 (authenticate)
/steps/0/oneOf/2/steps/0/oneOf/1  -> authenticate 的第2个选项 (primary_password)
/steps/0/oneOf/2/steps/0/oneOf/1/steps/0  -> primary_password 的嵌套步骤 (secondary_totp)
```

### 3. 嵌套 Steps 递归处理

```go
// pkg/lib/authenticationflow/declarative/intent_login_flow_step_authenticate.go:319-324
case !nestedStepsHandled:
    authentication := i.authenticationMethod(flows)
    return authflow.NewSubFlow(&IntentLoginFlowSteps{
        FlowReference: i.FlowReference,
        JSONPointer:   i.jsonPointer(step, authentication),  // 指向 authenticate 分支下的 steps
    }), nil
```

---

## Milestone 状态跟踪

嵌套 steps 的处理依赖 `MilestoneNestedSteps` 接口来标记状态：

```go
// pkg/lib/authenticationflow/declarative/milestone.go:71-74
type MilestoneNestedSteps interface {
    authflow.Milestone
    MilestoneNestedSteps()  // 空方法，仅作标记
}

// pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go:25,32
var _ MilestoneNestedSteps = &IntentLoginFlowSteps{}
func (*IntentLoginFlowSteps) MilestoneNestedSteps() {}
```

**状态流转**：

```
初始状态:
  identityUsed = false
  nestedStepsHandled = false

用户提交 email 后:
  identityUsed = true (由 MilestoneFlowUseIdentity 标记)
  nestedStepsHandled = false

创建 IntentLoginFlowSteps 后:
  identityUsed = true
  nestedStepsHandled = true (由 MilestoneNestedSteps 标记)
  
嵌套步骤执行中:
  嵌套 IntentLoginFlowStepAuthenticate 继续处理...
```

---

## 总结

### 核心设计模式


| 机制                          | 作用               | 关键代码                                               |
| --------------------------- | ---------------- | -------------------------------------------------- |
| **JSONPointer 导航**          | 定位嵌套配置位置         | `authflow.FlowObject(rootObject, i.JSONPointer)`   |
| **MilestoneNestedSteps**    | 标记嵌套 steps 是否已处理 | `FindMilestoneInCurrentFlow[MilestoneNestedSteps]` |
| **IntentLoginFlowSteps 递归** | 执行嵌套 steps       | `NewSubFlow(&IntentLoginFlowSteps{...})`           |
| **GetSteps()**              | 获取当前配置下的步骤列表     | `current.GetSteps()`                               |


### 执行顺序总结

1. **Identify 步骤完成** → 创建 `NodeDoUseIdentity`
2. **检查 nestedStepsHandled** → `false` 时创建子 `IntentLoginFlowSteps`
3. **子 IntentLoginFlowSteps** → 读取 `email` 分支的 `steps` 配置
4. **执行嵌套 authenticate** → 创建 `IntentLoginFlowStepAuthenticate`
5. **用户选择 authentication** → 如有嵌套 steps，重复步骤 2-4
6. **所有嵌套完成** → 返回 `ErrEOF`，流程继续到下一个顶层步骤

### 配置与代码的映射

```yaml
# 配置
- identification: email
  steps:
  - type: authenticate
    one_of:
    - authentication: primary_password
      steps:
      - type: authenticate
```

```go
// 代码执行
IntentLoginFlowStepIdentify
    └─ IntentLoginFlowSteps (处理 email.steps)
        └─ IntentLoginFlowStepAuthenticate (处理 primary_password)
            └─ IntentLoginFlowSteps (处理 primary_password.steps)
                └─ IntentLoginFlowStepAuthenticate (处理 secondary_totp)
```

这种设计使得 Authgear 的认证流程可以**无限嵌套**，支持复杂的认证场景（如：邮箱 → 密码 → TOTP → 修改密码提示等）。