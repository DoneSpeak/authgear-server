# 纯原生 Account SDK 设计分析 & OfflineGrant 完整说明

---

## 第一部分：不依赖浏览器的纯原生 Account SDK 设计

### 核心结论

**如果只做 App ↔ App 的 Device SSO，不需要 IDP Session（Browser Cookie-based Session）。**

`device_secret` + Token Exchange（基于 OIDC Native SSO 规范）是一个**完整的、自包含的**跨 App 认证机制。IDP Session 只在需要 App ↔ 系统浏览器 SSO 时才需要引入。

### 两种 Session 的本质区别

```
IDP Session (cookie-based)
  用途: 浏览器登录状态共享
  载体: HTTP Cookie (Set-Cookie / Cookie header)
  创建方式: 用户在 WebView/浏览器中登录 → 服务端设 Set-Cookie
  共享范围: 同一 Cookie Jar (iOS: ASWebAuthenticationSession, Android: Custom Tabs)
  前提条件: 必须有浏览器组件参与

OfflineGrant / DeviceSession (token-based)
  用途: 原生 App 的认证状态
  载体: OAuth refresh_token (或等价的自定义 token)
  创建方式: POST /api/login → 服务端返回 token
  共享范围: 通过设备共享存储 (iOS App Group / Android AccountManager) 传递
  前提条件: 不需要浏览器, 纯 API 通信即可
```

### Authgear 为什么同时有两者？

Authgear 的架构演进：
1. **最初是 Web-first**：只有 IDP Session（浏览器用户）
2. **后来加入 Native App 支持**：引入 OfflineGrant（token-based）
3. **为了兼容和登出联动**：OfflineGrant 通过 `IDPSessionID` + `SSOEnabled` 回链到 IDP Session

**从零设计不需要这个历史包袱。**

### 纯原生 SDK 的架构设计

#### 第一个 App 登录（无需浏览器）

```
App 1                               Server
  │                                   │
  │  ① 原生 UI (native text field)     │
  │     用户输入 username/password      │
  │                                   │
  │  ② POST /api/auth/login            │
  │     {                              │
  │       "username": "...",           │
  │       "password": "...",           │
  │       "device_info": {             │
  │         "platform": "ios",         │
  │         "device_model": "iPhone15" │
  │       }                            │
  │     }                              │
  │                                   │
  │                                   │  ③ 验证凭据
  │                                   │     创建 DeviceSession:
  │                                   │       - sessionID (UUID)
  │                                   │       - userID
  │                                   │       - authenticatedAt
  │                                   │       - deviceSecretHash ← SHA256(device_secret)
  │                                   │       - refreshToken (32位随机字符串)
  │                                   │
  │  ④ Response:                       │
  │     {                              │
  │       "refresh_token": "...",      │ ← DeviceSession 的凭证
  │       "access_token": "...",       │
  │       "expires_in": 3600,          │
  │       "device_secret": "...",      │ ← 32位随机字符串, 仅返回一次
  │       "id_token": "..."            │ ← 含 ds_hash = SHA256(device_secret)
  │     }                              │
  │                                   │
  │  ⑤ SDK 存入本地安全存储:            │
  │     私有存储 (Keychain/Keystore):   │
  │       - refresh_token              │
  │       - access_token               │
  │     共享存储 (App Group/AccountManager):│
  │       - device_secret              │
  │       - id_token (含 ds_hash)      │
```

**全程没有浏览器参与，没有 cookie，没有 IDP Session。**

#### 第二个 App 登录（纯 Device SSO）

```
App 2                               Server
  │                                   │
  │  ① 从共享存储读取:                  │
  │     device_secret + id_token      │
  │     (App 1 写入的)                 │
  │                                   │
  │  ② POST /api/auth/device-sso      │
  │     {                              │
  │       "subject_token": "<id_token>",│
  │       "actor_token": "<device_secret>",│
  │       "scope": "..."              │
  │     }                              │
  │                                   │
  │                                   │  ③ 验证:
  │                                   │     - SHA256(actor_token) == id_token.ds_hash
  │                                   │     - SHA256(actor_token) == session.DeviceSecretHash
  │                                   │     - id_token.sid → 找到 DeviceSession
  │                                   │
  │                                   │  ④ 轮换 device_secret
  │                                   │     生成 App 2 的 refresh_token
  │                                   │     (关联到同一个 DeviceSession)
  │                                   │
  │  ⑤ Response:                       │
  │     {                              │
  │       "refresh_token": "...",      │ ← App 2 自己的 token
  │       "access_token": "...",       │
  │       "device_secret": "...",      │ ← 新 device_secret (轮换后)
  │       "id_token": "..."            │ ← 新 ds_hash
  │     }                              │
  │                                   │
  │  ⑥ SDK 更新共享存储:                │
  │     新 device_secret + 新 id_token │
  │     (覆盖 App 1 的旧值)            │
```

