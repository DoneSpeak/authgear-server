# Authgear i18n 国际化说明

本文总结 Authgear 服务端与文档中关于国际化（i18n）的定义与实现，主要参考 `docs/specs` 与相关代码。

---

## 1. 配置：应用支持的语言

应用级配置在 **Localization 配置** 中定义：

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `fallback_language` | string (BCP47) | 回退语言，未匹配时使用。默认 `en`。 |
| `supported_languages` | array of string (BCP47) | 应用支持的语言列表，至少一项，且需包含 fallback。 |

- 定义位置：`pkg/lib/config/localization.go`
- 未配置时：`fallback_language` 默认为 `en`，`supported_languages` 为 `[fallback_language]`。

---

## 2. 用户偏好语言的来源

用户“偏好语言”用于解析模板和翻译，来源优先级如下：

1. **请求参数 `ui_locales`**（query）  
   - 若存在，则优先使用。格式可为逗号/空格分隔的 BCP47 语言标签。
2. **HTTP 头 `Accept-Language`**  
   - 当没有 `ui_locales` 时使用。

实现要点：

- Web 请求：`pkg/auth/webapp/intl_middleware.go` 中的 `IntlMiddleware` 会调用 `PreferredLanguageTagsFromRequest(r)`，得到 `[]string` 的偏好语言列表，并写入 context：`intl.WithPreferredLanguageTags(ctx, tags)`。
- 解析：`pkg/util/intl/parse.go`  
  - `ParseUILocales(uiLocales string)`：把 `ui_locales` 转成与 Accept-Language 兼容再解析。  
  - `ParseAcceptLanguage(header string)`：使用 `golang.org/x/text/language.ParseAcceptLanguage` 解析为语言标签列表。

事件与 SDK 中的语义（见 `docs/specs/event.md`、`docs/specs/sdk.md`）：

- **preferred_languages**：从请求推断的用户偏好语言；若提供则来自 `ui_locales`，否则来自 `Accept-Language`；非用户触发的请求可为空数组。
- **language**：根据用户偏好语言与应用 `supported_languages` 解析出的最终使用语言；回退值为应用的 `fallback_language`。
- SDK 中的 **uiLocales** 即对应上述“界面语言”偏好。

---

## 3. 语言解析与匹配

- **Resolve**（`pkg/util/intl/resolve.go`）：根据 `preferred`、`fallback`、`supported` 解析出“在 supported 中的下标”和最终 `language.Tag`；未匹配时返回 -1。
- **BestMatch**（`pkg/util/intl/match.go`）：在 supported 列表中为 preferred 列表做最佳匹配，优先 Exact，其次按 confidence 选更具体的语言（例如 zh-Hant-HK 相对 zh 更具体）。
- **Supported / Fallback**（`pkg/util/intl/language.go`）：  
  - `Fallback(tag)` 得到 `FallbackLanguage`，空字符串会变为 `BuiltinBaseLanguage`（`en`）。  
  - `Supported(supportedTags, fallback)` 得到 `SupportedLanguages`，并保证 fallback 在列表第一位。

内置语言常量（`pkg/util/intl/language_constant.go`）：

- **BuiltinBaseLanguage**：默认语言 `en`。
- **ManuallyLocalizedLanguages**：如 en、zh-HK、zh-TW。
- **MachineLocalizedLanguages**：如 de、ja、zh-CN 等。
- **AvailableLanguages**：所有被识别的语言标签集合；与 CLDR 等映射用于 BCP47 ↔ CLDR。

---

## 4. 模板与翻译文件

### 4.1 模板路径与语言标签

- 模板按 **类型 + 语言** 组织，语言标签为 BCP47。
- 路径约定：`templates/<language_tag>/...`，例如：
  - `templates/en/translation.json`
  - `templates/zh-HK/messages/xxx.html`
- 正则：`templateLanguageTagRegex = ^templates/([a-zA-Z0-9-_]+)/`（`pkg/util/template/resource.go`）。

### 4.2 模板解析流程（docs/specs/templates.md）

1. 输入：**模板类型** + **用户偏好语言**（preferred languages）。
2. 先按类型选模板，再按偏好语言选“最佳语言”版本。
3. 所有模板都有默认，因此模板解析在正常情况下都会成功。

