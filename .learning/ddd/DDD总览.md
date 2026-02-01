# DDD架构设计总览

本文档使用领域驱动设计（Domain-Driven Design, DDD）方法分析 Authgear Server 系统，帮助理解系统架构和设计自己的身份认证系统。

## 什么是DDD

领域驱动设计（DDD）是一种软件开发方法论，强调：

1. **以业务领域为中心** - 代码结构应该反映业务领域模型
2. **通用语言（Ubiquitous Language）** - 开发团队和业务专家使用相同的术语
3. **分层架构** - 明确区分领域层、应用层、基础设施层
4. **聚合（Aggregate）** - 定义业务边界和一致性保证

## Authgear系统的领域划分

基于代码分析，Authgear Server 系统可以划分为以下核心领域：

### 1. 用户身份领域（User Identity Domain）
**核心职责**: 管理用户、身份和认证器

- **聚合根**: User
- **关键概念**: Identity（身份）、Authenticator（认证器）、Account Status（账户状态）
- **业务规则**: 
  - 一个用户可以有多个身份
  - 一个用户可以有多个认证器
  - 身份用于识别用户，认证器用于验证用户

### 2. 认证流程领域（Authentication Flow Domain）
**核心职责**: 管理认证流程的执行

- **聚合根**: AuthenticationFlow
- **关键概念**: Identification（识别）、Authentication（认证）、Session Creation（会话创建）
- **业务规则**:
  - 流程是声明式的，由步骤（Steps）组成
  - 支持分支（one_of）和条件执行
  - 流程完成后创建会话

### 3. 会话管理领域（Session Management Domain）
**核心职责**: 管理用户会话和设备

- **聚合根**: Session（IdPSession/OfflineGrant）
- **关键概念**: Session Lifetime、Idle Timeout、Concurrent Sessions、Device Management
- **业务规则**:
  - 会话有生命周期和空闲超时
  - 支持并发会话限制
  - 会话与设备关联

### 4. SSO领域（Single Sign-On Domain）
**核心职责**: 实现单点登录

- **聚合根**: Session（通过IdPSession实现）
- **关键概念**: SSO Group、Session Sharing、Browser SSO、Device SSO
- **业务规则**:
  - SSO组内的会话共享
  - 浏览器SSO基于Cookie
  - 设备SSO基于系统账户

### 5. OAuth领域（OAuth Domain）
**核心职责**: 管理OAuth授权和Social Login

- **聚合根**: OAuthAuthorization
- **关键概念**: Authorization（授权）、Grants（凭证）、OAuth Provider、Account Linking
- **业务规则**:
  - 授权代表用户对客户端的同意
  - Grant基于Authorization创建
  - 支持账户关联（Account Linking）

### 6. MFA领域（Multi-Factor Authentication Domain）
**核心职责**: 管理多因素认证

- **聚合根**: User（MFA相关实体作为子实体）
- **关键概念**: Primary Authenticator、Secondary Authenticator、Device Token、Recovery Code
- **业务规则**:
  - Primary认证器需要Secondary认证
  - 设备Token用于跳过MFA
  - Recovery Code用于恢复

## DDD架构层次

Authgear Server 采用分层架构，符合DDD原则：

```
┌─────────────────────────────────────┐
│   Presentation Layer (表现层)        │
│   - HTTP Handlers                   │
│   - GraphQL Resolvers               │
│   - Web Controllers                 │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│   Application Layer (应用层)         │
│   - Application Services            │
│   - Use Cases                       │
│   - DTOs                            │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│   Domain Layer (领域层)              │
│   - Aggregates (聚合)                │
│   - Entities (实体)                  │
│   - Value Objects (值对象)           │
│   - Domain Services (领域服务)       │
│   - Domain Events (领域事件)        │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│   Infrastructure Layer (基础设施层)  │
│   - Database Repositories           │
│   - External Services               │
│   - Message Queue                   │
└─────────────────────────────────────┘
```

### 各层职责

#### 表现层（Presentation Layer）
- **位置**: `pkg/auth/handler/`, `pkg/api/`, `pkg/admin/`
- **职责**: 
  - 处理HTTP请求
  - 参数验证
  - 响应格式化
  - 不包含业务逻辑

#### 应用层（Application Layer）
- **位置**: `pkg/lib/facade/`, `pkg/lib/accountmanagement/`
- **职责**:
  - 编排领域对象完成用例
  - 事务管理
  - 权限检查
  - 不包含核心业务逻辑

