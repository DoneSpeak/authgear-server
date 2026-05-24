# Authgear OAuth 设计与登录流程配置分析

## 目录

1. [OAuth 整体设计架构](#1-oauth-整体设计架构)
2. [多 OAuth 类型的配置设计](#2-多-oauth-类型的配置设计)
3. [登录/注册节点的用户选项设计](#3-登录注册节点的用户选项设计)
4. [Login Flow 配置示例](#4-login-flow-配置示例)
5. [HTTP 请求流程](#5-http-请求流程)
6. [Google OAuth Signup Flow 详细流程](#6-google-oauth-signup-flow-详细流程)
7. [Apple OAuth 特殊处理](#7-apple-oauth-特殊处理)
8. [fill_in_user_profile 实现详解](#8-fill_in_user_profile-实现详解)
9. [核心代码参考](#9-核心代码参考)

---

## 1. OAuth 整体设计架构

### 1.1 Identity 类型层级

```
┌─────────────────────────────────────────────────────────────┐
│                    Identity 系统                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  LoginID     │  │    OAuth     │  │   Passkey    │      │
│  │ (邮箱/手机)   │  │ (第三方登录)  │  │  (生物识别)   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 OAuth 相关核心类型定义

```go
// pkg/api/model/identity.go
type IdentityType string

const (
    IdentityTypeLoginID   IdentityType = "login_id"
    IdentityTypeOAuth     IdentityType = "oauth"      // 第三方 OAuth 提供商
    IdentityTypeAnonymous IdentityType = "anonymous"
    IdentityTypeBiometric IdentityType = "biometric"
    IdentityTypePasskey   IdentityType = "passkey"
    // ...
)

// pkg/api/model/identification.go
type AuthenticationFlowIdentification string

const (
    AuthenticationFlowIdentificationEmail    AuthenticationFlowIdentification = "email"
    AuthenticationFlowIdentificationPhone    AuthenticationFlowIdentification = "phone"
    AuthenticationFlowIdentificationUsername AuthenticationFlowIdentification = "username"
    AuthenticationFlowIdentificationOAuth    AuthenticationFlowIdentification = "oauth"
    AuthenticationFlowIdentificationPasskey  AuthenticationFlowIdentification = "passkey"
    AuthenticationFlowIdentificationIDToken  AuthenticationFlowIdentification = "id_token"
    AuthenticationFlowIdentificationLDAP     AuthenticationFlowIdentification = "ldap"
)
```

---

## 2. 多 OAuth 类型的配置设计

### 2.1 OAuth Provider 配置结构

```go
// pkg/lib/config/identity.go

type IdentityConfig struct {
    LDAP       *LDAPConfig             `json:"ldap,omitempty"`
    LoginID    *LoginIDConfig          `json:"login_id,omitempty"`
    OAuth      *OAuthSSOConfig         `json:"oauth,omitempty"`     // OAuth 配置入口
    Biometric  *BiometricConfig        `json:"biometric,omitempty"`
    OnConflict *IdentityConflictConfig `json:"on_conflict,omitempty"`
}

// OAuthSSOConfig 包含多个 Provider 配置
type OAuthSSOConfig struct {
    Providers []OAuthSSOProviderConfig `json:"providers,omitempty"`
}

// OAuthSSOProviderConfig 是单个 Provider 的配置
type OAuthSSOProviderConfig oauthrelyingparty.ProviderConfig

// ProviderConfig 结构 (来自 oauthrelyingparty 库)
// 包含以下关键字段:
// - alias: Provider 的唯一标识别名
// - type: Provider 类型 (google, apple, facebook, etc.)
// - client_id: OAuth Client ID
// - claims: 包含 email 配置 (assume_verified, required)
// - 其他 Provider 特定配置 (key_id, team_id 等用于 Apple)
```

### 2.2 多 Provider 配置示例

```yaml
# authgear.yaml 中的 identity 配置
identity:
  oauth:
    providers:
      # Google OAuth 配置
      - alias: google
        type: google
        client_id: "your-google-client-id.apps.googleusercontent.com"
        claims:
          email:
            assume_verified: true
            required: true
      
      # Apple OAuth 配置
      - alias: apple
        type: apple
        client_id: "your.apple.bundle.id"
        key_id: "your-key-id"
        team_id: "your-team-id"
        claims:
          email:
            assume_verified: true
            required: false  # Apple 可能不提供 email
      
      # 其他 Provider...
      - alias: facebook
        type: facebook
        client_id: "your-facebook-app-id"
```

### 2.3 Provider 状态管理

```go
// OAuthProviderStatus 表示 Provider 的当前状态
type OAuthProviderStatus string

const (
    OAuthProviderStatusActive               OAuthProviderStatus = "active"
    OAuthProviderStatusMissingCredentials   OAuthProviderStatus = "missing_credentials"
    OAuthProviderStatusUsingDemoCredentials OAuthProviderStatus = "using_demo_credentials"
)

// 计算 Provider 状态
func (c OAuthSSOProviderConfig) ComputeProviderStatus(demoCredentials *SSOOAuthDemoCredentials) OAuthProviderStatus {
    if c.GetCredentialsBehavior() == OAuthSSOProviderCredentialsBehaviorUseProjectCredentials {
        return OAuthProviderStatusActive
    }
    if demoCredentials == nil {
        return OAuthProviderStatusMissingCredentials
    }
    // ... 检查 demo credentials
}
```

---

## 3. 登录/注册节点的用户选项设计

### 3.1 Identification 选项生成流程

```
IntentLoginFlowStepIdentify
    │
    ├─ NewIntentLoginFlowStepIdentify()
    │   │
    │   └─ 遍历 step.OneOf (配置的 identification 选项)
    │       │
    │       ├─ email/phone/username → NewIdentificationOptionLoginID()
    │       │
    │       ├─ oauth → NewIdentificationOptionsOAuth()  【重点】
    │       │          │
    │       │          └─ 遍历 oauthConfig.Providers
    │       │              │
    │       │              └─ 为每个 Provider 生成一个选项
    │       │
    │       ├─ passkey → NewIdentificationOptionPasskey()
    │       │
    │       └─ ldap → NewIdentificationOptionLDAP()
    │
    └─ CanReactTo() → 返回 InputSchemaStepIdentify
        │
        └─ 包含所有生成的 Options
```

### 3.2 OAuth 选项生成代码

```go
// pkg/lib/authenticationflow/declarative/data_identification.go

func NewIdentificationOptionsOAuth(
    flows authflow.Flows,
    oauthConfig *config.OAuthSSOConfig,
    oauthFeatureConfig *config.OAuthSSOProvidersFeatureConfig,
    authflowCfg *config.AuthenticationFlowBotProtection,
    appCfg *config.BotProtectionConfig,
    demoCredentials *config.SSOOAuthDemoCredentials,
) []IdentificationOption {
    output := []IdentificationOption{}
    
    // 遍历所有配置的 Provider
    for _, p := range oauthConfig.Providers {
        // 检查 Provider 是否被 FeatureConfig 禁用
        if !identity.IsOAuthSSOProviderTypeDisabled(p.AsProviderConfig(), oauthFeatureConfig) {
            // 计算 Provider 状态
            status := p.ComputeProviderStatus(demoCredentials)

            output = append(output, IdentificationOption{
                Identification: model.AuthenticationFlowIdentificationOAuth,
                BotProtection:  GetBotProtectionData(flows, authflowCfg, appCfg),
                ProviderType:   p.AsProviderConfig().Type(),  // "google", "apple", etc.
                Alias:          p.Alias(),                     // 唯一别名
                WechatAppType:  wechat.ProviderConfig(p).AppType(),
                ProviderStatus: status,                        // active, missing_credentials, etc.
            })
        }
    }
    return output
}

// IdentificationOption 结构
type IdentificationOption struct {
    Identification model.AuthenticationFlowIdentification `json:"identification"`
    BotProtection *BotProtectionData `json:"bot_protection,omitempty"`
    
    // OAuth 特定字段
    ProviderType string `json:"provider_type,omitempty"`    // "google", "apple"
    Alias string `json:"alias,omitempty"`                   // Provider 别名
    WechatAppType wechat.AppType `json:"wechat_app_type,omitempty"`
    ProviderStatus OAuthProviderStatus `json:"provider_status,omitempty"`
    
    // Passkey 特定字段
    RequestOptions *model.WebAuthnRequestOptions `json:"request_options,omitempty"`
    
    // LDAP 特定字段
    ServerName string `json:"server_name,omitempty"`
}
```

### 3.3 用户选择 OAuth Provider 后的流程

```
用户选择 OAuth (e.g., Google)
    │
    ▼
IntentLoginFlowStepIdentify.ReactTo()
    │
    ├─ identification == "oauth"
    │
    ▼
启动 SubFlow: IntentOAuth
    │
    ├─ CanReactTo() → InputSchemaTakeOAuthAuthorizationRequest
    │   │
    │   └─ 返回 OAuth 授权 URL 等信息
    │
    ├─ ReactTo() → NodeOAuth
    │   │
    │   └─ 创建 NodeOAuth，存储 alias, redirect_uri 等
    │
    ▼
NodeOAuth
    │
    ├─ OutputData() → 返回 OAuth 授权页面 URL
    │
    ├─ 用户完成 OAuth 授权后
    │
    ├─ ReactTo() → 处理授权回调
    │   │
    │   ├─ handleOAuthAuthorizationResponse() 
    │   │   │
    │   │   └─ 与 Provider 交换 Token，获取用户信息
    │   │
    │   └─ 创建 identity.Spec (包含 Provider 用户信息)
    │
    ▼
根据场景:
    │
    ├─ 注册场景 → IntentCheckConflictAndCreateIdenity → NodeDoCreateIdentity
    │
    └─ 登录场景 → findExactOneIdentityInfo() → NewNodeDoUseIdentityWithUpdate
```

---

## 4. Login Flow 配置示例

### 4.1 需求场景

- 用户可以从 Google 或 Apple 登录
- 也可以使用 Email + 密码登录
- 验证完成后需要邮箱验证码二次验证
- 如果 OAuth Provider (如 Apple) 未提供 email，需要用户补充

**⚠️ 重要限制**: 在 Flow 配置中**无法区分不同 OAuth Provider** (Google、Apple 等)，它们共用同一个 `identification: oauth` 配置。如果配置了 `fill_in_user_profile` 步骤，**所有** OAuth 用户在未获取到 email 时都会被要求补充。

例如：

- Apple 用户未提供 email → 需要补充
- Google 用户未授权 email 访问 → 同样需要补充

### 4.2 完整配置

**重要说明**:

1. `fill_in_user_profile` 和 `verify` 步骤仅在 **Signup Flow** 中可用
2. **配置中无法区分不同 OAuth Provider**：所有 OAuth (Google、Apple 等) 共用同一个 `identification: oauth` 配置和 nested steps
3. 如果配置了 `fill_in_user_profile`，**所有** OAuth 用户都会被要求填写（当 Provider 未返回 email 时）

```yaml
# authgear.yaml

# ========== Identity 配置 ==========
identity:
  login_id:
    keys:
      - type: email
  
  oauth:
    providers:
      # Google OAuth
      - alias: google
        type: google
        client_id: "your-google-client-id.apps.googleusercontent.com"
        claims:
          email:
            assume_verified: true
            required: true
      
      # Apple OAuth
      - alias: apple
        type: apple
        client_id: "your.apple.bundle.id"
        key_id: "your-key-id"
        team_id: "your-team-id"
        claims:
          email:
            assume_verified: true
            required: false  # Apple 可能不提供 email

# ========== Authentication 配置 ==========
authentication:
  primary_authenticators:
    - password
  
  secondary_authentication_mode: required  # 强制二次验证
  
  secondary_authenticators:
    - oob_otp_email  # 邮箱验证码

# ========== Signup Flow 配置 (处理新用户注册) ==========
authentication_flow:
  signup_flows:
    - name: multi_oauth_signup_with_mfa
      steps:
        # Step 1: 身份识别
        # ⚠️ 注意：所有 OAuth Provider 共用此配置
        - type: identify
          one_of:
            # OAuth 注册 (适用于所有配置的 Providers: Google, Apple 等)
            - identification: oauth
              steps:
                # 补充用户信息（当 OAuth Provider 未返回 email 时触发）
                # 适用于所有 OAuth：Apple(无email)、Google(email未授权)等
                - type: fill_in_user_profile
                  user_profile:
                    - pointer: /email
                      required: true
                
                # 验证邮箱
                - type: verify
                  target_step: identify
                
                # 创建二次验证器
                - type: create_authenticator
                  one_of:
                    - authentication: secondary_oob_otp_email
            
            # Email + 密码注册
            - identification: email
              steps:
                - type: create_authenticator
                  one_of:
                    - authentication: primary_password
                - type: verify
                  target_step: identify
                - type: create_authenticator
                  one_of:
                    - authentication: secondary_oob_otp_email

# ========== Login Flow 配置 (已有用户登录) ==========
  login_flows:
    - name: multi_oauth_login_with_mfa
      steps:
        # Step 1: 身份识别
        # ⚠️ 注意：所有 OAuth Provider 共用此配置
        - type: identify
          one_of:
            # OAuth 登录 (适用于所有配置的 Providers)
            - identification: oauth
              steps:
                - type: authenticate
                  one_of:
                    - authentication: secondary_oob_otp_email
                      target_step: identify
            
            # Email + 密码登录
            - identification: email
              steps:
                - type: authenticate
                  one_of:
                    - authentication: primary_password
                      target_step: identify
                - type: authenticate
                  one_of:
                    - authentication: secondary_oob_otp_email
                      target_step: identify
```

### 4.3 关键配置说明

#### 4.3.1 Flow 类型与支持的 Steps


| Step 类型                    | Signup Flow | Login Flow | 说明            |
| -------------------------- | ----------- | ---------- | ------------- |
| `identify`                 | ✅           | ✅          | 身份识别          |
| `create_authenticator`     | ✅           | ❌          | 创建验证器 (仅注册)   |
| `authenticate`             | ❌           | ✅          | 验证身份 (仅登录)    |
| `verify`                   | ✅           | ❌          | 验证邮箱/手机 (仅注册) |
| `fill_in_user_profile`     | ✅           | ❌          | 补充用户信息 (仅注册)  |
| `view_recovery_code`       | ✅           | ❌          | 查看恢复码 (仅注册)   |
| `prompt_create_passkey`    | ✅           | ✅          | 提示创建 Passkey  |
| `check_account_status`     | ❌           | ✅          | 检查账户状态 (仅登录)  |
| `terminate_other_sessions` | ❌           | ✅          | 终止其他会话 (仅登录)  |
| `change_password`          | ❌           | ✅          | 修改密码 (仅登录)    |


#### 4.3.2 nested_steps 机制

在 `identify` 步骤中，`steps` 表示在该身份识别完成后执行的子步骤：

**Signup Flow 示例:**

```yaml
- type: identify
  one_of:
    - identification: oauth
      steps:  # OAuth 完成后执行
        - type: fill_in_user_profile  # 补充信息
          user_profile:
            - pointer: /email
              required: true
        - type: verify                  # 验证邮箱
          target_step: identify
        - type: create_authenticator    # 创建二次验证器
          one_of:
            - authentication: secondary_oob_otp_email
```

**Login Flow 示例:**

```yaml
- type: identify
  one_of:
    - identification: oauth
      steps:  # OAuth 完成后执行
        - type: authenticate            # 进行二次验证
          one_of:
            - authentication: secondary_oob_otp_email
              target_step: identify  # 指向 identify step 获取的身份
```

#### 4.3.3 fill_in_user_profile 用于补充信息 (Signup Flow 专用)

用于处理 Apple 等 OAuth Provider 不提供 email 的场景，**仅适用于 Signup Flow**：

```yaml
- type: fill_in_user_profile
  user_profile:
    - pointer: /email
      required: true  # 强制要求提供 email
```

#### 4.3.4 verify 步骤验证邮箱 (Signup Flow 专用)

用于验证用户提供的邮箱，**仅适用于 Signup Flow**：

```yaml
- type: verify
  target_step: identify  # 验证 identify step 中创建的 identity
```

#### 4.3.5 target_step 说明

`target_step` 用于指定引用哪个 step 创建的身份：

- 在 Login Flow 的 `authenticate` 中，指向 `identify` step
- 在 Signup Flow 的 `verify` 中，指向 `identify` step
- 可以使用 `name` 字段为 step 命名以便引用

---

## 5. HTTP 请求流程

### 5.1 完整 HTTP 交互流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          HTTP 请求流程图                                    │
└─────────────────────────────────────────────────────────────────────────────┘

【1】创建登录 Flow
─────────────────────────────────────────────────────────────────────────────
POST /api/v1/authentication_flows
Content-Type: application/json

Request:
{
    "type": "login",
    "name": "multi_oauth_with_mfa"
}

Response:
{
    "result": {
        "state_token": "state_token_xxx",
        "type": "identify",
        "options": [
            {
                "identification": "oauth",
                "provider_type": "google",
                "alias": "google",
                "provider_status": "active"
            },
            {
                "identification": "oauth",
                "provider_type": "apple",
                "alias": "apple",
                "provider_status": "active"
            },
            {
                "identification": "email"
            }
        ]
    }
}


【2】选择 Google OAuth 登录
─────────────────────────────────────────────────────────────────────────────
POST /api/v1/authentication_flows/states/state_token_xxx
Content-Type: application/json

Request:
{
    "identification": "oauth",
    "alias": "google"  # 使用 Provider 配置中的 alias
}

# 或选择 Apple OAuth:
# {
#     "identification": "oauth",
#     "alias": "apple"
# }

Response:
{
    "result": {
        "state_token": "state_token_xxx",
        "type": "oauth",
        "oauth_authorization_url": "https://accounts.google.com/o/oauth2/v2/auth?client_id=xxx&..."
    }
}


【3】用户完成 OAuth 授权后，发送回调数据
─────────────────────────────────────────────────────────────────────────────
POST /api/v1/authentication_flows/states/state_token_xxx
Content-Type: application/json

# 实际发送的是授权回调的 query 参数（由 frontend 从 URL 解析）
Request:
{
    "query": "code=authorization_code_from_google&state=state_param"
}

Response (需要二次验证):
{
    "result": {
        "state_token": "state_token_yyy",
        "type": "authenticate",
        "options": [
            {
                "authentication": "secondary_oob_otp_email",
                "target": "user@example.com"
            }
        ]
    }
}


【4】触发二次验证 OTP 发送
─────────────────────────────────────────────────────────────────────────────
POST /api/v1/authentication_flows/states/state_token_yyy
Content-Type: application/json

Request:
{
    "authentication": "secondary_oob_otp_email",
    "device_token": "optional_device_token"
}

Response:
{
    "result": {
        "state_token": "state_token_yyy",
        "type": "authenticate",
        "options": [
            {
                "authentication": "secondary_oob_otp_email",
                "target": "user@example.com",
                "otp_form": "code"
            }
        ]
    }
}


【5】提交 OTP 验证码
─────────────────────────────────────────────────────────────────────────────
POST /api/v1/authentication_flows/states/state_token_yyy
Content-Type: application/json

Request:
{
    "authentication": "secondary_oob_otp_email",
    "code": "123456"
}

Response (登录成功):
{
    "result": {
        "state_token": "state_token_yyy",
        "type": "finished",
        "finish_redirect_uri": "/oauth2/callback?code=auth_code&state=xxx",
        "finish_uri": "/settings"
    }
}
```

### 5.2 Apple OAuth 无 Email 场景 (Signup Flow)

**重要**: Apple 无 Email 场景仅适用于 **Signup Flow**（新用户注册）。Login Flow 假设用户已存在，不提供 `fill_in_user_profile` 和 `verify` 步骤。

```
【Apple 注册场景 - Signup Flow】

POST /api/v1/authentication_flows
Request:
{
    "type": "signup",
    "name": "multi_oauth_signup_with_mfa"
}

Response (显示选项):
{
    "result": {
        "state_token": "state_token_xxx",
        "type": "identify",
        "options": [
            {"identification": "oauth", "provider_type": "apple", "alias": "apple"}
        ]
    }
}


【选择 Apple OAuth】
POST /api/v1/authentication_flows/states/state_token_xxx
Request:
{
    "identification": "oauth",
    "alias": "apple"  # 使用 Provider 配置中的 alias
}

Response (需要补充信息 - 因 Apple 未提供 email):
{
    "result": {
        "state_token": "state_token_yyy",
        "type": "fill_in_user_profile",
        "user_profile": {
            "items": [
                {
                    "pointer": "/email",
                    "required": true
                }
            ]
        }
    }
}


【补充 Email】
POST /api/v1/authentication_flows/states/state_token_yyy
Request:
{
    "user_profile": {
        "email": "user@example.com"
    }
}

Response (进入验证步骤):
{
    "result": {
        "state_token": "state_token_zzz",
        "type": "verify",
        "target_step": "identify"
    }
}


【触发邮箱验证】
POST /api/v1/authentication_flows/states/state_token_zzz
Request:
{
    "resend": true
}

Response (发送验证码):
{
    "result": {
        "state_token": "state_token_zzz",
        "type": "verify",
        "verify": {
            "channel": "email",
            "target": "user@example.com",
            "otp_form": "code"
        }
    }
}


【提交验证码完成验证】
POST /api/v1/authentication_flows/states/state_token_zzz
Request:
{
    "code": "123456"
}

Response (验证通过，进入创建二次验证器):
{
    "result": {
        "state_token": "state_token_www",
        "type": "create_authenticator",
        "options": [
            {
                "authentication": "secondary_oob_otp_email"
            }
        ]
    }
}


【创建二次验证器】
POST /api/v1/authentication_flows/states/state_token_www
Request:
{
    "authentication": "secondary_oob_otp_email"
}

Response (注册完成):
{
    "result": {
        "state_token": "state_token_www",
        "type": "finished",
        "finish_redirect_uri": "/oauth2/callback?code=auth_code&state=xxx",
        "finish_uri": "/settings"
    }
}
```

---

## 6. Google OAuth Signup Flow 详细流程

### 6.1 配置示例

```yaml
identity:
  oauth:
    providers:
      - alias: google
        type: google
        client_id: "xxx.apps.googleusercontent.com"
        claims:
          email:
            assume_verified: true   # Google email 被视为已验证
            required: true

authentication_flow:
  signup_flows:
    - name: google_signup
      steps:
        - type: identify
          one_of:
            - identification: oauth
              steps:
                # 1. 补充用户信息
                - type: fill_in_user_profile
                  user_profile:
                    - pointer: /email
                      required: true
                
                # 2. 验证邮箱（已验证则自动跳过）
                - type: verify
                  target_step: identify
                
                # 3. 创建二次验证器
                - type: create_authenticator
                  one_of:
                    - authentication: secondary_oob_otp_email
                      target_step: identify
```

### 6.2 完整 HTTP 流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Google OAuth Signup 流程                             │
└─────────────────────────────────────────────────────────────────────────────┘

【Step 1: Identify】
POST /api/v1/authentication_flows
Request:
{
    "type": "signup",
    "name": "google_signup"
}

Response:
{
    "result": {
        "type": "identify",
        "options": [
            {
                "identification": "oauth",
                "alias": "google",
                "provider_type": "google",
                "provider_status": "active"
            }
        ]
    }
}

【Step 2: 选择 Google】
POST /api/v1/authentication_flows/states/{token}
Request:
{
    "identification": "oauth",
    "alias": "google",
    "redirect_uri": "http://localhost/callback"
}

Response:
{
    "result": {
        "type": "oauth",
        "oauth_authorization_url": "https://accounts.google.com/o/oauth2/..."
    }
}

【Step 3: Google 授权回调】
POST /api/v1/authentication_flows/states/{token}
Request:
{
    "oauth_authorization_response": {
        "code": "4/xxx",
        "state": "yyy"
    }
}

系统处理:
├─ NodeOAuth.ReactTo() 处理回调
├─ 获取 Google 用户信息: {email: "user@gmail.com", verified: true}
├─ 创建 OAuth Identity (email 标记为 verified 因 assume_verified: true)
└─ 进入 nested steps

【Step 4: fill_in_user_profile】⚠️ 始终触发
Response:
{
    "result": {
        "state_token": "state_token_aaa",
        "type": "fill_in_user_profile",
        "user_profile": {
            "items": [
                {
                    "pointer": "/email",
                    "required": true  // 预填充为 "user@gmail.com"
                }
            ]
        }
    }
}

// ⚠️ 注意：即使 Google 已返回 email，此步骤仍显示
// 用户需要确认或修改 email

【Step 5: 提交用户信息】
POST /api/v1/authentication_flows/states/state_token_aaa
Request:
{
    "attributes": [
        {
            "pointer": "/email",
            "value": "user@gmail.com"
        }
    ]
}

Response: 进入 verify 步骤

【Step 6: verify - 自动跳过】✅ 关键！
// IntentSignupFlowStepVerify.ReactTo() 内部逻辑：
// 1. 获取 OAuth Identity 的 claims: {email: "user@gmail.com"}
// 2. 调用 deps.Verification.GetClaimStatus(ctx, userID, "email", "user@gmail.com")
// 3. 发现 email 已验证 (因 Google 配置了 assume_verified: true)
// 4. 直接返回 NodeSentinel，跳过 OTP 验证

Response (直接进入下一步):
{
    "result": {
        "state_token": "state_token_bbb",
        "type": "create_authenticator",
        "options": [
            {
                "authentication": "secondary_oob_otp_email",
                "target": "user@gmail.com",
                "masked_display_name": "u***@gmail.com"
            }
        ]
    }
}

【Step 7: 创建二次验证器】
// IntentCreateAuthenticatorOOBOTP 执行：
// 1. target_step = "identify"
// 2. 调用 getCreateAuthenticatorOOBOTPTargetFromTargetStep("identify")
// 3. 从 IntentSignupFlowStepIdentify 获取 email
//    └─ GetOOBOTPClaims() → GetVerifiableClaims() → IdentityAwareStandardClaims()
//    └─ 返回: {email: "user@gmail.com"}

POST /api/v1/authentication_flows/states/state_token_bbb
Request:
{
    "authentication": "secondary_oob_otp_email"
}

Response (注册完成):
{
    "result": {
        "state_token": "state_token_bbb",
        "type": "finished",
        "finish_redirect_uri": "/oauth2/callback?code=auth_code&state=xxx",
        "finish_uri": "/settings"
    }
}
```

### 6.3 关键步骤行为对比


| 步骤                     | Google 有 email 时的行为          | 是否自动跳过     | 原因                                   |
| ---------------------- | ---------------------------- | ---------- | ------------------------------------ |
| `fill_in_user_profile` | 显示表单，预填充 Google email        | ❌ 不会跳过     | 源码中无任何检查 email 是否存在的逻辑               |
| `verify`               | **直接跳过** OTP 验证              | ✅ **自动跳过** | `assume_verified: true` 标记 email 已验证 |
| `create_authenticator` | 使用 target_step 的 email 创建验证器 | -          | 从 Identity 获取 email                  |


### 6.4 源码分析

#### fill_in_user_profile 为什么不会跳过？

```go
// pkg/lib/authenticationflow/declarative/intent_signup_flow_step_fill_in_user_profile.go:41-42
func (i *IntentSignupFlowStepFillInUserProfile) CanReactTo(...) {
    // 只要没有 Node 且不是更新现有用户，就返回 InputSchema
    if !i.IsUpdatingExistingUser && len(flows.Nearest.Nodes) == 0 {
        return &InputSchemaFillInUserProfile{
            Attributes: step.UserProfile,  // 直接使用配置，不检查 email 是否存在
        }, nil
    }
    return nil, authflow.ErrEOF
}
```

#### verify 为什么能跳过？

```go
// pkg/lib/authenticationflow/declarative/intent_signup_flow_step_verify.go:123-131
func (i *IntentSignupFlowStepVerify) ReactTo(...) {
    // ...
    claimStatus, err := deps.Verification.GetClaimStatus(ctx, i.UserID, claimName, claimValue)
    if err != nil {
        return nil, err
    }
    
    if claimStatus.Verified {  // ✅ Google 配置了 assume_verified: true
        // 已验证，直接结束此步骤
        return authflow.NewNodeSimple(&NodeSentinel{}), nil
    }
    // 未验证，继续 OTP 流程
}
```

### 6.5 用户体验总结


| 步骤           | 用户操作           | 界面显示          | 说明                        |
| ------------ | -------------- | ------------- | ------------------------- |
| 1. 选择 Google | 点击按钮           | OAuth 选项列表    | -                         |
| 2. Google 授权 | 跳转 Google 页面   | Google 登录/授权页 | -                         |
| 3. 补充信息      | **点击确认 email** | 预填充的 email 表单 | fill_in_user_profile 始终显示 |
| 4. 验证邮箱      | **无操作**        | 自动跳过          | 因 `assume_verified: true` |
| 5. 创建二次验证器   | 点击创建           | 二次验证器选项       | -                         |
| 6. 完成        | 自动跳转           | 完成页面          | -                         |


**实际效果**：Google 用户比 Apple 用户**少输入一次验证码**（verify 步骤跳过），但仍需**多点击一次**确认 email（fill_in_user_profile 步骤无法跳过）。

---

## 7. Apple OAuth 特殊处理

### 7.1 Apple Provider 配置

```go
// pkg/lib/oauthrelyingparty/apple/provider.go

const Type = "apple"

type ProviderConfig oauthrelyingparty.ProviderConfig

func (c ProviderConfig) TeamID() string {
    team_id, _ := c["team_id"].(string)
    return team_id
}

func (c ProviderConfig) KeyID() string {
    key_id, _ := c["key_id"].(string)
    return key_id
}

// Apple 特殊: 首次登录时用户信息只在 form post 中提供
type AuthorizationResponseFormField_user struct {
    Name  *AuthorizationResponseFormField_user_name `json:"name,omitempty"`
    Email string                                    `json:"email,omitempty"`
}

type AuthorizationResponseFormField_user_name struct {
    FirstName string `json:"firstName,omitempty"`
    LastName  string `json:"lastName,omitempty"`
}
```

### 7.2 Apple Email 处理逻辑

```go
func (p Apple) GetUserProfile(ctx context.Context, deps oauthrelyingparty.Dependencies, param oauthrelyingparty.GetUserProfileOptions) (authInfo oauthrelyingparty.UserProfile, err error) {
    // ... 获取 JWT Token 和 Claims
    
    // Apple 特殊处理: 从 form field 获取用户信息 (仅在首次授权时)
    user, userOK, err := p.getFormFieldUser(param.Query)
    if err != nil {
        return
    }
    if userOK && user != nil && user.Name != nil {
        if user.Name.FirstName != "" {
            claims[stdattrs.GivenName] = user.Name.FirstName
        }
        if user.Name.LastName != "" {
            claims[stdattrs.FamilyName] = user.Name.LastName
        }
    }
    
    // ... 提取 StandardAttributes
    emailRequired := deps.ProviderConfig.EmailClaimConfig().Required()
    stdAttrs, err := stdattrs.Extract(claims, stdattrs.ExtractOptions{
        EmailRequired: emailRequired,  // 根据配置决定是否必须
    })
    
    // 注意: Apple 可能不提供 email (用户选择隐藏)
    // 这时需要在 Flow 中使用 fill_in_user_profile 步骤补充
}
```

### 7.3 Apple Email 缺失处理策略

```
场景: Apple 用户选择隐藏 email
    │
    ▼
Apple 返回的 Claims 中没有 email
    │
    ▼
NodeOAuth.ReactTo() 创建 identity.Spec
    │
    └─ spec.StandardAttributes 中没有 email
    │
    ▼
进入 fill_in_user_profile Step (如果配置了)
    │
    ├─ 要求用户输入 email
    │
    ├─ 创建临时 identity (包含 email)
    │
    ▼
进入 verify Step
    │
    └─ 发送 OTP 到用户邮箱验证
    │
    ▼
验证通过后完成登录
```

---

## 8. fill_in_user_profile 实现详解

### 8.1 核心组件概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    fill_in_user_profile 实现架构                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────┐      ┌─────────────────────────────────────┐  │
│  │ IntentSignupFlowStep    │─────▶│ InputSchemaFillInUserProfile        │  │
│  │ FillInUserProfile       │      │ - 构建 JSON Schema                  │  │
│  │                         │      │ - 验证输入格式                      │  │
│  │ - CanReactTo()          │      │ - 区分 Standard/Custom Attributes │  │
│  │ - ReactTo()             │      │                                     │  │
│  └─────────────────────────┘      └─────────────────────────────────────┘  │
│           │                                                              │
│           ▼                                                              │
│  ┌─────────────────────────┐      ┌─────────────────────────────────────┐  │
│  │ InputFillInUserProfile  │─────▶│ NodeDoUpdateUserProfile             │  │
│  │                         │      │                                     │  │
│  │ - GetAttributes()       │      │ - 更新 StandardAttributes           │  │
│  │                         │      │ - 更新 CustomAttributes             │  │
│  └─────────────────────────┘      │ - Effect 持久化到数据库             │  │
│                                     └─────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Intent 实现

```go
// pkg/lib/authenticationflow/declarative/intent_signup_flow_step_fill_in_user_profile.go

type IntentSignupFlowStepFillInUserProfile struct {
    JSONPointer            jsonpointer.T `json:"json_pointer,omitempty"`
    StepName               string        `json:"step_name,omitempty"`
    UserID                 string        `json:"user_id,omitempty"`
    IsUpdatingExistingUser bool          `json:"skip_update,omitempty"`
}

// CanReactTo 返回 InputSchema，定义需要收集的用户属性
func (i *IntentSignupFlowStepFillInUserProfile) CanReactTo(
    ctx context.Context,
    deps *authflow.Dependencies,
    flows authflow.Flows,
) (authflow.InputSchema, error) {
    if !i.IsUpdatingExistingUser && len(flows.Nearest.Nodes) == 0 {
        // 从 Flow 配置中获取 user_profile 定义
        current, err := authflow.FlowObject(flowRootObject, i.JSONPointer)
        step := i.step(current)
        
        return &InputSchemaFillInUserProfile{
            JSONPointer:      i.JSONPointer,
            FlowRootObject:   flowRootObject,
            Attributes:       step.UserProfile,        // 配置中定义的 required 属性
            CustomAttributes: deps.Config.UserProfile.CustomAttributes.Attributes,
        }, nil
    }
    return nil, authflow.ErrEOF
}

// ReactTo 处理用户输入，创建 NodeDoUpdateUserProfile
func (i *IntentSignupFlowStepFillInUserProfile) ReactTo(
    ctx context.Context,
    deps *authflow.Dependencies,
    flows authflow.Flows,
    input authflow.Input,
) (authflow.ReactToResult, error) {
    var inputFillInUserProfile inputFillInUserProfile
    if authflow.AsInput(input, &inputFillInUserProfile) {
        // 验证输入属性是否符合配置要求
        attributes := inputFillInUserProfile.GetAttributes()
        allAbsent, err := i.validate(step, attributes)
        if err != nil {
            return nil, err
        }
        
        // 添加未提供但非必需的属性（标记为 absent）
        attributes = i.addAbsent(attributes, allAbsent)
        
        // 分离 Standard 和 Custom Attributes
        stdAttrs, customAttrs := i.separate(deps, attributes)
        
        // 创建更新节点
        return authflow.NewNodeSimple(&NodeDoUpdateUserProfile{
            UserID:             i.UserID,
            StandardAttributes: stdAttrs,
            CustomAttributes:   customAttrs,
        }), nil
    }
    return nil, authflow.ErrIncompatibleInput
}
```

### 8.3 Input Schema 与数据结构

```go
// pkg/lib/authenticationflow/declarative/input_fill_in_user_profile.go

// InputSchemaFillInUserProfile 定义输入验证 Schema
type InputSchemaFillInUserProfile struct {
    JSONPointer      jsonpointer.T
    FlowRootObject   config.AuthenticationFlowObject
    Attributes       []*config.AuthenticationFlowSignupFlowUserProfile
    CustomAttributes []*config.CustomAttributesAttributeConfig
}

// 构建 JSON Schema 用于验证用户输入
func (s *InputSchemaFillInUserProfile) SchemaBuilder() validation.SchemaBuilder {
    items := validation.SchemaBuilder{}.
        Type(validation.TypeObject).
        Required("pointer", "value")
    
    var pointerEnum []string
    var itemsAllOf []validation.SchemaBuilder
    
    for _, attribute := range s.Attributes {
        pointerEnum = append(pointerEnum, attribute.Pointer)
        
        // 根据 pointer 获取对应的 schema (标准属性或自定义属性)
        if stdAttrSchemaBuilder, ok := stdattrs.SchemaBuilderForPointerString(attribute.Pointer); ok {
            itemsAllOf = append(itemsAllOf, s.buildItemsSchema(attribute.Pointer, stdAttrSchemaBuilder))
        } else if cfg, ok := m[attribute.Pointer]; ok {
            customAttrSchemaBuilder, _ := cfg.ToSchemaBuilder()
            itemsAllOf = append(itemsAllOf, s.buildItemsSchema(attribute.Pointer, customAttrSchemaBuilder))
        }
        
        // 标记 required 属性
        if attribute.Required {
            allOfContains = append(allOfContains, s.buildContainsSchema(attribute.Pointer))
        }
    }
    
    return b
}

// InputFillInUserProfile 是实际的输入数据结构
type InputFillInUserProfile struct {
    Attributes []attrs.T `json:"attributes,omitempty"`
}

func (i *InputFillInUserProfile) GetAttributes() []attrs.T {
    return i.Attributes
}

// attrs.T 是单个属性的定义 (pkg/lib/authn/attrs/attrs.go)
type T struct {
    Pointer string      `json:"pointer"`      // 属性路径，如 "/email"
    Value   interface{} `json:"value,omitempty"` // 属性值
}
```

### 8.4 Node 实现 - 持久化用户资料

```go
// pkg/lib/authenticationflow/declarative/node_do_update_user_profile.go

type NodeDoUpdateUserProfile struct {
    UserID             string     `json:"user_id,omitempty"`
    SkipUpdate         bool       `json:"skip_update,omitempty"`
    StandardAttributes attrs.List `json:"standard_attributes,omitempty"`
    CustomAttributes   attrs.List `json:"custom_attributes,omitempty"`
}

// GetEffects 返回 Effect，实际执行数据库更新
func (n *NodeDoUpdateUserProfile) GetEffects(
    ctx context.Context,
    deps *authflow.Dependencies,
    flows authflow.Flows,
) (effs []authflow.Effect, err error) {
    return []authflow.Effect{
        authflow.RunEffect(func(ctx context.Context, deps *authflow.Dependencies) error {
            if n.SkipUpdate {
                return nil
            }
            
            // 更新标准属性 (email, name, given_name 等)
            err := deps.StdAttrsService.UpdateStandardAttributesWithList(
                ctx,
                config.RoleEndUser,
                n.UserID,
                n.StandardAttributes,
            )
            if err != nil {
                return err
            }
            
            // 更新自定义属性
            err = deps.CustomAttrsService.UpdateCustomAttributesWithList(
                ctx,
                config.RoleEndUser,
                n.UserID,
                n.CustomAttributes,
            )
            if err != nil {
                return err
            }
            
            return nil
        }),
    }, nil
}
```

### 8.5 HTTP 请求/响应格式详解

#### 7.5.1 请求 fill_in_user_profile 数据

```http
POST /api/v1/authentication_flows/states/{state_token}
Content-Type: application/json

// Response 展示需要收集的属性
{
    "result": {
        "state_token": "state_token_xxx",
        "type": "fill_in_user_profile",
        "user_profile": {
            "items": [
                {
                    "pointer": "/email",
                    "required": true
                },
                {
                    "pointer": "/given_name",
                    "required": false
                }
            ]
        }
    }
}
```

#### 7.5.2 提交用户资料

```http
POST /api/v1/authentication_flows/states/{state_token}
Content-Type: application/json

// Request 使用 attributes 数组格式
{
    "attributes": [
        {
            "pointer": "/email",
            "value": "user@example.com"
        },
        {
            "pointer": "/given_name",
            "value": "John"
        }
    ]
}

// 或使用简化的 user_profile 格式（取决于 SDK 版本）
{
    "user_profile": {
        "email": "user@example.com",
        "given_name": "John"
    }
}
```

#### 7.5.3 验证失败响应

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
    "error": {
        "code": "invalid",
        "message": "invalid request body",
        "reason": "ValidationFailed",
        "info": {
            "causes": [
                {
                    "location": "(root).attributes[0].value",
                    "kind": "format",
                    "details": "must be a valid email address"
                }
            ]
        }
    }
}
```

### 8.6 配置详解

```yaml
# authgear.yaml 中的 user_profile 配置

authentication_flow:
  signup_flows:
    - name: with_fill_in_profile
      steps:
        - type: identify
          one_of:
            - identification: oauth
              steps:
                - type: fill_in_user_profile
                  user_profile:
                    # 标准属性指针
                    - pointer: /email
                      required: true
                    - pointer: /given_name
                      required: false
                    - pointer: /family_name
                      required: false
                    - pointer: /name
                      required: false
                    
                    # 自定义属性指针（需在 user_profile.custom_attributes 中定义）
                    - pointer: /custom/department
                      required: true

# 自定义属性定义
user_profile:
  custom_attributes:
    attributes:
      - id: "department"
        pointer: "/custom/department"
        type: "string"
        minimum: 1
        maximum: 100
```

### 8.7 标准属性指针参考


| 指针                        | 类型      | 说明        | 示例                               |
| ------------------------- | ------- | --------- | -------------------------------- |
| `/email`                  | string  | 邮箱地址      | `user@example.com`               |
| `/email_verified`         | boolean | 邮箱是否已验证   | `true`                           |
| `/phone_number`           | string  | 电话号码      | `+852-91234567`                  |
| `/phone_number_verified`  | boolean | 电话是否已验证   | `true`                           |
| `/name`                   | string  | 全名        | `John Doe`                       |
| `/given_name`             | string  | 名         | `John`                           |
| `/family_name`            | string  | 姓         | `Doe`                            |
| `/middle_name`            | string  | 中间名       | `William`                        |
| `/nickname`               | string  | 昵称        | `Johnny`                         |
| `/preferred_username`     | string  | 首选用户名     | `johndoe`                        |
| `/profile`                | string  | 个人资料页 URL | `https://example.com/johndoe`    |
| `/picture`                | string  | 头像 URL    | `https://example.com/avatar.jpg` |
| `/website`                | string  | 网站 URL    | `https://johndoe.com`            |
| `/gender`                 | string  | 性别        | `male`, `female`                 |
| `/birthdate`              | string  | 出生日期      | `1990-01-01`                     |
| `/zoneinfo`               | string  | 时区        | `Asia/Hong_Kong`                 |
| `/locale`                 | string  | 语言区域      | `zh-HK`                          |
| `/address`                | object  | 地址对象      | -                                |
| `/address/formatted`      | string  | 格式化地址     | `1 Example Street`               |
| `/address/street_address` | string  | 街道地址      | `1 Example Street`               |
| `/address/locality`       | string  | 城市        | `Hong Kong`                      |
| `/address/region`         | string  | 省份/州      | -                                |
| `/address/postal_code`    | string  | 邮编        | `123456`                         |
| `/address/country`        | string  | 国家        | `HK`                             |


### 8.8 文件位置汇总


| 组件           | 文件路径                                                                                     |
| ------------ | ---------------------------------------------------------------------------------------- |
| Intent 实现    | `pkg/lib/authenticationflow/declarative/intent_signup_flow_step_fill_in_user_profile.go` |
| Input Schema | `pkg/lib/authenticationflow/declarative/input_fill_in_user_profile.go`                   |
| Node 实现      | `pkg/lib/authenticationflow/declarative/node_do_update_user_profile.go`                  |
| 属性定义         | `pkg/lib/authn/attrs/attrs.go`                                                           |
| 标准属性         | `pkg/lib/authn/stdattrs/stdattrs.go`                                                     |


---

## 9. 核心代码参考

### 9.1 OAuth Intent 和 Node

```go
// pkg/lib/authenticationflow/declarative/intent_oauth.go

type IntentOAuth struct {
    JSONPointer    jsonpointer.T                          `json:"json_pointer,omitempty"`
    NewUserID      string                                 `json:"new_user_id,omitempty"`
    Identification model.AuthenticationFlowIdentification `json:"identification,omitempty"`
}

// CanReactTo 返回 OAuth 授权 URL
func (i *IntentOAuth) CanReactTo(...) (authflow.InputSchema, error) {
    oauthOptions := NewIdentificationOptionsOAuth(...)
    return &InputSchemaTakeOAuthAuthorizationRequest{
        OAuthOptions: oauthOptions,
        // ...
    }, nil
}

// ReactTo 处理用户选择，创建 NodeOAuth
func (i *IntentOAuth) ReactTo(...) (authflow.ReactToResult, error) {
    alias := inputOAuth.GetOAuthAlias()
    redirectURI := inputOAuth.GetOAuthRedirectURI()
    
    return authflow.NewNodeSimple(&NodeOAuth{
        Alias:        alias,
        RedirectURI:  redirectURI,
        ResponseMode: responseMode,
    }), nil
}
```

### 9.2 NodeOAuth 处理回调

```go
// pkg/lib/authenticationflow/declarative/node_oauth.go

type NodeOAuth struct {
    JSONPointer  jsonpointer.T `json:"json_pointer,omitempty"`
    NewUserID    string        `json:"new_user_id,omitempty"`
    Alias        string        `json:"alias,omitempty"`
    RedirectURI  string        `json:"redirect_uri,omitempty"`
    ResponseMode string        `json:"response_mode,omitempty"`
}

// OutputData 返回 OAuth 授权页面 URL
func (n *NodeOAuth) OutputData(...) (authflow.Data, error) {
    data, err := getOAuthData(ctx, deps, GetOAuthDataOptions{
        RedirectURI:  n.RedirectURI,
        Alias:        n.Alias,
        ResponseMode: n.ResponseMode,
    })
    return data, nil
}

// ReactTo 处理 OAuth 回调
func (n *NodeOAuth) ReactTo(...) (authflow.ReactToResult, error) {
    // 处理授权响应
    spec, err := handleOAuthAuthorizationResponse(ctx, deps, ...)
    
    // 注册场景
    if n.NewUserID != "" {
        return authflow.NewSubFlow(&IntentCheckConflictAndCreateIdenity{
            UserID:  n.NewUserID,
            Request: NewCreateOAuthIdentityRequest(spec),
        }), nil
    }
    
    // 登录场景
    exactMatch, err := findExactOneIdentityInfo(ctx, deps, spec)
    return NewNodeDoUseIdentityWithUpdate(ctx, deps, flows, exactMatch, spec)
}
```

### 9.3 Identification 选项数据结构

```go
// pkg/lib/authenticationflow/declarative/data_identification.go

type IdentificationData struct {
    TypedData
    Options []IdentificationOption `json:"options"`
}

type IdentificationOption struct {
    // 通用字段
    Identification model.AuthenticationFlowIdentification `json:"identification"`
    BotProtection *BotProtectionData `json:"bot_protection,omitempty"`
    
    // OAuth 特定
    ProviderType string `json:"provider_type,omitempty"`    // "google", "apple"
    Alias string `json:"alias,omitempty"`                   // Provider 别名
    ProviderStatus OAuthProviderStatus `json:"provider_status,omitempty"`
    
    // Passkey 特定
    RequestOptions *model.WebAuthnRequestOptions `json:"request_options,omitempty"`
    
    // LDAP 特定
    ServerName string `json:"server_name,omitempty"`
}
```

### 9.4 配置结构定义

```go
// pkg/lib/config/authentication_flow.go

// Login Flow 配置
type AuthenticationFlowLoginFlow struct {
    Name  string                              `json:"name"`
    Steps []*AuthenticationFlowLoginFlowStep  `json:"steps"`
}

// Login Flow Step 配置
type AuthenticationFlowLoginFlowStep struct {
    Name string                             `json:"name,omitempty"`
    Type AuthenticationFlowStepType         `json:"type"`
    OneOf []*AuthenticationFlowLoginFlowIdentify `json:"one_of,omitempty"`
}

// Identify 分支配置
type AuthenticationFlowLoginFlowIdentify struct {
    Identification AuthenticationFlowIdentification      `json:"identification"`
    BotProtection *AuthenticationFlowBotProtection     `json:"bot_protection,omitempty"`
    Steps []*AuthenticationFlowLoginFlowStep            `json:"steps,omitempty"`  // nested steps
}

// Authenticate 分支配置
type AuthenticationFlowLoginFlowAuthenticate struct {
    Authentication AuthenticationFlowAuthentication    `json:"authentication"`
    BotProtection *AuthenticationFlowBotProtection     `json:"bot_protection,omitempty"`
    TargetStep string                                  `json:"target_step,omitempty"`
    Steps []*AuthenticationFlowLoginFlowStep            `json:"steps,omitempty"`
}
```

---

## 总结

### 关键设计要点

1. **多 OAuth Provider 支持**: 通过 `OAuthSSOConfig.Providers` 数组配置多个 Provider，每个 Provider 有独立的 `alias` 和 `type`
2. **动态选项生成**: `NewIdentificationOptionsOAuth()` 遍历配置的所有 Provider，为每个可用 Provider 生成一个 `IdentificationOption`
3. **Signup Flow vs Login Flow**:
  - **Signup Flow** (注册): 使用 `identify` → `fill_in_user_profile` → `verify` → `create_authenticator`
  - **Login Flow** (登录): 使用 `identify` → `authenticate`
  - 两者支持的 step 类型不同，不可混用
4. **Nested Steps**: OAuth identify 完成后可以继续执行 nested steps
  - Signup Flow: 信息补充、邮箱验证、创建验证器
  - Login Flow: 二次身份验证
5. **Apple 特殊处理**:
  - 可能不提供 email (用户选择隐藏)
  - 首次登录时用户信息在 form post 中
  - **必须在 Signup Flow** 中配置 `fill_in_user_profile` 和 `verify` 步骤处理缺失 email
  - Login Flow 不支持这些步骤
6. **配置限制 - 无法区分 OAuth Provider**:
  - Flow 配置中只有通用的 `identification: oauth`，不区分具体 Provider
  - 所有 OAuth Provider (Google、Apple 等) **共用同一套 nested steps**
  - 如果配置了 `fill_in_user_profile`，**所有** OAuth 用户在未获取 email 时都会被要求补充
  - 运行时通过 HTTP 请求中的 `alias` 字段选择具体 Provider
7. **状态机驱动**: Intent 和 Node 通过 `CanReactTo` 和 `ReactTo` 方法实现状态转换，Milestone 用于跟踪关键状态点

### 配置注意事项


| 配置项         | 正确写法                                         | 常见错误                              |
| ----------- | -------------------------------------------- | --------------------------------- |
| 二次验证模式      | `secondary_authentication_mode: required`    | ❌ `secondary_authentication.mode` |
| Apple 无邮箱处理 | 在 **Signup Flow** 中使用 `fill_in_user_profile` | ❌ 在 Login Flow 中使用                |
| 邮箱验证        | 在 **Signup Flow** 中使用 `verify` step          | ❌ 在 Login Flow 中使用                |
| 创建验证器       | 在 Signup Flow 中使用 `create_authenticator`     | -                                 |
| 验证身份        | 在 Login Flow 中使用 `authenticate`              | -                                 |
| Provider 选择 | HTTP 请求中使用 `"alias": "google"`               | ❌ 配置中区分不同 Provider                |


### OAuth Provider 选择机制

**配置层面** (YAML):

```yaml
identity:
  oauth:
    providers:
      - alias: google  # 配置多个 Provider
        type: google
      - alias: apple
        type: apple

authentication_flow:
  login_flows:
    - steps:
        - type: identify
          one_of:
            - identification: oauth  # 所有 Provider 共用此配置
```

**运行时层面** (HTTP API):

```json
// 创建 Flow 时返回所有 Provider 选项
{
    "options": [
        {"identification": "oauth", "alias": "google", "provider_type": "google"},
        {"identification": "oauth", "alias": "apple", "provider_type": "apple"}
    ]
}

// 用户选择时通过 alias 指定具体 Provider
{
    "identification": "oauth",
    "alias": "apple"
}
```

### 文件位置汇总


| 功能                              | 文件路径                                                                                     |
| ------------------------------- | ---------------------------------------------------------------------------------------- |
| OAuth Provider 配置               | `pkg/lib/config/identity.go`                                                             |
| Authentication 配置               | `pkg/lib/config/authentication.go`                                                       |
| Signup/Login Flow 配置            | `pkg/lib/config/authentication_flow.go`                                                  |
| Identification 选项               | `pkg/lib/authenticationflow/declarative/data_identification.go`                          |
| OAuth Intent                    | `pkg/lib/authenticationflow/declarative/intent_oauth.go`                                 |
| OAuth Node                      | `pkg/lib/authenticationflow/declarative/node_oauth.go`                                   |
| Login Flow Step Identify        | `pkg/lib/authenticationflow/declarative/intent_login_flow_step_identify.go`              |
| Signup Flow Step Identify       | `pkg/lib/authenticationflow/declarative/intent_signup_flow_step_identify.go`             |
| **fill_in_user_profile Intent** | `pkg/lib/authenticationflow/declarative/intent_signup_flow_step_fill_in_user_profile.go` |
| **fill_in_user_profile Input**  | `pkg/lib/authenticationflow/declarative/input_fill_in_user_profile.go`                   |
| **User Profile 更新 Node**        | `pkg/lib/authenticationflow/declarative/node_do_update_user_profile.go`                  |
| **属性定义 (attrs.T)**              | `pkg/lib/authn/attrs/attrs.go`                                                           |
| Apple Provider                  | `pkg/lib/oauthrelyingparty/apple/provider.go`                                            |
| Identity 类型定义                   | `pkg/api/model/identity.go`                                                              |
| Identification 类型               | `pkg/api/model/identification.go`                                                        |


