# Authgear Server 修改密码 MFA 流程设计调研报告

## 一、需求概述

### 1.1 核心需求

为用户修改密码操作设计 MFA（多因素认证）流程，具体要求：

- 修改密码时需要邮箱 2FA 验证
- 处理用户邮箱可能未绑定的场景（需要先绑定邮箱）
- 考虑"一次有效 TOTP"机制（10 分钟内有效）

### 1.2 流程拆分方案

考虑拆分为两个 Flow：

1. **Flow A - 邮箱绑定/验证**：验证邮箱（或绑定新邮箱）
2. **Flow B - 密码修改**：执行实际密码修改（需要 TOTP 验证）
3. **关联机制**：Flow A 完成后获得"一次有效 TOTP"，可在 10 分钟内跳过 Flow B 的验证

---

## 二、当前 Authgear 密码修改流程分析

### 2.1 现有修改密码实现

**主密码修改**（无需 MFA）：

```go:23:43:pkg/lib/accountmanagement/service_authenticator.go
// ChangePrimaryPassword 仅需旧密码验证，不需要 MFA
func (s *Service) ChangePrimaryPassword(ctx context.Context, resolvedSession session.ResolvedSession, input *ChangePrimaryPasswordInput) error {
    err = s.Database.WithTx(ctx, func(ctx context.Context) error {
        _, err = s.changePassword(ctx, resolvedSession, &changePasswordInput{
            Kind:        authenticator.KindPrimary,
            OldPassword: input.OldPassword,
            NewPassword: input.NewPassword,
        })
        return err
    })
    return err
}
```

**Web Handler 处理**：

```go:83:108:pkg/auth/handler/webapp/authflowv2/settings_change_password.go
func (h *AuthflowV2SettingsChangePasswordHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // ...
    ctrl.PostActionWithSettingsActionWebSession("", r, func(ctx context.Context, webappSession *webapp.Session) error {
        // 仅需旧密码
        input := &accountmanagement.ChangePrimaryPasswordInput{
            OldPassword: oldPassword,
            NewPassword: newPassword,
        }
        err = h.AccountManagementService.ChangePrimaryPassword(ctx, s, input)
        // ...
    })
}
```

### 2.2 当前流程特点

- 仅需旧密码验证
- 不强制要求 MFA
- 使用已登录会话进行身份验证
- 通过 `AccountManagementService` 执行密码修改

---

## 三、关键机制调研

### 3.1 AccountManagement Token 系统

**Token 结构**：

```go:12:57:pkg/lib/accountmanagement/token.go
type Token struct {
    AppID     string     `json:"app_id,omitempty"`
    UserID    string     `json:"user_id,omitempty"`
    TokenHash string     `json:"token_hash,omitempty"`
    CreatedAt *time.Time `json:"created_at,omitempty"`
    ExpireAt  *time.Time `json:"expire_at,omitempty"`

    // Identity
    Identity *TokenIdentity `json:"token_identity,omitempty"`

    // Authenticator - 可存储验证状态
    Authenticator *TokenAuthenticator `json:"token_authenticator,omitempty"`
}

type TokenAuthenticator struct {
    // OOB OTP 验证状态
    OOBOTPChannel  model.AuthenticatorOOBChannel `json:"oob_otp_channel,omitempty"`
    OOBOTPTarget   string                        `json:"oob_otp_target,omitempty"`
    OOBOTPVerified bool                          `json:"oob_otp_verified,omitempty"`
    
    // TOTP 验证状态
    TOTPVerified   bool                          `json:"totp_verified,omitempty"`
}
```

**Token 存储与有效期**：

```go:60:67:pkg/lib/accountmanagement/redis_store.go
func (s *RedisStore) GenerateToken(ctx context.Context, options GenerateTokenOptions) (string, error) {
    tokenString := GenerateToken()
    tokenHash := HashToken(tokenString)
    
    now := s.Clock.NowUTC()
    ttl := duration.UserInteraction  // 20分钟
    expireAt := now.Add(ttl)
    // ...
}
```

**Token 特点**：

- 存储于 Redis，TTL 默认 20 分钟
- 可携带 `OOBOTPVerified` 等验证状态
- 使用 SHA256 哈希作为存储 Key
- 支持 `GetToken`（仅获取）和 `ConsumeToken`（获取并删除）

### 3.2 Reauth Flow 机制

**Reauth Flow 执行流程**：

