# MFA领域设计

本文档详细说明 Authgear Server MFA（多因素认证）领域的领域模型设计，这是系统的重要安全特性之一。

## 领域概述

MFA领域负责管理多因素认证，包括Primary Authenticator和Secondary Authenticator的区分、Device Token用于跳过MFA、以及Recovery Code用于账户恢复。

### 核心概念

1. **Primary Authenticator** - 主要认证器（用于身份认证）
2. **Secondary Authenticator** - 次要认证器（用于MFA）
3. **Device Token** - 设备信任令牌（用于跳过MFA）
4. **Recovery Code** - 恢复码（用于账户恢复）
5. **MFA Policy** - MFA策略（决定MFA是否必需）

## 聚合设计

### 聚合根：User（MFA相关实体作为子实体）

**职责**: 管理MFA相关的认证器和设备信任

#### 实体（Entities）

##### Authenticator（实体，MFA相关）
- **标识**: `ID` (string)
- **属性**:
  - `Type` - 认证器类型（Password, TOTP, OOB-OTP等）
  - `Kind` - Primary或Secondary
  - `IsDefault` - 是否默认
- **业务规则**:
  - Primary Authenticator用于身份认证
  - Secondary Authenticator用于MFA
  - Primary Password需要Secondary认证
  - Primary WebAuthn不需要Secondary认证

**代码位置**: `pkg/lib/authn/authenticator/`

##### DeviceToken（实体）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `Token` - 设备令牌（Base32编码的64字符字符串）
  - `CreatedAt` - 创建时间
  - `ExpireAt` - 过期时间
- **业务规则**:
  - DeviceToken用于跳过MFA
  - 存储在Cookie中
  - 有过期时间
  - 可以撤销所有DeviceToken

**代码位置**: `pkg/lib/authn/mfa/device_token.go`

##### RecoveryCode（实体）
- **标识**: `ID` (string)
- **属性**:
  - `UserID` - 用户ID
  - `Code` - 恢复码（Crockford Base32编码的10字符字符串）
  - `CreatedAt`, `UpdatedAt` - 时间戳
  - `Consumed` - 是否已使用
- **业务规则**:
  - RecoveryCode用于恢复账户
  - 使用后立即失效
  - 可以重新生成
  - 支持账户锁定保护

**代码位置**: `pkg/lib/authn/mfa/recovery_code.go`

#### 值对象（Value Objects）

##### MFAPolicy（值对象）
- **属性**:
  - `Mode` - MFA模式（disabled, required, if_exists）
  - `RequiredForPrimaryPassword` - Primary Password是否需要MFA
- **业务规则**:
  - 决定MFA是否必需
  - 影响认证流程
  - 可以按用户配置

**代码位置**: `pkg/lib/config/authentication.go`

##### AuthenticatorKind（值对象）
- **类型**: 枚举
- **值**: `Primary`, `Secondary`
- **业务规则**:
  - Primary用于身份认证
  - Secondary用于MFA
  - 一个Authenticator只能是Primary或Secondary

##### AMR（值对象，MFA相关）
- **类型**: 字符串数组
- **值**: `mfa`, `x_secondary_totp`, `x_secondary_oob_otp_email`, `x_recovery_code`, `x_device_token`等
- **业务规则**:
  - 记录MFA过程中使用的方法
  - 用于安全审计

**代码位置**: `pkg/api/model/amr.go`

#### 领域服务（Domain Services）

##### MFAService
- **职责**: 管理MFA相关操作
- **方法**:
  - `GenerateDeviceToken()` - 生成设备令牌
  - `CreateDeviceToken()` - 创建设备令牌
  - `VerifyDeviceToken()` - 验证设备令牌
  - `InvalidateAllDeviceTokens()` - 撤销所有设备令牌
  - `GenerateRecoveryCodes()` - 生成恢复码
  - `ReplaceRecoveryCodes()` - 替换恢复码
  - `VerifyRecoveryCode()` - 验证恢复码
  - `ConsumeRecoveryCode()` - 消费恢复码

**代码位置**: `pkg/lib/authn/mfa/service.go`

##### AuthenticatorService（MFA相关）
- **职责**: 管理Secondary Authenticator
- **方法**:
  - `ListSecondaryAuthenticators()` - 列出用户的Secondary Authenticator
  - `VerifySecondaryAuthenticator()` - 验证Secondary Authenticator
  - `CreateSecondaryAuthenticator()` - 创建Secondary Authenticator

**代码位置**: `pkg/lib/authn/authenticator/service/`

## MFA策略

### MFA模式

#### disabled（禁用）
- **含义**: MFA完全禁用
- **行为**: 即使用户有Secondary Authenticator，也不需要MFA

#### required（必需）
- **含义**: MFA是必需的
- **行为**: 所有用户必须有至少一个Secondary Authenticator
- **业务规则**: 用户注册时必须设置Secondary Authenticator

#### if_exists（如果存在）
- **含义**: 如果用户有Secondary Authenticator，则需要MFA
- **行为**: 用户可以选择是否启用MFA
- **默认模式**: 这是系统的默认模式

### Primary Authenticator与MFA

不同的Primary Authenticator对MFA的要求不同：

1. **Primary Password** → 需要Secondary认证
2. **Primary OOB-OTP** → 需要Secondary认证
3. **Primary WebAuthn** → 不需要Secondary认证
4. **OAuth Identity** → 不需要Secondary认证（无Primary Authenticator）

## MFA验证流程

### 标准MFA流程

