# Session管理领域设计

本文档详细说明 Authgear Server Session管理领域的领域模型设计，包括会话生命周期、设备管理和并发会话控制。

## 领域概述

Session管理领域负责管理用户会话的完整生命周期，包括创建、验证、更新、失效和设备管理。系统支持多种会话类型和并发会话控制。

### 核心概念

1. **Session Lifetime** - 会话生命周期（绝对过期时间）
2. **Idle Timeout** - 空闲超时（相对过期时间）
3. **Concurrent Sessions** - 并发会话限制
4. **Device Management** - 设备识别和管理
5. **Session Attributes** - 会话属性（AMR、Identity等）

## 聚合设计

### 聚合根：Session（抽象）

**职责**: 管理用户会话和设备

#### 实体（Entities）

##### IdPSession（聚合根）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `CreatedAt` - 创建时间
  - `AuthenticatedAt` - 认证时间
  - `ExpireAt` - 过期时间（计算得出）
  - `Attrs` - 会话属性
  - `AccessInfo` - 访问信息
  - `TokenHash` - 令牌哈希
- **业务规则**:
  - IdP Session有生命周期和空闲超时
  - Session存储在Cookie中
  - 每次访问更新LastAccess时间
  - 如果启用空闲超时，超时后Session失效

**代码位置**: `pkg/lib/session/idpsession/session.go`

##### OfflineGrant（聚合根）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `ClientID` - OAuth客户端ID
  - `AuthorizationID` - 授权ID
  - `CreatedAt`, `UpdatedAt` - 时间戳
  - `LastAccessAt` - 最后访问时间
  - `SSOEnabled` - 是否启用SSO
  - `RefreshTokens` - 刷新令牌列表
- **业务规则**:
  - OfflineGrant代表Refresh Token
  - 有生命周期和空闲超时
  - 如果SSO启用，与IdPSession关联
  - 同一时间只有一个有效的Access Token

**代码位置**: `pkg/lib/oauth/grant_offline.go`

#### 值对象（Value Objects）

##### SessionAttributes（值对象）
- **属性**:
  - `UserID` - 用户ID
  - `AMR` - 认证方法引用数组
  - `IdentitySpecs` - 身份规格
- **业务规则**:
  - AMR记录使用的认证方法
  - IdentitySpecs记录使用的身份
  - 用于安全审计

**代码位置**: `pkg/lib/session/attrs.go`

##### AccessInfo（值对象）
- **属性**:
  - `InitialAccess` - 初始访问信息（时间、IP、UserAgent）
  - `LastAccess` - 最后访问信息（时间、IP、UserAgent）
- **业务规则**:
  - 记录Session的访问历史
  - 用于设备识别和安全审计

**代码位置**: `pkg/lib/session/access/event.go`

##### DeviceInfo（值对象）
- **属性**:
  - `DeviceName` - 设备名称
  - `DeviceType` - 设备类型（mobile, desktop等）
  - `IPAddress` - IP地址
  - `UserAgent` - 用户代理
  - `Location` - 地理位置（可选）
- **业务规则**:
  - 用于设备识别和管理
  - 支持设备信任（Device Token）
  - 用于会话列表显示

##### SessionConfig（值对象）
- **属性**:
  - `Lifetime` - 会话生命周期（秒）
  - `IdleTimeout` - 空闲超时（秒，可选）
  - `CookieDomain` - Cookie域名
  - `CookieName` - Cookie名称
- **业务规则**:
  - 决定Session的行为
  - IdP Session配置是全局的
  - OfflineGrant配置是客户端级别的

**代码位置**: `pkg/lib/config/session.go`

#### 领域服务（Domain Services）

##### SessionManager
- **职责**: 管理Session的创建、查询、删除
- **方法**:
  - `MakeSession()` - 创建Session
  - `Get()` - 查询Session
  - `List()` - 列出用户的所有Session
  - `Delete()` - 删除Session
  - `TerminateAllExcept()` - 终止除指定Session外的所有Session
  - `UpdateAccessInfo()` - 更新访问信息

