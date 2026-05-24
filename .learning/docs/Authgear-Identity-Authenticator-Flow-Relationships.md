# Authgear Identity、Authenticator 和 Flow 关联关系分析

## 1. 三者关系概览

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    Flow 层（流程编排）                                    │
│                                                                                         │
│   ┌─────────────────────────────────────────────────────────────────────────────────┐  │
│   │  IntentLoginFlow / IntentSignupFlow / ...                                       │  │
│   │         │                                                                       │  │
│   │         ▼                                                                       │  │
│   │  ┌───────────────────────────────────────────────────────────────────────────┐  │  │
│   │  │                    Step Identify                                            │  │  │
│   │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │  │  │
│   │  │  │  email   │ │  oauth   │ │ passkey  │ │   ldap   │ │  custom  │       │  │  │
│   │  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘       │  │  │
│   │  │       └────────────┴────────────┴────────────┴────────────┘               │  │  │
│   │  │                      │                                                   │  │  │
│   │  │                      ▼ 调用 Identity Service                              │  │  │
│   │  └───────────────────────────────────────────────────────────────────────────┘  │  │
│   │                                    │                                              │  │
│   │         ┌──────────────────────────┴──────────────────────────┐                  │  │
│   │         ▼                                                      ▼                  │  │
│   │  ┌──────────────────────┐                            ┌──────────────────────┐     │  │
│   │  │   Step Authenticate   │                            │   Create Identity    │     │  │
│   │  │  ┌────────────────┐ │                            │   (Signup Flow)      │     │  │
│   │  │  │ primary_passwd │ │                            └──────────────────────┘     │  │
│   │  │  │ primary_passkey│ │                                        │                 │  │
│   │  │  │ primary_oob_otp│ │                                        ▼ 调用             │  │
│   │  │  │ secondary_totp │ │                              Authenticator Service      │  │
│   │  │  └────────────────┘ │                            ┌──────────────────────┐    │  │
│   │  │         │           │                            │  Create Authenticator│    │  │
│   │  │         ▼           │                            │  (Primary/Secondary) │    │  │
│   │  │  调用 Authenticator │                            └──────────────────────┘    │  │
│   │  │      Service        │                                                            │  │
│   │  └─────────────────────┘                                                            │  │
│   │                                                                                     │  │
│   └─────────────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                    │           │
                                    ▼           ▼
┌─────────────────────────────────────────┐ ┌─────────────────────────────────────────┐
│         Identity Service 层            │ │       Authenticator Service 层           │
│                                        │ │                                          │
│  ┌──────────────────────────────────┐  │ │  ┌──────────────────────────────────┐    │
│  │      Identity Provider 集合      │  │ │  │   Authenticator Provider 集合    │    │
│  │  ┌──────────┐ ┌──────────┐      │  │ │  │  ┌──────────┐ ┌──────────┐       │    │
│  │  │ LoginID  │ │  OAuth   │ ...  │  │ │  │  │ Password │ │  TOTP    │ ...  │    │
│  │  └────┬─────┘ └────┬─────┘      │  │ │  │  └────┬─────┘ └────┬─────┘       │    │
│  │       └────────────┘            │  │ │  │       └────────────┘              │    │
│  │             │                   │  │ │  │             │                      │    │
│  │             ▼                   │  │ │  │             ▼                      │    │
│  │  ┌──────────────────────────┐   │  │ │  │  ┌──────────────────────────┐    │    │
│  │  │     Identity Store       │   │  │ │  │  │   Authenticator Store    │    │    │
│  │  │  - _auth_identity_loginid│  │  │ │  │  │ - _auth_authenticator_pw │    │    │
│  │  │  - _auth_identity_oauth  │  │  │ │  │  │ - _auth_authenticator_tp │    │    │
│  │  └──────────────────────────┘   │  │ │  │  └──────────────────────────┘    │    │
│  └──────────────────────────────────┘  │ │  └──────────────────────────────────┘    │
└─────────────────────────────────────────┘ └─────────────────────────────────────────┘
```

## 2. Identity 与 Authenticator 的映射关系

### 2.1 Identity 支持的主认证方式

```go
// pkg/api/model/identity.go

