# 新增 Identity、Authenticator 和 Flow 类型完整指南

## 概述

本指南详细介绍如何在 Authgear 中添加新的 Identity 类型、Authenticator 类型和 Flow 类型。这三种组件相互配合，构成完整的认证系统。

## 整体架构关系

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    Flow 层                                          │
│                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │  IntentCustomFlow                                                              │ │
│  │       │                                                                        │ │
│  │       ▼                                                                        │ │
│  │  IntentCustomFlowSteps                                                         │ │
│  │       │                                                                        │ │
│  │       ├── IntentCustomFlowStepIdentify ──► IntentUseIdentityCustom ──► Node   │ │
│  │       │                                                                        │ │
│  │       └── IntentCustomFlowStepAuthenticate ──► IntentUseAuthenticatorCustom  │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                                │
│                                    │ 调用                                            │
│                                    ▼                                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                Service 层                                           │
│                                                                                     │
│  ┌─────────────────────────────────┐  ┌─────────────────────────────────────┐      │
│  │     Identity Service            │  │      Authenticator Service          │      │
│  │  ┌─────────────────────────┐   │  │  ┌─────────────────────────────┐   │      │
│  │  │   Custom Provider         │   │  │  │     Custom Provider         │   │      │
│  │  │  - New()                  │   │  │  │  - New()                    │   │      │
│  │  │  - Get/Create/Delete      │   │  │  │  - Authenticate()           │   │      │
│  │  │  - GetByCustomField     │   │  │  │  - Get/Create/Delete        │   │      │
│  │  └─────────────────────────┘   │  │  └─────────────────────────────┘   │      │
│  │            │                   │  │            │                       │      │
│  │            ▼                   │  │            ▼                       │      │
│  │  ┌─────────────────────────┐   │  │  ┌─────────────────────────────┐   │      │
│  │  │   Custom Store          │   │  │  │     Custom Store            │   │      │
│  │  │  - _auth_identity_custom│   │  │  │  - _auth_authenticator_custom│   │      │
│  │  └─────────────────────────┘   │  │  └─────────────────────────────┘   │      │
│  └─────────────────────────────────┘  └─────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

## 第一部分：新增 Identity 类型

### 1.1 定义类型常量

```go
// pkg/api/model/identity.go

type IdentityType string

const (
    IdentityTypeLoginID   IdentityType = "login_id"
    IdentityTypeOAuth     IdentityType = "oauth"
    IdentityTypeAnonymous IdentityType = "anonymous"
    IdentityTypeBiometric IdentityType = "biometric"
    IdentityTypePasskey   IdentityType = "passkey"
    IdentityTypeSIWE      IdentityType = "siwe"
    IdentityTypeLDAP      IdentityType = "ldap"
    IdentityTypeCustom    IdentityType = "custom"  // 新增
)
```

### 1.2 实现 Identity 模型

```go
// pkg/lib/authn/identity/custom_identity.go

package identity

import (
    "time"
    "github.com/authgear/authgear-server/pkg/api/model"
)

// Custom Identity 数据结构
type Custom struct {
    ID        string    `json:"id"`
    CreatedAt time.Time `json:"created_at"`
    UpdatedAt time.Time `json:"updated_at"`
    UserID    string    `json:"user_id"`
    
    // 自定义字段
    ProviderID    string `json:"provider_id"`     // 提供商ID
    ExternalID    string `json:"external_id"`     // 外部ID
    DisplayName   string `json:"display_name"`    // 显示名称
    Email         string `json:"email"`           // 关联邮箱
    
    // 标准 Claims
    Claims map[string]interface{} `json:"claims,omitempty"`
}

// ToInfo 转换为统一的 Info 结构
func (i *Custom) ToInfo() *Info {
    return &Info{
        ID:        i.ID,
        UserID:    i.UserID,
        CreatedAt: i.CreatedAt,
        UpdatedAt: i.UpdatedAt,
        Type:      model.IdentityTypeCustom,
        Custom:    i,
    }
}

// IdentityAwareStandardClaims 返回用于账户关联的 Claims
// 这些 Claims 用于识别可能属于同一用户的不同 Identity
func (i *Custom) IdentityAwareStandardClaims() map[model.ClaimName]string {
    claims := map[model.ClaimName]string{}
    
    // 如果有邮箱，用作关联依据
    if i.Email != "" {
        claims[model.ClaimEmail] = i.Email
    }
    
    // 提供商ID + 外部ID 的组合也是唯一标识
    // 但这个通常不用于跨提供商关联
    
    return claims
}

// AllStandardClaims 返回所有标准 Claims
func (i *Custom) AllStandardClaims() map[string]interface{} {
    if i.Claims != nil {
        return i.Claims
    }
    return map[string]interface{}{}
}
```

### 1.3 定义 Spec

```go
// pkg/lib/authn/identity/custom_spec.go

package identity

type CustomSpec struct {
    ProviderID  string                 `json:"provider_id"`
    ExternalID  string                 `json:"external_id"`
    DisplayName string                 `json:"display_name"`
    Email       string                 `json:"email"`
    Claims      map[string]interface{} `json:"claims,omitempty"`
}
```

### 1.4 扩展 Info 结构