```
IntentReauthFlow (Root Intent)
├── Node 0: NodePreInitialize (初始化)
├── Node 1: IntentReauthFlowSteps (执行配置步骤)
│   ├── StepIdentify (可选)
│   │   └── IntentIdentifyWithIDToken / 其他识别方式
│   └── StepAuthenticate (认证)
│       ├── IntentUseAuthenticatorPassword
│       ├── IntentUseAuthenticatorOOBOTP (邮箱/短信 OTP)
│       ├── IntentUseAuthenticatorTOTP
│       └── IntentUseAuthenticatorPasskey
└── Node 2: NodeDidReauthenticate (完成标记)
    └── Effects: 更新 Session AMR
```

**关键代码**：

```go:44:75:pkg/lib/authenticationflow/declarative/intent_reauth_flow.go
func (i *IntentReauthFlow) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 检查是否已完成重新认证
    _, _, ok := authflow.FindMilestoneInCurrentFlow[MilestoneDidReauthenticate](flows)
    if ok {
        return nil, authflow.ErrEOF
    }
    return nil, nil
}

func (i *IntentReauthFlow) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    switch {
    case len(flows.Nearest.Nodes) == 0:
        return NewNodePreInitialize(ctx, deps, flows)
    case len(flows.Nearest.Nodes) == 1:
        return authflow.NewSubFlow(&IntentReauthFlowSteps{...}), nil
    case len(flows.Nearest.Nodes) == 2:
        n, err := NewNodeDidReauthenticate(ctx, deps, flows, &NodeDidReauthenticate{
            UserID: i.userID(flows),
        })
        return authflow.NewNodeSimple(n), nil
    }
}
```

**Reauth Flow 特点**：

- 支持多种认证方式：密码、TOTP、OOB OTP、Passkey
- 不会创建新 Session，只更新现有 Session 的 AMR
- 使用 Milestone 机制标记完成状态
- 配置灵活，可在 `authentication_flow.yaml` 中定义步骤

### 3.3 Verification 服务

**验证声明结构**：

```go:18:35:pkg/lib/feature/verification/claim.go
type Claim struct {
    ID        string
    UserID    string
    Name      string      // "email" 或 "phone_number"
    Value     string      // 邮箱地址或手机号
    CreatedAt time.Time
    Metadata  map[string]interface{}
}

type ClaimStatus struct {
    Name                       string
    Value                      string
    Verified                   bool
    RequiredToVerifyOnCreation bool
    EndUserTriggerable         bool
    VerifiedByChannel          model.AuthenticatorOOBChannel
}
```

**验证状态检查**：

```go:166:195:pkg/lib/feature/verification/service.go
func (s *Service) GetClaimStatus(ctx context.Context, userID string, claimName model.ClaimName, claimValue string) (*ClaimStatus, error) {
    claims, err := s.ClaimStore.ListByUser(ctx, userID)
    
    verified := false
    for _, claim := range claims {
        if claim.Name == string(claimName) && claim.Value == claimValue {
            verified = true
            break
        }
    }
    
    return &ClaimStatus{
        Name:     string(claimName),
        Value:    claimValue,
        Verified: verified,
        // ...
    }, nil
}
```

**Verification 服务特点**：

- 基于数据库持久化存储（`_auth_verified_claim` 表）
- `Claim` 没有内置过期机制（只有 `CreatedAt`）
- 验证状态是持久的，不适用于"临时验证"场景
- 主要用于邮箱/手机号的身份验证

### 3.4 OOB OTP 机制

**OOB OTP 验证流程**：

```go:86:189:pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go
func (n *IntentUseAuthenticatorOOBOTP) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 阶段1：选择认证器（邮箱或手机）
    case !authenticatorSelected:
        return &InputSchemaUseAuthenticatorOOBOTP{...}, nil
    
    // 阶段2：验证声明（发送并验证 OTP）
    case !claimVerified:
        return nil, nil  // 启动子流程
        
    // 阶段3：完成认证
    case !authenticated:
        return nil, nil
}

func (n *IntentUseAuthenticatorOOBOTP) ReactTo(...) {
    case !claimVerified:
        // 启动 OTP 验证子流程
        return authflow.NewSubFlow(&IntentAuthenticationOOB{
            JSONPointer:    n.JSONPointer,
            UserID:         n.UserID,
            Purpose:        otp.PurposeOOBOTP,
            Authentication: n.Authentication,
            Info:           info,
            Form:           otpForm,
        }), nil
}
```

**OTP 验证码有效期**：

```go:36:52:pkg/lib/config/verification.go
func (c *VerificationConfig) SetDefaults() {
    if c.Deprecated_CodeExpirySeconds == 0 {
        c.Deprecated_CodeExpirySeconds = DurationSeconds(300)  // 5分钟
    }
    if c.CodeValidPeriod == "" {
        c.CodeValidPeriod = DurationString(c.Deprecated_CodeExpirySeconds.Duration().String())
    }
}
```

