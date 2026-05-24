# Authgear Authenticator 系统深入分析

## 1. Authenticator 系统概述

Authenticator 系统用于验证用户身份。与 Identity 系统不同，Authenticator 关注的是"如何证明你是你"，而不是"你是谁"。

### 1.1 支持的 Authenticator 类型

```go
// pkg/api/model/authenticator.go
type AuthenticatorType string

const (
    AuthenticatorTypePassword AuthenticatorType = "password"      // 密码
    AuthenticatorTypePasskey  AuthenticatorType = "passkey"       // Passkey/WebAuthn
    AuthenticatorTypeTOTP     AuthenticatorType = "totp"          // 时间型一次性密码
    AuthenticatorTypeOOBEmail AuthenticatorType = "oob_otp_email" // 邮件验证码
    AuthenticatorTypeOOBSMS   AuthenticatorType = "oob_otp_sms"   // 短信验证码
)
```

### 1.2 Authenticator 分类

```go
// pkg/lib/authn/authenticator/kinds.go
type Kind string

const (
    KindPrimary   Kind = "primary"     // 主认证器（首次认证）
    KindSecondary Kind = "secondary"   // 二次认证器（MFA）
)
```

**主认证器类型：**
- Password
- Passkey
- OOB OTP (Email/SMS)

**二次认证器类型：**
- TOTP
- OOB OTP (Email/SMS)
- Password（作为二次认证）

### 1.3 Authenticator 数据结构

```go
// pkg/lib/authn/authenticator/info.go
type Info struct {
    ID        string                  `json:"id"`
    UserID    string                  `json:"user_id"`
    CreatedAt time.Time               `json:"created_at"`
    UpdatedAt time.Time               `json:"updated_at"`
    Type      model.AuthenticatorType `json:"type"`
    IsDefault bool                    `json:"is_default"`
    Kind      Kind                    `json:"kind"`

    Password *Password `json:"password,omitempty"`
    Passkey  *Passkey  `json:"passkey,omitempty"`
    TOTP     *TOTP     `json:"totp,omitempty"`
    OOBOTP   *OOBOTP   `json:"oobotp,omitempty"`
}
```

## 2. Authenticator 系统架构

### 2.1 三层架构

```
┌─────────────────────────────────────────────────────────────┐
│                     Service Layer                          │
│           (pkg/lib/authn/authenticator/service)             │
│  - 统一的 Authenticator 管理接口                            │
│  - 协调不同类型 Authenticator 的操作                         │
│  - 验证逻辑整合                                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Provider Layer                           │
│  (pkg/lib/authn/authenticator/{password,passkey,totp,oob})  │
│  - 每种 Authenticator 类型的具体实现                         │
│  - 创建、认证、更新、删除操作                                 │
│  - 类型特定的验证逻辑                                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Storage Layer                           │
│                   (Store/Repository)                        │
│  - 凭证数据持久化                                           │
│  - 敏感信息加密存储                                         │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Service 层设计

```go
// pkg/lib/authn/authenticator/service/service.go
type Service struct {
    ReadOnlyService
    Store          *Store
    Config         *config.AppConfig
    OTPCodeService OTPCodeService
    RateLimits     RateLimits
    Lockout        Lockout
}

// ReadOnlyService 提供只读查询能力
type ReadOnlyService struct {
    Store   *Store
    Config  *config.AppConfig
    Password PasswordAuthenticatorProvider
    Passkey  PasskeyAuthenticatorProvider
    TOTP     TOTPAuthenticatorProvider
    OOBOTP   OOBOTPAuthenticatorProvider
}
```

### 2.3 Provider 接口定义

以 Password Provider 为例：

```go
// pkg/lib/authn/authenticator/password/provider.go
type Provider struct {
    Store           *Store
    Config          *config.AuthenticatorPasswordConfig
    Clock           clock.Clock
    PasswordHistory *HistoryStore
    PasswordChecker *Checker
    Expiry          *Expiry
    Housekeeper     *Housekeeper
}

