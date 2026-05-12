# DataOutputer 实现汇总

## 1. 实际代码中的接口定义

### 1.1 DataOutputer 接口（pkg/lib/authenticationflow/output.go:15-18）

```go
// DataOutputer is an InputReactor.
// The data it outputs allow the caller to proceed.
type DataOutputer interface {
    InputReactor
    OutputData(ctx context.Context, deps *Dependencies, flows Flows) (Data, error)
}
```

### 1.2 Data 标记接口（pkg/lib/authenticationflow/output.go:9-11）

```go
// Data is a marker.
// Ensure all data is a struct, not an ad-hoc map.
type Data interface {
    Data()
}
```

---

## 2. DataOutputer 在代码中的实际位置

### 2.1 调用链位置（基于 pkg/lib/authenticationflow/service.go）

以下示意图展示了 DataOutputer 在实际代码中的调用位置：

```
HTTP Handler (pkg/auth/handler/api/authenticationflow_v1.go)
    │
    ▼
Service.CreateNewFlow() / Service.FeedInput() / Service.GetFlowAction()
    │
    ▼
getFlowAction(ctx, session, flow)  [service.go:624]
    │
    ├──▶ FindInputReactor(ctx, deps, flows)  [service.go:625]
    │          │
    │          ▼
    │    返回 findInputReactorResult
    │          ├── InputReactor (Intent/Node 实现)
    │          ├── InputSchema
    │          └── Flows
    │
    └──▶ 类型断言检查 DataOutputer 接口 [service.go:665]
             │
             ▼
    if dataOutputer, ok := findInputReactorResult.InputReactor.(DataOutputer); ok {
        data, err = dataOutputer.OutputData(ctx, s.Deps, findInputReactorResult.Flows)  [service.go:666]
    }
             │
             ▼
    flowAction.Data = data  [service.go:675]
             │
             ▼
    返回 FlowAction → 包装为 FlowResponse → JSON 序列化
```

### 2.2 调用链路说明

DataOutputer 位于 **Authentication Flow Service** 的核心处理流程中：

1. **HTTP Handler** 接收客户端请求（如创建流程、提交输入）
2. **Service Layer** 调用 `GetFlowAction()` 或 `feedInput()` 处理流程状态
3. **FindInputReactor** 查找当前需要处理输入的 Intent 或 Node
4. **DataOutputer** 如果该 Intent/Node 实现了 DataOutputer 接口，调用 `OutputData()` 获取数据
5. **FlowAction** 将数据包装到 `FlowAction.Data` 字段
6. **FlowResponse** 序列化为 JSON 返回给客户端

---

## 3. 调用时机（代码实际调用点）

### 3.1 OutputData() 的实际调用位置

DataOutputer.OutputData() 仅在 `pkg/lib/authenticationflow/service.go:getFlowAction()` 函数中被调用。

#### 3.1.1 创建新流程时（service.go:83-103）
```
Service.CreateNewFlow()
  └─> createNewFlowWithSession()
       └─> createNewFlow()
            └─> getFlowAction() [service.go:221] ◄── 调用 OutputData()
```

#### 3.1.2 提交输入时（service.go:306-387）
```
Service.FeedInput()
  └─> feedInput()
       └─> Accept()
            └─> getFlowAction() [service.go:508] ◄── 调用 OutputData()
```

#### 3.1.3 获取流程状态时（service.go:624-679）
```
Service.GetFlowAction()
  └─> FindInputReactor() [service.go:625]
  └─> 类型断言并调用 OutputData() [service.go:665-669]
```

### 3.2 调用条件（service.go:665-669）

```go
var data Data
if dataOutputer, ok := findInputReactorResult.InputReactor.(DataOutputer); ok {
    data, err = dataOutputer.OutputData(ctx, s.Deps, findInputReactorResult.Flows)
    if err != nil {
        return nil, err
    }
}
if data == nil {
    data = mapData{}
}
if flowAction != nil {
    flowAction.Data = data
}
```

即：**当前激活的 Intent 或 Node 必须实现了 DataOutputer 接口**，否则返回空的 `mapData{}`

---

## 4. 数据返回给客户端的完整流程

### 4.1 数据流向（基于实际代码结构）

