# Authgear 异常码与 Reason 统计

本文档根据 `docs/error-handling.md` 与代码库中 `apierrors.*.WithReason` 的用法整理，统计 Authgear 定义的 **API 异常名（Name）**、**HTTP 状态码**、**Reason** 以及部分 **Cause kind**。

---

## 一、错误处理规范摘要（来自 error-handling.md）

### 1. API 错误结构

- **Name**：对应 HTTP 状态，用于区分大类。
- **Reason**：标识具体错误类型，由各包在 `error.go` 等处用 `apierrors.XXX.WithReason("ReasonString")` 定义。
- **Message**：面向开发者的调试信息，不应包含 reason/info 之外的业务信息。
- **Info**：可选，可包含 `cause` / `causes`，用于校验等详细原因；每个 cause 有字符串字段 `kind`。

**客户端如何知道读 `info.cause` 还是 `info.causes`？**  
API 不会用单独字段标明。服务端可能返回其一：
- **单条原因**：`NewWithCause()` → 响应里是 `info.cause`（一个对象，含 `kind`）。
- **多条原因**：`NewWithCauses()` 或校验聚合错误 → 响应里是 `info.causes`（对象数组，每项含 `kind`）。

客户端应**同时兼容**两种形态：先看是否存在 `info.cause`，再看是否存在 `info.causes`，再统一成「原因列表」处理。例如：

```ts
const causes = Array.isArray(info.causes)
  ? info.causes
  : info.cause != null
    ? [info.cause]
    : [];
causes.forEach(c => { /* c.kind, 以及 c 上其它字段 */ });
```

示例 JSON：

```json
{
  "name": "Invalid",
  "reason": "PasswordPolicyViolated",
  "message": "password policy violated",
  "code": 400,
  "info": {
    "causes": [
      { "kind": "PasswordTooShort", "min_length": 8, "pw_length": 6 },
      { "kind": "PasswordUppercaseRequired" }
    ]
  }
}
```

### 2. 完整的 Error Response 结构

**HTTP 层**

- 出错时 HTTP 状态码 = `error.code`（与 Name 对应，见下表）。
- `Content-Type: application/json`，body 为 JSON。

**Body 根结构（标准 API，如 `pkg/api/response.go`）**

```json
{
  "result": { ... },   // 成功时有，可省略
  "error": { ... }     // 失败时有，即下面的 error 对象；成功时无或省略
}
```

客户端应先看是否存在 `error`，有则按错误处理，否则用 `result`。

**`error` 对象（即完整 APIError 的 JSON）**

| 字段       | 类型   | 必填 | 说明 |
|------------|--------|------|------|
| `name`     | string | 是   | 错误大类，如 `Invalid`、`Unauthorized` |
| `reason`   | string | 是   | 具体原因标识，如 `PasswordPolicyViolated` |
| `message`  | string | 是   | 面向开发者的说明，用于调试 |
| `code`     | number | 是   | HTTP 状态码（与 `name` 一致） |
| `info`     | object | 否   | 额外信息；无则整段省略 |

**`info` 内常见键**

| 键         | 类型           | 说明 |
|------------|----------------|------|
| `cause`    | object         | 单条原因，至少含 `kind`（string） |
| `causes`   | array of object| 多条原因，每项至少含 `kind`（string） |
| 其它       | 任意           | 如 `byte_offset`（InvalidJSON）、`field`、业务字段等 |

**单条 cause 对象**

- 至少包含：`kind`（string），如 `PasswordTooShort`、`CodeNotFound`。
- 可能包含其它字段（由 Reason/Cause 类型决定），例如：
  - 密码策略：`min_length`、`pw_length` 等
  - InvalidJSON：根级 `info.byte_offset`

**完整示例 1：单条 cause**

```json
{
  "error": {
    "name": "Invalid",
    "reason": "ValidationFailure",
    "message": "invalid code",
    "code": 400,
    "info": {
      "cause": { "kind": "CodeExpired" }
    }
  }
}
```

**完整示例 2：多条 causes + 其它 info**

```json
{
  "error": {
    "name": "Invalid",
    "reason": "ValidationFailure",
    "message": "invalid password format",
    "code": 400,
    "info": {
      "causes": [
        { "kind": "TooShort" },
        { "kind": "TooSimple" }
      ]
    }
  }
}
```

**完整示例 3：无 info（仅 name/reason/message/code）**

```json
{
  "error": {
    "name": "NotFound",
    "reason": "UserNotFound",
    "message": "user not found",
    "code": 404
  }
}
```

**说明**：部分接口（如 Web/Turbo 等）可能直接返回 `{ "error": { ... } }` 而不带 `result` 键，但 `error` 对象结构同上。

### 3. 错误名与 HTTP 状态码

