# SSO领域设计

本文档详细说明 Authgear Server SSO（单点登录）领域的领域模型设计，这是系统的重要特性之一。

## 领域概述

SSO领域负责实现"一次登录，处处可用"的单点登录功能。系统支持两种SSO机制：Browser SSO（基于Cookie）和Device SSO（基于系统账户）。

### 核心概念

1. **SSO Group（SSO组）** - 共享同一IdP Session的会话集合
2. **Session Sharing（会话共享）** - 多个应用共享同一个会话
3. **Browser SSO** - 基于浏览器Cookie的SSO机制
4. **Device SSO** - 基于设备系统账户的SSO机制

## 聚合设计

### 聚合根：Session（通过IdPSession实现）

**职责**: 管理SSO会话组和会话共享

#### 实体（Entities）

##### IdPSession（聚合根）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `SSOGroupID` - SSO组ID（通常是SessionID本身）
  - `CreatedAt`, `UpdatedAt` - 时间戳
  - `LastAccessAt` - 最后访问时间
- **业务规则**:
  - IdPSession是SSO组的核心
  - 同一SSO组内的所有会话共享生命周期
  - IdPSession失效时，SSO组内所有会话失效

**代码位置**: `pkg/lib/session/idpsession/session.go`

##### OfflineGrant（实体，SSO相关）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `SSOEnabled` - 是否启用SSO
  - `SSOGroupIDPSessionID` - 关联的IdP Session ID
  - `ClientID` - OAuth客户端ID
- **业务规则**:
  - 如果SSOEnabled=true，OfflineGrant属于SSO组
  - SSO组内的OfflineGrant与IdPSession关联
  - IdPSession失效时，关联的OfflineGrant也失效

**代码位置**: `pkg/lib/oauth/grant_offline.go`

#### 值对象（Value Objects）

##### SSOGroup（值对象）
- **属性**:
  - `IDPSessionID` - IdP Session ID（作为组ID）
  - `Members` - 组成员列表（IdPSession + SSOEnabled OfflineGrants）
- **业务规则**:
  - SSO组由IdPSession ID标识
  - 组内所有会话共享生命周期
  - 组内任一会话失效，所有会话失效

##### SSOConfig（值对象）
- **属性**:
  - `SSOEnabled` - 是否启用SSO
  - `CookieDomain` - Cookie域名
  - `ShareSessionWithSystemBrowser` - 是否与系统浏览器共享
- **业务规则**:
  - 决定SSO行为
  - 影响会话创建和共享

#### 领域服务（Domain Services）

##### SSOResolver
- **职责**: 解析SSO会话
- **方法**:
  - `ResolveSSOSession()` - 解析SSO会话
  - `IsSameSSOGroup()` - 判断是否在同一SSO组

**代码位置**: `pkg/lib/session/idpsession/resolver.go`

##### SSOValidator
- **职责**: 验证SSO会话有效性
- **方法**:
  - `ValidateSSOSession()` - 验证SSO会话
  - `CheckSSOGroup()` - 检查SSO组状态

## SSO机制详解

### 1. Browser SSO（浏览器SSO）

**聚合根**: `IdPSession`

**工作原理**:
- 基于HTTP Cookie实现
- Cookie存储在浏览器中，域名可配置
- 同一域名下的所有应用共享Cookie

**业务规则**:
- Cookie域名默认为eTLD+1（如`.oursky.apps`）
- 所有`*.oursky.apps`下的应用共享会话
- Cookie是HttpOnly、Secure、SameSite=Lax
- Cookie是持久化的（Persistent Cookie）

**代码位置**: `pkg/lib/session/idpsession/provider.go`

### 2. Device SSO（设备SSO）

**聚合根**: `IdPSession` + `OfflineGrant`

**工作原理**:
- 基于系统账户（iOS AppGroup、Android AccountManager）
- 移动应用与系统浏览器共享会话
- 通过`shareSessionWithSystemBrowser`配置启用

**业务规则**:
- 移动应用可以读取系统浏览器的IdP Session
- 移动应用创建的OfflineGrant与IdP Session关联
- 系统账户删除时，所有关联会话失效

### 3. SPA SSO（单页应用SSO）

**聚合根**: `IdPSession` + `OfflineGrant`

**工作原理**:
- SPA使用Refresh Token（OfflineGrant）
- 如果SSO启用，OfflineGrant与IdP Session关联
- 通过`x_sso_enabled`参数控制

**业务规则**:
- `x_sso_enabled=true`: 创建IdP Session Cookie，OfflineGrant关联到IdP Session
- `x_sso_enabled=false`: 不创建IdP Session Cookie，OfflineGrant独立
- SSO组内的OfflineGrant共享生命周期

## SSO组管理

### SSO组识别

SSO组通过`SSOGroupIDPSessionID`识别：
- IdPSession的SSOGroupID = 自己的SessionID
- OfflineGrant的SSOGroupID = 关联的IdPSession的SessionID