```
DataOutputer.OutputData()
    │ 返回 Data (如 IdentificationData)
    ▼
┌─────────────────────────────────┐
│  service.go:671-676              │
│  if data == nil {                │
│      data = mapData{}            │
│  }                               │
│  if flowAction != nil {          │
│      flowAction.Data = data      │
│  }                               │
└────────┬────────────────────────┘
         ▼
┌─────────────────────────────────┐
│  FlowAction (flow.go:60-65)      │
│  type FlowAction struct {        │
│      Type   FlowActionType       │
│      Data   Data                 │
│      ...                         │
│  }                               │
└────────┬────────────────────────┘
         ▼
┌─────────────────────────────────┐
│  ServiceOutput (service.go:21)   │
│  type ServiceOutput struct {     │
│      FlowAction *FlowAction      │
│      ...                         │
│  }                               │
└────────┬────────────────────────┘
         │ 调用 ToFlowResponse()
         ▼
┌─────────────────────────────────┐
│  service.go:33-40                │
│  func (o *ServiceOutput)           │
│      ToFlowResponse()            │
│  return FlowResponse{             │
│      StateToken: o.Flow.StateToken│
│      Type: o.FlowReference.Type  │
│      Action: o.FlowAction        │
│  }                               │
└────────┬────────────────────────┘
         ▼
┌─────────────────────────────────┐
│  FlowResponse (flow.go:68-73)  │
│  type FlowResponse struct {      │
│      StateToken string           │
│      Type       FlowType         │
│      Action     *FlowAction      │
│  }                               │
└────────┬────────────────────────┘
         │ JSON.Marshal()
         ▼
    HTTP Response
```

### 4.2 实际 API 响应示例（对应 FlowResponse 结构）

基于 `flow.go:68-73` 的 FlowResponse 结构：

```go
type FlowResponse struct {
    StateToken string      `json:"state_token"`
    Type       FlowType    `json:"type,omitempty"`
    Name       string      `json:"name,omitempty"`
    Action     *FlowAction `json:"action,omitempty"`
}
```

### 4.2.1 创建注册流程

**Request:**
```http
POST /api/v1/authentication_flows
Content-Type: application/json

{
  "type": "signup",
  "name": "default"
}
```

**Response:**
```json
{
  "state_token": "authflow_TJSAV0F58G8VBWREZ22YBMAW1A0GFCD4",
  "type": "signup",
  "name": "default",
  "action": {
    "type": "identify",
    "identification": "",
    "authentication": "",
    "data": {
      "type": "identification-data",
      "options": [
        {
          "identification": "email",
          "bot_protection": {
            "enabled": true,
            "provider": "recaptchav2"
          }
        }
      ]
    }
  }
}
```

说明：
- `state_token` → FlowResponse.StateToken
- `type` → FlowResponse.Type
- `name` → FlowResponse.Name
- `action` → FlowResponse.Action (FlowAction 结构)
- `action.data` → FlowAction.Data (DataOutputer.OutputData 返回的数据)

### 4.2.2 提交邮箱后继续

**Request:**
```http
POST /api/v1/authentication_flows/{state_token}/input
Content-Type: application/json

{
  "identification": "email",
  "login_id": "user@example.com"
}
```

**Response:**
```json
{
  "state_token": "authflow_TJSAV0F58G8VBWREZ22YBMAW1A0GFCD4",
  "type": "signup",
  "action": {
    "type": "create_authenticator",
    "data": {
      "type": "create-authenticator-data",
      "options": [
        {
          "authentication": "primary_password",
          "bot_protection": {
            "enabled": false
          }
        }
      ]
    }
  }
}
```

---

## 5. 实现列表

### 5.1 Intent 实现（按流程类型分组）

#### 5.1.1 Signup Flow 相关

##### 5.1.1.1 IntentSignupFlowStepViewRecoveryCode
- **功能**：显示恢复码
- **输出数据**：`IntentSignupFlowStepViewRecoveryCodeData`
  - `RecoveryCodes []string` - 恢复码列表

**Use Case Example:**
```json
{
  "type": "view-recovery-code-data",
  "recovery_codes": [
    "AAAA-BBBB-CCCC-DDDD",
    "EEEE-FFFF-GGGG-HHHH",
    "IIII-JJJJ-KKKK-LLLL",
    "MMMM-NNNN-OOOO-PPPP"
  ]
}
```

---

##### 5.1.1.2 IntentSignupFlowStepIdentify
- **功能**：注册流程的身份识别步骤，提供识别选项（邮箱、手机、用户名、OAuth、LDAP）
- **输出数据**：`IdentificationData`
  - `Options []IdentificationOption` - 可用的识别选项列表