| Name                   | HTTP Status | 说明 |
|------------------------|-------------|------|
| BadRequest             | 400         | 请求语法错误，服务无法理解 |
| Invalid                | 400         | 请求语义错误，服务拒绝处理 |
| Unauthorized           | 401         | 未提供或无效凭证（认证） |
| Forbidden              | 403         | 凭证有效但无权限（授权） |
| NotFound               | 404         | 资源不存在 |
| AlreadyExists          | 409         | 资源已存在（冲突） |
| DataRace               | 409         | 数据竞争 |
| TooManyRequest         | 429         | 请求过于频繁 |
| InternalError          | 500         | 内部错误 |
| ServiceUnavailable     | 503         | 服务不可用 |
| RequestEntityTooLarge  | 413         | 请求体过大 |

---

## 二、Reason 按 Name 分类统计

### BadRequest (400)

| Reason | 定义位置 |
|--------|----------|
| WorkflowUnknownIntent | pkg/lib/workflow/errors.go |
| WorkflowUnknownInput | pkg/lib/workflow/errors.go |
| WorkflowInvalidInputKind | pkg/lib/workflow/errors.go |
| UnsupportedImageFile | pkg/lib/web/error.go |
| InvalidQuery | pkg/lib/infra/db/pagination.go |
| OAuthProtocolError | pkg/lib/oauthrelyingparty/oauthrelyingpartyutil/error.go, pkg/lib/authn/sso/error.go |
| OAuthError | pkg/lib/oauthrelyingparty/oauthrelyingpartyutil/error.go |
| SMSGatewayInvalidPhoneNumber | pkg/lib/infra/sms/smsapi/api.go |
| DenoRunError | pkg/lib/hook/error.go |
| StandardAttributesEmailRequired | pkg/lib/authn/stdattrs/error.go |
| ExternalJWTInvalidJWT | pkg/lib/externaljwt/errors.go |
| ExternalJWTInvalidClaim | pkg/lib/externaljwt/errors.go |
| RoleDuplicateKey | pkg/lib/rolesgroups/errors.go |
| GroupDuplicateKey | pkg/lib/rolesgroups/errors.go |
| ResourceDuplicateURI | pkg/lib/resourcescope/errors.go |
| ScopeDuplicate | pkg/lib/resourcescope/errors.go |
| AuthenticationFlowUnknownFlow | pkg/lib/authenticationflow/errors.go |
| AuthenticationFlowDifferentUserID | pkg/lib/authenticationflow/errors.go |
| AuthenticationFlowNoUserID | pkg/lib/authenticationflow/errors.go |
| InvalidWhatsappUser | pkg/lib/infra/whatsapp/errors.go |
| WhatsappUndeliverable | pkg/lib/infra/whatsapp/errors.go |
| NoAvailableWhatsappClient | pkg/lib/infra/whatsapp/errors.go |

### Invalid (400)

| Reason | 定义位置 |
|--------|----------|
| NewPasswordTypo | pkg/util/password/confirm.go |
| InvalidJWTMutations | pkg/util/jwtutil/jwtutil.go |
| InvalidSignatureQueryParam | pkg/util/httpsigning/httpsigning.go |
| InvalidSignature | pkg/util/httpsigning/httpsigning.go |
| ExpiredSignature | pkg/util/httpsigning/httpsigning.go |
| InvalidDomain | pkg/portal/service/domain.go |
| CollaboratorInvitationInvalidCode | pkg/portal/service/collaborator.go |
| CollaboratorInvitationInvalidEmail | pkg/portal/service/collaborator.go |
| CollaboratorQuotaExceeded | pkg/portal/service/collaborator.go |
| InvalidAppID | pkg/portal/service/app.go |
| UserExportNonUniqueFieldNames | pkg/lib/userexport/errors.go |
| InvalidCursor | pkg/lib/infra/db/pagination.go |
| DenoCheckError | pkg/lib/hook/error.go |
| PasswordResetFailed | pkg/lib/feature/forgotpassword/errors.go |
| ForgotPasswordFailed | pkg/lib/feature/forgotpassword/errors.go |
| SendPasswordNoTarget | pkg/lib/feature/forgotpassword/errors.go |
| CaptchaFailed | pkg/lib/feature/captcha/errors.go |
| InvariantViolated | pkg/lib/facade/error.go, pkg/lib/authn/authenticator/errors.go, pkg/api/errors.go |
| UserIsAnonymized | pkg/lib/facade/error.go |
| MFAGracePeriodInvalid | pkg/lib/facade/error.go |
| WebUIInvalidCustomURI | pkg/lib/oauth/oidc/errors.go |
| InvalidTOTPSecret | pkg/lib/authn/authenticator/totp/errors.go |
| PasswordPolicyViolated | pkg/lib/authn/authenticator/password/errors.go |
| PasswordExpiryForceChange | pkg/lib/authn/authenticator/password/errors.go |
| InvalidBcryptHash | pkg/lib/authn/authenticator/password/error.go |
| InvalidAccountStatusTransition | pkg/lib/authn/user/errors.go |
| GetUsersInvalidArgument | pkg/api/errors.go |
| OAuthProviderMissingCredentials | pkg/api/errors.go |
| ChangePasswordFailed | pkg/api/errors.go |
| AccountManagementOAuthTokenInvalid | pkg/lib/accountmanagement/errors.go |
| AccountManagementOAuthStateNotBoundToToken | pkg/lib/accountmanagement/errors.go |
| AccountManagementOAuthTokenNotBoundToUser | pkg/lib/accountmanagement/errors.go |
| AccountManagementTokenInvalid | pkg/lib/accountmanagement/errors.go |
| AccountManagementTokenNotBoundToUser | pkg/lib/accountmanagement/errors.go |
| AccountManagementIdentityNotOwnedByUser | pkg/lib/accountmanagement/errors.go |
| AccountManagementAuthenticatorNotOwnedByUser | pkg/lib/accountmanagement/errors.go |
| AccountManagementSecondaryAuthenticatorIsRequired | pkg/lib/accountmanagement/errors.go |
| ValidationFailed | pkg/api/apierrors/kinds.go（validation.AggregatedError 转换） |

