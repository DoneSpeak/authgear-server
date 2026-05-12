# Authgear Intent 设计深度分析

## 1. Intent 公共特性与接口设计

### 1.1 核心接口定义

Intent是Authgear认证流程的核心抽象，定义在 `pkg/lib/authenticationflow/intent.go`：

```go
type Intent interface {
    Kinder
    InputReactor
}
```

Intent由两个更基础的接口组合而成：

**Kinder接口** (`pkg/lib/authenticationflow/marshal.go`):
```go
type Kinder interface {
    Kind() string  // 返回Intent的类型标识，用于序列化和反序列化
}
```

**InputReactor接口** (`pkg/lib/authenticationflow/input.go`):
```go
type InputReactor interface {
    // CanReactTo 判断当前Intent是否能响应输入
    // 返回nil表示可以接受nil输入
    // 返回InputSchema表示期望的输入格式
    // 返回ErrEOF表示流程已完成
    CanReactTo(ctx context.Context, deps *Dependencies, flows Flows) (InputSchema, error)
    
    // ReactTo 处理用户输入，返回下一个节点或子流程
    ReactTo(ctx context.Context, deps *Dependencies, flows Flows, input Input) (ReactToResult, error)
}
```

### 1.2 Intent的公共数据字段

所有Intent都包含以下公共字段（以实际结构体定义为准）：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `JSONPointer` | `jsonpointer.T` | 指向配置文件中当前步骤的JSON指针路径 |
| `FlowReference` | `authflow.FlowReference` | 流程类型和名称引用 |
| `UserID` | `string` | 关联的用户ID |

### 1.3 注册机制

Intent通过 `init()` 函数自注册到全局注册表：

```go
func init() {
    authflow.RegisterIntent(&IntentLoginFlow{})
}
```

注册机制 (`marshal.go`)：
- 使用反射获取Intent的Go类型
- 存储在 `intentRegistry` 映射中 (kind -> factory)
- 支持通过 `InstantiateIntent(kind string)` 动态创建实例

---

## 2. Flow Intent vs SubFlow Intent

### 2.1 流程层级结构

```
Flow (根Intent - PublicFlow)
├── Node 1
├── Node 2
├── SubFlow (子Intent)
│   ├── Node 1
│   ├── Node 2
│   └── SubFlow (嵌套Intent)
└── Node 3
```

### 2.2 PublicFlow接口

顶级Flow Intent实现 `PublicFlow` 接口 (`pkg/lib/authenticationflow/flow.go`):

```go
type PublicFlow interface {
    Intent
    FlowType() FlowType                                    // 流程类型: signup/login/reauth等
    FlowInit(r FlowReference, startFrom jsonpointer.T)     // 初始化流程
    FlowFlowReference() FlowReference                      // 获取流程引用
    FlowRootObject(deps *Dependencies) (config.AuthenticationFlowObject, error)  // 获取配置根对象
}
```

### 2.3 六大PublicFlow类型

| FlowType | 说明 | 主要Intent |
|----------|------|-----------|
| `signup` | 用户注册 | `IntentSignupFlow` |
| `login` | 用户登录 | `IntentLoginFlow` |
| `promote` | 匿名用户升级 | `IntentPromoteFlow` |
| `signup_login` | 注册/登录二合一 | `IntentSignupLoginFlow` |
| `reauth` | 重新认证 | `IntentReauthFlow` |
| `account_recovery` | 账户恢复 | `IntentAccountRecoveryFlow` |

### 2.4 SubFlow Intent的特点

SubFlow Intent用于处理具体的步骤或分支：

- **不实现PublicFlow接口**
- 专注于单一职责（如身份识别、认证、创建资源等）
- 可以被嵌套，形成树状结构
- 通过 `authflow.NewSubFlow(intent)` 创建

---

## 3. 不同职能Node的独特信息

### 3.1 Node类型定义

```go
type NodeType string

const (
    NodeTypeSimple  NodeType = "SIMPLE"   // 简单节点
    NodeTypeSubFlow NodeType = "SUB_FLOW" // 子流程节点
)

type Node struct {
    Type    NodeType   `json:"type"`
    Simple  NodeSimple `json:"simple,omitempty"`  // 简单节点数据
    SubFlow *Flow      `json:"flow,omitempty"`    // 子流程数据
}
```

### 3.2 NodeSimple的种类与职能

NodeSimple代表流程中已完成的原子操作，主要类型包括：

**身份相关Node：**
- `NodeDoUseIdentity` - 使用身份（通过MilestoneDoUseIdentity接口暴露已使用的identity.Info）
- `NodeDoCreateIdentity` - 创建身份
- `NodeOAuth` - OAuth授权处理

**认证相关Node：**
- `NodeDoUseAuthenticatorPassword` - 使用密码认证器
- `NodeDoUseAuthenticatorTOTP` - 使用TOTP认证器
- `NodeDoUseAuthenticatorOOBOTP` - 使用OOB OTP认证器
- `NodeDoUseAuthenticatorPasskey` - 使用Passkey认证器

**会话相关Node：**
- `NodeDoCreateSession` - 创建会话（实现MilestoneDoCreateSession）
- `NodeDoCreateUser` - 创建用户

**账户恢复Node：**
- `NodeDoUseAccountRecoveryIdentity` - 使用账户恢复身份
- `NodeDoMarkClaimVerified` - 标记声明已验证

### 3.3 Node与Intent的关系

```
Intent (意图/计划) -> 产生 -> Node (已完成的动作记录)

例如:
IntentUseIdentityLoginID.ReactTo() 
    -> 创建 NodeDoUseIdentity (记录已完成的身份识别)
```

---

## 4. Milestone机制

### 4.1 Milestone设计模式

Milestone是用于查询流程状态的标记接口，实现解耦的状态查询：

```go
// Milestone基础接口
type Milestone interface {
    Milestone()  // 标记接口
}

// 具体Milestone示例
type MilestoneDoCreateSession interface {
    authflow.Milestone
    MilestoneDoCreateSession() (*idpsession.IDPSession, bool)
}

type MilestoneDoUseIdentity interface {
    authflow.Milestone
    MilestoneDoUseIdentity() *identity.Info
    MilestoneDoUseIdentityIdentification() model.Identification
}
```

### 4.2 Milestone查询机制

```go
// 在当前流程中查找Milestone
m, mFlows, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoCreateSession](flows)
if ok {
    session, found := m.MilestoneDoCreateSession()
}
```

### 4.3 核心Milestone类型