#### 领域层（Domain Layer）
- **位置**: `pkg/lib/authn/`, `pkg/lib/session/`, `pkg/lib/authenticationflow/`
- **职责**:
  - 核心业务逻辑
  - 业务规则验证
  - 领域模型定义
  - 领域事件发布

#### 基础设施层（Infrastructure Layer）
- **位置**: `pkg/lib/infra/`, `pkg/lib/db/`
- **职责**:
  - 数据持久化
  - 外部服务集成
  - 技术实现细节

## 核心设计模式

### 1. 聚合模式（Aggregate Pattern）
每个聚合有一个聚合根，负责维护聚合内的一致性边界。

**示例**:
- `User` 是用户聚合的聚合根
- `Identity` 和 `Authenticator` 是 `User` 的子实体
- 所有对 `Identity` 和 `Authenticator` 的操作都通过 `User` 进行

### 2. 仓储模式（Repository Pattern）
提供领域对象的持久化抽象。

**示例**:
- `IdentityService` 提供身份实体的持久化操作
- `AuthenticatorService` 提供认证器实体的持久化操作

### 3. 领域服务（Domain Service）
处理跨聚合的业务逻辑。

**示例**:
- `AccountLinkingService` - 处理账户关联逻辑
- `SessionResolver` - 解析会话信息

### 4. 领域事件（Domain Event）
表示领域内发生的重要事件。

**示例**:
- `UserCreated` - 用户创建事件
- `IdentityConnected` - 身份关联事件
- `SessionCreated` - 会话创建事件

## 通用语言（Ubiquitous Language）

Authgear系统使用以下核心术语：

| 术语 | 英文 | 含义 |
|------|------|------|
| 用户 | User | 系统中的账户主体 |
| 身份 | Identity | 用户识别自己的方式（邮箱、手机、OAuth等） |
| 认证器 | Authenticator | 用户验证自己的方式（密码、OTP、TOTP等） |
| 会话 | Session | 用户认证后的状态保持 |
| 认证流程 | Authentication Flow | 从识别到认证到会话创建的完整流程 |
| 识别 | Identification | 确定用户身份的过程 |
| 认证 | Authentication | 验证用户身份的过程 |
| 授权 | Authorization | 用户对OAuth客户端的同意 |
| 凭证 | Grant | 基于授权的访问凭证 |
| 设备令牌 | Device Token | 用于跳过MFA的设备信任令牌 |
| 恢复码 | Recovery Code | 用于恢复账户的备用认证方式 |

## 文档结构

本系列文档包含以下内容：

1. **领域模型设计.md** - 详细的聚合、实体、值对象设计
2. **认证流程领域设计.md** - 认证流程的领域建模
3. **SSO领域设计.md** - 单点登录的领域设计
4. **Social Login领域设计.md** - OAuth第三方登录的领域设计
5. **Session管理领域设计.md** - 会话管理的领域设计
6. **MFA领域设计.md** - 多因素认证的领域设计

每个文档都包含：
- 领域模型图（PlantUML）
- 时序图（关键业务流程）
- 状态机图（状态转换）
- 代码映射（DDD概念与代码实现的对应）
- 设计原则和最佳实践

## 学习路径建议

1. **先阅读本总览文档** - 理解DDD方法和系统整体架构
2. **阅读领域模型设计.md** - 理解核心领域模型
3. **按需深入特定领域** - 根据学习目标选择重点领域
4. **参考代码实现** - 结合代码理解设计

## 设计原则

### 1. 聚合边界原则
- 聚合应该尽可能小
- 聚合之间通过ID引用，不直接引用对象
- 聚合内的事务一致性，聚合间最终一致性

### 2. 领域服务原则
- 领域服务应该是无状态的
- 领域服务处理跨聚合的业务逻辑
- 避免在领域服务中处理基础设施关注点

### 3. 值对象原则
- 值对象是不可变的
- 值对象通过值相等性比较
- 值对象应该包含验证逻辑

### 4. 实体原则
- 实体有唯一标识
- 实体通过ID相等性比较
- 实体包含业务逻辑和行为

## 参考资源

- [领域驱动设计 - Eric Evans](https://www.domainlanguage.com/ddd/)
- [实现领域驱动设计 - Vaughn Vernon](https://vaughnvernon.com/implementing-domain-driven-design/)
- [Authgear官方文档](../../docs/)

---

**最后更新**: 2026-01-28