#### Token 刷新流程（带 device_secret 轮换）

```
App (已登录)                          Server
  │                                   │
  │  POST /api/auth/refresh            │
  │  {                                │
  │    "refresh_token": "...",        │
  │    "device_secret": "..."         │ ← 当前 device_secret
  │  }                                │
  │                                   │
  │                                   │  验证:
  │                                   │    - refresh_token hash 匹配
  │                                   │    - SHA256(device_secret) == session.DeviceSecretHash
  │                                   │
  │                                   │  若 scope 包含 device_sso:
  │                                   │    - 轮换 device_secret
  │                                   │    - 签发新 id_token (ds_hash 指向新值)
  │                                   │
  │  Response:                        │
  │  {                                │
  │    "refresh_token": "...",        │ ← 轮换后的新 token
  │    "access_token": "...",         │
  │    "device_secret": "...",        │ ← 新 device_secret
  │    "id_token": "..."              │ ← 新 ds_hash
  │  }                                │
  │                                   │
  │  更新共享存储:                      │
  │   新 device_secret + 新 id_token  │
```

#### 登出策略

```
场景 A: 单个 App 登出 (App 1)
  → 删除 App 1 的 refresh_token
  → 不删除 device_secret (App 2 仍在用)
  → App 2 不受影响

场景 B: 所有 App 登出 (用户主动)
  → 清除 deviceSecretHash
  → 删除所有 refresh_tokens
  → 清除共享存储中的 device_secret + id_token
  → 所有 App 下次 refresh 或 device-sso 都会失败

场景 C: Session 过期
  → refresh_token idle timeout / absolute timeout
  → OfflineGrant 整体失效
  → 所有 App 都需要重新登录
```

#### Session 模型设计

```
DeviceSession {
    id: UUID                          // session 唯一标识
    userID: string                    // 关联的用户

    createdAt: timestamp
    authenticatedAt: timestamp

    deviceSecretHash: string          // SHA256(device_secret)
    deviceSecretDPoPJKT: string?      // DPoP 绑定 (可选)

    refreshTokens: [{                 // 每个 App 一个
        clientID: string              // app1 / app2 / ...
        tokenHash: string             // SHA256(refresh_token)
        scopes: [string]
        authorizationID: string
        createdAt: timestamp
        lastAccess: timestamp
        rotatedTokenHash: string?     // token 轮换
        rotatedAt: timestamp?
    }]

    deviceInfo: {                     // 设备信息
        platform: "ios" | "android"
        deviceModel: string
    }

    accessInfo: {
        initialAccess: { time, ip, userAgent }
        lastAccess:    { time, ip, userAgent }
    }
}
```

**一个 DeviceSession = 一个用户的单设备登录状态。多个 App 通过 `refreshTokens` 数组共享同一个 Session，通过 `deviceSecretHash` 实现安全的跨 App 认证。**

### 是否需要 IDP Session？

| 需求场景 | 是否需要 IDP Session | 原因 |
|---------|---------------------|------|
| 只有 App ↔ App SSO | **不需要** | device_secret + Token Exchange 已完整覆盖 |
| App ↔ 系统浏览器 SSO | **需要** | 浏览器只用 cookie，无原生 SDK/共享存储 |
| 两者都要 | **需要但可解耦** | IDP Session 和 DeviceSession 平等共存 |

### 混合架构（同时支持 Browser SSO）

```
                    ┌─────────────┐
                    │   User ID   │
                    └──────┬──────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
     ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
     │IDP Session│   │ App 1     │   │ App 2     │
     │(browser)  │   │ refresh   │   │ refresh   │
     │cookie     │   │ token     │   │ token     │
     └─────┬─────┘   └─────┬─────┘   └─────┬─────┘
           │               │               │
           │        DeviceSecretHash       │
           │        共享存储传递             │
           │                               │
     Browser SSO                    Device SSO
     (通过 cookie)                  (通过 token exchange)

登出策略可按需配置:
  - 浏览器登出 → 只删 IDP Session (App 不受影响)
  - App 登出 → 只删该 App 的 refresh_token (浏览器不受影响)
  - 全设备登出 → 清除所有 + 清除 device_secret
```