**代码位置**: `pkg/lib/session/manager.go`

##### SessionResolver
- **职责**: 解析Session Token到Session对象
- **方法**:
  - `Resolve()` - 解析Session Token
  - `Validate()` - 验证Session有效性

**代码位置**: `pkg/lib/session/idpsession/resolver.go`

##### DeviceManager
- **职责**: 管理设备识别和设备信任
- **方法**:
  - `IdentifyDevice()` - 识别设备
  - `GetDeviceInfo()` - 获取设备信息
  - `ListDevices()` - 列出用户的所有设备

## 会话生命周期

### 创建阶段

1. **用户认证成功** → 触发Session创建
2. **生成Session Token** → 加密的随机字符串
3. **设置Session Attributes** → AMR、Identity等
4. **记录初始访问信息** → IP、UserAgent、时间
5. **计算过期时间** → 基于Lifetime和IdleTimeout
6. **持久化Session** → 保存到数据库/Redis
7. **设置Cookie** → 写入浏览器Cookie

### 使用阶段

1. **请求到达** → 携带Session Token（Cookie或Header）
2. **解析Token** → 验证签名，查询Session
3. **验证有效性** → 检查过期时间和账户状态
4. **更新访问信息** → 更新LastAccess时间
5. **重新计算过期时间** → 如果启用IdleTimeout
6. **返回Session信息** → 供业务逻辑使用

### 失效阶段

1. **主动登出** → 用户点击登出
2. **会话过期** → Lifetime或IdleTimeout到期
3. **账户状态变更** → 用户被禁用、删除等
4. **并发限制** → 超过最大并发会话数
5. **安全事件** → 检测到异常行为

## 并发会话管理

### 项目级并发会话

**聚合根**: `Session`

**规则**:
- 按SSO组计算并发会话数
- 超过限制时，终止最旧的SSO组
- 同一SSO组内的所有会话一起失效

**业务规则**:
- SSO组由IdPSession ID标识
- SSOEnabled OfflineGrant属于同一SSO组
- 项目级限制适用于所有客户端

### 客户端级并发会话

**聚合根**: `OfflineGrant`

**规则**:
- 按Refresh Token计算并发会话数
- 超过限制时，终止最旧的Refresh Token
- 只影响特定OAuth客户端

**业务规则**:
- 客户端级限制只适用于Token-based应用
- 与SSO互斥（不能同时启用）
- Cookie-based应用不受影响

## 设备管理

### 设备识别

**值对象**: `DeviceInfo`

**识别方式**:
1. **User Agent解析** → 提取设备类型、浏览器、操作系统
2. **IP地址** → 用于地理位置识别
3. **设备指纹** → 组合多个特征（可选）

**业务规则**:
- 设备信息用于会话列表显示
- 支持设备命名（用户自定义）
- 设备信息存储在Session的AccessInfo中

### 设备信任

**实体**: `DeviceToken`（属于MFA聚合）

**工作原理**:
- 设备Token存储在Cookie中
- 用于跳过MFA验证
- 可以撤销所有设备Token

**业务规则**:
- 设备Token与Session关联
- 设备Token失效不影响Session
- 可以单独管理设备Token

## 会话解析流程

### IdP Session解析

1. **从Cookie读取Token** → 获取Session Token
2. **验证Token签名** → 确保Token未被篡改
3. **查询Session** → 从存储中查询Session
4. **验证有效性** → 检查过期时间和账户状态
5. **更新访问信息** → 更新LastAccess
6. **返回Session对象** → 供业务逻辑使用

### OfflineGrant解析

1. **从请求读取Refresh Token** → 获取Refresh Token
2. **验证Token哈希** → 确保Token有效
3. **查询OfflineGrant** → 从存储中查询
4. **验证SSO关联** → 如果SSO启用，检查IdPSession
5. **验证有效性** → 检查过期时间和授权状态
6. **更新访问信息** → 更新LastAccess
7. **返回Session对象** → 供业务逻辑使用

## 领域事件

