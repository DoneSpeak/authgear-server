# Authgear Server Flow 类型深度调研报告

## 概述

Authgear Server 的 Flow 系统是一个声明式（Declarative）的认证流程框架，采用 Intent-Node 架构实现。它将复杂的认证流程分解为可组合、可重用的步骤，支持多种认证场景。

---

## 一、Flow 类型总览

Authgear 定义了 **6 种核心 Flow 类型**：

| Flow Type | 常量定义 | 主要用途 |
|-----------|----------|----------|
| Signup | `FlowTypeSignup` | 用户注册新账号 |
| Login | `FlowTypeLogin` | 用户登录现有账号 |
| SignupLogin | `FlowTypeSignupLogin` | 统一入口（根据身份自动选择注册或登录） |
| Reauth | `FlowTypeReauth` | 重新认证（用于敏感操作前的身份验证） |
| AccountRecovery | `FlowTypeAccountRecovery` | 账号恢复（密码重置） |
| Promote | `FlowTypePromote` | 匿名用户升级为正式用户 |

### 类型定义位置
```go
// pkg/lib/authenticationflow/flow.go
const (
    FlowTypeSignup          FlowType = "signup"
    FlowTypePromote         FlowType = "promote"
    FlowTypeLogin           FlowType = "login"
    FlowTypeSignupLogin     FlowType = "signup_login"
    FlowTypeReauth          FlowType = "reauth"
    FlowTypeAccountRecovery FlowType = "account_recovery"
)
```

---

## 二、每种 Flow 的详细分析

### 1. Signup Flow（注册流程）

#### 关键类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `IntentSignupFlow` | `intent_signup_flow.go` | 注册流程主入口 |
| `IntentSignupFlowSteps` | `intent_signup_flow_steps.go` | 管理注册步骤序列 |
| `IntentSignupFlowStepIdentify` | `intent_signup_flow_step_identify.go` | 身份识别步骤 |
| `IntentSignupFlowStepCreateAuthenticator` | `intent_signup_flow_step_create_authenticator.go` | 创建认证器步骤 |
| `IntentSignupFlowStepVerify` | `intent_signup_flow_step_verify.go` | 验证步骤 |
| `IntentSignupFlowStepFillInUserProfile` | `intent_signup_flow_step_fill_in_user_profile.go` | 填写用户资料 |
| `NodeDoCreateUser` | `node_do_create_user.go` | 创建用户节点 |
| `NodeDoCreateSession` | `node_do_create_session.go` | 创建会话节点 |

#### 支持的 Step 类型
```go
const (
    AuthenticationFlowSignupFlowStepTypeIdentify            = "identify"
    AuthenticationFlowSignupFlowStepTypeCreateAuthenticator = "create_authenticator"
    AuthenticationFlowSignupFlowStepTypeVerify              = "verify"
    AuthenticationFlowSignupFlowStepTypeFillInUserProfile   = "fill_in_user_profile"
    AuthenticationFlowSignupFlowStepTypeViewRecoveryCode    = "view_recovery_code"
    AuthenticationFlowSignupFlowStepTypePromptCreatePasskey = "prompt_create_passkey"
)
```

#### 解决的核心问题
- **新用户注册**：处理从身份识别到创建用户的完整流程
- **身份验证方式设置**：支持密码、OTP、Passkey 等多种认证器创建
- **用户资料收集**：在注册过程中收集必要的用户信息
- **防重复注册**：通过检查已存在的身份防止重复注册
- **账户关联**：支持第三方登录后的账户关联

#### 典型执行流程
```
IntentSignupFlow
  ├─ NodePreInitialize
  ├─ NodeDoCreateUser (创建用户，生成 UUID)
  ├─ IntentSignupFlowSteps
  │   ├─ IntentSignupFlowStepIdentify (身份识别)
  │   ├─ IntentSignupFlowStepCreateAuthenticator (创建认证器)
 │   ├─ IntentSignupFlowStepVerify (验证邮箱/手机)
  │   └─ ...
  └─ NodeDoCreateSession (创建登录会话)
```

---

### 2. Login Flow（登录流程）