---

## 第二部分：OfflineGrant 完整说明

### 概述

`OfflineGrant` 是 Authgear 中 Native App / SPA 使用的持久化会话类型。它是对 OAuth 2.0 Refresh Token 的服务端存储抽象，不同于 `IDPSession`（Cookie-based），它是 **Token-based** 的认证方式。

### 在 Session 体系中的位置

```go
// pkg/lib/session/session.go:12-17
const (
    TypeIdentityProvider Type = "idp"          // Cookie-based, 浏览器
    TypeOfflineGrant     Type = "offline_grant" // Token-based, Native App/SPA
)
```

Authgear 只有这两种持久化 Session 类型。`OfflineGrant` 实现了三个核心接口：

- `SessionBase` — 基础会话标识（ID、Type、AuthenticationInfo、SSOGroupIDPSessionID）
- `ResolvedSession`（通过 `OfflineGrantSession`）— 可解析的运行时会话
- `ListableSession` — 可在 Admin API 中列举的会话

### 完整数据结构

#### OfflineGrant（顶层 Session 容器）

```go
// pkg/lib/oauth/grant_offline.go:40-80
type OfflineGrant struct {
    // === 标识字段 ===
    AppID           string `json:"app_id"`
    ID              string `json:"id"`              // UUID, Session ID
    InitialClientID string `json:"client_id"`       // 创建此 Session 的第一个 OAuth Client

    // === 关联字段 ===
    IDPSessionID string `json:"idp_session_id,omitempty"`  // 关联的 IDP Session (可选)
    IdentityID   string `json:"identity_id,omitempty"`     // 仅 Biometric 认证时设置

    // === 时间字段 ===
    CreatedAt       time.Time `json:"created_at"`         // Session 创建时间
    AuthenticatedAt time.Time `json:"authenticated_at"`   // 用户最后认证时间

    // === 认证信息 ===
    Attrs      session.Attrs `json:"attrs"`       // { userID, claims: { amr: [...] } }
    AccessInfo access.Info   `json:"access_info"` // 首次和最后访问的 { time, ip, userAgent }

    // === 设备信息 ===
    DeviceInfo map[string]interface{} `json:"device_info,omitempty"`

    // === Browser SSO ===
    SSOEnabled bool `json:"sso_enabled,omitempty"`  // 是否加入 SSO Group

    // === App2App SSO ===
    App2AppDeviceKeyJWKJSON string `json:"app2app_device_key_jwk_json"` // 设备公钥 (JWK JSON)

    // === Device SSO ===
    DeviceSecretHash    string `json:"device_secret_hash"`      // SHA256(device_secret)
    DeviceSecretDPoPJKT string `json:"device_secret_dpop_jkt"`  // DPoP 公钥指纹

    // === 多 Client 支持 ===
    RefreshTokens []OfflineGrantRefreshToken `json:"refresh_tokens,omitempty"`

    // === SAML ===
    ParticipatedSAMLServiceProviderIDs []string `json:"participated_saml_service_provider_ids,omitempty"`

    // === 向后兼容 (已废弃, 新数据写入 RefreshTokens) ===
    Deprecated_AuthorizationID string   `json:"authz_id"`
    Deprecated_Scopes          []string `json:"scopes"`
    Deprecated_TokenHash       string   `json:"token_hash"`

    // === 瞬时字段 (不持久化) ===
    ExpireAtForResolvedSession time.Time `json:"-"`
}
```

#### OfflineGrantRefreshToken（每个 App 的 Token）

```go
// pkg/lib/oauth/grant_offline.go:19-38
type OfflineGrantRefreshToken struct {
    InitialTokenHash string    `json:"token_hash"`  // 首次 token 的 SHA256，用作该 token 的 ID
    ClientID         string    `json:"client_id"`   // 哪个 OAuth Client 拥有此 token
    CreatedAt        time.Time `json:"created_at"`
    Scopes           []string  `json:"scopes"`       // 授权的 scope
    AuthorizationID  string    `json:"authz_id"`
    DPoPJKT          string    `json:"dpop_jkt"`     // DPoP 绑定 (可选)
    AccessInfo       *access.Info `json:"access_info"`
    ExpireAt         *time.Time `json:"expire_at"`   // 短时效 token 的过期时间 (Pre-Authenticated URL)

    // Token 轮换
    RotatedTokenHash *string    `json:"rotated_token_hash,omitzero"`
    RotatedAt        *time.Time `json:"rotated_at,omitzero"`
}
```

