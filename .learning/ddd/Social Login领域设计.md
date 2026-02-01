# Social Login领域设计

本文档详细说明 Authgear Server Social Login（OAuth第三方登录）领域的领域模型设计，这是系统的重要特性之一。

## 领域概述

Social Login领域负责管理OAuth第三方登录和账户关联（Account Linking）。系统支持多种OAuth Provider（Google、Facebook、Apple、GitHub等），并实现了智能的账户关联机制。

### 核心概念

1. **OAuth Provider** - 第三方身份提供商（如Google、Facebook）
2. **OAuth User Profile** - 从OAuth Provider获取的用户信息
3. **Account Linking** - 将OAuth身份关联到现有账户
4. **Identity Attributes** - 用于账户关联的身份属性

## 聚合设计

### 聚合根：OAuthAuthorization

**职责**: 管理OAuth授权和Social Login流程

#### 实体（Entities）

##### Authorization（聚合根）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `ClientID` - OAuth客户端ID
  - `Scopes` - 授权范围
  - `CreatedAt`, `UpdatedAt` - 时间戳
- **业务规则**:
  - Authorization代表用户对OAuth客户端的同意
  - 可以撤销，撤销后所有关联的Grant失效
  - 同一用户和客户端的Authorization唯一

**代码位置**: `pkg/lib/oauth/authorization.go`

##### OAuthIdentity（实体，属于User聚合）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `ProviderType` - OAuth Provider类型（google, facebook等）
  - `ProviderUserID` - Provider中的用户ID
  - `ProviderKeys` - Provider特定键值对
  - `Claims` - OAuth User Profile的声明
  - `Profile` - 完整的用户资料
- **业务规则**:
  - OAuth Identity不需要Primary Authenticator
  - 通过Identity Attributes进行账户关联
  - 同一Provider的同一用户只能有一个Identity

**代码位置**: `pkg/lib/authn/identity/oauth/`

#### 值对象（Value Objects）

##### OAuthUserProfile（值对象）
- **属性**:
  - `Sub` - 用户唯一标识
  - `Email`, `EmailVerified` - 邮箱信息
  - `Name`, `GivenName`, `FamilyName` - 姓名信息
  - `Picture`, `Profile` - 头像和资料链接
  - Provider特定的其他字段
- **业务规则**:
  - 从OAuth Provider获取
  - 转换为Identity Attributes用于账户关联
  - 不同Provider的Profile结构不同

**代码位置**: `pkg/lib/oauthrelyingparty/user_profile.go`

##### IdentityAttributes（值对象）
- **属性**:
  - `Email` - 邮箱地址
  - `PhoneNumber` - 手机号码
  - `PreferredUsername` - 首选用户名
- **业务规则**:
  - 用于检测重复身份
  - 用于账户关联判断
  - 从OAuth User Profile提取

**代码位置**: `pkg/lib/authn/identity/attributes.go`

##### OAuthState（值对象）
- **属性**:
  - `AppID` - 应用ID
  - `ProviderAlias` - OAuth Provider别名
  - `WebSessionID` - Web会话ID
  - `UIImplementation` - UI实现类型
  - `AccountManagementToken` - 账户管理令牌（可选）
- **业务规则**:
  - 用于OAuth回调的状态验证
  - 防止CSRF攻击
  - 一次性使用

**代码位置**: `pkg/auth/webapp/oauth_state.go`

##### AccountLinkingConfig（值对象）
- **属性**:
  - `OAuthClaim` - OAuth声明的JSON指针
  - `UserProfile` - 用户属性的JSON指针
  - `Action` - 关联动作（error, login_and_link等）
- **业务规则**:
  - 定义账户关联的条件
  - 决定关联发生时的行为

**代码位置**: `pkg/lib/config/account_linking.go`

#### 领域服务（Domain Services）

##### OAuthProviderFactory
- **职责**: 创建和管理OAuth Provider实例
- **方法**:
  - `GetProviderConfig()` - 获取Provider配置
  - `GetAuthorizationURL()` - 获取授权URL
  - `GetUserProfile()` - 获取用户信息
  - `ExchangeCode()` - 交换授权码