#### 关键类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `IntentLoginFlow` | `intent_login_flow.go` | 登录流程主入口 |
| `IntentLoginFlowSteps` | `intent_login_flow_steps.go` | 管理登录步骤序列 |
| `IntentLoginFlowStepIdentify` | `intent_login_flow_step_identify.go` | 身份识别步骤 |
| `IntentLoginFlowStepAuthenticate` | `intent_login_flow_step_authenticate.go` | 身份验证步骤 |
| `IntentLoginFlowStepCheckAccountStatus` | `intent_login_flow_step_check_account_status.go` | 账户状态检查 |
| `NodeCheckLoginHint` | `node_check_login_hint.go` | 登录提示检查 |
| `NodeDoCreateSession` | `node_do_create_session.go` | 创建会话 |

#### 支持的 Step 类型
```go
const (
    AuthenticationFlowLoginFlowStepTypeIdentify               = "identify"
    AuthenticationFlowLoginFlowStepTypeAuthenticate           = "authenticate"
    AuthenticationFlowLoginFlowStepTypeCheckAccountStatus     = "check_account_status"
    AuthenticationFlowLoginFlowStepTypeTerminateOtherSessions = "terminate_other_sessions"
    AuthenticationFlowLoginFlowStepTypeChangePassword         = "change_password"
    AuthenticationFlowLoginFlowStepTypePromptCreatePasskey    = "prompt_create_passkey"
)
```

#### 解决的核心问题
- **身份识别**：支持邮箱、手机、用户名、OAuth、Passkey、LDAP、ID Token 等方式
- **多因素认证**：支持主认证器（密码、Passkey、OTP）+ 第二因素（TOTP、备用 OTP）
- **账户安全检查**：检查账户状态（是否被禁用、是否需要强制改密）
- **会话管理**：处理多设备登录冲突、创建新会话
- **登录事件追踪**：触发 `user.authenticated` 事件

#### 身份识别选项
```go
// 支持的识别方式
identification: [
    "email",      // 邮箱
    "phone",      // 手机
    "username",   // 用户名
    "oauth",      // 第三方登录
    "passkey",    // Passkey
    "ldap",       // LDAP
    "id_token"    // ID Token (JWT)
]
```

---

### 3. SignupLogin Flow（统一入口）

#### 关键类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `IntentSignupLoginFlow` | `intent_signup_login_flow.go` | 统一入口主流程 |
| `IntentSignupLoginFlowSteps` | `intent_signup_login_flow_steps.go` | 步骤管理 |
| `IntentSignupLoginFlowStepIdentify` | `intent_signup_login_flow_step_identify.go` | 身份识别 |

#### 支持的 Step 类型
```go
const (
    AuthenticationFlowSignupLoginFlowStepTypeIdentify = "identify"
)
```

#### 解决的核心问题
- **自动路由**：根据用户输入的身份自动决定是登录还是注册
- **统一用户体验**：用户无需预先选择登录或注册，输入凭据后系统自动处理
- **身份存在性检查**：检查邮箱/手机号是否已存在，决定后续流程

#### 特殊配置
```go
type AuthenticationFlowSignupLoginFlowOneOf struct {
    Identification model.AuthenticationFlowIdentification
    BotProtection  *AuthenticationFlowBotProtection
    SignupFlow     string  // 指向具体的 signup_flow 名称
    LoginFlow      string  // 指向具体的 login_flow 名称
}
```

---

### 4. Reauth Flow（重新认证）

#### 关键类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `IntentReauthFlow` | `intent_reauth_flow.go` | 重新认证主入口 |
| `IntentReauthFlowSteps` | `intent_reauth_flow_steps.go` | 步骤管理 |
| `IntentReauthFlowStepIdentify` | `intent_reauth_flow_step_identify.go` | 身份识别 |
| `IntentReauthFlowStepAuthenticate` | `intent_reauth_flow_step_authenticate.go` | 身份验证 |
| `NodeDidReauthenticate` | `node_did_reauthenticate.go` | 重新认证完成标记 |

#### 支持的 Step 类型
```go
const (
    AuthenticationFlowReauthFlowStepTypeIdentify     = "identify"
    AuthenticationFlowReauthFlowStepTypeAuthenticate = "authenticate"
)
```

#### 解决的核心问题
- **敏感操作保护**：在执行敏感操作（如修改密码、删除账户）前要求用户重新验证身份
- **步进式认证（Step-up Auth）**：在已有会话基础上要求更强的认证
- **MFA 验证**：用于触发第二因素认证

#### 识别方式限制
```go
// Reauth Flow 仅支持 id_token 识别方式
identification: [
    "id_token"    // 仅限 ID Token
]
```