### Unauthorized (401)

| Reason | 定义位置 |
|--------|----------|
| Unauthenticated | pkg/portal/service/authz.go, pkg/portal/graphql/errors.go |
| InvalidCredentials | pkg/api/errors.go |

### Forbidden (403)

| Reason | 定义位置 |
|--------|----------|
| Forbidden | pkg/portal/service/authz.go |
| DomainNotCustom | pkg/portal/service/domain.go |
| DomainVerificationFailed | pkg/portal/service/domain.go |
| AppIDReserved | pkg/portal/service/app.go |
| ReauthRequrired | pkg/portal/service/app.go |
| AccessDenied | pkg/portal/graphql/errors.go |
| ResourceUpdateConflict | pkg/portal/appresource/error.go |
| CollaboratorSelfDeletion | pkg/portal/service/collaborator.go |
| UserAgentUnmatched | pkg/lib/workflow/errors.go |
| HookDisallowed | pkg/lib/hook/error.go |
| InvalidVerificationCode | pkg/lib/feature/verification/errors.go |
| SMSNotSupported | pkg/lib/feature/errors.go |
| InvalidOTPCode | pkg/lib/authn/otp/errors.go |
| DisabledUser | pkg/lib/authn/user/errors.go |
| AnonymizedUser | pkg/lib/authn/user/errors.go |
| ScheduledDeletionByAdmin | pkg/lib/authn/user/errors.go |
| ScheduledDeletionByEndUser | pkg/lib/authn/user/errors.go |
| ScheduledAnonymizationByAdmin | pkg/lib/authn/user/errors.go |
| UserOutsideValidPeriod | pkg/lib/authn/user/errors.go |
| AccessControlViolated | pkg/lib/authn/stdattrs/error.go, pkg/lib/authn/customattrs/error.go |
| NoPublicSignup | pkg/lib/interaction/nodes/do_create_user.go |
| ResourceNotAssociatedWithClient | pkg/lib/resourcescope/errors.go |
| BotProtectionVerificationFailed | pkg/lib/botprotection/errors.go |
| AuthenticationFlowNotAllowed | pkg/lib/authenticationflow/errors.go |
| InvalidGrant | pkg/auth/handler/oauth/error.go |

### NotFound (404)

| Reason | 定义位置 |
|--------|----------|
| ResourceNotFound | pkg/util/resource/manager.go, pkg/lib/resourcescope/errors.go |
| ErrSubscriptionCheckoutNotFound | pkg/portal/service/subscription.go |
| ErrSubscriptionNotFound | pkg/portal/service/subscription.go |
| DomainNotFound | pkg/portal/service/domain.go |
| CollaboratorNotFound | pkg/portal/service/collaborator.go |
| CollaboratorInvitationNotFound | pkg/portal/service/collaborator.go |
| TokenNotFound | pkg/portal/appsecret/error.go |
| WorkflowNotFound | pkg/lib/workflow/errors.go |
| RoleNotFound | pkg/lib/rolesgroups/errors.go |
| GroupNotFound | pkg/lib/rolesgroups/errors.go |
| GroupUnknownKeys | pkg/lib/rolesgroups/errors.go |
| UserUnknownKeys | pkg/lib/rolesgroups/errors.go |
| RoleUnknownKeys | pkg/lib/rolesgroups/errors.go |
| ScopeNotFound | pkg/lib/resourcescope/errors.go |
| ClientNotFound | pkg/lib/resourcescope/errors.go |
| UserNotFound | pkg/lib/feature/passkey/errors.go, pkg/api/errors.go |
| WebAuthnSessionNotFound | pkg/lib/feature/passkey/errors.go |
| UserNotFound (api) | pkg/api/errors.go |
| IdentityNotFound | pkg/api/errors.go |
| OAuthProviderNotFound | pkg/api/errors.go |
| AuthenticatorNotFound | pkg/api/errors.go |
| TaskNotFound | pkg/api/errors.go |
| AuthenticationFlowNotFound | pkg/lib/authenticationflow/errors.go |
| AuthenticationFlowStepNotFound | pkg/lib/authenticationflow/errors.go |
| AuthenticationFlowFlowNotFound | pkg/lib/authenticationflow/declarative/error.go |