func (p *Provider) New(ctx context.Context, id string, userID string, passwordSpec *authenticator.PasswordSpec, isDefault bool, kind string) (*authenticator.Password, error)
func (p *Provider) Get(ctx context.Context, userID string, id string) (*authenticator.Password, error)
func (p *Provider) GetMany(ctx context.Context, ids []string) ([]*authenticator.Password, error)
func (p *Provider) List(ctx context.Context, userID string) ([]*authenticator.Password, error)
func (p *Provider) Create(ctx context.Context, a *authenticator.Password) error
func (p *Provider) Update(ctx context.Context, a *authenticator.Password) error
func (p *Provider) Delete(ctx context.Context, a *authenticator.Password) error
func (p *Provider) Authenticate(ctx context.Context, a *authenticator.Password, password string) (verifyResult *VerifyResult, err error)
func (p *Provider) UpdatePassword(ctx context.Context, a *authenticator.Password, options *UpdatePasswordOptions) (bool, *authenticator.Password, error)
```

## 3. Authenticator Spec 模式

### 3.1 Spec 结构

```go
// pkg/lib/authn/authenticator/spec.go
type Spec struct {
    UserID    string                  `json:"user_id,omitempty"`
    Type      model.AuthenticatorType `json:"type,omitempty"`
    IsDefault bool                    `json:"is_default,omitempty"`
    Kind      Kind                    `json:"kind,omitempty"`

    Password *PasswordSpec `json:"password,omitempty"`
    Passkey  *PasskeySpec  `json:"passkey,omitempty"`
    TOTP     *TOTPSpec     `json:"totp,omitempty"`
    OOBOTP   *OOBOTPSpec   `json:"oobotp,omitempty"`
}
```

### 3.2 具体 Spec 示例

**Password Spec：**

```go
// pkg/lib/authn/authenticator/password_spec.go
type PasswordSpec struct {
    PlainPassword string     `json:"plain_password,omitempty"` // 明文密码（输入）
    PasswordHash  string     `json:"password_hash,omitempty"`  // 密码哈希（输入）
    ExpireAfter   *time.Time `json:"expire_after,omitempty"`   // 过期时间
}
```

**Passkey Spec：**

```go
// pkg/lib/authn/authenticator/passkey_spec.go
type PasskeySpec struct {
    AttestationResponse []byte `json:"attestation_response,omitempty"` // 创建凭证
    AssertionResponse   []byte `json:"assertion_response,omitempty"`   // 验证断言
}
```

**TOTP Spec：**

```go
// pkg/lib/authn/authenticator/totp_spec.go
type TOTPSpec struct {
    Secret string `json:"secret,omitempty"` // TOTP 密钥
}
```

**OOB OTP Spec：**

```go
// pkg/lib/authn/authenticator/oobotp_spec.go
type OOBOTPSpec struct {
    Email string `json:"email,omitempty"` // 邮箱目标
    Phone string `json:"phone,omitempty"` // 手机目标
}
```

## 4. Authenticator 核心方法

### 4.1 Info 结构的核心方法

```go
// 转换为引用
func (i *Info) ToRef() *Ref

// 转换为 API Model
func (i *Info) ToModel() model.Authenticator

// 转换为 Authentication 类型（用于 Flow）
func (i *Info) ToAuthentication() model.AuthenticationFlowAuthentication

// 判断两个认证器是否相等
func (i *Info) Equal(that *Info) bool

// 获取公开 Claims
func (i *Info) ToPublicClaims() map[string]interface{}

// 获取标准 Claims（如 email, phone）
func (i *Info) StandardClaims() map[model.ClaimName]string

// 判断是否支持 MFA
func (i *Info) CanHaveMFA() bool

// 判断是否是独立的（不依赖 Identity）
func (i *Info) IsIndependent() bool

// 判断是否依赖特定 Identity
func (i *Info) IsDependentOf(iden *identity.Info) bool

// 判断是否适用于特定 Identity
func (i *Info) IsApplicableTo(iden *identity.Info) bool