### 3.5 用户邮箱绑定状态检查

**检查用户是否有邮箱身份**：

```go
// 获取用户所有身份信息
identities, err := deps.Identities.ListByUser(ctx, userID)

// 查找邮箱身份
var emailIdentity *identity.Info
for _, iden := range identities {
    if iden.Type == model.IdentityTypeLoginID && iden.LoginID != nil {
        switch iden.LoginID.LoginIDType {
        case config.LoginIDKeyTypeEmail:
            emailIdentity = iden
        }
    }
}

// 检查邮箱是否已验证
if emailIdentity != nil {
    status, err := deps.Verification.GetClaimStatus(ctx, userID, model.ClaimEmail, emailIdentity.LoginID.LoginID)
    if status.Verified {
        // 邮箱已绑定并验证
    }
}
```

---

## 四、方案设计与对比

### 4.1 方案 A：使用 AccountManagement Token（推荐）

**设计思路**：
利用现有 Token 系统，创建"预验证 Token"存储邮箱验证状态。

**流程设计**：

```
┌─────────────────────────────────────────────────────────────────┐
│  Flow A: 邮箱验证                                                 │
├─────────────────────────────────────────────────────────────────┤
│  1. 检查用户是否有已验证的邮箱                                      │
│     ├─ 有 → 发送 OTP 到已有邮箱                                   │
│     └─ 无 → 引导用户输入并绑定新邮箱 → 发送 OTP                     │
│  2. 用户输入 OTP 验证码                                            │
│  3. 验证成功 → 生成 PreVerifiedToken                             │
│     (Token.OOBOTPVerified = true, TTL=10分钟)                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Flow B: 修改密码                                                 │
├─────────────────────────────────────────────────────────────────┤
│  1. 接收 PreVerifiedToken + 新密码                                │
│  2. 验证 Token 有效性（ConsumeToken）                              │
│  3. 检查 Token.OOBOTPVerified 是否为 true                          │
│  4. 执行密码修改                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**实现代码示例**：

```go
// Flow A: 开始邮箱验证
type StartEmailVerificationInput struct {
    UserID string
    Email  string // 可选，为空则使用已有邮箱
}

type StartEmailVerificationOutput struct {
    Token string // 预验证 Token
}

func (s *Service) StartEmailVerification(ctx context.Context, input *StartEmailVerificationInput) (*StartEmailVerificationOutput, error) {
    // 1. 确定邮箱地址
    email := input.Email
    if email == "" {
        // 获取用户默认邮箱
        identities, _ := s.Identities.ListByUser(ctx, input.UserID)
        for _, iden := range identities {
            if iden.LoginID.LoginIDType == config.LoginIDKeyTypeEmail {
                email = iden.LoginID.LoginID
                break
            }
        }
    }
    
    // 2. 生成 OTP
    code, _ := s.OTPCodeService.GenerateOTP(ctx, otp.KindOOBOTP, email, otp.FormCode, nil)
    
    // 3. 发送 OTP
    s.OTPSender.Send(ctx, otp.SendOptions{
        Target:  email,
        OTP:     code,
        Purpose: otp.PurposeOOBOTP,
    })
    
    // 4. 生成预验证 Token（未验证状态）
    token, _ := s.Store.GenerateToken(ctx, GenerateTokenOptions{
        UserID:                     input.UserID,
        AuthenticatorOOBOTPChannel: model.AuthenticatorOOBChannelEmail,
        AuthenticatorOOBOTPTarget:  email,
        AuthenticatorOOBOTPVerified: false,
    })
    
    return &StartEmailVerificationOutput{Token: token}, nil
}

// Flow A: 验证 OTP
func (s *Service) VerifyEmailOTP(ctx context.Context, tokenStr, code string) error {
    token, _ := s.Store.GetToken(ctx, tokenStr)
    
    // 验证 OTP
    err := s.OTPCodeService.VerifyOTP(ctx, otp.KindOOBOTP, token.Authenticator.OOBOTPTarget, code, nil)
    if err != nil {
        return err
    }
    
    // 标记已验证（生成新 Token）
    newToken, _ := s.Store.GenerateToken(ctx, GenerateTokenOptions{
        UserID:                     token.UserID,
        AuthenticatorOOBOTPChannel: token.Authenticator.OOBOTPChannel,
        AuthenticatorOOBOTPTarget:  token.Authenticator.OOBOTPTarget,
        AuthenticatorOOBOTPVerified: true, // 已验证
    })
    
    // 返回新 Token 给客户端
    return nil
}

// Flow B: 修改密码（带验证）
type ChangePasswordWithVerificationInput struct {
    Token       string // 预验证 Token
    NewPassword string
}