func (t IdentityType) PrimaryAuthenticatorTypes(loginIDKeyType LoginIDKeyType) []AuthenticatorType {
    switch t {
    case IdentityTypeLoginID:
        switch loginIDKeyType {
        case LoginIDKeyTypeUsername:
            return []AuthenticatorType{
                AuthenticatorTypePassword,
                AuthenticatorTypePasskey,
            }
        case LoginIDKeyTypeEmail:
            return []AuthenticatorType{
                AuthenticatorTypePassword,
                AuthenticatorTypePasskey,
                AuthenticatorTypeOOBEmail,  // 邮箱可用 Email OTP
            }
        case LoginIDKeyTypePhone:
            return []AuthenticatorType{
                AuthenticatorTypePassword,
                AuthenticatorTypePasskey,
                AuthenticatorTypeOOBSMS,    // 手机可用 SMS OTP
            }
        }
        
    case IdentityTypeOAuth:
        return nil  // OAuth 本身已认证，不需要额外认证
        
    case IdentityTypeAnonymous:
        return nil  // 匿名身份无认证
        
    case IdentityTypeBiometric:
        return nil  // 生物识别已完成认证
        
    case IdentityTypePasskey:
        return []AuthenticatorType{
            AuthenticatorTypePasskey,  // Passkey Identity 使用 Passkey 认证
        }
        
    case IdentityTypeSIWE:
        return nil  // SIWE 本身已完成认证
        
    case IdentityTypeLDAP:
        return nil  // LDAP 本身已完成认证
    }
}
```

### 2.2 依赖关系矩阵

```
                        ┌───────────────────────────────────────────────────────────────┐
                        │                    Authenticator 类型                          │
                        ├──────────┬──────────┬──────────┬──────────┬──────────┬───────┤
                        │ Password │ Passkey  │ TOTP     │ OOBEmail │ OOBSMS   │ Custom│
┌───────────────────────┼──────────┼──────────┼──────────┼──────────┼──────────┼───────┤
│ Identity     LoginID  │    ✓     │    ✓     │    -     │    ✓*    │    ✓*    │   ?   │
│   类型       OAuth    │    -     │    -     │    -     │    -     │    -     │   -   │
│              Anonymous│    -     │    -     │    -     │    -     │    -     │   -   │
│              Biometric│    -     │    -     │    -     │    -     │    -     │   -   │
│              Passkey  │    -     │    ✓     │    -     │    -     │    -     │   -   │
│              SIWE     │    -     │    -     │    -     │    -     │    -     │   -   │
│              LDAP     │    -     │    -     │    -     │    -     │    -     │   -   │
│              Custom   │    ?     │    ?     │    -     │    ?     │    ?     │   ✓   │
└───────────────────────┴──────────┴──────────┴──────────┴──────────┴──────────┴───────┘

图例：
✓  = 直接支持
✓* = 依赖型支持（基于 LoginID 的值）
-  = 不支持
?  = 视具体实现而定
```

### 2.3 依赖关系详解

```go
// pkg/lib/authn/authenticator/info.go

