# Authgear Server: OOB OTP 完整流程详解

本文档详细说明 `email_password_primary_oob_otp_email` 流程的完整执行过程，从 IDENTIFY 到最终提交 OTP code。

## 流程配置

```yaml
- name: email_password_primary_oob_otp_email
  type: LOGIN
  steps:
    - type: IDENTIFY
      oneOf:
        - identification: email
    - type: AUTHENTICATE
      oneOf:
        - authentication: primary_password
    - type: AUTHENTICATE
      oneOf:
        - authentication: primary_oob_otp_email
```

---

## 第一阶段：IDENTIFY（身份识别）

### 1.1 创建流程请求

```bash
curl --location 'http://localhost:8080/api/v1/authentication_flows' \
--header 'Content-Type: application/json' \
--data '{
    "flow_type": "login",
    "flow_name": "email_password_primary_oob_otp_email",
    "input": {
        "identification": "email",
        "login_id": "user@example.com"
    }
}'
```

### 1.2 服务端处理流程

```
Service.CreateNewFlow()
    ↓
IntentLoginFlowStepIdentify (root intent)
    ↓
Accept 循环:
    - FindInputReactor: IntentLoginFlowStepIdentify
    - CanReactTo: 返回 InputSchemaIdentify
    - MakeInput: 解析 {identification: "email", login_id: "..."}
    - ReactTo: 创建 NodeDoUseIdentity
    - appendNode: Nodes[0] = NodeDoUseIdentity
    ↓
生成新 state_token
返回 FlowResponse
```

### 1.3 返回给客户端

```json
{
    "result": {
        "state_token": "authflowstate_2IrRI8IB3ud0zS_7vwXp3hVbvuiu4v1G4yAoMyBYLdeQtyMa",
        "type": "login",
        "name": "email_password_primary_oob_otp_email",
        "action": {
            "type": "authenticate",
            "data": {
                "type": "authentication_data",
                "options": [
                    {
                        "authentication": "primary_password",
                        "bot_protection": null
                    }
                ]
            }
        }
    }
}
```

此时 Nodes 链：
```
Nodes[0]: NodeDoUseIdentity (email: user@example.com)
```

---

## 第二阶段：AUTHENTICATE Password（密码认证）

### 2.1 提交密码

```bash
curl --location 'http://localhost:8080/api/v1/authentication_flows/states/input' \
--header 'Content-Type: application/json' \
--data '{
    "state_token": "authflowstate_2IrRI8IB3ud0zS_7vwXp3hVbvuiu4v1G4yAoMyBYLdeQtyMa",
    "input": {
        "authentication": "primary_password",
        "password": "password123"
    }
}'
```

### 2.2 服务端处理流程

```
Service.FeedInput()
    ↓
GetFlowByStateToken() -> 获取 Flow (包含 NodeDoUseIdentity)
    ↓
Accept 循环:
    - FindInputReactor: IntentLoginFlowStepAuthenticate
    - 当前状态: !authenticationMethodTaken
    - CanReactTo: 返回 InputSchemaAuthenticationMethod
    - MakeInput: 解析 {authentication: "primary_password", password: "..."}
    - ReactTo: 进入 switch case
        ↓
        case primary_password:
            return NewSubFlow(&IntentUseAuthenticatorPassword{...})
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentUseAuthenticatorPassword
    - ReactTo:
        - VerifyPassword()
        - 成功 -> 返回 NodeDoUseAuthenticatorPassword
    - appendNode: Nodes[1] = NodeDoUseAuthenticatorPassword
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentLoginFlowStepAuthenticate
    - 当前状态: authenticationMethodTaken=true, !authenticated
    - CanReactTo: 返回 nil, nil (等待 milestone)
    - ReactTo: 检查 milestone
        - 发现 MilestoneDidAuthenticate (来自 NodeDoUseAuthenticatorPassword)
        - 返回 NodeDoUseAuthenticatorSimple
    - appendNode: Nodes[2] = NodeDoUseAuthenticatorSimple
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentLoginFlowStepAuthenticate
    - 当前状态: authenticated=true, !nestedStepsHandled
    - ReactTo: 返回 IntentLoginFlowSteps (处理嵌套步骤)
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentLoginFlowStepAuthenticate (下一个 step)
    - 检查 options，发现 primary_oob_otp_email
    - CanReactTo: 返回 InputSchemaAuthenticationMethod (包含 options)
        ↓
生成新 state_token
返回 FlowResponse
```