**代码位置**: `pkg/lib/oauthrelyingparty/factory.go`

##### AccountLinkingService
- **职责**: 处理账户关联逻辑
- **方法**:
  - `CheckAccountLinking()` - 检查是否需要账户关联
  - `LinkIdentity()` - 关联身份到现有账户
  - `ResolveAccountLinkingConfig()` - 解析账户关联配置

**代码位置**: `pkg/lib/authenticationflow/declarative/utils_account_linking.go`

##### IdentityService（OAuth相关）
- **职责**: 管理OAuth Identity的创建和查询
- **方法**:
  - `NewOAuthIdentity()` - 创建OAuth Identity
  - `CheckDuplicated()` - 检查重复Identity（用于账户关联）
  - `ListByClaim()` - 通过声明查询Identity

**代码位置**: `pkg/lib/authn/identity/service/service.go`

## OAuth流程详解

### 1. OAuth授权流程

**聚合根**: `Authorization`

**流程步骤**:
1. **用户点击OAuth登录** → 重定向到OAuth Provider
2. **用户在Provider授权** → Provider回调Authgear
3. **交换授权码** → 获取Access Token
4. **获取用户信息** → 调用Provider API获取User Profile
5. **创建或关联Identity** → 根据账户关联配置处理
6. **创建Session** → 完成登录

**业务规则**:
- 使用Authorization Code流程
- 支持PKCE（Proof Key for Code Exchange）
- 支持State参数防止CSRF

### 2. 账户关联流程

**聚合根**: `User` + `OAuthAuthorization`

**关联条件**:
- 通过Identity Attributes匹配
- 例如：OAuth Identity的email与现有User的email匹配

**关联动作**:
- `error` - 拒绝，返回错误
- `login_and_link` - 切换到登录流程，登录后关联
- `always_link_without_login` - 直接关联（需信任Provider）
- `link_without_login_when_verified` - 邮箱已验证时直接关联
- `create_new_account` - 创建新账户
- `create_new_account_or_link` - 让用户选择

**业务规则**:
- 账户关联发生在Signup流程中
- 如果Identity已存在，直接使用
- 如果Identity不存在但有关联，触发关联流程

## 支持的OAuth Provider

系统支持以下OAuth Provider：

### 标准OIDC Provider
- **Google** - 使用OIDC协议
- **Apple** - 使用OIDC协议
- **Azure AD** - 使用OIDC协议
- **Azure B2C** - 使用OIDC协议
- **ADFS** - 使用OIDC协议

### OAuth 2.0 Provider
- **Facebook** - 使用OAuth 2.0 + Graph API
- **GitHub** - 使用OAuth 2.0 + REST API
- **LinkedIn** - 使用OAuth 2.0 + REST API
- **WeChat** - 使用OAuth 2.0 + 微信API

### Provider适配器模式

每个Provider都有对应的适配器实现：
- `GoogleProvider` - Google OAuth实现
- `FacebookProvider` - Facebook OAuth实现
- `AppleProvider` - Apple OAuth实现
- 等等...

**代码位置**: `pkg/lib/oauthrelyingparty/providers/`

## 账户关联机制

### Identity Attributes提取

从OAuth User Profile提取Identity Attributes：

1. **Email** - 从`email`字段提取
2. **PhoneNumber** - 从`phone_number`字段提取
3. **PreferredUsername** - 从`preferred_username`字段提取

### 账户关联判断

系统通过以下方式判断是否需要账户关联：

1. **提取OAuth Identity的Identity Attributes**
2. **查询现有Identity的Identity Attributes**
3. **匹配判断** - 如果匹配，触发账户关联
4. **执行关联动作** - 根据配置执行相应动作

### 账户关联示例

**场景**: 用户已有邮箱账户`user@example.com`，现在使用Google登录，Google账户的email也是`user@example.com`

