# Authgear 学习计划 · IdaaS 平台研发参考

基于 `.local` 文档，面向自研 IdaaS（Identity as a Service）平台的 Authgear 学习路径。

---

## 一、学习目标

| 目标                                | 对应 IdaaS 能力             |
| --------------------------------- | ----------------------- |
| 理解多租户隔离                           | 租户/应用级数据隔离              |
| 掌握 User-Identity-Authenticator 模型 | 统一身份、多登录方式、多认证因子        |
| 理解认证流程引擎                          | 可配置 Signup/Login/Reauth |
| 掌握 Session/SSO 设计                 | 单点登录、会话共享               |
| 理解 RBAC 与资源授权                     | 角色、组、OAuth scope        |
| 掌握审计与合规                           | 审计日志、数据保留               |

---

## 二、学习路径（按顺序）

### 第 1 周：概念与数据模型

#### Day 1–2：核心术语与模型

1. **阅读**
   - [model/00-模型总览.md](./model/00-模型总览.md)
   - [docs/app_id说明.md](./docs/app_id说明.md)
2. **重点**
   - User / Identity / Authenticator 三者关系
   - app_id 多租户隔离
   - 有界上下文划分（Auth / RBAC / Portal / Audit）
3. **IdaaS 对照**
   - 租户 ID 设计（类似 app_id）
   - 身份统一模型（多身份源、多认证方式）

#### Day 3–4：数据库模型

1. **阅读**
   - [docs/数据库模型总览.md](./docs/数据库模型总览.md)
   - [docs/数据库模型-核心认证.md](./docs/数据库模型-核心认证.md)
   - [docs/数据库模型-RBAC.md](./docs/数据库模型-RBAC.md)
   - [database/authgear_schema.sql](./database/authgear_schema.sql)（核心表）
2. **重点**
   - _auth_user / _auth_identity / _auth_authenticator 表结构
   - 身份类型：login_id, oauth, passkey, anonymous, biometric, ldap, siwe
   - 认证器类型：password, totp, oob, passkey
   - RBAC：role, group, user_role, user_group, group_role
3. **IdaaS 对照**
   - 设计租户维度 ER 图
   - 规划 Identity/Authenticator 扩展点（如 LDAP、SAML）

#### Day 5–7：DDD 领域划分

1. **阅读**
   - [ddd/01-DDD总览.md](./ddd/01-DDD总览.md)
   - [ddd/领域模型设计.md](./ddd/领域模型设计.md)
   - [ddd/聚合关系图.md](./ddd/聚合关系图.md)
   - [model/01-Auth领域.md](./model/01-Auth领域.md)
2. **重点**
   - User / Session / AuthenticationFlow / OAuth / MFA 聚合
   - 聚合根与不变式
   - 跨聚合引用（user_id, session_id 等）
3. **IdaaS 对照**
   - 划分 IdaaS 的聚合边界
   - 确定聚合根与仓储接口

---

### 第 2 周：认证流程与 Session

#### Day 1–2：认证流程

1. **阅读**
   - [ddd/认证流程领域设计.md](./ddd/认证流程领域设计.md)
   - [ddd/认证流程时序图.md](./ddd/认证流程时序图.md)
   - [ddd/认证流程状态机.md](./ddd/认证流程状态机.md)
2. **重点**
   - 流程状态机：未开始 → 初始化 → 执行步骤 → 等待输入 → 处理输入 → 创建会话 → 完成
   - StateToken 与 AcceptInput 交互
   - Signup / Login / OAuth Login 差异
3. **IdaaS 对照**
   - 定义认证流程状态机
   - 设计声明式流程配置（步骤、分支）

#### Day 3–4：Session 与 SSO

1. **阅读**
   - [ddd/Session管理领域设计.md](./ddd/Session管理领域设计.md)
   - [ddd/Session生命周期图.md](./ddd/Session生命周期图.md)
   - [ddd/SSO领域设计.md](./ddd/SSO领域设计.md)
   - [ddd/SSO流程时序图.md](./ddd/SSO流程时序图.md)
2. **重点**
   - IdPSession / OfflineGrant / AccessGrant 职责
   - Session 生命周期：创建 → 活跃 → 空闲/过期/失效
   - SSO 组：IdPSession 删除时关联 OfflineGrant 失效
3. **IdaaS 对照**
   - Session 模型（IdP Session / 刷新令牌 / 访问令牌）
   - SSO 域/组策略（单点登出、跨应用共享）

#### Day 5–7：OAuth 与 Social Login

1. **阅读**
   - [ddd/Social Login领域设计.md](./ddd/Social Login领域设计.md)
   - [ddd/OAuth流程时序图.md](./ddd/OAuth流程时序图.md)
   - [docs/数据库模型-Social Login关系说明.md](./docs/数据库模型-Social%20Login关系说明.md)
   - [docs/数据库表-_auth_identity_oauth详细说明.md](./docs/数据库表-_auth_identity_oauth详细说明.md)
2. **重点**
   - OAuth 授权码流程（新用户 / 账户关联）
   - Identity Attributes（email/phone）用于账户关联
   - AccountManagementToken 添加 OAuth 身份
3. **IdaaS 对照**
   - 第三方 IdP 接入（Google/微信等）
   - 账户关联策略（同 email/phone 自动关联）

---

### 第 3 周：MFA、RBAC、审计

#### Day 1–2：MFA

1. **阅读**
   - [ddd/MFA领域设计.md](./ddd/MFA领域设计.md)
   - [ddd/MFA流程时序图.md](./ddd/MFA流程时序图.md)
   - [ddd/设备管理设计.md](./ddd/设备管理设计.md)
