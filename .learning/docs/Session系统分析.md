# Authgear Session 系统分析

## 概述

本文档详细分析 Authgear 项目中与 session 相关的代码，包括 session 的类型、结构、存储、生命周期、与 cookie 和 device 的关系，以及在 OAuth2 流程中的使用。

---

## 1. Session 类型总览

在 Authgear 中，存在以下几种类型的 session：

| Session 类型 | 包路径 | 主要用途 | 存储位置 |
|-------------|--------|---------|---------|
| **IDPSession** | `pkg/lib/session/idpsession` | 用户登录后的身份提供者会话 | Redis |
| **OfflineGrant** | `pkg/lib/oauth` | OAuth 刷新令牌会话（用于 native app） | PostgreSQL |
| **OAuthSession** | `pkg/lib/oauth/oauthsession` | OAuth 授权端点的临时会话 | Redis |
| **WebappSession** | `pkg/auth/webapp` | Web 应用交互会话 | Redis |
| **AuthflowSession** | `pkg/lib/authenticationflow` | 认证流程会话 | Redis |
| **WorkflowSession** | `pkg/lib/workflow` | 工作流会话（旧版认证流程） | Redis |
| **SAMLSession** | `pkg/lib/saml/samlsession` | SAML 协议会话 | Redis |
| **AppSessionToken** | `pkg/lib/oauth` | 应用会话令牌 | Redis |

---

## 2. SSO Token 流程中的 Session 演进

### 2.1 流程概述

从 `/oauth2/authorize` 到 `/oauth2/token` 的完整流程中，session 的演进如下：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          OAuth2 Authorization Flow                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. /oauth2/authorize                                                       │
│     ├── 创建 OAuthSession (Entry)                                         │
│     └── 设置 oauth_ui cookie                                               │
│              ↓                                                              │
│  2. 重定向到 Web App                                                        │
│     ├── 从 OAuthSession 创建 WebappSession                                  │
│     └── 设置 webapp_session cookie                                         │
│              ↓                                                              │
│  3. 用户认证流程                                                            │
│     ├── 创建 AuthflowSession                                               │
│     └── 认证完成后创建 IDPSession                                          │
│              ↓                                                              │
│  4. 用户同意后                                                              │
│     ├── 创建 AuthenticationInfo Entry                                       │
│     └── 创建 CodeGrant                                                     │
│              ↓                                                              │
│  5. /oauth2/token (authorization_code)                                      │
│     ├── 消耗 CodeGrant                                                     │
│     ├── 创建 OfflineGrant (含 refresh token)                               │
│     └── 发放 access_token + refresh_token                                  │
│              ↓                                                              │
│  6. /oauth2/token (refresh_token)                                           │
│     ├── 验证 OfflineGrant                                                  │
│     ├── 刷新访问事件                                                       │
│     └── 发放新的 access_token (+ 可选的新 refresh_token)                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 各阶段 Session 详细说明

#### 阶段 1: OAuthSession (OAuth Session Entry)

**结构位置**: `pkg/lib/oauth/oauthsession/model.go:15-18`

```go
type Entry struct {
    ID string `json:"id,omitempty"`
    T  T      `json:"t,omitempty"`
}

type T struct {
    AuthorizationRequest protocol.AuthorizationRequest `json:"authorization_request,omitempty"`
    SettingsActionID     string                        `json:"settings_action_id,omitempty"`
    SettingsActionResult *SettingsActionResult         `json:"settings_action_result,omitempty"`
}
```

**创建时机**: `pkg/lib/oauth/handler/handler_authz.go:459-465`

```go
// 创建 oauth session 并重定向到 web app
oauthSessionEntry := oauthsession.NewEntry(oauthsession.T{
    AuthorizationRequest: r,
})
err = h.OAuthSessionService.Save(ctx, oauthSessionEntry)
```

**存储**: Redis，TTL 为 `duration.UserInteraction + duration.Consent`

**与客户端关联**: 通过 `oauth_ui` cookie 传递 Entry ID， SameSite=None

---

#### 阶段 2: WebappSession

**结构位置**: `pkg/auth/webapp/session.go:61-119`

```go
type Session struct {
    ID string `json:"id"`
    Steps []SessionStep `json:"steps,omitempty"`
    Authflow *Authflow `json:"authflow,omitempty"`
    SAMLSessionID  string `json:"saml_session_id,omitempty"`
    OAuthSessionID string `json:"oauth_session_id,omitempty"`
    ClientID string `json:"client_id,omitempty"`
    RedirectURI string `json:"redirect_uri,omitempty"`
    KeepAfterFinish bool `json:"keep_after_finish,omitempty"`
    Extra map[string]interface{} `json:"extra"`
    Prompt []string `json:"prompt_list,omitempty"`
    Page string `json:"page,omitempty"`
    UpdatedAt time.Time `json:"updated_at,omitempty"`
    UserIDHint string `json:"user_id_hint,omitempty"`
    CanUseIntentReauthenticate bool `json:"can_use_intent_reauthenticate,omitempty"`
    SuppressIDPSessionCookie bool `json:"suppress_idp_session_cookie,omitempty"`
    OAuthProviderAlias string `json:"oauth_provider_alias,omitempty"`
    LoginHint string `json:"login_hint,omitempty"`
    SettingsActionID string `json:"settings_action_id,omitempty"`
    IsCompleted bool `json:"is_completed,omitempty"`
}
```

