# _auth_user 表中 standard_attributes 与 custom_attributes 说明

本文说明 `_auth_user` 表中 `standard_attributes`、`custom_attributes` 两列的约束方式、记录方式以及与彼此的关系。

## 1. 表结构与存储形式

### 1.1 数据库列

- **表名**：`_auth_user`
- **列**：
  - `standard_attributes`：`jsonb`，可为 NULL（见迁移 `20210921175056-add-standard-attributes.sql`）
  - `custom_attributes`：`jsonb`，可为 NULL（见迁移 `20211207160728-add_custom_attributes.sql`）

数据库层**没有** CHECK 或其它约束，类型与合法性完全由应用层保证。

### 1.2 写入与读取

- **写入**：Go 中 `map[string]interface{}` 经 `json.Marshal` 后写入对应列（见 `pkg/lib/authn/user/store.go` 的 `Create`、`UpdateStandardAttributes`、`UpdateCustomAttributes`）。
- **读取**：从 DB 读出的 `[]byte` 经 `json.Unmarshal` 得到 `StandardAttributes` / `CustomAttributes`（同上 store 的 `scan`）。

两列在应用里都表示为 `map[string]interface{}`，在 API 模型（如 `pkg/api/model/user.go`）中同样以 `StandardAttributes`、`CustomAttributes` 暴露。

---

## 2. standard_attributes 的约束与记录

### 2.1 配置约束（谁可以读写）

约束来自 **User Profile 配置** 中的 `standard_attributes`（`StandardAttributesConfig`），定义在 `pkg/lib/config/user_profile.go`：

- **StandardAttributesConfig** 包含：
  - `population`：何时填充（如 `on_signup`）
  - `access_control`：数组，每个元素为「JSON Pointer → 访问控制」

- **允许的 key（pointer）** 在 schema 中固定为下列 **enum**（即标准属性集合）：
  - `/email`, `/phone_number`, `/preferred_username`
  - `/family_name`, `/given_name`, `/picture`, `/gender`, `/birthdate`, `/zoneinfo`, `/locale`
  - `/name`, `/nickname`, `/middle_name`, `/profile`, `/website`, `/address`

- **访问控制**：每个 pointer 对应 `UserProfileAttributesAccessControl`（end_user / bearer / portal_ui 的 hidden | readonly | readwrite）。默认部分为 readwrite、部分为 hidden（见 `defaultReadwriteStandardAttributesPointers` / `defaultHiddenStandardAttributesPointers`）。

也就是说：**允许哪些标准属性、以及谁可以读写，由配置里的 `standard_attributes.access_control` 约束**，而不是数据库约束。

### 2.2 值的约束（类型与格式）

**类型与格式** 在代码里写死，在 `pkg/lib/authn/stdattrs/validate.go` 中通过 `validation.SimpleSchema` 校验：

- Schema 为 `type: object` 且 **additionalProperties: false**，即只允许预定义的 key。
- 每个 key 对应一个 `SchemaBuilder`，例如：
  - `email`：string, format email
  - `phone_number`：string, format phone
  - `preferred_username` / `family_name` / `given_name` 等：string, minLength 1
  - `picture`：string, format x_picture
  - `birthdate`：string, format birthdate
  - `zoneinfo`：string, format timezone
  - `locale`：string, format bcp47
  - `address`：嵌套对象（formatted, street_address, locality, region, postal_code, country 等）

写入前会调用 `stdattrs.Validate(ctx, stdattrs.T(stdAttrs))`，不通过则不会落库。

### 2.3 写入流程与“记录”方式

更新标准属性的入口在 `pkg/lib/feature/stdattrs/service_noevent.go` 的 `UpdateStandardAttributes`，大致步骤：

1. **去掉派生字段**：如 `email_verified`、`phone_number_verified`、`updated_at` 等不写入 DB，由系统派生。
2. **Representation → Storage**：通过 `Transformer.RepresentationFormToStorageForm` 做格式转换（若需要）。
3. **校验**：`stdattrs.Validate(ctx, stdattrs.T(stdAttrs))`（见上）。
4. **访问控制**：用 `UserProfileConfig.StandardAttributes.GetAccessControl()` 与当前 role 做 `CheckWrite`，禁止写入无权限的 pointer。
5. **身份相关唯一性**：对 email / phone_number / preferred_username 等，会结合 identity 的 claim 做唯一性/归属校验（避免占用他人已用值等）。
6. **落库**：`UserStore.UpdateStandardAttributes(ctx, userID, stdAttrs)`，将整份 `map[string]interface{}` 序列化为 JSON 写入 `_auth_user.standard_attributes`。

因此，**standard_attributes 的“记录”**就是：在应用层校验（schema + 访问控制 + 业务规则）通过后，把整份标准属性对象以 JSON 形式写入 `standard_attributes` 列；**key 即为属性名**（如 `email`、`given_name`），与 OIDC/配置中的 pointer 一致（去掉前导 `/`）。

---

## 3. custom_attributes 的约束与记录

### 3.1 配置约束（有哪些字段、类型、谁可读写）

约束来自 **User Profile 配置** 中的 `custom_attributes`（`CustomAttributesConfig`），同上在 `pkg/lib/config/user_profile.go`：