**Use Case Example:**
```json
{
  "type": "identification-data",
  "options": [
    {
      "identification": "email",
      "bot_protection": {
        "enabled": true,
        "provider": "recaptchav2"
      }
    },
    {
      "identification": "oauth",
      "alias": "google",
      "provider_type": "google",
      "display_id": "Google",
      "provider_status": "enabled"
    }
  ]
}
```

---

##### 5.1.1.3 IntentSignupFlowStepCreateAuthenticator
- **功能**：注册流程中创建认证器步骤
- **输出数据**：`CreateAuthenticatorData`
  - `Options []CreateAuthenticatorOptionForOutput` - 可用的认证器创建选项

**Use Case Example:**
```json
{
  "type": "create-authenticator-data",
  "options": [
    {
      "authentication": "primary_password",
      "bot_protection": {
        "enabled": false
      }
    },
    {
      "authentication": "secondary_totp",
      "bot_protection": {
        "enabled": false
      }
    }
  ]
}
```

---

#### 5.1.2 Login Flow 相关

##### 5.1.2.1 IntentLoginFlowStepIdentify
- **功能**：登录流程的身份识别步骤
- **输出数据**：`IdentificationData`
  - `Options []IdentificationOption` - 可用的识别选项

**Use Case Example:**
```json
{
  "type": "identification-data",
  "options": [
    {
      "identification": "email",
      "bot_protection": {
        "enabled": true,
        "provider": "recaptchav2"
      }
    },
    {
      "identification": "passkey",
      "request_options": {
        "challenge": "base64encodedChallenge...",
        "rp_id": "example.com"
      }
    }
  ]
}
```

---

##### 5.1.2.2 IntentLoginFlowStepAuthenticate
- **功能**：登录流程的认证步骤
- **输出数据**：`StepAuthenticateData`
  - `Options []AuthenticateOptionForOutput` - 可用的认证选项
  - `DeviceTokenEnabled bool` - 是否启用设备令牌

**Use Case Example:**
```json
{
  "type": "step-authenticate-data",
  "options": [
    {
      "authentication": "primary_password",
      "masked_display_name": "user@example.com"
    },
    {
      "authentication": "secondary_totp"
    }
  ],
  "device_token_enabled": true
}
```

---

##### 5.1.2.3 IntentLoginFlowStepCreateAuthenticator
- **功能**：登录流程中创建次级认证器步骤
- **输出数据**：`CreateAuthenticatorData`
  - `Options []CreateAuthenticatorOptionForOutput` - 可用的次级认证器创建选项

**Use Case Example:**
```json
{
  "type": "create-authenticator-data",
  "options": [
    {
      "authentication": "secondary_totp"
    },
    {
      "authentication": "secondary_oob_otp_sms",
      "channel": "sms",
      "masked_display_name": "+1***1234"
    }
  ]
}
```

---

#### 5.1.3 Reauth Flow 相关

##### 5.1.3.1 IntentReauthFlowStepIdentify
- **功能**：重新认证流程的身份识别步骤（仅支持 ID Token）
- **输出数据**：`IdentificationData`

**Use Case Example:**
```json
{
  "type": "identification-data",
  "options": [
    {
      "identification": "id_token"
    }
  ]
}
```

---

##### 5.1.3.2 IntentReauthFlowStepAuthenticate
- **功能**：重新认证流程的认证步骤
- **输出数据**：`StepAuthenticateData`

**Use Case Example:**
```json
{
  "type": "step-authenticate-data",
  "options": [
    {
      "authentication": "primary_password",
      "masked_display_name": "user@example.com"
    }
  ]
}
```

---

#### 5.1.4 Signup/Login Flow 相关

##### 5.1.4.1 IntentSignupLoginFlowStepIdentify
- **功能**：注册/登录合并流程的身份识别步骤
- **输出数据**：`IdentificationData`

**Use Case Example:**
```json
{
  "type": "identification-data",
  "options": [
    {
      "identification": "email"
    },
    {
      "identification": "oauth",
      "alias": "google",
      "signup_flow": "signup",
      "login_flow": "login"
    }
  ]
}
```

---

#### 5.1.5 Promote Flow 相关

##### 5.1.5.1 IntentPromoteFlowStepIdentify
- **功能**：匿名用户升级流程的身份识别步骤
- **输出数据**：`IdentificationData`

**Use Case Example:**
```json
{
  "type": "identification-data",
  "options": [
    {
      "identification": "email"
    }
  ]
}
```

---

#### 5.1.6 Account Recovery Flow 相关

##### 5.1.6.1 IntentAccountRecoveryFlowStepIdentify
- **功能**：账户恢复流程的身份识别步骤
- **输出数据**：`IntentAccountRecoveryFlowStepIdentifyData`