**创建时机**: `pkg/auth/webapp/session_middleware.go:118-162`

当检测到 `oauth_ui` cookie 时，从 OAuthSession 创建 WebappSession：

```go
func (m *SessionMiddleware) createSessionFromOAuthSession(ctx context.Context, oauthSessionID string) []*http.Cookie {
    entry, err := m.OAuthSessions.Get(ctx, oauthSessionID)
    if entry != nil {
        req := entry.T.AuthorizationRequest
        uiInfo, err := m.OAuthUIInfoResolver.ResolveForUI(ctx, req)
        sessionOptions = SessionOptions{
            OAuthSessionID:             oauthSessionID,
            ClientID:                   uiInfo.ClientID,
            RedirectURI:                uiInfo.RedirectURI,
            Prompt:                     uiInfo.Prompt,
            UserIDHint:                 uiInfo.UserIDHint,
            CanUseIntentReauthenticate: uiInfo.CanUseIntentReauthenticate,
            Page:                       uiInfo.Page,
            SuppressIDPSessionCookie:   uiInfo.SuppressIDPSessionCookie,
            OAuthProviderAlias:         uiInfo.OAuthProviderAlias,
            LoginHint:                  uiInfo.LoginHint,
            SettingsActionID:           entry.T.SettingsActionID,
        }
    }
    session := NewSession(sessionOptions)
    result, err := m.Sessions.CreateSession(ctx, session, unimportant)
    return result.Cookies
}
```

**存储**: Redis，TTL 为 `interaction.GraphLifetime`

**与客户端关联**: 通过 `webapp_session` cookie 关联，SameSite=Lax

---

#### 阶段 3: AuthflowSession

**结构位置**: `pkg/lib/authenticationflow/session.go:13-31`

```go
type Session struct {
    FlowID string `json:"flow_id"`
    OAuthSessionID string `json:"oauth_session_id,omitempty"`
    SAMLSessionID  string `json:"saml_session_id,omitempty"`
    ClientID    string   `json:"client_id,omitempty"`
    RedirectURI string   `json:"redirect_uri,omitempty"`
    Prompt      []string `json:"prompt,omitempty"`
    State       string   `json:"state,omitempty"`
    XState      string   `json:"x_state,omitempty"`
    UILocales   string   `json:"ui_locales,omitempty"`
    BotProtectionVerificationResult *BotProtectionVerificationResult `json:"bot_protection_verification_result,omitempty"`
    IDToken                         string                           `json:"id_token,omitempty"`
    SuppressIDPSessionCookie        bool                             `json:"suppress_idp_session_cookie,omitempty"`
    UserIDHint                      string                           `json:"user_id_hint,omitempty"`
    LoginHint                       string                           `json:"login_hint,omitempty"`
}
```

**注意**: 注释明确说明 `Session must not contain web session ID. This is to ensure webapp does not have privilege in authflow.`

**存储**: Redis，TTL 为 `duration.UserInteraction`

---

#### 阶段 4: IDPSession (用户登录后创建)

**结构位置**: `pkg/lib/session/idpsession/session.go:14-35`

```go
type IDPSession struct {
    ID    string `json:"id"`
    AppID string `json:"app_id"`
    CreatedAt time.Time `json:"created_at"`
    AuthenticatedAt time.Time     `json:"authenticated_at"`
    Attrs           session.Attrs `json:"attrs"`
    AccessInfo access.Info `json:"access_info"`
    TokenHash string `json:"token_hash"`
    ParticipatedSAMLServiceProviderIDs []string `json:"participated_saml_service_provider_ids,omitempty"`
    ExpireAtForResolvedSession time.Time `json:"-"`
}
```

**创建时机**: 在认证流程完成后，通过 `IntentEnsureSession` 或 `NodeDoCreateSession` 创建

**关键代码**: `pkg/lib/session/idpsession/provider.go:55-72`

```go
func (p *Provider) MakeSession(attrs *session.Attrs) (*IDPSession, string) {
    now := p.Clock.NowUTC()
    accessEvent := access.NewEvent(now, p.RemoteIP, p.UserAgentString)
    session := &IDPSession{
        ID:              uuid.New(),
        CreatedAt:       now,
        AuthenticatedAt: now,
        Attrs:           *attrs,
        AccessInfo: access.Info{
            InitialAccess: accessEvent,
            LastAccess:    accessEvent,
        },
    }
    setSessionExpireAtForResolvedSession(session, p.Config)
    token := p.generateToken(session)
    return session, token
}
```