// 更新 UserID
func (i *Info) UpdateUserID(newUserID string) *Info
```

### 4.2 核心验证流程

```go
// pkg/lib/authn/authenticator/service/service.go
func (s *Service) VerifyOneWithSpec(
    ctx context.Context,
    userID string,
    authenticatorType model.AuthenticatorType,
    infos []*authenticator.Info,
    spec *authenticator.Spec,
    options *VerifyOptions) (info *authenticator.Info, verifyResult *VerifyResult, err error) {
    
    // 1. 检查限流
    r, err := s.RateLimits.Reserve(ctx, userID, authenticatorType)
    // ...
    
    // 2. 检查账户锁定
    err = s.Lockout.Check(ctx, userID)
    // ...
    
    // 3. 遍历尝试验证
    for _, thisInfo := range infos {
        verifyResult, err = s.verifyWithSpec(ctx, thisInfo, spec, options)
        if errors.Is(err, api.ErrInvalidCredentials) {
            continue
        }
        // ...
    }
    
    // 4. 处理失败（锁定）
    if errors.Is(err, api.ErrInvalidCredentials) {
        s.Lockout.MakeAttempt(ctx, userID, authenticatorType)
    }
}
```

## 5. 新增 Authenticator 类型的步骤

### 5.1 定义类型常量

在 `pkg/api/model/authenticator.go` 中添加：

```go
const (
    AuthenticatorTypePassword AuthenticatorType = "password"
    // ... 现有类型 ...
    AuthenticatorTypeCustom   AuthenticatorType = "custom"  // 新增类型
)
```

### 5.2 实现 Model 结构

在 `pkg/lib/authn/authenticator/` 目录下创建：

```go
// custom_authenticator.go
type Custom struct {
    ID        string    `json:"id"`
    CreatedAt time.Time `json:"created_at"`
    UpdatedAt time.Time `json:"updated_at"`
    UserID    string    `json:"user_id"`
    IsDefault bool      `json:"is_default"`
    Kind      Kind      `json:"kind"`
    
    // 自定义字段
    CustomSecret string `json:"custom_secret"`
    CustomData   string `json:"custom_data"`
    // ...
}

func (a *Custom) ToInfo() *Info {
    return &Info{
        ID:        a.ID,
        UserID:    a.UserID,
        CreatedAt: a.CreatedAt,
        UpdatedAt: a.UpdatedAt,
        Type:      model.AuthenticatorTypeCustom,
        IsDefault: a.IsDefault,
        Kind:      a.Kind,
        Custom:    a,
    }
}
```

### 5.3 定义 Spec

```go
// custom_spec.go
type CustomSpec struct {
    PlainSecret string `json:"plain_secret,omitempty"` // 创建时的明文
    SecretHash  string `json:"secret_hash,omitempty"`  // 验证时的哈希
    CustomData  string `json:"custom_data,omitempty"`
}
```

### 5.4 扩展 Info 结构

```go
// 在 info.go 中添加
type Info struct {
    // ... 现有字段 ...
    Custom *Custom `json:"custom,omitempty"`
}

// 在 Equal 方法中添加 case
func (i *Info) Equal(that *Info) bool {
    // ... 类型检查 ...
    switch i.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        // 定义相等性判断逻辑
        return i.Custom.CustomSecret == that.Custom.CustomSecret
    }
}

// 在 ToPublicClaims 中添加 case
func (i *Info) ToPublicClaims() map[string]interface{} {
    switch i.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        claims[AuthenticatorClaimCustomData] = i.Custom.CustomData
    }
}

// 在 StandardClaims 中添加 case
func (i *Info) StandardClaims() map[model.ClaimName]string {
    switch i.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        // 返回关联的标准 Claims
    }
}

// 在 IsIndependent 中添加 case
func (i *Info) IsIndependent() bool {
    switch i.Kind {
    case KindPrimary:
        switch i.Type {
        // ... 现有 case ...
        case model.AuthenticatorTypeCustom:
            return true // 或 false，取决于是否独立
        }
    }
}

// 在 IsDependentOf 中添加 case
func (i *Info) IsDependentOf(iden *identity.Info) bool {
    // ... 现有逻辑 ...
    // 添加对新类型的依赖判断
}

// 在 ToAuthentication 中添加 case
func (i *Info) ToAuthentication() model.AuthenticationFlowAuthentication {
    switch i.Kind {
    case KindPrimary:
        switch i.Type {
        // ... 现有 case ...
        case model.AuthenticatorTypeCustom:
            authn = model.AuthenticationFlowAuthenticationPrimaryCustom
        }
    case KindSecondary:
        switch i.Type {
        // ... 现有 case ...
        case model.AuthenticatorTypeCustom:
            authn = model.AuthenticationFlowAuthenticationSecondaryCustom
        }
    }
}
```

### 5.5 扩展 Spec 结构

```go
// 在 spec.go 中添加
type Spec struct {
    // ... 现有字段 ...
    Custom *CustomSpec `json:"custom,omitempty"`
}
```

### 5.6 实现 Provider

```go
// custom/provider.go
type Provider struct {
    Store  *Store
    Clock  clock.Clock
    Config *config.AuthenticatorCustomConfig
}