### SSO组生命周期

1. **创建**: 创建IdPSession时，创建新的SSO组
2. **扩展**: 创建SSOEnabled OfflineGrant时，加入SSO组
3. **失效**: IdPSession失效时，整个SSO组失效
4. **清理**: 组内所有会话失效后，SSO组自动清理

### SSO组操作

#### 加入SSO组
- 创建SSOEnabled OfflineGrant时，关联到现有IdPSession
- 如果IdPSession不存在，创建新的IdPSession

#### 退出SSO组
- 删除IdPSession时，SSO组失效
- 删除OfflineGrant时，从SSO组移除

#### 查询SSO组
- 通过`IsSameSSOGroup()`判断会话是否在同一组
- 通过`SSOGroupIDPSessionID()`获取组ID

## 业务流程

### Browser SSO登录流程

1. **用户访问App A** → 检查IdP Session Cookie
2. **Cookie存在且有效** → 自动登录，无需输入凭证
3. **Cookie不存在或无效** → 重定向到登录页面
4. **用户登录** → 创建IdP Session，设置Cookie
5. **用户访问App B** → 检查IdP Session Cookie
6. **Cookie存在** → 自动登录（SSO成功）

### SPA SSO登录流程

1. **用户访问SPA** → 检查IdP Session Cookie
2. **Cookie存在** → 显示"Continue As..."按钮
3. **用户点击Continue** → 使用现有IdP Session创建OfflineGrant
4. **Cookie不存在** → 执行完整登录流程
5. **登录成功** → 创建IdP Session Cookie和OfflineGrant
6. **OfflineGrant关联到IdP Session** → 加入SSO组

### SSO登出流程

1. **用户点击登出** → 删除IdP Session
2. **SSO组失效** → 所有关联的OfflineGrant失效
3. **清除Cookie** → 清除IdP Session Cookie
4. **通知客户端** → 所有应用收到登出通知

## 领域事件

### SSOGroupCreated
- **触发时机**: SSO组创建（IdPSession创建）
- **事件数据**: SSOGroupID, UserID
- **用途**: 记录SSO组创建

### SSOGroupMemberAdded
- **触发时机**: OfflineGrant加入SSO组
- **事件数据**: SSOGroupID, OfflineGrantID
- **用途**: 记录SSO组扩展

### SSOGroupTerminated
- **触发时机**: SSO组失效（IdPSession删除）
- **事件数据**: SSOGroupID, Reason
- **用途**: 通知所有应用会话失效

## 设计模式

### 1. 策略模式（Strategy Pattern）
不同的SSO机制（Browser SSO、Device SSO）使用不同的策略实现。

### 2. 组合模式（Composite Pattern）
SSO组是会话的组合，组内会话共享生命周期。

### 3. 观察者模式（Observer Pattern）
SSO组失效时，通知所有关联的会话。

## 代码映射

### 核心类映射

| DDD概念 | 代码位置 | 说明 |
|---------|---------|------|
| IdPSession聚合根 | `pkg/lib/session/idpsession/session.go` | IdPSession结构体 |
| OfflineGrant实体 | `pkg/lib/oauth/grant_offline.go` | OfflineGrant结构体 |
| SSOResolver | `pkg/lib/session/idpsession/resolver.go` | 会话解析器 |
| SessionManager | `pkg/lib/session/manager.go` | 会话管理器 |

### 关键方法映射

| 方法 | 代码位置 | 说明 |
|------|---------|------|
| IsSameSSOGroup() | `pkg/lib/session/idpsession/session.go` | 判断是否在同一SSO组 |
| SSOGroupIDPSessionID() | `pkg/lib/session/idpsession/session.go` | 获取SSO组ID |
| CreateSessionFromOAuthSession() | `pkg/auth/webapp/session_middleware.go` | 从OAuth会话创建SSO会话 |

## 设计原则

### 1. SSO组一致性
SSO组内的所有会话必须保持一致性，任一会话失效，所有会话失效。

### 2. 会话生命周期管理
SSO组由IdPSession控制，IdPSession是SSO组的核心。

### 3. 跨应用会话共享
通过Cookie域名配置实现跨应用会话共享。

### 4. 设备会话隔离
不同设备的会话是隔离的，除非启用Device SSO。

## 安全考虑

### 1. Cookie安全
- HttpOnly防止XSS攻击
- Secure确保HTTPS传输
- SameSite=Lax防止CSRF攻击

### 2. SSO组验证
- 验证SSO组ID的有效性
- 防止会话劫持

### 3. 会话过期
- IdPSession有过期时间
- SSO组内会话共享过期时间

## 扩展点

### 自定义SSO策略
可以通过实现`SSOStrategy`接口创建自定义SSO策略。

### SSO组事件
可以监听SSO组事件，实现自定义业务逻辑。

---

**最后更新**: 2026-01-28
