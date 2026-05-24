# Authgear Identity 系统深入分析

## 1. Identity 系统概述

Identity 系统用于识别和表示用户的身份。Authgear 支持多种身份类型，每种类型对应不同的身份验证方式。

### 1.1 支持的 Identity 类型

```go
// pkg/api/model/identity.go
type IdentityType string

const (
    IdentityTypeLoginID   IdentityType = "login_id"   // 邮箱、手机号、用户名
    IdentityTypeOAuth     IdentityType = "oauth"      // 第三方 OAuth 提供商
    IdentityTypeAnonymous IdentityType = "anonymous"  // 匿名身份
    IdentityTypeBiometric IdentityType = "biometric"  // 生物识别
    IdentityTypePasskey   IdentityType = "passkey"    // WebAuthn/Passkey
    IdentityTypeSIWE      IdentityType = "siwe"       // Sign-In with Ethereum
    IdentityTypeLDAP      IdentityType = "ldap"       // LDAP 身份
)
```

### 1.2 Identity 数据结构

```go
// pkg/lib/authn/identity/info.go
type Info struct {
    ID        string             `json:"id"`
    UserID    string             `json:"user_id"`
    CreatedAt time.Time          `json:"created_at"`
    UpdatedAt time.Time          `json:"updated_at"`
    Type      model.IdentityType `json:"type"`

    LoginID   *LoginID   `json:"login_id,omitempty"`
    OAuth     *OAuth     `json:"oauth,omitempty"`
    Anonymous *Anonymous `json:"anonymous,omitempty"`
    Biometric *Biometric `json:"biometric,omitempty"`
    Passkey   *Passkey   `json:"passkey,omitempty"`
    SIWE      *SIWE      `json:"siwe,omitempty"`
    LDAP      *LDAP      `json:"ldap,omitempty"`
}
```

## 2. Identity 系统架构

### 2.1 三层架构

```
┌─────────────────────────────────────────────────────────────┐
│                     Service Layer                          │
│              (pkg/lib/authn/identity/service)               │
│  - 统一的 Identity 管理接口                                  │
│  - 协调不同类型 Identity 的操作                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Provider Layer                           │
│     (pkg/lib/authn/identity/{loginid,oauth,passkey,...})    │
│  - 每种 Identity 类型的具体实现                              │
│  - 创建、查询、更新、删除操作                                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Storage Layer                           │
│                   (Store/Repository)                        │
│  - 数据持久化                                                │
│  - 数据库操作                                                │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Service 层设计

Service 层通过接口组合各个 Provider：

```go
// pkg/lib/authn/identity/service/service.go
type Service struct {
    Authentication          *config.AuthenticationConfig
    Identity                *config.IdentityConfig
    IdentityFeatureConfig   *config.IdentityFeatureConfig
    SSOOAuthDemoCredentials *config.SSOOAuthDemoCredentials
    Store                   *Store
    LoginID                 LoginIDIdentityProvider
    OAuth                   OAuthIdentityProvider
    Anonymous               AnonymousIdentityProvider
    Biometric               BiometricIdentityProvider
    Passkey                 PasskeyIdentityProvider
    SIWE                    SIWEIdentityProvider
    LDAP                    LDAPIdentityProvider
}
```

### 2.3 Provider 接口定义

每种 Identity 类型都有对应的 Provider 接口：

```go
// 示例：LoginID Provider 接口
type LoginIDIdentityProvider interface {
    New(ctx context.Context, userID string, loginID identity.LoginIDSpec, options loginid.CheckerOptions) (*identity.LoginID, error)
    WithValue(ctx context.Context, iden *identity.LoginID, value string, options loginid.CheckerOptions) (*identity.LoginID, error)
    Normalize(typ model.LoginIDKeyType, value string) (normalized string, uniqueKey string, err error)
    
    Get(ctx context.Context, userID, id string) (*identity.LoginID, error)
    GetMany(ctx context.Context, ids []string) ([]*identity.LoginID, error)
    List(ctx context.Context, userID string) ([]*identity.LoginID, error)
    GetByValue(ctx context.Context, loginIDValue string) ([]*identity.LoginID, error)
    GetByKeyAndValue(ctx context.Context, loginIDKey string, loginIDValue string) (*identity.LoginID, error)
    GetByUniqueKey(ctx context.Context, uniqueKey string) (*identity.LoginID, error)
    ListByClaim(ctx context.Context, name string, value string) ([]*identity.LoginID, error)
    Create(ctx context.Context, i *identity.LoginID) error
    Update(ctx context.Context, i *identity.LoginID) error
    Delete(ctx context.Context, i *identity.LoginID) error
}
```

## 3. Identity Spec 模式

### 3.1 Spec 结构

Spec 用于定义创建 Identity 所需的参数：

```go
// pkg/lib/authn/identity/spec.go
type Spec struct {
    Type model.IdentityType `json:"type"`

    LoginID   *LoginIDSpec   `json:"login_id,omitempty"`
    OAuth     *OAuthSpec     `json:"oauth,omitempty"`
    Anonymous *AnonymousSpec `json:"anonymous,omitempty"`
    Biometric *BiometricSpec `json:"biometric,omitempty"`
    Passkey   *PasskeySpec   `json:"passkey,omitempty"`
    SIWE      *SIWESpec      `json:"siwe,omitempty"`
    LDAP      *LDAPSpec      `json:"ldap,omitempty"`
}
```

### 3.2 具体 Spec 示例

以 LoginID 为例：

```go
// pkg/lib/authn/identity/loginid_spec.go
type LoginIDSpec struct {
    Key   string               `json:"key"`   // 如 "email", "phone", "username"
    Type  model.LoginIDKeyType `json:"type"`  // "email", "phone", "username"
    Value UserInputString      `json:"value"` // 用户输入的值
}
```

以 Passkey 为例：

```go
// pkg/lib/authn/identity/passkey_spec.go
type PasskeySpec struct {
    AttestationResponse []byte `json:"attestation_response,omitempty"` // 创建凭证响应
    AssertionResponse   []byte `json:"assertion_response,omitempty"`   // 验证断言响应
}
```

## 4. Identity 核心方法

### 4.1 Info 结构的核心方法

```go
// 转换为 Spec
func (i *Info) ToSpec() Spec