func (s *Service) ChangePasswordWithVerification(ctx context.Context, resolvedSession session.ResolvedSession, input *ChangePasswordWithVerificationInput) error {
    // 消费 Token（一次性使用）
    token, err := s.Store.ConsumeToken(ctx, input.Token)
    if err != nil {
        return err // Token 无效或过期
    }
    
    // 检查用户绑定
    err = token.CheckUser(resolvedSession.GetAuthenticationInfo().UserID)
    if err != nil {
        return err
    }
    
    // 检查验证状态
    if !token.Authenticator.OOBOTPVerified {
        return errors.New("email not verified")
    }
    
    // 执行密码修改
    return s.ChangePrimaryPassword(ctx, resolvedSession, &ChangePrimaryPasswordInput{
        OldPassword: "", // 可跳过旧密码检查（已通过邮箱验证）
        NewPassword: input.NewPassword,
    })
}
```

**方案 A 优点**：

- 利用现有 Token 系统，实现简单
- TTL 天然支持过期（可配置为 10 分钟）
- Token 一次性使用，安全性高
- 不需要修改 Verification 服务的数据库结构
- 与现有 AccountManagement 服务集成良好

**方案 A 缺点**：

- 需要新增 Service 方法
- Token 需要在客户端和服务端之间传递

---

### 4.2 方案 B：扩展 Verification Claim 添加过期时间

**设计思路**：
扩展 Verification 服务的 `Claim` 结构，添加 `ExpiresAt` 字段支持临时验证状态。

**数据结构调整**：

```go
// pkg/lib/feature/verification/claim.go
type Claim struct {
    ID        string
    UserID    string
    Name      string
    Value     string
    CreatedAt time.Time
    ExpiresAt *time.Time  // 新增：过期时间
    Metadata  map[string]interface{}
}
```

**验证逻辑**：

```go
func (s *Service) IsClaimValid(ctx context.Context, claim *Claim) bool {
    if claim.ExpiresAt == nil {
        return true // 永久有效
    }
    return s.Clock.NowUTC().Before(*claim.ExpiresAt)
}

func (s *Service) GetClaimStatus(ctx context.Context, userID string, claimName model.ClaimName, claimValue string) (*ClaimStatus, error) {
    // ... 现有逻辑 ...
    
    valid := verified
    if verified && claim.ExpiresAt != nil {
        valid = s.Clock.NowUTC().Before(*claim.ExpiresAt)
    }
    
    return &ClaimStatus{
        Verified: valid, // 包含过期检查
        // ...
    }, nil
}
```

**流程设计**：

```
1. 验证邮箱 OTP（使用现有机制）
2. 创建临时 Claim（带过期时间）
   claim := &Claim{
       Name:      "email",
       Value:     email,
       ExpiresAt: &time.Now().Add(10 * time.Minute),
   }
3. 修改密码前检查 Claim 是否有效
```

**方案 B 优点**：

- 验证状态存储在数据库，可追踪和审计
- 不依赖 Redis，更持久

**方案 B 缺点**：

- 需要修改数据库结构（添加 `expires_at` 列）
- 需要修改 Verification 服务的多处逻辑
- 临时 Claim 清理需要额外机制
- 实现复杂度高

---

### 4.3 方案 C：使用 Reauth Flow + 自定义步骤

**设计思路**：
创建自定义的 Reauth Flow 配置，添加邮箱验证步骤。

**配置示例**：

```yaml
# authentication_flow.yaml
reauth_flow:
  name: reauth_for_password_change
  steps:
    - name: identify
      type: identify
      one_of:
        - identification: id_token
    
    - name: verify_email
      type: authenticate
      one_of:
        - authentication: secondary_oob_otp_email
          target_step: identify
    
    - name: authenticate
      type: authenticate
      one_of:
        - authentication: secondary_totp
        - authentication: secondary_oob_otp_email
