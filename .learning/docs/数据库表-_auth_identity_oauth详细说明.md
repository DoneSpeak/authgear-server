# _auth_identity_oauth 表详细说明

## 表结构

```sql
CREATE TABLE _auth_identity_oauth
(
    id               text PRIMARY KEY REFERENCES _auth_identity (id),
    app_id           text                        NOT NULL,
    created_at       timestamp without time zone NOT NULL,
    updated_at       timestamp without time zone NOT NULL,
    provider_type    text                        NOT NULL,
    provider_keys    jsonb                       NOT NULL DEFAULT '{}'::jsonb,
    provider_user_id text                        NOT NULL,
    claims           jsonb                       NOT NULL,
    profile          jsonb
);
```

## 字段详细说明

### 1. `id` (text, PRIMARY KEY)

**作用**：
- OAuth身份的唯一标识符
- 作为主键，同时是外键，引用 `_auth_identity` 表的 `id` 字段
- 与 `_auth_identity` 表形成一对一关系

**来源**：
- 在创建OAuth身份时自动生成（使用UUID）
- **代码位置**：`pkg/lib/authn/identity/oauth/provider.go:61`

```go
i := &identity.OAuth{
    ID: uuid.New(),  // 生成唯一ID
    ...
}
```

**可能的内容**：
- UUID格式的字符串，例如：`"550e8400-e29b-41d4-a716-446655440000"`

**特点**：
- 全局唯一
- 一旦创建，不会改变
- 与 `_auth_identity.id` 保持一致

---

### 2. `app_id` (text, NOT NULL)

**作用**：
- 多租户隔离标识符
- 标识该OAuth身份属于哪个应用
- 用于数据隔离和查询过滤

**来源**：
- 从当前请求的应用上下文（AppContext）中获取
- 通过 `SQLBuilderApp` 自动添加到所有SQL操作中
- **代码位置**：`pkg/lib/infra/db/sql_builder.go`

**可能的内容**：
- 应用配置中的 `id` 字段值
- 例如：`"my-app"`, `"production-app"`, `"test-app-001"`

**特点**：
- 所有业务表都包含此字段
- 用于确保多租户数据隔离
- 在查询时自动过滤

---

### 3. `created_at` (timestamp without time zone, NOT NULL)

**作用**：
- 记录OAuth身份创建的时间戳
- 用于审计和排序

**来源**：
- 在创建OAuth身份时自动设置为当前UTC时间
- **代码位置**：`pkg/lib/authn/identity/oauth/provider.go:107-110`

```go
func (p *Provider) Create(ctx context.Context, i *identity.OAuth) error {
    now := p.Clock.NowUTC()
    i.CreatedAt = now
    i.UpdatedAt = now
    return p.Store.Create(ctx, i)
}
```

**可能的内容**：
- PostgreSQL timestamp格式，例如：`2024-01-15 10:30:45.123456`
- UTC时区

**特点**：
- 创建后不会改变
- 用于按时间排序OAuth身份列表

---

### 4. `updated_at` (timestamp without time zone, NOT NULL)

**作用**：
- 记录OAuth身份最后更新的时间戳
- 用于追踪数据变更

**来源**：
- 创建时设置为当前时间
- 每次更新时自动更新为当前UTC时间
- **代码位置**：`pkg/lib/authn/identity/oauth/provider.go:113-116`

```go
func (p *Provider) Update(ctx context.Context, i *identity.OAuth) error {
    now := p.Clock.NowUTC()
    i.UpdatedAt = now
    return p.Store.Update(ctx, i)
}
```

**可能的内容**：
- PostgreSQL timestamp格式，例如：`2024-01-20 14:22:33.789012`
- UTC时区

**特点**：
- 每次更新 `claims` 或 `profile` 时都会更新
- 用于判断数据是否过期，是否需要重新同步

---

### 5. `provider_type` (text, NOT NULL)

**作用**：
- 标识OAuth提供商的类型
- 用于区分不同的OAuth提供商（Google、Facebook、GitHub等）

**来源**：
- 从OAuth提供商配置中获取
- 由 `oauthrelyingparty.ProviderID.Type` 字段提供
- **代码位置**：`pkg/lib/authn/identity/oauth/store.go:248`