// 转换为引用
func (i *Info) ToRef() *model.IdentityRef

// 转换为 API Model
func (i *Info) ToModel() model.Identity

// 获取显示ID（用户可读标识）
func (i *Info) DisplayID() string

// 获取身份感知标准 Claims（用于账户关联）
func (i *Info) IdentityAwareStandardClaims() map[model.ClaimName]string

// 获取所有标准 Claims
func (i *Info) AllStandardClaims() map[string]interface{}

// 获取支持的主认证器类型
func (i *Info) PrimaryAuthenticatorTypes() []model.AuthenticatorType

// 转换为 Identification 类型（用于 Flow）
func (i *Info) ToIdentification() model.AuthenticationFlowIdentification

// 检查操作是否被禁用
func (i *Info) CreateDisabled(c *config.IdentityConfig) bool
func (i *Info) DeleteDisabled(c *config.IdentityConfig) bool
func (i *Info) UpdateDisabled(c *config.IdentityConfig) bool

// 更新 UserID
func (i *Info) UpdateUserID(newUserID string) *Info

// 获取 AMR（Authentication Methods Reference）
func (i *Info) AMR() []string
```

## 5. 新增 Identity 类型的步骤

### 5.1 定义类型常量

在 `pkg/api/model/identity.go` 中添加：

```go
const (
    IdentityTypeLoginID   IdentityType = "login_id"
    IdentityTypeOAuth     IdentityType = "oauth"
    // ... 现有类型 ...
    IdentityTypeCustom    IdentityType = "custom"  // 新增类型
)
```

### 5.2 实现 Model 结构

在 `pkg/lib/authn/identity/` 目录下创建：

```go
// custom_identity.go
type Custom struct {
    ID        string    `json:"id"`
    CreatedAt time.Time `json:"created_at"`
    UpdatedAt time.Time `json:"updated_at"`
    UserID    string    `json:"user_id"`
    
    // 自定义字段
    CustomField string `json:"custom_field"`
    // ...
}

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

func (i *Custom) IdentityAwareStandardClaims() map[model.ClaimName]string {
    // 返回用于账户关联的 Claims
    return map[model.ClaimName]string{}
}
```

### 5.3 定义 Spec

```go
// custom_spec.go
type CustomSpec struct {
    CustomField string `json:"custom_field"`
    // ...
}
```

### 5.4 扩展 Info 结构

```go
// 在 info.go 中添加
type Info struct {
    // ... 现有字段 ...
    Custom *Custom `json:"custom,omitempty"`
}

// 在 ToSpec 中添加 case
func (i *Info) ToSpec() Spec {
    switch i.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        return Spec{
            Type: i.Type,
            Custom: &CustomSpec{
                CustomField: i.Custom.CustomField,
            },
        }
    }
}

