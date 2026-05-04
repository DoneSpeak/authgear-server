# Authgear API 教程

基于 [官方 API 参考](https://docs.authgear.com/reference/apis) 与项目 [docs/specs](../../docs/specs/) 整理。所有路径相对应用 endpoint（默认 `https://[myapp].authgear.cloud`）。

## 通用约定

- 成功：JSON 体含 `result`。
- 失败：JSON 体含 `error`（name、reason、message、code、info）。部分 API 以 HTTP 状态码与响应头表示结果（如 Resolver）。

---

## 1. OAuth 2.0 / OpenID Connect

| 路径 | 用途 | 认证 |
|------|------|------|
| `/.well-known/openid-configuration` | OIDC 配置（authorization/token/jwks 等） | 无 |
| `/.well-known/oauth-authorization-server` | OAuth 授权服务器元数据 | 无 |
| `/oauth2/authorize` | 授权端点 | 浏览器/Cookie |
| `/oauth2/consent` | 同意端点 | 同上 |
| `/oauth2/token` | Token 端点（code 换 token、refresh） | client 凭证或 body 参数 |
| `/oauth2/revoke` | 撤销 token | 无 |
| `/oauth2/jwks` | JWK Set | 无 |
| `/oauth2/userinfo` | UserInfo（需 Access Token） | Bearer |
| `/oauth2/end_session` | 登出 | 无 |

仅支持 Authorization Code Flow + PKCE。详见 [docs/specs/oidc.md](../../docs/specs/oidc.md)。

---

## 2. Admin API

| 路径 | 用途 | 认证 |
|------|------|------|
| `/_api/admin/graphql` | GraphQL：用户 CRUD、GetUsers、Events 等 | Admin API JWT |

服务端调用，用于管理用户与项目。Events 等 REST 风格接口见 spec。详见 [docs/specs/api-admin.md](../../docs/specs/api-admin.md)、[admin-api-get-users.md](../../docs/specs/admin-api-get-users.md)。

---

## 3. User Import API

| 路径 | 方法 | 用途 | 认证 |
|------|------|------|------|
| `/_api/admin/users/import` | POST | 发起批量导入 | Admin API JWT |
| `/_api/admin/users/import/{ID}` | GET | 查询导入任务状态 | Admin API JWT |

异步任务，不触发 user 相关 hook。详见 [docs/specs/user-import.md](../../docs/specs/user-import.md)。

---

## 4. User Export API

| 路径 | 方法 | 用途 | 认证 |
|------|------|------|------|
| `/_api/admin/users/export` | POST | 发起用户导出 | Admin API JWT |
| `/_api/admin/users/export/{Task ID}` | GET | 查询导出状态并获取下载 URL | Admin API JWT |

异步任务，支持 CSV/ndjson。详见 [docs/specs/user-export.md](../../docs/specs/user-export.md)。

---

## 5. Authentication Flow API

| 路径 | 用途 | 认证 |
|------|------|------|
| `/api/v1/authentication_flows` | 创建/推进认证流程（HTTP） | 无（流程内状态 token） |
| `/api/v1/authentication_flows/ws` | 监听流程状态变更（WebSocket） | query 参数 |

用于自定义 Auth UI，与默认 Auth UI 能力一致。详见 [docs/specs/authentication-flow-api-reference.md](../../docs/specs/authentication-flow-api-reference.md)。

---

## 6. Resolver

| 路径 | 用途 | 认证 |
|------|------|------|
| `/_resolver/resolve` | 校验请求中的 Cookie 或 Authorization，回写 `x-authgear-*` 头 | 转发原请求的 Cookie/Authorization |

不写 body，仅在响应头中返回 session/user 等信息（如 `x-authgear-session-valid`、`x-authgear-user-id`、`x-authgear-user-roles` 等）。详见 [docs/specs/api-resolver.md](../../docs/specs/api-resolver.md)。

---

## 7. 其他 API

### 7.1 匿名用户与 Challenge

| 路径 | 方法 | 用途 | 认证 |
|------|------|------|------|
| `/oauth2/challenge` | POST | 获取一次性短效 challenge（匿名/生物识别等） | 无 |
| `/api/anonymous_user/signup` | POST | 匿名用户注册（Web SDK） | 无 / Cookie 或 refresh_token |
| `/api/anonymous_user/promotion_code` | POST | 获取匿名用户升级用 promotion_code | Cookie 或 refresh_token |

详见 [docs/specs/api.md](../../docs/specs/api.md)。

### 7.2 Account Management API

端用户在自己已登录状态下管理身份、认证器、资料、会话等。基础路径为 `/api/v1/`，如 `/api/v1/account/identification`。认证：Cookie 或 Authorization（Bearer）。

- 身份：增删改邮箱/手机/用户名、OAuth 账号、生物识别、Passkey
- 认证器：密码、TOTP、OOB-OTP、恢复码
- 资料：标准/自定义属性、头像（含「如何修改用户基本信息」见 **[User-Profile接口说明.md](./User-Profile接口说明.md)**）
- 会话：列表、撤销单会话、终止其他会话

详见 [docs/specs/account-management-api.md](../../docs/specs/account-management-api.md)。

---

## 其他特殊 URL

- `/` — Web UI 入口（非授权端点，需通过 SDK 发起认证）
- `/settings` — 默认用户设置页