#### 与 Login Flow 的区别
- **不创建新会话**：仅验证身份，不创建新的 IDP Session
- **不触发登录事件**：不触发 `user.authenticated` 事件
- **用户已知**：基于当前已登录用户进行验证

---

### 5. AccountRecovery Flow（账号恢复）

#### 关键类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `IntentAccountRecoveryFlow` | `intent_account_recovery_flow.go` | 账号恢复主入口 |
| `IntentAccountRecoveryFlowSteps` | `intent_account_recovery_flow_steps.go` | 步骤管理 |
| `IntentAccountRecoveryFlowStepIdentify` | `intent_account_recovery_flow_step_identify.go` | 身份识别 |
| `IntentAccountRecoveryFlowStepSelectDestination` | `intent_account_recovery_flow_step_select_destination.go` | 选择恢复目标 |
| `IntentAccountRecoveryFlowStepVerifyAccountRecoveryCode` | `intent_account_recovery_flow_step_verify_account_recovery_code.go` | 验证恢复码 |
| `IntentAccountRecoveryFlowStepResetPassword` | `intent_account_recovery_flow_step_reset_password.go` | 重置密码 |
| `NodeDoSendAccountRecoveryCode` | `node_do_send_account_recovery_code.go` | 发送恢复码 |
| `NodeDoResetPassword` | `node_do_reset_password.go` | 执行密码重置 |

#### 支持的 Step 类型
```go
const (
    AuthenticationFlowAccountRecoveryFlowTypeIdentify                  = "identify"
    AuthenticationFlowAccountRecoveryFlowTypeSelectDestination         = "select_destination"
    AuthenticationFlowAccountRecoveryFlowTypeVerifyAccountRecoveryCode = "verify_account_recovery_code"
    AuthenticationFlowAccountRecoveryFlowTypeResetPassword             = "reset_password"
)
```

#### 解决的核心问题
- **密码遗忘恢复**：通过邮箱或手机验证用户身份
- **安全验证流程**：发送一次性验证码（OTP）或链接
- **密码重置**：验证通过后允许用户设置新密码
- **多种恢复渠道**：支持 Email、SMS、WhatsApp

#### 恢复渠道配置
```go
type AccountRecoveryChannel struct {
    Channel AccountRecoveryCodeChannel  // "sms", "email", "whatsapp"
    OTPForm AccountRecoveryCodeForm     // "link" 或 "code"
}
```

---

### 6. Promote Flow（匿名用户升级）

#### 关键类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `IntentPromoteFlow` | `intent_promote_flow.go` | 升级流程主入口 |
| `IntentPromoteFlowSteps` | `intent_promote_flow_steps.go` | 步骤管理 |
| `IntentPromoteFlowStepIdentify` | `intent_promote_flow_step_identify.go` | 身份识别 |
| `NodeDoUseAnonymousUser` | `node_do_use_anonymous_user.go` | 使用匿名用户 |

#### 解决的核心问题
- **匿名用户转正**：允许匿名用户升级为正式注册用户
- **保留匿名身份数据**：在升级过程中保留匿名用户的数据
- **身份迁移**：移除匿名身份，添加正式身份

#### 特殊 Effect
```go
// 移除匿名身份
authflow.OnCommitEffect(func(ctx context.Context, deps *authflow.Dependencies) error {
    anonymousIden := i.anonymousIdentity(flows)
    return deps.Identities.Delete(ctx, anonymousIden)
})

// 触发匿名用户升级事件
authflow.OnCommitEffect(func(ctx context.Context, deps *authflow.Dependencies) error {
    return deps.Events.DispatchEventOnCommit(ctx, &nonblocking.UserAnonymousPromotedEventPayload{...})
})
```

---

## 三、核心架构组件

### 1. Intent（意图）

Intent 是 Flow 的基本执行单元，代表一个可执行的操作意图。

```go
// pkg/lib/authenticationflow/intent.go
type Intent interface {
    Kind() string
    CanReactTo(ctx context.Context, deps *Dependencies, flows Flows) (InputSchema, error)
    ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (ReactToResult, error)
}
```

**主要 Intent 类型**：
- **PublicFlow Intent**：Flow 的主入口（如 `IntentSignupFlow`）
- **Step Intent**：具体步骤的执行（如 `IntentLoginFlowStepIdentify`）
- **SubFlow Intent**：嵌套子流程（如 `IntentAccountLinking`）

### 2. Node（节点）

Node 是 Flow 中的状态节点，代表已完成的操作或数据。

