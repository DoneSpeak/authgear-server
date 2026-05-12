# `email_password_primary_oob_otp_email` — HTTP 请求序列（Authentication Flow API）

说明：以下为 **Authentication Flow API** 形态示例（与 Authgear 服务端语义一致）。`localhost:8080` 可按实际网关替换。`state_token`、邮箱掩码等为占位示例。

流程 YAML 要点：`IDENTIFY(email)` → `AUTHENTICATE(primary_password)` → `AUTHENTICATE(primary_oob_otp_email)`。

要点（与实现一致）：进入邮箱 OTP 输入界面时，**`action.type` 仍为 `authenticate`**，`action.authentication` 为 **`primary_oob_otp_email`**；是否处于填码阶段由 **`action.data.type`** 区分——OTP 界面为 **`verify_oob_otp_data`**，而非单独的 `action.type: verify`。

每个步骤均包含 **HTTP 请求** 与对应的 **`result` 响应**（成功路径示例）。

---

## 1. 创建登录流

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows
Content-Type: application/json
```

```json
{
  "type": "login",
  "name": "email_password_primary_oob_otp_email"
}
```

**响应**

```json
{
  "result": {
    "state_token": "authflowstate_BZsDAnHCAMm665Rd-z5woqgx2AeCT1oYfN-8Bt5aqsdK4WBb",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "identify",
      "data": {
        "type": "identification_data",
        "options": [
          {
            "identification": "email"
          }
        ]
      }
    }
  }
}
```

---

## 2. 提交邮箱（IDENTIFY）

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows/states/input
Content-Type: application/json
```

```json
{
  "state_token": "authflowstate_BZsDAnHCAMm665Rd-z5woqgx2AeCT1oYfN-8Bt5aqsdK4WBb",
  "input": {
    "identification": "email",
    "login_id": "user@example.com"
  }
}
```

**响应**

```json
{
  "result": {
    "state_token": "authflowstate_<STEP2_AUTHENTICATE_PASSWORD>",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "authenticate",
      "authentication": "primary_password",
      "data": {
        "type": "authentication_data",
        "options": [
          {
            "authentication": "primary_password"
          }
        ],
        "device_token_enabled": false
      }
    }
  }
}
```

（若项目开启人机验证或 `device_token`，`options` / `device_token_enabled` 会与线上配置一致，此处从略。）

---

## 3. 提交主密码（第二个顶层步骤：AUTHENTICATE / primary_password）

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows/states/input
Content-Type: application/json
```

```json
{
  "state_token": "authflowstate_<STEP2_AUTHENTICATE_PASSWORD>",
  "input": {
    "authentication": "primary_password",
    "password": "your-password"
  }
}
```

**响应**

```json
{
  "result": {
    "state_token": "authflowstate_<STEP3_AUTHENTICATE_OOB_EMAIL>",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "authenticate",
      "authentication": "primary_oob_otp_email",
      "data": {
        "type": "authentication_data",
        "options": [
          {
            "authentication": "primary_oob_otp_email",
            "otp_form": "code",
            "channels": ["email"],
            "masked_display_name": "us***@example.com"
          }
        ],
        "device_token_enabled": false
      }
    }
  }
}
```

---

## 4. 选择邮箱 OTP 认证分支（第三个顶层步骤：authenticate / primary_oob_otp_email）

对应 `IntentLoginFlowStepAuthenticate`：需在 **`options` 中的下标** 与 `authentication` 一致。

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows/states/input
Content-Type: application/json
```

```json
{
  "state_token": "authflowstate_<STEP3_AUTHENTICATE_OOB_EMAIL>",
  "input": {
    "authentication": "primary_oob_otp_email",
    "index": 0
  }
}
```

（仅当该选项存在多个 `channels` 时 schema 会要求额外传入 `"channel": "email"`。）

**响应（常见：单次 Accept 已选认证器并发 OTP，直接进入填码态）**