### 2.3 返回给客户端

```json
{
    "result": {
        "state_token": "authflowstate_Lb-_VWyRZJ_tFlNu5uIdj11VbBGSh3-w42dN8kKI_WWQ3rY9",
        "type": "login",
        "name": "email_password_primary_oob_otp_email",
        "action": {
            "type": "authenticate",
            "data": {
                "type": "authentication_data",
                "options": [
                    {
                        "authentication": "primary_oob_otp_email",
                        "otp_form": "code",
                        "channels": ["email"],
                        "masked_display_name": "u***@example.com"
                    }
                ]
            }
        }
    }
}
```

此时 Nodes 链：
```
Nodes[0]: NodeDoUseIdentity
Nodes[1]: NodeDoUseAuthenticatorPassword
Nodes[2]: NodeDoUseAuthenticatorSimple
Nodes[3]: (等待选择 OOB OTP)
```

---

## 第三阶段：AUTHENTICATE OOB OTP（选择并发送邮件）

### 3.1 关键问题：邮件何时发送？

**邮件不是在返回响应后发送，而是在 Accept 循环处理选择 OOB OTP 时立即发送。**

### 3.2 选择 OOB OTP

当客户端看到 `authentication_data` 包含 `primary_oob_otp_email` 选项后，需要再次提交请求选择该方式：

```bash
curl --location 'http://localhost:8080/api/v1/authentication_flows/states/input' \
--header 'Content-Type: application/json' \
--data '{
    "state_token": "authflowstate_Lb-_VWyRZJ_tFlNu5uIdj11VbBGSh3-w42dN8kKI_WWQ3rY9",
    "input": {
        "index": 0,
        "authentication": "primary_oob_otp_email"
    }
}'
```

### 3.3 服务端处理流程（邮件发送时机）

```
Service.FeedInput()
    ↓
GetFlowByStateToken()
    ↓
Accept 循环:
    - FindInputReactor: IntentLoginFlowStepAuthenticate
    - ReactTo:
        - case primary_oob_otp_email:
            return NewSubFlow(&IntentUseAuthenticatorOOBOTP{...})
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentUseAuthenticatorOOBOTP
    - CanReactTo: !authenticatorSelected
    - ReactTo:
        - pickAuthenticator() 获取或创建 authenticator
        - 返回 NodeDidSelectAuthenticator
    - appendNode: Nodes[4] = NodeDidSelectAuthenticator
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentUseAuthenticatorOOBOTP
    - CanReactTo: authenticatorSelected=true, !claimVerified
    - ReactTo: 返回 NewSubFlow(&IntentAuthenticationOOB{...})
        ↓
    Accept 循环继续:
    - FindInputReactor: IntentAuthenticationOOB
    - CanReactTo: 只有一个 channel (email)，返回 nil, nil
    - ReactTo: 调用 NewNodeAuthenticationOOB()
        ↓
        ┌─────────────────────────────────────────────────────────┐
        │           邮件发送核心逻辑                              │
        │                                                         │
        │  func NewNodeAuthenticationOOB(...) {                   │
        │      // 1. 生成 WebSocket 通道名                       │
        │      n.WebsocketChannelName = authflow.NewWebsocketChannelName()
        │                                                         │
        │      // 2. 立即生成 OTP Code                           │
        │      code, err := n.GenerateCode(ctx, deps)            │
        │      // 生成 6 位数字验证码                            │
        │                                                         │
        │      // 3. 返回带 DelayedOneTimeFunction 的节点        │
        │      return &NodeWithDelayedOneTimeFunction{             │
        │          Node: simpleNode,                              │
        │          DelayedOneTimeFunction: func(ctx, deps) error { │
        │              // 4. 异步发送邮件                        │
        │              return n.SendCode(ctx, deps, code)         │
        │          },                                             │
        │      }                                                  │
        │  }                                                      │
        │                                                         │
        │  注意：                                                 │
        │  - GenerateCode() 是同步调用，code 已生成              │
        │  - SendCode() 是 DelayedOneTimeFunction，异步执行      │
        │  - 邮件在 Accept 循环结束后通过 processAcceptResult() 发送│
        └─────────────────────────────────────────────────────────┘
        ↓
    - appendNode: Nodes[5] = NodeAuthenticationOOB
        ↓
    Accept 循环结束 (NodeAuthenticationOOB 等待 code 输入)
        ↓
Service.processAcceptResult()
    - 执行所有 DelayedOneTimeFunctions
    - 调用 SendCode() -> 发送邮件
        ↓
Service.CreateFlow() 持久化状态
    ↓
返回 FlowResponse
```