func (i *Info) IsDependentOf(iden *identity.Info) bool {
    // 主 OOB OTP 认证器依赖于 LoginID Identity
    // 因为只有 LoginID 包含邮箱/手机号信息
    if i.Kind == KindPrimary && 
       (i.Type == model.AuthenticatorTypeOOBEmail || i.Type == model.AuthenticatorTypeOOBSMS) {
        identityClaims := iden.IdentityAwareStandardClaims()
        for k, v := range i.StandardClaims() {
            if iden.Type == model.IdentityTypeLoginID && identityClaims[k] == v {
                return true
            }
        }
    }

    // 主 Passkey 认证器依赖于 Passkey Identity
    // 因为它们共享同一个 WebAuthn Credential
    if i.Kind == KindPrimary && i.Type == model.AuthenticatorTypePasskey {
        if iden.Type == model.IdentityTypePasskey {
            if i.Passkey.CredentialID == iden.Passkey.CredentialID {
                return true
            }
        }
    }

    return false
}
```

### 2.4 依赖关系图示

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              依赖关系详细分析                                              │
│                                                                                         │
│  场景 1：LoginID + OOB OTP 依赖关系                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                                 │   │
│  │  ┌───────────────┐          依赖          ┌───────────────┐                    │   │
│  │  │  LoginID      │◄───────────────────────│  OOB Email    │                    │   │
│  │  │  (email:      │   共享 email 值         │  Authenticator│                    │   │
│  │  │   user@e.com) │                       │  (target:      │                    │   │
│  │  └───────────────┘                       │   user@e.com) │                    │   │
│  │           │                              └───────────────┘                    │   │
│  │           │                                                                   │   │
│  │           │  同时                                                             │   │
│  │           ▼                                                                   │   │
│  │  ┌───────────────┐                                                            │   │
│  │  │  Password     │  (独立的认证器，不依赖特定 Identity)                          │   │
│  │  │  Authenticator│                                                            │   │
│  │  └───────────────┘                                                            │   │
│  │                                                                                 │   │
│  └─────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                         │
│  场景 2：Passkey Identity + Passkey Authenticator 关系                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                                 │   │
│  │  ┌───────────────────────┐              ┌───────────────────────┐              │   │
│  │  │   Passkey Identity    │              │   Passkey Authenticator│              │   │
│  │  │   (存储用户信息)        │              │   (存储凭证信息)       │              │   │
│  │  │                       │              │                       │              │   │
│  │  │  - ID                 │              │  - ID                 │              │   │
│  │  │  - UserID             │◄────────────►│  - UserID             │              │   │
│  │  │  - CredentialID ──────┼──────────────│  - CredentialID       │              │   │
│  │  │  - CreationOptions    │   共享凭证ID   │  - PublicKey          │              │   │
│  │  │  - AttestationResponse│              │  - SignCount          │              │   │
│  │  └───────────────────────┘              └───────────────────────┘              │   │
│  │                                                                                 │   │
│  │  注意：一个用户可以有多个 Passkey，每个 Passkey 对应一对                         │   │
│  │        Identity 和 Authenticator                                                │   │
│  │                                                                                 │   │
│  └─────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                         │
│  场景 3：独立认证器（如 Password、TOTP）                                                │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                                 │   │
│  │       ┌───────────────┐      ┌───────────────┐                                │   │
│  │       │  LoginID      │      │  Password     │                                │   │
│  │       │  (email:      │      │  Authenticator│                                │   │
│  │       │   user@e.com) │      │               │                                │   │
│  │       └───────────────┘      └───────────────┘                                │   │
│  │                                          │                                    │   │
│  │                                          │  用户验证时使用任意                  │   │
│  │                                          │  对应的 LoginID                     │   │
│  │       ┌───────────────┐                  │                                    │   │
│  │       │  LoginID      │◄─────────────────┘                                    │   │
│  │       │  (phone:     │  (验证时关联)                                          │   │
│  │       │   +123456)    │                                                     │   │
│  │       └───────────────┘                                                     │   │
│  │                                                                                 │   │
│  │  特点：                                                                          │   │
│  │  - Password Authenticator 不依赖特定 Identity                                  │   │
│  │  - 一个 Password 可以用于验证多个 LoginID（如果它们属于同一用户）                  │   │
│  │  - 由 Identity 决定可以使用哪些 Authenticator 类型                              │   │
│  │                                                                                 │   │
│  └─────────────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

## 3. Flow 与 Identity/Authenticator 的协作

### 3.1 Flow 步骤与 Identity/Authenticator 的映射

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              Flow 步骤调用链                                            │
│                                                                                         │
│  Signup Flow                                                                            │
│  ═══════════                                                                            │
│                                                                                         │
│  Step: identify                                                                          │
│  ├─ Option: email ──► IntentUseIdentityLoginID ──► NodeDoUseIdentity                    │
│  ├─ Option: oauth ──► IntentOAuth ──► NodeDoUseIdentity                                 │
│  ├─ Option: passkey ► IntentUseIdentityPasskey ──► NodeDoUseIdentityPasskey             │
│  └─ Option: custom ─► IntentUseIdentityCustom ──► NodeDoUseIdentityCustom             │
│           │                                                                             │
│           ▼ 找到/创建 Identity                                                           │
│           ▼ 通过 deps.Identities.New() / Create()                                       │
│                                                                                         │
│  Step: create_authenticator (如果 Identity 需要)                                        │
│  ├─ Option: primary_password ──► IntentCreateAuthenticatorPassword                      │
│  ├─ Option: primary_oob_otp_email ──► IntentCreateAuthenticatorOOBOTP                   │
│  └─ Option: primary_passkey ──► IntentCreateAuthenticatorPasskey                        │
│           │                                                                             │
│           ▼ 创建 Authenticator                                                           │
│           ▼ 通过 deps.Authenticators.New() / Create()                                   │
│                                                                                         │
│  Step: verify (如果需要验证 Identity)                                                    │
│  └─ 发送 OTP/邮件验证                                                                    │
│           ▼ 通过 deps.OTPCodeService.SendOTP()                                          │
│           ▼ 通过 deps.VerificationService.Verify()                                      │
│                                                                                         │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  Login Flow                                                                             │
│  ══════════                                                                             │
│                                                                                         │
│  Step: identify                                                                          │
│  ├─ Option: email ──► IntentUseIdentityLoginID                                          │
│  │                     ├─ 查找 Identity: deps.Identities.SearchBySpec()                  │
│  │                     └─ 可能需要 Authenticator: deps.Authenticators.VerifyOneWithSpec()│
│  ├─ Option: passkey ──► IntentUseIdentityPasskey                                        │
│  │                     └─ 同时验证 Identity 和 Authenticator（一体）                      │
│  └─ Option: custom ──► IntentUseIdentityCustom                                        │
│                        ├─ 查找 Identity                                                │
│                        └─ 可能需要 Authenticator                                        │
│                                                                                         │
│  Step: authenticate (如果需要)                                                          │
│  ├─ Option: primary_password ──► IntentUseAuthenticatorPassword                       │
│  │                                └─ deps.Authenticators.VerifyOneWithSpec()             │
│  ├─ Option: primary_passkey ──► IntentUseAuthenticatorPasskey                         │
│  │                                └─ deps.Authenticators.VerifyOneWithSpec()             │
│  └─ Option: secondary_totp ──► IntentUseAuthenticatorTOTP                               │
│                                   └─ deps.Authenticators.VerifyOneWithSpec()             │
│                                                                                         │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  关键方法调用汇总                                                                        │
│  ═══════════════                                                                         │
│                                                                                         │
│  Identity Service:                                                                       │
│  ├── New(ctx, userID, spec) -> *Info          (创建 Identity 模型)                      │
│  ├── Create(ctx, *Info)                       (持久化 Identity)                          │
│  ├── SearchBySpec(ctx, spec) -> *Info         (查找 Identity)                          │
│  └── ListByUser(ctx, userID) -> []*Info       (列出用户的所有 Identity)                 │
│                                                                                         │
│  Authenticator Service:                                                                  │
│  ├── New(ctx, spec) -> *Info                   (创建 Authenticator 模型)                │
│  ├── Create(ctx, *Info)                        (持久化 Authenticator)                    │
│  ├── VerifyOneWithSpec(ctx, userID, type, infos, spec)                                  │
│  │                              -> (*Info, *VerifyResult, error)  (验证)                │
│  └── List(ctx, userID, filters...) -> []*Info  (列出用户的 Authenticators)              │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Flow Intent 中的典型调用模式

```go
// pkg/lib/authenticationflow/declarative/intent_use_identity_passkey.go