```go
q := s.SQLBuilder.
    Insert(s.SQLBuilder.TableName("_auth_identity_oauth")).
    Columns(..., "provider_type", ...).
    Values(..., i.ProviderID.Type, ...)
```

**可能的内容**：
- 预定义的提供商类型字符串，例如：
  - `"google"` - Google OAuth
  - `"facebook"` - Facebook OAuth
  - `"github"` - GitHub OAuth
  - `"apple"` - Apple Sign In
  - `"wechat"` - 微信登录
  - `"azureadv2"` - Azure AD v2
  - `"azureadb2c"` - Azure AD B2C
  - `"linkedin"` - LinkedIn
  - `"adfs"` - Active Directory Federation Services
  - 等等...

**特点**：
- 区分不同的OAuth提供商
- 与 `provider_keys` 一起唯一标识一个提供商配置
- 用于查询特定提供商的OAuth身份

---

### 6. `provider_keys` (jsonb, NOT NULL, DEFAULT '{}'::jsonb)

**作用**：
- 存储提供商的额外标识键
- 用于区分同一类型的多个提供商配置（例如：同一个应用可能配置了多个Google提供商，使用不同的client_id）

**来源**：
- 从 `oauthrelyingparty.ProviderID.Keys` 字段序列化为JSON
- **代码位置**：`pkg/lib/authn/identity/oauth/store.go:223-226`

```go
providerKeys, err := json.Marshal(i.ProviderID.Keys)
if err != nil {
    return err
}
```

**可能的内容**：
- JSON对象格式，通常为空对象 `{}`
- 对于需要区分的提供商，可能包含：
  ```json
  {
    "client_id": "123456789-abc.apps.googleusercontent.com",
    "tenant": "contoso.onmicrosoft.com"
  }
  ```
- 大多数情况下是空对象：`{}`

**特点**：
- 默认值为空对象
- 与 `provider_type` 和 `provider_user_id` 一起构成唯一性约束
- 用于支持同一应用配置多个相同类型的OAuth提供商

**唯一性约束**：
```sql
UNIQUE (app_id, provider_type, provider_keys, provider_user_id)
```

---

### 7. `provider_user_id` (text, NOT NULL)

**作用**：
- 存储OAuth提供商返回的用户唯一标识符
- 这是提供商系统中的用户ID，用于识别用户在提供商系统中的身份

**来源**：
- 从OAuth提供商的响应中提取
- 不同提供商使用不同的字段名：
  - **Google**: ID Token中的 `sub` 字段
  - **Facebook**: Graph API返回的 `id` 字段
  - **GitHub**: API返回的 `id` 字段（JSON Number）
  - **Apple**: ID Token中的 `sub` 字段
  - **Azure AD**: ID Token中的 `oid` 或 `sub` 字段
  - 等等...

**代码位置**：
- Google: `pkg/lib/oauthrelyingparty/google/provider.go:158-165`
- Facebook: `pkg/lib/oauthrelyingparty/facebook/provider.go:160`
- GitHub: `pkg/lib/oauthrelyingparty/github/provider.go:104-106`
- Apple: `pkg/lib/oauthrelyingparty/apple/provider.go:269-276`

**可能的内容**：
- 字符串格式的用户ID，例如：
  - Google: `"109876543210987654321"` (数字字符串)
  - Facebook: `"1234567890123456"` (数字字符串)
  - GitHub: `"12345678"` (数字字符串)
  - Apple: `"001234.567890abcdef.1234"` (Apple特有的格式)
  - Azure AD: `"a1b2c3d4-e5f6-7890-abcd-ef1234567890"` (UUID格式)

**特点**：
- 由OAuth提供商分配，在提供商系统中唯一
- 用于识别用户在提供商系统中的身份
- 与 `provider_type` 和 `provider_keys` 一起构成唯一性约束
- 用于账户链接：通过此ID可以识别是否为同一提供商用户

**查询示例**：
```sql
-- 根据提供商用户ID查找OAuth身份
SELECT * FROM _auth_identity_oauth
WHERE provider_type = 'google'
  AND provider_user_id = '109876543210987654321'
  AND app_id = 'my-app';
```

---

### 8. `claims` (jsonb, NOT NULL)