| Milestone | 作用 | 实现者 |
|-----------|------|--------|
| MilestoneDoCreateSession | 标记会话创建 | IntentLoginFlow等 |
| MilestoneDoUseIdentity | 标记身份使用 | NodeDoUseIdentity等 |
| MilestoneDoCreateIdentity | 标记身份创建 | NodeDoCreateIdentity等 |
| MilestoneDidAuthenticate | 标记认证完成 | NodeDoUseAuthenticator*等 |
| MilestoneDoCreateUser | 标记用户创建 | NodeDoCreateUser |
| MilestoneNestedSteps | 标记嵌套步骤 | Intent*Steps等 |

---

## 5. 持久化机制

### 5.1 Redis存储结构

存储实现位于 `pkg/lib/authenticationflow/store.go`：

**键命名规范：**
```
app:{appID}:authenticationflow_flow:{flowID}      -> 流程存在标记
app:{appID}:authenticationflow_state:{stateToken} -> 完整流程状态(JSON)
app:{appID}:authenticationflow_session:{flowID}   -> 会话数据
```

### 5.2 序列化格式

**JSON序列化** (`marshal.go`)：

```go
// Flow序列化结构
type flowJSON struct {
    FlowID     string     `json:"flow_id,omitempty"`
    StateToken string     `json:"state_token,omitempty"`
    Intent     intentJSON `json:"intent"`
    Nodes      []Node     `json:"nodes,omitempty"`
}

// Intent使用Kind字段进行类型识别
type intentJSON struct {
    Kind string          `json:"kind"`      // 如 "IntentLoginFlow"
    Data json.RawMessage `json:"data"`      // 具体Intent的JSON数据
}

// Node序列化
type nodeJSON struct {
    Type    NodeType        `json:"type"`
    Simple  *nodeSimpleJSON `json:"simple,omitempty"`
    SubFlow *Flow           `json:"flow,omitempty"`
}
```

### 5.3 存储操作

**创建流程：**
```go
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    bytes, err := json.Marshal(flow)
    // 设置两个key:
    // 1. flowKey (标记流程存在，值只是key本身)
    // 2. stateKey (存储完整JSON，以stateToken为key)
}
```

**获取流程：**
```go
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    // 1. 通过stateToken获取JSON
    // 2. 反序列化
    // 3. 检查flowKey是否存在（防删除后残留）
}
```

**删除流程：**
```go
func (s *StoreImpl) DeleteFlow(ctx context.Context, flow *Flow) error {
    // 只删除flowKey，不删除stateKeys
    // 这样GetFlowByStateToken会返回ErrFlowNotFound
}
```

### 5.4 更新策略

**不直接更新，而是创建新StateToken：**

每个用户操作产生新的StateToken：
```
请求1: stateToken=abc123 -> 处理后生成新stateToken=def456
请求2: stateToken=def456 -> 处理后生成新stateToken=ghi789
```

**过期策略：**
- TTL: `duration.UserInteraction` (用户交互超时时间)
- 旧stateToken自然过期，无需清理

---

## 6. 完整Intent类型目录

### 6.1 六大主流程Intent (PublicFlow)

| Intent | 文件 | 职能 | 存储数据 | 特殊功能 |
|--------|------|------|----------|----------|
| IntentLoginFlow | intent_login_flow.go | 用户登录 | TargetUserID, FlowReference, JSONPointer | 会话创建、登录时间更新、锁清理 |
| IntentSignupFlow | intent_signup_flow.go | 用户注册 | FlowReference, JSONPointer | 用户创建、注册限流、用户初始化 |
| IntentPromoteFlow | intent_promote_flow.go | 匿名用户升级 | FlowReference, JSONPointer, UserID | 匿名转正式用户 |
| IntentSignupLoginFlow | intent_signup_login_flow.go | 注册/登录二合一 | FlowReference, JSONPointer, UserID | 智能判断注册或登录 |
| IntentReauthFlow | intent_reauth_flow.go | 重新认证 | TargetUserID, FlowReference, JSONPointer | 验证现有会话 |
| IntentAccountRecoveryFlow | intent_account_recovery_flow.go | 账户恢复 | FlowReference, JSONPointer | 恢复码处理 |

### 6.2 Steps Intent (流程步骤编排)

| Intent | 文件 | 职能 | 存储数据 |
|--------|------|------|----------|
| IntentLoginFlowSteps | intent_login_flow_steps.go | 登录步骤编排 | FlowReference, JSONPointer, NextStepIndex |
| IntentSignupFlowSteps | intent_signup_flow_steps.go | 注册步骤编排 | FlowReference, JSONPointer, UserID, NextStepIndex |
| IntentSignupLoginFlowSteps | intent_signup_login_flow_steps.go | 二合一流程编排 | FlowReference, JSONPointer, UserID, NextStepIndex |
| IntentReauthFlowSteps | intent_reauth_flow_steps.go | 重认证步骤编排 | FlowReference, JSONPointer, NextStepIndex |
| IntentPromoteFlowSteps | intent_promote_flow_steps.go | 升级步骤编排 | FlowReference, JSONPointer, UserID, NextStepIndex |
| IntentAccountRecoveryFlowSteps | intent_account_recovery_flow_steps.go | 恢复步骤编排 | FlowReference, JSONPointer, NextStepIndex |

### 6.3 Step-level Intent (具体步骤处理)

**Identify步骤 (身份识别):**

| Intent | 文件 | 职能 | 支持的识别方式 |
|--------|------|------|----------------|
| IntentLoginFlowStepIdentify | intent_login_flow_step_identify.go | 登录身份识别 | Email/Phone/Username/OAuth/Passkey/LDAP/IDToken |
| IntentSignupFlowStepIdentify | intent_signup_flow_step_identify.go | 注册身份识别 | Email/Phone/Username/OAuth/Passkey/LDAP |
| IntentSignupLoginFlowStepIdentify | intent_signup_login_flow_step_identify.go | 二合一身份识别 | OAuth/Passkey |
| IntentPromoteFlowStepIdentify | intent_promote_flow_step_identify.go | 升级身份识别 | Email/Phone/Username |
| IntentReauthFlowStepIdentify | intent_reauth_flow_step_identify.go | 重认证身份识别 | 当前会话身份 |
| IntentAccountRecoveryFlowStepIdentify | intent_account_recovery_flow_step_identify.go | 恢复身份识别 | Email/Phone |

**Authenticate步骤 (认证):**

| Intent | 文件 | 职能 | 支持的认证方式 |
|--------|------|------|----------------|
| IntentLoginFlowStepAuthenticate | intent_login_flow_step_authenticate.go | 登录认证 | Password/TOTP/OOB_OTP/Passkey/RecoveryCode |
| IntentReauthFlowStepAuthenticate | intent_reauth_flow_step_authenticate.go | 重认证认证 | Password/TOTP/OOB_OTP |
| IntentSignupFlowStepVerify | intent_signup_flow_step_verify.go | 注册验证 | 声明验证 |

