# GET userinfo 设计确认 — 时序图与流程图

本目录中的 Mermaid 图来自计划文档「GET userinfo 设计确认」，描述 Authgear 从 `_auth_user` 到 userinfo 返回、以及写请求到落库的流程。

## 图表列表

| 文件 | 类型 | 说明 |
|------|------|------|
| `01-auth_user-to-raw-entity.mmd` | flowchart | 数据库 `_auth_user` 表与 raw 实体（StandardAttributes / CustomAttributes）的对应关系 |
| `02-entity-to-userinfo.mmd` | flowchart | 从 user.Queries + UserInfoService 到 model.User / UserInfo 的聚合（按 role 派生、读 standard/custom） |
| `03-write-standard-attributes-webapp.mmd` | sequenceDiagram | Webapp 设置页保存 standard_attributes：Handler → StdAttrsService → NoEvent → Store → DB，以及事件派发 |
| `04-write-custom-attributes-webapp.mmd` | sequenceDiagram | Webapp 设置页保存 custom_attributes：Handler → CustomAttrsService → NoEvent → Store → DB |
| `05-write-admin-updateUser.mmd` | sequenceDiagram | Admin API updateUser：GraphQL → UserProfileFacade → StdAttrs/CustomAttrs → Store → DB，以及事件 |
| `06-blocking-hook-user-profile.mmd` | sequenceDiagram | 事务提交后 Blocking Hook（UserProfilePreUpdate）：EventService → HookSink → Hook → PerformEffectsOnUser → 再次落库 |

## 使用方式

- 扩展名为 `.mmd`，可用 [Mermaid CLI](https://github.com/mermaid-js/mermaid-cli)、VS Code 插件或支持 Mermaid 的 Markdown 渲染器打开。
- 在 Markdown 中引用示例：` ```mermaid ` 代码块内粘贴对应 `.mmd` 内容即可渲染。