func (n *IntentUseIdentityPasskey) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    // 1. 从输入中获取凭证
    assertionResponse := inputAssertionResponse.GetAssertionResponse()
    assertionResponseBytes, _ := json.Marshal(assertionResponse)
    
    // 2. 构建 Identity Spec
    identitySpec := &identity.Spec{
        Type: model.IdentityTypePasskey,
        Passkey: &identity.PasskeySpec{
            AssertionResponse: assertionResponseBytes,
        },
    }
    
    // 3. 调用 Identity Service 查找 Identity
    //    这是第一步：确定用户是谁
    exactMatch, err := findExactOneIdentityInfo(ctx, deps, identitySpec)
    if err != nil {
        return nil, err
    }
    userID := exactMatch.UserID
    
    // 4. 构建 Authenticator Spec
    authenticatorSpec := &authenticator.Spec{
        Type: model.AuthenticatorTypePasskey,
        Passkey: &authenticator.PasskeySpec{
            AssertionResponse: assertionResponseBytes,
        },
    }
    
    // 5. 获取该用户的 Passkey Authenticators
    authenticators, err := deps.Authenticators.List(ctx, userID, 
        authenticator.KeepType(model.AuthenticatorTypePasskey))
    
    // 6. 调用 Authenticator Service 验证
    //    这是第二步：验证用户的凭证
    authenticatorInfo, verifyResult, err := deps.Authenticators.VerifyOneWithSpec(ctx,
        userID,
        model.AuthenticatorTypePasskey,
        authenticators,
        authenticatorSpec,
        &facade.VerifyOptions{
            AuthenticationDetails: facade.NewAuthenticationDetails(
                userID,
                authn.AuthenticationStagePrimary,
                authn.AuthenticationTypePasskey,
            ),
        },
    )
    
    // 7. 创建结果节点
    result, err := NewNodeDoUseIdentityPasskey(ctx, deps, flows, &NodeDoUseIdentityPasskeyOptions{
        Identity:      exactMatch,      // Identity 结果
        Authenticator: authenticatorInfo, // Authenticator 结果（可选）
        RequireUpdate: verifyResult.Passkey, // 需要更新（如迁移密钥）
    })
    
    return result, nil
}
```

### 3.3 创建流程中的协作

```go
// pkg/lib/authenticationflow/declarative/utils_create_authenticator.go