**存储**: Redis

**与客户端关联**: 通过 `session` cookie 关联，包含 `ID` + `Token` 格式: `{id}.{token}`

---

#### 阶段 5: AuthenticationInfo Entry

**结构位置**: `pkg/lib/authn/authenticationinfo/model.go:35-50`

```go
type Entry struct {
    ID             string `json:"id,omitempty"`
    T              T      `json:"t,omitempty"`
    OAuthSessionID string `json:"oauth_session_id,omitempty"`
    SAMLSessionID  string `json:"saml_session_id,omitempty"`
}

type T struct {
    UserID string `json:"user_id,omitempty"`
    AMR             []string  `json:"amr,omitempty"`
    AuthenticatedAt time.Time `json:"authenticated_at,omitempty"`
    ShouldFireAuthenticatedEventWhenIssueOfflineGrant bool `json:"should_fire_authenticated_event_when_issue_offline_grant,omitempty"`
    AuthenticatedBySessionType string
    AuthenticatedBySessionID   string
    IdentitySpecs []*identity.Spec `json:"identity_specs,omitzero"`
}
```

**作用**: 在 OAuth 授权流程中临时保存认证信息，用于在 consent 阶段与 OAuthSession 关联。

**存储**: Redis

---

#### 阶段 6: CodeGrant

**结构位置**: `pkg/lib/oauth/code_grant.go` (简化)

```go
type CodeGrant struct {
    AppID string `json:"app_id"`
    AuthorizationID string `json:"authz_id"`
    SessionType session.Type `json:"session_type,omitempty"`
    SessionID string `json:"session_id,omitempty"`
    AuthenticationInfo authenticationinfo.T `json:"authentication_info"`
    IDTokenHintSID string `json:"id_token_hint_sid,omitempty"`
    RedirectURI string `json:"redirect_uri"`
    AuthorizationRequest protocol.AuthorizationRequest `json:"authorization_request"`
    DPoPJKT string `json:"dpop_jkt,omitempty"`
    ExpireAt time.Time `json:"expire_at"`
}
```

**创建时机**: 用户同意后，在授权端点创建

**有效期**: 短时效，默认 `duration.Short`

**存储**: Redis

---

#### 阶段 7: OfflineGrant (Refresh Token)

**结构位置**: `pkg/lib/oauth/grant_offline.go:40-80`

```go
type OfflineGrant struct {
    AppID           string `json:"app_id"`
    ID              string `json:"id"`
    InitialClientID string `json:"client_id"`
    IDPSessionID string `json:"idp_session_id,omitempty"`
    IdentityID string `json:"identity_id,omitempty"`
    CreatedAt       time.Time `json:"created_at"`
    AuthenticatedAt time.Time `json:"authenticated_at"`
    Attrs      session.Attrs `json:"attrs"`
    AccessInfo access.Info   `json:"access_info"`
    DeviceInfo map[string]interface{} `json:"device_info,omitempty"`
    SSOEnabled bool `json:"sso_enabled,omitempty"`
    App2AppDeviceKeyJWKJSON string `json:"app2app_device_key_jwk_json"`
    DeviceSecretHash        string `json:"device_secret_hash"`
    DeviceSecretDPoPJKT     string `json:"device_secret_dpop_jkt"`
    RefreshTokens []OfflineGrantRefreshToken `json:"refresh_tokens,omitempty"`
    ParticipatedSAMLServiceProviderIDs []string `json:"participated_saml_service_provider_ids,omitempty"`
    // 兼容字段...
    ExpireAtForResolvedSession time.Time `json:"-"`
}

type OfflineGrantRefreshToken struct {
    InitialTokenHash string    `json:"token_hash"`
    ClientID         string    `json:"client_id"`
    CreatedAt        time.Time `json:"created_at"`
    Scopes           []string  `json:"scopes"`
    AuthorizationID  string    `json:"authz_id"`
    DPoPJKT          string    `json:"dpop_jkt"`
    AccessInfo *access.Info `json:"access_info"`
    ExpireAt *time.Time `json:"expire_at"`
    RotatedTokenHash *string    `json:"rotated_token_hash,omitzero"`
    RotatedAt        *time.Time `json:"rotated_at,omitzero"`
}
```

**创建时机**: `/oauth2/token` 端点处理 `authorization_code` grant 时

**关键代码**: `pkg/lib/oauth/handler/handler_token.go:1680-1957`