**流程**:
1. 用户点击"Login with Google"
2. 完成OAuth授权，获取User Profile
3. 系统检测到email匹配
4. 触发`login_and_link`动作
5. 用户输入密码登录现有账户
6. Google Identity关联到现有账户

## 领域事件

### OAuthIdentityConnected
- **触发时机**: OAuth Identity成功关联到账户
- **事件数据**: UserID, IdentityID, ProviderType
- **用途**: 记录账户关联，触发后续流程

### OAuthIdentityCreated
- **触发时机**: 创建新的OAuth Identity
- **事件数据**: UserID, IdentityID, ProviderType
- **用途**: 记录新身份创建

### AccountLinkingTriggered
- **触发时机**: 检测到账户关联条件
- **事件数据**: OAuthIdentity, MatchedUserID, Action
- **用途**: 记录账户关联触发

## 设计模式

### 1. 工厂模式（Factory Pattern）
`OAuthProviderFactory`根据Provider类型创建对应的Provider实例。

### 2. 策略模式（Strategy Pattern）
不同的账户关联动作使用不同的策略实现。

### 3. 适配器模式（Adapter Pattern）
每个OAuth Provider都有对应的适配器，统一接口。

## 代码映射

### 核心类映射

| DDD概念 | 代码位置 | 说明 |
|---------|---------|------|
| Authorization聚合根 | `pkg/lib/oauth/authorization.go` | Authorization结构体 |
| OAuthIdentity实体 | `pkg/lib/authn/identity/oauth/` | OAuth Identity实现 |
| OAuthProviderFactory | `pkg/lib/oauthrelyingparty/factory.go` | Provider工厂 |
| AccountLinkingService | `pkg/lib/authenticationflow/declarative/utils_account_linking.go` | 账户关联服务 |

### Provider实现映射

| Provider | 代码位置 | 说明 |
|---------|---------|------|
| Google | `pkg/lib/oauthrelyingparty/providers/google/` | Google OAuth实现 |
| Facebook | `pkg/lib/oauthrelyingparty/providers/facebook/` | Facebook OAuth实现 |
| Apple | `pkg/lib/oauthrelyingparty/providers/apple/` | Apple OAuth实现 |
| GitHub | `pkg/lib/oauthrelyingparty/providers/github/` | GitHub OAuth实现 |

## 业务流程示例

### Google登录流程（新用户）

1. **用户点击"Login with Google"** → 重定向到Google
2. **用户在Google授权** → Google回调Authgear
3. **交换授权码** → 获取Access Token和ID Token
4. **解析ID Token** → 获取User Profile
5. **检查账户关联** → 未找到匹配，创建新账户
6. **创建OAuth Identity** → 关联到新用户
7. **创建Session** → 完成登录

### Google登录流程（账户关联）

1. **用户点击"Login with Google"** → 重定向到Google
2. **用户在Google授权** → Google回调Authgear
3. **交换授权码** → 获取User Profile
4. **检查账户关联** → 发现email匹配现有账户
5. **触发login_and_link** → 切换到登录流程
6. **用户输入密码** → 验证现有账户
7. **关联Google Identity** → 添加到现有账户
8. **创建Session** → 完成登录

## 设计原则

### 1. Provider抽象
所有OAuth Provider通过统一接口抽象，便于扩展。

### 2. 账户关联策略
账户关联是可配置的，支持多种策略。

### 3. 安全性
- 使用State参数防止CSRF
- 使用PKCE增强安全性
- 验证OAuth Provider的签名

### 4. 用户体验
- 智能账户关联，减少用户操作
- 支持用户选择（create_new_account_or_link）

## 扩展点

### 添加新的OAuth Provider
1. 实现`OAuthProvider`接口
2. 实现`GetUserProfile()`方法
3. 注册到`OAuthProviderFactory`

### 自定义账户关联逻辑
1. 配置`AccountLinkingConfig`
2. 实现自定义关联动作
3. 使用Hook进行高级控制

---

**最后更新**: 2026-01-28