```

**自定义 Intent 实现**：

```go
// 在修改密码前执行 Reauth Flow
func (h *SettingsChangePasswordHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // 1. 先执行 Reauth Flow 验证邮箱
    reauthResult := h.AuthflowController.HandleReauth(r, w, "reauth_for_password_change")
    if !reauthResult.IsFinished {
        return // 等待用户完成验证
    }
    
    // 2. 检查是否完成了邮箱验证步骤
    if !reauthResult.HasStep("verify_email") {
        return ErrEmailVerificationRequired
    }
    
    // 3. 执行密码修改
    h.AccountManagementService.ChangePrimaryPassword(...)
}
```

**方案 C 优点**：

- 利用现有 Reauth Flow 机制
- 配置化，灵活度高
- 可与 Flow 系统深度集成

**方案 C 缺点**：

- 需要创建自定义 Flow 配置
- 修改密码 Handler 需要大幅改动
- 需要处理 Reauth Flow 与修改密码的衔接
- 实现复杂度高

---

## 五、方案对比总结


| 特性        | 方案 A (Token) | 方案 B (Verification) | 方案 C (Reauth Flow) |
| --------- | ------------ | ------------------- | ------------------ |
| 实现复杂度     | ⭐⭐ 低         | ⭐⭐⭐⭐ 高              | ⭐⭐⭐⭐ 高             |
| 对现有代码改动   | ⭐⭐ 小         | ⭐⭐⭐⭐ 大              | ⭐⭐⭐ 中              |
| 安全性       | ⭐⭐⭐⭐⭐ 高      | ⭐⭐⭐⭐ 高              | ⭐⭐⭐⭐⭐ 高            |
| 灵活性       | ⭐⭐⭐⭐ 高       | ⭐⭐⭐ 中               | ⭐⭐⭐⭐⭐ 极高           |
| 是否需要数据库迁移 | ❌ 否          | ✅ 是                 | ❌ 否                |
| 是否支持过期时间  | ✅ TTL        | ✅ 可添加               | ✅ 可配置              |
| 一次性使用     | ✅ 是          | ❌ 否（可添加）            | ✅ 是                |
| 推荐程度      | ⭐⭐⭐⭐⭐        | ⭐⭐⭐                 | ⭐⭐⭐⭐               |


---

## 六、推荐方案：方案 A + 优化

### 6.1 核心设计

结合方案 A 的简单性和方案 C 的配置化思想，采用 **"预验证 Token + 独立 API"** 的设计。

### 6.2 完整流程设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                         修改密码 MFA 流程                            │
└─────────────────────────────────────────────────────────────────────┘

  ┌─────────────┐     ┌──────────────────┐     ┌─────────────────────┐
  │  用户请求    │     │  检查邮箱绑定状态  │     │   决策分支           │
  │  修改密码    │────▶│                  │────▶│                     │
  └─────────────┘     └──────────────────┘     └──────────┬──────────┘
                                                          │
                              ┌─────────────────────────────┼─────────────────────────────┐
                              │                             │                             │
                              ▼                             ▼                             ▼
                    ┌─────────────────┐           ┌─────────────────┐           ┌─────────────────┐
                    │  已有验证邮箱    │           │  有邮箱未验证    │           │   无邮箱绑定    │
                    │                 │           │                 │           │                 │
                    └────────┬────────┘           └────────┬────────┘           └────────┬────────┘
                             │                             │                             │
                             ▼                             ▼                             ▼
                    ┌─────────────────┐           ┌─────────────────┐           ┌─────────────────┐
                    │ 发送 OTP 到邮箱  │           │ 发送 OTP 到邮箱  │           │ 引导用户输入    │
                    │                 │           │ 并标记需绑定     │           │ 新邮箱地址      │
                    └────────┬────────┘           └────────┬────────┘           └────────┬────────┘
                             │                             │                             │
                             └─────────────────────────────┼─────────────────────────────┘
                                                           │
                                                           ▼
                                                  ┌─────────────────┐
                                                  │  用户输入 OTP    │
                                                  │                 │
                                                  └────────┬────────┘
                                                           │
                                                           ▼
                                                  ┌─────────────────┐
                                                  │  验证 OTP        │
                                                  │  生成 Token      │
                                                  │  (TTL=10分钟)    │
                                                  └────────┬────────┘
                                                           │
                                                           ▼
                                                  ┌─────────────────┐
                                                  │  用户提交新密码  │
                                                  │  + Token         │
                                                  └────────┬────────┘
                                                           │
                                                           ▼
                                                  ┌─────────────────┐
                                                  │  验证 Token      │
                                                  │  修改密码        │
                                                  │  删除 Token      │
                                                  └─────────────────┘
```

### 6.3 API 设计

**API 1: 开始邮箱验证**

```http
POST /api/account_management/start_email_verification_for_password_change
Request:
{
    "email": "user@example.com"  // 可选，不传则使用默认邮箱
}

Response:
{
    "token": "abc123...",        // 预验证 Token（未验证状态）
    "state": "pending_verification"
}
```

**API 2: 验证邮箱 OTP**

```http
POST /api/account_management/verify_email_otp
Request:
{
    "token": "abc123...",
    "code": "123456"
}

Response:
{
    "token": "xyz789...",        // 新 Token（已验证状态）
    "state": "verified",
    "expires_at": "2024-01-01T12:10:00Z"
}
```