1. **用户完成Primary认证** → 使用Password或其他Primary Authenticator
2. **检查MFA要求** → 根据Primary Authenticator和MFA Policy判断
3. **如果需要MFA** → 检查Device Token
4. **Device Token有效** → 跳过MFA，直接创建Session
5. **Device Token无效或不存在** → 要求MFA
6. **用户选择MFA方式** → TOTP、OOB-OTP或Recovery Code
7. **验证MFA** → 验证Secondary Authenticator
8. **MFA成功** → 可选创建Device Token，创建Session

### Device Token流程

1. **用户完成MFA** → 验证Secondary Authenticator成功
2. **用户选择"信任此设备"** → 触发Device Token创建
3. **生成Device Token** → Base32编码的64字符字符串
4. **设置Cookie** → 存储在浏览器Cookie中
5. **持久化** → 保存到数据库
6. **后续登录** → 检查Device Token，如果有效则跳过MFA

### Recovery Code流程

1. **用户无法使用MFA** → 丢失TOTP设备或无法接收OTP
2. **用户输入Recovery Code** → 提供恢复码
3. **验证Recovery Code** → 检查Code是否存在且未使用
4. **消费Recovery Code** → 标记为已使用
5. **完成MFA** → 创建Session
6. **建议重新生成** → 提示用户重新生成Recovery Code

## 领域事件

### MFARequired
- **触发时机**: 需要MFA验证
- **事件数据**: UserID, PrimaryAuthenticatorType, AvailableSecondaryAuthenticators
- **用途**: 记录MFA要求，触发MFA流程

### MFAVerified
- **触发时机**: MFA验证成功
- **事件数据**: UserID, SecondaryAuthenticatorType, AMR
- **用途**: 记录MFA验证，用于审计

### DeviceTokenCreated
- **触发时机**: Device Token创建
- **事件数据**: UserID, DeviceTokenID
- **用途**: 记录设备信任

### RecoveryCodeUsed
- **触发时机**: Recovery Code被使用
- **事件数据**: UserID, RecoveryCodeID
- **用途**: 记录恢复码使用，安全审计

## 设计模式

### 1. 策略模式（Strategy Pattern）
不同的MFA方式（TOTP、OOB-OTP、Recovery Code）使用不同的策略实现。

### 2. 模板方法模式（Template Method）
MFA验证的框架是固定的，具体验证由Authenticator类型决定。

### 3. 责任链模式（Chain of Responsibility）
MFA验证按优先级尝试不同的Authenticator。

## 代码映射

### 核心类映射

| DDD概念 | 代码位置 | 说明 |
|---------|---------|------|
| DeviceToken实体 | `pkg/lib/authn/mfa/device_token.go` | 设备令牌 |
| RecoveryCode实体 | `pkg/lib/authn/mfa/recovery_code.go` | 恢复码 |
| MFAService | `pkg/lib/authn/mfa/service.go` | MFA服务 |
| AuthenticatorService | `pkg/lib/authn/authenticator/service/` | 认证器服务 |

### 关键方法映射

| 方法 | 代码位置 | 说明 |
|------|---------|------|
| GenerateDeviceToken() | `pkg/lib/authn/mfa/service.go` | 生成设备令牌 |
| VerifyDeviceToken() | `pkg/lib/authn/mfa/service.go` | 验证设备令牌 |
| GenerateRecoveryCodes() | `pkg/lib/authn/mfa/service.go` | 生成恢复码 |
| VerifyRecoveryCode() | `pkg/lib/authn/mfa/service.go` | 验证恢复码 |

## 业务流程示例

### TOTP MFA流程

1. **用户完成Primary认证** → 使用Password登录
2. **系统检查MFA要求** → Primary Password需要MFA
3. **系统检查Device Token** → 未找到有效Token
4. **系统要求MFA** → 显示TOTP输入界面
5. **用户输入TOTP** → 从Google Authenticator获取6位数字
6. **系统验证TOTP** → 验证所有用户的TOTP Authenticator
7. **MFA成功** → 可选创建Device Token
8. **创建Session** → 完成登录

### Recovery Code流程

1. **用户无法使用TOTP** → 丢失手机
2. **用户选择使用Recovery Code** → 点击"使用恢复码"
3. **用户输入Recovery Code** → 提供10字符恢复码
4. **系统验证Recovery Code** → 检查Code是否存在且未使用
5. **系统消费Recovery Code** → 标记为已使用
6. **MFA成功** → 创建Session
7. **系统提示重新生成** → 建议用户重新生成Recovery Code

### Device Token流程

1. **用户完成MFA** → 验证TOTP成功
2. **系统询问是否信任设备** → 显示"信任此设备"选项
3. **用户选择信任** → 触发Device Token创建
4. **系统生成Device Token** → 64字符Base32字符串
5. **系统设置Cookie** → 存储在浏览器Cookie中
6. **系统持久化** → 保存到数据库
7. **后续登录** → 检查Device Token，如果有效则跳过MFA

## 设计原则

### 1. 安全性优先
- MFA是额外的安全层
- Device Token有过期时间
- Recovery Code使用后立即失效
- 支持账户锁定保护

### 2. 用户体验
- 支持Device Token减少MFA频率
- 支持Recovery Code用于账户恢复
- 清晰的MFA提示和错误信息

### 3. 灵活性
- 支持多种Secondary Authenticator
- 支持多个TOTP Authenticator
- MFA策略可配置

### 4. 可审计性
- 记录所有MFA事件
- 记录Device Token创建和撤销
- 记录Recovery Code使用

## 扩展点

### 自定义MFA方式
可以通过实现`SecondaryAuthenticator`接口创建自定义MFA方式。

### 自定义MFA策略
可以实现自定义MFA策略逻辑。

### MFA事件监听
可以监听MFA事件，实现自定义业务逻辑。

---

**最后更新**: 2026-01-28