func createAuthenticator(ctx context.Context, deps *authflow.Dependencies, userID string, spec *authenticator.Spec) (*authenticator.Info, error) {
    // 1. 创建 Authenticator 模型（内存中）
    info, err := deps.Authenticators.New(ctx, spec)
    if err != nil {
        return nil, err
    }
    
    // 2. 持久化到数据库
    err = deps.Authenticators.Create(ctx, info)
    if err != nil {
        return nil, err
    }
    
    return info, nil
}

// 使用场景：Signup Flow 的 create_authenticator 步骤
func (i *IntentSignupFlowStepCreateAuthenticator) ReactTo(...) {
    // ...
    
    // 创建密码认证器
    passwordSpec := &authenticator.Spec{
        Type:      model.AuthenticatorTypePassword,
        Kind:      authenticator.KindPrimary,
        Password: &authenticator.PasswordSpec{
            PlainPassword: input.GetPassword(),
        },
    }
    
    // 调用通用创建函数
    info, err := createAuthenticator(ctx, deps, userID, passwordSpec)
    
    // 创建结果节点
    return authflow.NewNodeSimple(&NodeDoCreateAuthenticator{
        Authenticator: info,
    }), nil
}
```

## 4. 数据流追踪

### 4.1 完整的登录流程数据流

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                        登录流程数据流示例（Login + Password）                              │
│                                                                                         │
│  1. 用户提交邮箱和密码                                                                    │
│     ───────────────────                                                                   │
│     Input: {                                                                              │
│       "identification": "email",                                                         │
│       "login_id": "user@example.com",                                                    │
│       "password": "secret123"                                                            │
│     }                                                                                     │
│                                                                                         │
│  2. Flow 层处理                                                                           │
│     ─────────────                                                                         │
│     IntentLoginFlowStepIdentify.ReactTo()                                                │
│     ├─ 选择分支：email ──► IntentUseIdentityLoginID                                      │
│     │                                                                                     │
│     │  IntentUseIdentityLoginID.ReactTo()                                                │
│     │  ├─ 构建 Identity Spec: { type: "login_id", login_id: { key: "email", value: "..." } }│
│     │  │                                                                                  │
│     │  ├─ 调用 deps.Identities.SearchBySpec(spec)                                         │
│     │  │   └─ 找到 Identity: { id: "id1", user_id: "u1", type: "login_id", login_id: {...} }│
│     │  │                                                                                  │
│     │  └─ 创建 NodeDoUseIdentity: { identity: Identity, use_input_password: true }       │
│     │                                                                                     │
│     └─ Flow 继续到下一步                                                                  │
│                                                                                         │
│  3. 认证步骤                                                                              │
│     ─────────                                                                             │
│     IntentLoginFlowStepAuthenticate.ReactTo()                                            │
│     ├─ 找到上一步的 Identity: user_id = "u1"                                               │
│     │                                                                                     │
│     ├─ 调用 deps.Authenticators.List(ctx, "u1", KeepKind(KindPrimary))                   │
│     │   └─ 返回: [                                                                        │
│     │        { id: "a1", type: "password", kind: "primary", password: { hash: "..." } },   │
│     │        { id: "a2", type: "oob_otp_email", kind: "primary", oobotp: { email: "..." } }│
│     │      ]                                                                              │
│     │                                                                                     │
│     ├─ 用户选择 primary_password                                                          │
│     │  ├─ 构建 Authenticator Spec: { type: "password", password: { plain_password: "secret123" } }│
│     │  │                                                                                  │
│     │  ├─ 调用 deps.Authenticators.VerifyOneWithSpec(                                    │
│     │  │        ctx, "u1", "password",                                                     │
│     │  │        [Authenticator1, Authenticator2],                                         │
│     │  │        spec, options)                                                            │
│     │  │   └─ 验证通过，返回: { info: Authenticator1, result: nil }                        │
│     │  │                                                                                  │
│     │  └─ 创建 NodeDoUseAuthenticator: { authenticator: Authenticator1 }                 │
│     │                                                                                     │
│     └─ 返回结果                                                                          │
│                                                                                         │
│  4. 创建会话                                                                              │
│     ──────────                                                                            │
│     IntentLoginFlow.ReactTo()                                                            │
│     ├─ 找到 MilestoneDoUseIdentity ──► 获取 user_id                                       │
│     ├─ 找到 MilestoneDoAuthenticate ──► 获取认证详情                                       │
│     └─ 创建 NodeDoCreateSession: { user_id: "u1", ... }                                   │
│         └─ 调用 deps.IDPSessions.CreateSession(...)                                      │
│             └─ 创建会话记录                                                               │
│                 Session: { id: "s1", user_id: "u1", ... }                                  │
│                                                                                         │
│  5. 流程完成                                                                              │
│     ────────                                                                              │
│     Flow 返回: {                                                                          │
│       state_token: "...",                                                                │
│       action: { type: "finished" }                                                       │
│     }                                                                                     │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Identity 和 Authenticator 在 Session 中的体现

```go
// 登录成功后，Session 包含的信息
type IDPSession struct {
    ID          string
    UserID      string
    // ...
    
    // 认证详情（从 Milestone 收集）
    AuthenticationDetails *AuthenticationDetails
}