```go
// pkg/lib/authenticationflow/node.go
type NodeSimple interface {
    Kind() string
}
```

**主要 Node 类型**：
- **Action Node**：执行具体操作（如 `NodeDoCreateUser`）
- **Data Node**：存储中间数据
- **Milestone Node**：标记重要里程碑

### 3. Milestone（里程碑）

Milestone 是一种特殊的标记接口，用于追踪 Flow 执行过程中的关键状态。

```go
// 核心 Milestone 接口
type MilestoneDoCreateSession interface {
    MilestoneDoCreateSession() (*idpsession.IDPSession, bool)
}

type MilestoneDoCreateUser interface {
    MilestoneDoCreateUser() (userID string, createUser bool)
    MilestoneDoCreateUserUseExisting(userID string)
}

type MilestoneDidAuthenticate interface {
    MilestoneDidAuthenticate() (amr []string)
    MilestoneDidAuthenticateAuthenticator() (*authenticator.Info, bool)
}
```

**Milestone 的作用**：
1. **状态查询**：检查 Flow 中是否已完成特定操作
2. **数据收集**：聚合 Flow 中的身份信息、认证方式等
3. **流程控制**：根据里程碑决定下一步操作

### 4. Effect（副作用）

Effect 代表 Flow 执行过程中产生的副作用，如数据库写入、事件发送等。

```go
// pkg/lib/authenticationflow/effect.go
type Effect interface {
    effect()
}

// 立即执行的 Effect
type RunEffectFunc func(ctx context.Context, deps *Dependencies) error

// 提交时执行的 Effect
type OnCommitEffectFunc func(ctx context.Context, deps *Dependencies) error
```

---

## 四、关键场景与问题解决

### 场景 1：账户关联（Account Linking）

**问题**：用户使用 OAuth 登录，但该邮箱已关联到另一个账户

**解决方案**：
```
IntentAccountLinking
  ├─ 检测冲突身份
  ├─ 提供关联选项
  ├─ 启动 Login Flow 验证身份
  └─ 合并身份到现有账户
```

**关键类**：`IntentAccountLinking`, `NodeUseAccountLinkingIdentification`

### 场景 2：多因素认证（MFA）

**问题**：用户需要通过多种方式验证身份

**解决方案**：
```
IntentLoginFlowStepAuthenticate
  ├─ 主认证器验证（密码/Passkey/OTP）
  └─ 第二因素验证（TOTP/备用码）
```

**认证方式枚举**：
```go
const (
    AuthenticationFlowAuthenticationPrimaryPassword       = "primary_password"
    AuthenticationFlowAuthenticationPrimaryPasskey          = "primary_passkey"
    AuthenticationFlowAuthenticationPrimaryOOBOTPEmail    = "primary_oob_otp_email"
    AuthenticationFlowAuthenticationPrimaryOOBOTPSMS      = "primary_oob_otp_sms"
    AuthenticationFlowAuthenticationSecondaryPassword     = "secondary_password"
    AuthenticationFlowAuthenticationSecondaryTOTP          = "secondary_totp"
    AuthenticationFlowAuthenticationSecondaryOOBOTPEmail  = "secondary_oob_otp_email"
    AuthenticationFlowAuthenticationSecondaryOOBOTPSMS     = "secondary_oob_otp_sms"
    AuthenticationFlowAuthenticationRecoveryCode          = "recovery_code"
    AuthenticationFlowAuthenticationDeviceToken           = "device_token"
)
```

### 场景 3：嵌套步骤（Nested Steps）

**问题**：根据用户选择的不同身份，后续步骤不同

**解决方案**：
```yaml
# 配置示例
steps:
  - type: identify
    one_of:
      - identification: email
        steps:  # 嵌套步骤
          - type: create_authenticator
            one_of:
              - authentication: primary_password
      - identification: oauth
        steps:
          - type: view_recovery_code
```

### 场景 4：AMR（Authentication Method Reference）收集

**问题**：需要追踪用户使用的所有认证方法

**解决方案**：
```go
func CollectAMR(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) ([]string, error) {
    // 遍历 Flow 中所有 MilestoneDidAuthenticate
    // 收集所有使用的认证方法
}
```

---

## 五、配置结构