```go
func (h *TokenHandler) doIssueTokensForAuthorizationCode(...) (*HandleResult, error) {
    // ...
    opts := IssueOfflineGrantOptions{
        Scopes:             scopes,
        AuthorizationID:    authz.ID,
        AuthenticationInfo: info,
        IDPSessionID:       offlineGrantIDPSessionID,
        DeviceInfo:         deviceInfo,
        SSOEnabled:         code.AuthorizationRequest.SSOEnabled(),
        App2AppDeviceKey:   app2appDevicePublicKey,
        IssueDeviceSecret:  issueDeviceToken,
        DPoPJKT:            dpopJKT,
    }
    offlineGrant, newTokenHash, err := h.issueOfflineGrant(ctx, client, code.AuthenticationInfo.UserID, resp, opts, true)
    // ...
}
```

**存储**: PostgreSQL (通过 `OfflineGrantStore` 接口)

**与客户端关联**: 通过 Refresh Token 关联，格式: `{token}.{grant_id}`

---

## 3. Session 结构详细分析

### 3.1 IDPSession 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `ID` | string | UUID，唯一标识 |
| `AppID` | string | 所属应用 ID |
| `CreatedAt` | time.Time | 创建时间 |
| `AuthenticatedAt` | time.Time | 最后认证时间（用于重新认证判断） |
| `Attrs` | session.Attrs | 用户属性（UserID、Claims、AMR） |
| `AccessInfo` | access.Info | 访问信息（初始/最后访问时间、IP、UserAgent） |
| `TokenHash` | string | Session Token 的 SHA256 哈希值 |
| `ParticipatedSAMLServiceProviderIDs` | []string | 参与的 SAML 服务提供者 ID 列表 |

**Session.Attrs**: `pkg/lib/session/attrs.go:8-11`

```go
type Attrs struct {
    UserID string                          `json:"user_id"`
    Claims map[model.ClaimName]interface{} `json:"claims"`
}
```

**Access.Info**: `pkg/lib/session/access/event.go:9-12`

```go
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

---

### 3.2 OfflineGrant 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `ID` | string | 唯一标识（Grant ID） |
| `InitialClientID` | string | 初始 OAuth 客户端 ID |
| `IDPSessionID` | string | 关联的 IDP Session ID（SSO 场景） |
| `IdentityID` | string | 生物识别认证时的身份 ID |
| `Attrs` | session.Attrs | 用户属性 |
| `AccessInfo` | access.Info | 访问信息 |
| `DeviceInfo` | map[string]interface{} | 设备信息（iOS/Android 详情） |
| `SSOEnabled` | bool | 是否启用 SSO |
| `RefreshTokens` | []OfflineGrantRefreshToken | 刷新令牌列表（支持多设备/客户端） |
| `DeviceSecretHash` | string | 设备密钥哈希（Device SSO 使用） |
| `App2AppDeviceKeyJWKJSON` | string | App2App 设备密钥（JWK 格式） |

---

### 3.3 WebappSession 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `ID` | string | 唯一标识 |
| `Steps` | []SessionStep | 步骤历史栈 |
| `Authflow` | *Authflow | 关联的认证流程 |
| `OAuthSessionID` | string | 关联的 OAuth Session |
| `SAMLSessionID` | string | 关联的 SAML Session |
| `ClientID` | string | OAuth 客户端 ID |
| `RedirectURI` | string | 回调 URI |
| `SuppressIDPSessionCookie` | bool | 是否抑制 IDP Session Cookie |
| `UserIDHint` | string | 预期的用户 ID |

---

## 4. Session 与 Cookie 的关系

### 4.1 Cookie 定义汇总

**文件**: `pkg/lib/session/cookie.go:20-44`

```go
func NewSessionCookieDef(sessionCfg *config.SessionConfig) CookieDef {
    def := &httputil.CookieDef{
        NameSuffix:    "session",
        Path:          "/",
        SameSite:      http.SameSiteLaxMode,
        IsNonHostOnly: true,
    }
    strictDef := &httputil.CookieDef{
        NameSuffix:    "same_site_strict",
        Path:          "/",
        SameSite:      http.SameSiteStrictMode,
        IsNonHostOnly: true,
    }
    maxAge := int(sessionCfg.Lifetime)
    def.MaxAge = &maxAge
    strictDef.MaxAge = &maxAge
    return CookieDef{Def: def, SameSiteStrictDef: strictDef}
}
```

### 4.2 各 Session 对应的 Cookie

| Session 类型 | Cookie Name | SameSite | 说明 |
|--------------|-------------|----------|------|
| IDPSession | `{app_id}_session` | Lax | 主 session cookie |
| IDPSession | `{app_id}_same_site_strict` | Strict | 严格模式 session cookie |
| OAuthSession | `{app_id}_oauth_ui` | None | OAuth 授权临时 cookie |
| WebappSession | `{app_id}_webapp_session` | Lax | Web 应用 session |
| AppSessionToken | `{app_id}_app_session` | Lax | 应用会话令牌 |
| AppAccessToken | `{app_id}_app_access_token` | Lax | 应用访问令牌 |

### 4.3 Cookie 工作流程

**IDPSession Cookie 设置**:

```go
// pkg/lib/session/idpsession/provider.go:260-263
func (p *Provider) generateToken(s *IDPSession) string {
    token := encodeToken(s.ID, corerand.StringWithAlphabet(tokenLength, tokenAlphabet, p.Random))
    s.TokenHash = hashToken(token)
    return token
}