type AuthenticationDetails struct {
    UserID string
    
    // 使用的 Identity
    Identity *identity.Info
    
    // 使用的认证方式（可以有多个，如 MFA）
    Authenticators []*authenticator.Info
    
    // 认证阶段
    Stage AuthenticationStage // primary / secondary
    
    // 认证类型汇总
    Types []AuthenticationType
}

// 这些信息用于：
// 1. 生成 ID Token / Access Token
// 2. AMR (Authentication Methods Reference) Claim
// 3. 审计日志
// 4. 会话管理
```

## 5. 扩展新类型的关联关系

### 5.1 设计新的 Identity + Authenticator 组合

```
场景：添加 "企业证书" (Enterprise Certificate) 认证

设计决策：
═══════════

Identity: EnterpriseCertificate
───────────────────────────────
- 标识用户的 "企业身份"
- 包含：企业ID、证书序列号、员工ID、部门信息
- 关联 Claims: email（员工邮箱）、preferred_username

Authenticator: CertificateAuth
────────────────────────────────
- 验证企业证书（X.509 证书 + 私钥签名）
- 可以是 Primary（单因素）或 Secondary（MFA 的一部分）
- 依赖：证书需要与 EnterpriseCertificate Identity 关联

关系设计：
──────────

EnterpriseCertificate.Identity ────────┐
  - 企业ID                              │
  - 员工ID                              │ 依赖关系：
  - 证书序列号 ◄────────────────────────┤ CertificateAuth
  - 部门信息                            │ 认证器依赖
                                      │ 特定的 Identity
EnterpriseCertificate.Authenticator ───┘
  - 证书序列号（匹配 Identity）
  - 公钥（用于验证签名）

配置示例：
──────────

authentication:
  identities:
    - login_id
    - enterprise_certificate  # 新增
  
  authenticators:
    primary:
      - password
      - enterprise_certificate  # 新增
  
  flows:
    login_flows:
      - name: enterprise_login
        steps:
          - type: identify
            one_of:
              - identification: enterprise_certificate
                # 证书识别直接完成认证（类似 Passkey）
```

### 5.2 实现依赖关系

```go
// 1. Identity 模型
// pkg/lib/authn/identity/enterprise_identity.go

type Enterprise struct {
    ID            string    `json:"id"`
    UserID        string    `json:"user_id"`
    EnterpriseID  string    `json:"enterprise_id"`   // 企业标识
    EmployeeID    string    `json:"employee_id"`     // 员工号
    CertSerial    string    `json:"cert_serial"`     // 证书序列号（关键关联字段）
    Department    string    `json:"department"`
    Email         string    `json:"email"`
}

func (i *Enterprise) ToInfo() *Info { ... }

func (i *Enterprise) IdentityAwareStandardClaims() map[model.ClaimName]string {
    claims := map[model.ClaimName]string{}
    if i.Email != "" {
        claims[model.ClaimEmail] = i.Email
    }
    return claims
}

// 2. Authenticator 模型
// pkg/lib/authn/authenticator/enterprise_authenticator.go