#### Attrs（认证属性）

```go
// pkg/lib/session/attrs.go:8-11
type Attrs struct {
    UserID string                          `json:"user_id"`
    Claims map[model.ClaimName]interface{} `json:"claims"`  // 包含 AMR (Authentication Methods Reference)
}
```

#### AccessInfo（访问追踪）

```go
// pkg/lib/session/access/event.go:9-12
type Info struct {
    InitialAccess Event `json:"initial_access"`
    LastAccess    Event `json:"last_access"`
}

type Event struct {
    Timestamp time.Time `json:"time"`
    RemoteIP  string    `json:"ip,omitempty"`
    UserAgent string    `json:"user_agent,omitempty"`
}
```

### 一个 OfflineGrant = 一个用户的单设备登录状态

```
User "Alice" 在 iPhone 上:
  OfflineGrant {
    id: "og-001"
    userID: "user_alice"
    appID: "myapp"
    initialClientID: "app1"
    deviceInfo: { platform: "ios", model: "iPhone 15" }

    // 多个 App 共享同一个 DeviceSecretHash
    deviceSecretHash: "abc123..."

    refreshTokens: [
      { clientID: "app1", scopes: ["openid","offline_access","device_sso"], tokenHash: "h1" }
      { clientID: "app2", scopes: ["openid","offline_access"],            tokenHash: "h2" }
    ]
  }

User "Alice" 在 Android 平板上:
  OfflineGrant {
    id: "og-002"                              ← 不同的 OfflineGrant
    userID: "user_alice"
    deviceInfo: { platform: "android", model: "Galaxy Tab" }
    deviceSecretHash: "xyz789..."             ← 不同的 device_secret
    refreshTokens: [
      { clientID: "app1", tokenHash: "h3" }
    ]
  }
```

**关键设计**：
- **一个设备一个 OfflineGrant**：不同设备的 Session 天然隔离（不同 `deviceSecretHash`）
- **一个 OfflineGrant 多个 refresh_token**：同一设备上的多个 App 共享底层 Session（通过 Device SSO），但各自有独立的 refresh_token

### Token 编码格式

```go
// pkg/lib/oauth/token_encoding.go:301-312
func EncodeRefreshToken(token string, grantID string) string {
    return fmt.Sprintf("%s.%s", grantID, token)
}
// 格式: {offlineGrantID}.{32位随机字符串}
// 示例: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d.XyZ9aB8cD7eF6gH5iJ4kL3mN2oP1qR0sT"
```

服务端只存储 `SHA256(token)`，明文 token 仅在生成时返回给客户端一次。

### Token 轮换机制

```
初始状态:
  refreshTokens[0].InitialTokenHash = SHA256("token_abc")
  refreshTokens[0].RotatedTokenHash = nil

第一次使用 "token_abc" 后:
  refreshTokens[0].InitialTokenHash = SHA256("token_abc")
  refreshTokens[0].RotatedTokenHash = SHA256("token_def")  ← 新 token
  refreshTokens[0].RotatedAt = now

MatchCurrentHash("token_def") → true   (匹配 RotatedTokenHash)
MatchInitialHash("token_abc") → true   (匹配 InitialTokenHash)
MatchCurrentHash("token_abc") → true   (没有 RotatedTokenHash 时回退到 InitialTokenHash)
```

```go
// pkg/lib/oauth/grant_offline.go:469-474
func (t *OfflineGrantRefreshToken) MatchCurrentHash(anotherHash string) bool {
    if t.RotatedTokenHash != nil {
        return subtle.ConstantTimeCompare([]byte(anotherHash), []byte(*t.RotatedTokenHash)) == 1
    }
    return subtle.ConstantTimeCompare([]byte(anotherHash), []byte(t.InitialTokenHash)) == 1
}

func (t *OfflineGrantRefreshToken) MatchInitialHash(anotherHash string) bool {
    return subtle.ConstantTimeCompare([]byte(anotherHash), []byte(t.InitialTokenHash)) == 1
}
```

### Session 过期计算