### AlreadyExists (409)

| Reason | 定义位置 |
|--------|----------|
| DuplicatedDomain | pkg/portal/service/domain.go |
| DomainVerified | pkg/portal/service/domain.go |
| DuplicatedAppID | pkg/portal/service/config.go |
| CollaboratorDuplicate | pkg/portal/service/collaborator.go |
| CollaboratorInvitationDuplicate | pkg/portal/service/collaborator.go |
| AccountManagementDuplicatedIdentity | pkg/lib/accountmanagement/errors.go |

### TooManyRequest (429)

| Reason | 定义位置 |
|--------|----------|
| UsageLimitExceeded | pkg/lib/usage/errors.go |
| RateLimited | pkg/lib/ratelimit/error.go |
| AccountLockout | pkg/lib/lockout/error.go |
| SMSGatewayRateLimited | pkg/lib/infra/sms/smsapi/api.go |

### InternalError (500)

| Reason | 定义位置 |
|--------|----------|
| SMTPTestFailed | pkg/portal/smtp/errors.go |
| SearchDisabled | pkg/lib/search/error.go, pkg/lib/search/pgsearch/error.go, pkg/lib/elasticsearch/error.go |
| InvalidConfiguration | pkg/lib/oauthrelyingparty/oauthrelyingpartyutil/error.go, pkg/lib/authn/sso/error.go, pkg/lib/accountmigration/errors.go, pkg/api/errors.go |
| OTPDeliveryUnexpectedError | pkg/lib/authn/otp/errors.go |
| NoAvailableSMTPConfiguration | pkg/lib/infra/mail/sender.go |
| HookDeliveryTimeout | pkg/lib/hook/error.go |
| HookInvalidResponse | pkg/lib/hook/error.go |
| HookDeliveryUnknownFailure | pkg/lib/hook/error.go |
| UserExportDisabled | pkg/lib/userexport/errors.go |
| PasswordGenerateError | pkg/lib/authn/authenticator/password/errors.go |
| ExternalJWTFailedToFetchJWKs | pkg/lib/externaljwt/errors.go |
| SMSGatewayAuthenticationFailed | pkg/lib/infra/sms/smsapi/api.go |
| SMSGatewayDeliveryRejected | pkg/lib/infra/sms/smsapi/api.go |
| SMSGatewayTimeout | pkg/lib/infra/sms/smsapi/api.go |
| SMSGatewayUnsupportedRequest | pkg/lib/infra/sms/smsapi/api.go |
| AuthenticationFlowInvalidTargetStep | pkg/lib/authenticationflow/declarative/error.go |
| AuthenticationFlowInvalidFlowConfig | pkg/lib/authenticationflow/declarative/error.go |
| UnexpectedError | pkg/api/apierrors/kinds.go（非 API 错误的兜底） |
| UnexpectedWhatsappMessageStatusError | pkg/lib/infra/whatsapp/errors.go |
| WhatsappMessageStatusCallbackTimeout | pkg/lib/infra/whatsapp/errors.go |

### ServiceUnavailable (503)

| Reason | 定义位置 |
|--------|----------|
| BotProtectionVerificationServiceUnavailable | pkg/lib/botprotection/errors.go |
| LDAPConnectionTestFailed | pkg/api/errors.go |

### RequestEntityTooLarge (413)

| Reason | 定义位置 |
|--------|----------|
| JSONTooLarge | pkg/util/httputil/json.go |
| ResourceTooLarge | pkg/portal/appresource/error.go |
| RequestEntityTooLarge | 代码内构造（如 MaxBytesError 转换） |
| InvalidJSON | 代码内构造（JSON 语法错误转换） |

---

## 三、Cause kind 统计（info.cause / info.causes）

以下为代码中出现的 **cause kind**（即 `info.cause.kind` 或 `info.causes[].kind`）。

### 验证码 / OTP

| Kind | Reason | 说明 |
|------|--------|------|
| CodeNotFound | InvalidVerificationCode, InvalidOTPCode | 验证码过期或不存在 |
| InvalidVerificationCode | InvalidVerificationCode | 验证码无效 |
| InvalidCode | InvalidOTPCode, PasswordResetFailed | 验证码无效 / 无效 code |
| UsedCode | InvalidOTPCode, PasswordResetFailed | 验证码已使用 |
| FeatureDisabled | ForgotPasswordFailed | 忘记密码功能未开启 |
| UserNotFound | SendCodeFailed | 指定用户不存在 |