### 3.4 邮件发送时序详解

```
Accept 循环执行时序:

[t1] NewNodeAuthenticationOOB() 被调用
     - GenerateCode() 生成 "123456"
     - 返回 NodeWithDelayedOneTimeFunction

[t2] appendNode() 添加 NodeAuthenticationOOB

[t3] Accept 循环检测到 NodeAuthenticationOOB 等待输入
     循环结束

[t4] Service.processAcceptResult()
     - 执行 DelayedOneTimeFunction
     - SendCode() 被调用
     - 邮件实际发送

[t5] 返回 HTTP 响应给客户端
     - 客户端收到 verify_oob_otp_data
     - 此时邮件已在后台发送
```

### 3.5 返回给客户端（verify_oob_otp_data）

```json
{
    "result": {
        "state_token": "authflowstate_xxx...",
        "type": "login",
        "name": "email_password_primary_oob_otp_email",
        "action": {
            "type": "authenticate",
            "data": {
                "type": "verify_oob_otp_data",
                "channel": "email",
                "otp_form": "code",
                "masked_claim_value": "u***@example.com",
                "code_length": 6,
                "can_resend_at": "2026-05-05T01:06:00Z",
                "can_check": true,
                "failed_attempt_rate_limit_exceeded": false,
                "delivery_status": "delivering",
                "delivery_error": null,
                "websocket_url": "wss://example.com/_ws/authentication_flows?channel=ws_xxx"
            }
        }
    }
}
```

此时 Nodes 链：
```
Nodes[0]: NodeDoUseIdentity
Nodes[1]: NodeDoUseAuthenticatorPassword
Nodes[2]: NodeDoUseAuthenticatorSimple
Nodes[3]: NodeDidSelectAuthenticator (OOB OTP)
Nodes[4]: NodeAuthenticationOOB (等待 code 输入)
```

---

## 第四阶段：提交 OTP Code

### 4.1 请求处理入口

当客户端提交包含 `code` 的请求时：

```bash
curl --location 'http://localhost:8080/api/v1/authentication_flows/states/input' \
--header 'Content-Type: application/json' \
--data '{
    "state_token": "authflowstate_...",
    "input": {
        "code": "123456"
    }
}'
```