**API 3: 修改密码（带验证）**

```http
POST /api/account_management/change_password_with_verification
Request:
{
    "token": "xyz789...",        // 已验证 Token
    "new_password": "newPass123!"
}

Response:
{
    "success": true
}
```

### 6.4 核心代码实现

**Service 扩展**：

```go:1:150:pkg/lib/accountmanagement/service_password_verification.go（新增文件）
package accountmanagement

import (
    "context"
    "errors"
    "time"
    
    "github.com/authgear/authgear-server/pkg/api/model"
    "github.com/authgear/authgear-server/pkg/lib/authn/identity"
    "github.com/authgear/authgear-server/pkg/lib/authn/otp"
    "github.com/authgear/authgear-server/pkg/lib/config"
    "github.com/authgear/authgear-server/pkg/lib/session"
)

// ErrNoEmailBound 用户没有绑定邮箱
type ErrNoEmailBound struct{}

func (e ErrNoEmailBound) Error() string {
    return "no email bound to user"
}

// ErrEmailVerificationExpired 邮箱验证已过期
type ErrEmailVerificationExpired struct{}

func (e ErrEmailVerificationExpired) Error() string {
    return "email verification expired"
}

// ErrEmailNotVerified 邮箱未验证
type ErrEmailNotVerified struct{}

func (e ErrEmailNotVerified) Error() string {
    return "email not verified"
}

// StartEmailVerificationInput 开始邮箱验证输入
type StartEmailVerificationInput struct {
    UserID string
    Email  string // 可选，如果为空使用默认邮箱
}

// StartEmailVerificationOutput 开始邮箱验证输出
type StartEmailVerificationOutput struct {
    Token string `json:"token"`
    Email string `json:"email"`
}

// StartEmailVerification 开始邮箱验证流程
// 检查用户邮箱绑定状态，发送 OTP，生成预验证 Token
func (s *Service) StartEmailVerification(ctx context.Context, input *StartEmailVerificationInput) (*StartEmailVerificationOutput, error) {
    var email string
    
    if input.Email != "" {
        // 使用用户提供的邮箱
        email = input.Email
    } else {
        // 获取用户默认邮箱
        var err error
        email, err = s.getUserDefaultEmail(ctx, input.UserID)
        if err != nil {
            return nil, err
        }
    }
    
    // 生成并发送 OTP
    code, err := s.OTPCodeService.GenerateOTP(ctx, otp.KindOOBOTP(email), email, otp.FormCode, &otp.GenerateOptions{
        TTL: 5 * time.Minute, // OTP 5分钟有效
    })
    if err != nil {
        return nil, err
    }
    
    err = s.OTPSender.Send(ctx, otp.SendOptions{
        Target:  email,
        OTP:     code,
        Purpose: otp.PurposeOOBOTP,
    })
    if err != nil {
        return nil, err
    }
    
    // 生成预验证 Token（TTL=10分钟）
    token, err := s.Store.GenerateToken(ctx, GenerateTokenOptions{
        UserID:                     input.UserID,
        AuthenticatorOOBOTPChannel: model.AuthenticatorOOBChannelEmail,
        AuthenticatorOOBOTPTarget:  email,
        AuthenticatorOOBOTPVerified: false,
    })
    if err != nil {
        return nil, err
    }
    
    return &StartEmailVerificationOutput{
        Token: token,
        Email: email,
    }, nil
}

// getUserDefaultEmail 获取用户默认邮箱
func (s *Service) getUserDefaultEmail(ctx context.Context, userID string) (string, error) {
    identities, err := s.Identities.ListByUser(ctx, userID)
    if err != nil {
        return "", err
    }
    
    for _, iden := range identities {
        if iden.Type == model.IdentityTypeLoginID && iden.LoginID != nil {
            if iden.LoginID.LoginIDType == config.LoginIDKeyTypeEmail {
                return iden.LoginID.LoginID, nil
            }
        }
    }
    
    return "", ErrNoEmailBound{}
}

// VerifyEmailOTPInput 验证邮箱 OTP 输入
type VerifyEmailOTPInput struct {
    Token string
    Code  string
}

// VerifyEmailOTPOutput 验证邮箱 OTP 输出
type VerifyEmailOTPOutput struct {
    Token     string    `json:"token"`
    Email     string    `json:"email"`
    ExpiresAt time.Time `json:"expires_at"`
}

// VerifyEmailOTP 验证邮箱 OTP，生成已验证 Token
func (s *Service) VerifyEmailOTP(ctx context.Context, input *VerifyEmailOTPInput) (*VerifyEmailOTPOutput, error) {
    // 获取 Token（不删除，因为需要验证后才更新状态）
    token, err := s.Store.GetToken(ctx, input.Token)
    if err != nil {
        return nil, err
    }
    
    // 验证 OTP
    target := token.Authenticator.OOBOTPTarget
    err = s.OTPCodeService.VerifyOTP(ctx, otp.KindOOBOTP(target), target, input.Code, nil)
    if err != nil {
        return nil, err
    }
    
    // 生成新的已验证 Token
    // 注意：这里可以缩短 TTL，例如 10 分钟
    newToken, err := s.Store.GenerateToken(ctx, GenerateTokenOptions{
        UserID:                     token.UserID,
        AuthenticatorOOBOTPChannel: token.Authenticator.OOBOTPChannel,
        AuthenticatorOOBOTPTarget:  target,
        AuthenticatorOOBOTPVerified: true, // 标记已验证
    })
    if err != nil {
        return nil, err
    }
    
    // 删除旧 Token
    _, _ = s.Store.ConsumeToken(ctx, input.Token)
    
    return &VerifyEmailOTPOutput{
        Token:     newToken,
        Email:     target,
        ExpiresAt: time.Now().Add(10 * time.Minute),
    }, nil
}

// ChangePasswordWithVerificationInput 带验证的密码修改输入
type ChangePasswordWithVerificationInput struct {
    Token       string
    NewPassword string
}

// ChangePasswordWithVerification 使用已验证 Token 修改密码
// Token 一次性使用，验证后即删除
func (s *Service) ChangePasswordWithVerification(ctx context.Context, resolvedSession session.ResolvedSession, input *ChangePasswordWithVerificationInput) error {
    // 消费 Token（一次性使用）
    token, err := s.Store.ConsumeToken(ctx, input.Token)
    if err != nil {
        return err
    }
    
    // 检查用户绑定
    err = token.CheckUser(resolvedSession.GetAuthenticationInfo().UserID)
    if err != nil {
        return err
    }
    
    // 检查验证状态
    if token.Authenticator == nil || !token.Authenticator.OOBOTPVerified {
        return ErrEmailNotVerified{}
    }
    
    // 可选：检查 Token 是否在有效期内（虽然 Redis TTL 会自动处理）
    if token.ExpireAt != nil && time.Now().After(*token.ExpireAt) {
        return ErrEmailVerificationExpired{}
    }
    
    // 执行密码修改
    // 注意：这里可以跳过旧密码验证，因为已经通过邮箱验证
    err = s.Database.WithTx(ctx, func(ctx context.Context) error {
        _, err := s.changePassword(ctx, resolvedSession, &changePasswordInput{
            Kind:        authenticator.KindPrimary,
            OldPassword: "", // 跳过旧密码检查
            NewPassword: input.NewPassword,
        })
        return err
    })
    
    return err
}
```