```go
// pkg/lib/authn/identity/info.go

// 添加字段
type Info struct {
    // ... 现有字段 ...
    Custom *Custom `json:"custom,omitempty"`
}

// 在 ToSpec() 中添加 case
case model.IdentityTypeCustom:
    return Spec{
        Type: i.Type,
        Custom: &CustomSpec{
            ProviderID:  i.Custom.ProviderID,
            ExternalID:  i.Custom.ExternalID,
            DisplayName: i.Custom.DisplayName,
            Email:       i.Custom.Email,
            Claims:      i.Custom.Claims,
        },
    }

// 在 ToModel() 中添加 case
case model.IdentityTypeCustom:
    claims[IdentityClaimCustomProviderID] = i.Custom.ProviderID
    claims[IdentityClaimCustomExternalID] = i.Custom.ExternalID
    claims[IdentityClaimCustomDisplayName] = i.Custom.DisplayName
    if i.Custom.Email != "" {
        claims[model.ClaimEmail] = i.Custom.Email
    }
    for k, v := range i.Custom.Claims {
        claims[k] = v
    }

// 在 DisplayID() 中添加 case
case model.IdentityTypeCustom:
    if i.Custom.DisplayName != "" {
        return i.Custom.DisplayName
    }
    if i.Custom.Email != "" {
        return i.Custom.Email
    }
    return i.Custom.ExternalID

// 在 IdentityAwareStandardClaims() 中添加 case
case model.IdentityTypeCustom:
    return i.Custom.IdentityAwareStandardClaims()

// 在 AllStandardClaims() 中添加 case
case model.IdentityTypeCustom:
    return i.Custom.AllStandardClaims()

// 在 PrimaryAuthenticatorTypes() 中添加 case (在 model/identity.go)
func (t IdentityType) PrimaryAuthenticatorTypes(loginIDKeyType LoginIDKeyType) []AuthenticatorType {
    switch t {
    // ... 现有 case ...
    case IdentityTypeCustom:
        // Custom Identity 可以使用的认证方式
        return []AuthenticatorType{
            AuthenticatorTypePassword,
            AuthenticatorTypeCustom,  // 专用认证器
        }
    }
}

// 在 ToIdentification() 中添加 case
case model.IdentityTypeCustom:
    return model.AuthenticationFlowIdentificationCustom
```

### 1.5 扩展 Spec 结构

```go
// pkg/lib/authn/identity/spec.go

type Spec struct {
    // ... 现有字段 ...
    Custom *CustomSpec `json:"custom,omitempty"`
}
```

### 1.6 定义 Claim Key 常量

```go
// pkg/lib/authn/identity/claim_key.go

const (
    // ... 现有常量 ...
    IdentityClaimCustomProviderID  = "https://authgear.com/claims/custom/provider_id"
    IdentityClaimCustomExternalID  = "https://authgear.com/claims/custom/external_id"
    IdentityClaimCustomDisplayName = "https://authgear.com/claims/custom/display_name"
)
```

### 1.7 实现 Provider

```go
// pkg/lib/authn/identity/custom/provider.go

package custom

import (
    "context"
    "github.com/authgear/authgear-server/pkg/lib/authn/identity"
    "github.com/authgear/authgear-server/pkg/util/clock"
    "github.com/authgear/authgear-server/pkg/util/uuid"
)

type Provider struct {
    Store *Store
    Clock clock.Clock
}

func (p *Provider) New(
    userID string,
    spec *identity.CustomSpec,
) *identity.Custom {
    return &identity.Custom{
        ID:          uuid.New(),
        UserID:      userID,
        ProviderID:  spec.ProviderID,
        ExternalID:  spec.ExternalID,
        DisplayName: spec.DisplayName,
        Email:       spec.Email,
        Claims:      spec.Claims,
    }
}

func (p *Provider) WithUpdate(
    iden *identity.Custom,
    spec *identity.CustomSpec,
) *identity.Custom {
    newIden := *iden
    newIden.DisplayName = spec.DisplayName
    newIden.Email = spec.Email
    newIden.Claims = spec.Claims
    return &newIden
}

func (p *Provider) Get(ctx context.Context, userID, id string) (*identity.Custom, error) {
    return p.Store.Get(ctx, userID, id)
}

func (p *Provider) GetMany(ctx context.Context, ids []string) ([]*identity.Custom, error) {
    return p.Store.GetMany(ctx, ids)
}

func (p *Provider) List(ctx context.Context, userID string) ([]*identity.Custom, error) {
    return p.Store.List(ctx, userID)
}

func (p *Provider) GetByProviderSubject(
    ctx context.Context,
    providerID string,
    externalID string,
) (*identity.Custom, error) {
    return p.Store.GetByProviderSubject(ctx, providerID, externalID)
}

func (p *Provider) ListByClaim(ctx context.Context, name string, value string) ([]*identity.Custom, error) {
    return p.Store.ListByClaim(ctx, name, value)
}

func (p *Provider) Create(ctx context.Context, i *identity.Custom) error {
    now := p.Clock.NowUTC()
    i.CreatedAt = now
    i.UpdatedAt = now
    return p.Store.Create(ctx, i)
}

func (p *Provider) Update(ctx context.Context, i *identity.Custom) error {
    now := p.Clock.NowUTC()
    i.UpdatedAt = now
    return p.Store.Update(ctx, i)
}

func (p *Provider) Delete(ctx context.Context, i *identity.Custom) error {
    return p.Store.Delete(ctx, i)
}
```

### 1.8 实现 Store