请求处理流程：

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────┐
│ HTTP Handler │────>│ Service.FeedInput │────>│ Accept 循环 │
└─────────────┘     └──────────────────┘     └─────────────┘
```

---

## 服务端如何记录下一步可处理的内容

### Flow 存储结构

服务端通过 `state_token` 在数据库中查找当前流程状态：

```go
// pkg/lib/authenticationflow/service.go:306-317
func (s *Service) FeedInput(ctx context.Context, stateToken string, rawMessage json.RawMessage) (output *ServiceOutput, err error) {
    // 1. 通过 state_token 查找 Flow
    flow, err := s.Store.GetFlowByStateToken(ctx, stateToken)
    if err != nil {
        return
    }
    
    // 2. 获取关联的 Session
    ctx, session, err := s.getSessionAndUpdateContext(ctx, flow.FlowID)
    if err != nil {
        return
    }
    
    // 3. 处理输入
    flow, flowAction, err = s.feedInput(ctx, session, stateToken, rawMessage)
    ...
}
```

### Flow 数据结构

Flow 包含 Intent（意图）和 Nodes（节点链）：

```go
// pkg/lib/authenticationflow/flow.go
type Flow struct {
    FlowID    string    `json:"flow_id"`     // 流程唯一标识
    StateToken string   `json:"state_token"` // 当前状态令牌
    Intent    Intent    `json:"intent"`      // 根意图（如 LoginFlow）
    Nodes     []Node    `json:"nodes"`       // 执行节点链
}
```

### Nodes 链记录执行状态

对于 `email_password_primary_oob_otp_email` 完整流程，Nodes 链如下：

```
Nodes[0]: NodeDoUseIdentity (已完成: email 识别)
Nodes[1]: NodeDoUseAuthenticatorPassword (已完成: 密码认证)
Nodes[2]: NodeDoUseAuthenticatorSimple (已完成: 密码验证 milestone)
Nodes[3]: NodeDidSelectAuthenticator (已完成: 选择 OOB OTP)
Nodes[4]: NodeAuthenticationOOB (当前: 等待 OTP code 输入)
```

NodeAuthenticationOOB 结构：

```go
// pkg/lib/authenticationflow/declarative/node_authn_oob.go:27-36
type NodeAuthenticationOOB struct {
    JSONPointer          jsonpointer.T                          // 配置位置
    UserID               string                                 // 用户ID
    Purpose              otp.Purpose                            // OOB OTP 用途
    Form                 otp.Form                               // code 或 link
    Info                 *authenticator.Info                    // 认证器信息
    Channel              model.AuthenticatorOOBChannel          // email/sms/whatsapp
    WebsocketChannelName string                                 // WebSocket 通道
    Authentication       model.AuthenticationFlowAuthentication // primary_oob_otp_email
}
```

### 如何知道可以处理 code

通过 `FindInputReactor` 查找当前可接收输入的节点：

```go
// pkg/lib/authenticationflow/service.go:624-626
func (s *Service) getFlowAction(ctx context.Context, session *Session, flow *Flow) (flowAction *FlowAction, err error) {
    findInputReactorResult, err := FindInputReactor(ctx, s.Deps, NewFlows(flow))
    // ...
}
```

`FindInputReactor` 遍历 Nodes 链，找到实现 `InputReactor` 接口且 `CanReactTo` 返回非 nil 的节点。

对于 `NodeAuthenticationOOB`：

```go
// pkg/lib/authenticationflow/declarative/node_authn_oob.go:76-87
func (n *NodeAuthenticationOOB) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 始终返回 InputSchema，表示可以接收输入
    return &InputSchemaNodeAuthenticationOOB{
        JSONPointer:    n.JSONPointer,
        FlowRootObject: flowRootObject,
        OTPForm:        n.Form,
    }, nil
}
```

---

## 提交 Code 后的处理流程

### 整体流程图

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ Client 提交 │────>│ Service.FeedInput │────>│ Database.ReadOnly │
│ code        │     │                  │     │ (事务)            │
└─────────────┘     └──────────────────┘     └──────────────────┘
                                                        │
                       ┌────────────────────────────────┘
                       ▼
              ┌─────────────────┐
              │ Accept 循环     │
              │ doAccept(ctx,   │
              │   deps, flows,  │
              │   result,       │
              │   inputFn)      │
              └─────────────────┘
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
  ┌────────────┐ ┌──────────┐ ┌──────────────┐
  │ FindInput  │ │ inputFn  │ │ ReactTo      │
  │ Reactor    │ │ (验证)   │ │ (执行业务)   │
  └────────────┘ └──────────┘ └──────────────┘
                                        │
                       ┌────────────────┼────────────────┐
                       ▼                ▼                ▼
              ┌─────────────┐  ┌──────────────┐  ┌─────────────┐
              │ IsCode()    │  │ IsResend()   │  │ IsCheck()   │
              │ 验证 OTP    │  │ 重发 OTP     │  │ 检查状态    │
              └─────────────┘  └──────────────┘  └─────────────┘
```

### Accept 循环详解

```go
// pkg/lib/authenticationflow/accept.go:107-268
func doAccept(ctx context.Context, deps *Dependencies, flows Flows, result *AcceptResult, inputFn func(inputSchema InputSchema) (Input, error)) (err error) {
    for {
        // 1. 查找可接收输入的 Reactor
        findInputReactorResult, err := FindInputReactor(ctx, deps, flows)
        
        // 2. 通过 InputSchema 验证和解析输入
        input, err := inputFn(findInputReactorResult.InputSchema)
        
        // 3. 调用 ReactTo 执行业务逻辑
        reactToResult, err := findInputReactorResult.InputReactor.ReactTo(ctx, deps, findInputReactorResult.Flows, input)
        
        // 4. 处理返回结果（添加新节点或替换节点）
        // ...
    }
}
```