**Create Authenticator步骤 (创建认证器):**

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentLoginFlowStepCreateAuthenticator | intent_login_flow_step_create_authenticator.go | 登录时创建认证器 |
| IntentSignupFlowStepCreateAuthenticator | intent_signup_flow_step_create_authenticator.go | 注册时创建认证器 |

**其他步骤:**

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentLoginFlowStepCheckAccountStatus | intent_login_flow_step_check_account_status.go | 检查账户状态 |
| IntentLoginFlowStepPromptCreatePasskey | intent_login_flow_step_prompt_create_passkey.go | 提示创建Passkey |
| IntentLoginFlowStepChangePassword | intent_login_flow_step_change_password.go | 修改密码 |
| IntentLoginFlowStepTerminateOtherSessions | intent_login_flow_step_terminate_other_sessions.go | 终止其他会话 |
| IntentSignupFlowStepFillInUserProfile | intent_signup_flow_step_fill_in_user_profile.go | 填写用户资料 |
| IntentSignupFlowStepViewRecoveryCode | intent_signup_flow_step_view_recovery_code.go | 查看恢复码 |

### 6.4 身份操作Intent

| Intent | 文件 | 职能 | 实现Milestone |
|--------|------|------|---------------|
| IntentUseIdentityLoginID | intent_use_identity_login_id.go | 使用LoginID身份 | MilestoneFlowUseIdentity |
| IntentUseIdentityPasskey | intent_use_identity_passkey.go | 使用Passkey身份 | MilestoneFlowUseIdentity |
| IntentCreateIdentityLoginID | intent_create_identity_login_id.go | 创建LoginID身份 | MilestoneFlowCreateIdentity |
| IntentLookupIdentityOAuth | intent_lookup_identity_oauth.go | 查找OAuth身份 | MilestoneFlowUseIdentity |
| IntentLookupIdentityLoginID | intent_lookup_identity_login_id.go | 查找LoginID身份 | - |
| IntentLookupIdentityPasskey | intent_lookup_identity_passkey.go | 查找Passkey身份 | - |
| IntentLookupIdentityLDAP | intent_lookup_identity_ldap.go | 查找LDAP身份 | - |
| IntentLookupWithIDToken | intent_lookup_with_id_token.go | 用IDToken查找身份 | - |
| IntentIdentifyWithIDToken | intent_identify_with_id_token.go | IDToken身份识别 | MilestoneFlowUseIdentity |
| IntentPromoteIdentityLoginID | intent_promote_identity_login_id.go | 升级LoginID身份 | MilestoneFlowCreateIdentity |
| IntentPromoteIdentityOAuth | intent_promote_identity_oauth.go | 升级OAuth身份 | MilestoneFlowCreateIdentity |
| IntentCheckConflictAndCreateIdentity | intent_check_conflict_and_create_identity.go | 检查冲突并创建身份 | MilestoneFlowCreateIdentity |
| IntentSkipCreationByExistingIdentity | intent_skip_creation_by_existing_identity.go | 跳过已有身份创建 | - |

### 6.5 认证器操作Intent

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentUseAuthenticatorPassword | intent_use_authenticator_password.go | 使用密码认证 |
| IntentUseAuthenticatorTOTP | intent_use_authenticator_totp.go | 使用TOTP认证 |
| IntentUseAuthenticatorOOBOTP | intent_use_authenticator_oob_otp.go | 使用OOB OTP认证 |
| IntentUseAuthenticatorPasskey | intent_use_authenticator_passkey.go | 使用Passkey认证 |
| IntentCreateAuthenticatorPassword | intent_create_authenticator_password.go | 创建密码认证器 |
| IntentCreateAuthenticatorTOTP | intent_create_authenticator_totp.go | 创建TOTP认证器 |
| IntentCreateAuthenticatorOOBOTP | intent_create_authenticator_oob_otp.go | 创建OOB OTP认证器 |
| IntentUseRecoveryCode | intent_use_recovery_code.go | 使用恢复码 |

### 6.6 OAuth相关Intent

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentOAuth | intent_oauth.go | 处理OAuth授权流程 |
| IntentAuthnOOB | intent_authn_oob.go | OOB认证处理 |

### 6.7 其他功能性Intent

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentLDAP | intent_ldap.go | LDAP身份验证 |
| IntentVerifyClaim | intent_verify_claim.go | 声明验证 |
| IntentAccountLinking | intent_account_linking.go | 账户关联 |
| IntentUseAccountRecoveryIdentity | intent_use_account_recovery_identity.go | 使用账户恢复身份 |
| IntentInspectDeviceToken | intent_inspect_device_token.go | 检查设备令牌 |
| IntentCreateDeviceTokenIfRequested | intent_create_device_token_if_requested.go | 创建设备令牌 |
| IntentLoginFlowPreAuthenticated | intent_login_flow_pre_authenticated.go | 预认证处理 |
| IntentSignupFlowPreAuthenticated | intent_signup_flow_pre_authenticated.go | 注册预认证 |
| IntentPromoteFlowPreAuthenticated | intent_promote_flow_pre_authenticated.go | 升级预认证 |
| IntentLoginFlowEnforceAMRConstraints | intent_login_flow_enforce_amr_constraints.go | 强制AMR约束 |
| IntentSignupFlowEnforceAMRConstraints | intent_signup_flow_enforce_amr_constraints.go | 注册AMR约束 |
| IntentReauthFlowEnforceAMRConstraints | intent_reauth_flow_enforce_amr_constraints.go | 重认证AMR约束 |

### 6.8 Latte模块Intent (pkg/latte/)