### 密码

| Kind | Reason | 说明 |
|------|--------|------|
| PasswordTooShort | PasswordPolicyViolated | 密码过短 |
| PasswordUppercaseRequired | PasswordPolicyViolated | 需要大写 |
| PasswordLowercaseRequired | PasswordPolicyViolated | 需要小写 |
| PasswordAlphabetRequired | PasswordPolicyViolated | 需要字母 |
| PasswordDigitRequired | PasswordPolicyViolated | 需要数字 |
| PasswordSymbolRequired | PasswordPolicyViolated | 需要符号 |
| PasswordContainingExcludedKeywords | PasswordPolicyViolated | 含排除关键词 |
| PasswordBelowGuessableLevel | PasswordPolicyViolated | 可猜度低于要求 |
| PasswordReused | PasswordPolicyViolated | 密码重复使用 |
| NoPassword | ChangePasswordFailed | 用户无密码 |
| PasswordReused | ChangePasswordFailed | 密码重复使用 |

### 账号状态（InvalidAccountStatusTransition）

| Kind | 说明 |
|------|------|
| AccountValidFromShouldBeBeforeAccountValidUntil | 有效起始早于有效结束 |
| TemporarilyDisabledPeriodMissingUntilTimestamp | 临时禁用缺少 Until |
| TemporarilyDisabledPeriodMissingFromTimestamp | 临时禁用缺少 From |
| TemporarilyDisabledFromShouldBeBeforeTemporarilyDisabledUntil | 临时禁用时间顺序 |
| AccountValidFromShouldBeBeforeTemporarilyDisabledFrom | 有效起始与临时禁用 From 顺序 |
| TemporarilyDisabledUntilShouldBeBeforeAccountValidUntil | 临时禁用 Until 与有效结束顺序 |

### 鉴权 / 身份 / 约束（InvariantViolated）

| Kind | 说明 |
|------|------|
| DuplicatedAuthenticator | 重复的认证器 |
| IdentityModifyDisabled | 禁止修改身份 |
| MismatchedUser | 用户不匹配 |
| NoAuthenticator | 无认证器 |
| ClaimNotVerifiable | 声明不可验证 |

### 其他

| Kind | Reason | 说明 |
|------|--------|------|
| VerificationFailed | CaptchaFailed | 人机验证失败 |
| FailedToConnect | LDAPConnectionTestFailed | LDAP 连接失败 |
| FailedToBindSearchUser | LDAPConnectionTestFailed | 绑定搜索用户失败 |
| TestingEndUserNotFound | LDAPConnectionTestFailed | 测试用最终用户未找到 |
| MoreThanOneEntryInSearchResult | LDAPConnectionTestFailed | 搜索结果多于一条 |
| TestingEndUserMissingUserIDAttribute | LDAPConnectionTestFailed | 缺少用户 ID 属性 |

### 校验框架（ValidationFailed）

当 `validation.AggregatedError` 被转换为 API 错误时，`info.causes` 中每项的 `kind` 为校验的 **Keyword**（如 `required`、`format` 等），由各接口校验规则决定，此处不逐一列出。

---

## 四、数量汇总

| Name                   | Reason 数量（约） |
|------------------------|------------------|
| BadRequest             | 24+              |
| Invalid                | 40+              |
| Unauthorized           | 2                |
| Forbidden              | 25+              |
| NotFound               | 28+              |
| AlreadyExists          | 6                |
| TooManyRequest         | 4                |
| InternalError          | 22+              |
| ServiceUnavailable     | 2                |
| RequestEntityTooLarge  | 4（含代码内构造） |

---

## 五、关键代码结构与异常处理机制

### 5.1 核心类型与结构（与 error / info / response 对应）

| 概念 | 类型/位置 | 说明 |
|------|-----------|------|
| **错误名 Name** | `pkg/api/apierrors/kinds.go`：`type Name string`，常量 `BadRequest`、`Invalid`、`Unauthorized` 等 | 对应 HTTP 大类；`Name.HTTPStatus()` 返回状态码 |
| **Kind** | `pkg/api/apierrors/kinds.go`：`type Kind struct { Name, Reason string; IsSkipLoggingToExternalService bool }` | 即 (name + reason)；`Name.WithReason(reason)` 得到 Kind |
| **Cause** | `pkg/api/apierrors/error.go`：`type Cause interface{ Kind() string }` | 单条原因，序列化后至少含 `kind` |
| **StringCause** | `pkg/api/apierrors/error.go`：`type StringCause string`，实现 `Cause`，JSON 为 `{"kind":"<string>"}` | 仅 kind 时用 |
| **MapCause** | `pkg/api/apierrors/error.go`：`type MapCause struct { CauseKind string; Data map[string]interface{} }`，JSON 为 `{"kind":..., ...Data}` | 带额外字段的 cause |
| **APIError** | `pkg/api/apierrors/error.go`：`struct { Kind; Message string; Code int; Info_ReadOnly Details }`，实现 `error` | 即最终返回的 error 对象；`Kind` 内嵌故 JSON 有 `name`、`reason` |
| **Details / info** | `pkg/api/apierrors/error.go`：`type Details = errorutil.Details`（`map[string]interface{}`） | 即 `APIError.Info_ReadOnly`，序列化为 `error.info` |
| **Response** | `pkg/api/response.go`：`struct { Result interface{}; Error error }`，`MarshalJSON` 时 Error 用 `AsAPIError` 转成 *APIError | 标准 API 响应体：`{ "result"? , "error"? }` |