**作用**：
- 存储标准化的用户声明（Standard Claims）
- 这些是经过提取和标准化的用户属性，符合OIDC标准声明规范
- 用于账户链接、用户识别、显示名称等

**来源**：
- 从OAuth提供商的原始响应中提取标准属性
- 通过 `stdattrs.Extract()` 函数提取
- **代码位置**：
  - 提取逻辑：`pkg/lib/authn/stdattrs/extract.go`
  - 存储逻辑：`pkg/lib/authn/identity/oauth/store.go:231-234`

```go
claims, err := json.Marshal(i.Claims)
```

**提取过程**：
1. OAuth提供商返回原始用户资料（Raw Profile）
2. 使用 `stdattrs.Extract()` 提取标准声明
3. 提取的字段包括：email, phone_number, given_name, family_name, preferred_username, picture, profile等

**可能的内容**：
- JSON对象，包含标准化的用户属性，例如：

```json
{
  "email": "user@example.com",
  "email_verified": true,
  "phone_number": "+1234567890",
  "phone_number_verified": false,
  "given_name": "John",
  "family_name": "Doe",
  "name": "John Doe",
  "preferred_username": "johndoe",
  "picture": "https://lh3.googleusercontent.com/a/...",
  "profile": "https://plus.google.com/...",
  "locale": "en-US",
  "zoneinfo": "America/New_York"
}
```

**标准声明字段**（根据OIDC规范）：
- `email` (string): 邮箱地址
- `email_verified` (boolean): 邮箱是否已验证
- `phone_number` (string): 电话号码
- `phone_number_verified` (boolean): 电话号码是否已验证
- `given_name` (string): 名
- `family_name` (string): 姓
- `name` (string): 全名
- `middle_name` (string): 中间名
- `nickname` (string): 昵称
- `preferred_username` (string): 首选用户名
- `picture` (string): 头像URL
- `profile` (string): 个人资料URL
- `website` (string): 网站URL
- `gender` (string): 性别
- `birthdate` (string): 生日
- `zoneinfo` (string): 时区
- `locale` (string): 语言区域
- `address` (object): 地址信息

**不同提供商的claims示例**：

**Google**：
```json
{
  "email": "user@gmail.com",
  "email_verified": true,
  "given_name": "John",
  "family_name": "Doe",
  "picture": "https://lh3.googleusercontent.com/a/...",
  "locale": "en"
}
```

**Facebook**：
```json
{
  "email": "user@facebook.com",
  "given_name": "John",
  "family_name": "Doe",
  "name": "John Doe",
  "nickname": "Johnny",
  "picture": "https://graph.facebook.com/.../picture"
}
```

**GitHub**：
```json
{
  "email": "user@github.com",
  "name": "johndoe",
  "given_name": "johndoe",
  "picture": "https://avatars.githubusercontent.com/u/...",
  "profile": "https://github.com/johndoe"
}
```

**Apple**：
```json
{
  "email": "user@privaterelay.appleid.com",
  "given_name": "John",
  "family_name": "Doe"
}
```

**特点**：
- 标准化的格式，便于跨提供商使用
- 用于账户链接：通过 `email` 或 `phone_number` 识别同一用户
- 用于显示用户信息：`GetDisplayName()` 方法从claims中提取显示名称
- 可以更新：当用户重新授权时，会更新claims

**索引**：
```sql
-- 根据email查找OAuth身份（用于账户链接）
CREATE INDEX _auth_identity_oauth_claim_email 
ON _auth_identity_oauth (app_id, (claims ->> 'email'));

-- 根据phone_number查找OAuth身份
CREATE INDEX _auth_identity_oauth_claim_phone_number 
ON _auth_identity_oauth (app_id, (claims ->> 'phone_number'));
```

---

### 9. `profile` (jsonb, NULLABLE)

**作用**：
- 存储OAuth提供商返回的**原始用户资料**（Raw Profile）
- 保留提供商的完整原始数据，不进行标准化处理
- 用于保留提供商特有的字段和数据

**来源**：
- 直接从OAuth提供商的API响应中获取
- 不同提供商返回的数据结构不同
- **代码位置**：
  - Google: `pkg/lib/oauthrelyingparty/google/provider.go:164`
  - Facebook: `pkg/lib/oauthrelyingparty/facebook/provider.go:143`
  - GitHub: `pkg/lib/oauthrelyingparty/github/provider.go:96`

