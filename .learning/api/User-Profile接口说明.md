# Authgear User Profile 接口说明

本文档整理 Authgear 与**用户资料（User Profile）**相关的接口，以及**如何读取与修改用户基本信息**。用户资料包含 **标准属性（standard_attributes）** 与 **自定义属性（custom_attributes）**。

---

## 一、User Profile 相关接口总览

| 用途 | 接口/入口 | 认证方式 | 说明 |
|------|-----------|----------|------|
| **读取** 当前用户资料 | `GET /oauth2/userinfo` | Bearer（Access Token） | OIDC User Info 端点，返回 standard_attributes + custom_attributes（按 access_control 过滤） |
| **端用户修改** 资料 | Web 设置页 `/settings/profile` | Cookie / 已登录会话 | 在 Auth UI 中编辑标准属性、自定义属性 |
| **端用户修改** 资料（按变体） | `/settings/profile/:variant/edit` | Cookie / 已登录会话 | `variant` 如标准属性页或 `custom_attributes`，表单 POST 提交 |
| **端用户** 头像上传 | `POST /api/images/upload` | Cookie 或 Bearer | 获取 presign 上传 URL，上传后需将返回的图片 URL 写入用户 `picture` 等属性（通过设置页或 Admin API） |
| **服务端/管理端修改** 任意用户资料 | Admin API GraphQL `updateUser` | Admin API JWT | 可更新 `standardAttributes`、`customAttributes`（整对象替换） |
| **流程内** 写入资料 | Authentication Flow API 的 `user_profile` 步骤 | 流程 state token | 在注册等流程中收集并写入 standard/custom attributes |

说明：

- **Account Management API** 在 [account-management-api.md](../../docs/specs/account-management-api.md) 中列出「Manage user profile」：Update simple standard attributes、Update custom attributes、Add/Replace/Remove user profile picture。当前仓库内已实现的 **REST 路径** 主要为 `/api/v1/account/identification` 与 `/api/v1/account/identification/oauth`；独立的 profile 更新 REST 端点（如 `PATCH /api/v1/account/profile`）未在代码中找到，端用户修改资料目前主要通过 **Web 设置页** 或 **Authentication Flow** 完成。
- **Resolver**（`/_resolver/resolve`）不返回 profile 内容，只返回 session/user 等头信息；完整资料需通过 User Info 或 Admin API 获取。

---

## 二、读取用户基本信息

### 2.1 OAuth2 User Info（端用户 / 第三方读当前用户）