```go
// pkg/lib/oauth/grant_offline_service.go:154-163
func (s *OfflineGrantService) computeRefreshTokenExpiryWithClient(
    token expirableRefreshToken, cfg *config.OAuthClientConfig) (expiry time.Time) {
    // 绝对过期: CreatedAt + RefreshTokenLifetime
    expiry = token.CreatedAt.Add(cfg.RefreshTokenLifetime.Duration())

    // Idle 过期 (可选): LastAccessAt + RefreshTokenIdleTimeout
    if *cfg.RefreshTokenIdleTimeoutEnabled {
        idleExpiry := token.LastAccessAt.Add(cfg.RefreshTokenIdleTimeout.Duration())
        if idleExpiry.Before(expiry) {
            expiry = idleExpiry  // 取两者中较早的
        }
    }
    return
}
```

两种过期策略可以同时启用，取较早的时间点。

### SSO Group 机制

```go
// pkg/lib/oauth/grant_offline.go:218-242
func (g *OfflineGrant) SSOGroupIDPSessionID() string {
    if g.SSOEnabled {
        return g.IDPSessionID   // 加入 SSO Group: 返回关联的 IDP Session ID
    }
    return ""                   // 不加入 SSO Group: 返回空
}

func (g *OfflineGrant) IsSameSSOGroup(ss session.SessionBase) bool {
    if g.EqualSession(ss) {     // 同一个 OfflineGrant
        return true
    }
    if g.SSOEnabled {           // SSO 启用时检查是否同组
        if g.SSOGroupIDPSessionID() == "" {
            return false
        }
        return g.SSOGroupIDPSessionID() == ss.SSOGroupIDPSessionID()
    }
    return false
}
```

**SSO Group 的规则**：
- `SSOEnabled=true` → `SSOGroupIDPSessionID()` = 关联的 IDP Session ID → 属于该 SSO Group
- `SSOEnabled=false` → `SSOGroupIDPSessionID()` = "" → 独立存在
- IDP Session 登出/过期 → 同组所有 OfflineGrant 自动失效（`GetOfflineGrant` 检查）

### GetOfflineGrant 的验证链

```go
// pkg/lib/oauth/grant_offline_service.go:85-119
func (s *OfflineGrantService) GetOfflineGrant(ctx context.Context, id string) (*OfflineGrant, error) {
    g, err := s.OfflineGrants.GetOfflineGrantWithoutExpireAt(ctx, id)
    if err != nil {
        return nil, err
    }

    // 检查 1: 计算并验证过期时间
    expiry, err := s.ComputeOfflineGrantExpiry(g)
    // ...
    g.ExpireAtForResolvedSession = expiry
    now := s.Clock.NowUTC()
    if now.After(g.ExpireAtForResolvedSession) {
        return nil, ErrGrantNotFound
    }

    // 检查 2: 若 SSOEnabled && 有 IDPSessionID
    //          → 检查关联的 IDP Session 是否存在且未过期
    if g.SSOEnabled && g.IDPSessionID != "" {
        idp, err := s.IDPSessions.Get(ctx, g.IDPSessionID)
        if err != nil {
            if errors.Is(err, idpsession.ErrSessionNotFound) {
                return nil, ErrGrantNotFound  // IDP Session 不存在 → OfflineGrant 失效
            }
            return nil, err
        }
        idpSessionExpired := s.IDPSessions.CheckSessionExpired(idp)
        if idpSessionExpired {
            return nil, ErrGrantNotFound  // IDP Session 过期 → OfflineGrant 失效
        }
    }

    return g, nil
}
```

**验证链保证**：Web 登出（删除 IDP Session）→ 所有 `SSOEnabled=true` 的 OfflineGrant 自动失效。

### OfflineGrantSession（运行时适配层）

`OfflineGrant` 本身只实现 `ListableSession`。在运行时（Token 验证、API 鉴权），通过 `ToSession()` 创建 `OfflineGrantSession`（实现 `ResolvedSession`）：

```go
// pkg/lib/oauth/grant_offline.go:248-297
func (g *OfflineGrant) ToSession(refreshTokenHash string) (*OfflineGrantSession, bool) {
    // 1. 向后兼容: 空 hash 或匹配 deprecated token hash → 使用 "root" grant
    // 2. 遍历 RefreshTokens, 匹配 InitialTokenHash 或 RotatedTokenHash
    // 3. 找到匹配的 token → 创建 OfflineGrantSession (携带该 token 的 clientID, scopes, DPoPJKT)
    // 4. 未找到 → (nil, false)
}
```

`OfflineGrantSession` 携带当前请求对应 Client 的 scope、DPoP 绑定等信息，实现按 Client 隔离的访问控制。

### Housekeeping（清理机制）