Latte模块提供更高层次的认证功能封装：

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentLogin | intent_login.go | 登录封装 |
| IntentSignup | intent_signup.go | 注册封装 |
| IntentAuthenticate | intent_authenticate.go | 通用认证 |
| IntentReauthenticate | intent_reauthenticate.go | 重新认证封装 |
| IntentForgotPassword | intent_forgot_password.go | 忘记密码 |
| IntentForgotPasswordV2 | intent_forgot_password_v2.go | 忘记密码V2 |
| IntentResetPassword | intent_reset_password.go | 重置密码 |
| IntentChangePassword | intent_change_password.go | 修改密码 |
| IntentChangeEmail | intent_change_email.go | 修改邮箱 |
| IntentMigrate | intent_migrate.go | 数据迁移 |
| IntentMigrateLoginID | intent_migrate_login_id.go | 迁移LoginID |
| IntentMigrateAccount | intent_migrate_account.go | 迁移账户 |
| IntentMigrateIdentities | intent_migrate_identities.go | 迁移身份 |
| IntentMigrateAuthenticators | intent_migrate_authenticators.go | 迁移认证器 |
| IntentMigrateOOBOTPAuthenticator | intent_migrate_oob_otp_authenticator.go | 迁移OOB OTP |
| IntentVerifyIdentity | intent_verify_identity.go | 验证身份 |
| IntentFindVerifyIdentity | intent_find_verify_identity.go | 查找验证身份 |
| IntentVerifyUser | intent_verify_user.go | 验证用户 |
| IntentVerifyCaptcha | intent_verify_captcha.go | 验证验证码 |
| IntentVerifyLoginLink | intent_verify_login_link.go | 验证登录链接 |
| IntentVerifyProofOfPhoneNumberVerification | intent_verify_proof_of_phone_number_verification.go | 验证手机号 |
| IntentCreateLoginID | intent_create_login_id.go | 创建LoginID |
| IntentCreatePassword | intent_create_password.go | 创建密码 |
| IntentCreateOOBOTPAuthenticatorForLoginID | intent_create_oob_otp_authenticator_for_login_id.go | 创建OOB OTP认证器 |
| IntentAuthenticatePassword | intent_authenticate_password.go | 密码认证 |
| IntentAuthenticateOOBOTPPhone | intent_authenticate_oob_otp_phone.go | OOB OTP手机认证 |
| IntentAuthenticateEmailLoginLink | intent_authenticate_email_login_link.go | 邮箱登录链接认证 |
| IntentProtectedAuthenticate | intent_protected_authenticate.go | 受保护认证 |
| IntentEnsureSession | intent_ensure_session.go | 确保会话 |
| IntentReauthForgotPassword | intent_reauth_forgot_password.go | 重认证忘记密码 |

### 6.9 账户恢复步骤Intent

| Intent | 文件 | 职能 |
|--------|------|------|
| IntentAccountRecoveryFlowStepSelectDestination | intent_account_recovery_flow_step_select_destination.go | 选择恢复目标 |
| IntentAccountRecoveryFlowStepVerifyAccountRecoveryCode | intent_account_recovery_flow_step_verify_account_recovery_code.go | 验证恢复码 |
| IntentAccountRecoveryFlowStepResetPassword | intent_account_recovery_flow_step_reset_password.go | 重置密码 |

---

## 7. 设计理念分析

### 7.1 核心设计原则

**1. 声明式配置驱动**
- 流程结构由JSON/YAML配置文件定义
- Intent通过JSONPointer定位到配置中的具体步骤
- 运行时动态解析配置，灵活支持不同场景

**2. 组合优于继承**
- 通过接口组合实现功能扩展
- Intent = Kinder + InputReactor
- 可以叠加多个Milestone接口实现状态标记

**3. 不可变状态树**
- 每次操作生成新的StateToken
- 旧的Flow状态保留（直到过期）
- 支持幂等和可重放

**4. 显式状态传递**
- 通过Milestone接口显式暴露关键状态
- 避免隐式全局状态
- 便于测试和调试

### 7.2 解决的问题

| 问题 | 解决方案 |
|------|----------|
| 多步骤认证流程的复杂性 | 树状结构分解为可管理的Intent子树 |
| 不同认证方式的扩展 | 每个认证方式独立实现Intent |
| 流程状态持久化 | JSON序列化 + Redis存储 |
| 并发和幂等性 | StateToken机制保证 |
| 配置与代码解耦 | JSONPointer引用配置文件 |
| 测试困难 | 纯函数式ReactTo设计 |

### 7.3 解决效果评估

**优势：**
- 高度可配置：通过配置文件即可调整流程
- 易于扩展：新增认证方式只需实现新的Intent
- 状态清晰：Milestone机制明确暴露流程状态
- 调试友好：完整的流程树可序列化查看
- 容错性好：不可变状态树支持重试

---

## 8. 设计优缺点评估

### 8.1 优点

**1. 清晰的职责分离**
```
Intent: 负责"要做什么" (计划)
Node:   负责"已做了什么" (记录)
Milestone: 负责"如何查询状态" (接口)
```

**2. 强大的可扩展性**
- 注册机制支持动态添加新Intent
- 接口组合支持功能叠加
- 配置驱动支持非代码调整

**3. 完善的类型安全**
- 编译期接口检查
- 序列化Kind字段验证
- 强类型Milestone查询

**4. 优秀的可测试性**
- ReactTo是纯函数（给定相同输入产生相同输出）
- 可以轻松模拟Dependencies
- 可以单独测试单个Intent

**5. 灵活的状态管理**
- Milestone模式避免全局状态
- 显式状态查询接口
- 支持复杂的跨步骤依赖

### 8.2 缺点

**1. 学习曲线陡峭**
- 概念多（Intent/Node/Milestone/Flow/SubFlow）
- 接口组合需要理解关系
- JSONPointer机制需要配置文件知识

**2. 代码冗余**
- 大量类似的Intent结构（boilerplate代码）
- 每个Intent都需要注册、Kind方法、初始化
- 类型声明重复（var _ authflow.Intent = &X{}）

**3. 调试复杂度高**
- 嵌套SubFlow使调用栈很深
- 需要理解整个树结构才能定位问题
- 状态分散在多个Milestone中

**4. 性能考虑**
- JSON序列化开销较大
- 每次操作都完整保存整个Flow树
- Redis存储数据量随流程深度增加

**5. 限制和约束**
- 不支持循环流程（树状结构限制）
- 配置错误只能在运行时发现
- 复杂的错误处理逻辑

### 8.3 改进建议

| 方面 | 建议 |
|------|------|
| 代码生成 | 使用代码生成减少boilerplate |
| 状态压缩 | 考虑增量存储而非全量 |
| 验证增强 | 增加配置静态验证 |
| 文档完善 | 增加更多流程示例和最佳实践 |
| 调试工具 | 提供Flow可视化工具 |

---

## 9. InputSchema 详细设计

### 9.1 InputSchema 接口定义

`InputSchema` 定义在 `pkg/lib/authenticationflow/input.go`：

```go
type InputSchema interface {
    GetJSONPointer() jsonpointer.T                           // 指向配置中的位置
    GetFlowRootObject() config.AuthenticationFlowObject      // 流程配置根对象
    SchemaBuilder() validation.SchemaBuilder                 // JSON Schema构建器
    // MakeInput 解析并验证原始JSON输入，返回结构化Input对象
    // 验证失败必须返回 *validation.AggregateError
    MakeInput(ctx context.Context, rawMessage json.RawMessage) (Input, error)
}
```

### 9.2 InputSchema 核心职责

| 职责 | 说明 |
|------|------|
| **位置标识** | `GetJSONPointer()` 返回配置树中的精确位置，用于输入路由 |
| **配置访问** | `GetFlowRootObject()` 提供访问流程配置的能力 |
| **Schema定义** | `SchemaBuilder()` 生成JSON Schema，驱动前端表单渲染 |
| **输入解析** | `MakeInput()` 将原始JSON解析为强类型Input结构体 |