// 格式: {session_id}.{random_token}
// Cookie 值: ID + Token
```

**IDPSession 解析**: `pkg/lib/session/idpsession/resolver.go:33-50`

```go
func (re *Resolver) Resolve(ctx context.Context, rw http.ResponseWriter, r *http.Request) (session.ResolvedSession, error) {
    cookie, err := re.Cookies.GetCookie(r, re.CookieDef.Def)
    accessEvent := access.NewEvent(re.Clock.NowUTC(), re.RemoteIP, re.UserAgentString)
    s, err := re.Provider.AccessWithToken(ctx, cookie.Value, accessEvent)
    // ...
    return s, nil
}
```

---

## 5. Session 与 Device 的关系

### 5.1 DeviceInfo 结构

**文件**: `pkg/util/deviceinfo/deviceinfo.go`

DeviceInfo 是一个 `map[string]interface{}`，根据平台不同包含不同信息：

**iOS 设备**:
```json
{
  "ios": {
    "uname": {
      "machine": "iPhone14,2",
      "nodename": "John's iPhone"
    },
    "NSBundle": {
      "CFBundleDisplayName": "MyApp",
      "CFBundleIdentifier": "com.example.myapp"
    }
  }
}
```

**Android 设备**:
```json
{
  "android": {
    "Build": {
      "MANUFACTURER": "Samsung",
      "MODEL": "SM-G991B"
    },
    "ApplicationInfoLabel": "MyApp",
    "PackageInfo": {
      "packageName": "com.example.myapp"
    },
    "Settings": {
      "Global": {
        "DEVICE_NAME": "John's Phone"
      }
    }
  }
}
```

### 5.2 DeviceInfo 使用场景

| Session 类型 | DeviceInfo 字段 | 用途 |
|-------------|-----------------|------|
| OfflineGrant | `DeviceInfo` | 显示设备名称、管理会话 |
| IDPSession | 无 | 使用 UserAgent |

**设备信息提取**:

```go
// pkg/util/deviceinfo/deviceinfo.go:225-235
func DeviceModel(deviceInfo map[string]interface{}) string {
    android, ok := deviceInfo["android"].(map[string]interface{})
    if ok {
        return deviceModelAndroid(android)
    }
    ios, ok := deviceInfo["ios"].(map[string]interface{})
    if ok {
        return deviceModelIOS(ios)
    }
    return ""
}

func DeviceName(deviceInfo map[string]interface{}) string { ... }
func ApplicationName(deviceInfo map[string]interface{}) string { ... }
```

---

## 6. Session 生命周期

### 6.1 IDPSession 生命周期

**创建**: 
- 触发点: 用户完成登录/注册流程
- 代码: `pkg/lib/session/idpsession/provider.go:55-72`
- 创建时设置: ID、CreatedAt、AuthenticatedAt、Attrs、TokenHash、Initial AccessEvent

**更新**:
- 触发点: 用户访问系统（通过 `AccessWithToken`）
- 代码: `pkg/lib/session/idpsession/provider.go:158-205`
- 更新内容: `LastAccess` 时间戳、IP、UserAgent
- 重新计算过期时间（考虑 idle timeout）

**重新认证**:
- 触发点: 用户完成 MFA 或密码验证
- 代码: `pkg/lib/session/idpsession/provider.go:74-102`
- 更新内容: `AuthenticatedAt`、AMR

**过期检查**:
- 代码: `pkg/lib/session/idpsession/expiry.go:7-16`

```go
func setSessionExpireAtForResolvedSession(session *IDPSession, cfg *config.SessionConfig) {
    session.ExpireAtForResolvedSession = session.CreatedAt.Add(cfg.Lifetime.Duration())
    if *cfg.IdleTimeoutEnabled {
        sessionIdleExpiry := session.AccessInfo.LastAccess.Timestamp.Add(cfg.IdleTimeout.Duration())
        if sessionIdleExpiry.Before(session.ExpireAtForResolvedSession) {
            session.ExpireAtForResolvedSession = sessionIdleExpiry
        }
    }
}
```

**删除**:
- 触发点: 用户登出、管理员吊销、会话过期
- 代码: `pkg/lib/session/idpsession/manager.go:42-48`

### 6.2 OfflineGrant 生命周期

**创建**:
- 触发点: Token 端点处理 `authorization_code` grant
- 代码: `pkg/lib/oauth/handler/service_token.go` (IssueOfflineGrant)

**访问更新**:
- 触发点: 使用 refresh_token 获取新 access_token
- 代码: `pkg/lib/oauth/grant_offline_service.go:48-83`

```go
func (s *OfflineGrantService) AccessOfflineGrant(ctx context.Context, grantID string, initialRefreshTokenHash string, accessEvent *access.Event, expireAt time.Time) (*OfflineGrant, error) {
    grant, err := s.OfflineGrants.UpdateOfflineGrantWithMutator(ctx, grantID, expireAt, func(grant *OfflineGrant) *OfflineGrant {
        grant.AccessInfo.LastAccess = *accessEvent
        // 更新具体 refresh token 的访问信息
        if initialRefreshTokenHash != "" {
            for i := range grant.RefreshTokens {
                token := grant.RefreshTokens[i]
                if token.MatchInitialHash(initialRefreshTokenHash) {
                    if token.AccessInfo == nil {
                        tokenAccessInfo := grant.AccessInfo
                        token.AccessInfo = &tokenAccessInfo
                    }
                    token.AccessInfo.LastAccess = *accessEvent
                }
                grant.RefreshTokens[i] = token
            }
        }
        return grant
    })
    // ...
}
```

**过期计算**:
- 代码: `pkg/lib/oauth/grant_offline_service.go:121-163`

```go
func (s *OfflineGrantService) ComputeOfflineGrantExpiry(session *OfflineGrant) (expiry time.Time, err error) {
    clientConfig := s.ClientResolver.ResolveClient(session.InitialClientID)
    expiry = s.computeRefreshTokenExpiryWithClient(expirableRefreshToken{
        CreatedAt:    session.CreatedAt,
        LastAccessAt: session.AccessInfo.LastAccess.Timestamp,
    }, clientConfig)
    return
}