```go
// pkg/lib/authn/identity/custom/store.go

package custom

import (
    "context"
    "database/sql"
    "encoding/json"
    
    "github.com/authgear/authgear-server/pkg/lib/authn/identity"
    "github.com/authgear/authgear-server/pkg/lib/infra/db"
)

type Store struct {
    SQLBuilder  *db.SQLBuilder
    SQLExecutor *db.SQLExecutor
}

func (s *Store) Get(ctx context.Context, userID, id string) (*identity.Custom, error) {
    builder := s.SQLBuilder.
        Select(
            "id",
            "created_at",
            "updated_at",
            "user_id",
            "provider_id",
            "external_id",
            "display_name",
            "email",
            "claims",
        ).
        From(s.SQLBuilder.TableName("_auth_identity_custom")).
        Where("id = ?", id)
    
    row := s.SQLExecutor.QueryRowWith(builder)
    return s.scan(row)
}

func (s *Store) GetMany(ctx context.Context, ids []string) ([]*identity.Custom, error) {
    // 实现批量查询
}

func (s *Store) List(ctx context.Context, userID string) ([]*identity.Custom, error) {
    // 实现列表查询
}

func (s *Store) GetByProviderSubject(
    ctx context.Context,
    providerID string,
    externalID string,
) (*identity.Custom, error) {
    builder := s.SQLBuilder.
        Select("id", "created_at", "updated_at", "user_id", "provider_id", "external_id", "display_name", "email", "claims").
        From(s.SQLBuilder.TableName("_auth_identity_custom")).
        Where("provider_id = ? AND external_id = ?", providerID, externalID)
    
    row := s.SQLExecutor.QueryRowWith(builder)
    i, err := s.scan(row)
    if err != nil {
        if errors.Is(err, sql.ErrNoRows) {
            return nil, api.ErrIdentityNotFound
        }
        return nil, err
    }
    return i, nil
}

func (s *Store) ListByClaim(ctx context.Context, name string, value string) ([]*identity.Custom, error) {
    // 根据 email 等 claim 查询
    builder := s.SQLBuilder.
        Select("id", "created_at", "updated_at", "user_id", "provider_id", "external_id", "display_name", "email", "claims").
        From(s.SQLBuilder.TableName("_auth_identity_custom")).
        Where("email = ?", value)
    
    rows, err := s.SQLExecutor.QueryWith(builder)
    // ... 扫描结果
}

func (s *Store) Create(ctx context.Context, i *identity.Custom) error {
    claimsJSON, err := json.Marshal(i.Claims)
    if err != nil {
        return err
    }
    
    builder := s.SQLBuilder.
        Insert(s.SQLBuilder.TableName("_auth_identity_custom")).
        Columns(
            "id",
            "created_at",
            "updated_at",
            "user_id",
            "provider_id",
            "external_id",
            "display_name",
            "email",
            "claims",
        ).
        Values(
            i.ID,
            i.CreatedAt,
            i.UpdatedAt,
            i.UserID,
            i.ProviderID,
            i.ExternalID,
            i.DisplayName,
            i.Email,
            claimsJSON,
        )
    
    _, err = s.SQLExecutor.ExecWith(builder)
    return err
}

func (s *Store) Update(ctx context.Context, i *identity.Custom) error {
    // 实现更新逻辑
}

func (s *Store) Delete(ctx context.Context, i *identity.Custom) error {
    builder := s.SQLBuilder.
        Delete(s.SQLBuilder.TableName("_auth_identity_custom")).
        Where("id = ?", i.ID)
    
    _, err := s.SQLExecutor.ExecWith(builder)
    return err
}

func (s *Store) scan(scanner db.Scanner) (*identity.Custom, error) {
    i := &identity.Custom{}
    var claimsJSON []byte
    
    err := scanner.Scan(
        &i.ID,
        &i.CreatedAt,
        &i.UpdatedAt,
        &i.UserID,
        &i.ProviderID,
        &i.ExternalID,
        &i.DisplayName,
        &i.Email,
        &claimsJSON,
    )
    if err != nil {
        return nil, err
    }
    
    if len(claimsJSON) > 0 {
        err = json.Unmarshal(claimsJSON, &i.Claims)
        if err != nil {
            return nil, err
        }
    }
    
    return i, nil
}
```

### 1.9 数据库 Migration

```sql
-- migrations/20260101_add_custom_identity.sql

CREATE TABLE _auth_identity_custom (
    id VARCHAR(32) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    email VARCHAR(255),
    claims JSONB,
    
    CONSTRAINT fk_user_id FOREIGN KEY (user_id) REFERENCES _auth_user(id) ON DELETE CASCADE
);

-- 唯一约束：同一提供商不能重复绑定同一外部ID
CREATE UNIQUE INDEX idx_custom_provider_subject 
ON _auth_identity_custom(provider_id, external_id);

-- 邮箱索引（用于账户关联）
CREATE INDEX idx_custom_email ON _auth_identity_custom(email) WHERE email IS NOT NULL;

-- 用户ID索引
CREATE INDEX idx_custom_user_id ON _auth_identity_custom(user_id);
```

## 第二部分：新增 Authenticator 类型

### 2.1 定义类型常量

```go
// pkg/api/model/authenticator.go

const (
    AuthenticatorTypePassword AuthenticatorType = "password"
    AuthenticatorTypePasskey  AuthenticatorType = "passkey"
    AuthenticatorTypeTOTP     AuthenticatorType = "totp"
    AuthenticatorTypeOOBEmail AuthenticatorType = "oob_otp_email"
    AuthenticatorTypeOOBSMS   AuthenticatorType = "oob_otp_sms"
    AuthenticatorTypeCustom   AuthenticatorType = "custom_auth"  // 新增
)
```

### 2.2 实现 Authenticator 模型

```go
// pkg/lib/authn/authenticator/custom_authenticator.go

package authenticator

import (
    "time"
    "github.com/authgear/authgear-server/pkg/api/model"
)

// Custom Authenticator 数据结构
type CustomAuth struct {
    ID        string    `json:"id"`
    CreatedAt time.Time `json:"created_at"`
    UpdatedAt time.Time `json:"updated_at"`
    UserID    string    `json:"user_id"`
    IsDefault bool      `json:"is_default"`
    Kind      Kind      `json:"kind"`
    
    // 凭证数据（加密存储）
    CredentialHash string `json:"credential_hash"`
    
    // 附加数据
    DisplayName string `json:"display_name"`
}

func (a *CustomAuth) ToInfo() *Info {
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

### 2.3 定义 Spec

```go
// pkg/lib/authn/authenticator/custom_spec.go

package authenticator