```go
// pkg/lib/oauth/grant_offline_service.go:307-362
func (s *OfflineGrantService) housekeepOfflineGrant(ctx context.Context, grant *OfflineGrant) {
    // 每次 refresh token 操作后触发, 清理过期 token:
    // 1. 移除已过期的短时效 token (ExpireAt < now)
    // 2. 移除已被删除的 Client 的 token
    // 3. 移除 idle/absolute 过期的 token
    // 4. 保留 root token (第一个 refresh token, 永不自动删除)
}
```

### 完整生命周期

```
创建:
  POST /oauth2/token (grant_type=authorization_code)
    → doIssueTokensForAuthorizationCode()
      → IssueOfflineGrantOptions
        → TokenService.IssueOfflineGrant()
          → 生成 OfflineGrant
          → 生成 device_secret (若 scope=device_sso)
          → 写入 Redis

使用:
  POST /oauth2/token (grant_type=refresh_token)
    → GetOfflineGrant(id)
      → 检查过期
      → 检查 SSO Group (若 SSOEnabled)
    → AccessOfflineGrant()
      → 更新 LastAccess
      → Token 轮换 (若启用)
    → housekeepOfflineGrant()
      → 清理过期 token

添加新 App (Device SSO):
  POST /oauth2/token (grant_type=token-exchange)
    → handlePreAuthenticatedURLToken()
      → verifyIDTokenDeviceSecretHash()
      → rotateDeviceSecret()
      → CreateNewRefreshToken() → 添加新的 refresh_token

删除:
  单个 App 登出 → RemoveOfflineGrantRefreshTokens(tokenHashes)
  整个 Session 过期 → OfflineGrant 整体从 Redis 移除
  Web 登出 → IDP Session 删除 → GetOfflineGrant 返回 ErrGrantNotFound
```

### 存储

OfflineGrant 存储在 **Redis** 中，使用分布式锁保证并发安全：

```go
// pkg/lib/oauth/redis/store.go
// 所有写操作都经过 Redis Mutex:
//   mutex := s.Redis.NewMutex(offlineGrantMutexName(appID, grantID))
//   mutex.LockContext(ctx)
//   defer mutex.UnlockContext(ctx)
```

### 设计要点总结

| 设计特征 | 说明 |
|---------|------|
| **单设备单 Session** | 同一设备的不同 App 共享一个 OfflineGrant（通过 Device SSO），各自有独立 refresh_token |
| **Token 哈希存储** | 服务端只存 SHA256(token)，明文仅返回一次 |
| **恒定时间比较** | 所有 token hash 比较使用 `crypto/subtle.ConstantTimeCompare` |
| **多 Client 支持** | 一个 OfflineGrant 包含多个 `RefreshToken`，每个绑定不同 Client |
| **Token 轮换** | Refresh token 可配置每次使用后轮换（RefreshTokenRotationEnabled） |
| **双过期策略** | 绝对过期 (RefreshTokenLifetime) + Idle 过期 (RefreshTokenIdleTimeout) |
| **SSO Group 联动** | SSOEnabled=true 时，IDP Session 过期/删除会导致 OfflineGrant 失效 |
| **Redis 分布式锁** | 所有 OfflineGrant 写操作使用 Redis Mutex |
| **Housekeeping** | 每次 token 操作后自动清理过期的 refresh token |
| **向后兼容** | 旧的 `Deprecated_*` 字段与新的 `RefreshTokens` 数组共存 |

### 从零设计时的简化

如果不需要 Browser SSO 和历史兼容：

```
简化为 DeviceSession {
    id: UUID
    userID: string
    authenticatedAt: timestamp

    deviceSecretHash: string        // Device SSO 核心
    deviceSecretDPoPJKT: string?

    refreshTokens: [{
        clientID: string
        tokenHash: string
        scopes: [string]
        createdAt: timestamp
        lastAccess: timestamp
        rotatedTokenHash: string?
    }]

    deviceInfo: { platform, model }
    accessInfo: { initial, last }
}

可移除的 Authgear 特有设计:
  - IDPSessionID / SSOEnabled    → 如果不需要 Browser SSO
  - App2AppDeviceKeyJWKJSON       → 如果不需要 App2App SSO
  - Deprecated_* 字段             → 如果没有向后兼容需求
  - SAML 字段                     → 如果不需要 SAML
  - OfflineGrantSession 适配层    → 直接让 DeviceSession 实现 ResolvedSession
```