- **CustomAttributesConfig** 包含 `attributes` 数组。
- 每个元素 **CustomAttributesAttributeConfig** 包括：
  - **id**：字符串，在配置中唯一，用于**存储层 key**（见下）。
  - **pointer**：字符串，格式为 `x_custom_attribute_pointer`，且**不能**与标准 claim 的 pointer 重名（schema 里用 `not { enum: [ "/email", ... ] }` 等禁止）。
  - **type**：string | number | integer | enum | phone_number | email | url | country_code 等，决定校验与解析方式。
  - **access_control**：同 standard，end_user / bearer / portal_ui 的 hidden | readonly | readwrite。
  - 对 number/integer：可配 minimum、maximum；对 enum：配 enum 数组。

也就是说：**有哪些自定义属性、叫什么（id/pointer）、类型与取值范围、以及谁可以读写，都由配置里的 `custom_attributes.attributes` 约束**。

### 3.2 值的约束（类型与格式）

校验是**按配置动态生成 JSON Schema** 的，在 `pkg/lib/feature/customattrs/service_noevent.go` 中：

- `generateSchemaString(pointers)` 根据当前 app 的 `CustomAttributes.Attributes` 为给定 pointers 生成 schema（用各 attribute 的 `ToSchemaBuilder()`，如 string/email/phone/number/enum 等）。
- `validate(ctx, pointers, input)` 用 `SchemaValidator` 对该 schema 校验传入的 representation form。

只有通过校验的 custom 属性才会进入后续“合并当前用户 + 访问控制”并写入 DB。

### 3.3 存储形式：representation form vs storage form

- **Representation form（API / 业务层）**：以 **pointer** 为路径的键值结构（如 `/x_custom_attr_key` 对应的值），便于与标准属性一致地按 pointer 读写。
- **Storage form（数据库）**：以配置里定义的 **id** 为 key 的 `map[string]interface{}`，即 **custom_attributes 列里存的 JSON 的 key 是 id，不是 pointer**。

转换在 `pkg/lib/feature/customattrs/service_noevent.go` 中：

- **toStorageForm(t)**：按配置中每个 attribute 的 pointer 从 `t` 中取值，写入 `out[c.ID]`。
- **fromStorageForm(storageForm)**：按配置中每个 attribute 的 `c.Pointer` 与 `storageForm[c.ID]` 写回 representation。

因此，**custom_attributes 的“记录”**是：在应用层校验（动态 schema + 访问控制）通过后，将 **representation form 转成 storage form（id 为 key）**，再整份序列化写入 `_auth_user.custom_attributes` 列。

---

## 4. standard_attributes 与 custom_attributes 的关系

| 维度 | standard_attributes | custom_attributes |
|------|---------------------|-------------------|
| 配置位置 | `user_profile.standard_attributes` | `user_profile.custom_attributes` |
| 字段集合 | 固定 16 个 pointer（OIDC 标准 + address 子字段） | 由配置的 `attributes[].id/pointer` 定义，且 pointer 禁止与标准 claim 重名 |
| Schema | 代码内固定 schema，additionalProperties: false | 按配置动态生成 schema（类型、enum、min/max 等） |
| 访问控制 | 同源：UserProfileAttributesAccessControl（end_user/bearer/portal_ui） | 同源：同一套 AccessControl 模型 |
| 存储 key | 属性名（与 pointer 去掉 `/` 一致） | **id**（配置中的 id），不是 pointer |
| 写入入口 | StdAttrsService.UpdateStandardAttributes / UpdateStandardAttributesWithList | CustomAttrsService.UpdateCustomAttributes / UpdateCustomAttributesWithList |
| 是否同列 | 否，两列独立 | 否，两列独立 |

**关系小结**：

- **并列、独立**：同属 `UserProfileConfig` 下两套配置，在 `_auth_user` 中对应两列，互不覆盖；业务上可同时使用标准属性与自定义属性。
- **约束方式一致**：都由应用配置定义「哪些字段、谁可读写」，由应用代码做类型/格式校验；DB 只存 JSON，不做约束。
- **区别**：标准属性固定集合、固定 schema、存的是“属性名”；自定义属性由配置定义集合与类型、存的是“配置 id”，通过 toStorageForm/fromStorageForm 与 pointer 形式互转。

---

## 5. 相关代码索引

- 表与列：`cmd/authgear/cmd/cmddatabase/migrations/authgear/` 下 `20210921175056-add-standard-attributes.sql`、`20211207160728-add_custom_attributes.sql`。
- 持久化：`pkg/lib/authn/user/store.go`（Create/UpdateStandardAttributes/UpdateCustomAttributes/scan）。
- 配置与访问控制：`pkg/lib/config/user_profile.go`（StandardAttributesConfig、CustomAttributesConfig、access_control、SetDefaults、GetAccessControl）。
- 标准属性校验与模型：`pkg/lib/authn/stdattrs/validate.go`、`model.go`；更新逻辑 `pkg/lib/feature/stdattrs/service_noevent.go`。
- 自定义属性校验与存储形式：`pkg/lib/feature/customattrs/service_noevent.go`（validate、toStorageForm、fromStorageForm）；模型 `pkg/lib/authn/customattrs/model.go`。
- 认证流中同时更新两者：`pkg/lib/authenticationflow/declarative/node_do_update_user_profile.go`（StandardAttributes + CustomAttributes 分别调 StdAttrsService / CustomAttrsService）。
