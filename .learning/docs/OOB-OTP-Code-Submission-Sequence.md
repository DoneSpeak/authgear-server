# OOB OTP Code 提交时序图

## Accept 循环设计说明

### 为什么需要循环？

Accept 循环是 Authgear Flow 的核心执行机制。单次请求处理单个 input 看似足够，但以下场景需要循环：

1. **自动推进（Nil Input）**：创建 Flow 时传入 nil input，系统自动推进到第一个需要用户输入的状态
2. **连锁反应**：一个 input 可能触发多个自动步骤（如 CREATE_IDENTITY → USER_PROFILE）
3. **SubFlow 展开**：创建 SubFlow 后需要立即处理其内部节点

### 为什么优先检查最后一个 Node？

```
Nodes 链: [A, B, C, D]
                    ^
                    最新 Node，代表当前活跃状态
```

- Node A/B/C 已完成（返回 ErrEOF）
- Node D 是当前活跃状态，优先检查
- 采用**栈式执行模型**，深层 SubFlow 优先

### 循环终止条件

- `ErrIncompatibleInput`：无法处理当前 input
- `ErrSameNode`：节点无限循环自我处理
- `ErrReplaceNode`：节点被替换
- 其他错误
- 无更多可处理的节点

---

## 1. 正常提交流程（验证成功）

```mermaid
sequenceDiagram
    participant C as Client
    participant H as HTTP Handler
    participant S as Service
    participant DB as Database
    participant A as Accept Loop
    participant N as NodeAuthenticationOOB
    participant V as OTP Verifier

    C->>H: POST /api/v1/authentication_flows/states/input
    C->>H: {state_token, input: {code: "123456"}}
    
    H->>S: FeedInput(ctx, stateToken, rawMessage)
    
    S->>DB: GetFlowByStateToken(stateToken)
    DB-->>S: Flow (包含 Nodes 链)
    
    S->>DB: GetSession(flow.FlowID)
    DB-->>S: Session
    
    S->>S: feedInput(ctx, session, stateToken, rawMessage)
    
    loop Accept 循环
        S->>DB: ReadOnly 事务开始
        
        S->>S: ApplyRunEffects(flows)
        
        S->>A: Accept(ctx, deps, flows, result, rawMessage)
        
        A->>A: FindInputReactor(flows)
        Note over A: 找到 NodeAuthenticationOOB
        
        A->>A: inputSchema.MakeInput(rawMessage)
        Note over A: 解析并验证 {code: "123456"}
        
        A->>N: ReactTo(ctx, deps, flows, input)
        
        N->>N: IsCode() = true
        N->>N: GetCode() = "123456"
        
        N->>V: VerifyOneWithSpec(userID, type, authenticators, spec)
        Note over V: 验证 OTP 是否正确
        V-->>N: 验证成功
        
        N->>N: createAuthenticatorSpec(code)
        
        N->>N: NewNodeSimple(&NodeDoMarkClaimVerified{Claim})
        N-->>A: 返回新节点
        
        A->>A: appendNode(ctx, deps, flows, nextNode)
        A->>A: changed = true
        A->>A: flows.Nearest.StateToken = newStateToken()
        
        A-->>S: 返回 ErrNoChange (无更多输入)
        
        S->>DB: ReadOnly 事务结束
    end
    
    S->>DB: CreateFlow(ctx, flow) 持久化新状态
    
    S->>S: getFlowAction(ctx, session, flow)
    Note over S: 检查是否完成 (ErrEOF?)
    
    alt 还有后续步骤
        S-->>H: FlowAction{type: "authenticate", data: {...}}
        H-->>C: {state_token: "新token", action: {...}}
    else 所有步骤完成
        S->>DB: WithTx 开始
        S->>S: finishFlow(flow) 应用 effects
        S->>DB: DeleteSession(session)
        S->>DB: DeleteFlow(flow)
        S->>DB: WithTx 结束
        S-->>H: FlowAction{type: "finished", data: {...}}
        H-->>C: {action: {type: "finished", data: {...}}}
    end
```

## 2. 验证失败流程

```mermaid
sequenceDiagram
    participant C as Client
    participant H as HTTP Handler
    participant S as Service
    participant DB as Database
    participant A as Accept Loop
    participant N as NodeAuthenticationOOB
    participant V as OTP Verifier

    C->>H: POST /input with wrong code
    
    H->>S: FeedInput(...)
    S->>DB: GetFlowByStateToken
    DB-->>S: Flow
    
    S->>DB: ReadOnly 事务
    
    S->>A: Accept(...)
    A->>A: FindInputReactor
    A->>A: MakeInput (验证通过)
    A->>N: ReactTo(ctx, deps, flows, input)
    
    N->>V: VerifyOneWithSpec(...)
    Note over V: 验证 OTP: 123456
    V-->>N: 返回 otp.InvalidOTPCode 错误
    
    N->>N: invalidOTPCodeError()
    N-->>A: 返回 api.ErrInvalidCredentials
    
    A->>A: newAuthenticationFlowError(flows, err)
    A-->>S: 返回错误
    
    S->>DB: ReadOnly 事务结束
    S-->>H: 错误 (无状态变更)
    H-->>C: 400 Bad Request
    Note over C: {error: {name: "InvalidCredentials", ...}}
    
    Note over C: 客户端可以重试
```

## 3. 重发 OTP 流程