2. **重点**
   - TOTP / OOB-OTP / Recovery Code
   - Device Token 信任设备（跳过 MFA）
   - MFA 策略：disabled / required / if_exists
3. **IdaaS 对照**
   - MFA 配置与策略
   - 设备信任与恢复码

#### Day 3–4：RBAC 与资源授权

1. **阅读**
   - [docs/数据库模型-RBAC.md](./docs/数据库模型-RBAC.md)
   - [docs/数据库模型-资源范围.md](./docs/数据库模型-资源范围.md)
   - [model/01-Auth领域.md](./model/01-Auth领域.md)（Role/Group/Resource 聚合）
2. **重点**
   - Role / Group / UserRole / UserGroup / GroupRole
   - Resource / ResourceScope / ClientResource / ClientResourceScope
   - OAuth scope 与资源范围映射
3. **IdaaS 对照**
   - 租户内 RBAC 模型
   - API 资源与 scope 授权设计

#### Day 5–7：审计、Portal、多租户

1. **阅读**
   - [model/02-Audit领域.md](./model/02-Audit领域.md)
   - [docs/数据库模型-审计和搜索.md](./docs/数据库模型-审计和搜索.md)
   - [model/03-Portal领域.md](./model/03-Portal领域.md)
   - [docs/数据库模型-Portal.md](./docs/数据库模型-Portal.md)
2. **重点**
   - 审计日志分区、只写
   - ConfigSource / Domain / Subscription / Plan
   - Portal 协作、邀请、用量
3. **IdaaS 对照**
   - 审计日志模型与合规
   - 管理后台（租户/应用/订阅）

---

### 第 4 周：整合与 IdaaS 设计

#### Day 1–2：架构整合

1. **梳理**
   - 有界上下文依赖（认证 ↔ 身份 ↔ 会话 ↔ OAuth ↔ MFA）
   - 关键时序：Signup / Login / OAuth / MFA / SSO / 登出
2. **对照**
   - [ddd/聚合关系图.md](./ddd/聚合关系图.md)
   - [model/00-模型总览.md](./model/00-模型总览.md)

#### Day 3–5：IdaaS 设计草案

1. **数据模型**
   - 租户 / 应用 / 用户 / 身份 / 认证器
   - RBAC / 资源 / 审计
2. **核心流程**
   - 注册 / 登录 / 社交登录 / MFA / SSO
3. **多租户**
   - 租户隔离、配置隔离、域名解析
4. **审计与合规**
   - 日志字段、保留策略、搜索

#### Day 6–7：代码对照（可选）

若需对照实现，可结合 authgear-server 源码：

- 术语表：`docs/specs/glossary.md`
- 用户模型：`docs/specs/user-model.md`
- 认证机制：`docs/authn.md`
- 认证流程：`docs/specs/authentication-flow.md`
- 核心代码：`pkg/lib/authn/`、`pkg/lib/session/`、`pkg/lib/authenticationflow/`

---

## 三、IdaaS 研发对照清单

| 能力 | 参考文档 | 关键设计点 |
|------|----------|------------|
| 多租户 | app_id说明, model/00 | 租户 ID、SQL 自动过滤、配置隔离 |
| 身份模型 | model/01, 数据库模型-核心认证 | User-Identity-Authenticator、类型扩展 |
| RBAC | model/01, 数据库模型-RBAC | Role/Group、用户-组-角色 |
| 认证流程 | 认证流程领域设计, 认证流程时序图 | 状态机、StateToken、声明式配置 |
| Session | Session管理领域设计, Session生命周期图 | IdPSession、OfflineGrant、过期策略 |
| SSO | SSO领域设计, SSO流程时序图 | SSO 组、登出联动 |
| OAuth/Social | Social Login, OAuth流程时序图 | 授权码流程、账户关联 |
| MFA | MFA领域设计, MFA流程时序图 | TOTP/OOB/Recovery、Device Token |
| 资源授权 | model/01, 数据库模型-资源范围 | Resource/Scope、客户端授权 |
| 审计 | model/02, 数据库模型-审计和搜索 | 只写、分区、统计 |
| 管理后台 | model/03, 数据库模型-Portal | 应用、订阅、协作、域名 |

---

## 四、推荐阅读顺序（速览）

```
1. model/00-模型总览.md
2. docs/app_id说明.md
3. docs/数据库模型总览.md
4. ddd/01-DDD总览.md
5. ddd/聚合关系图.md
6. ddd/认证流程领域设计.md + 认证流程时序图.md
7. ddd/Session管理领域设计.md + Session生命周期图.md
8. ddd/SSO领域设计.md + SSO流程时序图.md
9. ddd/Social Login领域设计.md + OAuth流程时序图.md
10. ddd/MFA领域设计.md + MFA流程时序图.md
11. model/01-Auth领域.md（RBAC、Resource）
12. model/02-Audit领域.md + model/03-Portal领域.md
```

---

## 五、学习检查清单

- [ ] 能画出 User-Identity-Authenticator 关系图
- [ ] 理解 app_id 多租户隔离机制
- [ ] 能描述 Signup / Login / OAuth 流程差异
- [ ] 理解 IdPSession / OfflineGrant 职责与生命周期
- [ ] 理解 SSO 组与登出联动
- [ ] 能设计 IdaaS 的认证流程状态机
- [ ] 能设计 IdaaS 的 RBAC 与资源授权模型
- [ ] 能设计 IdaaS 的审计日志表结构
- [ ] 能写出 IdaaS 核心聚合与仓储接口