type CustomAuthSpec struct {
    PlainCredential string `json:"plain_credential,omitempty"` // 创建/验证时的明文
    CredentialHash  string `json:"credential_hash,omitempty"`  // 哈希值（导入时用）
    DisplayName     string `json:"display_name"`
}
```

### 2.4 扩展 Info 结构

```go
// pkg/lib/authn/authenticator/info.go

// 添加字段
type Info struct {
    // ... 现有字段 ...
    Custom *CustomAuth `json:"custom,omitempty"`
}

// 在 Equal() 中添加 case
case model.AuthenticatorTypeCustom:
    // 比较凭证哈希
    return subtle.ConstantTimeCompare(
        []byte(i.Custom.CredentialHash),
        []byte(that.Custom.CredentialHash),
    ) == 1

// 在 ToPublicClaims() 中添加 case
case model.AuthenticatorTypeCustom:
    claims[AuthenticatorClaimCustomDisplayName] = i.Custom.DisplayName

// 在 StandardClaims() 中添加 case
// 如果与 Identity 有关联，返回对应 Claims
case model.AuthenticatorTypeCustom:
    // 返回关联 Identity 的标准 Claims
    return map[model.ClaimName]string{}

// 在 CanHaveMFA() 中添加 case
case model.AuthenticatorTypeCustom:
    // 根据安全性决定
    return true  // 或 false

// 在 IsIndependent() 中添加 case
case model.AuthenticatorTypeCustom:
    return true  // 或 false

// 在 IsDependentOf() 中添加 case
// 判断 Custom Authenticator 是否依赖特定 Identity

// 在 ToAuthentication() 中添加 case
func (i *Info) ToAuthentication() model.AuthenticationFlowAuthentication {
    switch i.Kind {
    case KindPrimary:
        switch i.Type {
        // ... 现有 case ...
        case model.AuthenticatorTypeCustom:
            return model.AuthenticationFlowAuthenticationPrimaryCustom
        }
    case KindSecondary:
        switch i.Type {
        // ... 现有 case ...
        case model.AuthenticatorTypeCustom:
            return model.AuthenticationFlowAuthenticationSecondaryCustom
        }
    }
}

// 在 UpdateUserID() 中添加 case
case model.AuthenticatorTypeCustom:
    i.Custom.UserID = newUserID
```

### 2.5 扩展 Spec 结构

```go
// pkg/lib/authn/authenticator/spec.go

type Spec struct {
    // ... 现有字段 ...
    Custom *CustomAuthSpec `json:"custom,omitempty"`
}
```

### 2.6 定义 Claim Key 常量

```go
// pkg/lib/authn/authenticator/keys.go

const (
    // ... 现有常量 ...
    AuthenticatorClaimCustomDisplayName = "custom_display_name"
)
```

### 2.7 实现 Provider

```go
// pkg/lib/authn/authenticator/custom/provider.go

package custom

import (
    "context"
    "crypto/subtle"
    
    "github.com/authgear/authgear-server/pkg/lib/authn/authenticator"
    "github.com/authgear/authgear-server/pkg/util/clock"
    "github.com/authgear/authgear-server/pkg/util/uuid"
    "github.com/authgear/authgear-server/pkg/util/crypto"
)

type Provider struct {
    Store  *Store
    Clock  clock.Clock
    Config *config.AuthenticatorCustomConfig
}

func (p *Provider) New(
    ctx context.Context,
    id string,
    userID string,
    spec *authenticator.CustomAuthSpec,
    isDefault bool,
    kind string,
) (*authenticator.CustomAuth, error) {
    if id == "" {
        id = uuid.New()
    }
    
    authen := &authenticator.CustomAuth{
        ID:          id,
        UserID:      userID,
        IsDefault:   isDefault,
        Kind:        kind,
        DisplayName: spec.DisplayName,
    }
    
    switch {
    case spec.PlainCredential != "":
        // 验证并哈希凭证
        if err := p.validateCredential(spec.PlainCredential); err != nil {
            return nil, err
        }
        hash, err := p.hashCredential(spec.PlainCredential)
        if err != nil {
            return nil, err
        }
        authen.CredentialHash = hash
        
    case spec.CredentialHash != "":
        // 直接导入哈希值（迁移场景）
        authen.CredentialHash = spec.CredentialHash
        
    default:
        return nil, fmt.Errorf("no credential provided")
    }
    
    return authen, nil
}

func (p *Provider) Authenticate(ctx context.Context, a *authenticator.CustomAuth, credential string) error {
    // 1. 预处理凭证
    processed := p.preprocessCredential(credential)
    
    // 2. 验证凭证
    if err := p.verifyCredential(processed, a.CredentialHash); err != nil {
        return err
    }
    
    return nil
}

func (p *Provider) verifyCredential(plain string, hash string) error {
    // 使用常数时间比较防止时序攻击
    if subtle.ConstantTimeCompare([]byte(plain), []byte(hash)) != 1 {
        // 实际应该使用密码哈希比较（如 bcrypt）
        // 这里简化处理
        return fmt.Errorf("invalid credential")
    }
    return nil
}

func (p *Provider) validateCredential(credential string) error {
    // 实现凭证强度验证
    if len(credential) < 8 {
        return fmt.Errorf("credential too short")
    }
    return nil
}

func (p *Provider) hashCredential(credential string) (string, error) {
    // 实现凭证哈希
    // 可以使用 bcrypt, argon2, pbkdf2 等
    return crypto.Hash(credential)
}

func (p *Provider) preprocessCredential(credential string) string {
    // 预处理：去除空格、统一大小写等
    return strings.TrimSpace(credential)
}

func (p *Provider) Get(ctx context.Context, userID, id string) (*authenticator.CustomAuth, error) {
    return p.Store.Get(ctx, userID, id)
}

func (p *Provider) GetMany(ctx context.Context, ids []string) ([]*authenticator.CustomAuth, error) {
    return p.Store.GetMany(ctx, ids)
}