- **路径**：`GET /oauth2/userinfo`
- **认证**：`Authorization: Bearer <access_token>`
- **返回**：JSON，根级别为标准属性（如 `sub`、`email`、`given_name`、`family_name`、`picture` 等），`custom_attributes` 为自定义属性对象；另含 [Special Claims](https://docs.authgear.com/reference/apis/user-info)（如 roles、is_anonymous、is_verified 等）。
- **权限**：按项目配置的 **access_control**（end_user / bearer / portal_ui）决定哪些字段对「session bearer」可见。详见 [user-profile/design.md](../../docs/specs/user-profile/design.md#access-control)。

示例（片段）：

```json
{
  "sub": "user_id",
  "email": "user@example.com",
  "email_verified": true,
  "given_name": "John",
  "family_name": "Doe",
  "picture": "https://...",
  "custom_attributes": {
    "hobby": "reading"
  },
  "https://authgear.com/claims/user/roles": ["manager"]
}
```

### 2.2 Admin API 查用户（含 profile）

- **路径**：`POST /_api/admin/graphql`
- **认证**：Admin API JWT
- **方式**：GraphQL 查询，例如通过 `user(id)` 或 `users` 等获取用户节点，其 `standardAttributes`、`customAttributes` 即完整 profile（管理端无 access_control 限制）。

---

## 三、如何修改用户基本信息

### 3.1 端用户修改「自己」的资料

- **方式一：Auth UI 设置页（推荐）**  
  - 打开 `/settings/profile`，编辑标准属性或自定义属性；  
  - 提交后请求会到 `/settings/profile/:variant/edit`（POST），服务端调用 `UpdateStandardAttributes` / `UpdateCustomAttributesWithForm`，仅能改当前登录用户，且受 access_control（end_user readwrite）限制。

- **方式二：头像**  
  - 调用 `POST /api/images/upload` 获取上传 URL，上传完成后得到图片 URL；  
  - 将该 URL 写入用户的标准属性 `picture`（或配置的其它字段）：目前需通过设置页或 Admin API 写入，Account Management API 若提供独立 profile 接口则也可用该接口更新。

- **方式三：Authentication Flow**  
  - 在 Signup 等流程中配置 `user_profile` 步骤，用户在流程内填写资料，服务端在流程中调用 `UpdateStandardAttributesWithList` / `UpdateCustomAttributesWithList` 写入；适用于注册时一次性收集资料。

### 3.2 服务端 / 管理端修改任意用户资料

- **Admin API GraphQL：`updateUser`**
  - **Mutation**：`updateUser(input: UpdateUserInput!)`
  - **Input**：
    - `userID`：目标用户 ID（必填）
    - `standardAttributes`：整个 standard_attributes 对象（可选，传入即整体替换）
    - `customAttributes`：整个 custom_attributes 对象（可选，传入即整体替换）
  - **说明**：Admin API 具有完整权限，可更新任意用户的 standard/custom attributes；会触发 `user.profile.pre_update`（blocking）、`user.profile.updated`（non-blocking）等事件。
  - **代码参考**：`pkg/admin/graphql/user_mutation.go`（updateUser）、`pkg/admin/facade/user_profile.go`（UpdateUserProfile）。

示例（GraphQL）：

```graphql
mutation UpdateUser($input: UpdateUserInput!) {
  updateUser(input: $input) {
    user {
      id
      standardAttributes
      customAttributes
    }
  }
}
```

变量示例：

```json
{
  "input": {
    "userID": "VXNlck5vZGU6eHh4",
    "standardAttributes": {
      "given_name": "张",
      "family_name": "三",
      "name": "张三",
      "locale": "zh-Hans"
    },
    "customAttributes": {
      "department": "engineering"
    }
  }
}
```

注意：这里传入的是「整对象」替换，未传的 key 可能被清空，取决于服务端实现（当前 facade 为按传入的 map 更新对应部分，未传的字段不一定会被删，需以实际代码为准）。若只改部分字段，建议先查当前用户再合并后调用 `updateUser`。

---

## 四、标准属性与自定义属性简要说明

- **标准属性**：OIDC 标准声明，如 `name`、`given_name`、`family_name`、`email`、`email_verified`、`phone_number`、`picture`、`locale`、`zoneinfo`、`birthdate`、`gender`、`address.*` 等。部分与身份绑定（如 email/phone_number/preferred_username）通常通过「身份管理」变更，其余可在 profile 中编辑。详见 [user-profile/design.md](../../docs/specs/user-profile/design.md#standard-attributes)。
- **自定义属性**：在项目中配置的扩展字段（如 `/x_phone_number`、`/department`），类型可为 string、integer、number、enum、phone_number、email、url、alpha2 等。详见 [user-profile/design.md](../../docs/specs/user-profile/design.md#custom-attributes)。

---

## 五、相关文档与代码索引

| 内容 | 路径 |
|------|------|
| Account Management API 总览（含 Manage user profile 列表） | [docs/specs/account-management-api.md](../../docs/specs/account-management-api.md) |
| User Profile 设计（标准/自定义属性、access_control、User Info） | [docs/specs/user-profile/design.md](../../docs/specs/user-profile/design.md) |
| Admin API（GraphQL） | [docs/specs/api-admin.md](../../docs/specs/api-admin.md) |
| 事件：user.profile.pre_update / user.profile.updated | [docs/specs/event.md](../../docs/specs/event.md) |
| 本目录 API 教程与 DDD 映射 | [.learning/api/API教程.md](./API教程.md)、[.learning/api/API与DDD映射.md](./API与DDD映射.md) |
| 服务端更新 profile 门面 | `pkg/admin/facade/user_profile.go`（UpdateUserProfile） |
| Admin GraphQL updateUser | `pkg/admin/graphql/user_mutation.go` |
| Web 设置页 profile 编辑 | `pkg/auth/handler/webapp/settings_profile_edit.go`、`authflowv2/settings_profile_edit.go` |
| 图片上传 API | `pkg/auth/handler/api/presign_images_upload.go`（`/api/images/upload`） |

---

## 六、小结

- **读用户基本信息**：端用户/第三方用 `GET /oauth2/userinfo` + Bearer Token；管理端用 Admin GraphQL 查用户。
- **改用户基本信息**：  
  - **端用户**：主要用 Auth UI `/settings/profile`（及 `/settings/profile/:variant/edit`），头像用 `/api/images/upload` 再写入 picture；流程内用 Authentication Flow 的 `user_profile` 步骤。  
  - **服务端/管理端**：用 Admin API 的 **`updateUser`** mutation，传入 `standardAttributes` / `customAttributes` 更新目标用户资料。
