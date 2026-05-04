# User Profile Attributes — Java 21 Spring Boot 组件

本模块是 Authgear Server 中 **\_auth_user 表 standard_attributes / custom_attributes** 设计的 Java 21 Spring Boot 实现，用于学习与对照。

## 设计来源

- 设计说明：`../docs/_auth_user表-standard_attributes与custom_attributes说明.md`
- Authgear 实现：`pkg/lib/config/user_profile.go`、`pkg/lib/authn/user/store.go`、`pkg/lib/authn/stdattrs/`、`pkg/lib/feature/stdattrs/`、`pkg/lib/feature/customattrs/`

## 技术栈

- **Java 21**
- **Spring Boot 3.2.x**
- **Spring Data JPA** + Hibernate 6.2（`@JdbcTypeCode(SqlTypes.JSON)` 存 JSON）
- **H2** 内存库（可换 PostgreSQL 使用 jsonb）
- **Lombok**（可选）

## 模块结构

```
src/main/java/learning/userprofile/
├── UserProfileAttributesApplication.java   # 启动类
├── config/                                   # 配置模型（对应 Authgear UserProfileConfig）
│   ├── AccessControlLevel.java               # hidden | readonly | readwrite
│   ├── ProfileRole.java                      # end_user | bearer | portal_ui
│   ├── UserProfileAttributesAccessControl.java
│   ├── StandardAttributesAccessControlEntry.java
│   ├── StandardAttributesConfig.java         # population + access_control
│   ├── CustomAttributeDefinition.java        # id, pointer, type, access_control
│   ├── CustomAttributesConfig.java
│   ├── CustomAttributeType.java
│   ├── UserProfileProperties.java            # @ConfigurationProperties
│   └── UserProfileConfig.java                # 启用配置
├── domain/
│   └── AuthUser.java                         # 实体，standard_attributes / custom_attributes 为 JSON 列
├── repository/
│   └── AuthUserRepository.java
├── validation/
│   ├── StandardAttributesValidator.java      # 固定 key + 格式校验
│   └── CustomAttributesValidator.java        # 配置驱动类型/范围校验
├── service/
│   ├── CustomAttributesFormConverter.java    # representation (pointer) <-> storage (id)
│   ├── StandardAttributesService.java        # 更新/读取 + 访问控制
│   └── CustomAttributesService.java         # 更新/读取 + 存储形式转换 + 访问控制
```

## 核心对应关系

| Authgear | 本模块 |
|----------|--------|
| `_auth_user.standard_attributes` (jsonb) | `AuthUser.standardAttributes` (Map, JSON 列) |
| `_auth_user.custom_attributes` (jsonb) | `AuthUser.customAttributes` (Map, JSON 列) |
| `UserProfileConfig` | `UserProfileProperties` (YAML) |
| `StandardAttributesConfig` + access_control | `StandardAttributesConfig` + `getLevel(pointer, role)` |
| `CustomAttributesConfig` + attributes[].id/pointer/type | `CustomAttributesConfig` + `CustomAttributeDefinition` |
| stdattrs.Validate | `StandardAttributesValidator.validate` |
| customattrs validate (动态 schema) | `CustomAttributesValidator.validate` |
| toStorageForm / fromStorageForm | `CustomAttributesFormConverter` |
| StdAttrsService.UpdateStandardAttributes | `StandardAttributesService.updateStandardAttributes` |
| CustomAttrsService.UpdateCustomAttributesWithList | `CustomAttributesService.updateCustomAttributes` |

## 运行

```bash
cd .learning/java
mvn spring-boot:run
```

默认使用 H2 内存库，表 `auth_user` 由 JPA 自动建表。如需 PostgreSQL，修改 `application.yml` 中 `datasource` 与 `jpa.database-platform`，并确保 JSON 列类型与方言兼容（Hibernate 6.2 对 PostgreSQL 的 jsonb 支持良好）。

## 配置示例

见 `src/main/resources/application.yml` 中 `app.user-profile`：

- **standard_attributes**：population.strategy、access_control（pointer + end_user/bearer/portal_ui 级别）
- **custom_attributes**：attributes[].id、pointer、type、access_control，以及 number/integer 的 minimum/maximum、enum 的 enum 列表

## 与 Authgear 的差异（简化处）

1. **身份相关唯一性**：未实现 email/phone_number/preferred_username 的跨用户唯一性校验，仅做格式与访问控制。
2. **Representation form**：自定义属性使用单层 key（pointer 去掉 `/`），未实现深层 JSON Pointer 遍历。
3. **事件**：无 UserProfileUpdated 等事件发布。
4. **Population**：仅配置模型存在，未接注册流程的 on_signup 填充逻辑。

详细设计对照见 `docs/DESIGN.md`。