func (p *Provider) List(ctx context.Context, userID string) ([]*authenticator.CustomAuth, error) {
    return p.Store.List(ctx, userID)
}

func (p *Provider) Create(ctx context.Context, a *authenticator.CustomAuth) error {
    now := p.Clock.NowUTC()
    a.CreatedAt = now
    a.UpdatedAt = now
    return p.Store.Create(ctx, a)
}

func (p *Provider) Update(ctx context.Context, a *authenticator.CustomAuth) error {
    now := p.Clock.NowUTC()
    a.UpdatedAt = now
    return p.Store.Update(ctx, a)
}

func (p *Provider) Delete(ctx context.Context, a *authenticator.CustomAuth) error {
    return p.Store.Delete(ctx, a)
}
```

### 2.8 实现 Store

```go
// pkg/lib/authn/authenticator/custom/store.go

package custom

import (
    "context"
    "database/sql"
    
    "github.com/authgear/authgear-server/pkg/lib/authn/authenticator"
    "github.com/authgear/authgear-server/pkg/lib/infra/db"
)

type Store struct {
    SQLBuilder  *db.SQLBuilder
    SQLExecutor *db.SQLExecutor
}

func (s *Store) Get(ctx context.Context, userID, id string) (*authenticator.CustomAuth, error) {
    builder := s.SQLBuilder.
        Select("id", "created_at", "updated_at", "user_id", "is_default", "kind", "credential_hash", "display_name").
        From(s.SQLBuilder.TableName("_auth_authenticator_custom")).
        Where("id = ?", id)
    
    row := s.SQLExecutor.QueryRowWith(builder)
    return s.scan(row)
}

func (s *Store) GetMany(ctx context.Context, ids []string) ([]*authenticator.CustomAuth, error) {
    // 实现批量查询
}

func (s *Store) List(ctx context.Context, userID string) ([]*authenticator.CustomAuth, error) {
    // 实现列表查询
}

func (s *Store) Create(ctx context.Context, a *authenticator.CustomAuth) error {
    builder := s.SQLBuilder.
        Insert(s.SQLBuilder.TableName("_auth_authenticator_custom")).
        Columns("id", "created_at", "updated_at", "user_id", "is_default", "kind", "credential_hash", "display_name").
        Values(a.ID, a.CreatedAt, a.UpdatedAt, a.UserID, a.IsDefault, a.Kind, a.CredentialHash, a.DisplayName)
    
    _, err := s.SQLExecutor.ExecWith(builder)
    return err
}

func (s *Store) Update(ctx context.Context, a *authenticator.CustomAuth) error {
    builder := s.SQLBuilder.
        Update(s.SQLBuilder.TableName("_auth_authenticator_custom")).
        Set("updated_at", a.UpdatedAt).
        Set("credential_hash", a.CredentialHash).
        Set("display_name", a.DisplayName).
        Where("id = ?", a.ID)
    
    _, err := s.SQLExecutor.ExecWith(builder)
    return err
}

func (s *Store) Delete(ctx context.Context, a *authenticator.CustomAuth) error {
    builder := s.SQLBuilder.
        Delete(s.SQLBuilder.TableName("_auth_authenticator_custom")).
        Where("id = ?", a.ID)
    
    _, err := s.SQLExecutor.ExecWith(builder)
    return err
}

func (s *Store) scan(scanner db.Scanner) (*authenticator.CustomAuth, error) {
    a := &authenticator.CustomAuth{}
    err := scanner.Scan(
        &a.ID,
        &a.CreatedAt,
        &a.UpdatedAt,
        &a.UserID,
        &a.IsDefault,
        &a.Kind,
        &a.CredentialHash,
        &a.DisplayName,
    )
    return a, err
}
```

### 2.9 数据库 Migration

```sql
-- migrations/20260102_add_custom_authenticator.sql

CREATE TABLE _auth_authenticator_custom (
    id VARCHAR(32) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    kind VARCHAR(32) NOT NULL, -- 'primary' or 'secondary'
    credential_hash VARCHAR(255) NOT NULL, -- 加密存储的凭证哈希
    display_name VARCHAR(255),
    
    CONSTRAINT fk_user_id FOREIGN KEY (user_id) REFERENCES _auth_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_custom_auth_user_id ON _auth_authenticator_custom(user_id);
CREATE INDEX idx_custom_auth_user_kind ON _auth_authenticator_custom(user_id, kind);
```

## 第三部分：在 Service 层集成

### 3.1 Identity Service 集成

```go
// pkg/lib/authn/identity/service/service.go

// 定义 Provider 接口
type CustomIdentityProvider interface {
    New(userID string, spec *identity.CustomSpec) *identity.Custom
    WithUpdate(iden *identity.Custom, spec *identity.CustomSpec) *identity.Custom
    Get(ctx context.Context, userID, id string) (*identity.Custom, error)
    GetMany(ctx context.Context, ids []string) ([]*identity.Custom, error)
    List(ctx context.Context, userID string) ([]*identity.Custom, error)
    GetByProviderSubject(ctx context.Context, providerID string, externalID string) (*identity.Custom, error)
    ListByClaim(ctx context.Context, name string, value string) ([]*identity.Custom, error)
    Create(ctx context.Context, i *identity.Custom) error
    Update(ctx context.Context, i *identity.Custom) error
    Delete(ctx context.Context, i *identity.Custom) error
}

// Service 结构添加字段
type Service struct {
    // ... 现有字段 ...
    Custom CustomIdentityProvider
}

// 在各个方法中添加 case

func (s *Service) Get(ctx context.Context, id string) (*identity.Info, error) {
    // ...
    switch ref.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        c, err := s.Custom.Get(ctx, ref.UserID, id)
        if err != nil {
            return nil, err
        }
        return c.ToInfo(), nil
    }
}