### Flow 配置根结构
```go
type AuthenticationFlowConfig struct {
    SignupFlows          []*AuthenticationFlowSignupFlow
    PromoteFlows         []*AuthenticationFlowSignupFlow  // Promote 复用 SignupFlow 结构
    LoginFlows           []*AuthenticationFlowLoginFlow
    SignupLoginFlows     []*AuthenticationFlowSignupLoginFlow
    ReauthFlows          []*AuthenticationFlowReauthFlow
    AccountRecoveryFlows []*AuthenticationFlowAccountRecoveryFlow
    RateLimits           *AuthenticationFlowRateLimitsConfig
}
```

### Flow 结构层次
```
Flow (Root)
  └─ Steps []Step
       └─ OneOf []Branch
            └─ Steps []Step  (嵌套)
```

---

## 六、类图与关系

```
┌─────────────────────────────────────────────────────────────────────┐
│                           PublicFlow                                 │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                     │
│  │ IntentSignup│ │ IntentLogin │ │IntentSignup │ ...               │
│  │    Flow     │ │    Flow     │ │  LoginFlow  │                     │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘                     │
└─────────┼───────────────┼───────────────┼─────────────────────────────┘
          │               │               │
          ▼               ▼               ▼
┌────────────────────────────────────────────────────────────┐
│                         Intent                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────┐ │
│  │  *FlowSteps     │  │ *FlowStep*      │  │  *SubFlow*    │ │
│  │ (步骤容器)       │  │ (具体步骤)       │  │ (嵌套流程)     │ │
│  └─────────────────┘  └─────────────────┘  └───────────────┘ │
└────────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────────┐
│                      NodeSimple                              │
│  ┌──────────────┐ ┌──────────────┐ ┌─────────────────────┐  │
│  │NodeDoCreate  │ │NodeDoCreate  │ │NodeDoUseIdentity   │  │
│  │    User      │ │   Session    │ │                     │  │
│  └──────────────┘ └──────────────┘ └─────────────────────┘  │
└────────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────────┐
│                       Milestone                              │
│  ┌──────────────┐ ┌──────────────┐ ┌─────────────────────┐  │
│  │MilestoneDo   │ │MilestoneDid  │ │MilestoneFlowUse     │  │
│  │CreateUser    │ │ Authenticate │ │    Identity         │  │
│  └──────────────┘ └──────────────┘ └─────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

---

## 七、执行流程示例

### Signup Flow 执行时序
```
1. HTTP 层调用 InstantiateFlow(FlowReference{Type: "signup", Name: "default")
2. 创建 IntentSignupFlow 实例
3. 调用 ReactTo() 开始执行
   - Node 0: NodePreInitialize (初始化)
   - Node 1: NodeDoCreateUser (创建用户)
   - SubFlow: IntentSignupFlowSteps
     - Step 0: IntentSignupFlowStepIdentify
       - 用户选择邮箱识别 -> IntentUseIdentityLoginID
       - Node: NodeDoUseIdentity
     - Step 1: IntentSignupFlowStepCreateAuthenticator
       - 创建密码认证器 -> NodeDoCreateAuthenticator
   - Node 2: NodeDoCreateSession (创建会话)
4. Flow 结束，返回 StateToken 和 Session
```

---

## 八、总结

### 核心设计思想
1. **声明式配置**：通过 YAML/JSON 配置定义 Flow，无需修改代码
2. **组合性**：通过 Intent + Node + Milestone 的组合实现复杂流程
3. **可扩展性**：新的认证方式可以通过实现新的 Intent 和 Node 添加
4. **状态追踪**：Milestone 模式提供清晰的执行状态追踪
5. **副作用隔离**：Effect 机制确保副作用可控、可回滚

### 使用场景映射

| 业务场景 | Flow Type | 关键类 |
|----------|-----------|--------|
| 新用户注册 | Signup | IntentSignupFlow, NodeDoCreateUser |
| 用户登录 | Login | IntentLoginFlow, NodeDoCreateSession |
| 自动注册/登录 | SignupLogin | IntentSignupLoginFlow |
| 敏感操作验证 | Reauth | IntentReauthFlow, NodeDidReauthenticate |
| 忘记密码 | AccountRecovery | IntentAccountRecoveryFlow, NodeDoResetPassword |
| 匿名用户转正 | Promote | IntentPromoteFlow, NodeDoUseAnonymousUser |
| 账户合并 | Signup + AccountLinking | IntentAccountLinking |
| MFA 验证 | Login/Reauth | Intent*FlowStepAuthenticate |

---

*文档生成时间：2026-05-18*
*基于 Authgear Server 代码库分析*