### 6.5 配置支持

**Token TTL 配置**：

```go:25:58:pkg/lib/accountmanagement/redis_store.go（修改 GenerateTokenOptions）
// GenerateTokenOptions 扩展支持自定义 TTL
type GenerateTokenOptions struct {
    UserID string
    
    // ... 其他字段 ...
    
    // OOB OTP
    AuthenticatorOOBOTPChannel  model.AuthenticatorOOBChannel
    AuthenticatorOOBOTPTarget   string
    AuthenticatorOOBOTPVerified bool
    
    // 新增：自定义 TTL
    CustomTTL *time.Duration
}

func (s *RedisStore) GenerateToken(ctx context.Context, options GenerateTokenOptions) (string, error) {
    // ...
    ttl := duration.UserInteraction // 默认 20分钟
    if options.CustomTTL != nil {
        ttl = *options.CustomTTL
    }
    expireAt := now.Add(ttl)
    // ...
}
```

---

## 七、Authgear 当前能力确认

### 7.1 已支持的特性


| 特性          | 支持情况 | 说明                             |
| ----------- | ---- | ------------------------------ |
| 邮箱 OTP 发送   | ✅ 支持 | `OTPSender` 和 `OTPCodeService` |
| OTP 验证      | ✅ 支持 | `OTPCodeService.VerifyOTP`     |
| 临时 Token    | ✅ 支持 | AccountManagement Token        |
| Token TTL   | ✅ 支持 | Redis TTL，可配置                  |
| 检查邮箱绑定      | ✅ 支持 | `Identities.ListByUser` + 过滤   |
| 检查邮箱验证      | ✅ 支持 | `Verification.GetClaimStatus`  |
| Reauth Flow | ✅ 支持 | `IntentReauthFlow`             |


### 7.2 需要新增的特性