组件模板可被包含（如 `auth_ui_login.html` 依赖 `auth_ui_header.html`），便于只覆盖部分 UI 组件。

### 4.3 翻译文件（Translation file）

- **格式**：每个语言一个 **JSON 文件**，路径为 `templates/<language_tag>/translation.json`（常量 `TranslationJSONName = "translation.json"`）。
- **内容**：扁平 JSON，key 为翻译键，value 为字符串。
- **value 格式**：**ICU MessageFormat**。支持的子集包括：`select`、`plural`、`selectordinal`（见 `docs/specs/templates.md`）。
- 模板中通过 `{{ template "key" }}` 或 `{{ template "key" (dict "arg" .value) }}` 引用翻译，翻译 value 可含 HTML（按模板解析）。

示例（来自 docs）：

```json
{
  "email.sent": "Hi {1}, an email has been sent to {0}"
}
```

### 4.4 翻译解析（Translation Resolution）与模板解析的区别

- **模板解析**：按**文件**选——先类型再语言，选出一个模板文件。
- **翻译解析**：按**键**选——同一 key 在不同语言/变体中有不同 value，再按用户偏好语言做“键级”解析。

例如（docs 示例）：

- 存在 `zh` 与 `zh-Hant-HK` 的 `auth_ui_translation.json`，且用户偏好为 `["zh-Hant-HK"]`。
- key `"enter.password"`：在 zh-Hant-HK 有则用其值，否则回退到 zh 等。
- key `"enter.email"`：若 zh-Hant-HK 无，则用 zh 的“輸入電郵地址”。

即：**同一 key 在不同语言/地区文件中可只覆盖部分键**，解析时按偏好语言 + 回退链选出一个 value。

---

## 5. 翻译资源的 FS 层级与合并

翻译文件来自多级 **resource.Fs**（见 `pkg/util/template/translation.go`）：

- **FsLevelBuiltin** → **FsLevelCustom** → **FsLevelApp**（优先级递增）。
- 同一 key 高优先级 FS 覆盖低优先级；不同语言/变体在同一层级内再按“翻译解析”选一个 value。

两类键处理方式：

- **App-agnostic**：普通翻译键，按语言 + 上述层级合并后，再按 preferred + default 做 `intlresource.Match`。
- **App-specific**：匹配 `appSpecificKeysRegex` 的键（如 `app.name`、`email.*.sender`、`sms.*.sender`、terms-of-service-link 等），按 **FS 层级** 分别解析，再合并到最终 effective 翻译表。

翻译 value 会经 **messageformat** 解析（`pkg/util/messageformat`），用于 ICU 格式化；校验在 `viewValidateResource` 中做。

---

## 6. 使用翻译的服务流程

- **Context**：请求经 `IntlMiddleware` 后，context 中带有 `PreferredLanguageTags`。
- **TemplateEngine**（`pkg/util/template/engine.go`）：
  - `Translation(ctx, preferredLanguages)`：解析出当前请求的“有效翻译表”（已按 preferred + fallback 选好语言并合并层级），返回 `*TranslationMap`。
  - `TranslationMap` 提供 `RenderText(key, args)`、`HasKey(key)` 等，用于邮件、短信、页面文案。
- **translation.Service**（`pkg/lib/translation/service.go`）：
  - 从 context 取 `intl.GetPreferredLanguageTags(ctx)`，再调 TemplateEngine 的 Translation / Render。
  - 邮件、短信、WhatsApp 等文案都通过该 Service 渲染模板与翻译键。

---

## 7. 事件与 SSO 中的语言

- **Event**（`docs/specs/event.md`）：  
  - `preferred_languages`：请求中的界面语言偏好（ui_locales 或 Accept-Language）。  
  - `language`：最终使用的 locale，由偏好 + 应用 `supported_languages` 与 `fallback_language` 解析得到。
- **SSO**（`docs/specs/sso-providers.md`）：  
  - 部分 IdP 会返回 `locale`；需校验为合法 locale，否则去掉该字段。  
  - 与 OIDC 等对接时可能从 IdP 提取 `language` 作为 `locale`。

---

## 8. i18n 与 API Error 中 message 的关系

API 错误（`pkg/api/apierrors`）与面向用户的文案是**分离设计**：**服务端只提供稳定标识，展示文案由客户端通过 i18n 按语言解析**。