// 在 ToModel 中添加 case
func (i *Info) ToModel() model.Identity {
    // ...
    switch i.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        claims[IdentityClaimCustomField] = i.Custom.CustomField
    }
}

// 在 DisplayID 中添加 case
func (i *Info) DisplayID() string {
    switch i.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        return i.Custom.CustomField
    }
}

// 在 IdentityAwareStandardClaims 中添加 case
func (i *Info) IdentityAwareStandardClaims() map[model.ClaimName]string {
    switch i.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        return i.Custom.IdentityAwareStandardClaims()
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
    Store *Store
    Clock clock.Clock
}

func (p *Provider) New(userID string, spec *identity.CustomSpec) *identity.Custom {
    return &identity.Custom{
        ID:          uuid.New(),
        UserID:      userID,
        CustomField: spec.CustomField,
    }
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

func (p *Provider) GetByCustomField(ctx context.Context, value string) (*identity.Custom, error) {
    return p.Store.GetByCustomField(ctx, value)
}

func (p *Provider) Create(ctx context.Context, i *identity.Custom) error {
    now := p.Clock.NowUTC()
    i.CreatedAt = now
    i.UpdatedAt = now
    return p.Store.Create(ctx, i)
}

func (p *Provider) Delete(ctx context.Context, i *identity.Custom) error {
    return p.Store.Delete(ctx, i)
}
```

### 5.7 定义 Provider 接口

```go
// 在 service/service.go 中添加
type CustomIdentityProvider interface {
    New(userID string, spec *identity.CustomSpec) *identity.Custom
    Get(ctx context.Context, userID, id string) (*identity.Custom, error)
    GetMany(ctx context.Context, ids []string) ([]*identity.Custom, error)
    List(ctx context.Context, userID string) ([]*identity.Custom, error)
    GetByCustomField(ctx context.Context, value string) (*identity.Custom, error)
    Create(ctx context.Context, i *identity.Custom) error
    Delete(ctx context.Context, i *identity.Custom) error
}

// 在 Service 结构中添加
type Service struct {
    // ... 现有字段 ...
    Custom CustomIdentityProvider
}
```

### 5.8 实现 Store

```go
// custom/store.go
type Store struct {
    SQLBuilder  *db.SQLBuilder
    SQLExecutor *db.SQLExecutor
}

func (s *Store) Get(ctx context.Context, userID, id string) (*identity.Custom, error) {
    // 实现查询逻辑
}

func (s *Store) GetMany(ctx context.Context, ids []string) ([]*identity.Custom, error) {
    // 实现批量查询逻辑
}

func (s *Store) List(ctx context.Context, userID string) ([]*identity.Custom, error) {
    // 实现列表查询逻辑
}

func (s *Store) GetByCustomField(ctx context.Context, value string) (*identity.Custom, error) {
    // 实现按自定义字段查询逻辑
}

func (s *Store) Create(ctx context.Context, i *identity.Custom) error {
    // 实现创建逻辑
}

func (s *Store) Delete(ctx context.Context, i *identity.Custom) error {
    // 实现删除逻辑
}
```

### 5.9 在 Service 层集成

```go
// 在 service/service.go 的各个方法中添加 case

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

func (s *Service) New(ctx context.Context, userID string, spec *identity.Spec, options identity.NewIdentityOptions) (*identity.Info, error) {
    switch spec.Type {
    // ... 现有 case ...
    case model.IdentityTypeCustom:
        c := s.Custom.New(userID, spec.Custom)
        return c.ToInfo(), nil
    }
}

func (s *Service) Create(ctx context.Context, info *identity.Info) error {
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
```

### 5.10 配置数据库表

添加新的数据库 migration：

```sql
CREATE TABLE _auth_identity_custom (
    id VARCHAR(32) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    custom_field VARCHAR(255) NOT NULL,
    
    CONSTRAINT fk_user_id FOREIGN KEY (user_id) REFERENCES _auth_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_custom_field ON _auth_identity_custom(custom_field);
CREATE INDEX idx_user_id ON _auth_identity_custom(user_id);
```

## 6. 关键设计原则

1. **分层架构**：Service → Provider → Store，职责清晰
2. **统一接口**：通过 Info 结构统一所有 Identity 类型
3. **Spec 模式**：使用 Spec 定义创建参数，与运行时数据分离
4. **Claim 系统**：通过 Claims 实现身份之间的关联
5. **扩展性**：新增类型只需要实现对应的接口，无需修改核心逻辑