### 5.2 构造 API 错误与 info（cause/causes）

| 用法 | 代码位置 | 说明 |
|------|----------|------|
| 定义 Reason | 各包 `error.go`：`var Xxx = apierrors.Invalid.WithReason("Xxx")` | 得到 `Kind`，用于后续 `.New` / `.NewWithCause` / `.NewWithCauses` |
| 仅 message | `Kind.New(msg)` | `pkg/api/apierrors/error.go`，等价 `NewWithInfo(msg, nil)`，无 info |
| 单条 cause | `Kind.NewWithCause(msg, c Cause)` | 同上，内部 `Details{"cause": c}`，响应里 `info.cause` |
| 多条 causes | `Kind.NewWithCauses(msg, cs []Cause)` | 同上，内部 `Details{"causes": cs}`，响应里 `info.causes` |
| 任意 info | `Kind.NewWithInfo(msg, info Details)` | 同上；info 中值会用 `APIErrorDetail.Value(v)` 标记，便于出现在响应中 |
| 通用构造 | `NewBadRequest(msg)`、`NewInvalid(msg)`、`NewUnauthorized(msg)` 等 | `pkg/api/apierrors/error.go`，用默认 Reason 的快捷方法 |

### 5.3 任意 error → APIError（info 合并、兜底）

| 步骤 | 代码位置 | 说明 |
|------|----------|------|
| 收集链上 details | `errorutil.CollectDetails(err, nil)` | `pkg/util/errorutil/details.go`，沿 `Unwrap` 收集所有 `Detailer.FillDetails` |
| 只保留 API 可见 | `errorutil.FilterDetails(details, apierrors.APIErrorDetail)` | `pkg/util/errorutil/details_kv.go`，只保留带 `APIErrorDetail` 标记的键值，作为 info 补充 |
| 统一入口 | `apierrors.AsAPIError(err)` | `pkg/api/apierrors/error.go`：先判断 `*http.MaxBytesError` → RequestEntityTooLarge；再 `*json.SyntaxError` → InvalidJSON；再 `*APIError` → 合并 info 后返回；再 `*validation.AggregatedError` → 转成 ValidationFailed + causes；否则 → UnexpectedError + 上述 FilterDetails 的 info |

### 5.4 校验错误 → APIError（ValidationFailed + causes）

| 类型 | 代码位置 | 说明 |
|------|----------|------|
| 单条校验 | `pkg/util/validation/error.go`：`Error struct { Location, Keyword, Info }`，`Keyword` 序列化为 `kind`，实现 `Kind() string` | 即单条 cause 的 kind |
| 聚合 | `AggregatedError struct { Message string; Errors []Error }` | 作为 error 返回时，被 `AsAPIError` 识别 |
| 转换 | `pkg/api/apierrors/error.go`：`AsAPIError` 内 `errors.As(err, &v)` 到 `*validation.AggregatedError` 时，将 `v.Errors` 转为 `[]Cause` 写入 `info["causes"]`，Reason 固定为 `ValidationFailed` | 客户端看到的即 `reason: "ValidationFailed"` + `info.causes` 数组，每项含 `kind`（及 location/details） |

#### 5.4.1 validation.Error 的字段

| 字段 | 类型 | JSON 字段 | 说明 |
|------|------|-----------|------|
| `Location` | `string` | `location` | JSON Pointer 路径，指出错字段在请求体中的位置（如 `/email`、`/profile/name`） |
| `Keyword` | `string` | `kind` | 校验关键词，标识错误类型（如 `required`、`minimum`、`format`），对应 cause 的 `kind` |
| `Info` | `map[string]interface{}` | `details`（出现在 cause 内） | 额外信息，取决于 Keyword；常见键：`actual`、`expected`、`minimum`、`maximum`、`minLength`、`maxLength`、`format`、`error`（format 校验失败时的错误描述） |

**示例：多字段校验失败**

```json
{
  "name": "Invalid",
  "reason": "ValidationFailed",
  "message": "invalid value",
  "code": 400,
  "info": {
    "causes": [
      {
        "kind": "required",
        "location": "/email"
      },
      {
        "kind": "minimum",
        "location": "/age",
        "details": {
          "actual": 15,
          "minimum": 18
        }
      },
      {
        "kind": "format",
        "location": "/phone",
        "details": {
          "format": "phone",
          "error": "invalid phone number format"
        }
      }
    ]
  }
}
```

