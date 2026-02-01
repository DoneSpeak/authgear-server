# 数据库中用户与Social Login（身份提供商）的关系

## 概述

Authgear使用**三层表结构**来记录用户与Social Login（OAuth身份提供商）的关系：

1. **`_auth_user`** - 用户基础表
2. **`_auth_identity`** - 身份基础表（支持多种身份类型）
3. **`_auth_identity_oauth`** - OAuth身份详情表

## 表结构关系

```
_auth_user (用户)
    ↓ (一对多)
_auth_identity (身份)
    ↓ (一对一，当type='oauth'时)
_auth_identity_oauth (OAuth身份详情)
```

## 核心表结构

### 1. `_auth_identity` - 身份基础表

```sql
CREATE TABLE _auth_identity
(
    id        text PRIMARY KEY,
    app_id    text NOT NULL,
    type      text NOT NULL,  -- 'oauth', 'login_id', 'anonymous', 'biometric', 'passkey', 'siwe', 'ldap'
    user_id   text NOT NULL REFERENCES _auth_user (id),
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);
```

**作用**：
- 作为所有身份类型的统一入口
- 通过 `type` 字段区分不同的身份类型（OAuth、登录ID、匿名等）
- 建立用户与身份的多对一关系（一个用户可以有多个身份）

### 2. `_auth_identity_oauth` - OAuth身份详情表

```sql
CREATE TABLE _auth_identity_oauth
(
    id               text PRIMARY KEY REFERENCES _auth_identity (id),
    app_id           text                        NOT NULL,
    created_at       timestamp without time zone NOT NULL,
    updated_at       timestamp without time zone NOT NULL,
    provider_type    text                        NOT NULL,  -- 'google', 'facebook', 'github', 'apple', etc.
    provider_keys    jsonb                       NOT NULL DEFAULT '{}'::jsonb,  -- 提供商额外标识键
    provider_user_id text                        NOT NULL,  -- 提供商返回的用户唯一ID
    claims           jsonb                       NOT NULL,  -- 标准声明（email, phone_number等）
    profile          jsonb                                  -- 原始用户资料
);
```

**关键字段说明**：

- **`provider_type`**: 提供商类型
  - 例如：`google`, `facebook`, `github`, `apple`, `wechat`, `azureadv2` 等
  - 标识用户使用的是哪个OAuth提供商

- **`provider_keys`**: 提供商的额外标识键（JSONB格式）
  - 用于区分同一类型的多个提供商配置
  - 例如：同一个应用可能配置了多个Google提供商（不同的client_id）
  - 格式：`{"client_id": "xxx", "tenant": "xxx"}` 等
  - 默认值为空对象 `{}`

- **`provider_user_id`**: 提供商返回的用户唯一标识
  - 这是OAuth提供商（如Google、Facebook）返回的用户唯一ID
  - 例如：Google返回的 `sub` 字段值

- **`claims`**: 标准声明（JSONB格式）
  - 存储标准化的用户信息，如：
    - `email`: 邮箱地址
    - `phone_number`: 电话号码
    - `preferred_username`: 用户名
    - `given_name`: 名
    - `family_name`: 姓
  - 这些声明用于账户链接、用户识别等

- **`profile`**: 原始用户资料（JSONB格式）
  - 存储OAuth提供商返回的完整原始用户资料
  - 用于保留提供商的原始数据

### 3. 唯一性约束

```sql
ALTER TABLE _auth_identity_oauth
    ADD CONSTRAINT _auth_identity_oauth_key 
    UNIQUE (app_id, provider_type, provider_keys, provider_user_id);
```

**作用**：
- 确保在同一个应用（`app_id`）下，同一个提供商（`provider_type` + `provider_keys`）的同一个用户（`provider_user_id`）只能有一条记录
- 防止重复创建OAuth身份

## 数据关系示例

### 示例1：用户通过Google登录

假设用户 `user_123` 通过Google登录：

```sql
-- 1. 用户表
INSERT INTO _auth_user (id, app_id, ...) VALUES ('user_123', 'app_001', ...);

-- 2. 身份基础表
INSERT INTO _auth_identity (id, app_id, type, user_id, ...) 
VALUES ('identity_oauth_001', 'app_001', 'oauth', 'user_123', ...);

-- 3. OAuth身份详情表
INSERT INTO _auth_identity_oauth (
    id, app_id, provider_type, provider_keys, provider_user_id, claims, profile
) VALUES (
    'identity_oauth_001',
    'app_001',
    'google',                    -- 提供商类型
    '{}'::jsonb,                -- 默认空对象
    '1098765432',               -- Google返回的用户ID
    '{"email": "user@example.com", "preferred_username": "user"}'::jsonb,
    '{"sub": "1098765432", "email": "user@example.com", ...}'::jsonb
);
```