func (p *Provider) New(
    ctx context.Context,
    id string,
    userID string,
    customSpec *authenticator.CustomSpec,
    isDefault bool,
    kind string,
) (*authenticator.Custom, error) {
    if id == "" {
        id = uuid.New()
    }
    
    authen := &authenticator.Custom{
        ID:         id,
        UserID:     userID,
        IsDefault:  isDefault,
        Kind:       kind,
        CustomData: customSpec.CustomData,
    }
    
    // 处理凭证创建
    if customSpec.PlainSecret != "" {
        hash, err := p.hashSecret(customSpec.PlainSecret)
        if err != nil {
            return nil, err
        }
        authen.CustomSecret = hash
    } else if customSpec.SecretHash != "" {
        authen.CustomSecret = customSpec.SecretHash
    }
    
    return authen, nil
}

func (p *Provider) Authenticate(ctx context.Context, a *authenticator.Custom, secret string) error {
    // 实现验证逻辑
    return compareSecret(secret, a.CustomSecret)
}

func (p *Provider) Get(ctx context.Context, userID, id string) (*authenticator.Custom, error) {
    return p.Store.Get(ctx, userID, id)
}

func (p *Provider) GetMany(ctx context.Context, ids []string) ([]*authenticator.Custom, error) {
    return p.Store.GetMany(ctx, ids)
}

func (p *Provider) List(ctx context.Context, userID string) ([]*authenticator.Custom, error) {
    return p.Store.List(ctx, userID)
}

func (p *Provider) Create(ctx context.Context, a *authenticator.Custom) error {
    now := p.Clock.NowUTC()
    a.CreatedAt = now
    a.UpdatedAt = now
    return p.Store.Create(ctx, a)
}

func (p *Provider) Update(ctx context.Context, a *authenticator.Custom) error {
    now := p.Clock.NowUTC()
    a.UpdatedAt = now
    return p.Store.Update(ctx, a)
}

func (p *Provider) Delete(ctx context.Context, a *authenticator.Custom) error {
    return p.Store.Delete(ctx, a)
}
```

### 5.7 定义 Provider 接口

```go
// 在 service/service.go 中添加
type CustomAuthenticatorProvider interface {
    New(ctx context.Context, id string, userID string, customSpec *authenticator.CustomSpec, isDefault bool, kind string) (*authenticator.Custom, error)
    Authenticate(ctx context.Context, a *authenticator.Custom, secret string) error
    
    Get(ctx context.Context, userID, id string) (*authenticator.Custom, error)
    GetMany(ctx context.Context, ids []string) ([]*authenticator.Custom, error)
    List(ctx context.Context, userID string) ([]*authenticator.Custom, error)
    Create(ctx context.Context, a *authenticator.Custom) error
    Update(ctx context.Context, a *authenticator.Custom) error
    Delete(ctx context.Context, a *authenticator.Custom) error
}

// 在 ReadOnlyService 结构中添加
type ReadOnlyService struct {
    // ... 现有字段 ...
    Custom CustomAuthenticatorProvider
}
```

### 5.8 在 Service 层集成

```go
// 在 service/service.go 的各个方法中添加 case

func (s *Service) Get(ctx context.Context, id string) (*authenticator.Info, error) {
    // ...
    switch ref.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        c, err := s.Custom.Get(ctx, ref.UserID, id)
        if err != nil {
            return nil, err
        }
        return c.ToInfo(), nil
    }
}

func (s *Service) NewWithAuthenticatorID(ctx context.Context, authenticatorID string, spec *authenticator.Spec) (*authenticator.Info, error) {
    switch spec.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        c, err := s.Custom.New(ctx, authenticatorID, spec.UserID, spec.Custom, spec.IsDefault, string(spec.Kind))
        if err != nil {
            return nil, err
        }
        return c.ToInfo(), nil
    }
}

func (s *Service) Create(ctx context.Context, info *authenticator.Info) error {
    switch info.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        a := info.Custom
        if err := s.Custom.Create(ctx, a); err != nil {
            return err
        }
        *info = *a.ToInfo()
    }
}

func (s *Service) Delete(ctx context.Context, info *authenticator.Info) error {
    switch info.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        a := info.Custom
        if err := s.Custom.Delete(ctx, a); err != nil {
            return err
        }
    }
}