#### 5.4.2 常用 Keyword（校验关键词）

| Keyword | 说明 | 常见 Info 键 |
|---------|------|--------------|
| `required` | 必填字段缺失 | `location`（指出哪个字段） |
| `type` | 类型不匹配（如期望 string 收到 number） | `expected`（期望类型）、`actual`（实际类型） |
| `minimum` / `maximum` | 数值超出范围 | `minimum`、`maximum`、`actual` |
| `minLength` / `maxLength` | 字符串长度超出范围 | `minLength`、`maxLength`、`actual` |
| `format` | 格式校验失败 | `format`（格式名）、`error`（详细错误） |
| `enum` | 不在允许值列表中 | `expected`（允许值列表，可选） |
| `pattern` | 正则不匹配 | `pattern`（正则表达式，可选） |
| `additionalProperties` | 不允许的字段 | `unexpected`（字段名） |
| `oneOf` / `allOf` / `not` | 组合校验失败 | - |
| `general` | 通用错误消息 | `msg`（自定义错误描述） |

#### 5.4.3 如何简化定义：SchemaBuilder

直接手写 JSON Schema 繁琐，Authgear 提供 `pkg/util/validation/schema_builder.go`：`SchemaBuilder`（`map[string]interface{}` 的封装）链式调用构建校验规则。

**常用方法**

```go
// 类型定义
b.Type(validation.TypeString)                    // type: string
b.Type(validation.TypeObject)                     // type: object
b.Types(validation.TypeString, validation.TypeNull)  // type: ["string", "null"]

// 必填
b.Required("email", "name")                      // required: ["email", "name"]

// 长度
b.MinLength(8)                                   // minLength: 8
b.MaxLength(128)                                 // maxLength: 128

// 数值范围
b.MinimumInt64(18)                              // minimum: 18
b.MaximumInt64(120)                              // maximum: 120

// 枚举
b.Enum("sms", "email", "whatsapp")               // enum: ["sms", "email", "whatsapp"]

// 格式（需先 RegisterFormat）
b.Format("email")                                // format: "email"
b.Format("phone")                                // format: "phone"

// 嵌套对象
b.Properties().Property("email", SchemaBuilder{}.Type(TypeString).Format("email"))

// 组合
b.OneOf(...)                                     // oneOf: [...]
b.AllOf(...)                                     // allOf: [...]
```

**完整示例**

```go
// 定义用户更新请求的校验 Schema
func UserUpdateSchema() *validation.SimpleSchema {
    b := SchemaBuilder{}.
        Type(TypeObject).
        Required("email", "name").
        Properties().
            Property("email", SchemaBuilder{}.Type(TypeString).Format("email")).
            Property("name", SchemaBuilder{}.Type(TypeString).MinLength(1).MaxLength(100)).
            Property("age", SchemaBuilder{}.Type(TypeInteger).MinimumInt64(0).MaximumInt64(150)).
            Property("phone", SchemaBuilder{}.Type(TypeString).Format("phone")).
        AdditionalPropertiesFalse()

    return b.ToSimpleSchema()
}

// 使用
func HandleUpdateUser(ctx context.Context, req *UserUpdateRequest) error {
    validator := UserUpdateSchema().Validator()
    err := validator.ValidateWithMessage(ctx, req, "invalid request")
    if err != nil {
        return err  // 会返回 *AggregatedError，被 AsAPIError 转成 ValidationFailed + causes
    }
    // ...
}
```

**自定义格式示例**

```go
// 在初始化时注册自定义格式
schema := validation.NewSimpleSchema("{}")
schema.RegisterFormat("my_custom_format", MyFormatChecker{})

// MyFormatChecker 实现 jsonschemaformat.FormatChecker 接口
type MyFormatChecker struct{}

func (c MyFormatChecker) IsFormat(input string, err *error) bool {
    if !isValidMyFormat(input) {
        *err = fmt.Errorf("invalid my custom format: %s", input)
        return false
    }
    return true
}
```

**简化要点总结**

| 场景 | 简化方式 |
|------|----------|
| 直接构造 JSON Schema | 用 `SchemaBuilder` 链式调用 |
| 复用同一 Schema | 封装为 `func xxxSchema() *SimpleSchema` |
| 自定义校验逻辑 | 实现 `Validator` 接口，在 `Context.EmitError` 手动 emit |
| 业务错误消息 | 用 `Context.EmitErrorMessage("custom message")` 或 `Error("general", map[string]interface{}{"msg": "..."})` |

### 5.5 写出 HTTP 与 error response