**Use Case Example:**
```json
{
  "type": "account-recovery-identification-data",
  "options": [
    {
      "identification": "email"
    },
    {
      "identification": "phone"
    }
  ]
}
```

---

##### 5.1.6.2 IntentAccountRecoveryFlowStepSelectDestination
- **功能**：选择账户恢复验证码发送目的地
- **输出数据**：`IntentAccountRecoveryFlowStepSelectDestinationData`

**Use Case Example:**
```json
{
  "type": "account-recovery-select-destination-data",
  "options": [
    {
      "masked_display_name": "us***@example.com",
      "channel": "email",
      "otp_form": "code"
    }
  ]
}
```

---

##### 5.1.6.3 IntentAccountRecoveryFlowStepVerifyAccountRecoveryCode
- **功能**：验证账户恢复验证码
- **输出数据**：`IntentAccountRecoveryFlowStepVerifyAccountRecoveryCodeData`

**Use Case Example:**
```json
{
  "type": "account-recovery-verify-code-data",
  "masked_display_name": "us***@example.com",
  "channel": "email",
  "otp_form": "code",
  "code_length": 6,
  "can_resend_at": "2026-05-05T12:55:00Z"
}
```

---

##### 5.1.6.4 IntentAccountRecoveryFlowStepResetPassword
- **功能**：重置密码步骤
- **输出数据**：`NewPasswordData`

**Use Case Example:**
```json
{
  "type": "new-password-data",
  "password_policy": {
    "min_length": 8,
    "min_uppercase": 1,
    "min_lowercase": 1,
    "min_digit": 1
  },
  "masked_target": "us***@example.com"
}
```

---

#### 5.1.7 认证相关 Intents

##### 5.1.7.1 IntentAuthenticationOOB
- **功能**：OOB（Out-of-Band）OTP 认证流程
- **输出数据**：`SelectOOBOTPChannelsData`

**Use Case Example:**
```json
{
  "type": "select-oob-otp-channels-data",
  "channels": ["email", "sms"],
  "masked_claim_value": "us***@example.com"
}
```

---

##### 5.1.7.2 IntentVerifyClaim
- **功能**：验证声明（Claim Verification）
- **输出数据**：`SelectOOBOTPChannelsData`

**Use Case Example:**
```json
{
  "type": "select-oob-otp-channels-data",
  "channels": ["email", "sms"],
  "masked_claim_value": "+1***1234"
}
```

---

##### 5.1.7.3 IntentCreateAuthenticatorTOTP
- **功能**：创建 TOTP 认证器
- **输出数据**：`IntentCreateAuthenticatorTOTPData`

**Use Case Example:**
```json
{
  "type": "create-totp-data",
  "secret": "JBSWY3DPEHPK3PXP",
  "otpauth_uri": "otpauth://totp/Example:user@example.com?secret=..."
}
```

---

##### 5.1.7.4 IntentAccountLinking
- **功能**：处理账户关联冲突
- **输出数据**：`AccountLinkingIdentifyData`

**Use Case Example:**
```json
{
  "type": "account-linking-identify-data",
  "options": [
    {
      "identification": "email",
      "masked_display_name": "ex***@example.com",
      "action": "login_and_link"
    }
  ]
}
```

---

### 5.2 Node 实现

#### 5.2.1 OAuth 相关 Nodes

##### 5.2.1.1 NodeOAuth
- **功能**：处理 OAuth 注册/登录授权响应
- **输出数据**：`OAuthData`

**Use Case Example:**
```json
{
  "type": "oauth-data",
  "alias": "google",
  "state": "randomStateString...",
  "oauth_uri": "https://accounts.google.com/o/oauth2/v2/auth?client_id=...",
  "response_mode": "query"
}
```

---

##### 5.2.1.2 NodeLookupIdentityOAuth
- **功能**：查找 OAuth 身份（用于 Signup/Login 流程）
- **输出数据**：`OAuthData`

---

##### 5.2.1.3 NodePromoteIdentityOAuth
- **功能**：处理 OAuth 身份升级（匿名用户升级）
- **输出数据**：`OAuthData`

---

#### 5.2.2 认证相关 Nodes

##### 5.2.2.1 NodeAuthenticationOOB
- **功能**：OOB OTP 认证节点
- **输出数据**：`VerifyOOBOTPData`

