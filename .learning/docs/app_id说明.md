# app_id 在 Authgear 中的定位和建模

## 概述

`app_id` 是 Authgear 中**多租户架构（Multi-tenancy）的核心标识符**，用于区分和管理不同的应用实例。每个应用（App）都有唯一的 `app_id`，所有与该应用相关的数据都通过 `app_id` 进行隔离。

## app_id 的定义

### 类型定义

在代码中，`app_id` 被定义为 `AppID` 类型：

**位置**：`pkg/lib/config/config.go`

```go
type AppID string
```

### 配置中的定义

在应用配置文件中，`app_id` 作为应用配置的根字段：

**位置**：`pkg/lib/config/config.go`

```go
type AppConfig struct {
    ID AppID `json:"id"`  // 这就是 app_id
    
    HTTP *HTTPConfig `json:"http"`
    // ... 其他配置
}
```

**配置文件示例**（`authgear.yaml`）：

```yaml
id: my-app  # 这就是 app_id
http:
  public_origin: https://my-app.authgear.com
# ... 其他配置
```

## app_id 在 Authgear 中的定位

### 1. 多租户架构的核心

Authgear 采用**共享数据库、逻辑隔离**的多租户架构：

- **共享数据库**：所有应用共享同一个 PostgreSQL 数据库
- **逻辑隔离**：通过 `app_id` 字段在所有表中进行数据隔离
- **配置隔离**：每个应用有独立的配置文件（存储在 `_portal_config_source` 表）

### 2. 数据隔离机制

所有业务数据表都包含 `app_id` 字段，确保数据隔离：

```sql
-- 用户表
CREATE TABLE _auth_user (
    id text PRIMARY KEY,
    app_id text NOT NULL,  -- 应用隔离
    ...
);

-- 身份表
CREATE TABLE _auth_identity (
    id text PRIMARY KEY,
    app_id text NOT NULL,  -- 应用隔离
    user_id text NOT NULL,
    ...
);

-- OAuth身份表
CREATE TABLE _auth_identity_oauth (
    id text PRIMARY KEY,
    app_id text NOT NULL,  -- 应用隔离
    provider_type text NOT NULL,
    ...
);
```

### 3. 查询自动过滤

通过 `SQLBuilderApp` 自动在所有查询中添加 `app_id` 过滤：

**位置**：`pkg/lib/infra/db/sql_builder.go`

```go
type SQLBuilderApp struct {
    sqlBuilderSchema
    builder sq.StatementBuilderType
    appID   string  // 存储当前应用的 app_id
}

// 自动在 UPDATE 和 DELETE 中添加 app_id 过滤
func (b SQLBuilderApp) Update(table string) sq.UpdateBuilder {
    builder := b.builder.Update(table)
    builder = builder.Where("app_id = ?", b.appID)  // 自动过滤
    return builder
}

func (b SQLBuilderApp) Delete(from string) sq.DeleteBuilder {
    builder := b.builder.Delete(from)
    builder = builder.Where("app_id = ?", b.appID)  // 自动过滤
    return builder
}

// INSERT 时自动添加 app_id
func (b SQLBuilderApp) Insert(into string) InsertBuilder {
    builder := b.builder.Insert(into)
    builder = builder.Columns("app_id")  // 自动添加 app_id 列
    return InsertBuilder{
        builder: builder,
        appID:   b.appID, 
    }
}
```

## app_id 的解析机制

### 1. 从域名解析 app_id

Authgear 支持通过域名自动解析 `app_id`：

**位置**：`pkg/lib/config/configsource/database.go`

```go
func (d *Database) resolveAppIDByDomain(ctx context.Context, r *http.Request) (string, error) {
    host := httputil.GetHost(r, bool(d.TrustProxy))
    
    // 从 _portal_domain 表查询域名对应的 app_id
    appID, err := store.GetAppIDByDomain(ctx, host)
    return appID, nil
}
```

**数据库表**：`_portal_domain`

```sql
CREATE TABLE _portal_domain (
    id text PRIMARY KEY,
    app_id text NOT NULL,  -- 域名关联的应用ID
    domain text NOT NULL,
    apex_domain text NOT NULL,
    ...
    UNIQUE (apex_domain)
);
```

### 2. 从路径解析 app_id

也支持通过 URL 路径解析（如 `/app/{appid}/...`）：

**位置**：`pkg/lib/config/configsource/database.go`