func (s *OfflineGrantService) computeRefreshTokenExpiryWithClient(token expirableRefreshToken, cfg *config.OAuthClientConfig) (expiry time.Time) {
    expiry = token.CreatedAt.Add(cfg.RefreshTokenLifetime.Duration())
    if *cfg.RefreshTokenIdleTimeoutEnabled {
        idleExpiry := token.LastAccessAt.Add(cfg.RefreshTokenIdleTimeout.Duration())
        if idleExpiry.Before(expiry) {
            expiry = idleExpiry
        }
    }
    return
}
```

**Refresh Token 轮换**:
- 代码: `pkg/lib/oauth/grant_offline_service.go:245-284`

```go
func (s *OfflineGrantService) RotateRefreshToken(...) (*RotateRefreshTokenResult, *OfflineGrant, error) {
    newToken := GenerateToken()
    newTokenHash := HashToken(newToken)
    newGrant, err := s.OfflineGrants.RotateOfflineGrantRefreshToken(ctx, rotateOpts, expiry)
    // ...
}
```

### 6.3 临时 Session 生命周期

| Session 类型 | TTL | 说明 |
|-------------|-----|------|
| OAuthSession | `UserInteraction + Consent` | 授权流程期间有效 |
| WebappSession | `GraphLifetime` | 交互图生命周期 |
| AuthflowSession | `UserInteraction` | 用户交互期间有效 |
| CodeGrant | `Short` | 短时效（默认几分钟） |
| SAMLSession | `UserInteraction` | SAML 流程期间有效 |

---

## 7. Session 存储层

### 7.1 Redis 存储

**IDPSession Store**: `pkg/lib/session/idpsession/store_redis.go`

```go
type StoreRedis struct {
    Redis *appredis.Handle
    AppID config.AppID
    Clock clock.Clock
}

// Key 格式:
// app:{app_id}:session:{session_id}           - Session 数据
// app:{app_id}:session-list:{user_id}         - 用户 session 列表
// app:{app_id}:session-mutex:{session_id}     - Session 操作锁
```

**OAuthSession Store**: `pkg/lib/oauth/oauthsession/store_redis.go`

```go
// Key 格式: app:{app_id}:oauth-session-entry:{entry_id}
// TTL: duration.UserInteraction + duration.Consent
```

**WebappSession Store**: `pkg/auth/webapp/session_store.go`

```go
// Key 格式: app:{app_id}:webapp-session:{session_id}
// TTL: interaction.GraphLifetime
```

**Authflow Store**: `pkg/lib/authenticationflow/store.go`

```go
// Key 格式:
// app:{app_id}:authenticationflow_flow:{flow_id}
// app:{app_id}:authenticationflow_state:{state_token}
// app:{app_id}:authenticationflow_session:{flow_id}
```

### 7.2 PostgreSQL 存储

**OfflineGrant Store**: `pkg/lib/oauth/store_grant.go`

通过 `OfflineGrantStore` 接口存储在 PostgreSQL 中，支持复杂查询（按用户、客户端查询）。

---

## 8. 关键代码类

### 8.1 Session 接口定义

**文件**: `pkg/lib/session/session.go`

```go
type SessionBase interface {
    SessionID() string
    SessionType() Type
    GetAuthenticationInfo() authenticationinfo.T
    SSOGroupIDPSessionID() string
}

