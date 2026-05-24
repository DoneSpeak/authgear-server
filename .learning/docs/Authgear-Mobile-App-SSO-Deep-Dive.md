# Authgear Mobile App 单点登录(SSO)深度分析

## 核心问题：Mobile App 用什么替代 Session Cookie？

**答案：Mobile App 使用 OAuth 2.0 Refresh Token（OfflineGrant）进行认证，这是 token-based 机制，而非 cookie-based 的 session 机制。但通过 `x_sso_enabled` 参数和 Device SSO 两种方式，Mobile App 可以与 Browser Web 共享认证状态，实现 SSO。**

---

## 1. 两种 Session 类型：Web  vs Mobile

Authgear 只有两种持久化的 core session 类型：

| Session 类型 | 常量 | 载体 | 使用方 |
|---|---|---|---|
| **IDPSession** | `TypeIdentityProvider = "idp"` | HTTP Cookie (`{app_id}_session`) | Web Browser |
| **OfflineGrant** | `TypeOfflineGrant = "offline_grant"` | OAuth Refresh Token (`{grantID}.{token}`) | Mobile App / SPA |

代码定义：`pkg/lib/session/session.go:12-17`

```go
const (
    TypeIdentityProvider Type = "idp"
    TypeOfflineGrant     Type = "offline_grant"
)
```

### 两者的关系

OfflineGrant **不是** IDPSession 的替代品——它们是**不同层次**的概念：
- **IDPSession** = 用户的登录会话（cookie-based），表示"这个浏览器已经登录了"
- **OfflineGrant** = OAuth 的持久化授权（token-based），表示"这个客户端被授权访问用户数据"

**一个 Mobile App 持有 OfflineGrant（refresh token），但没有 IDPSession cookie。Mobile App 使用 refresh token 通过 OAuth Token Endpoint 换取 access token，然后用 access token 调用 API。**

---

## 2. Mobile App 如何实现同设备 SSO？

Authgear 为 Mobile App 提供了 **三种 SSO 机制**：

### 2.1 Browser SSO（Cookie 共享）

**适用场景**：Mobile App 与 System Browser 之间共享登录状态

**原理**：Mobile App 的 WebView 与系统浏览器共享 Cookie。配置方式：
- 旧版 SDK：`shareSessionWithSystemBrowser: true`
- 新版 SDK：`ssoEnabled: true`

**流程**：
1. 用户在系统浏览器中登录（IDPSession Cookie 被设置）
2. Mobile App 打开 WebView 发起 OAuth authorize 请求，带上 `x_sso_enabled=true`
3. WebView 与系统浏览器共享 Cookie，因此 IDPSession Cookie 自动生效
4. 认证流程检测到已有 IDP Session，"Continue As..." 或直接完成
5. 生成的 OfflineGrant 与 IDPSession 关联（`SSOEnabled=true`, `IDPSessionID` 记录在案）

**OAuth 参数**：`x_sso_enabled=true`
- `x_sso_enabled=true` → IdP Session Cookie 被设置，OfflineGrant 加入 SSO Group
- `x_sso_enabled=false` → IdP Session Cookie 被抑制，OfflineGrant 独立

代码：`pkg/lib/oauth/protocol/authz.go:79-97`
```go
func (r AuthorizationRequest) SuppressIDPSessionCookie() bool {
    if r["x_sso_enabled"] != "" {
        return r["x_sso_enabled"] != "true"
    }
    // backward compatibility
    if r["x_suppress_idp_session_cookie"] != "" {
        return r["x_suppress_idp_session_cookie"] == "true"
    }
    return false
}
```

**SSO Group 关联**（在 Issuing OfflineGrant 时）：
代码：`pkg/lib/oauth/handler/handler_token.go:1792-1809`
```go
var offlineGrantIDPSessionID string
switch session.Type(info.AuthenticatedBySessionType) {
case session.TypeIdentityProvider:
    offlineGrantIDPSessionID = info.AuthenticatedBySessionID
default:
    // no idp session id
}

opts := IssueOfflineGrantOptions{
    IDPSessionID: offlineGrantIDPSessionID,
    SSOEnabled:   code.AuthorizationRequest.SSOEnabled(),
    // ...
}
```

**Reference spec**：`docs/specs/oidc-sso-browser.md`

---

### 2.2 Device SSO / Native SSO（基于 OIDC Native SSO 规范）