```go
func (d *Database) resolveAppIDByPath(ctx context.Context, r *http.Request) (string, error) {
    appid := httproute.GetParam(r, "appid")
    return appid, nil
}
```

### 3. 配置上下文解析

解析 `app_id` 后，加载对应的应用配置：

**位置**：`pkg/lib/config/configsource/database.go`

```go
func (d *Database) ResolveContext(ctx context.Context, appID string, 
    fn func(context.Context, *config.AppContext) error) error {
    
    // 从 _portal_config_source 表加载应用配置
    appCtx, err := app.Load(ctx, d)
    ctx = config.WithAppContext(ctx, appCtx)
    
    return fn(ctx, appCtx)
}
```

**数据库表**：`_portal_config_source`

```sql
CREATE TABLE _portal_config_source (
    id text PRIMARY KEY,
    app_id text NOT NULL UNIQUE,  -- 应用ID唯一
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    data jsonb NOT NULL,  -- 存储完整的应用配置（authgear.yaml）
    plan_name text NOT NULL
);
```

## app_id 的建模位置

### 1. 配置层（Configuration Layer）

**位置**：
- `pkg/lib/config/config.go` - AppID 类型定义和 AppConfig 结构
- `pkg/lib/config/configsource/` - 配置源管理（数据库、本地文件等）

**职责**：
- 定义 `AppID` 类型
- 管理应用配置的加载和解析
- 提供配置上下文（AppContext）

### 2. 数据访问层（Data Access Layer）

**位置**：
- `pkg/lib/infra/db/sql_builder.go` - SQLBuilderApp 自动添加 app_id 过滤
- `pkg/lib/infra/db/appdb/` - 应用数据库访问封装

**职责**：
- 自动在所有 SQL 操作中添加 `app_id` 过滤
- 确保数据隔离
- 提供类型安全的数据库访问接口

### 3. 业务逻辑层（Business Logic Layer）

**位置**：
- `pkg/lib/authn/identity/` - 身份管理
- `pkg/lib/session/` - 会话管理
- `pkg/lib/user/` - 用户管理
- 等等...

**职责**：
- 使用 `SQLBuilderApp` 进行数据操作
- 确保所有业务操作都在正确的应用上下文中执行

### 4. Portal 管理层（Portal Management Layer）

**位置**：
- `pkg/portal/service/app.go` - 应用服务
- `pkg/portal/service/domain.go` - 域名服务
- `pkg/portal/model/app.go` - 应用模型

**职责**：
- 应用的创建、更新、删除
- 域名管理（域名与 app_id 的关联）
- 协作者管理（用户与应用的关系）

### 5. 数据库层（Database Layer）

**位置**：
- `cmd/authgear/cmd/cmddatabase/migrations/authgear/` - Authgear 迁移文件
- `cmd/portal/cmd/cmddatabase/migrations/portal/` - Portal 迁移文件

**职责**：
- 定义所有表的 `app_id` 字段
- 创建 `app_id` 相关的索引
- 定义唯一性约束（通常包含 `app_id`）

## app_id 的使用场景

### 1. 数据查询

所有查询都自动包含 `app_id` 过滤：

```go
// 代码示例
builder := s.SQLBuilderApp.
    Select("id", "email").
    From(s.SQLBuilderApp.TableName("_auth_user")).
    Where("email = ?", email)
// SQLBuilderApp 会自动确保只查询当前应用的数据
```

### 2. 数据插入

插入时自动添加 `app_id`：

```go
builder := s.SQLBuilderApp.
    Insert(s.SQLBuilderApp.TableName("_auth_user")).
    Columns("id", "email").
    Values(userID, email)
// app_id 会自动添加到插入语句中
```

### 3. 数据更新和删除

更新和删除时自动过滤 `app_id`：

```go
builder := s.SQLBuilderApp.
    Update(s.SQLBuilderApp.TableName("_auth_user")).
    Set("email", newEmail).
    Where("id = ?", userID)
// 自动添加 WHERE app_id = ? 条件
```

### 4. 唯一性约束

唯一性约束通常包含 `app_id`：

```sql
-- 确保同一应用内邮箱唯一
ALTER TABLE _auth_identity_login_id
    ADD CONSTRAINT _auth_identity_login_id_key 
    UNIQUE (app_id, unique_key);

-- 确保同一应用内OAuth身份唯一
ALTER TABLE _auth_identity_oauth
    ADD CONSTRAINT _auth_identity_oauth_key 
    UNIQUE (app_id, provider_type, provider_keys, provider_user_id);
```

