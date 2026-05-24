# Authgear Device SSO 完整实现参考

## 概述

Device SSO（又名 Native SSO）基于 [OIDC Native SSO 规范](https://openid.net/specs/openid-connect-native-sso-1_0.html)，允许**同一发行商的多个原生 App 在同一设备上免交互共享登录状态**。

核心思想：通过 `device_secret`（设备级密钥）和 `id_token`（含 `ds_hash` claim）实现跨 App 认证转移。App 1 首次登录后，将 `device_secret` + `id_token` 存入设备共享存储（iOS App Group / Android AccountManager）；App 2 从共享存储读取后，通过 Token Exchange 换取自己的 token，全程无需用户交互。

---

## 一、关键概念

### 1.1 核心凭证

| 名称 | 生成方式 | 存储位置 | 用途 |
|------|---------|---------|------|
| `device_secret` | 32位随机字符串，SHA256 哈希后存于 `OfflineGrant.DeviceSecretHash` | 客户端：iOS Keychain / Android Keystore（共享存储） | 设备级身份凭证 |
| `id_token` (含 `ds_hash`) | JWT，其中 `ds_hash` = SHA256(device_secret) | 客户端：与 device_secret 一起存入共享存储 | 跨 App 携带 device_secret 的承诺 |
| `OfflineGrant` | 持久化授权会话 | 服务端：PostgreSQL | 用户在该设备的认证会话 |

### 1.2 代码中的核心常量与类型

```go
// pkg/lib/oauth/scope.go:19-20
const DeviceSSOScope = "device_sso"
const PreAuthenticatedURLScope = "https://authgear.com/scopes/pre-authenticated-url"

// pkg/lib/oauth/grant_type.go
const TokenExchangeGrantType = "urn:ietf:params:oauth:grant-type:token-exchange"
const App2AppRequestGrantType = "urn:authgear:params:oauth:grant-type:app2app-request"

// pkg/lib/oauth/handler/handler_token.go:59-63 (token types 用于 Token Exchange)
PreAuthenticatedURLTokenTokenType = "urn:authgear:params:oauth:token-type:pre-authenticated-url-token"
IDTokenTokenType                 = "urn:ietf:params:oauth:token-type:id_token"
DeviceSecretTokenType            = "urn:x-oath:params:oauth:token-type:device-secret"
```

### 1.3 Native App 如何创建 IDP Session（移动端 OAuth 的基础）

理解 Native App 如何创建 IDP Session 是理解 Device SSO 的前提。**Native App 不会绕过浏览器，而是使用平台提供的安全认证浏览器：**

| 平台 | 组件 | 与系统浏览器的关系 | SDK 配置 |
|------|------|-------------------|---------|
| **iOS** | [`ASWebAuthenticationSession`](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession) | 与 **Safari** 共享 Cookie Jar | 自动，iOS 12+ 默认行为 |
| **Android** | [Custom Tabs](https://developer.chrome.com/docs/android/custom-tabs) | 与 **Chrome** 共享 Cookie Jar | 自动，推荐方式 |

两种组件的关键特性：
- **共享 Cookie Jar**：认证完成后，IDP Session Cookie 不仅对当前 App 可见，对同一设备上的系统浏览器（Safari/Chrome）和其他使用相同组件发起 OAuth 的 App 也可见
- **App 无法访问 Cookie**：宿主 App 无法读取认证浏览器中的 Cookie 或网页内容，保证了安全性
- **独立的 Web 上下文**：每次打开都是全新的 Web 会话（不会遗留前一次的表单状态）

#### IDP Session 的创建流程

```
App 1 调用 SDK.authenticate()
  │
  │  SDK 打开 ASWebAuthenticationSession / Custom Tabs
  │  发起 GET /oauth2/authorize?client_id=app1&scope=...&x_sso_enabled=true
  │
  ▼
Authgear 授权端点 (/oauth2/authorize)
  │
  │  用户在 AuthUI 页面中输入凭据并完成认证
  │
  │  认证成功后, 服务端创建 IDP Session:
  │  - 在 Redis 中存储 session 数据 (idpsession.Session)
  │  - 生成 session cookie (app_id_session=...)
  │
  │  x_sso_enabled 决定 cookie 是否被抑制:
  │
  │  ┌─ x_sso_enabled=true ─────────────────────────────────────┐
  │  │  SuppressIDPSessionCookie() = false                       │
  │  │  → SkipCreate = false (不跳过创建)                         │
  │  │  → Set-Cookie: app_id_session=<token> 被发送到客户端         │
  │  │  → Cookie 存储在 ASWebAuthenticationSession/Custom Tabs    │
  │  │    的共享 Cookie Jar 中                                    │
  │  │  → Safari/Chrome + 其他 App 也能看到这个 Cookie             │
  │  └──────────────────────────────────────────────────────────┘
  │
  │  ┌─ x_sso_enabled=false ────────────────────────────────────┐
  │  │  SuppressIDPSessionCookie() = true                        │
  │  │  → SkipCreate = true (跳过 cookie 创建)                    │
  │  │  → 服务端 IDP Session 仍然创建（AuthnSession 存在）         │
  │  │  → 但 Set-Cookie 响应头被抑制，不发给客户端                  │
  │  │  → Cookie 不进入共享 Cookie Jar                            │
  │  └──────────────────────────────────────────────────────────┘
  │
  ▼
302 redirect → App 1 收到 authorization_code
  │
  │  POST /oauth2/token (grant_type=authorization_code)
  │
  ▼
doIssueTokensForAuthorizationCode()  handler_token.go:1680
  │
  │  info.AuthenticatedBySessionType = "idp"   ← 认证来源于 IDP Session
  │  info.AuthenticatedBySessionID   = <idp_session_id>
  │
  │  // 构建 IssueOfflineGrantOptions
  │  opts := IssueOfflineGrantOptions{
  │      IDPSessionID:      offlineGrantIDPSessionID,  // ← 始终被设置
  │      SSOEnabled:        code.AuthorizationRequest.SSOEnabled(),  // ← x_sso_enabled 的值
  │      IssueDeviceSecret: issueDeviceToken,           // ← scope 包含 device_sso
  │  }
  │
  ▼
IssueOfflineGrant()  service_token.go:122
  │
  │  offlineGrant.IDPSessionID = opts.IDPSessionID   // 总是记录 IDP Session ID
  │  offlineGrant.SSOEnabled   = opts.SSOEnabled     // 决定是否加入 SSO Group
  │
  │  // 关键区别:
  │  //   x_sso_enabled=true  → SSOEnabled=true  → OfflineGrant 加入 SSO Group
  │  //   x_sso_enabled=false → SSOEnabled=false → OfflineGrant 独立存在
  │
  ▼
OfflineGrant 创建完成, 包含:
  - DeviceSecretHash = SHA256(device_secret) ← device_sso scope 触发
  - IDPSessionID                               ← 总是记录
  - SSOEnabled                                 ← 由 x_sso_enabled 决定
```

#### 代码关键路径

**`SuppressIDPSessionCookie()` 判断逻辑** (`pkg/lib/oauth/protocol/authz.go:79-97`):

```go
func (r AuthorizationRequest) SuppressIDPSessionCookie() bool {
    if r["x_sso_enabled"] != "" {
        return r["x_sso_enabled"] != "true"   // "true" → 不抑制, 其他值 → 抑制
    }
    if r["x_suppress_idp_session_cookie"] != "" {
        return r["x_suppress_idp_session_cookie"] == "true"  // 向后兼容
    }
    return false  // 默认不抑制
}
```

**`SkipCreate` 在认证流中的传递** (`pkg/lib/authenticationflow/declarative/intent_login_flow.go:80`):

```go
// 登录完成后, 调用 CreateSession 时:
deps.IDPSessions.Create(ctx, &idpsession.CreateOptions{
    SkipCreate: authflow.GetSuppressIDPSessionCookie(ctx),
    // SkipCreate=true → 不设 cookie (服务端 session 仍创建)
    // SkipCreate=false → 设 Set-Cookie
})
```

**`doIssueTokensForAuthorizationCode` 中的 IDPSessionID 传递** (`pkg/lib/oauth/handler/handler_token.go:1791-1808`):

```go
// 无论 x_sso_enabled 为何值, 只要认证来源是 IDP Session,
// offlineGrantIDPSessionID 都会被设置
var offlineGrantIDPSessionID string
switch session.Type(info.AuthenticatedBySessionType) {
case session.TypeIdentityProvider:
    offlineGrantIDPSessionID = info.AuthenticatedBySessionID
default:
    // 非 IDP Session 认证来源 (如 refresh_token 重用、biometric 等)
    // 此时没有 IDP Session ID
}

opts := IssueOfflineGrantOptions{
    IDPSessionID:  offlineGrantIDPSessionID,  // ← 始终传递 (如果是 IDP 认证来源)
    SSOEnabled:    code.AuthorizationRequest.SSOEnabled(),  // ← 决定是否加入 SSO Group
}
```

#### 总结：两个独立维度的控制

| 字段 | 控制什么 | 由谁决定 | 何时生效 |
|------|---------|---------|---------|
| `IDPSessionID` | 记录"这个 OfflineGrant 是从哪个 IDP Session 创建的" | 认证来源类型 (总是 IdentityProvider) | OfflineGrant 创建时记录 |
| `SSOEnabled` | 控制"这个 OfflineGrant 是否加入 SSO Group" | `x_sso_enabled` 参数 | `IsSameSSOGroup()` 检查 + `GetOfflineGrant()` 有效性验证 |

**IDP Session 总是会存在于服务端**（它在认证流程中被创建），区别只在于：
- `x_sso_enabled=true`：Cookie 被返回给客户端 → 进入共享 Cookie Jar → 其他 App/浏览器可见 → OfflineGrant 加入 SSO Group
- `x_sso_enabled=false`：Cookie 被抑制 → 不进入共享 Cookie Jar → OfflineGrant 独立

**对 Device SSO 的影响**：Device SSO 依赖 `device_secret` + `id_token` 在共享存储中传递，不依赖 Cookie Jar。所以即使 `x_sso_enabled=false`（没有 Cookie 传播），两个 App 之间仍然可以通过共享存储中的 `device_secret` + `id_token` 完成 Device SSO。

### 1.4 OfflineGrant 中的 Device SSO 字段

```go
// pkg/lib/oauth/grant_offline.go:61-62
type OfflineGrant struct {
    // ...
    DeviceSecretHash    string `json:"device_secret_hash"`      // SHA256(device_secret) 的 hex
    DeviceSecretDPoPJKT string `json:"device_secret_dpop_jkt"`  // DPoP 公钥指纹，将 device_secret 绑定到设备硬件密钥
    // ...
}
```

### 1.5 ID Token 中的 ds_hash Claim

```go
// pkg/lib/oauth/oidc/id_token.go:117-119
// ds_hash
if dshash := opts.DeviceSecretHash; dshash != "" {
    _ = claims.Set(string(model.ClaimDeviceSecretHash), dshash)
}
```

`ds_hash` 的值等于 `SHA256(device_secret)`，即 `OfflineGrant.DeviceSecretHash`。

#### id_token 的有效期与验证规则

**id_token 的名义有效期是 5 分钟**：

```go
// pkg/lib/oauth/oidc/id_token.go:57-59
// IDTokenValidDuration is the valid period of ID token.
// It can be short, since id_token_hint should accept expired ID tokens.
const IDTokenValidDuration = duration.Short  // = 5 分钟

// pkg/lib/oauth/oidc/id_token.go:106
_ = claims.Set(jwt.ExpirationKey, now.Add(IDTokenValidDuration).Unix())
```

**但 `VerifyIDToken` 不检查 `exp`**：

```go
// pkg/lib/oauth/oidc/id_token.go:211-238
func (ti *IDTokenIssuer) VerifyIDToken(idToken string) (token jwt.Token, err error) {
    // 1. 验证签名
    _, err = jws.Verify([]byte(idToken), jws.WithKeySet(jwkSet))
    if err != nil {
        return
    }
    // 2. 解析 JWT (不验证 exp, iss, aud)
    _, token, err = jwtutil.SplitWithoutVerify([]byte(idToken))
    // ...
    // 注释说明:
    // We used to validate `aud`.
    // However, some features like Native SSO will share a id token with multiple clients.
    // So we removed the checking of `aud`.
    //
    // Normally we should also validate `iss`.
    // But `iss` can change if public_origin was changed.
    // We should still accept ID token referencing an old public_origin.
    return
}
```

**`VerifyIDToken` 只做两件事：验证签名 + 解析 JWT。不校验 `exp`、`iss`、`aud`。**

**设计规范明确允许过期的 id_token**：

```markdown
// docs/specs/oidc-native-sso.md:84
Validate `subject_token` is a valid ID token issued to the first app.
An expired ID token is still valid. (4.3 Point 2)
```

**真正限制 id_token 使用的不是 `exp`，而是 `ds_hash`**。id_token 的实际生命周期如下：

```
id_token 可用窗口 = 从上一次 Token Exchange 到下一次 Token Exchange (device_secret 轮换)
                 （而不是从签发到 exp）

每次 Token Exchange 成功后:
  1. rotateDeviceSecret() → 生成新的 device_secret, 更新 OfflineGrant.DeviceSecretHash
  2. 签发新 id_token, ds_hash = SHA256(新 device_secret)
  3. SDK 将新 id_token + 新 device_secret 存入共享存储，覆盖旧值
  4. ★ 旧 id_token 的 ds_hash 指向旧 device_secret → 下次 Token Exchange 时
     verifyIDTokenDeviceSecretHash 会失败: ds_hash != SHA256(当前 device_secret)
```

**生命周期对比表**：

| 场景 | id_token 的 exp | device_secret 是否匹配 | 能否用于 Token Exchange |
|------|----------------|----------------------|----------------------|
| 刚签发（< 5 分钟） | 未过期 | 匹配 | **能** |
| 5 分钟 ~ 长期 | 已过期 | 匹配 | **仍能** — VerifyIDToken 不校验 exp |
| device_secret 轮换后 | 任意 | ds_hash 不匹配 | **不能** — SHA256(旧 ds_hash) != 当前 DeviceSecretHash |
| 签名被篡改 | — | — | **不能** — 签名验证失败 |
| id_token 来自其他用户/设备 | 任意 | ds_hash 不匹配 + OfflineGrant.DeviceSecretHash 不匹配 | **不能** — 双重 hash 校验失败 |

**为什么设计成这样？**

1. **id_token 的 `exp` 设置短是标准 OIDC 实践**（id_token 通常用于单次认证，不需长期有效）
2. **Device SSO 场景下 id_token 是"凭证载体"而非"时效凭证"** — 它携带的是 `ds_hash`（对 device_secret 的承诺），过期与否不影响这个承诺的验证
3. **真正的时效控制来自 device_secret 轮换** — 每次 App 2 完成 Token Exchange，device_secret 被轮换，旧的 id_token 自动失效。这保证了"前一个 App 登录→下一个 App 登录"的时间窗口由 device_secret 的使用频率决定，而非 id_token 的 5 分钟 exp
4. **安全等价**：如果攻击者窃取了 id_token，那么他也需要同时窃取 device_secret（且 device_secret 没有被轮换），5 分钟有效期不会显著提升安全性

### 1.6 Token Hashing

```go
// pkg/lib/oauth/token.go:17-19
func HashToken(token string) string {
    return crypto.SHA256String(token)  // hex encoded SHA256
}

// pkg/lib/oauth/token.go:12-14
func GenerateToken() string {
    token := rand.StringWithAlphabet(32, tokenAlphabet, rand.SecureRand)
    return token
}
```

### 1.7 Scope 验证

```go
// pkg/lib/oauth/scope.go:246-255
if s == DeviceSSOScope {
    hasDeviceSSO = true
}
if s == DeviceSSOScope && !client.PreAuthenticatedURLEnabled {
    return protocol.NewError("invalid_scope", "device_sso is not allowed for this client")
}
if s == PreAuthenticatedURLScope && !hasDeviceSSO {
    return protocol.NewError("invalid_scope", "device_sso must be requested when using pre-authenticated url")
}
```

**限制**：
- `scope=device_sso` 要求 OAuth client 配置 `x_pre_authenticated_url_enabled: true`
- 如果请求 `scope=https://authgear.com/scopes/pre-authenticated-url`，必须同时请求 `scope=device_sso`

### 1.8 issueOfflineGrant 中的 DeviceSecret 生成逻辑

```go
// pkg/lib/oauth/handler/service_token.go:167-173
offlineGrant = &oauth.OfflineGrant{
    // ... 其他字段 ...
    SSOEnabled:   opts.SSOEnabled,
    DeviceInfo:   opts.DeviceInfo,
    // ...
}

if opts.IssueDeviceSecret {
    deviceSecretHash := s.IssueDeviceSecret(ctx, resp)  // <-- 生成 device_secret 并写入 response
    offlineGrant.DeviceSecretHash = deviceSecretHash
    offlineGrant.DeviceSecretDPoPJKT = opts.DPoPJKT
}
```

```go
// pkg/lib/oauth/handler/service_token.go:415-420
func (s *TokenService) IssueDeviceSecret(ctx context.Context, resp protocol.TokenResponse) (deviceSecretHash string) {
    deviceSecret := s.GenerateToken()
    deviceSecretHash = oauth.HashToken(deviceSecret)
    resp.DeviceSecret(deviceSecret)  // 将明文 device_secret 写入 HTTP 响应
    return deviceSecretHash           // 返回 SHA256(device_secret)
}
```

```go
// pkg/lib/oauth/protocol/token.go:42
func (r TokenResponse) DeviceSecret(v string) { r["device_secret"] = v }
```

### 1.9 shouldIssueDeviceSecret

```go
// pkg/lib/oauth/handler/handler_token.go:2067-2076
func (h *TokenHandler) shouldIssueDeviceSecret(scopes []string) bool {
    issueDeviceToken := false
    for _, scope := range scopes {
        switch scope {
        case oauth.DeviceSSOScope:
            issueDeviceToken = true
        }
    }
    return issueDeviceToken
}
```

只要 authorization request 的 scope 包含 `device_sso`，后续颁发 token 时就会生成 device_secret。

---

## 二、完整流程详解

### 2.1 前置条件

- OAuth Client 配置：
  ```yaml
  x_pre_authenticated_url_enabled: true
  ```
- 两个 App 使用不同的 `client_id`（如 `app1` 和 `app2`），但属于同一发行商
- 客户端已配置共享存储（iOS App Group / Android AccountManager）
- SDK 已设置 `isDeviceSSOEnabled: true`

### 2.2 流程图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          App 1 (首次登录)                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ① GET /oauth2/authorize                                                 │
│     ?client_id=app1                                                      │
│     &redirect_uri=com.example.app1%3A%2F%2Fcallback                       │
│     &scope=openid+offline_access+device_sso                              │
│     &response_type=code                                                  │
│     &code_challenge=<PKCE_S256_challenge>                                 │
│     &code_challenge_method=S256                                          │
│     &x_sso_enabled=true                                                  │
│                                                                          │
│  ② 用户在 WebView 中完成认证（用户名密码/生物识别等）                       │
│                                                                          │
│  ③ 302 redirect_uri?code=<authorization_code>                            │
│                                                                          │
│  ④ POST /oauth2/token                                                    │
│     grant_type=authorization_code                                        │
│     client_id=app1                                                       │
│     code=<authorization_code>                                            │
│     redirect_uri=com.example.app1%3A%2F%2Fcallback                       │
│     code_verifier=<PKCE_S256_verifier>                                   │
│                                                                          │
│  ⑤ Response:                                                             │
│     {                                                                    │
│       "access_token":  "<access_token>",                                 │
│       "refresh_token": "<offlineGrantID.32位token>",                      │
│       "token_type":     "Bearer",                                        │
│       "expires_in":     3600,                                            │
│       "id_token":       "<JWT with ds_hash claim>",                      │
│       "device_secret":  "<32位随机字符串>"                                 │
│     }                                                                    │
│                                                                          │
│  ⑥ SDK 将 device_secret + id_token 存入共享存储                           │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                          App 2 (免交互登录)                                │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ① 从共享存储读取 device_secret + id_token（由 App 1 写入）                │
│                                                                          │
│  ② POST /oauth2/token                                                    │
│     grant_type=urn:ietf:params:oauth:grant-type:token-exchange            │
│     client_id=app2                                                       │
│     scope=device_sso                                                     │
│     subject_token=<id_token>                                             │
│     subject_token_type=urn:ietf:params:oauth:token-type:id_token          │
│     actor_token=<device_secret>                                          │
│     actor_token_type=urn:x-oath:params:oauth:token-type:device-secret     │
│     audience=<issuer>                                                    │
│                                                                          │
│  ③ Response:                                                             │
│     {                                                                    │
│       "access_token":       "<pre_authenticated_url_token>",             │
│       "token_type":         "Bearer",                                    │
│       "issued_token_type":  "urn:authgear:params:oauth:token-type:       │
│                              pre-authenticated-url-token",                │
│       "expires_in":         300,                                         │
│       "id_token":           "<新JWT，ds_hash 指向新 device_secret>",      │
│       "device_secret":      "<新32位随机字符串>"                           │
│     }                                                                    │
│                                                                          │
│  ④ SDK 用 pre_authenticated_url_token 换取正常的 access_token              │
│     + refresh_token（可选，需 offline_access scope）                       │
│                                                                          │
│  ⑤ SDK 更新共享存储：新的 device_secret + id_token                         │
│                                                                          │
│  ⑥ 免交互完成！                                                           │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 三、完整 HTTP 请求与响应

### 3.1 App 1 初次登录

#### Step 1: Authorization Request

App 1 通过 WebView 发起 OAuth authorize 请求。用户在 WebView 中完成认证（例如输入用户名密码）。

```http
GET /oauth2/authorize?client_id=app1&redirect_uri=com.example.app1%3A%2F%2Fcallback&scope=openid+offline_access+device_sso&response_type=code&code_challenge=eX2GfD4k5j7hK9mN1pQ3rS5tU7vW9xY1zA3bC5dE7fG9hI1kL3mN5oP7qR9sT1uV3wX5yZ7&code_challenge_method=S256&x_sso_enabled=true HTTP/1.1
Host: accounts.example.com
```

**关键参数说明**：

| 参数 | 值 | 说明 |
|------|-----|------|
| `client_id` | `app1` | App 1 的 OAuth client ID |
| `scope` | `openid offline_access device_sso` | `device_sso` 触发 Device SSO 机制 |
| `response_type` | `code` | 标准授权码流程 |
| `code_challenge` | `<PKCE_S256_challenge>` | PKCE 必须 (S256)，因为 native app 是 public client |
| `code_challenge_method` | `S256` | PKCE 挑战方法 |
| `x_sso_enabled` | `true` | 启用 Browser SSO，将 OfflineGrant 关联到 IDP Session |

#### Step 2: 用户认证 (WebView 内)

用户在 AuthUI 页面中输入凭据完成认证。AuthUI 由 Authgear 服务端渲染，用户交互在 WebView 中进行。

认证完成后，服务端将用户重定向到 redirect_uri：

```http
HTTP/1.1 302 Found
Location: com.example.app1://callback?code=authcode_abc123xyz
```

#### Step 3: Token Request (Authorization Code 兑换)

App 1 拿到授权码后，通过 HTTPS 直接请求 Token Endpoint（不经过 WebView）。

```http
POST /oauth2/token HTTP/1.1
Host: accounts.example.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&client_id=app1
&code=authcode_abc123xyz
&redirect_uri=com.example.app1%3A%2F%2Fcallback
&code_verifier=d36f6c8f1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6g7h8i9j0k
```

**服务端处理** (`pkg/lib/oauth/handler/handler_token.go:1680-1855`):

1. `doIssueTokensForAuthorizationCode()` 被调用
2. `shouldIssueDeviceSecret(code.AuthorizationRequest.Scope())` 返回 `true`（因为 scope 包含 `device_sso`）→ `issueDeviceToken = true`
3. TokenExchange 未发生 → 走标准 `issueOfflineGrant` 路径
4. 新 OfflineGrant 被创建：
   ```go
   // handler_token.go:1799-1808
   opts := IssueOfflineGrantOptions{
       Scopes:             scopes,
       AuthorizationID:    authz.ID,
       AuthenticationInfo: info,
       IDPSessionID:       offlineGrantIDPSessionID,
       SSOEnabled:         code.AuthorizationRequest.SSOEnabled(), // true
       IssueDeviceSecret:  issueDeviceToken,                       // true
       DPoPJKT:            dpopJKT,
   }
   ```
5. 在 `IssueOfflineGrant()` (`service_token.go:169-173`) 中：
   ```go
   if opts.IssueDeviceSecret {
       deviceSecretHash := s.IssueDeviceSecret(ctx, resp)  // 生成 device_secret
       offlineGrant.DeviceSecretHash = deviceSecretHash
       offlineGrant.DeviceSecretDPoPJKT = opts.DPoPJKT
   }
   ```
   生成的 `device_secret` 通过 `resp.DeviceSecret(deviceSecret)` 写入 token response
6. ID Token 被签发，`ds_hash` 等于 `offlineGrant.DeviceSecretHash` → SHA256(device_secret)

#### Step 4: Token Response

```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "access_token":  "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...<truncated>",
  "refresh_token": "3a2b1c0d-9e8f-7a6b-5c4d-3e2f1a0b9c8d.XyZ9aB8cD7eF6gH5iJ4kL3mN2oP1qR0sT",
  "token_type":    "Bearer",
  "expires_in":    3600,
  "scope":         "openid offline_access device_sso",
  "id_token":      "eyJhbGciOiJSUzI1NiIsImtpZCI6IjA4QjE1RTc2M0I1QzNCMTFEMEE1QjRGQjdCREZCNjhFQzY4M0JBNkUiLCJ0eXAiOiJKV1QifQ.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmV4YW1wbGUuY29tIiwic3ViIjoiMTIzNDU2Nzg5MCIsImF1ZCI6ImFwcDEiLCJleHAiOjE3MjUwNzU4MDAsImlhdCI6MTcyNDk4OTQwMCwiYXV0aF90aW1lIjoxNzI0OTg5NDAwLCJzaWQiOiJvZmZsaW5lX2dyYW50XzEyMzQ1Njc4OTAiLCJhbXIiOlsicHdkIl0sImRzX2hhc2giOiJhYmNkZWYxMjM0NTY3ODkwYWJjZGVmMTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTBhYmNkZWYifQ.signature",
  "device_secret": "aZ9bY8cX7dW6eV5fU4gT3hS2iR1jQ0kP9oL8mN7nM6"
}
```

**关键字段解释**：

| 字段 | 值/格式 | 说明 |
|------|---------|------|
| `refresh_token` | `{offlineGrantID}.{32位token}` | `offlineGrantID` 是 UUID，通过 `.` 与 32位随机token拼接 |
| `id_token` | JWT | 包含 `ds_hash` claim，值为 SHA256(device_secret) 的 hex |
| `device_secret` | 32位随机字符串 | 设备级密钥，**仅返回一次**，服务端只存 SHA256 哈希 |
| `scope` | `openid offline_access device_sso` | 返回实际授权的 scope |

**id_token payload 示例**（解码后）:

```json
{
  "iss": "https://accounts.example.com",
  "sub": "user_1234567890",
  "aud": "app1",
  "exp": 1725075800,
  "iat": 1724989400,
  "auth_time": 1724989400,
  "sid": "offline_grant_1234567890",
  "amr": ["pwd"],
  "ds_hash": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
}
```

`ds_hash` = SHA256(device_secret) 的 hex 编码。

#### Step 5: SDK 存储

App 1 的 SDK 将 `device_secret` 和 `id_token` 写入设备共享存储：

- **iOS**: 通过 App Group 共享的 Keychain
- **Android**: 通过 AccountManager（需在 AndroidManifest.xml 中配置 `<account-authenticator>`）

---

### 3.2 App 2 免交互登录

#### Step 1: SDK 从共享存储读取

App 2 启动时，SDK 调用 `checkDeviceSSOPossible()`：
- 从共享存储中查找是否存在 `device_secret` 和 `id_token`
- 如果存在 → 可以进行 Device SSO
- 如果不存在 → 需要走正常的授权码流程

以下假设 App 1 已登录，共享存储中有 `device_secret` 和 `id_token`。

#### Step 2: Token Exchange Request

App 2 通过 HTTPS 直接请求 Token Endpoint（无需 WebView，无需用户交互）。

```http
POST /oauth2/token HTTP/1.1
Host: accounts.example.com
Content-Type: application/x-www-form-urlencoded

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange
&client_id=app2
&scope=device_sso
&subject_token=eyJhbGciOiJSUzI1NiIsImtpZCI6...<App1的id_token>
&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aid_token
&actor_token=aZ9bY8cX7dW6eV5fU4gT3hS2iR1jQ0kP9oL8mN7nM6
&actor_token_type=urn%3Ax-oath%3Aparams%3Aoauth%3Atoken-type%3Adevice-secret
&audience=https%3A%2F%2Faccounts.example.com
```

**参数说明**：

| 参数 | 值 | 说明 |
|------|-----|------|
| `grant_type` | `urn:ietf:params:oauth:grant-type:token-exchange` | RFC 8693 Token Exchange |
| `client_id` | `app2` | App 2 的 client ID |
| `scope` | `device_sso` | 必需，且必须是 App 1 授权 scope 的子集 |
| `subject_token` | `<App1的id_token>` | App 1 签发、包含 `ds_hash` 的 ID Token |
| `subject_token_type` | `urn:ietf:params:oauth:token-type:id_token` | subject_token 的类型 |
| `actor_token` | `<32位device_secret>` | App 1 签发 token response 中的 device_secret 明文 |
| `actor_token_type` | `urn:x-oath:params:oauth:token-type:device-secret` | actor_token 的类型 |
| `audience` | `https://accounts.example.com` | Token 的预期接收者，必须等于 issuer |

#### Step 3: 服务端验证

服务端 `handlePreAuthenticatedURLToken()` (`pkg/lib/oauth/handler/handler_token.go:861-985`) 依次验证：

```
验证步骤                         代码位置                            说明
────────────────────────────────────────────────────────────────────────────────────
1. actor_token_type              handler_token.go:867               必须 = "urn:x-oath:params:oauth:token-type:device-secret"
   必须为 device-secret
────────────────────────────────────────────────────────────────────────────────────
2. subject_token_type            handler_token.go:869               必须 = "urn:ietf:params:oauth:token-type:id_token"
   必须为 id_token
────────────────────────────────────────────────────────────────────────────────────
3. actor_token 不为空            handler_token.go:873               device_secret 是必需的
────────────────────────────────────────────────────────────────────────────────────
4. subject_token 不为空          handler_token.go:876               id_token 是必需的
────────────────────────────────────────────────────────────────────────────────────
5. subject_token 是有效的               handler_token.go:880               调用 h.IDTokenIssuer.VerifyIDToken() 验证签名
   ID Token (签名验证)                                               和标准 claims (iss, exp, aud 等)
────────────────────────────────────────────────────────────────────────────────────
6. audience == issuer            handler_token.go:884               audience 必须等于 ID Token 的 issuer
────────────────────────────────────────────────────────────────────────────────────
7. sid 指向有效 session           handler_token.go:887-893          从 id_token 的 sid claim 解码，
                                                                   定位到 OfflineGrant 或 IDPSession
                                                                   如果 session 不是 OfflineGrant → 报错
────────────────────────────────────────────────────────────────────────────────────
8. session 拥有 device_sso 权限   handler_token.go:903              检查 offlineGrant.HasAllScopes(PreAuthenticatedURLScope)
                                                                   (device_sso scope 也映射到 pre-authenticated-url 能力)
────────────────────────────────────────────────────────────────────────────────────
9. 检查用户速率限制               handler_token.go:906-908         防止滥用
────────────────────────────────────────────────────────────────────────────────────
10. verifyIDTokenDeviceSecretHash handler_token.go:914              三个恒定时间比较 (handler_token.go:827-859):
    (DS Hash 验证)                                                  a) id_token.ds_hash == SHA256(actor_token)
                                                                   b) offlineGrant.DeviceSecretHash == SHA256(actor_token)
                                                                   c) DPoP JKT 匹配 (如果适用)
────────────────────────────────────────────────────────────────────────────────────
11. scope 验证                   handler_token.go:919-929           请求的 scope 必须是原始 scope 的子集
                                                                   scope 必须对 client 有效
```

**核心验证方法 `verifyIDTokenDeviceSecretHash`** (`handler_token.go:827-859`):

```go
func (h *TokenHandler) verifyIDTokenDeviceSecretHash(ctx context.Context,
    client *config.OAuthClientConfig, offlineGrant *oauth.OfflineGrant,
    idToken jwt.Token, deviceSecret string) error {
    // 所有检查都执行，确保恒定时间比较（防止时序攻击）
    var err error = nil
    deviceSecretHash := oauth.HashToken(deviceSecret)  // SHA256(actor_token)

    // 检查 1: id_token 中是否有 ds_hash claim
    dsHashInterface, ok := idToken.Get(string(model.ClaimDeviceSecretHash))
    if !ok {
        err = protocol.NewError("invalid_grant", "expected ds_hash to be present in id token (subject_token)")
    }
    dsHash, ok := dsHashInterface.(string)
    if !ok {
        err = protocol.NewError("invalid_grant", "expected ds_hash to be a string")
    }

    // 检查 2: ds_hash == SHA256(device_secret)  -- 恒定时间
    if subtle.ConstantTimeCompare([]byte(dsHash), []byte(deviceSecretHash)) != 1 {
        err = protocol.NewError("invalid_grant",
            "the hash of device_secret (actor_token) does not match ds_hash in id token (subject_token)")
    }

    // 检查 3: offlineGrant 中存储的 DeviceSecretHash == SHA256(device_secret) -- 恒定时间
    if subtle.ConstantTimeCompare([]byte(offlineGrant.DeviceSecretHash), []byte(deviceSecretHash)) != 1 {
        err = protocol.NewError("invalid_grant",
            "the device_secret (actor_token) does not bind to the session")
    }

    // 检查 4: DPoP 绑定验证
    if dpopErr := offlineGrant.MatchDeviceSecretDPoPJKT(ctx, client, ...); dpopErr != nil {
        return dpopErr
    }

    return err
}
```

**三个恒定时间比较的含义**：

| 比较 | 含义 | 防止的攻击 |
|------|------|-----------|
| `ds_hash == SHA256(device_secret)` | id_token 确实是在签发时就知道 device_secret 的 | 伪造的 id_token（不知道 device_secret 的人无法生成正确的 ds_hash） |
| `OfflineGrant.DeviceSecretHash == SHA256(device_secret)` | device_secret 确实绑定到此用户的 OfflineGrant | 使用其他设备/用户的 device_secret |
| DPoP JKT 匹配 | device_secret 绑定到硬件密钥 | 从其他设备窃取 device_secret 后重放 |

#### Step 4: Token Exchange Response

验证通过后，服务端：

1. 颁发 `pre-authenticated-url-token`（作为 access_token 返回）
2. 轮换 device_secret（生成新的，返回新的）
3. 颁发新的 id_token（ds_hash 指向新 device_secret）

```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "access_token":       "E7zQ9wR8tY6uI5oP4aS3dF2gH1jK0L9mN8bV7cX6Z",
  "token_type":         "Bearer",
  "issued_token_type":  "urn:authgear:params:oauth:token-type:pre-authenticated-url-token",
  "expires_in":         300,
  "scope":              "device_sso",
  "id_token":           "eyJhbGciOiJSUzI1NiIsImtpZCI6IjA4QjFF..."<新 JWT，ds_hash 指向新 device_secret>,
  "device_secret":      "B8cX7dW6eV5fU4gT3hS2iR1jQ0kP9oL8mN7nM6aZ"
}
```

**Response 字段说明**：

| 字段 | 值/格式 | 说明 |
|------|---------|------|
| `access_token` | 随机字符串 | **pre-authenticated-url-token**，用于后续在 `pre_authenticated_url` endpoint 兑换 |
| `token_type` | `Bearer` | 标准 Bearer token |
| `issued_token_type` | `urn:authgear:params:oauth:token-type:pre-authenticated-url-token` | 表明这不是普通的 access_token |
| `expires_in` | `300` | 5分钟有效期 |
| `id_token` | JWT | **新签发的 ID Token**，`ds_hash` 指向新轮换的 device_secret |
| `device_secret` | 32位随机字符串 | **新 device_secret**，**仅返回一次** |

**device_secret 轮换** (`handler_token.go:955-963`):

```go
// 对原始的 offlineGrant 执行 device_secret 轮换
offlineGrant, err = h.rotateDeviceSecret(ctx, client, offlineGrant, resp)
```

`rotateDeviceSecret` (`handler_token.go:472-494`):

```go
func (h *TokenHandler) rotateDeviceSecret(ctx context.Context, client *config.OAuthClientConfig,
    offlineGrant *oauth.OfflineGrant, resp protocol.TokenResponse) (*oauth.OfflineGrant, error) {
    dpopJKT, _, err := dpop.GetDPoPProofJKT(ctx, client)
    if err != nil {
        return nil, err
    }
    // 生成新的 device_secret,写入 response
    deviceSecretHash := h.TokenService.IssueDeviceSecret(ctx, resp)
    // 更新 offlineGrant 中的 DeviceSecretHash
    offlineGrant, err = h.OfflineGrants.UpdateOfflineGrantDeviceSecretHash(
        ctx, offlineGrant.ID, deviceSecretHash, dpopJKT, offlineGrant.ExpireAtForResolvedSession,
    )
    return offlineGrant, nil
}
```

**新 ID Token 签发** (`handler_token.go:966-979`):

```go
// Issue new id_token which associated to the new device_secret
prepareIDTokenResult, err := h.IDTokenIssuer.PrepareIDToken(ctx, oidc.PrepareIDTokenOptions{
    ClientID:           client.ClientID,
    SID:                oauth.EncodeSID(offlineGrant),
    AuthenticationInfo: offlineGrant.GetAuthenticationInfo(),
    ClientLike:         oauth.ClientClientLike(client, []string{}),
    DeviceSecretHash:   offlineGrant.DeviceSecretHash,  // 新的 device_secret 的哈希
})
```

#### Step 5: 兑换 Pre-Authenticated URL Token

App 2 用 `pre-authenticated-url-token` 作为 `access_token`，在 pre-authenticated URL endpoint 中兑换真正的 access token + refresh token。

```http
POST /oauth2/token HTTP/1.1
Host: accounts.example.com
Content-Type: application/x-www-form-urlencoded

grant_type=urn%3Aauthgear%3Aparams%3Aoauth%3Agrant-type%3Apre-authenticated-url-token
&client_id=app2
&access_token=E7zQ9wR8tY6uI5oP4aS3dF2gH1jK0L9mN8bV7cX6Z
```

**服务端处理** (`handler_token.go` — `handlePreAuthenticatedURLTokenGrant` 或类似函数)：
- 验证 pre-authenticated-url-token 的有效性（哈希匹配、未过期、client_id 匹配）
- 提供与原始 OfflineGrant session 相同用户身份的短期 refresh token + access token

#### Step 6: 最终 Token Response

```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "access_token":  "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...<App 2 的 access_token>",
  "refresh_token": "f9e8d7c6-b5a4-3f2e-1d0c-9b8a7f6e5d4c.pQ0kP9oL8mN7nM6aZ8bX7cW6eV5fU4gT",
  "token_type":    "Bearer",
  "expires_in":    3600,
  "id_token":      "eyJhbGciOiJSUzI1NiIsImtpZCI6..."
}
```

#### Step 7: SDK 更新共享存储

App 2 的 SDK 将新的 `device_secret` 和 `id_token` 更新到共享存储（覆盖 App 1 的旧值）。

**在 refresh_token 流程中的 device_secret 轮换** (`handler_token.go:496-515`):

```go
func (h *TokenHandler) rotateDeviceSecretIfDeviceSecretIsPresentAndValid(
    ctx context.Context, client *config.OAuthClientConfig, deviceSecret string,
    authorizedScopes []string, offlineGrant *oauth.OfflineGrant,
    resp protocol.TokenResponse) (*oauth.OfflineGrant, bool, error) {
    if deviceSecret == "" {
        return offlineGrant, false, nil  // 没有提供 device_secret，不轮换
    }
    // 恒定时间比较提供的 device_secret 与存储的哈希
    if subtle.ConstantTimeCompare(
        []byte(oauth.HashToken(deviceSecret)),
        []byte(offlineGrant.DeviceSecretHash)) != 1 {
        return offlineGrant, false, nil  // device_secret 无效，不轮换
    }
    return h.rotateDeviceSecretIfSufficientScope(ctx, client, authorizedScopes, offlineGrant, resp)
}
```

---

## 四、关键服务端代码路径

### 4.1 Token Exchange 流程

```
POST /oauth2/token (grant_type=token-exchange)
  └─ TokenHandler.Handle()                                 handler_token.go
     └─ handleTokenExchange()                              handler_token.go:780
        └─ handlePreAuthenticatedURLToken()                handler_token.go:861
           ├─ 验证 actor_token_type                        handler_token.go:866
           ├─ 验证 subject_token_type                      handler_token.go:869
           ├─ VerifyIDToken(subject_token)                 handler_token.go:880
           ├─ 验证 audience == issuer                      handler_token.go:884
           ├─ resolveIDTokenSession(sid)                   handler_token.go:887,795-825
           │  └─ DecodeSID(sid) → typ, sessionID           sid.go
           │     ├─ TypeIdentityProvider → IDPSessions.Get()
           │     └─ TypeOfflineGrant → OfflineGrantService.GetOfflineGrant()
           ├─ 验证 PreAuthenticatedURL scope               handler_token.go:903
           ├─ verifyIDTokenDeviceSecretHash()              handler_token.go:914,827-859
           │  ├─ SHA256(actor_token)
           │  ├─ ConstantTimeCompare(ds_hash, SHA256(actor_token))
           │  ├─ ConstantTimeCompare(OfflineGrant.DeviceSecretHash, SHA256(actor_token))
           │  └─ MatchDeviceSecretDPoPJKT()
           └─ PreAuthenticatedURLTokenService               handler_token.go:943
              .IssuePreAuthenticatedURLToken()
              └─ 创建临时 token (Redis, 5min TTL)
```

### 4.2 首次登录 Authorization Code 流程

```
POST /oauth2/token (grant_type=authorization_code)
  └─ TokenHandler.Handle()
     └─ doHandle()                                        handler_token.go
        └─ handleAuthorizationCode()
           └─ doIssueTokensForAuthorizationCode()          handler_token.go:1680
              ├─ shouldIssueDeviceSecret(scopes)           handler_token.go:2067
              │  └─ 遍历 scope, 如果包含 device_sso → true
              ├─ IssueOfflineGrantOptions{                 handler_token.go:1799-1808
              │      IssueDeviceSecret: true }
              └─ issueOfflineGrant() / IssueRefreshTokenForOfflineGrant()
                 └─ TokenService.IssueOfflineGrant()       service_token.go:122
                    └─ if opts.IssueDeviceSecret:
                       └─ IssueDeviceSecret()              service_token.go:415
                          ├─ GenerateToken()               token.go:12 (32位随机字符串)
                          ├─ HashToken(deviceSecret)       token.go:17 (SHA256)
                          ├─ resp.DeviceSecret(deviceSecret) ← 写入 HTTP 响应
                          └─ 返回 deviceSecretHash → 存入 offlineGrant.DeviceSecretHash
```

### 4.3 Refresh Token 流程中的 Device Secret

```
POST /oauth2/token (grant_type=refresh_token)
  └─ TokenHandler.Handle()
     └─ handleRefreshToken()
        └─ rotateDeviceSecretIfDeviceSecretIsPresentAndValid()  handler_token.go:496
           ├─ 如果请求中提供了 device_secret:
           │  └─ if SHA256(device_secret) == offlineGrant.DeviceSecretHash:
           │     └─ rotateDeviceSecretIfSufficientScope()       handler_token.go:517
           │        └─ 如果 scope 包含 device_sso: 生成新 device_secret
           └─ 如果没有提供 device_secret: 不轮换
```

---

## 五、安全机制详解

### 5.1 Device Secret 哈希链

```
device_secret (明文, 仅传输一次)
     │
     └─ SHA256 → DeviceSecretHash (存储于 OfflineGrant)
                  │
                  └─ 写入 ID Token 的 ds_hash claim
                     │
                     └─ 下一次 Token Exchange 时:
                        验证 SHA256(actor_token) == ds_hash == DeviceSecretHash
```

### 5.2 DPoP 绑定

如果客户端在请求中提供 DPoP Proof (HTTP Header: `DPoP: <JWT>`)，服务端会将 `device_secret` 绑定到 DPoP 公钥：

```go
// service_token.go:171
if opts.IssueDeviceSecret {
    deviceSecretHash := s.IssueDeviceSecret(ctx, resp)
    offlineGrant.DeviceSecretDPoPJKT = opts.DPoPJKT  // 公钥指纹
}
```

后续验证时从 HTTP Header 中提取 DPoP JKT，与 `offlineGrant.DeviceSecretDPoPJKT` 比较。

### 5.3 登出联动

Device SSO 下所有 App 共享同一个 OfflineGrant。由于 `OfflineGrant.SSOGroupIDPSessionID()` 可能指向关联的 IDP Session：
- Web 登出 → 删除 IDP Session → 所有关联 OfflineGrant 的 refresh token 均失效
- App 内登出 → 清除 device_secret → 其他 App 的 `checkDeviceSSOPossible()` 失败

### 5.4 Token 安全措施

| 措施 | 实现 | 位置 |
|------|------|------|
| 哈希存储 | `SHA256(token)` → hex | `pkg/lib/oauth/token.go:17` |
| 仅返回一次 | token 明文仅在生成时返回 | `handler_token.go` |
| 恒定时间比较 | `crypto/subtle.ConstantTimeCompare` | `handler_token.go:840-844` |
| PKCE 强制 | native client 必须用 PKCE S256 | OAuth RFC |
| DPoP 绑定 | 可选，将 device_secret 绑定到硬件密钥 | `handler_token.go:848-856` |
| Token 轮换 | Refresh token 每次使用后轮换 | `grant_offline_service.go` |

---

## 六、配置要求总结

### 6.1 OAuth Client 配置

```yaml
oauth:
  clients:
    - client_id: app1
      name: "App 1"
      application_type: native
      redirect_uris:
        - "com.example.app1://callback"
      grant_types:
        - authorization_code
        - refresh_token
        - urn:ietf:params:oauth:grant-type:token-exchange
      x_pre_authenticated_url_enabled: true  # Device SSO 必需
      x_sso_enabled: true  # 可选，启用 Browser SSO (WebView 与系统浏览器共享 Cookie)

    - client_id: app2
      name: "App 2"
      application_type: native
      redirect_uris:
        - "com.example.app2://callback"
      grant_types:
        - authorization_code
        - refresh_token
        - urn:ietf:params:oauth:grant-type:token-exchange
      x_pre_authenticated_url_enabled: true  # Device SSO 必需
```

### 6.2 Feature Flag

无需额外的 feature flag（不像 App2App SSO 需要 `app2app_enabled: true`）。

Device SSO 只需要 client 级别配置 `x_pre_authenticated_url_enabled: true`。

---

## 七、与 App2App SSO 的对比

| 特性 | Device SSO (Native SSO) | App2App SSO |
|------|------------------------|-------------|
| **协议基础** | OIDC Native SSO (RFC 8693 Token Exchange) | 自研 App2App JWT Challenge |
| **共享方式** | 共享存储（iOS App Group / Android AccountManager） | 不共享存储，通过 Universal Link / App Link 通信 |
| **核心凭证** | `device_secret` (对称密钥) | 设备密钥对（JWK，非对称密钥） |
| **用户交互** | App 1 需要，App 2 免交互 | App 1 需要，App 2 需用户确认 |
| **Session 独立性** | 共享底层 OfflineGrant（登出互相影响） | 每个 App 独立 Session（登出独立） |
| **配置复杂度** | 低（只需 `x_pre_authenticated_url_enabled`） | 高（需 `app2app_enabled` + 设备密钥管理） |
| **安全级别** | 依赖共享存储安全性 + DPoP | 依赖设备密钥对 + JWT 签名 |

---

## 八、关键文件索引

| 文件 | 内容 |
|------|------|
| `docs/specs/oidc-native-sso.md` | OIDC Native SSO 设计规范 |
| `pkg/lib/oauth/handler/handler_token.go:780-793` | `handleTokenExchange` — Token Exchange 入口 |
| `pkg/lib/oauth/handler/handler_token.go:795-825` | `resolveIDTokenSession` — 从 sid 解析 session |
| `pkg/lib/oauth/handler/handler_token.go:827-859` | `verifyIDTokenDeviceSecretHash` — DS Hash 三重验证 |
| `pkg/lib/oauth/handler/handler_token.go:861-985` | `handlePreAuthenticatedURLToken` — 核心处理逻辑 |
| `pkg/lib/oauth/handler/handler_token.go:472-494` | `rotateDeviceSecret` — device_secret 轮换 |
| `pkg/lib/oauth/handler/handler_token.go:496-515` | `rotateDeviceSecretIfDeviceSecretIsPresentAndValid` |
| `pkg/lib/oauth/handler/handler_token.go:517-533` | `rotateDeviceSecretIfSufficientScope` |
| `pkg/lib/oauth/handler/handler_token.go:1680-1855` | `doIssueTokensForAuthorizationCode` — 首次登录 token 颁发 |
| `pkg/lib/oauth/handler/handler_token.go:2067-2076` | `shouldIssueDeviceSecret` — 判断是否生成 device_secret |
| `pkg/lib/oauth/handler/service_token.go:32-43` | `IssueOfflineGrantOptions` 结构体 |
| `pkg/lib/oauth/handler/service_token.go:122-203` | `IssueOfflineGrant` — 创建 OfflineGrant + device_secret |
| `pkg/lib/oauth/handler/service_token.go:415-420` | `IssueDeviceSecret` — 生成 device_secret |
| `pkg/lib/oauth/handler/service_preauthenticated_url.go` | PreAuthenticatedURLToken 的签发和兑换 |
| `pkg/lib/oauth/scope.go:17-19` | `DeviceSSOScope`, `PreAuthenticatedURLScope` 常量 |
| `pkg/lib/oauth/scope.go:246-255` | Scope 验证（要求 PreAuthenticatedURLEnabled） |
| `pkg/lib/oauth/grant_offline.go:40-80` | `OfflineGrant` 结构体 |
| `pkg/lib/oauth/grant_offline.go:218-242` | `SSOGroupIDPSessionID`, `IsSameSSOGroup` |
| `pkg/lib/oauth/grant_offline_service.go:102-116` | SSO 验证链（IDP Session 存活检查） |
| `pkg/lib/oauth/token.go:12-19` | `GenerateToken`, `HashToken` |
| `pkg/lib/oauth/oidc/id_token.go:117-119` | `ds_hash` claim 写入 ID Token |
| `pkg/lib/oauth/protocol/token.go:34,42` | `DeviceSecret()` 请求参数, `DeviceSecret()` 响应字段 |
| `pkg/lib/oauth/grant_type.go` | Grant Type 常量 |
| `pkg/lib/config/oauth.go:252-281` | OAuth client 配置字段 |
| `pkg/lib/session/session.go:19-26` | SSOGroupIDPSessionID 接口 |
| `pkg/lib/session/manager.go:61-137` | SSO Group 登出传播 |