type ResolvedSession interface {
    SessionBase
    Session()
    GetCreatedAt() time.Time
    GetExpireAt() time.Time
    GetAccessInfo() *access.Info
    CreateNewAuthenticationInfoByThisSession() authenticationinfo.T
}

type ListableSession interface {
    SessionBase
    ListableSession()
    GetCreatedAt() time.Time
    GetAccessInfo() *access.Info
    GetDeviceInfo() (map[string]interface{}, bool)
    ToAPIModel() *model.Session
    IsSameSSOGroup(s SessionBase) bool
    EqualSession(s SessionBase) bool
    GetParticipatedSAMLServiceProviderIDsSet() setutil.Set[string]
}
```

### 8.2 核心 Provider/Service 类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `idpsession.Provider` | `pkg/lib/session/idpsession/provider.go` | IDP Session 创建、获取、访问更新、重新认证 |
| `idpsession.Resolver` | `pkg/lib/session/idpsession/resolver.go` | 从 Cookie 解析 Session |
| `idpsession.Manager` | `pkg/lib/session/idpsession/manager.go` | Session 列表、吊销、清理 |
| `OfflineGrantService` | `pkg/lib/oauth/grant_offline_service.go` | OfflineGrant 访问、过期计算、Token 轮换 |
| `TokenService` | `pkg/lib/oauth/handler/service_token.go` | Token 发放、OfflineGrant 创建 |

### 8.3 Handler 类

| 类名 | 文件路径 | 职责 |
|------|----------|------|
| `AuthorizationHandler` | `pkg/lib/oauth/handler/handler_authz.go` | OAuth 授权端点，创建 OAuthSession、CodeGrant |
| `TokenHandler` | `pkg/lib/oauth/handler/handler_token.go` | OAuth Token 端点，创建 OfflineGrant |
| `SessionMiddleware` | `pkg/auth/webapp/session_middleware.go` | Webapp Session 中间件 |

---

## 9. 关键流程

### 9.1 登录创建 IDPSession 流程

```
1. 用户完成认证 (Authentication Flow)
         ↓
2. NodeDoCreateSession / IntentEnsureSession
         ↓
3. idpsession.Provider.MakeSession(attrs)
   - 生成 UUID (Session ID)
   - 生成随机 Token
   - 计算 TokenHash
   - 设置 CreatedAt = AuthenticatedAt = Now
   - 设置 Initial AccessEvent
         ↓
4. idpsession.Provider.Create(ctx, session)
   - setSessionExpireAtForResolvedSession (计算过期时间)
   - Store.Create (写入 Redis)
   - AccessEvents.InitStream (初始化访问事件流)
         ↓
5. 设置 Session Cookie (id + token)
```

### 9.2 授权码换取 Token 流程

```
1. /oauth2/token (grant_type=authorization_code)
         ↓
2. TokenHandler.handleAuthorizationCode
         ↓
3. 验证 CodeGrant
   - GetCodeGrant (从 Redis 获取)
   - 验证 PKCE、RedirectURI、过期时间
         ↓
4. 获取或创建 OfflineGrant
   - 如果 IDPSession 存在且 SSOEnabled: 关联 IDPSessionID
   - issueOfflineGrant (创建新的 OfflineGrant)
         ↓
5. OfflineGrant 创建细节
   - 生成 Refresh Token
   - 设置 DeviceInfo (来自请求)
   - 设置 SSOEnabled (来自 CodeGrant)
   - 设置 DPoPJKT (如果启用 DPoP)
   - 可选: 生成 DeviceSecret (如果 scope 包含 device_sso)
         ↓
6. 发放 Token
   - access_token (JWT)
   - refresh_token (encoded: token.grant_id)
   - id_token (JWT)
   - device_secret (可选)
```

### 9.3 Refresh Token 轮换流程

```
1. /oauth2/token (grant_type=refresh_token)
         ↓
2. TokenHandler.handleRefreshToken
         ↓
3. 解析 Refresh Token
   - ParseRefreshToken: 分离 token 和 grant_id
   - GetOfflineGrant (从 PostgreSQL 获取)
   - ToSession: 匹配具体的 Refresh Token
         ↓
4. 验证
   - 检查过期 (ComputeOfflineGrantExpiry)
   - 如果 SSOEnabled: 验证关联的 IDPSession 是否有效
   - 匹配 DPoPJKT (如果绑定)
         ↓
5. 访问更新
   - OfflineGrantService.AccessOfflineGrant
   - 更新 LastAccess
   - 更新具体 Refresh Token 的 AccessInfo
         ↓
6. 设备密钥轮换 (可选)
   - 如果 scope 包含 device_sso 且提供了 device_secret
   - rotateDeviceSecret: 生成新的 DeviceSecret
         ↓