## Portal 数据库中的 app_id

### 应用管理表

**`_portal_config_source`** - 应用配置源
```sql
CREATE TABLE _portal_config_source (
    id text PRIMARY KEY,
    app_id text NOT NULL UNIQUE,  -- 应用ID
    data jsonb NOT NULL,          -- 应用配置（authgear.yaml）
    plan_name text NOT NULL
);
```

**`_portal_domain`** - 域名映射
```sql
CREATE TABLE _portal_domain (
    id text PRIMARY KEY,
    app_id text NOT NULL,  -- 域名关联的应用
    domain text NOT NULL,
    apex_domain text NOT NULL UNIQUE
);
```

**`_portal_app_collaborator`** - 应用协作者
```sql
CREATE TABLE _portal_app_collaborator (
    id text PRIMARY KEY,
    app_id text NOT NULL,  -- 应用ID
    user_id text NOT NULL, -- Portal用户ID
    role text NOT NULL,
    UNIQUE (app_id, user_id)
);
```

## 应用生命周期

### 1. 创建应用

**位置**：`pkg/portal/service/app.go`

```go
func (s *AppService) Create(ctx context.Context, userID string, id string) (*model.App, error) {
    // 1. 验证 app_id
    if err := s.validateAppID(ctx, id); err != nil {
        return nil, err
    }
    
    // 2. 创建应用配置
    err = s.AppConfigs.Create(ctx, createAppOpts)
    
    // 3. 创建默认域名
    err = s.DefaultDomains.CreateAllDefaultDomains(ctx, id)
    
    // 4. 添加应用所有者
    err = s.AppAuthz.AddAuthorizedUser(ctx, id, userID, model.CollaboratorRoleOwner)
    
    return app, nil
}
```

### 2. 加载应用配置

**位置**：`pkg/lib/config/configsource/database.go`

```go
// 从 _portal_config_source 表加载配置
func (a *dbApp) Load(ctx context.Context, d *Database) (*config.AppContext, error) {
    data, err := store.GetDatabaseSourceByAppID(ctx, a.appID)
    // 解析 JSON 配置
    appConfig, err := config.ParseAppConfig(data.Data)
    return &config.AppContext{
        Config: &config.Config{
            AppConfig: appConfig,
        },
    }, nil
}
```

### 3. 应用上下文传递

**位置**：`pkg/lib/config/context.go`

```go
// 将 AppContext 存储到 context 中
func WithAppContext(ctx context.Context, appCtx *AppContext) context.Context {
    return context.WithValue(ctx, appContextKey, appCtx)
}

// 从 context 中获取 AppContext
func GetAppContext(ctx context.Context) *AppContext {
    return ctx.Value(appContextKey).(*AppContext)
}
```

## 索引和性能

### app_id 相关索引

为了提高查询性能，系统创建了 `app_id` 相关的索引：

```sql
-- 用户表索引
CREATE INDEX _auth_identity_app_id_user_id ON _auth_identity (app_id, user_id);
CREATE INDEX _auth_identity_app_id ON _auth_identity (app_id);

-- OAuth授权表索引
CREATE INDEX _auth_oauth_authorization_app_id_user_id 
    ON _auth_oauth_authorization (app_id, user_id);
```

## 总结

### app_id 的核心作用

1. **多租户隔离**：确保不同应用的数据完全隔离
2. **配置管理**：每个应用有独立的配置
3. **域名映射**：通过域名自动识别应用
4. **数据安全**：防止跨应用数据访问

### 关键设计点

1. **自动过滤**：通过 `SQLBuilderApp` 自动添加 `app_id` 过滤，减少人为错误
2. **类型安全**：使用 `AppID` 类型确保类型安全
3. **配置隔离**：每个应用的配置存储在独立的记录中
4. **域名解析**：支持通过域名自动识别应用

### 相关文件位置

- **类型定义**：`pkg/lib/config/config.go`
- **SQL构建器**：`pkg/lib/infra/db/sql_builder.go`
- **配置源**：`pkg/lib/config/configsource/`
- **应用服务**：`pkg/portal/service/app.go`
- **数据库迁移**：`cmd/*/migrations/*/`