### 9.3 InputSchema 使用流程

```
1. Intent/Node.CanReactTo() 返回 InputSchema
   ↓
2. 前端获取Schema → 渲染表单（字段、验证规则、BotProtection等）
   ↓
3. 用户提交 → 后端调用 InputSchema.MakeInput() 解析原始JSON
   ↓
4. 解析成功 → 调用 Intent/Node.ReactTo(input) 处理业务逻辑
```

### 9.4 典型 InputSchema 实现

**InputSchemaTakeLoginID** (`input_take_login_id.go`)：

```go
type InputSchemaTakeLoginID struct {
    JSONPointer             jsonpointer.T                // 配置位置
    FlowRootObject          config.AuthenticationFlowObject
    IsBotProtectionRequired bool                         // 是否需要人机验证
    BotProtectionCfg        *config.BotProtectionConfig  // 验证配置
    IsExternalJWTAllowed    bool                         // 是否允许外部JWT
}

func (i *InputSchemaTakeLoginID) SchemaBuilder() validation.SchemaBuilder {
    b := validation.SchemaBuilder{}.Type(validation.TypeObject)
    
    if i.IsExternalJWTAllowed {
        b.OneOf(
            validation.SchemaBuilder{}.Required("login_id"),
            validation.SchemaBuilder{}.Required("external_jwt"),
        )
    } else {
        b.Required("login_id")
    }
    
    // 根据配置动态添加人机验证字段
    if i.IsBotProtectionRequired && i.BotProtectionCfg != nil {
        b = AddBotProtectionToExistingSchemaBuilder(b, i.BotProtectionCfg)
    }
    return b
}
```

### 9.5 Input 结构体设计模式

Input结构体实现多个细粒度接口，便于在ReactTo中类型断言：

```go
type InputTakeLoginID struct {
    LoginID       string                      `json:"login_id,omitempty"`
    ExternalJWT   string                      `json:"external_jwt,omitempty"`
    BotProtection *InputTakeBotProtectionBody `json:"bot_protection,omitempty"`
}

// 实现多个接口，支持组合输入
var _ inputTakeLoginID = &InputTakeLoginID{}
var _ inputTakeLoginIDOrExternalJWT = &InputTakeLoginID{}
var _ inputTakeBotProtection = &InputTakeLoginID{}
```

### 9.6 输入接口分类

| 类别 | 接口示例 | 用途 |
|------|----------|------|
| **身份识别** | `inputTakeIdentificationMethod`, `inputTakeLoginID` | 选择/输入身份信息 |
| **认证** | `inputTakePassword`, `inputTakeTOTP`, `inputTakeRecoveryCode` | 提供认证凭证 |
| **OAuth** | `inputTakeOAuthAuthorizationRequest`, `inputTakeOAuthAuthorizationResponse` | OAuth流程参数 |
| **BotProtection** | `inputTakeBotProtection` | 人机验证响应 |
| **账户恢复** | `inputTakeAccountRecoveryIdentificationMethod` | 恢复流程输入 |

### 9.7 关键设计特点

1. **Schema驱动UI**：JSON Schema直接决定前端表单结构
2. **动态Schema**：根据配置条件（如是否需要BotProtection）动态调整Schema
3. **接口组合**：单个Input可实现多个接口，支持灵活的类型断言
4. **强类型验证**：MakeInput必须返回AggregateError以便前端展示具体字段错误

---

## 10. 为什么区分 Node 和 Intent

### 10.1 核心差异对比

| 维度 | Intent | Node |
|------|--------|------|
| **角色** | 指挥官（Controller） | 记录员（Record） |
| **生命周期** | 长期活跃，控制流程 | 创建后冻结，追加到历史 |
| **是否可变** | 可变（如更新NextStepIndex） | 不可变（创建后不再修改） |
| **存储内容** | 控制逻辑、指针位置 | 已完成操作的数据快照 |
| **主要职责** | 决定"下一步做什么" | 记录"已经做了什么" |

### 10.2 分离的设计价值

#### 1. 不可变历史记录（Audit Trail）

Node一旦创建就不再修改，形成**不可变的操作历史**：

```go
type Flow struct {
    FlowID     string
    StateToken string
    Intent     Intent          // 当前指挥官（可变）
    Nodes      []Node          // 不可变历史记录 ⭐
}
```

**价值**：
- 完整回放流程执行路径
- 调试时可精确看到每一步的数据状态
- 支持幂等性重放

#### 2. 状态查询的清晰性（Milestone模式）

Node作为**数据快照**，可被安全查询：

```go
// 查询"用户是否已识别"
_, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDoUseIdentity](flows)
// 返回的是NodeDoUseIdentity，直接读取其中存储的identity.Info
```

如果合并为单一概念，需要额外字段区分"当前"和"已完成"，查询逻辑会变得复杂。

#### 3. 关注点分离（Single Responsibility）

**Intent的职责**：
- 决定"下一步做什么"
- 编排子流程（创建SubFlow）
- 维护控制状态（如NextStepIndex）

**Node的职责**：
- 记录"已经做了什么"
- 存储操作结果数据
- 作为Milestone暴露查询接口

```go
// Intent: 控制流（指挥官）
func (i *IntentUseIdentityLoginID) ReactTo(...) {
    // 执行业务逻辑...
    // 创建Node记录结果
    return NewNodeDoUseIdentityReactToResult(...)  // 委派给Node记录
}

// Node: 数据快照（记录员）
type NodeDoUseIdentity struct {
    Identity     *identity.Info  // 已识别的身份（不可变快照）
    IdentitySpec *identity.Spec  // 原始请求（不可变快照）
}
```

#### 4. 支持复杂的跨步骤依赖

由于Node不可变且存储完整数据，后续步骤可以**安全地依赖**之前的Node：

```go
func (i *IntentLoginFlowStepIdentify) ReactTo(...) {
    // 安全地查询之前创建的Node
    m, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneFlowUseIdentity](flows)
    info := m.MilestoneDoUseIdentity()  // 从Node快照中获取数据
}
```

#### 5. 调试和可观测性

分离后，可以**序列化整个Flow树**进行调试：

```json
{
  "intent": {"kind": "IntentLoginFlow", ...},
  "nodes": [
    {"type": "SIMPLE", "simple": {"kind": "NodeDoUseIdentity", "identity": {...}}},
    {"type": "SIMPLE", "simple": {"kind": "NodeDoUseAuthenticatorPassword", ...}},
    {"type": "SUB_FLOW", "flow": {"intent": {...}, "nodes": [...]}}
  ]
}
```