func (s *Service) GetMany(ctx context.Context, ids []string) ([]*identity.Info, error) {
    // ...
    case model.IdentityTypeCustom:
        customIDs = append(customIDs, ref.ID)
    // ...
    c, err := s.Custom.GetMany(ctx, customIDs)
    // ...
}

func (s *Service) New(ctx context.Context, userID string, spec *identity.Spec, options identity.NewIdentityOptions) (*identity.Info, error) {
    switch spec.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        c := s.Custom.New(userID, spec.Custom)
        return c.ToInfo(), nil
    }
}

func (s *Service) Create(ctx context.Context, info *identity.Info) error {
    // ... 重复检查 ...
    switch info.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        i := info.Custom
        if err := s.Custom.Create(ctx, i); err != nil {
            return err
        }
        *info = *i.ToInfo()
    }
}

func (s *Service) UpdateWithSpec(ctx context.Context, info *identity.Info, spec *identity.Spec, options identity.NewIdentityOptions) (*identity.Info, error) {
    switch info.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        i := s.Custom.WithUpdate(info.Custom, spec.Custom)
        return i.ToInfo(), nil
    }
}

func (s *Service) Update(ctx context.Context, info *identity.Info) error {
    switch info.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        i := info.Custom
        if err := s.Custom.Update(ctx, i); err != nil {
            return err
        }
        *info = *i.ToInfo()
    }
}

func (s *Service) Delete(ctx context.Context, info *identity.Info) error {
    switch info.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        i := info.Custom
        if err := s.Custom.Delete(ctx, i); err != nil {
            return err
        }
    }
}

func (s *Service) SearchBySpec(ctx context.Context, spec *identity.Spec) (exactMatch *identity.Info, otherMatches []*identity.Info, err error) {
    // ...
    case model.IdentityTypeCustom:
        c, err := s.Custom.GetByProviderSubject(ctx, spec.Custom.ProviderID, spec.Custom.ExternalID)
        // ...
}

func (s *Service) CheckDuplicatedByUniqueKey(ctx context.Context, info *identity.Info) (dupeIdentity *identity.Info, err error) {
    switch info.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        c, err := s.Custom.GetByProviderSubject(ctx, info.Custom.ProviderID, info.Custom.ExternalID)
        // ...
    }
}

func (s *Service) ListCandidates(ctx context.Context, userID string) (out []identity.Candidate, err error) {
    // ...
    case model.IdentityTypeCustom:
        // 返回 Custom Identity 候选
        customs, _ := s.Custom.List(ctx, userID)
        out = append(out, s.listCustomCandidates(customs)...)
    // ...
}
```

### 3.2 Authenticator Service 集成

```go
// pkg/lib/authn/authenticator/service/service.go

// 定义 Provider 接口
type CustomAuthenticatorProvider interface {
    New(ctx context.Context, id string, userID string, customSpec *authenticator.CustomAuthSpec, isDefault bool, kind string) (*authenticator.CustomAuth, error)
    Authenticate(ctx context.Context, a *authenticator.CustomAuth, credential string) error
    Get(ctx context.Context, userID, id string) (*authenticator.CustomAuth, error)
    GetMany(ctx context.Context, ids []string) ([]*authenticator.CustomAuth, error)
    List(ctx context.Context, userID string) ([]*authenticator.CustomAuth, error)
    Create(ctx context.Context, a *authenticator.CustomAuth) error
    Update(ctx context.Context, a *authenticator.CustomAuth) error
    Delete(ctx context.Context, a *authenticator.CustomAuth) error
}

// ReadOnlyService 结构添加字段
type ReadOnlyService struct {
    // ... 现有字段 ...
    Custom CustomAuthenticatorProvider
}

// 在各个方法中添加 case

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