**适用场景**：同一厂商的多个 Native App 之间共享登录状态（无 Browser 参与）

**原理**：基于 [OIDC Native SSO](https://openid.net/specs/openid-connect-native-sso-1_0.html) 规范，使用 `device_secret` 作为设备级凭证，结合 Token Exchange (RFC 8693) 实现跨 App 认证转移。

**架构要点**：

1. **`device_sso` OAuth Scope**：`oauth.DeviceSSOScope = "device_sso"`
2. **`device_secret`**：设备级密钥，32 位随机字符串（SHA256 哈希后存储在 `OfflineGrant.DeviceSecretHash`）
3. **`ds_hash` Claim**：ID Token 中包含 `ds_hash`（device_secret 的 SHA256 哈希），用于跨 App 验证
4. **Token Exchange Grant**：使用 `grant_type=urn:ietf:params:oauth:grant-type:token-exchange` 转移认证
5. **DPoP 绑定**：device_secret 通过 DPoP 绑定到设备硬件密钥

**流程**：

```
App 1 (首次认证) → App 2 (免交互登录)
─────────────────────────────────────────────────────────────────

App 1:
1. 发起 OAuth authorize 请求: scope=device_sso, x_sso_enabled=true
2. 用户完成登录流程
3. 调用 /oauth2/token (grant_type=authorization_code)
4. Server 返回:
   - refresh_token
   - id_token (含 ds_hash = SHA256(device_secret))
   - device_secret ← 存储在 iOS Keychain / Android Keystore
5. SDK 将 device_secret + id_token 写入共享存储
   (iOS App Group / Android AccountManager)

App 2 (同一设备):
1. 从共享存储读取 device_secret + id_token
2. 调用 checkDeviceSSOPossible() 确认可用
3. 调用 authenticateDeviceSSO():
   POST /oauth2/token
   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
   subject_token=<id_token>
   subject_token_type=urn:ietf:params:oauth:token-type:id_token
   actor_token=<device_secret>
   actor_token_type=urn:x-oath:params:oauth:token-type:device-secret
   scope=device_sso
4. Server 验证:
   - subject_token.ds_hash == SHA256(actor_token)
   - subject_token.sid 指向有效 session
   - client_id 和 subject_token.aud 都允许 Native SSO
   - scope 是原 refresh token scope 的子集
5. Server 返回新的 refresh_token + access_token + id_token
6. App 2 无需用户交互即可完成认证
```

**Server 端验证流程**（Token Exchange handler）：

代码：`pkg/lib/oauth/handler/handler_token.go:780-982`（handleTokenExchange 方法）

关键验证：
```go
// 1. device_sso scope required
// 2. audience = origin of endpoint
// 3. subject_token 是有效的 ID token (过期也可)
// 4. actor_token 是有效的 device_secret
// 5. ds_hash 验证: subject_token.ds_hash == SHA256(actor_token)
// 6. sid 指向有效 session
// 7. client_id 和 aud 都允许 Native SSO
// 8. scope 是原 session scope 的子集
```

**Device Secret 生成**：

代码：`pkg/lib/oauth/handler/service_token.go:415-420`
```go
func (s *TokenService) IssueDeviceSecret(ctx context.Context, resp protocol.TokenResponse) (deviceSecretHash string) {
    deviceSecret := s.GenerateToken()
    deviceSecretHash = oauth.HashToken(deviceSecret)
    resp.DeviceSecret(deviceSecret)
    return deviceSecretHash
}
```

**Feature 必须 `PreAuthenticatedURLEnabled`**：
代码：`pkg/lib/oauth/scope.go:249-251`
```go
if s == DeviceSSOScope && !client.PreAuthenticatedURLEnabled {
    return protocol.NewError("invalid_scope", "device_sso is not allowed for this client")
}
```

**OfflineGrant 的 Device SSO 相关字段**：
代码：`pkg/lib/oauth/grant_offline.go:56-62`
```go
DeviceSecretHash    string `json:"device_secret_hash"`      // SHA256(device_secret)
DeviceSecretDPoPJKT string `json:"device_secret_dpop_jkt"`  // DPoP 绑定
```

**ID Token 中的 `ds_hash` Claim**：当 OfflineGrant 持有 DeviceSecretHash 时，签发 ID Token 时会包含 `ds_hash` claim。

**Reference spec**：`docs/specs/oidc-native-sso.md`

---

### 2.3 App2App SSO（跨 App 授权码转移）

**适用场景**：同一设备上，一个 App 将其认证转移给另一个 App（无需共享存储，通过 Challenge/Response 实现）

**原理**：使用设备密钥对（JWK）+ JWT Challenge 机制，在 App 之间转移授权码。

**流程**：
1. App 1 首次登录时，SDK 生成设备密钥对（JWK），公钥注册到 Server（存储在 `OfflineGrant.App2AppDeviceKeyJWKJSON`）
2. App 2 需要登录时，发起 App2App 流程
3. App 2 通过 Universal Link / App Link 重定向到 App 1（由移动 OS 的 App Groups 或 AccountManager 代理）
4. App 1 生成一个 JWT（`vnd.authgear.app2app-request`），包含 challenge 和 scope
5. JWT 的公钥在 Header 中，签名使用设备私钥
6. App 2 调用：
```
POST /oauth2/token
grant_type=urn:authgear:params:oauth:grant-type:app2app-request
refresh_token=<App1的refresh_token>
jwt=<App1生成的JWT>
code_challenge=<PKCE verifier>
```
7. Server 验证：
   - `x_app2app_enabled=true`（feature flag + client config）
   - JWT Challenge 未被消费
   - 原始 OfflineGrant 持有 App2AppDeviceKey（或允许 insecure binding）
   - JWT 签名使用注册的设备公钥进行验证
   - 创建新的 CodeGrant 返回给 App 2
8. App 2 用 code 换取自己的 refresh_token

**配置要求**：
- 客户端 level：`x_app2app_enabled: true`
- Feature level：`app2app_enabled: true`
- 可选：`x_app2app_insecure_device_key_binding_enabled: true`（允许非硬件绑定密钥）

代码：`pkg/lib/oauth/handler/handler_token.go:1450-1577`

**App2App Device Key 结构**：
代码：`pkg/lib/app2app/provider.go`
代码：`pkg/lib/app2app/request.go`

JWT 类型：`vnd.authgear.app2app-request`

---

### 2.4 App Session Token（Web-to-Mobile Bridge）

**适用场景**：Mobile App 持有 refresh token，需要在其 WebView 中打开 settings 页面等（无需重新登录）

**流程**：
1. Mobile App 调用 `GET /oauth2/app_session_token` 传入 refresh_token
2. Server 返回一个短时效的 `app_session_token`
3. Mobile App 打开 WebView，在 authorize 请求中传入：
   ```
   login_hint=https://authgear.com/login_hint?type=app_session_token&app_session_token=TOKEN
   ```
4. Authorization Endpoint 验证 token，设置 `{app_id}_app_session` Cookie
5. WebView 中的页面通过 Cookie 获取认证状态，无需用户再次登录

代码：`pkg/auth/handler/oauth/app_session_token.go`
代码：`pkg/lib/oauth/app_session_token.go`

---

## 3. SSO Group：核心联动机制

### 什么是 SSO Group？

SSO Group 是一组共享同一 IdP Session 的会话集合。核心接口是：

代码：`pkg/lib/session/session.go:20-26`
```go
type SessionBase interface {
    SessionID() string
    SessionType() Type
    GetAuthenticationInfo() authenticationinfo.T
    SSOGroupIDPSessionID() string  // 返回 SSO Group 的 IdP Session ID
}
```

### 谁属于 SSO Group？

- **IDPSession**：`SSOGroupIDPSessionID()` = 自己的 `SessionID()`（它是 Group 的核心）
- **OfflineGrant (SSOEnabled=true)**：`SSOGroupIDPSessionID()` = `IDPSessionID`（指向关联的 IdP Session）

代码：`pkg/lib/oauth/grant_offline.go:218-242`
```go
func (g *OfflineGrant) SSOGroupIDPSessionID() string {
    if g.SSOEnabled {
        return g.IDPSessionID  // SSO 启用时返回关联的 IdP Session ID
    }
    return ""
}

func (g *OfflineGrant) IsSameSSOGroup(ss session.SessionBase) bool {
    if g.EqualSession(ss) { return true }
    if g.SSOEnabled {
        if g.SSOGroupIDPSessionID() == "" { return false }
        return g.SSOGroupIDPSessionID() == ss.SSOGroupIDPSessionID()
    }
    return false
}
```

### SSO Group 的关键行为

**1. 登出传播**：注销任意一个成员，整组失效

代码：`pkg/lib/session/manager.go:61-137`
```go
func (m *Manager) invalidate(ctx context.Context, session SessionBase, ...) {
    for _, s := range sessions {
        if s.IsSameSSOGroup(session) {
            // 注销同组的所有 session
            invalidatedSessions = append(invalidatedSessions, s)
        }
    }
}
```

**2. 生命周期联动**：IDPSession 过期 → 所有 SSO 关联 OfflineGrant 失效

代码：`pkg/lib/oauth/grant_offline_service.go:102-116`
```go
func (s *OfflineGrantService) GetOfflineGrant(ctx context.Context, id string) (*OfflineGrant, error) {
    if g.SSOEnabled && g.IDPSessionID != "" {
        idp, err := s.IDPSessions.Get(ctx, g.IDPSessionID)
        if err != nil {
            if errors.Is(err, idpsession.ErrSessionNotFound) {
                return nil, ErrGrantNotFound  // IdP Session 不存在 → OfflineGrant 不可用
            }
        }
        idpSessionExpired := s.IDPSessions.CheckSessionExpired(idp)
        if idpSessionExpired {
            return nil, ErrGrantNotFound  // IdP Session 过期 → OfflineGrant 不可用
        }
    }
    return g, nil
}
```

**3. 访问联动**：OfflineGrant 被访问时，同时更新关联 IdP Session 的 last_access 时间

代码：`pkg/lib/oauth/resolver.go:217-231`

---

## 4. 完整对比表

| 特性 | Browser Web | Mobile App (SSO Off) | Mobile App (Browser SSO) | Mobile App (Device SSO) |
|---|---|---|---|---|
| **核心凭证** | IDPSession Cookie | OfflineGrant (refresh token) | OfflineGrant + 关联 IDPSession | OfflineGrant + device_secret |
| **凭证载体** | HTTP Cookie | OAuth Refresh Token | OAuth Refresh Token | OAuth Refresh Token + 设备密钥 |
| **共享机制** | 浏览器 Cookie (Same Domain) | 无 | 共享 System Browser Cookie | 共享 iOS App Group / Android AccountManager |
| **SSO 参与** | 始终启用 | 不参与 | 通过 `x_sso_enabled=true` | 通过 `scope=device_sso` |
| **登出影响** | 清除本机 Cookie，其他 App 不受影响 | 仅该 App 登出 | 清除 Cookie + 所有 SSO 关联 App 的 refresh token 失效 | 清除 device_secret，所有共享 app 登出 |
| **`SSOGroupIDPSessionID()`** | 自身 ID | ""（空字符串） | IdP Session ID | IdP Session ID |
| **`IsSameSSOGroup()`** | 仅匹配自身 ID | 仅匹配自身 | 匹配同 IDP Session 的所有 session | 匹配同 IDP Session 的所有 session |
| **OAuth 参数** | N/A (不通过 OAuth 登录) | `x_sso_enabled=false` | `x_sso_enabled=true` | `scope=device_sso` |
| **需要用户交互** | 首次需要 | 每次都需要 | 首次需要，后续"Continue As..." | App 1 需要，App 2 无需交互 |
| **对应 OAuth 配置** | `sessionType=cookie` (Web SDK) | `ssoEnabled=false` (默认) | `ssoEnabled=true` | `isDeviceSSOEnabled=true` |
| **SDK Storage** | N/A | `PersistentTokenStorage` | `PersistentTokenStorage` | `IOSAppGroupDeviceSecretStorage` / `AndroidAccountManagerDeviceSecretStorage` |

---

## 5. Application Type 配置

Mobile App 在 Authgear 中被配置为 `native` 类型：

代码：`pkg/lib/config/oauth.go`
```go
type OAuthClientApplicationType string

const (
    OAuthClientApplicationTypeSPA            = "spa"
    OAuthClientApplicationTypeTraditionalWeb = "traditional_webapp"
    OAuthClientApplicationTypeNative         = "native"           // ← Mobile App
    OAuthClientApplicationTypeConfidential   = "confidential"
    OAuthClientApplicationTypeThirdPartyApp  = "third_party_app"
    OAuthClientApplicationTypeM2M            = "m2m"
)
```

Native App 的特性：
- **Public Client**（而非 Confidential），**必须使用 PKCE**（S256）
- `HasFullAccessScope() == true` — 可以请求完整的用户数据权限
- First-party（非第三方），可以 auto-grant authorization
- 不能使用 `client_credentials` grant（仅 M2M 可用）

SSO 相关的 OAuth Client 配置字段：
```go
type OAuthClientConfig struct {
    App2appEnabled                         bool   `json:"x_app2app_enabled"`
    App2appInsecureDeviceKeyBindingEnabled bool   `json:"x_app2app_insecure_device_key_binding_enabled"`
    MaxConcurrentSession                   int    `json:"x_max_concurrent_session"`  // 0 或 1，1 时 SSO 失效
    PreAuthenticatedURLEnabled             bool   `json:"x_pre_authenticated_url_enabled"` // Device SSO 必需
}
```

**SSO 与 MaxConcurrentSession 互斥**：如果 `x_max_concurrent_session=1`，则不允许 `x_sso_enabled=true`

代码：`pkg/lib/oauth/handler/handler_authz.go:1028-1031`
```go
if r.SSOEnabled() && client != nil && client.MaxConcurrentSession == 1 {
    return protocol.NewError("invalid_request", "'sso_enabled' must be false if config 'x_max_concurrent_session' is 1")
}
```

---

## 6. Session 类型总览

| Session 类型 | 包路径 | 用途 | 存储 | 有效期 |
|---|---|---|---|---|
| **IDPSession** | `pkg/lib/session/idpsession` | 用户身份提供者会话 (Web Cookie) | Redis | Idle 30d + 绝对 52w |
| **OfflineGrant** | `pkg/lib/oauth` | OAuth 刷新令牌会话 (Mobile/SPA) | PostgreSQL | Idle 30d + 绝对 52w |
| **OAuthSession** | `pkg/lib/oauth/oauthsession` | OAuth 授权端点临时会话 | Redis | `UserInteraction + Consent` |
| **WebappSession** | `pkg/auth/webapp` | Web 应用交互流程会话 | Redis | `GraphLifetime` |
| **AuthflowSession** | `pkg/lib/authenticationflow` | 认证流程会话 | Redis | `UserInteraction` |
| **CodeGrant** | `pkg/lib/oauth` | 授权码临时存储 | Redis | `Short` (几分钟) |
| **AccessGrant** | `pkg/lib/oauth` | Access Token 临时存储 | Redis | `AccessTokenLifetime` |
| **AppSession** | `pkg/lib/oauth` | App 会话令牌 (Bridge) | Redis | `Short` |

---

## 7. 关键 Grant Types

| Grant Type | 常量 | 用途 |
|---|---|---|
| `authorization_code` | `AuthorizationCodeGrantType` | 标准 OAuth 授权码流程 |
| `refresh_token` | `RefreshTokenGrantType` | 刷新 access token |
| `urn:ietf:params:oauth:grant-type:token-exchange` | `TokenExchangeGrantType` | **Device SSO** (RFC 8693) |
| `urn:authgear:params:oauth:grant-type:app2app-request` | `App2AppRequestGrantType` | **App2App SSO** |
| `urn:authgear:params:oauth:grant-type:anonymous-request` | `AnonymousRequestGrantType` | 匿名登录 |
| `urn:authgear:params:oauth:grant-type:biometric-request` | `BiometricRequestGrantType` | 生物识别登录 |
| `urn:authgear:params:oauth:grant-type:id-token` | `IDTokenGrantType` | ID token 刷新 |
| `urn:authgear:params:oauth:grant-type:settings-action` | `SettingsActionGrantType` | Settings 操作 |
| `client_credentials` | `ClientCredentialsGrantType` | M2M (仅 confidential) |

---

## 8. Refresh Token 的核心机制

### Token 格式

Refresh Token 编码格式：`{offlineGrantID}.{32位随机token}`

代码：`pkg/lib/oauth/token_encoding.go:301-312`
```go
func EncodeRefreshToken(token string, grantID string) string {
    return fmt.Sprintf("%s.%s", grantID, token)
}
```

### OfflineGrantRefreshToken 结构

一个 OfflineGrant 可以包含**多个 RefreshToken**（每个 client 一个）：

```go
type OfflineGrantRefreshToken struct {
    InitialTokenHash string      // SHA256(token)，用作 ID
    ClientID         string      // 哪个 OAuth client
    CreatedAt        time.Time
    Scopes           []string    // 授权的 scope
    AuthorizationID  string
    DPoPJKT          string      // DPoP 绑定
    AccessInfo       *access.Info
    ExpireAt         *time.Time  // 短时效 token
    RotatedTokenHash *string     // 轮换后的新 hash
    RotatedAt        *time.Time
}
```

### Token 轮换（Rotation）

启用条件：`client.RefreshTokenRotationEnabled == true`
- 每次使用 refresh token 后，生成新 token
- `RotatedTokenHash` 记录最新 token hash
- `MatchCurrentHash()` 使用 constant-time comparison 防时序攻击
- 旧的 token 自动失效

### SSO 验证链

```
Mobile App uses refresh_token
  → GetOfflineGrant(id)
    → if SSOEnabled && IDPSessionID != "":
      → Get IDPSession
        → 如果 IDP Session 不存在 → ErrGrantNotFound (refresh token 不可用)
        → 如果 IDP Session 已过期 → ErrGrantNotFound (refresh token 不可用)
```

这保证了：**Web 登出 → IDP Session 删除 → 所有关联的 Mobile App refresh token 自动失效。**

---

## 9. 安全机制

| 机制 | 说明 |
|---|---|
| **PKCE (S256)** | 所有 public client (native/spa) 必须使用，防止 authorization code interception |
| **DPoP** | 将 device_secret/refresh token 绑定到设备硬件密钥 |
| **Token Hashing** | 所有 token 仅存储 SHA256 hash，原文只返回一次 |
| **Redis Mutex** | OfflineGrant 和 IDPSession 的所有修改操作使用分布式锁 |
| **Constant-time comparison** | `MatchCurrentHash()` 和 `MatchInitialHash()` 使用 `crypto/subtle.ConstantTimeCompare` |
| **SameSite Cookie** | Web session 使用 `SameSite=Lax` / `SameSite=Strict` 防止 CSRF |
| **Refresh Token Rotation** | 每次使用后自动轮换，旧 token 失效 |

---

## 10. SDK 侧配置（概念层面）

### 浏览器 SSO 配置
```javascript
// Web SDK
authgear.configure({
  sessionType: "refresh_token",
  ssoEnabled: true,  // 启用 Browser SSO
});

// Mobile SDK (旧版)
authgear.configure({
  shareSessionWithSystemBrowser: true,  // 已废弃，改为 ssoEnabled
});

// Mobile SDK (新版)
authgear.configure({
  ssoEnabled: true,  // 启用 Browser SSO (WebView 共享 System Browser Cookie)
});
```

### Device SSO 配置
```javascript
// Mobile SDK
authgear.configure({
  isDeviceSSOEnabled: true,  // 启用 Device SSO (scope=device_sso)
  // deviceSecretStore: IOSAppGroupDeviceSecretStorage / AndroidAccountManagerDeviceSecretStorage
});
```

**Recipe - 两个同一厂商的 App 实现免交互登录**：
1. 两个 App 配置 `isDeviceSSOEnabled: true`
2. 使用共享存储（iOS App Group / Android AccountManager）
3. App 1 登录后，`device_secret` + `id_token` 写入共享存储
4. App 2 调用 `checkDeviceSSOPossible()` → `authenticateDeviceSSO()`
5. App 2 无需用户交互，自动完成认证

---

## 11. 关键文件索引

| 文件 | 说明 |
|---|---|
| `docs/specs/oidc-sso-browser.md` | Browser SSO 规范 |
| `docs/specs/oidc-native-sso.md` | OIDC Native SSO 规范（Device SSO） |
| `.learning/ddd/SSO领域设计.md` | SSO 领域设计文档（DDD） |
| `.learning/ddd/SSO流程时序图.md` | SSO 流程时序图 |
| `pkg/lib/session/session.go` | Session 接口定义 (SessionBase, ResolvedSession, ListableSession) |
| `pkg/lib/session/idpsession/session.go` | IDPSession 结构体 |
| `pkg/lib/session/idpsession/provider.go` | IDPSession 创建、访问、Token 管理 |
| `pkg/lib/session/idpsession/resolver.go` | Cookie-based IDP Session 解析器 |
| `pkg/lib/session/idpsession/manager.go` | IDPSession CRUD 管理 |
| `pkg/lib/session/manager.go` | Session Manager（SSO Group 登出传播） |
| `pkg/lib/session/cookie.go` | Cookie 定义 |
| `pkg/lib/oauth/grant_offline.go` | OfflineGrant 结构体（SSOEnabled, IDPSessionID, DeviceSecretHash） |
| `pkg/lib/oauth/grant_offline_service.go` | OfflineGrant 服务（SSO 验证、过期计算、Token 轮换） |
| `pkg/lib/oauth/handler/handler_authz.go` | Authorization Endpoint（SSO 参数处理、prompt=none、login_hint） |
| `pkg/lib/oauth/handler/handler_token.go` | Token Endpoint（authorization_code, refresh_token, token-exchange, app2app） |
| `pkg/lib/oauth/handler/service_token.go` | Token Service（IssueOfflineGrant, IssueDeviceSecret） |
| `pkg/lib/oauth/protocol/authz.go` | Authorization Request 参数解析（x_sso_enabled, x_suppress_idp_session_cookie） |
| `pkg/lib/oauth/scope.go` | Scope 定义和验证（device_sso） |
| `pkg/lib/oauth/grant_type.go` | Grant Type 常量定义 |
| `pkg/lib/oauth/token.go` | GenerateToken(), HashToken() |
| `pkg/lib/oauth/token_encoding.go` | Refresh Token 编码/解码 |
| `pkg/lib/oauth/sid.go` | Session ID 编码（sid claim） |
| `pkg/lib/oauth/oidc/id_token.go` | ID Token 签发和验证（ds_hash claim） |
| `pkg/lib/oauth/oidc/ui.go` | UI Info Resolver（prompt, id_token_hint） |
| `pkg/lib/oauth/resolver.go` | OAuth Access Token 解析器（Bearer header, app session cookie） |
| `pkg/lib/oauth/app_session_token.go` | App Session Token 服务 |
| `pkg/lib/app2app/provider.go` | App2App Token 解析和 JWT 验证 |
| `pkg/lib/app2app/request.go` | App2App JWT 请求格式 |
| `pkg/lib/config/oauth.go` | OAuth Client 配置（ApplicationType, SSO 配置） |
| `pkg/lib/config/feature_oauth.go` | OAuth Feature Flag 配置 |
| `pkg/lib/config/session.go` | Session 生命周期和超时配置 |
| `pkg/lib/authenticationflow/declarative/intent_login_flow.go` | Auth Flow 登录意图（SuppressIDPSessionCookie） |
| `pkg/lib/interaction/intents/authenticate.go` | 旧版交互流认证意图（SuppressIDPSessionCookie） |
| `pkg/lib/sessionlisting/listing.go` | Session Listing（SSO Group 合并显示） |

---

## 12. 总结

**核心答案**：Mobile App 使用 **OAuth 2.0 Refresh Token（OfflineGrant）** 替代 Browser 的 Session Cookie。Mobile App 不是"无 session"，而是使用了一种不同的 session 类型——`TypeOfflineGrant`。这种 session 是 token-based（而非 cookie-based），通过 OAuth 2.0 协议管理生命周期。

**Mobile App SSO 的本质**：
1. **Browser SSO（x_sso_enabled=true）**：Mobile App 的 OfflineGrant 关联到 Web 浏览器的 IDPSession，共享 SSO Group。Web 登出时，Mobile App 的 refresh token 也失效。
2. **Device SSO（scope=device_sso）**：基于 OIDC Native SSO 规范，使用 device_secret 作为设备级共享凭证。同一设备的多个 App 通过 Token Exchange 实现免交互登录，登录状态通过 iOS App Group / Android AccountManager 共享。
3. **App2App SSO（x_app2app_enabled）**：通过设备密钥对 + JWT Challenge 机制，在 App 之间转移授权码。适用于不能共享存储但需要 SSO 的场景。

**关键设计原则**：
- SSO Group 以 IdP Session 为核心。IdP Session 失效 → 整组失效。
- Token 安全：哈希存储、PKCE 强制、DPoP 绑定、Refresh Token 轮换。
- 设备隔离：不同设备的 session 天然隔离。Device SSO 以设备为单位共享。
- 灵活配置：每个 OAuth Client 可以独立配置 SSO 行为。