每个Node都是自包含的数据快照，可以独立理解。

### 10.3 如果合并会怎样？

假设合并为单一的`Step`概念：

```go
// 反模式：合并后的混乱
type Step struct {
    Kind        string
    Data        interface{}     // 既是控制逻辑又是结果数据
    IsCompleted bool            // 需要标记是否完成
    NextStep    *Step           // 既是控制流又是历史记录
    // ... 职责混乱
}
```

**问题**：
1. 控制逻辑和历史数据耦合
2. 需要额外字段区分"当前"和"已完成"
3. 无法安全地查询历史状态（数据可能被修改）
4. 难以支持Milestone模式

### 10.4 总结

Node和Intent的分离是**数据（Node）与控制（Intent）分离**的经典架构模式：

- **Node = 数据库记录**（事实的不可变快照）
- **Intent = 业务逻辑控制器**（决定下一步行动）

这种分离使得：
- 状态追踪清晰（Nodes列表就是操作日志）
- 查询简单（Milestone直接读取Node字段）
- 调试容易（Flow树就是完整的执行轨迹）
- 支持重放（重新执行Intent会产生相同的Nodes）

---

## 11. Node vs Intent 的 ReactTo 区别

### 10.1 核心区别概述

| 特性 | Intent.ReactTo | Node.ReactTo |
|------|----------------|--------------|
| **主要职责** | 控制流程走向，创建子流程或节点 | 响应当前状态，触发副作用或创建后续节点 |
| **调用时机** | 当当前Flow的所有Node都无法响应时 | 当Node处于活跃状态时（通常是最后一个Node）|
| **返回值** | SubFlow或Node（流程控制） | 通常返回nil或ErrEOF（状态标记） |
| **状态修改** | 可修改自身状态（如NextStepIndex） | 通常只读取自身状态，不修改 |

### 10.2 调用优先级（FindInputReactor）

```go
func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) {
    // 1. 优先检查最后一个Node是否能响应
    if len(flows.Nearest.Nodes) > 0 {
        lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
        result, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
        if err == nil {
            return result  // Node优先
        }
    }
    
    // 2. Node无法响应时，检查Intent
    return flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
}
```

**优先级**：最后一个Node > Intent > 更早的Node

### 10.3 Intent.ReactTo 典型模式

**IntentUseIdentityLoginID** - 驱动流程前进：

```go
func (n *IntentUseIdentityLoginID) ReactTo(ctx context.Context, deps *authflow.Dependencies, 
    flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    
    var inputTakeLoginID inputTakeLoginID
    if authflow.AsInput(input, &inputTakeLoginID) {
        // 1. 处理业务逻辑
        loginID := inputTakeLoginID.GetLoginID()
        spec := makeLoginIDSpec(n.Identification, stringutil.NewUserInputString(loginID))
        exactMatch, err := findExactOneIdentityInfo(ctx, deps, spec)
        
        // 2. 创建Node记录已完成的工作
        return NewNodeDoUseIdentityReactToResult(ctx, deps, flows, &NodeDoUseIdentity{
            Identity:     exactMatch,
            IdentitySpec: spec,
        })
    }
    return nil, authflow.ErrIncompatibleInput
}
```

**Intent特点**：
- 接收用户输入，执行业务逻辑
- 创建新的Node来记录完成的工作
- 驱动流程从"计划"进入"已执行"

### 10.4 Node.ReactTo 典型模式

**NodeDoUseIdentity** - 触发后续副作用：

```go
func (n *NodeDoUseIdentity) ReactTo(ctx context.Context, deps *authflow.Dependencies, 
    flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    
    // Node通常不直接处理用户输入
    // 而是创建后续处理节点（如触发事件）
    return NewNodePostIdentified(ctx, deps, flows, &NodePostIdentifiedOptions{
        Identification: n.identification(),
    })
}
```

**NodePostIdentified** - 标记完成并暂停：

```go
func (n *NodePostIdentified) ReactTo(ctx context.Context, deps *authflow.Dependencies, 
    flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    
    // 已经通过DelayedOneTimeFunction完成了工作
    // ReactTo只是标记完成
    return nil, authflow.ErrEOF
}
```

**Node特点**：
- 通常是响应流程推进的信号
- 可能创建后续处理Node
- 或返回ErrEOF表示无需进一步操作

### 10.5 协作示例：身份识别流程

```
IntentUseIdentityLoginID.ReactTo()
├── 接收login_id输入
├── 查询用户身份
└── 创建 NodeDoUseIdentity

NodeDoUseIdentity.ReactTo()
├── 无需额外输入
└── 创建 NodePostIdentified（触发事件）

NodePostIdentified.ReactTo()
├── 通过DelayedOneTimeFunction分发事件
└── 返回 ErrEOF（完成）

回到 Intent，继续下一个步骤
```

### 10.6 设计意图对比

| 维度 | Intent | Node |
|------|--------|------|
| **角色** | 指挥官（决定做什么） | 记录员（记录做了什么） |
| **生命周期** | 长期存在，控制多个步骤 | 创建后不再修改，可追加到Nodes列表 |
| **输入处理** | 主要输入处理器 | 辅助/被动输入处理器 |
| **副作用** | 通常不直接执行副作用 | 通过EffectGetter执行副作用 |

---

## 11. 关键文件索引

| 文件 | 说明 |
|------|------|
| `pkg/lib/authenticationflow/intent.go` | Intent接口定义 |
| `pkg/lib/authenticationflow/input.go` | InputReactor/InputSchema接口定义 |
| `pkg/lib/authenticationflow/marshal.go` | 序列化/注册机制 |
| `pkg/lib/authenticationflow/node.go` | Node类型定义 |
| `pkg/lib/authenticationflow/flow.go` | Flow类型和PublicFlow接口 |
| `pkg/lib/authenticationflow/store.go` | Redis存储实现 |
| `pkg/lib/authenticationflow/service.go` | 核心服务逻辑 |
| `pkg/lib/authenticationflow/declarative/input_*.go` | InputSchema实现（44个文件） |
| `pkg/lib/authenticationflow/declarative/intent_*.go` | 所有Intent实现 |
| `pkg/lib/authenticationflow/declarative/node_*.go` | 所有Node实现 |
| `pkg/lib/authenticationflow/declarative/milestone.go` | 扩展Milestone接口 |
| `pkg/latte/intent_*.go` | Latte模块Intent |

---

## 12. 案例分析：email_password_primary_oob_otp_email 登录流程

### 12.1 流程配置

```yaml
- name: email_password_primary_oob_otp_email
  type: LOGIN
  steps:
    - type: IDENTIFY
      oneOf:
        - identification: email
    - type: AUTHENTICATE
      oneOf:
        - authentication: primary_password
    - type: AUTHENTICATE
      oneOf:
        - authentication: primary_oob_otp_email
```