### Input 验证流程

```go
// pkg/lib/authenticationflow/declarative/input_node_auth_oob.go:64-71
func (i *InputSchemaNodeAuthenticationOOB) MakeInput(ctx context.Context, rawMessage json.RawMessage) (authflow.Input, error) {
    var input InputNodeAuthenticationOOB
    // 使用 JSON Schema 验证输入
    err := i.SchemaBuilder().ToSimpleSchema().Validator().ParseJSONRawMessage(ctx, rawMessage, &input)
    if err != nil {
        return nil, err
    }
    return &input, nil
}
```

支持的输入类型（oneOf）：

```go
// 1. 提交 code
{"code": "123456", "request_device_token": true}

// 2. 重发 OTP
{"resend": true}

// 3. 检查状态（用于 link 形式）
{"check": true}
```

### OTP 验证逻辑

```go
// pkg/lib/authenticationflow/declarative/node_authn_oob.go:89-131
func (n *NodeAuthenticationOOB) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    switch {
    case inputNodeAuthenticationOOB.IsCode():
        code := inputNodeAuthenticationOOB.GetCode()
        claimName, claimValue := n.Info.OOBOTP.ToClaimPair()
        
        // 1. 创建验证 Spec
        authenticatorSpec := n.createAuthenticatorSpec(code)
        authenticators := []*authenticator.Info{n.Info}
        
        // 2. 调用 VerifyOneWithSpec 验证 OTP
        _, _, err := deps.Authenticators.VerifyOneWithSpec(ctx,
            n.UserID,
            n.Info.Type,
            authenticators,
            authenticatorSpec,
            &facade.VerifyOptions{
                AuthenticationDetails: facade.NewAuthenticationDetails(
                    n.UserID,
                    authn.AuthenticationStageFromAuthenticationMethod(n.Authentication),
                    authn.AuthenticationType(n.Info.Type),
                ),
                Form: n.Form,
            },
        )
        if apierrors.IsKind(err, otp.InvalidOTPCode) {
            return nil, n.invalidOTPCodeError()  // 返回无效验证码错误
        } else if err != nil {
            return nil, err
        }
        
        // 3. 验证成功，创建 VerifiedClaim
        verifiedClaim := deps.Verification.NewVerifiedClaim(ctx,
            n.UserID,
            string(claimName),
            claimValue,
        )
        verifiedClaim.SetVerifiedByChannel(n.Channel)
        
        // 4. 返回 NodeDoMarkClaimVerified 节点
        return authflow.NewNodeSimple(&NodeDoMarkClaimVerified{
            Claim: verifiedClaim,
        }), nil
    }
}
```

---

## 提交后的可能结果

### 验证成功

```
Accept 循环 -> ReactTo 成功 -> 返回 NodeDoMarkClaimVerified 
    -> appendNode -> changed = true -> 生成新的 state_token
    -> 继续循环直到 ErrEOF
    -> Service.FeedInput 返回 FlowAction (finished 或下一步)
```

### 验证失败

```go
// 返回 InvalidCredentials 错误
return nil, errorutil.WithDetails(api.ErrInvalidCredentials, errorutil.Details{
    "AuthenticationType": apierrors.APIErrorDetail.Value(authenticationType),
})
```

客户端会收到错误响应，可以再次提交 code。

### 重发 OTP

```go
case inputNodeAuthenticationOOB.IsResend():
    code, err := n.GenerateCode(ctx, deps)  // 生成新 code
    // 返回带 DelayedOneTimeFunction 的节点
    return &authflow.NodeWithDelayedOneTimeFunction{
        Node: newSimpleNode,
        DelayedOneTimeFunction: func(ctx context.Context, deps *authflow.Dependencies) error {
            return n.SendCode(ctx, deps, code)  // 异步发送邮件/SMS
        },
    }, authflow.ErrReplaceNode
```

---

## 流程完成

当所有认证步骤完成，Accept 循环返回 `ErrEOF`：