### SessionCreated
- **触发时机**: Session创建成功
- **事件数据**: UserID, SessionID, SessionType, DeviceInfo
- **用途**: 记录会话创建，触发审计

### SessionUpdated
- **触发时机**: Session访问信息更新
- **事件数据**: SessionID, LastAccess
- **用途**: 记录会话活动

### SessionTerminated
- **触发时机**: Session被终止
- **事件数据**: SessionID, Reason, IsTermination
- **用途**: 记录会话终止，通知客户端

### ConcurrentSessionLimitReached
- **触发时机**: 达到并发会话限制
- **事件数据**: UserID, TerminatedSessions
- **用途**: 记录会话限制触发

## 设计模式

### 1. 策略模式（Strategy Pattern）
不同的会话类型（IdPSession、OfflineGrant）使用不同的策略实现。

### 2. 模板方法模式（Template Method）
会话解析的框架是固定的，具体实现由会话类型决定。

### 3. 观察者模式（Observer Pattern）
会话失效时，通知所有关联的系统。

## 代码映射

### 核心类映射

| DDD概念 | 代码位置 | 说明 |
|---------|---------|------|
| IdPSession聚合根 | `pkg/lib/session/idpsession/session.go` | IdPSession结构体 |
| OfflineGrant聚合根 | `pkg/lib/oauth/grant_offline.go` | OfflineGrant结构体 |
| SessionManager | `pkg/lib/session/manager.go` | 会话管理器 |
| SessionResolver | `pkg/lib/session/idpsession/resolver.go` | 会话解析器 |
| AccessInfo值对象 | `pkg/lib/session/access/event.go` | 访问信息 |

### 关键方法映射

| 方法 | 代码位置 | 说明 |
|------|---------|------|
| MakeSession() | `pkg/lib/session/idpsession/provider.go` | 创建Session |
| Resolve() | `pkg/lib/session/idpsession/resolver.go` | 解析Session Token |
| List() | `pkg/lib/session/idpsession/provider.go` | 列出用户的所有Session |
| TerminateAllExcept() | `pkg/lib/session/manager.go` | 终止除指定Session外的所有Session |

## 业务流程示例

### 会话创建流程

1. **用户认证成功** → AuthenticationFlow完成
2. **收集Session Attributes** → AMR、Identity等
3. **生成Session Token** → 加密的随机字符串
4. **创建IdPSession** → 设置属性、访问信息
5. **计算过期时间** → 基于Lifetime和IdleTimeout
6. **持久化Session** → 保存到Redis
7. **设置Cookie** → 写入浏览器
8. **发布SessionCreated事件** → 触发审计

### 会话验证流程

1. **请求到达** → 携带Session Token
2. **中间件解析** → SessionMiddleware处理
3. **验证Token** → 检查签名和有效性
4. **查询Session** → 从Redis查询
5. **验证账户状态** → 检查用户是否可用
6. **更新访问信息** → 更新LastAccess
7. **重新计算过期时间** → 如果启用IdleTimeout
8. **填充Session Context** → 供业务逻辑使用

### 会话终止流程

1. **用户点击登出** → 或系统触发终止
2. **删除Session** → 从Redis删除
3. **清除Cookie** → 清除浏览器Cookie
4. **处理SSO组** → 如果SSO启用，终止关联会话
5. **发布SessionTerminated事件** → 触发审计
6. **通知客户端** → 通过事件通知

## 设计原则

### 1. 会话不可变
Session对象创建后不可修改，更新操作创建新对象。

### 2. 令牌不透明
Session Token是不透明的字符串，客户端不应解析。

### 3. 安全性优先
- Token使用加密签名
- 支持Token轮换
- 记录所有访问事件

### 4. 性能优化
- Session存储在Redis中
- 支持批量查询
- 异步更新访问信息

## 扩展点

### 自定义会话类型
可以通过实现`SessionBase`接口创建自定义会话类型。

### 自定义设备识别
可以实现`DeviceIdentifier`接口自定义设备识别逻辑。

### 会话事件监听
可以监听会话事件，实现自定义业务逻辑。

---

**最后更新**: 2026-01-28