### 8.1 API Error 的结构（docs/error-handling.md）

| 字段 | 含义 | 是否 i18n |
|------|------|-----------|
| **name** | 对应 HTTP 状态的一类错误（如 Invalid、Unauthorized） | 否，固定标识 |
| **reason** | 具体错误种类标识（如 `PasswordPolicyViolated`、`ValidationFailed`） | 否，固定标识 |
| **message** | 面向**开发者**的说明，便于调试 | **否**，约定为英文，不用于界面展示 |
| **info** | 附加信息（如 **causes**：校验失败原因列表，每项有 `kind`、`details` 等） | 否，结构化数据 |

文档明确要求：*Message is a developer-facing message provided for ease of debugging. It should not contain extra useful information not found in reason/info, in order to prevent matching on the message by developers.*

因此：**API 的 `error.message` 不做国际化，也不应被前端直接当作最终用户可见文案使用。**

### 8.2 用户看到的错误文案从哪里来（i18n）

面向最终用户的错误文案**全部来自 i18n**，流程是：

1. **服务端**：只返回 `reason`（及 `info` 如 `causes`），不依赖 `message` 做界面语言。
2. **客户端**：根据 `reason`（以及 `info.causes` 的 `kind`、`location`、`details` 等）**映射到“翻译键”**，再用当前语言的翻译表查文案并展示。

两套客户端的实现方式：

| 端 | 翻译键来源 | 翻译表 | 说明 |
|----|------------|--------|------|
| **Auth UI（服务端渲染）** | 模板里按 `Error.reason` 与 `Error.info.causes` 分支，写死要调用的模板名（即翻译键），如 `error-password-required`、`error-login-id-required` | `templates/<locale>/translation.json` | 例如 `resources/authgear/templates/en/web/__error.html` 中根据 cause.kind、details.missing 等选择 `{{ template "error-xxx" }}`，该 key 在 translation.json 中有各语言版本。 |
| **Portal（前端）** | `portal/src/error/parse.ts` 等：`reason`（及 cause）→ **messageID**（如 `errors.validation.required`、`errors.password-policy.unknown`） | Portal 的 locale JSON（如 `locale-data/en.json`） | `ErrorMessageBar` / `ErrorRenderer` 用 `<FormattedMessage id={messageID} />` 查当前语言的文案。 |

共同点：**展示给用户的字符串都来自“翻译键 → 当前语言的翻译表”；API 的 `message` 仅作开发/调试用。**

### 8.3 小结关系

- **API Error.message**：非 i18n，英文、面向开发者，**不参与界面多语言**。
- **界面错误文案**：由 **reason（+ info）→ 翻译键 → i18n 翻译表** 得到；Auth UI 用服务端 `translation.json`，Portal 用前端 locale 文件。
- 新增或修改“用户可见错误文案”时，应改对应翻译键在各语言下的翻译，或在前端/模板中增加 reason→messageID / reason→template 的映射，**不要依赖或暴露 API 的 message 给最终用户**。

---

## 9. 小结

| 维度 | 说明 |
|------|------|
| **配置** | `localization.fallback_language`、`localization.supported_languages`（BCP47） |
| **请求偏好** | 优先 `ui_locales` query，否则 `Accept-Language`，解析后放入 context |
| **解析** | `intl.Resolve` / `intl.BestMatch` + `intlresource.Match`，保证 fallback 在 supported 首位 |
| **模板** | 按 `templates/<language_tag>/...` 组织，按类型+偏好语言选文件 |
| **翻译** | `templates/<language_tag>/translation.json`，扁平 JSON + ICU MessageFormat，按 key + 偏好语言 + FS 层级解析 |
| **使用** | IntlMiddleware → context → TemplateEngine.Translation() → translation.Service 渲染邮件/短信/UI 等 |
| **错误文案** | API 的 `message` 仅面向开发者；用户所见错误由 **reason(+info)→翻译键→i18n** 得到，不直接使用 `message`。 |

以上即 Authgear 服务端 i18n 的定义与主流程总结；Portal 前端另有 React Intl 等实现，可与本文档的服务端行为对应（如通过同一套 supported/fallback 与 ui_locales 约定）。