这是一个三步登录流程：
1. **识别**：用户输入邮箱地址
2. **第一次认证**：输入密码验证
3. **第二次认证**：输入邮箱收到的OTP验证码

### 12.2 流程执行时序图

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Flow 树结构演变                                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  初始状态                                                                       │
│  IntentLoginFlow                                                                │
│  └── [空Nodes列表]                                                              │
│                                                                                 │
│  Step 1: IDENTIFY (email)                                                     │
│  IntentLoginFlow                                                                │
│  ├── Node: NodePreInitialize                                                    │
│  └── SubFlow: IntentLoginFlowSteps                                              │
│      └── SubFlow: IntentLoginFlowStepIdentify                                   │
│          └── SubFlow: IntentUseIdentityLoginID                                  │
│              └── Node: NodeDoUseIdentity ⭐ [MilestoneDoUseIdentity]            │
│              └── Node: NodePostIdentified                                       │
│                                                                                 │
│  Step 2: AUTHENTICATE (primary_password)                                        │
│  IntentLoginFlow                                                                │
│  ├── Node: NodePreInitialize                                                    │
│  ├── SubFlow: IntentLoginFlowSteps (NextStepIndex=1)                            │
│      └── SubFlow: IntentLoginFlowStepAuthenticate                               │
│          └── SubFlow: IntentUseAuthenticatorPassword                            │
│              └── Node: NodeDoUseAuthenticatorPassword ⭐ [MilestoneDidAuthenticate]│
│                                                                                 │
│  Step 3: AUTHENTICATE (primary_oob_otp_email)                                   │
│  IntentLoginFlow                                                                │
│  ├── Node: NodePreInitialize                                                    │
│  ├── SubFlow: IntentLoginFlowSteps (NextStepIndex=2)                            │
│      ├── SubFlow: IntentLoginFlowStepAuthenticate (password 已完成)               │
│      └── SubFlow: IntentLoginFlowStepAuthenticate (oob_otp)                     │
│          └── SubFlow: IntentUseAuthenticatorOOBOTP                                │
│              ├── Node: NodeSendOOBOTP (发送验证码)                               │
│              └── Node: NodeDoUseAuthenticatorOOBOTP ⭐ [MilestoneDidAuthenticate] │
│                                                                                 │
│  完成状态                                                                       │
│  IntentLoginFlow                                                                │
│  ├── Node: NodePreInitialize                                                    │
│  ├── SubFlow: IntentLoginFlowSteps (NextStepIndex=3, EOF)                        │
│  └── Node: NodeDoCreateSession ⭐ [MilestoneDoCreateSession]                    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 12.3 详细组件分析

#### 12.3.1 Intent 清单

| Intent | 层级 | 职责 | JSONPointer |
|--------|------|------|-------------|
| **IntentLoginFlow** | Root | 登录流程入口，最终创建会话 | `/` |
| **IntentLoginFlowSteps** | SubFlow | 步骤编排器，管理3个步骤的执行顺序 | `/steps` |
| **IntentLoginFlowStepIdentify** | Step | 处理IDENTIFY步骤，提供email识别选项 | `/steps/0` |
| **IntentUseIdentityLoginID** | Task | 处理email身份识别，查询用户 | `/steps/0/oneOf/0` |
| **IntentLoginFlowStepAuthenticate** | Step | 处理AUTHENTICATE步骤，支持password和oob_otp | `/steps/1`, `/steps/2` |
| **IntentUseAuthenticatorPassword** | Task | 处理密码认证，验证用户密码 | `/steps/1/oneOf/0` |
| **IntentUseAuthenticatorOOBOTP** | Task | 处理OOB OTP认证，发送和验证验证码 | `/steps/2/oneOf/0` |

#### 12.3.2 Node 清单

| Node | 创建者 | 存储数据 | 作用 |
|------|--------|----------|------|
| **NodePreInitialize** | IntentLoginFlow.ReactTo | BotProtection配置、RateLimits约束 | 初始化上下文，准备执行环境 |
| **NodeDoUseIdentity** | IntentUseIdentityLoginID.ReactTo | `Identity` (识别的用户信息), `IdentitySpec` (原始请求) | 记录用户身份识别结果 |
| **NodePostIdentified** | NodeDoUseIdentity.ReactTo | `Identification`, `IsPostIdentifiedInvoked` | 触发post-identified事件，更新约束 |
| **NodeDoUseAuthenticatorPassword** | IntentUseAuthenticatorPassword.ReactTo | `Authenticator`, `PasswordChangeRequired` | 记录密码认证结果 |
| **NodeSendOOBOTP** | IntentUseAuthenticatorOOBOTP.ReactTo | `Channel`, `Target`, `Code` (加密存储) | 发送OTP验证码到用户邮箱 |
| **NodeDoUseAuthenticatorOOBOTP** | IntentUseAuthenticatorOOBOTP.ReactTo | `Authenticator`, `Authentication` | 记录OTP验证结果 |
| **NodeDoCreateSession** | IntentLoginFlow.ReactTo | `UserID`, `CreateReason`, `Session` | 创建用户会话，完成登录 |

#### 12.3.3 Milestone 清单

| Milestone | 实现者 | 作用 | 查询场景 |
|-----------|--------|------|----------|
| **MilestoneDoUseIdentity** | NodeDoUseIdentity | `MilestoneDoUseIdentity()` 返回已识别的identity.Info | 后续步骤查询已识别用户 |
| **MilestoneDidAuthenticate** | NodeDoUseAuthenticatorPassword, NodeDoUseAuthenticatorOOBOTP | `MilestoneDidAuthenticate()` 返回AMR信息 | AMR约束检查、日志记录 |
| **MilestoneDidSelectAuthenticationMethod** | IntentUseAuthenticatorPassword, IntentUseAuthenticatorOOBOTP | 返回选择的认证方式 | 确定用户使用的认证方法 |
| **MilestoneDoCreateSession** | NodeDoCreateSession | `MilestoneDoCreateSession()` 返回创建的会话 | 流程完成判断、事件派发 |
| **MilestoneNestedSteps** | IntentLoginFlowSteps | 标记这是一个嵌套步骤容器 | 步骤编排判断 |

### 12.4 关键执行流程详解

#### Step 1: 识别用户身份