func (s *Service) Update(ctx context.Context, info *authenticator.Info) error {
    switch info.Type {
    // ... 现有 case ...
    case model.AuthenticatorTypeCustom:
        a := info.Custom
        if err := s.Custom.Update(ctx, a); err != nil {
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
        credential := spec.Custom.PlainCredential
        a := info.Custom
        if err := s.Custom.Authenticate(ctx, a, credential); err != nil {
            err = api.ErrInvalidCredentials
            return nil, err
        }
        *info = *a.ToInfo()
        return
    }
}
```

## 第四部分：Flow 集成

### 4.1 定义 Identification 类型

```go
// pkg/api/model/identification.go

const (
    // ... 现有类型 ...
    AuthenticationFlowIdentificationCustom AuthenticationFlowIdentification = "custom"
)

func (m AuthenticationFlowIdentification) PrimaryAuthentications() []AuthenticationFlowAuthentication {
    switch m {
    // ... 现有 case ...
    case AuthenticationFlowIdentificationCustom:
        return []AuthenticationFlowAuthentication{
            AuthenticationFlowAuthenticationPrimaryPassword,
            AuthenticationFlowAuthenticationPrimaryCustom,
        }
    }
}

func (m AuthenticationFlowIdentification) SecondaryAuthentications() []AuthenticationFlowAuthentication {
    switch m {
    // ... 现有 case ...
    case AuthenticationFlowIdentificationCustom:
        return []AuthenticationFlowAuthentication{
            AuthenticationFlowAuthenticationSecondaryCustom,
        }
    }
}
```

### 4.2 定义 Authentication 类型

```go
// pkg/api/model/authentication.go

const (
    // ... 现有类型 ...
    AuthenticationFlowAuthenticationPrimaryCustom   AuthenticationFlowAuthentication = "primary_custom"
    AuthenticationFlowAuthenticationSecondaryCustom AuthenticationFlowAuthentication = "secondary_custom"
)
```

### 4.3 实现 Flow Intent

```go
// pkg/lib/authenticationflow/declarative/intent_use_identity_custom.go

package declarative

import (
    "context"
    "github.com/iawaknahc/jsonschema/pkg/jsonpointer"
    "github.com/authgear/authgear-server/pkg/api/model"
    authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
    "github.com/authgear/authgear-server/pkg/lib/authn/identity"
)

func init() {
    authflow.RegisterIntent(&IntentUseIdentityCustom{})
}

type IntentUseIdentityCustom struct {
    JSONPointer    jsonpointer.T                          `json:"json_pointer,omitempty"`
    Identification model.AuthenticationFlowIdentification `json:"identification,omitempty"`
}

var _ authflow.Intent = &IntentUseIdentityCustom{}
var _ authflow.Milestone = &IntentUseIdentityCustom{}
var _ MilestoneIdentificationMethod = &IntentUseIdentityCustom{}
var _ MilestoneFlowUseIdentity = &IntentUseIdentityCustom{}
var _ authflow.InputReactor = &IntentUseIdentityCustom{}

func (*IntentUseIdentityCustom) Kind() string {
    return "IntentUseIdentityCustom"
}

func (*IntentUseIdentityCustom) Milestone() {}

func (n *IntentUseIdentityCustom) MilestoneIdentificationMethod() model.AuthenticationFlowIdentification {
    return n.Identification
}

func (*IntentUseIdentityCustom) MilestoneFlowUseIdentity(flows authflow.Flows) (MilestoneDoUseIdentity, authflow.Flows, bool) {
    return authflow.FindMilestoneInCurrentFlow[MilestoneDoUseIdentity](flows)
}

func (n *IntentUseIdentityCustom) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    _, _, identified := authflow.FindMilestoneInCurrentFlow[MilestoneDoUseIdentity](flows)
    if identified {
        return nil, authflow.ErrEOF
    }
    
    flowRootObject, err := findNearestFlowObjectInFlow(deps, flows, n)
    if err != nil {
        return nil, err
    }
    
    isBotProtectionRequired, err := IsBotProtectionRequired(ctx, deps, flows, n.JSONPointer, n)
    if err != nil {
        return nil, err
    }
    
    return &InputSchemaTakeCustomIdentity{
        FlowRootObject:          flowRootObject,
        JSONPointer:             n.JSONPointer,
        IsBotProtectionRequired: isBotProtectionRequired,
        BotProtectionCfg:        deps.Config.BotProtection,
    }, nil
}

func (n *IntentUseIdentityCustom) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    var inputCustom inputTakeCustomIdentity
    if authflow.AsInput(input, &inputCustom) {
        // 处理机器人防护
        bpSpecialErr, err := HandleBotProtection(ctx, deps, flows, n.JSONPointer, input, n)
        if err != nil {
            return nil, err
        }
        
        // 构建 Identity Spec
        identitySpec := &identity.Spec{
            Type: model.IdentityTypeCustom,
            Custom: &identity.CustomSpec{
                ProviderID:  inputCustom.GetProviderID(),
                ExternalID:  inputCustom.GetExternalID(),
                DisplayName: inputCustom.GetDisplayName(),
                Email:       inputCustom.GetEmail(),
            },
        }
        
        // 查找 Identity
        exactMatch, err := findExactOneIdentityInfo(ctx, deps, identitySpec)
        if err != nil {
            return nil, err
        }
        
        // 创建使用 Identity 的节点
        result, err := NewNodeDoUseIdentityCustom(ctx, deps, flows, &NodeDoUseIdentityCustomOptions{
            Identity:     exactMatch,
            IdentitySpec: identitySpec,
        })
        if err != nil {
            return nil, err
        }
        
        return result, bpSpecialErr
    }
    
    return nil, authflow.ErrIncompatibleInput
}
```

### 4.4 实现 Node

```go
// pkg/lib/authenticationflow/declarative/node_do_use_identity_custom.go

package declarative

import (
    "context"
    "github.com/iawaknahc/jsonschema/pkg/jsonpointer"
    authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
    "github.com/authgear/authgear-server/pkg/lib/authn/identity"
)

func init() {
    authflow.RegisterNode(&NodeDoUseIdentityCustom{})
}

type NodeDoUseIdentityCustom struct {
    JSONPointer  jsonpointer.T    `json:"json_pointer,omitempty"`
    Identity     *identity.Info   `json:"identity,omitempty"`
    IdentitySpec *identity.Spec   `json:"identity_spec,omitempty"`
}

var _ authflow.Node = &NodeDoUseIdentityCustom{}
var _ authflow.Milestone = &NodeDoUseIdentityCustom{}
var _ MilestoneDoUseIdentity = &NodeDoUseIdentityCustom{}
var _ authflow.DataOutputer = &NodeDoUseIdentityCustom{}

func (*NodeDoUseIdentityCustom) Kind() string {
    return "NodeDoUseIdentityCustom"
}

func (*NodeDoUseIdentityCustom) Milestone() {}

func (n *NodeDoUseIdentityCustom) MilestoneDoUseIdentity() *identity.Info {
    return n.Identity
}

func (n *NodeDoUseIdentityCustom) OutputData(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.Data, error) {
    return NewIdentificationData(IdentificationData{
        Identity: n.Identity,
    }), nil
}

type NodeDoUseIdentityCustomOptions struct {
    Identity     *identity.Info
    IdentitySpec *identity.Spec
}