**Use Case Example:**
```json
{
  "type": "verify-oob-otp-data",
  "channel": "email",
  "otp_form": "code",
  "masked_claim_value": "us***@example.com",
  "code_length": 6,
  "can_resend_at": "2026-05-05T12:55:00Z",
  "delivery_status": "delivered"
}
```

---

##### 5.2.2.2 NodeVerifyClaim
- **功能**：验证声明节点（发送并验证 OTP）
- **输出数据**：`VerifyOOBOTPData`

**Use Case Example:**
```json
{
  "type": "verify-oob-otp-data",
  "channel": "sms",
  "otp_form": "code",
  "websocket_url": "wss://ws.example.com/v1/websocket?channel=...",
  "masked_claim_value": "+1***5678",
  "can_resend_at": "2026-05-05T12:52:30Z",
  "can_check": true
}
```

---

#### 5.2.3 密码相关 Nodes

##### 5.2.3.1 NodeLoginFlowChangePassword
- **功能**：登录流程中强制修改密码
- **输出数据**：`ForceChangePasswordData`

**Use Case Example - Password Expired:**
```json
{
  "type": "force-change-password-data",
  "password_policy": {
    "min_length": 12,
    "min_uppercase": 1,
    "min_lowercase": 1,
    "min_digit": 1,
    "min_symbol": 1
  },
  "force_change_reason": "expiry"
}
```

---

#### 5.2.4 Passkey 相关 Nodes

##### 5.2.4.1 NodePromptCreatePasskey
- **功能**：提示创建 Passkey（登录后的 Upsell）
- **输出数据**：`NodePromptCreatePasskeyData`

**Use Case Example:**
```json
{
  "type": "create-passkey-data",
  "creation_options": {
    "rp": {
      "name": "Example App",
      "id": "example.com"
    },
    "user": {
      "id": "base64UserId...",
      "name": "user@example.com",
      "display_name": "John Doe"
    },
    "challenge": "base64Challenge...",
    "timeout": 60000
  },
  "allow_do_not_ask_again": true
}
```

---

## 6. 数据类型汇总

| 数据类型 | 用途 | 关键字段 |
|---------|------|---------|
| `IdentificationData` | 身份识别选项 | Options |
| `CreateAuthenticatorData` | 创建认证器选项 | Options |
| `StepAuthenticateData` | 认证选项 | Options, DeviceTokenEnabled |
| `IntentSignupFlowStepViewRecoveryCodeData` | 恢复码 | RecoveryCodes |
| `IntentCreateAuthenticatorTOTPData` | TOTP 创建 | Secret, OTPAuthURI |
| `SelectOOBOTPChannelsData` | OOB 渠道选择 | Channels, MaskedClaimValue |
| `VerifyOOBOTPData` | OTP 验证 | Channel, OTPForm, MaskedClaimValue, CanResendAt, DeliveryStatus |
| `OAuthData` | OAuth 授权 | OAuthURI, State 等 |
| `AccountLinkingIdentifyData` | 账户关联 | 冲突选项 |
| `IntentAccountRecoveryFlowStepIdentifyData` | 账户恢复识别 | Options |
| `IntentAccountRecoveryFlowStepSelectDestinationData` | 恢复目的地 | Options |
| `IntentAccountRecoveryFlowStepVerifyAccountRecoveryCodeData` | 恢复验证码 | MaskedDisplayName, Channel, CanResendAt |
| `NewPasswordData` | 新密码 | PasswordPolicy, MaskedTarget |
| `ForceChangePasswordData` | 强制改密 | PasswordPolicy, ForceChangeReason |
| `NodePromptCreatePasskeyData` | Passkey 创建 | CreationOptions, AllowDoNotAskAgain |

---

## 7. 输出数据用途（基于代码实际使用）

DataOutputer 输出的数据通过 `FlowResponse.action.data` 返回给客户端，用于：

1. **UI 渲染**：客户端根据 `action.type` 和 `action.data` 渲染对应界面
   - 如 `action.type=identify` + `data.options` 渲染身份选择界面

2. **状态反馈**：显示当前步骤所需信息
   - 验证码发送状态（`delivery_status`）
   - 密码策略要求（`password_policy`）
   - 恢复码展示（`recovery_codes`）

3. **配置传递**：传递给客户端执行下一步所需的配置
   - WebAuthn 创建选项（`creation_options`）
   - OAuth 授权 URI（`oauth_uri`）
   - TOTP QR 码数据（`otpauth_uri`）

4. **API 契约**：作为 HTTP API 的响应体，位于 `action.data` 字段
   - 结构定义：`flow.go:60-65 FlowAction.Data Data`