7. Refresh Token 轮换 (如果启用)
   - OfflineGrantService.RotateRefreshToken
   - 生成新 Token
   - 设置 RotatedTokenHash、RotatedAt
         ↓
8. 发放新 Token
```

---

## 10. SSO 与 Session 关系

### 10.1 SSO Group 概念

**SSOGroupIDPSessionID**: 用于标识同一 SSO 组下的 session

```go
// pkg/lib/session/idpsession/session.go:106-108
func (s *IDPSession) SSOGroupIDPSessionID() string {
    return s.SessionID()  // IDPSession 返回自己的 ID
}

// pkg/lib/oauth/grant_offline.go:218-223
func (g *OfflineGrant) SSOGroupIDPSessionID() string {
    if g.SSOEnabled {
        return g.IDPSessionID  // SSO 启用时返回关联的 IDPSession ID
    }
    return ""
}
```

### 10.2 IsSameSSOGroup 判断

```go
// IDPSession: pkg/lib/session/idpsession/session.go:113-121
func (s *IDPSession) IsSameSSOGroup(ss session.SessionBase) bool {
    if s.EqualSession(ss) {
        return true
    }
    if s.SSOGroupIDPSessionID() == "" {
        return false
    }
    return s.SSOGroupIDPSessionID() == ss.SSOGroupIDPSessionID()
}

// OfflineGrant: pkg/lib/oauth/grant_offline.go:229-242
func (g *OfflineGrant) IsSameSSOGroup(ss session.SessionBase) bool {
    if g.EqualSession(ss) {
        return true
    }
    if g.SSOEnabled {
        if g.SSOGroupIDPSessionID() == "" {
            return false
        }
        return g.SSOGroupIDPSessionID() == ss.SSOGroupIDPSessionID()
    }
    return false
}
```

---

## 11. 关键 Specs 文档

| 文档 | 路径 | 内容 |
|------|------|------|
| Session 规范 | `docs/specs/sessions.md` | Session 类型、生命周期、并发控制 |
| OAuth 规范 | `docs/specs/oidc.md` | OIDC、OAuth2 流程、Token 格式 |
| Glossary | `docs/specs/glossary.md` | 术语定义 |
| User Model | `docs/specs/user-model.md` | 用户模型、Identity 与 Session 关系 |

---

## 12. Session 关系图

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                              Session 关系图                                       │
├────────────────────────────────────────────────────────────────────────────────┤
│                                                                                │
│  ┌──────────────┐     创建/引用      ┌──────────────┐                          │
│  │ OAuthSession │───────────────────▶│ WebappSession│                          │
│  │  (临时)      │    oauth_session_id│  (交互)      │                          │
│  └──────────────┘                   └──────┬───────┘                          │
│         │                                 │                                    │
│         │                                 ▼                                    │
│         │                          ┌──────────────┐                          │
│         │                          │AuthflowSession│                          │
│         │                          │  (认证流程)   │                          │
│         │                          └──────┬───────┘                          │
│         │                                 │                                    │
│         │                                 ▼                                    │
│         │                          ┌──────────────┐     关联      ┌──────────┐  │
│         │                          │   IDPSession │◀────────────│  SSO组   │  │
│         │                          │  (登录会话)  │   SSOEnabled │         │  │
│         │                          └──────┬───────┘              └──────────┘  │
│         │                                 │                                    │
│         │                                 │ 引用 (通过ID)                      │
│         │                                 ▼                                    │
│         │                          ┌──────────────┐                          │
│         └────────────────────────▶│ OfflineGrant │                          │
│              授权码换取            │ (RefreshToken)│                          │
│                                  └──────────────┘                          │
│                                                                                │
│  其他 Session:                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                    │
│  │ SAMLSession  │    │WorkflowSession│   │ CodeGrant    │                    │
│  │  (SAML流程)   │    │  (旧版流程)   │    │  (临时授权码)│                    │
│  └──────────────┘    └──────────────┘    └──────────────┘                    │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## 13. 总结

Authgear 的 Session 系统设计特点：

1. **分层设计**: 不同类型的 session 负责不同阶段的流程（OAuthSession → WebappSession → AuthflowSession → IDPSession/OfflineGrant）

2. **多存储后端**: Redis 用于临时 session 和 IDPSession，PostgreSQL 用于持久化的 OfflineGrant

3. **SSO 支持**: 通过 SSOGroupIDPSessionID 将 IDPSession 和 OfflineGrant 关联到同一 SSO 组

4. **安全机制**: Token 哈希存储、Cookie SameSite 策略、DPoP 绑定、Refresh Token 轮换

5. **设备感知**: OfflineGrant 存储 DeviceInfo，支持设备级别的会话管理

6. **灵活过期**: 支持固定生命周期和空闲超时两种过期策略