```go
// IntentLoginFlow.ReactTo 发现Nodes为空，开始执行
// 1. 创建 NodePreInitialize
// 2. 创建 SubFlow IntentLoginFlowSteps

// IntentLoginFlowSteps.ReactTo (NextStepIndex=0)
// 识别出是IDENTIFY步骤，创建 SubFlow IntentLoginFlowStepIdentify

// IntentLoginFlowStepIdentify.ReactTo
// 返回 InputSchemaStepIdentify，前端渲染email输入框

// 用户输入email后
// IntentUseIdentityLoginID.ReactTo
func (n *IntentUseIdentityLoginID) ReactTo(ctx, deps, flows, input) {
    loginID := input.GetLoginID()
    spec := makeLoginIDSpec("email", loginID)
    exactMatch := findExactOneIdentityInfo(deps, spec)  // 查询用户
    
    // 创建Node记录识别结果
    return NewNodeDoUseIdentityReactToResult(&NodeDoUseIdentity{
        Identity: exactMatch,  // 存储查询到的用户信息
        IdentitySpec: spec,     // 存储原始请求
    })
}

// NodeDoUseIdentity 实现了 MilestoneDoUseIdentity
// 后续步骤可以通过它查询已识别的用户
```

#### Step 2: 密码认证

```go
// IntentLoginFlowSteps.ReactTo (NextStepIndex=1, 识别已完成)
// 识别出是AUTHENTICATE步骤，创建 SubFlow IntentLoginFlowStepAuthenticate

// IntentLoginFlowStepAuthenticate 内部逻辑
// 1. 查询用户已启用的认证器
// 2. 根据配置确定可用的认证方式
// 3. 创建对应的认证Intent

// 这里创建 IntentUseAuthenticatorPassword
func (i *IntentUseAuthenticatorPassword) CanReactTo(...) {
    // 检查是否已有 MilestoneDidAuthenticate
    _, _, authenticated := FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
    if authenticated {
        return nil, ErrEOF  // 已认证，结束
    }
    // 返回 InputSchemaTakePassword
    return &InputSchemaTakePassword{...}
}

// 用户输入密码后
func (i *IntentUseAuthenticatorPassword) ReactTo(...) {
    password := input.GetPassword()
    // 验证密码...
    
    // 创建Node记录认证结果
    return NewNodeSimple(&NodeDoUseAuthenticatorPassword{
        Authenticator: info,
        PasswordChangeRequired: verifyResult.RequireUpdate(),
    })
}
```

#### Step 3: OTP认证

```go
// IntentLoginFlowSteps.ReactTo (NextStepIndex=2)
// 识别出第二个AUTHENTICATE步骤

// IntentUseAuthenticatorOOBOTP 处理流程
func (i *IntentUseAuthenticatorOOBOTP) CanReactTo(...) {
    switch len(flows.Nearest.Nodes) {
    case 0:
        // 需要发送OTP
        return &InputSchemaUseAuthenticatorOOBOTP{Step: "send"}, nil
    case 1:
        // 需要验证OTP
        return &InputSchemaUseAuthenticatorOOBOTP{Step: "verify"}, nil
    default:
        return nil, ErrEOF
    }
}

// 发送OTP阶段
func (i *IntentUseAuthenticatorOOBOTP) ReactTo (case 0) {
    // 生成验证码
    code := generateOOBCode()
    // 发送到用户邮箱
    sendEmail(user.Email, code)
    
    // 创建Node记录发送状态
    return NewNodeSimple(&NodeSendOOBOTP{...})
}

// 验证OTP阶段（用户收到邮件后输入验证码）
func (i *IntentUseAuthenticatorOOBOTP) ReactTo (case 1) {
    code := input.GetCode()
    // 验证验证码...
    
    // 创建Node记录验证结果
    return NewNodeSimple(&NodeDoUseAuthenticatorOOBOTP{
        Authenticator: authenticator,
        Authentication: authnModel,
    })
}
```

#### 完成：创建会话

```go
// IntentLoginFlow.ReactTo 检查到已有 MilestoneDoCreateSession
// 流程完成，返回ErrEOF

// IntentLoginFlow.GetEffects 执行副作用
// 1. 更新用户最后登录时间
// 2. 清理认证锁定
// 3. 派发UserAuthenticated事件
```

### 12.5 关键设计亮点

#### 1. 清晰的职责分离

| 阶段 | Intent 职责 | Node 职责 |
|------|---------------|-----------|
| 识别 | IntentUseIdentityLoginID 决定如何识别 | NodeDoUseIdentity 存储识别结果 |
| 认证1 | IntentUseAuthenticatorPassword 决定如何认证 | NodeDoUseAuthenticatorPassword 存储认证结果 |
| 认证2 | IntentUseAuthenticatorOOBOTP 控制发送/验证 | NodeSendOOBOTP + NodeDoUseAuthenticatorOOBOTP 记录状态 |
| 完成 | IntentLoginFlow 编排整体流程 | NodeDoCreateSession 记录会话创建 |

#### 2. Milestone 状态传递

```
Step 1完成
  NodeDoUseIdentity [MilestoneDoUseIdentity]
         ↓
         查询用户ID
         ↓
Step 2开始
  IntentUseAuthenticatorPassword
         ↓
         需要用户ID来查询密码认证器
         ↓
  通过 MilestoneDoUseIdentity 获取用户ID
```

#### 3. 不可变状态的好处

假设在Step 3需要重试OTP验证：
- Nodes列表保持不变（记录了之前的发送和验证尝试）
- 每次验证失败都会追加新的Node
- 可以完整追踪用户的尝试历史

```
IntentUseAuthenticatorOOBOTP
├── NodeSendOOBOTP (第一次发送)
├── NodeDoUseAuthenticatorOOBOTP (第一次验证 - 失败)
├── NodeSendOOBOTP (第二次发送 - 重发)
└── NodeDoUseAuthenticatorOOBOTP (第二次验证 - 成功)
```

### 12.6 总结

这个案例展示了Authgear Intent设计在实际流程中的应用：

1. **Intent负责决策**：每个步骤用什么方式识别/认证
2. **Node负责记录**：每个操作的结果被快照保存
3. **Milestone负责查询**：跨步骤的状态依赖通过接口解耦
4. **树状结构清晰**：Flow → SubFlow → Intent → Node 的层级关系一目了然

这种设计使得：
- 流程可完整追踪和调试
- 状态查询类型安全
- 支持复杂的跨步骤依赖
- 易于扩展新的认证方式

---

## 13. 总结

Authgear的Intent设计是一个精心设计的认证流程框架，它：

1. **通过树状结构**将复杂的认证流程分解为可管理的单元
2. **通过接口组合**实现了功能的灵活扩展
3. **通过Milestone模式**提供了清晰的状态查询机制
4. **通过不可变状态**保证了幂等性和可重放性
5. **通过配置驱动**实现了流程的可定制化

虽然学习曲线较陡，但对于需要支持多种认证方式、复杂流程编排的认证系统来说，这是一个稳健且可扩展的设计方案。
$$