```go
authInfo.ProviderRawProfile = userProfile  // 原始响应
// 然后存储到 profile 字段
profile, err := json.Marshal(i.UserProfile)
```

**可能的内容**：
- JSON对象，包含提供商返回的完整原始数据

**不同提供商的profile示例**：

**Google** (ID Token claims)：
```json
{
  "iss": "https://accounts.google.com",
  "sub": "109876543210987654321",
  "aud": "123456789-abc.apps.googleusercontent.com",
  "exp": 1234567890,
  "iat": 1234567890,
  "azp": "123456789-abc.apps.googleusercontent.com",
  "email": "user@gmail.com",
  "email_verified": true,
  "name": "John Doe",
  "picture": "https://lh3.googleusercontent.com/a/...",
  "given_name": "John",
  "family_name": "Doe",
  "locale": "en"
}
```

**Facebook** (Graph API响应)：
```json
{
  "id": "1234567890123456",
  "name": "John Doe",
  "first_name": "John",
  "last_name": "Doe",
  "email": "user@facebook.com",
  "short_name": "Johnny",
  "picture": {
    "data": {
      "url": "https://graph.facebook.com/.../picture",
      "is_silhouette": false
    }
  }
}
```

**GitHub** (API响应)：
```json
{
  "login": "johndoe",
  "id": 12345678,
  "node_id": "MDQ6VXNlcjEyMzQ1Njc4",
  "avatar_url": "https://avatars.githubusercontent.com/u/...",
  "gravatar_id": "",
  "url": "https://api.github.com/users/johndoe",
  "html_url": "https://github.com/johndoe",
  "name": "John Doe",
  "company": "Example Corp",
  "blog": "https://johndoe.dev",
  "location": "San Francisco",
  "email": "user@github.com",
  "bio": "Software Developer",
  "twitter_username": "johndoe",
  "public_repos": 42,
  "followers": 100,
  "following": 50,
  "created_at": "2010-01-01T00:00:00Z",
  "updated_at": "2024-01-01T00:00:00Z"
}
```

**Apple** (ID Token claims)：
```json
{
  "iss": "https://appleid.apple.com",
  "sub": "001234.567890abcdef.1234",
  "aud": "com.example.app",
  "exp": 1234567890,
  "iat": 1234567890,
  "email": "user@privaterelay.appleid.com",
  "email_verified": true
}
```

**特点**：
- **可空字段**：如果配置了 `DoNotStoreIdentityAttributes`，此字段可能为空或包含空对象
- **保留原始数据**：不进行标准化，保留提供商的所有字段
- **用于调试和审计**：可以查看提供商返回的完整原始数据
- **特殊处理**：对于Apple，首次授权时会包含 `given_name` 和 `family_name`，后续授权可能不包含

**特殊场景 - DoNotStoreIdentityAttributes**：
如果OAuth提供商配置了 `do_not_store_identity_attributes: true`，则：
- `profile` 会被清空为 `{}`
- `claims` 也会被清空为 `{}`
- **代码位置**：`pkg/lib/authn/identity/oauth/provider.go:99-104`

```go
func (p *Provider) stripPII(i *identity.OAuth) {
    // Strip and replace it with an empty map.
    i.UserProfile = make(map[string]any)
    i.Claims = make(map[string]any)
}
```

---

## 数据流程

### 创建OAuth身份的数据流程

1. **用户授权**：用户通过OAuth提供商（如Google）进行授权
2. **获取用户资料**：调用提供商的API获取用户信息
   - **代码位置**：`pkg/lib/oauthrelyingparty/google/provider.go:107-179`
3. **提取标准声明**：从原始资料中提取标准声明
   - **代码位置**：`pkg/lib/authn/stdattrs/extract.go:34-67`
4. **创建OAuth身份对象**：
   ```go
   i := &identity.OAuth{
       ID:                uuid.New(),
       UserID:            userID,
       ProviderID:        spec.ProviderID,        // 包含 provider_type 和 provider_keys
       ProviderSubjectID: spec.SubjectID,        // provider_user_id
       UserProfile:       spec.RawProfile,        // profile 字段
       Claims:            spec.StandardClaims,    // claims 字段
   }
   ```