type EnterpriseAuth struct {
    ID         string `json:"id"`
    UserID     string `json:"user_id"`
    Kind       Kind   `json:"kind"`
    CertSerial string `json:"cert_serial"`  // 与 Identity 关联的字段
    PublicKey  []byte `json:"public_key"`
}

func (a *EnterpriseAuth) ToInfo() *Info { ... }

// 3. 实现依赖关系判断
// pkg/lib/authn/authenticator/info.go

func (i *Info) IsDependentOf(iden *identity.Info) bool {
    // ... 现有逻辑 ...
    
    // 企业证书认证器依赖企业身份
    if i.Kind == KindPrimary && i.Type == model.AuthenticatorTypeEnterprise {
        if iden.Type == model.IdentityTypeEnterprise {
            // 通过证书序列号关联
            return i.Enterprise.CertSerial == iden.Enterprise.CertSerial
        }
    }
    
    return false
}

// 4. Flow Intent 实现
// pkg/lib/authenticationflow/declarative/intent_use_identity_enterprise.go

func (n *IntentUseIdentityEnterprise) ReactTo(...) {
    // 1. 获取证书信息
    certPEM := input.GetCertificate()
    
    // 2. 解析证书获取序列号
    cert, _ := parseCertificate(certPEM)
    serialNumber := cert.SerialNumber.String()
    
    // 3. 构建 Identity Spec
    identitySpec := &identity.Spec{
        Type: model.IdentityTypeEnterprise,
        Enterprise: &identity.EnterpriseSpec{
            CertSerial: serialNumber,
        },
    }
    
    // 4. 查找 Identity
    exactMatch, _ := deps.Identities.SearchBySpec(ctx, identitySpec)
    userID := exactMatch.UserID
    
    // 5. 构建 Authenticator Spec（证书验证数据）
    authenticatorSpec := &authenticator.Spec{
        Type: model.AuthenticatorTypeEnterprise,
        Enterprise: &authenticator.EnterpriseSpec{
            Certificate: certPEM,
            Signature:   input.GetSignature(),
        },
    }
    
    // 6. 获取候选认证器
    authenticators, _ := deps.Authenticators.List(ctx, userID,
        authenticator.KeepType(model.AuthenticatorTypeEnterprise),
        authenticator.KeepKind(authenticator.KindPrimary),
    )
    
    // 7. 验证
    authInfo, _, _ := deps.Authenticators.VerifyOneWithSpec(ctx,
        userID, model.AuthenticatorTypeEnterprise,
        authenticators, authenticatorSpec, options)
    
    // 8. 创建结果节点
    return NewNodeDoUseIdentityEnterprise(...), nil
}
```

## 6. 关键设计要点总结

### 6.1 职责分离

```
┌─────────────────────────────────────────────────────────────────┐
│                         职责分离原则                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Identity（我是谁）                                              │
│  ─────────────────                                               │
│  • 标识用户的唯一性                                               │
│  • 包含用户可识别的信息（邮箱、用户名、外部ID等）                    │
│  • 用于账户关联（Account Linking）                               │
│  • 决定可以使用哪些认证方式                                        │
│                                                                  │
│  Authenticator（怎么证明）                                        │
│  ──────────────────────                                          │
│  • 包含凭证信息（密码哈希、密钥、证书等）                           │
│  • 提供验证方法（Authenticate）                                  │
│  • 可以独立于 Identity 存在                                        │
│  • 一个 Identity 可以有多个 Authenticator                         │
│                                                                  │
│  Flow（流程编排）                                                 │
│  ───────────────                                                 │
│  • 协调 Identity 和 Authenticator 的使用                          │
│  • 处理用户输入和状态转换                                          │
│  • 实现复杂认证场景（MFA、Step-up等）                              │
│  • 与具体 Identity/Authenticator 类型解耦                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 扩展指南