| 步骤 | 代码位置 | 说明 |
|------|----------|------|
| 标准 JSON 响应 | `pkg/util/httputil/json.go`：`WriteJSONResponse(ctx, w, resp *api.Response)` | 用 `AsAPIError(resp.Error)` 取 *APIError；若有错则 `httpStatus = err.Code`；设 `Content-Type: application/json`、`WriteHeader(httpStatus)`；`encoder.Encode(resp)` 写 body（resp 的 MarshalJSON 会序列化 `error` 为上述 APIError 结构）；5xx 时打 log |
| 响应体序列化 | `pkg/api/response.go`：`Response.MarshalJSON()` | 序列化为 `{ "result", "error" }`，其中 `error` 由 `apierrors.AsAPIError(r.Error)` 得到，即完整 error 对象（name, reason, message, code, info） |

### 5.6 Panic 恢复与 500 错误

| 场景 | 代码位置 | 说明 |
|------|----------|------|
| 通用 API Panic | `pkg/lib/infra/middleware/panic.go`：`PanicMiddleware.Handle` | `defer recover()` → `panicutil.MakeError(e)` → `AsAPIError(e)`（通常变为 UnexpectedError）→ 若尚未写响应则 `WriteHeader(apiError.Code)` 并 `json.Encode(api.Response{Error: e})` |
| Web/Turbo Panic | `pkg/auth/handler/webapp/panic_middleware.go`：`PanicMiddleware.Handle` | `defer recover()` → `panicutil.MakeError(e)` → `AsAPIError(e)` → 通过 `ErrorService.SetRecoverableError` 写 cookie；未写响应时 GET/HEAD 直接渲染错误页并 `RenderHTMLStatus(w, r, apiError.Code, ...)`，其它方法重定向到错误页 |
| 将任意值变 error | `pkg/util/panicutil/error.go`：`MakeError(val interface{})` | 若已是 `error` 则返回，否则 `fmt.Errorf("%+v", val)` |

### 5.7 关键代码清单（按调用链）

**定义与构造**

- `pkg/api/apierrors/kinds.go`：Name 常量、HTTPStatus、Kind、WithReason、ValidationFailed / UnexpectedError
- `pkg/api/apierrors/error.go`：Cause / StringCause / MapCause、APIError、New / NewWithInfo / NewWithCause / NewWithCauses、HasCause、Clone / CloneWithInfo
- `pkg/api/apierrors/tags.go`：APIErrorDetail、TenantDetail（DetailTag 用于 FilterDetails）
- `pkg/util/validation/error.go`：Error（Keyword→kind）、AggregatedError

**统一转换与响应**

- `pkg/api/apierrors/error.go`：IsAPIError、AsAPIError（含 MaxBytes、JSON、*APIError、*AggregatedError、兜底）、IsKind、newInvalidJSON、newRequestBodyTooLarge
- `pkg/api/response.go`：Response、MarshalJSON
- `pkg/util/httputil/json.go`：WriteJSONResponse

**Details 与 info 来源**

- `pkg/util/errorutil/details.go`：Details、Detailer、WithDetails、CollectDetails
- `pkg/util/errorutil/details_kv.go`：DetailTag、DetailTaggedValue、FilterDetails

**Panic 与日志**

- `pkg/util/panicutil/error.go`：MakeError
- `pkg/lib/infra/middleware/panic.go`：PanicMiddleware（API）
- `pkg/auth/handler/webapp/panic_middleware.go`：PanicMiddleware（Web）

**业务中设置 error / info 的典型位置（示例）**

- `pkg/api/errors.go`：UserNotFound、InvalidCredentials、InvariantViolated、NewInvariantViolated(cause, msg, data)、ChangePasswordFailed + cause、LDAPConnectionTestFailed + cause 等
- `pkg/lib/authn/authenticator/password/errors.go`：PasswordPolicyViolated、PasswordExpiryForceChange、NewWithCauses
- `pkg/lib/authn/authenticator/errors.go`：InvariantViolated.NewWithCause(..., MapCause{CauseKind: "DuplicatedAuthenticator", ...})
- `pkg/lib/feature/verification/errors.go`：InvalidVerificationCode.NewWithCause(..., StringCause("CodeNotFound"))
- `pkg/lib/feature/forgotpassword/errors.go`：PasswordResetFailed.NewWithCause(..., StringCause("InvalidCode")) 等
- Handler 层返回 `api.Response{ Error: err }` 并由 `httputil.WriteJSONResponse(ctx, w, resp)` 写出：如 `pkg/auth/handler/api/workflow_v2.go`、`pkg/auth/handler/oauth/challenge.go`、`pkg/admin/transport/` 下各 handler 等

---

## 六、参考

- 规范与约定：`docs/error-handling.md`
- API 错误类型与 Kind：`pkg/api/apierrors/error.go`、`pkg/api/apierrors/kinds.go`
- 各包 Reason 定义：各包内 `error.go` 或包含 `WithReason` 的文件（见上表「定义位置」）