5. **存储到数据库**：
   - 先插入 `_auth_identity` 表
   - 再插入 `_auth_identity_oauth` 表
   - **代码位置**：`pkg/lib/authn/identity/oauth/store.go:200-260`

### 更新OAuth身份的数据流程

1. **用户重新授权**：用户再次通过OAuth提供商授权
2. **获取最新用户资料**：调用提供商API获取最新信息
3. **更新claims和profile**：
   - 对于非Apple提供商：直接替换
   - 对于Apple：特殊合并逻辑（保留首次授权的given_name和family_name）
   - **代码位置**：`pkg/lib/authn/identity/oauth/provider.go:77-96`
4. **更新数据库**：
   - 更新 `_auth_identity_oauth` 表的 `claims` 和 `profile` 字段
   - 更新 `updated_at` 时间戳
   - **代码位置**：`pkg/lib/authn/identity/oauth/store.go:263-305`

---

## 唯一性约束

```sql
ALTER TABLE _auth_identity_oauth
    ADD CONSTRAINT _auth_identity_oauth_key 
    UNIQUE (app_id, provider_type, provider_keys, provider_user_id);
```

**作用**：
- 确保在同一个应用（`app_id`）下，同一个提供商（`provider_type` + `provider_keys`）的同一个用户（`provider_user_id`）只能有一条记录
- 防止重复创建OAuth身份
- 支持账户链接：如果用户已存在，可以通过此约束找到现有身份

---

## 索引

为了提高查询性能，系统创建了以下索引：

```sql
-- 根据email查找OAuth身份（用于账户链接）
CREATE INDEX _auth_identity_oauth_claim_email 
ON _auth_identity_oauth (app_id, (claims ->> 'email'));

-- 根据phone_number查找OAuth身份
CREATE INDEX _auth_identity_oauth_claim_phone_number 
ON _auth_identity_oauth (app_id, (claims ->> 'phone_number'));

-- 根据preferred_username查找
CREATE INDEX _auth_identity_oauth_claim_preferred_username 
ON _auth_identity_oauth (app_id, (claims ->> 'preferred_username'));
```

---

## 使用场景

### 1. 账户链接
通过 `claims` 中的 `email` 或 `phone_number` 识别是否为同一用户：

```sql
-- 查找具有相同email的OAuth身份
SELECT * FROM _auth_identity_oauth
WHERE app_id = 'my-app'
  AND claims->>'email' = 'user@example.com';
```

### 2. 用户识别
通过 `provider_user_id` 识别用户在提供商系统中的身份：

```sql
-- 查找特定提供商用户
SELECT * FROM _auth_identity_oauth
WHERE app_id = 'my-app'
  AND provider_type = 'google'
  AND provider_user_id = '109876543210987654321';
```

### 3. 显示用户信息
从 `claims` 中提取显示名称：

```go
// 代码位置：pkg/lib/authn/identity/oauth_identity.go:54-76
func (i *OAuth) GetDisplayName() string {
    if username, ok := i.Claims["preferred_username"].(string); ok && username != "" {
        return username
    }
    if email, ok := i.Claims["email"].(string); ok && email != "" {
        return mail.MaskAddress(email)
    }
    if phoneNumber, ok := i.Claims["phone_number"].(string); ok && phoneNumber != "" {
        return phone.Mask(phoneNumber)
    }
    return ""
}
```

### 4. 数据同步
当用户重新授权时，更新 `claims` 和 `profile` 以获取最新信息。

---

## 相关代码位置

- **表定义**：`cmd/authgear/cmd/cmddatabase/migrations/authgear/20200610183245-initial_tables.sql`
- **数据模型**：`pkg/lib/authn/identity/oauth_identity.go`
- **数据存储**：`pkg/lib/authn/identity/oauth/store.go`
- **业务逻辑**：`pkg/lib/authn/identity/oauth/provider.go`
- **标准声明提取**：`pkg/lib/authn/stdattrs/extract.go`
- **OAuth提供商实现**：`pkg/lib/oauthrelyingparty/*/provider.go`