```
添加新类型时的决策树：

1. 是否新增 Identity 类型？
   ├─ 是否需要一种新的用户标识方式？
   │   └─ 是 ──► 新增 Identity
   │
   └─ 是否可以用现有 Identity + 新的 Authenticator 实现？
       └─ 是 ──► 跳过 Identity，只添加 Authenticator

2. 是否新增 Authenticator 类型？
   ├─ 是否需要一种新的凭证验证方式？
   │   └─ 是 ──► 新增 Authenticator
   │
   └─ 是否可以用现有认证方式组合实现？
       └─ 是 ──► 修改 Flow 配置即可

3. 是否新增 Flow 类型？
   ├─ 是否现有 Flow 类型无法满足需求？
   │   └─ 是 ──► 新增 Flow
   │
   └─ 是否可以通过配置现有 Flow 实现？
       └─ 是 ──► 修改 Flow 配置

典型扩展模式：
═════════════

模式 1：新的第三方登录
─────── ────────────
• 添加新 OAuth Provider 配置
• 无需新增 Identity/Authenticator 类型
• 仅需配置 + 可能的 Provider 特定代码

模式 2：新的生物识别方式
─────── ────────────────
• Identity：复用 Biometric 或新增类型
• Authenticator：新增类型
• 需要硬件/SDK 集成

模式 3：新的 MFA 方式
─────── ─────────────
• Identity：复用现有（通常是 LoginID）
• Authenticator：新增 Secondary 类型
• Flow：配置中启用二次认证步骤

模式 4：企业特定认证
─────── ───────────
• Identity：新增（包含企业信息）
• Authenticator：新增（证书验证）
• Flow：新增或配置专用流程
```

## 7. 参考实现路径

```
新增类型的文件清单（以 Custom 为例）：

Identity:
─────────
pkg/api/model/identity.go                          - 添加 IdentityTypeCustom 常量
pkg/lib/authn/identity/custom_identity.go          - Identity 模型定义
pkg/lib/authn/identity/custom_spec.go              - Identity Spec 定义
pkg/lib/authn/identity/info.go                     - 扩展 Info 结构（添加 Custom 字段和 case）
pkg/lib/authn/identity/spec.go                   - 扩展 Spec 结构（添加 Custom 字段）
pkg/lib/authn/identity/claim_key.go              - 添加 Claim Key 常量
pkg/lib/authn/identity/custom/provider.go          - Provider 实现
pkg/lib/authn/identity/custom/store.go             - Store 实现
pkg/lib/authn/identity/service/service.go          - Service 集成（添加 Custom Provider 接口和 case）
pkg/lib/authn/identity/deps.go                   - 依赖注入配置
migrations/2026XXXX_add_custom_identity.sql      - 数据库表

Authenticator:
──────────────
pkg/api/model/authenticator.go                   - 添加 AuthenticatorTypeCustom 常量
pkg/lib/authn/authenticator/custom_authenticator.go - Authenticator 模型定义
pkg/lib/authn/authenticator/custom_spec.go           - Authenticator Spec 定义
pkg/lib/authn/authenticator/info.go                - 扩展 Info 结构
pkg/lib/authn/authenticator/spec.go                - 扩展 Spec 结构
pkg/lib/authn/authenticator/keys.go                - 添加 Claim Key 常量
pkg/lib/authn/authenticator/custom/provider.go       - Provider 实现
pkg/lib/authn/authenticator/custom/store.go          - Store 实现
pkg/lib/authn/authenticator/service/service.go       - Service 集成
pkg/lib/authn/authenticator/deps.go                - 依赖注入配置
migrations/2026XXXX_add_custom_authenticator.sql   - 数据库表

Flow:
─────
pkg/api/model/identification.go                  - 添加 AuthenticationFlowIdentificationCustom
pkg/api/model/authentication.go                  - 添加 AuthenticationFlowAuthenticationPrimary/SecondaryCustom
pkg/lib/authenticationflow/declarative/intent_use_identity_custom.go     - Identity Intent
pkg/lib/authenticationflow/declarative/intent_use_authenticator_custom.go - Authenticator Intent
pkg/lib/authenticationflow/declarative/node_do_use_identity_custom.go      - Identity Node
pkg/lib/authenticationflow/declarative/node_do_use_authenticator_custom.go - Authenticator Node
pkg/lib/authenticationflow/declarative/input_take_custom_identity.go     - Input Schema
pkg/lib/authenticationflow/declarative/data_custom.go                      - Output Data
pkg/lib/config/authentication_flow.go            - 配置 Schema 更新（枚举值）

配置示例：
──────────
authgear.yaml
authentication:
  identities:
    - login_id
    - oauth
    - custom
  authenticators:
    primary:
      - password
      - custom
    secondary:
      - totp
      - custom
  flows:
    login_flows:
      - name: custom_login
        steps:
          - type: identify
            one_of:
              - identification: custom
```

这份分析文档详细说明了 Authgear 中 Identity、Authenticator 和 Flow 三者之间的关系，以及如何在添加新类型时保持正确的关联关系。