```go
// pkg/lib/authenticationflow/service.go:353-371
isEOF := errors.Is(err, ErrEOF)
if isEOF {
    // 1. 应用所有 effects（创建 authenticator、更新用户状态等）
    err = s.Database.WithTx(ctx, func(ctx context.Context) error {
        cookies, err = s.finishFlow(ctx, flow)
        return err
    })
    
    // 2. 删除 Session 和 Flow
    err = s.Store.DeleteSession(ctx, session)
    err = s.Store.DeleteFlow(ctx, flow)
    
    // 3. 返回 ErrEOF 表示流程结束
    err = ErrEOF
}
```

返回给客户端的响应：

```json
{
    "result": {
        "action": {
            "type": "finished",
            "data": {
                "finish_redirect_uri": "https://...",
                "authentication_info": {...}
            }
        }
    }
}
```

---

## 关键数据流总结

### 完整流程数据流

```
Client                    Server                      Database
  |                          |                           |
  |-- POST /auth ------------> |                           |
  |   {identification: email}  |                           |
  |                          |-- CreateSession --------->|
  |                          |-- CreateFlow ------------>|
  |<-- state_token_1 ---------|                           |
  |   + authenticate options |                           |
  |                          |                           |
  |-- POST /input -----------> |                           |
  |   {state_token_1,         |                           |
  |    authentication:        |                           |
  |    primary_password}      |                           |
  |                          |-- GetFlowByStateToken --->|
  |                          |<-- Flow (Nodes[0]) -------|
  |                          |                           |
  |                          |-- Accept 循环 (密码验证) --|
  |                          |-- CreateFlow ------------>|
  |<-- state_token_2 ---------|                           |
  |   + OOB options          |                           |
  |                          |                           |
  |-- POST /input -----------> |                           |
  |   {state_token_2,         |                           |
  |    authentication:        |                           |
  |    primary_oob_otp_email} |                           |
  |                          |-- GetFlowByStateToken --->|
  |                          |<-- Flow (Nodes[0..2]) ----|
  |                          |                           |
  |                          |-- Accept 循环 (选择OOB) ---|
  |                          |   NewNodeAuthenticationOOB|
  |                          |   GenerateCode()          |
  |                          |   CreateFlow ------------>|
  |                          |   processAcceptResult()   |
  |                          |   SendCode() ----------> Mail/SMS
  |<-- state_token_3 ---------|                           |
  |   + verify_oob_otp_data |                           |
  |   (邮件已发送)            |                           |
  |                          |                           |
  |-- POST /input -----------> |                           |
  |   {state_token_3,         |                           |
  |    code: "123456"}        |                           |
  |                          |-- GetFlowByStateToken --->|
  |                          |<-- Flow (Nodes[0..4]) ----|
  |                          |                           |
  |                          |-- Accept 循环 (验证code) ---|
  |                          |   VerifyOneWithSpec()     |
  |                          |   成功 -> ErrEOF           |
  |                          |                           |
  |                          |-- WithTx (finishFlow) ----|
  |                          |   DeleteSession -------->|
  |                          |   DeleteFlow ----------->|
  |<-- finished -------------|                           |
```

### 邮件发送时机总结

| 时机 | 位置 | 说明 |
|------|------|------|
| Code 生成 | `NewNodeAuthenticationOOB()` | 同步调用 `GenerateCode()` |
| 邮件发送 | `processAcceptResult()` | Accept 循环结束后异步调用 `SendCode()` |
| 客户端感知 | `verify_oob_otp_data` | 返回时邮件已在发送中/已发送 |

### 状态流转

```
[开始]
  ↓
[IDENTIFY] --提交 email--> [authflowstate_1] --返回--> [authenticate: password]
  ↓
[AUTHENTICATE password] --提交密码--> [authflowstate_2] --返回--> [authenticate: OOB options]
  ↓
[AUTHENTICATE OOB] --选择 OOB--> [authflowstate_3] --返回--> [verify_oob_otp_data]
  ↓ (邮件发送)
[等待 OTP] --提交 code--> 
  ├-- 错误 --> [authflowstate_3] (保持，可重试)
  └-- 正确 --> [finished]
  ↓
[结束]
```
