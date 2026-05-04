# 设计对照：Authgear → Java 21 Spring Boot

本文档将 Authgear 的 **standard_attributes / custom_attributes** 设计与本 Java 模块逐项对照，便于移植与扩展。

## 1. 表与列

| Authgear | Java 模块 |
|----------|-----------|
| 表 `_auth_user` | 实体 `AuthUser`，表名 `auth_user`（可改） |
| 列 `standard_attributes` jsonb | `AuthUser.standardAttributes`，`@JdbcTypeCode(SqlTypes.JSON)`，类型 `Map<String, Object>` |
| 列 `custom_attributes` jsonb | `AuthUser.customAttributes`，同上 |
| 无 DB 约束 | 无：校验在应用层 |

## 2. 配置模型

| Authgear | Java 模块 |
|----------|-----------|
| `UserProfileConfig` | `UserProfileProperties`（`@ConfigurationProperties(prefix = "app.user-profile")`） |
| `StandardAttributesConfig`：population + access_control[] | `StandardAttributesConfig`：population + accessControl（List） |
| `StandardAttributesAccessControlConfig`：pointer + access_control | `StandardAttributesAccessControlEntry`：pointer + accessControl |
| `UserProfileAttributesAccessControl`：end_user, bearer, portal_ui | `UserProfileAttributesAccessControl`：endUser, bearer, portalUi（enum） |
| `AccessControlLevelString`：hidden/readonly/readwrite | `AccessControlLevel` enum |
| 默认 readwrite pointers（email, given_name, …） | `StandardAttributesConfig.setDefaults()` 中 DEFAULT_READWRITE_POINTERS |
| 默认 hidden pointers（name, nickname, …） | DEFAULT_HIDDEN_POINTERS |
| `CustomAttributesConfig` + attributes[] | `CustomAttributesConfig` + attributes（List<CustomAttributeDefinition>） |
| `CustomAttributesAttributeConfig`：id, pointer, type, access_control, minimum, maximum, enum | `CustomAttributeDefinition`：同名字段 |

## 3. 标准属性校验

| Authgear | Java 模块 |
|----------|-----------|
| `stdattrs.Validate`：SimpleSchema，additionalProperties: false | `StandardAttributesValidator.validate`：仅允许 ALLOWED_KEYS，未知 key 抛错 |
| 各 key 的 SchemaBuilder（email format, phone, minLength, birthdate, address 子字段） | `StandardAttributesValidator` 内按 key 做格式校验（正则、类型、address 子 key） |
| 派生字段不写入：email_verified, phone_number_verified, updated_at | `StandardAttributesValidator.withDerivedRemoved` 从 map 中移除上述 key |

## 4. 自定义属性校验

| Authgear | Java 模块 |
|----------|-----------|
| 按 config 的 type 动态生成 JSON Schema，再 SchemaValidator 校验 | `CustomAttributesValidator`：按 `CustomAttributeDefinition.type` 做类型与范围校验（string/number/integer/enum/email/phone/url/country_code） |
| pointer 禁止与标准 claim 重名 | 配置约定（未在代码中强制枚举禁止列表） |

## 5. 存储形式（自定义属性）

| Authgear | Java 模块 |
|----------|-----------|
| **Representation form**：key = pointer 路径（如 /x_company_id 对应一层 key） | API/服务层使用 Map，key = pointer 去掉 `/`（如 x_company_id） |
| **Storage form**：key = config 的 id | `CustomAttributesFormConverter.toStorageForm`：按 config 遍历，从 representation 按 pointer 取值写入 `out[attr.getId()]` |
| fromStorageForm：storageForm[attr.getId()] → pointer 写入 representation | `CustomAttributesFormConverter.fromStorageForm`：同上反方向 |

## 6. 访问控制

| Authgear | Java 模块 |
|----------|-----------|
| stdattrs.T.CheckWrite(accessControl, role, that) | `StandardAttributesService.updateStandardAttributes`：对每个要写的 key 查 `getLevel("/" + key, role)`，非 READWRITE 抛 SecurityException |
| 读时按 role 过滤 hidden | `StandardAttributesService.readStandardAttributes`：只返回 READONLY/READWRITE 的 key |
| customattrs T.Update(accessControl, role, pointers, incoming) | `CustomAttributesService.updateCustomAttributes`：对每个 pointer 查 level，非 READWRITE 抛错；读时同样按 role 过滤 |

## 7. 写入流程

**标准属性（Authgear）**  
1. 去掉派生字段 → 2. Representation→Storage 转换（若有）→ 3. stdattrs.Validate → 4. GetAccessControl + CheckWrite → 5. 身份相关唯一性（email/phone/preferred_username）→ 6. UserStore.UpdateStandardAttributes

**标准属性（本模块）**  
1. withDerivedRemoved → 2. StandardAttributesValidator.validate → 3. 对每个 key getLevel(pointer, role)，非 READWRITE 拒绝 → 4. 合并到现有 standardAttributes 并 save（未做身份唯一性）

**自定义属性（Authgear）**  
1. validate(pointers, reprForm) → 2. fromStorageForm 得到当前 representation → 3. Update(accessControl, role, pointers, incoming) → 4. toStorageForm → 5. UserStore.UpdateCustomAttributes

**自定义属性（本模块）**  
1. CustomAttributesValidator.validate(pointers, representationForm) → 2. 对每个 pointer 检查 READWRITE → 3. 合并到当前 representation → 4. toStorageForm → 5. user.setCustomAttributes(storageForm); save

## 8. 未实现或简化

- **身份相关唯一性**：email/phone_number/preferred_username 的跨用户唯一、归属 identity 未实现。
- **深层 JSON Pointer**：自定义属性 representation 仅支持单层 key（pointer 取第一段），未做完整 JSON Pointer 遍历。
- **事件**：无 UserProfileUpdated 等事件。
- **Population strategy**：仅配置存在，未在注册流程中做 on_signup 填充。
- **Transformer**：标准属性未做 RepresentationFormToStorageForm 的额外转换（如 phone 归一化等）。

按需可在本模块上补上述逻辑，或参考 Authgear 对应 pkg 实现。
