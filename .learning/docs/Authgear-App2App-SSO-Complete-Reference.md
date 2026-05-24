# Authgear App2App SSO 完整实现参考

## 概述

App2App SSO 是一种允许**同一设备上已登录的 App（App B）为另一个 App（App A）签发授权码**的认证机制。不同于 Device SSO 的共享存储方案，App2App 通过**设备密钥对（JWK）+ JWT Challenge 机制**实现跨 App 认证转移，全程无需共享存储。

**核心理念**：基于 [OpenID 的 App-to-App Authorisation 博客文章](https://openid.net/guest-blog-implementing-app-to-app-authorisation-in-oauth2-openid-connect/)。App A（待认证）通过平台级 Universal Link / App Link 向 App B（已认证）发起认证请求；App B 征得用户同意后通过 Token Endpoint 获取授权码，交还给 App A；App A 用授权码换取自己的 Token。

```
┌──────────────────────────────────────────────────────────────────┐
│                        同一设备                                    │
│  ┌──────────┐    Universal Link     ┌──────────┐                 │
│  │  App A   │ ◄──────────────────► │  App B   │                 │
│  │ (未登录)  │   / App Link          │ (已登录)  │                 │
│  └────┬─────┘                       └────┬─────┘                 │
│       │                                  │                        │
│       │  ③ 兑换 code → refresh_token      │  ② 请求授权码           │
│       │                                  │  grant_type=app2app    │
│       ▼                                  ▼                        │
│  ┌─────────────────────────────────────────────┐                 │
│  │              Authgear Server                  │                 │
│  └─────────────────────────────────────────────┘                 │
└──────────────────────────────────────────────────────────────────┘
```

---

## 设计原理与实现原则

### 核心设计问题：如何在不共享存储的前提下实现跨 App 认证？

Device SSO 依赖共享存储（iOS App Group / Android AccountManager）来传递 `device_secret` + `id_token`，但这种方式有两个局限：

1. **不同发行商的 App 无法共享存储**（iOS App Group 需要同一 Team ID，Android AccountManager 需要同一签名）
2. **共享存储意味着共享 session**（登出互相影响，违反了最小权限原则）

App2App 的设计目标是：**同一设备上的两个 App 不共享任何存储，依然可以安全地转移认证状态**。

### 设计思路：让已认证的 App 充当"认证代理"

核心思想来自 [OpenID 的 App-to-App Authorisation 博客文章](https://openid.net/guest-blog-implementing-app-to-app-authorisation-in-oauth2-openid-connect/)：

- **App B（已认证）**：持有 refresh_token，可以向服务端证明"我是我" - 用 refresh_token + 设备私钥签名 JWT
- **服务端**：验证 App B 的身份 + 设备绑定后，为 App A **签发一个新的 authorization_code**
- **App A（待认证）**：拿到 authorization_code 后，以自己的 client_id 兑换独立的 refresh_token

**关键优势**：
- App A 和 App B 拥有**完全独立的 OfflineGrant**（登出互不影响）
- 不需要共享存储（通过 Platform 的 App Link 通信）
- 不需要 App B 向 App A 暴露其 refresh_token（只传递 code）

### 三层安全模型

App2App 的安全性建立在三层递进验证之上：

```
层 1: Platform 级安全
      Platform (iOS/Android) 验证 App Link 的签名
      → 确保只有注册的 App 才能接收授权请求
      → 防御：伪造 App 冒充接收方

层 2: Challenge + JWT 时效
      Challenge token 一次性消费 + JWT 的 exp/iat/nbf
      → 确保每个 JWT 只能使用一次，且有时效限制
      → 防御：JWT 重放攻击

层 3: 设备密钥对
      私钥在设备安全区 → 签名 JWT → 服务端用存储的公钥验证
      → 确保请求来自持有原始 refresh_token 的设备
      → 防御：从其他设备窃取 refresh_token 后重放
```

### ParseTokenUnverified + ParseToken 两阶段验证的设计理由

```go
// pkg/lib/app2app/provider.go

// 阶段 1: ParseTokenUnverified — 不验证签名
// 目的：提取 challenge（防重放）和公钥（准备验证签名）
// 此时 JWT 签名尚未验证，但这是安全的，因为：
//   1. challenge 会被立即消费（一次性使用）
//   2. 随后的签名验证确保 JWT 未被篡改

// 阶段 2: ParseToken — 用存储的公钥验证签名
// 目的：证明 JWT 由持有对应私钥的人签发
// 这个时候 challenge 已经消费了，签名验证确保 JWT 的完整性
```

**为什么不能合并成一个步骤？**

如果先验证签名再用 challenge，需要：
1. 从 JWT header 提取公钥
2. 从存储中查找该公钥 → 此时还不知道这个 JWT 关联哪个 OfflineGrant
3. 验签 → 提取 challenge → 消费 challenge

这种方式的问题在于**无法高效查找公钥**（header 中的 `jwk` 是裸公钥，没有 session ID）。两阶段设计允许先消费 challenge（防重放），再从 `refresh_token` 参数中获取 `offlineGrantID`，然后用存储的公钥验签。

### 为什么设备密钥不可更改？(`"app2app device key cannot be changed"`)

```
绑定后不可更改的原因：

情况 A: 合法流程
  设备生成密钥对 + 签名 JWT → 服务端绑定公钥
  后续请求用同一私钥签名 → 服务端用已绑定的公钥验证 ✓

情况 B: 攻击者尝试替换密钥
  如果允许更改 → 攻击者可以用自生成密钥的 JWT 替换合法密钥
  → 攻击者现在可以伪造 App2App JWT
  → 可以在其他 App 中以受害者的身份登录

防御措施：密钥绑定后不可更改（immutable binding）
  首次登录时绑定 → 后续请求必须用同一密钥对
  即使 insecure_binding=true，也只能在尚无密钥时首次绑定
```

**与 Biometric Key 的差异**：
- Device Key（App2App）：不需要用户交互，但绑定后不可更改
- Biometric Key：需要生物识别验证用户身份，可能用于后续的 `authenticate_app2app` 功能

### insecure binding 的设计权衡

`x_app2app_insecure_device_key_binding_enabled` 是一个明确的**安全妥协**：

| 设置 | 安全性 | 兼容性 |
|------|--------|--------|
| `true` | 低（无密钥的 session 可被任意设备绑定） | 高（存量用户无需重新登录） |
| `false` | 高（只有登录时就带密钥的 session 能参与 App2App） | 低（需要用户重新登录并绑定密钥） |

**推荐策略**：
1. 上线时设为 `true`（允许存量用户迁移）
2. 迁移期结束后设为 `false`（锁定安全策略）
3. 始终将设备密钥绑定集成到首次登录流程中（通过 `x_app2app_device_key_jwt` 参数）

### 为什么 App2App 返回的是 authorization_code 而不是 token？

```
为什么不在 handleApp2AppRequest 中直接返回 access_token + refresh_token？

设计选择：返回 authorization_code，让 App A 自己兑换

原因 1: PKCE 强制
  authorization_code 兑换时必须提供 code_verifier
  → App A 是唯一持有 code_verifier 的一方
  → 即使 code 在 App Link 传递中被截获，无法兑换

原因 2: Client 信息隔离
  authorization_code 与 client_id=appa 绑定
  → App B 不知道 App A 的 redirect_uri 验证逻辑
  → 标准 OAuth 流程确保 client 级别的安全策略被执行

原因 3: 不破坏 OAuth 语义
  /token endpoint 的 grant_type=app2app-request 返回 code
  → 与实际资源访问的 token 分离
  → App B 不获取代表 App A 的 access_token
```

### 为什么 App2App 不依赖 DPoP？

```go
// handler_token.go:1556-1570
// CreateCodeGrant with DPoPJKT: "" (empty)
// 注释："App2app does not support DPoP because the app using the code
//         may not share storage with the app issuing it"
```

DPoP 需要将 token 绑定到特定公钥，意味着接收 code 的 App 必须持有对应的私钥。但 App2App 中：
- App B 用私钥签名 JWT（证明了设备绑定）
- App A 不需要知道 App B 的私钥
- App A 用 PKCE 保护自己的授权码兑换（提供了足够的安全性）

**安全覆盖**：App B 用设备私钥绑定 JWT + App A 用 PKCE 绑定 code = 两端都有保护，不需要 DPoP 在中间传递。

### Session 独立性：与 Device SSO 的根本区别

```
Device SSO:
  App 1 登录 → OfflineGrant { DeviceSecretHash: "xxx" }
  App 2 登录 → 同一个 OfflineGrant { DeviceSecretHash: "xxx轮换后" }
  → 登出 App 1 → 删除 device_secret → App 2 也无法登录

App2App SSO:
  App B 登录 → OfflineGrant_B { App2AppDeviceKeyJWKJSON: "..." }
  App A 登录 → OfflineGrant_A { 独立的, 没有 App2AppDeviceKey }
  → 登出 App A → OfflineGrant_A 被删除 → App B 不受影响
```

**App2App 的 session 隔离是故意的设计选择**：
- 如果发行商希望登出联动 → 使用 Device SSO
- 如果发行商希望 session 独立 → 使用 App2App SSO
- 两种机制可以共存，适用于不同场景

### 数据流所有权模型

```
阶段 1 (设备密钥绑定):
  App B SDK 生成密钥对
  → 私钥存储在 App B 的设备安全区（App B 独有）
  → 公钥通过 x_app2app_device_key_jwt 传给服务端
  → 服务端写入 App B 的 OfflineGrant

阶段 2 (App2App 认证):
  App B 用私钥签名 JWT → 证明"这个 refresh_token 没有离开设备"
  → 服务端为 App A 签发 code → App A 用自己的 client_id 兑换
  → App A 获得独立 token → App A 不碰 App B 的任何凭证
```

**每个 App 只拥有自己的凭证，不暴露给其他 App，即使在同一设备上。**

---

## 一、关键概念

### 1.1 设备密钥对（Device Key）

设备密钥对是 App2App SSO 的核心安全基础：

- **生成时机**：首次登录时（或 insecure migration 时），由 SDK 在设备安全存储区（iOS Keychain / Android Keystore）生成
- **密钥类型**：非对称密钥对（JWK 格式），私钥存储在设备安全区
- **作用**：证明持有 refresh_token 的 App 确实运行在绑定该 token 的设备上
- **生命周期**：每个 OfflineGrant 绑定一个设备公钥。如果 OfflineGrant 被重新创建（如重新登录），设备密钥也会重新生成
- **无需用户交互即可使用**（与 biometric key 不同）

```go
// pkg/lib/oauth/grant_offline.go:60
type OfflineGrant struct {
    // ...
    App2AppDeviceKeyJWKJSON string `json:"app2app_device_key_jwk_json"`
    // ...
}
```

### 1.2 Challenge 机制

Challenge 是防重放的核心机制：

```go
// pkg/lib/authn/challenge/challenge.go:16
const PurposeApp2AppRequest Purpose = "app2app_request"

// Challenge 有过期时间（duration.Short），确保 JWT 的时效性
```

流程：
1. App 调用 `POST /oauth2/challenge?purpose=app2app_request` 获取 challenge token
2. 将 challenge token 嵌入 JWT payload 的 `challenge` 字段
3. 服务端在验证 JWT 时 `Consume` challenge token（一次性消费）
4. 如果 challenge 已被消费或过期 → 请求被拒绝

### 1.3 App2App JWT 格式

**Token Type**: `vnd.authgear.app2app-request`

```go
// pkg/lib/app2app/request.go:8
const RequestTokenType = "vnd.authgear.app2app-request"
```

**JWT 结构**：

```
Header:
{
  "alg": "ES256",                    // 签名算法
  "typ": "vnd.authgear.app2app-request",  // JWT 类型
  "jwk": {                           // 设备公钥（嵌入 Header）
    "kty": "EC",
    "crv": "P-256",
    "x": "...",
    "y": "...",
    "kid": "device-key-20240501"
  }
}

Payload:
{
  "challenge": "<从 /oauth2/challenge 获得的 token>",
  "iat": 1724989400,
  "exp": 1724989460,
  "nbf": 1724989400
}
```

```go
// pkg/lib/app2app/request.go:10-13
type Request struct {
    Key       jwk.Key `json:"-"`       // 从 JWT header 提取的公钥（不序列化到 JSON）
    Challenge string  `json:"challenge"` // 从 /oauth2/challenge 获取的 token
}
```

### 1.4 Grant Type

```go
// pkg/lib/oauth/grant_type.go:16
const App2AppRequestGrantType = "urn:authgear:params:oauth:grant-type:app2app-request"
```

`App2AppRequestGrantType` 在 `whitelistedGrantTypes`（`grant_type.go:32`）中，对所有非 M2M 客户端类型自动允许。

```go
// pkg/lib/oauth/grant_type.go:24-35
var whitelistedGrantTypes = []string{
    // ...
    App2AppRequestGrantType,  // 自动允许，无需客户端在 grant_types 中显式列出
    // ...
}
```

### 1.5 关键配置

**Client 级别**（`pkg/lib/config/oauth.go:273-274`）：

```go
type OAuthClientConfig struct {
    // ...
    App2appEnabled                         bool `json:"x_app2app_enabled,omitempty"`
    App2appInsecureDeviceKeyBindingEnabled bool `json:"x_app2app_insecure_device_key_binding_enabled,omitempty"`
    // ...
}
```

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `x_app2app_enabled` | `false` | 该 client 是否可以作为"已认证方"处理其他 App 的认证请求 |
| `x_app2app_insecure_device_key_binding_enabled` | `false` | 是否允许尚无设备密钥的 OfflineGrant 在 App2App 流程中绑定新密钥。用于存量用户的迁移，但有安全隐患 |

**Feature 级别**（`pkg/lib/config/feature_oauth.go:51`）：

```go
type OAuthClientFeatureConfig struct {
    // ...
    App2AppEnabled *bool `json:"app2app_enabled,omitempty"`
    // ...
}

// SetDefaults (feature_oauth.go:67-69)：
// 如果 nil，默认设为 false
func (c *OAuthClientFeatureConfig) SetDefaults() {
    if c.App2AppEnabled == nil {
        c.App2AppEnabled = newBool(false)  // 全局默认关闭
    }
}
```

---

## 二、完整流程详解

### 2.1 流程图（完整交互）

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    阶段 1: 首次登录 — 设备密钥绑定                               │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  App B (首次登录)                                                             │
│     │                                                                        │
│     │  ① SDK 在设备安全存储区生成设备密钥对 (JWK)                                │
│     │     私钥 → iOS Keychain / Android Keystore                              │
│     │     公钥 → 后续通过 JWT 传给服务端                                        │
│     │                                                                        │
│     │  ② GET /oauth2/challenge?purpose=app2app_request                        │
│     │     Response: { "token": "<challenge_token>" }                          │
│     │                                                                        │
│     │  ③ 构造 JWT:                                                            │
│     │     Header: {"alg":"ES256","typ":"vnd.authgear.app2app-request",       │
│     │              "jwk":<公钥>}                                               │
│     │     Payload: {"challenge":"<challenge_token>"}                          │
│     │     用设备私钥签名                                                        │
│     │                                                                        │
│     │  ④ 正常 OAuth authorize 流程（WebView 登录）                              │
│     │     GET /oauth2/authorize?client_id=appb&scope=openid+offline_access   │
│     │                          &response_type=code&code_challenge=...         │
│     │                                                                        │
│     │  ⑤ POST /oauth2/token (authorization_code 兑换)                         │
│     │     grant_type=authorization_code                                       │
│     │     &client_id=appb                                                     │
│     │     &code=<authorization_code>                                          │
│     │     &code_verifier=<PKCE_verifier>                                      │
│     │     &redirect_uri=com.example.appb://callback                           │
│     │     &x_app2app_device_key_jwt=<步骤③签名的JWT>  ← 绑定设备密钥            │
│     │                                                                        │
│     │  ⑥ Response:                                                            │
│     │     {                                                                   │
│     │       "access_token":  "...",                                           │
│     │       "refresh_token": "<offlineGrantID.token>",                        │
│     │       "token_type":    "Bearer",                                        │
│     │       "expires_in":    3600,                                            │
│     │       "id_token":      "..."                                            │
│     │     }                                                                   │
│     │                                                                        │
│     │  ★ 服务端将 App2AppDeviceKeyJWKJSON 写入 OfflineGrant                     │
│     │                                                                        │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│                    阶段 2: App2App 认证 — App B 为 App A 签发授权码              │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  App A (待认证, client_id=appa)          App B (已认证, client_id=appb)        │
│     │                                        │                                │
│     │  ① SDK: startApp2AppAuthentication()   │                                │
│     │  构造 authorizeUri:                     │                                │
│     │  https://appb.example.com/authorize    │                                │
│     │    ?client_id=appa                      │                                │
│     │    &redirect_uri=https://appa.example.com/callback                     │
│     │    &code_challenge=<PKCE_challenge>     │                                │
│     │                                        │                                │
│     │  ② 通过 Platform 打开 Universal Link ──►│                                │
│     │                                        │  ③ parseApp2AppAuthentication  │
│     │                                        │     Request(url)               │
│     │                                        │                                │
│     │                                        │  ④ 检查当前认证状态              │
│     │                                        │     若未认证 → 先 authenticate()│
│     │                                        │                                │
│     │                                        │  ⑤ 向用户展示同意界面            │
│     │                                        │     "App A 请求使用您的账号登录"  │
│     │                                        │                                │
│     │                                        │  ⑥ 用户同意后:                  │
│     │                                        │  GET /oauth2/challenge          │
│     │                                        │    ?purpose=app2app_request     │
│     │                                        │                                │
│     │                                        │  ⑦ 构造 JWT:                    │
│     │                                        │  Header: {"alg":"ES256",       │
│     │                                        │    "typ":"vnd.authgear.        │
│     │                                        │     app2app-request",          │
│     │                                        │    "jwk":<设备公钥>}             │
│     │                                        │  Payload: {"challenge":        │
│     │                                        │    "<challenge_token>"}         │
│     │                                        │  用设备私钥签名                   │
│     │                                        │                                │
│     │                                        │  ⑧ POST /oauth2/token          │
│     │                                        │  grant_type=urn:authgear:      │
│     │                                        │    params:oauth:grant-type:    │
│     │                                        │    app2app-request             │
│     │                                        │  &client_id=appa               │
│     │                                        │  &refresh_token=<AppB的token>  │
│     │                                        │  &jwt=<步骤⑦签名的JWT>          │
│     │                                        │  &redirect_uri=https://        │
│     │                                        │    appa.example.com/callback    │
│     │                                        │                                │
│     │                                        │  ⑨ Response:                    │
│     │                                        │  {                             │
│     │                                        │    "code": "<authorization_code>"│
│     │                                        │  }                             │
│     │                                        │                                │
│     │                                        │  ⑩ 通过 Platform 打开 URI       │
│     │  ◄─ https://appa.example.com/callback  │     ?code=<authorization_code> │
│     │      ?code=<authorization_code>        │                                │
│     │                                        │                                │
│     │  ⑪ handleApp2AppAuthenticationResult() │                                │
│     │  提取 code                              │                                │
│     │                                        │                                │
│     │  ⑫ POST /oauth2/token                 │                                │
│     │  grant_type=authorization_code          │                                │
│     │  &client_id=appa                        │                                │
│     │  &code=<authorization_code>            │                                │
│     │  &code_verifier=<PKCE_verifier>         │                                │
│     │  &redirect_uri=https://                │                                │
│     │    appa.example.com/callback            │                                │
│     │                                        │                                │
│     │  ⑬ Response: 独立的 refresh_token       │                                │
│     │  {                                      │                                │
│     │    "access_token":  "...",              │                                │
│     │    "refresh_token": "...",              │                                │
│     │    "id_token":       "..."              │                                │
│     │  }                                      │                                │
│     │                                        │                                │
│     │  ★ App A 获得独立的 session              │                                │
│     │  ★ 登出互不影响                          │                                │
│     │                                        │                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 三、完整 HTTP 请求与响应

### 3.1 阶段 1：首次登录 + 设备密钥绑定（App B）

#### Step 1: 获取 Challenge

```http
GET /oauth2/challenge?purpose=app2app_request HTTP/1.1
Host: accounts.example.com
```

Response:
```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "token": "challenge_abc123def456",
  "expire_at": "2024-08-30T10:42:00Z"
}
```

#### Step 2: SDK 构造 x_app2app_device_key_jwt

SDK 在设备安全存储区生成 EC P-256 密钥对，然后用私钥签名 JWT：

```
JWT Header:
{
  "alg": "ES256",
  "typ": "vnd.authgear.app2app-request",
  "jwk": {
    "kty": "EC",
    "crv": "P-256",
    "x": "MKBCTNIcKUSDii...",
    "y": "4Etl6SRW2YiL...",
    "kid": "app2app-device-key-001"
  }
}

JWT Payload:
{
  "challenge": "challenge_abc123def456",
  "iat": 1724989400,
  "exp": 1724989460,
  "nbf": 1724989400
}
```

#### Step 3: 正常 Authorization Request（WebView）

```http
GET /oauth2/authorize?client_id=appb&redirect_uri=com.example.appb%3A%2F%2Fcallback&scope=openid+offline_access&response_type=code&code_challenge=pPnV5fB2sK8wJ4xH6zL0m...&code_challenge_method=S256 HTTP/1.1
Host: accounts.example.com
```

用户在 WebView 中完成认证后：

```http
HTTP/1.1 302 Found
Location: com.example.appb://callback?code=authcode_b_abc123xyz
```

#### Step 4: Token Request（带设备密钥绑定）

```http
POST /oauth2/token HTTP/1.1
Host: accounts.example.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&client_id=appb
&code=authcode_b_abc123xyz
&redirect_uri=com.example.appb%3A%2F%2Fcallback
&code_verifier=d36f6c8f1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6g7h8i9j0k
&x_app2app_device_key_jwt=eyJhbGciOiJFUzI1NiIsInR5cCI6InZuZC5hdXRoZ2Vhci5hcHAyYXBwLXJlcXVlc3QiLCJqd2siOnsia3R5IjoiRUMiLCJjcnYiOiJQLTI1NiIsIngiOiJNS0JDVE5JY0tVU0RpaS4uLiIsInkiOiI0RXRsNlNSVzJZaUwuLi4iLCJraWQiOiJhcHAyYXBwLWRldmljZS1rZXktMDAxIn19.eyJjaGFsbGVuZ2UiOiJjaGFsbGVuZ2VfYWJjMTIzZGVmNDU2IiwiaWF0IjoxNzI0OTg5NDAwLCJleHAiOjE3MjQ5ODk0NjAsIm5iZiI6MTcyNDk4OTQwMH0.signature
```

**参数说明**：

| 参数 | 说明 |
|------|------|
| `grant_type` | `authorization_code` — 标准授权码兑换 |
| `x_app2app_device_key_jwt` | **关键参数** — 设备密钥绑定 JWT。只有当 client 配置 `x_app2app_enabled: true` 时才会被处理，否则被忽略 |

**服务端处理** (`pkg/lib/oauth/handler/handler_token.go:1718-1725`):

```go
// doIssueTokensForAuthorizationCode()
var app2appDevicePublicKey jwk.Key = nil
if app2appDeviceKeyJWT != "" && client.App2appEnabled {
    k, err := h.app2appGetDeviceKeyJWKVerified(ctx, app2appDeviceKeyJWT)
    if err != nil {
        return nil, err
    }
    app2appDevicePublicKey = k
}
```

`app2appGetDeviceKeyJWKVerified` (`handler_token.go:457-470`):

```go
func (h *TokenHandler) app2appGetDeviceKeyJWKVerified(
    ctx context.Context, jwt string) (jwk.Key, error) {
    // 1. 未验证签名，仅提取 challenge 和公钥
    app2appToken, err := h.app2appVerifyAndConsumeChallenge(ctx, jwt)
    if err != nil { return nil, err }
    // 2. 提取嵌入的公钥
    key := app2appToken.Key
    // 3. 用提取的公钥验证签名（证明持有对应私钥）
    _, err = h.App2App.ParseToken(jwt, key)
    if err != nil { return nil, err }
    return key, nil
}
```

验证通过后，公钥通过 `IssueOfflineGrantOptions.App2AppDeviceKey` 传递给 `IssueOfflineGrant()`：

```go
// handler_token.go:1799-1808
opts := IssueOfflineGrantOptions{
    // ...
    App2AppDeviceKey: app2appDevicePublicKey,  // 设备公钥
    // ...
}
```

```go
// service_token.go:164-181
offlineGrant = &oauth.OfflineGrant{
    // ...
    App2AppDeviceKeyJWKJSON: "",  // 初始为空
    // ...
}

if opts.App2AppDeviceKey != nil {
    keyStr, err := json.Marshal(opts.App2AppDeviceKey)
    // ...
    offlineGrant.App2AppDeviceKeyJWKJSON = string(keyStr)  // 写入 OfflineGrant
}
```

#### Step 5: Token Response

```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "access_token":  "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "f9e8d7c6-b5a4-3f2e-1d0c-9b8a7f6e5d4c.abcdef1234567890abcdef1234567890",
  "token_type":    "Bearer",
  "expires_in":    3600,
  "scope":         "openid offline_access",
  "id_token":      "eyJhbGciOiJSUzI1NiIsImtpZCI6..."
}
```

App B 现在拥有绑定了设备密钥的 OfflineGrant。

---

### 3.2 阶段 2：App2App 认证（App B 为 App A 签发授权码）

#### Step 1: App A 发起 App2App 请求

App A 的 SDK 调用 `startApp2AppAuthentication()`，构建 `authorizeUri`：

```
https://appb.example.com/authorize?client_id=appa&redirect_uri=https%3A%2F%2Fappa.example.com%2Fcallback&code_challenge=eX2GfD4k5j7hK9mN1pQ3rS5tU7vW9xY1zA3bC5dE7fG9hI1kL3mN5oP7qR9sT1uV3wX5yZ7
```

通过平台打开（iOS Universal Link / Android App Link）：

```http
// iOS
UIApplication.shared.open(URL(string: "https://appb.example.com/authorize?...")!)

// Android
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://appb.example.com/authorize?..."))
startActivity(intent)
```

**平台会验证 App Link 签名**：
- **Android**: 检查 `.well-known/assetlinks.json` 中的 `sha256_cert_fingerprints`
- **iOS**: 通过 Associated Domains 机制确保只有注册的 App 才能处理该 Universal Link

#### Step 2: App B 解析请求

```swift
// iOS SDK
let request = authgear.parseApp2AppAuthenticationRequest(url: url)
// request.clientID = "appa"
// request.redirectUri = "https://appa.example.com/callback"
// request.codeChallenge = "eX2GfD4k5..."
```

#### Step 3: 获取 Challenge

```http
GET /oauth2/challenge?purpose=app2app_request HTTP/1.1
Host: accounts.example.com
Authorization: Bearer <AppB的access_token>
```

```json
HTTP/1.1 200 OK

{
  "token": "challenge_xyz789ghi012",
  "expire_at": "2024-08-30T10:47:00Z"
}
```

#### Step 4: 构造 App2App JWT

SDK 从设备安全存储区读取私钥，签名 JWT：

```
JWT Header:
{
  "alg": "ES256",
  "typ": "vnd.authgear.app2app-request",
  "jwk": {
    "kty": "EC",
    "crv": "P-256",
    "x": "MKBCTNIcKUSDii...",
    "y": "4Etl6SRW2YiL...",
    "kid": "app2app-device-key-001"
  }
}

JWT Payload:
{
  "challenge": "challenge_xyz789ghi012",
  "iat": 1724989620,
  "exp": 1724989680,
  "nbf": 1724989620
}
```

#### Step 5: Token Request（App2App Grant Type）

```http
POST /oauth2/token HTTP/1.1
Host: accounts.example.com
Content-Type: application/x-www-form-urlencoded

grant_type=urn%3Aauthgear%3Aparams%3Aoauth%3Agrant-type%3Aapp2app-request
&client_id=appa
&refresh_token=f9e8d7c6-b5a4-3f2e-1d0c-9b8a7f6e5d4c.abcdef1234567890abcdef1234567890
&jwt=eyJhbGciOiJFUzI1NiIsInR5cCI6InZuZC5hdXRoZ2Vhci5hcHAyYXBwLXJlcXVlc3QiLCJqd2siOnsia3R5IjoiRUMiLCJjcnYiOiJQLTI1NiIsIngiOiJNS0JDVE5JY0tVU0RpaS4uLiIsInkiOiI0RXRsNlNSVzJZaUwuLi4iLCJraWQiOiJhcHAyYXBwLWRldmljZS1rZXktMDAxIn19.eyJjaGFsbGVuZ2UiOiJjaGFsbGVuZ2VfeHl6Nzg5Z2hpMDEyIiwiaWF0IjoxNzI0OTg5NjIwLCJleHAiOjE3MjQ5ODk2ODAsIm5iZiI6MTcyNDk4OTYyMH0.signature
&redirect_uri=https%3A%2F%2Fappa.example.com%2Fcallback
```

**参数说明**：

| 参数 | 值 | 说明 |
|------|-----|------|
| `grant_type` | `urn:authgear:params:oauth:grant-type:app2app-request` | App2App 专用的 grant type |
| `client_id` | `appa` | **发起认证的 App 的 client_id**（不是 App B 的！） |
| `refresh_token` | App B 的 refresh_token | 用于识别已认证用户和设备密钥 |
| `jwt` | 步骤 4 签名的 JWT | 包含 challenge 和设备公钥，用于验证设备绑定 |
| `redirect_uri` | `https://appa.example.com/callback` | 必须列在 client `appa` 的 `redirect_uris` 中 |

#### Step 6: 服务端验证

服务端 `handleApp2AppRequest()` (`pkg/lib/oauth/handler/handler_token.go:1450-1577`) 执行以下完整验证：

```
验证步骤                                 代码位置                           说明
───────────────────────────────────────────────────────────────────────────────────
1. Client 级 App2appEnabled             handler_token.go:1459-1464       请求方的 client (App B) 必须配置
                                                                        x_app2app_enabled: true
───────────────────────────────────────────────────────────────────────────────────
2. Feature 级 App2AppEnabled            handler_token.go:1466-1471       全局 feature flag 必须启用
                                                                        app2app_enabled: true
───────────────────────────────────────────────────────────────────────────────────
3. redirect_uri 验证                    handler_token.go:1473-1476       必须在 client_id=appa 的
                                                                        redirect_uris 列表中
───────────────────────────────────────────────────────────────────────────────────
4. 解析 refresh_token                   handler_token.go:1478-1481       解码 refresh_token，获取
                                                                        offlineGrantID 和原始 token hash
───────────────────────────────────────────────────────────────────────────────────
5. 用户速率限制检查                      handler_token.go:1483-1485
───────────────────────────────────────────────────────────────────────────────────
6. 创建 OfflineGrantSession              handler_token.go:1487-1490      验证 token hash 匹配
───────────────────────────────────────────────────────────────────────────────────
7. 查找原始 client 配置                  handler_token.go:1495-1498      获取 App B 的 client config
                                                                        (用于 insecure binding 检查)
───────────────────────────────────────────────────────────────────────────────────
8. 解析并消费 app2app JWT                handler_token.go:1500-1504      调用 app2appVerifyAndConsumeChallenge:
                                                                        a) ParseTokenUnverified → 提取 challenge
                                                                        b) Challenge.Consume() → 一次性消费
                                                                        c) 验证 Purpose == app2app_request
───────────────────────────────────────────────────────────────────────────────────
9. 设备密钥绑定检查                      handler_token.go:1506-1515      - 若 OfflineGrant 无设备密钥:
                                                                          * insecure_binding=true → 绑定新密钥
                                                                          * insecure_binding=false → 返回错误
                                                                        - 若 OfflineGrant 已有设备密钥:
                                                                          密钥不可更改
───────────────────────────────────────────────────────────────────────────────────
10. JWT 签名验证                        handler_token.go:1517-1525      用 OfflineGrant 中存储的公钥
                                                                       验证 JWT 签名
                                                                       (证明持有对应私钥)
───────────────────────────────────────────────────────────────────────────────────
11. Authorization check                handler_token.go:1527-1535       CheckAndGrant(client_id=appa,
                                                                        userID, scopes)
───────────────────────────────────────────────────────────────────────────────────
12. 构造认证信息                        handler_token.go:1536-1539      创建 authenticationinfo.T
───────────────────────────────────────────────────────────────────────────────────
13. 构造人工 AuthorizationRequest       handler_token.go:1542-1548      包含: client_id=appa,
                                                                        scope, code_challenge (PKCE),
                                                                        x_sso_enabled (继承原始值)
───────────────────────────────────────────────────────────────────────────────────
14. 创建 CodeGrant                      handler_token.go:1556-1570      关联到 App B 的 user session
                                                                        (AuthenticatedBySessionType/
                                                                        AuthenticatedBySessionID)
───────────────────────────────────────────────────────────────────────────────────
15. 返回 authorization_code             handler_token.go:1572-1576      resp.Code(code)
```

**核心验证方法详解**：

**`app2appVerifyAndConsumeChallenge`** (`handler_token.go:442-455`):

```go
func (h *TokenHandler) app2appVerifyAndConsumeChallenge(
    ctx context.Context, jwt string) (*app2app.Request, error) {
    // 1. 未验证签名解析 JWT（提取 challenge 和嵌入的公钥）
    app2appToken, err := h.App2App.ParseTokenUnverified(jwt)
    if err != nil {
        return nil, protocol.NewError("invalid_request", "invalid app2app jwt")
    }
    // 2. Consume challenge token（一次性消费，防重放）
    purpose, err := h.Challenges.Consume(ctx, app2appToken.Challenge)
    if err != nil {
        return nil, protocol.NewError("invalid_request", "invalid app2app jwt challenge")
    }
    // 3. 验证 purpose 正确
    if *purpose != challenge.PurposeApp2AppRequest {
        return nil, protocol.NewError("invalid_request", "invalid app2app jwt challenge purpose")
    }
    return app2appToken, nil
}
```

**`app2appUpdateDeviceKeyIfNeeded`** (`handler_token.go:535-563`):

```go
func (h *TokenHandler) app2appUpdateDeviceKeyIfNeeded(
    ctx context.Context, client *config.OAuthClientConfig,
    offlineGrant *oauth.OfflineGrant,
    app2AppDeviceKey jwk.Key) (*oauth.OfflineGrant, error) {
    if app2AppDeviceKey != nil && client.App2appEnabled {
        newKeyJson, err := json.Marshal(app2AppDeviceKey)
        // ...
        isSameKey := subtle.ConstantTimeCompare(newKeyJson,
            []byte(offlineGrant.App2AppDeviceKeyJWKJSON)) != 0
        if isSameKey {
            return offlineGrant, nil  // 相同密钥，无需更新
        }
        // 密钥不同 → 检查是否允许更新
        if !client.App2appInsecureDeviceKeyBindingEnabled {
            return nil, protocol.NewError("invalid_request",
                "x_app2app_insecure_device_key_binding_enabled must be true "+
                "to allow updating x_app2app_device_key_jwt")
        }
        if offlineGrant.App2AppDeviceKeyJWKJSON != "" {
            return nil, protocol.NewError("invalid_request",
                "app2app device key cannot be changed")
        }
        // 绑定时使用 Redis 分布式锁
        return h.OfflineGrants.UpdateOfflineGrantApp2AppDeviceKey(...)
    }
    return offlineGrant, nil
}
```

**安全要点**：
- 设备密钥**只能绑定一次**（`App2AppDeviceKeyJWKJSON != ""` → 拒绝更改）
- 即使 `insecure_binding=true`，也只能绑定**尚未有密钥的** OfflineGrant
- Challenge 是**一次性消费**的，防止 JWT 重放

#### Step 7: Token Response

```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "code": "authcode_for_appa_xyz789"
}
```

**注意**：响应中只返回 `code`，不返回 access_token 或 refresh_token。这个 authorization_code 是**为 client_id=appa 签发**的。

#### Step 8: App B 将 Code 返回给 App A

```http
// iOS
UIApplication.shared.open(URL(string: "https://appa.example.com/callback?code=authcode_for_appa_xyz789")!)

// Android
val intent = Intent(Intent.ACTION_VIEW,
    Uri.parse("https://appa.example.com/callback?code=authcode_for_appa_xyz789"))
startActivity(intent)
```

#### Step 9: App A 兑换 Authorization Code

```http
POST /oauth2/token HTTP/1.1
Host: accounts.example.com
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&client_id=appa
&code=authcode_for_appa_xyz789
&redirect_uri=https%3A%2F%2Fappa.example.com%2Fcallback
&code_verifier=d36f6c8f1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6g7h8i9j0k
```

#### Step 10: 最终 Response

```json
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "access_token":  "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d.zyxwvutsrqponmlkjihgfedcba0987654321",
  "token_type":    "Bearer",
  "expires_in":    3600,
  "scope":         "openid offline_access",
  "id_token":      "eyJhbGciOiJSUzI1NiIsImtpZCI6..."
}
```

**App A 获得独立的 session**，与 App B 的 OfflineGrant 互不影响登出。

---

## 四、JWT 解析路径（Provider 详解）

```go
// pkg/lib/app2app/provider.go

// ParseTokenUnverified — 未验证签名，仅解析（用于提取 challenge 和公钥）
func (p *Provider) ParseTokenUnverified(requestJWT string) (*app2app.Request, error) {
    // 1. SplitWithoutVerify: 分割 JWT header/payload/signature（不验证）
    // 2. 验证 exp, nbf, iat（clock skew ±1min）
    // 3. 从 header 提取 "jwk" 参数（必须是包含 1 个 key 的 JWK Set）
    // 4. 验证 kid 格式 (^[-\w]{8,64}$)
    // 5. 验证 typ header == "vnd.authgear.app2app-request"
    // 6. 解析 JWS payload → app2app.Request
    // 7. 设置 request.Key = 提取的公钥
}

// ParseToken — 验证签名的完整解析
func (p *Provider) ParseToken(requestJWT string, key jwk.Key) (*app2app.Request, error) {
    // 1. 创建 JWK Set（仅包含提供的 key）
    // 2. jws.Verify(requestJWT, jws.WithKeySet(keySet)) 验证签名
    // 3. 解析 payload → app2app.Request
    // 4. 设置 request.Key = key
}
```

**两层设计的意义**：
- `ParseTokenUnverified`：快速提取 challenge 和公钥，不与存储交互（用于 consume challenge + 获取待验证的公钥）
- `ParseToken`：在获取到存储的公钥后用其验证 JWT 签名（用于证明持有对应私钥）

---

## 五、Redis 持久化

```go
// pkg/lib/oauth/redis/store.go:372-396
func (s *Store) UpdateOfflineGrantApp2AppDeviceKey(
    ctx context.Context, grantID string, newKey string, expireAt time.Time,
) (*oauth.OfflineGrant, error) {
    // 1. 获取 Redis 分布式锁 (offlineGrantMutexName)
    mutex := s.Redis.NewMutex(mutexName)
    mutex.LockContext(ctx)
    defer mutex.UnlockContext(ctx)

    // 2. 读取 OfflineGrant
    grant, err := s.GetOfflineGrantWithoutExpireAt(ctx, grantID)

    // 3. 更新 App2AppDeviceKeyJWKJSON
    grant.App2AppDeviceKeyJWKJSON = newKey

    // 4. 写回 Redis
    s.updateOfflineGrant(ctx, grant, expireAt)
}
```

使用 Redis 分布式锁确保并发安全。

---

## 六、安全机制

### 6.1 多层验证链

```
JWT Challenge (防重放)          设备密钥对 (设备绑定)           签名验证 (持有证明)
─────────────────────       ─────────────────────       ─────────────────────
Challenge token             私钥 → 设备安全存储区         JWT 用私钥签名
  │                            │                            │
  │ Consume() 一次性消费        │ 公钥 → OfflineGrant         │ 服务端用存储的公钥验证签名
  │                            │   .App2AppDeviceKeyJWKJSON    │
  ▼                            ▼                            ▼
Challenge 被消费后            只有持有设备私钥的 App        持有设备私钥的 App 才能
JWT 不可重放                  才能关联到 OfflineGrant        生成有效 JWT
```

### 6.2 Platform 级安全

- **Android App Links**: 通过 `.well-known/assetlinks.json` + `sha256_cert_fingerprints` 验证目标 App 的签名
- **iOS Universal Links**: 通过 Associated Domains 机制确保只有注册的 App 才能处理该 domain 的链接

### 6.3 设备密钥安全策略

| 场景 | 行为 | 原因 |
|------|------|------|
| 首次登录（App2AppEnabled client） | 可绑定新设备密钥 | 正常注册流程 |
| OfflineGrant 已有密钥 → 尝试绑定新密钥 | **拒绝**（`"app2app device key cannot be changed"`） | 防止攻击者用自生成的密钥替换合法密钥 |
| OfflineGrant 无密钥 + `insecure_binding=true` | **允许**绑定 | 存量用户迁移，但有风险 |
| OfflineGrant 无密钥 + `insecure_binding=false` | **拒绝** | 安全默认 |

### 6.4 与其他 SSO 机制的安全对比

| 安全维度 | App2App SSO | Device SSO |
|---------|-------------|-----------|
| **凭证绑定** | 非对称密钥对（私钥在设备安全区） | 对称密钥（device_secret） |
| **防重放** | Challenge 一次性消费 + JWT 时效 | device_secret 哈希验证 + DPoP |
| **设备证明** | 私钥签名（不可能从设备导出） | device_secret 可从存储读取 |
| **Session 隔离** | App A 和 App B 独立 OfflineGrant | 共享底层 OfflineGrant |
| **用户交互** | 需要用户同意 | 无需用户交互 |
| **Platform 依赖** | 依赖 iOS/Android App Link 签名验证 | 依赖共享存储安全性 |

---

## 七、配置要求总结

### 7.1 OAuth Client 配置

```yaml
oauth:
  clients:
    # App A（发起认证的 App）
    - client_id: appa
      name: "App A"
      application_type: native
      redirect_uris:
        - "https://appa.example.com/callback"
      grant_types:
        - authorization_code
        - refresh_token
      # App A 不需要 x_app2app_enabled
      # （它不处理其他 App 的认证请求）

    # App B（已认证的 App，处理其他 App 的认证请求）
    - client_id: appb
      name: "App B"
      application_type: native
      redirect_uris:
        - "com.example.appb://callback"
      grant_types:
        - authorization_code
        - refresh_token
      x_app2app_enabled: true                           # 允许处理 App2App 认证请求
      x_app2app_insecure_device_key_binding_enabled: false  # 安全默认，不允许无密钥的 OfflineGrant 绑定

  # Feature flag（全局开关）
  # 在 authgear.features.yaml 中：
  # oauth:
  #   client:
  #     app2app_enabled: true
```

### 7.2 Platform 配置

**Android**（`AndroidManifest.xml`）：

```xml
<!-- App B 的 AndroidManifest.xml -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
        android:scheme="https"
        android:host="appb.example.com" />
</intent-filter>
```

```json
// https://appb.example.com/.well-known/assetlinks.json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.example.appb",
    "sha256_cert_fingerprints": ["AA:BB:CC:..."]
  }
}]
```

**iOS**（`apple-app-site-association`）：

```json
{
  "applinks": {
    "details": [
      {
        "appID": "TEAMID.com.example.appb",
        "paths": ["/authorize"]
      }
    ]
  }
}
```

---

## 八、SDK API 概要

| SDK 方法 | 所属 App | 说明 |
|---------|---------|------|
| `startApp2AppAuthentication(options)` | App A (发起方) | 构造 authorizeUri 并通过 App Link 打开 |
| `parseApp2AppAuthenticationRequest(url)` | App B (已认证方) | 解析收到的 App2App 请求 |
| `approveApp2AppAuthenticationRequest(request)` | App B | 同意请求：获取 challenge → 签名 JWT → 调用 Token Endpoint → 返回 code |
| `rejectApp2AppAuthenticationRequest(request, error)` | App B | 拒绝请求：返回错误 |
| `handleApp2AppAuthenticationResult(url)` | App A | 从 redirect URI 提取 code 并兑换 token |

---

## 九、服务端完整代码路径

### 9.1 App2App Grant 处理链

```
POST /oauth2/token (grant_type=urn:authgear:params:oauth:grant-type:app2app-request)
  └─ TokenHandler.Handle()
     └─ doHandle()                                         handler_token.go
        └─ validateRequestWithoutTx()                      handler_token.go:370-420
           └─ case App2AppRequestGrantType:
              ├─ jwt required                              line 405
              ├─ refresh_token required                    line 408
              ├─ client_id required                        line 411
              └─ redirect_uri required                     line 414
        └─ doHandleWithTx()                                handler_token.go:329
           └─ case App2AppRequestGrantType                 line 360
              └─ handleApp2AppRequest()                     handler_token.go:1450-1577
                 ├─ client.App2appEnabled check            line 1459
                 ├─ feature.Client.App2AppEnabled check    line 1466
                 ├─ parseRedirectURI()                     line 1473
                 ├─ ParseRefreshToken()                    line 1478
                 ├─ offlineGrant.ToSession()               line 1487
                 ├─ app2appVerifyAndConsumeChallenge()     line 1500, 442-455
                 │  ├─ App2App.ParseTokenUnverified()      app2app/provider.go:33
                 │  └─ Challenges.Consume()                challenge.go
                 ├─ app2appUpdateDeviceKeyIfNeeded()       line 1511, 535-563
                 │  (仅当 offlineGrant 尚无 device key 时)
                 ├─ JSON unmarshal App2AppDeviceKeyJWKJSON line 1517
                 ├─ App2App.ParseToken(jwt, storedKey)     line 1521
                 │  (用存储的公钥验证 JWT 签名)
                 ├─ Authorizations.CheckAndGrant()         line 1527
                 ├─ 构造 AuthorizationRequest              line 1542
                 └─ CodeGrantService.CreateCodeGrant()     line 1556
```

### 9.2 设备密钥绑定链（authorization_code 流程）

```
POST /oauth2/token (grant_type=authorization_code, x_app2app_device_key_jwt=...)
  └─ TokenHandler.Handle()
     └─ handleAuthorizationCode()
        └─ doIssueTokensForAuthorizationCode()             handler_token.go:1680
           ├─ shouldIssueDeviceSecret()                    handler_token.go:2067
           ├─ if app2appDeviceKeyJWT != "" && client.App2appEnabled:
           │  └─ app2appGetDeviceKeyJWKVerified()          handler_token.go:1719, 457-470
           │     ├─ app2appVerifyAndConsumeChallenge()     handler_token.go:459, 442-455
           │     └─ App2App.ParseToken(jwt, key)            handler_token.go:464
           │        (用嵌入的公钥验证签名)
           └─ IssueOfflineGrantOptions{
                App2AppDeviceKey: app2appDevicePublicKey }  line 1806
              └─ TokenService.IssueOfflineGrant()
                 └─ if opts.App2AppDeviceKey != nil:       service_token.go:175
                    └─ json.Marshal(opts.App2AppDeviceKey)
                       → offlineGrant.App2AppDeviceKeyJWKJSON
```

---

## 十、与 Device SSO 的对比

| 特性 | App2App SSO | Device SSO |
|------|------------|------------|
| **协议基础** | App2App JWT Challenge（自研） | OIDC Native SSO / RFC 8693 Token Exchange |
| **共享方式** | 不共享存储，通过 App Link 通信 | 共享存储（iOS App Group / Android AccountManager） |
| **核心凭证** | 设备密钥对（JWK，非对称密钥） | `device_secret`（对称密钥） |
| **设备证明** | 私钥签名（私钥不可导出） | 密钥哈希匹配 |
| **防重放** | Challenge 一次性消费 | ds_hash 验证 + DPoP |
| **用户交互** | App B 需要用户同意 | 无需用户交互 |
| **Session 独立性** | 各自独立 OfflineGrant（登出互不影响） | 共享底层 OfflineGrant（登出互相影响） |
| **配置难度** | 高（需 feature flag + client config + platform 配置） | 低（只需 `x_pre_authenticated_url_enabled`） |
| **SDK 复杂度** | 高（5 个 API 方法 + App Link 处理） | 低（2 个 API 方法 + 共享存储） |
| **适用场景** | 不能/不愿共享存储的独立 App | 同一发行商、共享存储的多 App |

---

## 十一、关键文件索引

| 文件 | 内容 |
|------|------|
| `docs/specs/app2app.md` | App2App 完整设计规范 |
| `pkg/lib/oauth/handler/handler_token.go:442-455` | `app2appVerifyAndConsumeChallenge` — Challenge 验证与消费 |
| `pkg/lib/oauth/handler/handler_token.go:457-470` | `app2appGetDeviceKeyJWKVerified` — 设备密钥 JWT 完整验证 |
| `pkg/lib/oauth/handler/handler_token.go:535-563` | `app2appUpdateDeviceKeyIfNeeded` — 设备密钥更新策略 |
| `pkg/lib/oauth/handler/handler_token.go:1450-1577` | `handleApp2AppRequest` — **App2App 核心处理逻辑** |
| `pkg/lib/oauth/handler/handler_token.go:360-361` | Grant type dispatch → `handleApp2AppRequest` |
| `pkg/lib/oauth/handler/handler_token.go:404-419` | App2App grant type 参数验证 |
| `pkg/lib/oauth/handler/handler_token.go:1718-1725` | authorization_code 流程中的设备密钥绑定 |
| `pkg/lib/oauth/handler/service_token.go:175-181` | `IssueOfflineGrant` 中的设备密钥持久化 |
| `pkg/lib/oauth/redis/store.go:372-396` | Redis 中的 `UpdateOfflineGrantApp2AppDeviceKey` |
| `pkg/lib/oauth/grant_offline.go:60` | `OfflineGrant.App2AppDeviceKeyJWKJSON` 字段 |
| `pkg/lib/oauth/grant_type.go:16` | `App2AppRequestGrantType` 常量 |
| `pkg/lib/oauth/grant_type.go:32` | WhitelistedGrantTypes 包含 App2AppRequestGrantType |
| `pkg/lib/app2app/request.go` | `Request` 结构体 + `RequestTokenType` 常量 |
| `pkg/lib/app2app/provider.go:33-112` | `ParseTokenUnverified` — 未验证签名的 JWT 解析 |
| `pkg/lib/app2app/provider.go:114-132` | `ParseToken` — 验证签名的 JWT 解析 |
| `pkg/lib/app2app/provider.go:19` | `KeyIDFormat` 正则验证 |
| `pkg/lib/authn/challenge/challenge.go:16` | `PurposeApp2AppRequest` challenge purpose |
| `pkg/lib/authn/challenge/challenge.go:31-42` | Challenge validity period (`duration.Short`) |
| `pkg/lib/config/oauth.go:273` | `OAuthClientConfig.App2appEnabled` |
| `pkg/lib/config/oauth.go:274` | `OAuthClientConfig.App2appInsecureDeviceKeyBindingEnabled` |
| `pkg/lib/config/feature_oauth.go:51` | `OAuthClientFeatureConfig.App2AppEnabled` |
| `pkg/lib/config/feature_oauth.go:67-69` | Feature flag 默认值（false） |
| `pkg/lib/oauth/protocol/token.go:23-25` | `App2AppDeviceKeyJWT()` 请求参数 |
| `pkg/lib/oauth/protocol/token.go:83` | `Code(v)` TokenResponse 方法 |