```json
{
  "result": {
    "state_token": "authflowstate_<VERIFY_OTP>",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "authenticate",
      "authentication": "primary_oob_otp_email",
      "data": {
        "type": "verify_oob_otp_data",
        "channel": "email",
        "otp_form": "code",
        "masked_claim_value": "us***@example.com",
        "code_length": 6,
        "can_resend_at": "2026-05-10T12:00:00Z",
        "can_check": false,
        "failed_attempt_rate_limit_exceeded": false
      }
    }
  }
}
```

**响应（少见：仍需下一轮仅含 `index` 的输入时）**

若服务端在本轮结束后仍要求再提交 `{ "index": 0 }`，则可能再次返回 `authentication_data` 或与校验相关的中间态；以下一步实际返回的 JSON 为准。

---

## 5. （可能需要）再提交 `index` —— 选择具体 OOB 认证器实例

进入 `IntentUseAuthenticatorOOBOTP` 后，校验模式往往只允许 **`index`**。若与步骤 4 已在同一轮 Accept 内完成，则**无需**再发本请求。

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows/states/input
Content-Type: application/json
```

```json
{
  "state_token": "authflowstate_<AFTER_SELECT_BRANCH>",
  "input": {
    "index": 0
  }
}
```

**响应**

```json
{
  "result": {
    "state_token": "authflowstate_<VERIFY_OTP>",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "authenticate",
      "authentication": "primary_oob_otp_email",
      "data": {
        "type": "verify_oob_otp_data",
        "channel": "email",
        "otp_form": "code",
        "masked_claim_value": "us***@example.com",
        "code_length": 6,
        "can_resend_at": "2026-05-10T12:00:00Z",
        "can_check": false,
        "failed_attempt_rate_limit_exceeded": false
      }
    }
  }
}
```

也可用 **`batch_input`** 将「步骤 4 + 步骤 5」放在同一请求体内顺序提交（见 API 文档）；**响应**形态与上式相同（进入 `verify_oob_otp_data`）。

---

## 6. （可选）重发 OTP

仍在 `verify_oob_otp_data` 状态下可发起。

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows/states/input
Content-Type: application/json
```

```json
{
  "state_token": "authflowstate_<VERIFY_OTP>",
  "input": {
    "resend": true
  }
}
```

**响应**

```json
{
  "result": {
    "state_token": "authflowstate_<VERIFY_OTP_AFTER_RESEND>",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "authenticate",
      "authentication": "primary_oob_otp_email",
      "data": {
        "type": "verify_oob_otp_data",
        "channel": "email",
        "otp_form": "code",
        "masked_claim_value": "us***@example.com",
        "code_length": 6,
        "can_resend_at": "2026-05-10T12:05:00Z",
        "can_check": false,
        "failed_attempt_rate_limit_exceeded": false
      }
    }
  }
}
```

---

## 7. 提交 OTP（校验通过并完成登录流）

**请求**

```http
POST http://localhost:8080/api/v1/authentication_flows/states/input
Content-Type: application/json
```

```json
{
  "state_token": "authflowstate_<VERIFY_OTP>",
  "input": {
    "code": "123456"
  }
}
```

**响应**

```json
{
  "result": {
    "state_token": "authflowstate_<FINISHED>",
    "type": "login",
    "name": "email_password_primary_oob_otp_email",
    "action": {
      "type": "finished",
      "data": {
        "finish_redirect_uri": "https://your-app/oauth/callback?..."
      }
    }
  }
}
```

客户端应跳转至 **`finish_redirect_uri`**，把控制权交回 Authgear / OAuth 回调。

---

## 参考

- Authgear 官方：`docs/specs/authentication-flow-api-reference.md`（HTTP 路径、`batch_input`、`verify_oob_otp_data` 字段说明）。
- 引擎行为：`pkg/lib/authenticationflow/declarative/intent_login_flow_step_authenticate.go`、`intent_use_authenticator_oob_otp.go`、`intent_authn_oob.go`、`node_authn_oob.go`。
