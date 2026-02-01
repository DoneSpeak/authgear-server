# Authgear Server DDD 架构设计总览

## 概述

本文档使用领域驱动设计（Domain-Driven Design, DDD）方法对 Authgear Server 进行架构设计。DDD 是一种软件开发方法论，强调通过领域模型来驱动软件设计。

## DDD 核心概念

### 有界上下文（Bounded Context）

有界上下文是 DDD 中最重要的概念之一，它定义了模型的边界。在 Authgear Server 中，我们识别出以下主要有界上下文：

1. **认证上下文（Authentication Context）**
   - 负责用户身份识别和认证
   - 包含认证流程、身份验证等核心功能

2. **身份管理上下文（Identity Management Context）**
   - 管理用户身份（Identity）
   - 支持多种身份类型：Login ID、OAuth、Passkey、LDAP 等

3. **会话管理上下文（Session Management Context）**
   - 管理用户会话生命周期
   - 支持 IdP Session 和 Offline Grant

4. **SSO 上下文（Single Sign-On Context）**
   - 实现单点登录功能
   - 支持浏览器 SSO 和设备 SSO

5. **社交登录上下文（Social Login Context）**
   - 处理第三方 OAuth 提供商的登录
   - 管理 OAuth 流程和用户信息同步

6. **MFA 上下文（Multi-Factor Authentication Context）**
   - 多因素认证管理
   - 设备令牌和恢复码管理

7. **授权上下文（Authorization Context）**
   - OAuth/OIDC 授权流程
   - 访问令牌和刷新令牌管理

## 聚合（Aggregate）和聚合根（Aggregate Root）

### 认证聚合

**聚合根：AuthenticationFlow**
- 管理认证流程的完整生命周期
- 包含多个步骤（Step）和节点（Node）
- 维护流程状态和上下文

**实体：**
- `FlowStep`: 流程步骤
- `FlowNode`: 流程节点
- `AuthenticationInfo`: 认证信息

### 用户聚合

**聚合根：User**
- 用户的核心实体
- 管理用户状态和属性
- 协调身份和认证器的创建

**实体：**
- `Identity`: 用户身份
- `Authenticator`: 认证器
- `UserProfile`: 用户资料

### 会话聚合

**聚合根：Session**
- 会话的生命周期管理
- 维护会话状态和访问记录

**实体：**
- `IdPSession`: IdP 会话
- `OfflineGrant`: 离线授权（刷新令牌）
- `AccessGrant`: 访问授权（访问令牌）

### SSO 聚合

**聚合根：SSOGroup**
- 管理 SSO 会话组
- 协调多个会话的关联

**实体：**
- `SSOSession`: SSO 会话
- `DeviceSession`: 设备会话

### MFA 聚合

**聚合根：MFAEnrollment**
- 管理用户的 MFA 配置
- 协调多个认证器

**实体：**
- `DeviceToken`: 设备令牌
- `RecoveryCode`: 恢复码
- `MFAAuthenticator`: MFA 认证器

## 值对象（Value Object）

### 认证相关值对象

- `IdentitySpec`: 身份规格
- `AuthenticatorSpec`: 认证器规格
- `AMR` (Authentication Method Reference): 认证方法引用
- `Challenge`: 挑战值
- `OTPCode`: OTP 验证码

### 会话相关值对象

- `SessionToken`: 会话令牌（不透明字符串）
- `AccessToken`: 访问令牌
- `RefreshToken`: 刷新令牌
- `SessionAttributes`: 会话属性

### 设备相关值对象

- `DeviceInfo`: 设备信息
- `DeviceFingerprint`: 设备指纹
- `IPAddress`: IP 地址

## 领域服务（Domain Service）

### 认证领域服务

- `AuthenticationFlowService`: 认证流程服务
- `IdentityVerificationService`: 身份验证服务
- `AuthenticatorVerificationService`: 认证器验证服务

### 会话领域服务

- `SessionResolutionService`: 会话解析服务
- `SessionValidationService`: 会话验证服务
- `DeviceManagementService`: 设备管理服务

### SSO 领域服务

- `SSOGroupService`: SSO 组服务
- `SSOSessionService`: SSO 会话服务

### MFA 领域服务

- `MFAVerificationService`: MFA 验证服务
- `DeviceTokenService`: 设备令牌服务
- `RecoveryCodeService`: 恢复码服务

## 应用服务（Application Service）

应用服务协调领域对象和基础设施，处理用例：

- `AuthenticationApplicationService`: 认证应用服务
- `SessionApplicationService`: 会话应用服务
- `SSOApplicationService`: SSO 应用服务
- `SocialLoginApplicationService`: 社交登录应用服务
- `MFAApplicationService`: MFA 应用服务

## 仓储（Repository）

仓储模式用于持久化领域对象：

- `UserRepository`: 用户仓储
- `IdentityRepository`: 身份仓储
- `AuthenticatorRepository`: 认证器仓储
- `SessionRepository`: 会话仓储
- `AuthenticationFlowRepository`: 认证流程仓储

## 领域事件（Domain Event）

领域事件用于解耦和异步处理：

- `UserCreatedEvent`: 用户创建事件
- `IdentityAddedEvent`: 身份添加事件
- `AuthenticatorCreatedEvent`: 认证器创建事件
- `SessionCreatedEvent`: 会话创建事件
- `SessionRevokedEvent`: 会话撤销事件
- `MFAAuthenticatedEvent`: MFA 认证事件

## 上下文映射（Context Mapping）

### 认证上下文 ↔ 身份管理上下文
- 认证流程需要查询和创建身份
- 使用共享内核模式

### 认证上下文 ↔ 会话管理上下文
- 认证成功后创建会话
- 使用客户-供应商模式

### SSO 上下文 ↔ 会话管理上下文
- SSO 需要管理多个会话
- 使用共享内核模式

### 社交登录上下文 ↔ 身份管理上下文
- 社交登录创建 OAuth 身份
- 使用客户-供应商模式

### MFA 上下文 ↔ 认证上下文
- MFA 作为认证流程的一部分
- 使用共享内核模式

## 文档结构

详细的领域模型设计请参考以下文档：

1. [认证领域模型](./02-认证领域模型.md) - 详细讲解认证流程
2. [SSO领域模型](./03-SSO领域模型.md) - 单点登录设计
3. [Social Login领域模型](./04-Social-Login领域模型.md) - 社交登录设计
4. [Session管理领域模型](./05-Session管理领域模型.md) - 会话和设备管理
5. [MFA领域模型](./06-MFA领域模型.md) - 多因素认证设计

---

**最后更新**: 2026-01-28