func NewNodeDoUseIdentityCustom(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, options *NodeDoUseIdentityCustomOptions) (*NodeDoUseIdentityCustom, error) {
    return &NodeDoUseIdentityCustom{
        JSONPointer:  jsonpointer.T{},
        Identity:     options.Identity,
        IdentitySpec: options.IdentitySpec,
    }, nil
}
```

### 4.5 实现 Authenticator Flow Intent

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_custom.go

package declarative

import (
    "context"
    "github.com/iawaknahc/jsonschema/pkg/jsonpointer"
    "github.com/authgear/authgear-server/pkg/api/model"
    authflow "github.com/authgear/authgear-server/pkg/lib/authenticationflow"
    "github.com/authgear/authgear-server/pkg/lib/authn"
    "github.com/authgear/authgear-server/pkg/lib/authn/authenticator"
    "github.com/authgear/authgear-server/pkg/lib/facade"
)

func init() {
    authflow.RegisterIntent(&IntentUseAuthenticatorCustom{})
}

type IntentUseAuthenticatorCustom struct {
    JSONPointer    jsonpointer.T `json:"json_pointer,omitempty"`
    AuthenticatorID *string      `json:"authenticator_id,omitempty"`
}

func (*IntentUseAuthenticatorCustom) Kind() string {
    return "IntentUseAuthenticatorCustom"
}

func (i *IntentUseAuthenticatorCustom) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 检查是否已完成认证
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDoAuthenticate](flows)
    if authenticated {
        return nil, authflow.ErrEOF
    }
    
    flowRootObject, err := findNearestFlowObjectInFlow(deps, flows, i)
    if err != nil {
        return nil, err
    }
    
    return &InputSchemaTakeCustomAuth{
        FlowRootObject: flowRootObject,
        JSONPointer:    i.JSONPointer,
    }, nil
}

func (i *IntentUseAuthenticatorCustom) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    var inputCustomAuth inputTakeCustomAuth
    if authflow.AsInput(input, &inputCustomAuth) {
        credential := inputCustomAuth.GetCredential()
        
        // 获取当前用户ID
        userID, err := getUserID(flows)
        if err != nil {
            return nil, err
        }
        
        // 构建 Authenticator Spec
        authenticatorSpec := &authenticator.Spec{
            Type: model.AuthenticatorTypeCustom,
            Custom: &authenticator.CustomAuthSpec{
                PlainCredential: credential,
            },
        }
        
        // 获取用户的 Custom Authenticators
        authenticators, err := deps.Authenticators.List(ctx, userID, authenticator.KeepType(model.AuthenticatorTypeCustom))
        if err != nil {
            return nil, err
        }
        
        // 验证
        authenticatorInfo, verifyResult, err := deps.Authenticators.VerifyOneWithSpec(ctx,
            userID,
            model.AuthenticatorTypeCustom,
            authenticators,
            authenticatorSpec,
            &facade.VerifyOptions{
                AuthenticationDetails: facade.NewAuthenticationDetails(
                    userID,
                    authn.AuthenticationStagePrimary, // 或 Secondary
                    authn.AuthenticationTypeCustom,
                ),
            },
        )
        if err != nil {
            return nil, err
        }
        
        // 创建认证节点
        result, err := NewNodeDoUseAuthenticatorCustom(ctx, deps, flows, &NodeDoUseUseAuthenticatorCustomOptions{
            Authenticator: authenticatorInfo,
            VerifyResult:  verifyResult,
        })
        if err != nil {
            return nil, err
        }
        
        return result, nil
    }
    
    return nil, authflow.ErrIncompatibleInput
}
```

## 第五部分：配置更新

### 5.1 配置 Schema

```go
// pkg/lib/config/authentication_flow.go

var _ = Schema.Add("AuthenticationFlowSignupFlowIdentify", `
{
    "type": "object",
    "required": ["identification"],
    "properties": {
        "identification": {
            "type": "string",
            "enum": [
                "email",
                "phone",
                "username",
                "oauth",
                "passkey",
                "ldap",
                "custom"  // 新增
            ]
        }
    }
}
`)

var _ = Schema.Add("AuthenticationFlowSignupFlowAuthenticate", `
{
    "type": "object",
    "required": ["authentication"],
    "properties": {
        "authentication": {
            "type": "string",
            "enum": [
                "primary_password",
                "primary_oob_otp_email",
                "primary_oob_otp_sms",
                "primary_custom",  // 新增
                "secondary_password",
                "secondary_totp",
                "secondary_oob_otp_email",
                "secondary_oob_otp_sms",
                "secondary_custom"  // 新增
            ]
        }
    }
}
`)
```

### 5.2 配置示例

```yaml
# 使用 Custom Identity 和 Authenticator 的登录流程配置
authentication:
  flows:
    login_flows:
      - name: custom_login
        steps:
          - type: identify
            one_of:
              - identification: custom
                steps:
                  - type: authenticate
                    one_of:
                      - authentication: primary_custom
```

## 第六部分：依赖注入配置

```go
// pkg/lib/authn/identity/deps.go

var DependencySet = wire.NewSet(
    // ... 现有 Provider ...
    custom.NewProvider,
    wire.Bind(new(service.CustomIdentityProvider), new(*custom.Provider)),
)

// pkg/lib/authn/authenticator/deps.go

var DependencySet = wire.NewSet(
    // ... 现有 Provider ...
    customauth.NewProvider,
    wire.Bind(new(service.CustomAuthenticatorProvider), new(*customauth.Provider)),
)
```

## 总结

新增 Identity、Authenticator 和 Flow 类型需要：

1. **定义类型常量**：在 model 包中添加类型标识
2. **实现数据模型**：实现 Identity 和 Authenticator 的具体数据结构
3. **定义 Spec**：创建用于创建/验证的 Spec 结构
4. **扩展 Info 结构**：在统一的 Info 结构中添加新类型支持
5. **实现 Provider**：创建 Provider 处理业务逻辑
6. **实现 Store**：处理数据库操作
7. **Service 层集成**：在 Service 中添加类型处理分支
8. **Flow 集成**：实现 Flow Intent 和 Node
9. **配置更新**：更新配置 Schema 支持新类型
10. **数据库 Migration**：创建对应的数据库表

整个过程遵循 Authgear 的分层架构设计，新类型的实现与现有类型完全隔离，通过接口和统一的 Info/Spec 结构与核心系统集成。