| 特性             | 实现方式                      | 工作量 |
| -------------- | ------------------------- | --- |
| 邮箱验证专用 Token   | 扩展 `GenerateTokenOptions` | 小   |
| 验证状态标记         | 使用 `OOBOTPVerified` 字段    | 小   |
| 带验证的密码修改 API   | 新增 Service 方法             | 中   |
| Web Handler 更新 | 添加新的 HTTP 路由              | 中   |
| 前端页面           | 邮箱验证页面、密码修改页面             | 中   |


### 7.3 不支持的场景


| 场景                    | 原因          | 解决方案          |
| --------------------- | ----------- | ------------- |
| 原生"一次有效 TOTP"         | 无内置机制       | 使用 Token 方案替代 |
| 临时 Verification Claim | Claim 无过期时间 | 使用 Token 方案替代 |
| 跨 Flow 状态共享           | Flow 间独立    | 使用 Token 传递状态 |


---

## 八、实施建议

### 8.1 实施步骤

```
Phase 1: 核心服务开发（1-2 天）
├── 1.1 扩展 GenerateTokenOptions 支持自定义 TTL
├── 1.2 实现 service_password_verification.go
│   ├── StartEmailVerification
│   ├── VerifyEmailOTP
│   └── ChangePasswordWithVerification
└── 1.3 添加单元测试

Phase 2: API 层开发（1 天）
├── 2.1 添加 HTTP Handler
│   ├── start_email_verification.go
│   ├── verify_email_otp.go
│   └── change_password_with_verification.go
└── 2.2 添加路由配置

Phase 3: 前端开发（2-3 天）
├── 3.1 邮箱验证页面
├── 3.2 修改密码页面（集成验证）
└── 3.3 流程状态管理

Phase 4: 集成测试（1 天）
├── 4.1 E2E 测试
└── 4.2 安全审计
```

### 8.2 安全考虑

1. **Token 安全**
  - Token 使用随机字符串（32位）
  - Redis 存储使用 SHA256 哈希作为 Key
  - Token 一次性使用（ConsumeToken 后删除）
  - TTL 机制防止长期有效
2. **OTP 安全**
  - OTP 6 位数字
  - 5 分钟有效期
  - 失败次数限制（Rate Limit）
  - 防重放攻击
3. **密码修改安全**
  - 新密码需要符合密码策略
  - 建议保留旧密码验证作为可选安全层
  - 操作日志记录

### 8.3 兼容性考虑

1. **向后兼容**
  - 现有修改密码 API 保持不变
  - 新增 API 作为可选增强
  - 通过配置启用/禁用 MFA 要求
2. **配置化**
  ```yaml
   # authentication_flow.yaml
   account_management:
     password_change:
       require_email_verification: true
       verification_ttl_minutes: 10
       allow_skip_old_password: true  # 验证邮箱后可跳过旧密码
  ```

---

## 九、结论

Authgear Server **支持** 实现"修改密码时的邮箱 2FA 流程"，推荐采用 **方案 A（AccountManagement Token）**：

1. **技术可行性**：完全基于现有机制，无需大规模改造
2. **安全性**：Token 一次性使用 + TTL + SHA256 存储，安全性高
3. **实现复杂度**：新增一个 Service 文件和少量 API，工作量可控（约 5-7 天）
4. **灵活性**：可配置 TTL、是否跳过旧密码等参数
5. **维护性**：代码集中，逻辑清晰，易于维护

**核心流程**：

```
开始验证 → 检查/绑定邮箱 → 发送 OTP → 验证 OTP → 生成 Token(10分钟) 
→ 提交新密码 + Token → 验证 Token → 修改密码 → 删除 Token
```

---

## 十、参考代码路径


| 功能                   | 文件路径                                                                         |
| -------------------- | ---------------------------------------------------------------------------- |
| Token 系统             | `pkg/lib/accountmanagement/token.go`                                         |
| Token 存储             | `pkg/lib/accountmanagement/redis_store.go`                                   |
| 密码修改服务               | `pkg/lib/accountmanagement/service_authenticator.go`                         |
| AccountManagement 服务 | `pkg/lib/accountmanagement/service.go`                                       |
| 修改密码 Handler         | `pkg/auth/handler/webapp/authflowv2/settings_change_password.go`             |
| OOB OTP 认证           | `pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go` |
| Reauth Flow          | `pkg/lib/authenticationflow/declarative/intent_reauth_flow.go`               |
| 验证服务                 | `pkg/lib/feature/verification/service.go`                                    |
| OTP 服务               | `pkg/lib/authn/otp/service.go`                                               |
| Flow 配置              | `pkg/lib/config/authentication_flow.go`                                      |


---

*报告生成时间: 2026-05-18*
*基于 Authgear Server 代码库调研*