func (s *Service) verifyWithSpec(ctx context.Context, info *authenticator.Info, spec *authenticator.Spec, options *VerifyOptions) (verifyResult *VerifyResult, err error) {
    switch info.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        secret := spec.Custom.PlainSecret
        a := info.Custom
        if err := s.Custom.Authenticate(ctx, a, secret); err != nil {
            err = api.ErrInvalidCredentials
            return nil, err
        }
        *info = *a.ToInfo()
        return
    }
}
```

### 5.9 实现 Store

```go
// custom/store.go
type Store struct {
    SQLBuilder  *db.SQLBuilder
    SQLExecutor *db.SQLExecutor
}

func (s *Store) Get(ctx context.Context, userID, id string) (*authenticator.Custom, error) {
    // 实现查询逻辑
}

func (s *Store) GetMany(ctx context.Context, ids []string) ([]*authenticator.Custom, error) {
    // 实现批量查询逻辑
}

func (s *Store) List(ctx context.Context, userID string) ([]*authenticator.Custom, error) {
    // 实现列表查询逻辑
}

func (s *Store) Create(ctx context.Context, a *authenticator.Custom) error {
    // 实现创建逻辑，敏感信息应加密存储
}

func (s *Store) Update(ctx context.Context, a *authenticator.Custom) error {
    // 实现更新逻辑
}

func (s *Store) Delete(ctx context.Context, a *authenticator.Custom) error {
    // 实现删除逻辑
}
```

### 5.10 配置数据库表

添加新的数据库 migration：

```sql
CREATE TABLE _auth_authenticator_custom (
    id VARCHAR(32) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    kind VARCHAR(32) NOT NULL, -- 'primary' or 'secondary'
    custom_secret VARCHAR(255) NOT NULL, -- 加密存储
    custom_data VARCHAR(255),
    
    CONSTRAINT fk_user_id FOREIGN KEY (user_id) REFERENCES _auth_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_id_kind ON _auth_authenticator_custom(user_id, kind);
```

## 6. Identity 与 Authenticator 的关系

### 6.1 依赖关系

```
┌─────────────────────────────────────────────────────────────┐
│                       Identity                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ LoginID  │ │  OAuth   │ │ Passkey  │ │   ...    │       │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └──────────┘       │
│       │            │            │                           │
│       └────────────┴────────────┘                           │
│                    │                                        │
│                    ▼                                        │
│         Primary Authenticator Types                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                    │
│  │ Password │ │ Passkey  │ │ OOB OTP  │                    │
│  └──────────┘ └──────────┘ └──────────┘                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Authenticator                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ Password │ │ Passkey  │ │  TOTP    │ │ OOB OTP  │       │
│  │ (Primary│ │ (Primary/│ │(Secondary│ │(Primary/ │       │
│  │/Secondary)│ │Secondary)│ │         │ │Secondary)│       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 依赖判断逻辑

```go
// pkg/lib/authn/authenticator/info.go
func (i *Info) IsDependentOf(iden *identity.Info) bool {
    // 主 OOB OTP 认证器依赖于 LoginID Identity
    if i.Kind == KindPrimary && (i.Type == model.AuthenticatorTypeOOBEmail || i.Type == model.AuthenticatorTypeOOBSMS) {
        identityClaims := iden.IdentityAwareStandardClaims()
        for k, v := range i.StandardClaims() {
            if iden.Type == model.IdentityTypeLoginID && identityClaims[k] == v {
                return true
            }
        }
    }

    // 主 Passkey 认证器依赖于 Passkey Identity
    if i.Kind == KindPrimary && i.Type == model.AuthenticatorTypePasskey {
        if iden.Type == model.IdentityTypePasskey {
            if i.Passkey.CredentialID == iden.Passkey.CredentialID {
                return true
            }
        }
    }

    return false
}
```

## 7. 关键设计原则

1. **凭证安全**：所有凭证必须加密存储，敏感字段（如密码哈希）不应明文返回
2. **限流锁定**：验证失败需要支持限流和账户锁定机制
3. **分层架构**：Service → Provider → Store，职责清晰
4. **统一接口**：通过 Info 结构统一所有 Authenticator 类型
5. **多因素支持**：区分 Primary 和 Secondary，支持 MFA 场景
6. **与 Identity 解耦**：Authenticator 可以独立于 Identity 存在