```mermaid
sequenceDiagram
    participant C as Client
    participant H as HTTP Handler
    participant S as Service
    participant DB as Database
    participant A as Accept Loop
    participant N as NodeAuthenticationOOB
    participant O as OTPCodes
    participant M as Mail/SMS Sender

    C->>H: POST /input with {resend: true}
    
    H->>S: FeedInput(...)
    S->>DB: GetFlowByStateToken
    DB-->>S: Flow
    
    S->>DB: ReadOnly 事务
    
    S->>A: Accept(...)
    A->>A: FindInputReactor
    A->>A: MakeInput
    Note over A: 解析 {resend: true}
    
    A->>N: ReactTo(ctx, deps, flows, input)
    
    N->>N: IsResend() = true
    N->>O: GenerateCode(ctx, kind, claimValue, form)
    O-->>N: 新 code: 654321
    
    N->>N: NewNodeSimple(n)
    N-->>A: 返回 NodeWithDelayedOneTimeFunction
    Note over A: 标记 ErrReplaceNode
    
    A->>A: 替换最后一个节点
    A->>A: changed = true
    A-->>S: ErrReplaceNode (处理后为 nil)
    
    S->>DB: ReadOnly 事务结束
    S->>S: processAcceptResult(...)
    
    S->>M: DelayedOneTimeFunction: SendCode(code)
    Note over M: 异步发送邮件/SMS
    M-->>S: 发送完成
    
    S->>DB: CreateFlow(ctx, flow)
    
    S-->>H: FlowAction{type: "authenticate", data: verify_oob_otp_data}
    H-->>C: {state_token, action: {type: "authenticate", data: {...}}}
    
    Note over C: 客户端收到新的 CanResendAt 时间
```

## 4. 状态流转图

```mermaid
stateDiagram-v2
    [*] --> FlowCreated: 创建流程
    FlowCreated --> PasswordAuthenticated: 提交密码
    PasswordAuthenticated --> OOBSelected: 选择 OOB OTP
    OOBSelected --> WaitingForCode: 发送 OTP
    
    WaitingForCode --> WaitingForCode: 重发 OTP
    WaitingForCode --> CodeVerified: 提交正确 code
    WaitingForCode --> WaitingForCode: 提交错误 code
    
    CodeVerified --> Finished: 所有步骤完成
    CodeVerified --> NextStep: 还有后续步骤
    
    NextStep --> [*]: 返回新 state_token
    Finished --> [*]: 返回 finished
    
    note right of WaitingForCode
        NodeAuthenticationOOB 存储在 Nodes[2]
        StateToken 已更新
        CanReactTo 返回 InputSchema
    end note
    
    note right of CodeVerified
        ReactTo 返回 NodeDoMarkClaimVerified
        Accept 循环追加新节点
        changed = true
        生成新 StateToken
    end note
```

## 5. 数据存储结构

```
┌─────────────────────────────────────────────────────────────┐
│                        Flow 表                               │
├─────────────────────────────────────────────────────────────┤
│ flow_id:       "flow_abc123"                                 │
│ state_token:   "authflowstate_2IrRI8IB3ud..."                │
│ intent:        IntentLoginFlow                               │
│                                                                 │
│ nodes: [                                                      │
│   {                                                          │
│     type: "NodeSimple",                                       │
│     simple: {                                                │
│       kind: "NodeDoUseAuthenticatorPassword",               │
│       user_id: "user_123"                                    │
│     }                                                        │
│   },                                                         │
│   {                                                          │
│     type: "NodeSimple",                                       │
│     simple: {                                                │
│       kind: "NodeDidSelectAuthenticator",                  │
│       authenticator_id: "authn_oob_email_xxx"               │
│     }                                                        │
│   },                                                         │
│   {                                                          │
│     type: "NodeSimple",                                       │
│     simple: {                                                │
│       kind: "NodeAuthenticationOOB",                       │
│       user_id: "user_123",                                   │
│       channel: "email",                                      │
│       info: {                                                │
│         oob_otp: {                                           │
│           email: "user@example.com"                          │
│         }                                                    │
│       },                                                     │
│       websocket_channel_name: "ws_xxx"                     │
│     }                                                        │
│   }                                                          │
│ ]                                                            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                      Session 表                              │
├─────────────────────────────────────────────────────────────┤
│ flow_id:       "flow_abc123"                                │
│ user_id:       "user_123" (可选，登录后设置)                   │
│ redirect_uri:  "https://app.example.com/callback"           │
│ ...                                                          │
└─────────────────────────────────────────────────────────────┘
```

## 6. Input Schema 验证流程

```
rawMessage (JSON)
    ↓
InputSchemaNodeAuthenticationOOB.MakeInput()
    ↓
SchemaBuilder().ToSimpleSchema().Validator().ParseJSONRawMessage()
    ↓
JSON Schema 验证 (oneOf)
    ├─ {"resend": true}           → IsResend() = true
    ├─ {"code": "string"}          → IsCode() = true
    └─ {"check": true}            → IsCheck() = true (仅 link 形式)
    ↓
InputNodeAuthenticationOOB
    ↓
ReactTo(ctx, deps, flows, input)
```

## 7. OTP 验证内部调用链

```
NodeAuthenticationOOB.ReactTo()
    ↓
VerifyOneWithSpec(
    userID: "user_123",
    authenticatorType: "oob_otp_email",
    authenticators: [...],
    spec: {
        oob_otp: {
            code: "123456",
            email: "user@example.com"
        }
    },
    options: {
        authentication_details: {...},
        form: "code"
    }
)
    ↓
OTPCodeService.VerifyCode() / TOTP.Verify()
    ↓
返回: (authenticator.Info, true, nil) 或错误
```