### 示例2：用户关联多个Social Login

一个用户可以有多个OAuth身份：

```sql
-- 用户 user_123 关联了Google和Facebook
-- Google身份
INSERT INTO _auth_identity (id, app_id, type, user_id, ...) 
VALUES ('identity_oauth_001', 'app_001', 'oauth', 'user_123', ...);
INSERT INTO _auth_identity_oauth (id, app_id, provider_type, provider_user_id, ...)
VALUES ('identity_oauth_001', 'app_001', 'google', 'google_user_123', ...);

-- Facebook身份
INSERT INTO _auth_identity (id, app_id, type, user_id, ...) 
VALUES ('identity_oauth_002', 'app_001', 'oauth', 'user_123', ...);
INSERT INTO _auth_identity_oauth (id, app_id, provider_type, provider_user_id, ...)
VALUES ('identity_oauth_002', 'app_001', 'facebook', 'facebook_user_456', ...);
```

### 示例3：同一类型的多个提供商配置

如果应用配置了多个Google提供商（例如不同的client_id），使用 `provider_keys` 区分：

```sql
-- Google提供商配置1
INSERT INTO _auth_identity_oauth (id, app_id, provider_type, provider_keys, provider_user_id, ...)
VALUES (
    'identity_oauth_001', 
    'app_001', 
    'google',
    '{"client_id": "client_001"}'::jsonb,  -- 使用client_id区分
    'google_user_123',
    ...
);

-- Google提供商配置2
INSERT INTO _auth_identity_oauth (id, app_id, provider_type, provider_keys, provider_user_id, ...)
VALUES (
    'identity_oauth_002', 
    'app_001', 
    'google',
    '{"client_id": "client_002"}'::jsonb,  -- 不同的client_id
    'google_user_123',
    ...
);
```

## 查询示例

### 查询用户的所有OAuth身份

```sql
SELECT 
    u.id AS user_id,
    i.id AS identity_id,
    o.provider_type,
    o.provider_user_id,
    o.claims->>'email' AS email
FROM _auth_user u
JOIN _auth_identity i ON i.user_id = u.id
JOIN _auth_identity_oauth o ON o.id = i.id
WHERE u.id = 'user_123'
  AND i.type = 'oauth';
```

### 根据提供商用户ID查找用户

```sql
SELECT 
    u.id AS user_id,
    o.provider_type,
    o.provider_user_id
FROM _auth_user u
JOIN _auth_identity i ON i.user_id = u.id
JOIN _auth_identity_oauth o ON o.id = i.id
WHERE o.provider_type = 'google'
  AND o.provider_user_id = '1098765432'
  AND o.app_id = 'app_001';
```

### 查询用户通过哪些提供商登录

```sql
SELECT DISTINCT
    o.provider_type,
    COUNT(*) AS identity_count
FROM _auth_identity i
JOIN _auth_identity_oauth o ON o.id = i.id
WHERE i.user_id = 'user_123'
GROUP BY o.provider_type;
```

## 索引

为了提高查询性能，系统创建了以下索引：

```sql
-- 用户ID索引
CREATE INDEX _auth_identity_user_id ON _auth_identity (user_id);

-- 应用ID和用户ID索引
CREATE INDEX _auth_identity_app_id_user_id ON _auth_identity (app_id, user_id);

-- 根据email查找OAuth身份
CREATE INDEX _auth_identity_oauth_claim_email 
ON _auth_identity_oauth (app_id, (claims ->> 'email'));

-- 根据phone_number查找OAuth身份
CREATE INDEX _auth_identity_oauth_claim_phone_number 
ON _auth_identity_oauth (app_id, (claims ->> 'phone_number'));
```

## 设计要点

1. **多身份支持**：一个用户可以有多个OAuth身份（如同时关联Google和Facebook）

2. **账户链接**：通过 `claims` 中的 `email` 或 `phone_number` 可以识别是否为同一用户，实现账户链接

3. **提供商区分**：通过 `provider_type` + `provider_keys` 可以区分同一类型的多个提供商配置

4. **数据完整性**：通过外键约束和唯一性约束保证数据一致性

5. **灵活扩展**：使用JSONB存储 `claims` 和 `profile`，可以灵活存储不同提供商返回的不同字段

## 相关代码位置

- **数据模型定义**：`pkg/lib/authn/identity/oauth_identity.go`
- **数据存储**：`pkg/lib/authn/identity/oauth/store.go`
- **业务逻辑**：`pkg/lib/authn/identity/oauth/provider.go`
- **数据库迁移**：`cmd/authgear/cmd/cmddatabase/migrations/authgear/20200610183245-initial_tables.sql`
