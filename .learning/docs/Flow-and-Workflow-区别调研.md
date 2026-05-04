# Flow 与 Workflow 区别调研

## 概述

Authgear 中存在两个相似但用途不同的概念：
- **Flow** (`pkg/lib/authenticationflow`) - 专门用于认证流程（登录、注册、找回密码等）
- **Workflow** (`pkg/lib/workflow`) - 通用工作流引擎，作为底层执行框架

## 核心区别

| 维度 | Flow | Workflow |
|------|------|----------|
| **位置** | `pkg/lib/authenticationflow/workflow.go` | `pkg/lib/workflow/workflow.go` |
| **用途** | 面向认证业务的专用流程 | 通用工作流执行引擎 |
| **Intent类型** | 使用 `authflow.Intent` | 使用 `workflow.Intent` |
| **嵌套机制** | SubFlow (子流程) | SubWorkflow (子工作流) |
| **状态追踪** | StateToken | WorkflowID + InstanceID |
| **使用方** | authenticationflow 包 | workflow 包 (被其他模块复用) |

### 数据结构对比

```go
// Flow - 认证流程
 type Flow struct {
    FlowID     string  // 流程唯一ID
    StateToken string  // 状态令牌，用于追踪流程状态
    Intent     Intent  // 流程意图（如登录、注册）
    Nodes      []Node  // 流程节点
}

// Workflow - 通用工作流
type Workflow struct {
    WorkflowID string  // 工作流ID
    InstanceID string  // 实例ID，每次变更会更新
    Intent     Intent  // 工作流意图
    Nodes      []Node  // 工作流节点
}
```

## 作用范围

### Flow 的作用范围

1. **专门处理认证场景**：
   - 登录 (Login Flow)
   - 注册 (Signup Flow)
   - 找回密码 (Forgot Password Flow)
   - 账户恢复 (Account Recovery Flow)

2. **业务级封装**：
   - 包含 OAuth/SAML 会话管理
   - 支持 Bot Protection 验证
   - 集成 UI 参数解析（语言、客户端等）

3. **与配置绑定**：
   ```yaml
   # 配置示例
   - name: default_login_flow
     type: login
     steps:
       - type: identify
       - type: authenticate
   ```

### Workflow 的作用范围

1. **通用执行引擎**：
   - 可被任何业务模块使用
   - 支持子工作流嵌套 (`NodeTypeSubWorkflow`)
   - 提供标准化的 Accept/ReactTo 机制

2. **底层机制**：
   - Intent 注册与实例化
   - Node 遍历与执行
   - Effect 处理（RunEffect, OnCommitEffect）

## 生命周期

### Flow 的生命周期

```
创建 → 执行 → 完成/删除
```

#### 1. 创建（何时触发，如何创建）

**触发时机**：
- 用户发起登录/注册请求
- API 调用 `Service.CreateNewFlow()`

**创建过程**（代码位置：`pkg/lib/authenticationflow/service.go:83-103`）：
```go
func (s *Service) CreateNewFlow(ctx context.Context, publicFlow PublicFlow, sessionOptions *SessionOptions) (output *ServiceOutput, err error) {
    // 1. 验证流程配置
    err = s.validateNewFlow(publicFlow, sessionOptions)
    
    // 2. 创建 Session（包含 FlowID、OAuth 信息等）
    session := NewSession(sessionOptions)
    ctx = session.MakeContext(ctx, s.Deps)
    
    // 3. 存储 Session 到 Redis
    err = s.Store.CreateSession(ctx, session)
    
    // 4. 创建 Flow（通过 createNewFlow）
    return s.createNewFlowWithSession(ctx, publicFlow, session)
}
```

**创建 Flow**（代码位置：`pkg/lib/authenticationflow/service.go:201-261`）：
```go
func (s *Service) createNewFlow(ctx context.Context, session *Session, publicFlow PublicFlow) (flow *Flow, flowAction *FlowAction, err error) {
    // 1. 创建 Flow 实例
    flow = NewFlow(session.FlowID, publicFlow)
    
    // 2. 执行初始 Accept（推进流程到第一个等待输入的点）
    for shouldAccept {
        flows := NewFlows(flow)
        err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
    }
    
    // 3. 存储 Flow 到 Redis
    err = s.Store.CreateFlow(ctx, flow)
}
```

#### 2. 更新（什么场景会更新，如何更新）

**更新场景**：
- 用户提交输入（如输入邮箱、密码）
- 切换流程步骤（从 identify 到 authenticate）
- Bot Protection 验证结果更新

**更新过程**（代码位置：`pkg/lib/authenticationflow/service.go:306-387`）：
```go
func (s *Service) FeedInput(ctx context.Context, stateToken string, rawMessage json.RawMessage) (output *ServiceOutput, err error) {
    // 1. 根据 StateToken 获取 Flow
    flow, err := s.Store.GetFlowByStateToken(ctx, stateToken)
    
    // 2. 获取 Session 并更新 Context
    ctx, session, err := s.getSessionAndUpdateContext(ctx, flow.FlowID)
    
    // 3. 执行 feedInput 处理输入
    flow, flowAction, err = s.feedInput(ctx, session, stateToken, rawMessage)
    // ...
}

func (s *Service) feedInput(ctx context.Context, session *Session, stateToken string, rawMessage json.RawMessage) (flow *Flow, flowAction *FlowAction, err error) {
    // 1. 获取 Flow
    flow, err = s.Store.GetFlowByStateToken(ctx, stateToken)
    
    // 2. 循环 Accept 直到不需要更多输入
    for shouldAccept {
        flows := NewFlows(flow)
        
        // 3. 执行 RunEffects
        err = ApplyRunEffects(ctx, s.Deps, flows)
        
        // 4. 接受输入并推进流程
        err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
        
        // 5. 如果有变更，StateToken 会更新（见 accept.go:112）
    }
    
    // 6. 重新存储 Flow（因为 StateToken 可能已更新）
    err = s.Store.CreateFlow(ctx, flow)
}
```

**StateToken 更新机制**（代码位置：`pkg/lib/authenticationflow/accept.go:107-117`）：
```go
func doAccept(...) (err error) {
    defer func() {
        if changed {
            // 每次流程变化都会生成新的 StateToken
            flows.Nearest.StateToken = newStateToken()
        }
    }()
    // ...
}
```

#### 3. 删除（什么时候删除，如何删除）

**删除时机**：
- 流程正常完成（返回 `ErrEOF`）
- 流程超时（Redis TTL 过期）

**正常完成删除**（代码位置：`pkg/lib/authenticationflow/service.go:353-370`）：
```go
if isEOF {
    // 1. 在事务中完成流程（应用所有 Effects）
    err = s.Database.WithTx(ctx, func(ctx context.Context) error {
        cookies, err = s.finishFlow(ctx, flow)
        return err
    })
    
    // 2. 删除 Session
    err = s.Store.DeleteSession(ctx, session)
    
    // 3. 删除 Flow
    err = s.Store.DeleteFlow(ctx, flow)
}
```

**Redis 删除实现**（代码位置：`pkg/lib/authenticationflow/store.go:80-92`）：
```go
func (s *StoreImpl) DeleteFlow(ctx context.Context, flow *Flow) error {
    // 只删除 flowID key，state key 保留（会有很多历史 state）
    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err := conn.Del(ctx, flowKey).Result()
        return err
    })
}
```

### Workflow 的生命周期

Workflow 的生命周期由使用它的上层模块决定。在 authenticationflow 中，Workflow 作为 Node 存在：

```go
// Node 可以是 SubWorkflow
type Node struct {
    Type        NodeType   // NodeTypeSimple 或 NodeTypeSubWorkflow
    Simple      NodeSimple
    SubWorkflow *Workflow  // 嵌套的 Workflow
}
```

Workflow 本身不提供持久化，它的状态存储在父级 Flow 中。

## 生命周期长度

### Flow 的生命周期长度

1. **整个认证流程**：从用户开始登录到登录完成
2. **TTL 配置**：`duration.UserInteraction`（代码位置：`pkg/lib/authenticationflow/store.go:17`）
3. **Redis 存储**：
   ```go
   const Lifetime = duration.UserInteraction
   // 通常是数分钟到数小时，取决于配置
   ```

### Session 的生命周期

Session 与 Flow 共享相同的生命周期：
- 创建：与 Flow 同时创建
- 更新：Bot Protection 验证结果等
- 删除：流程完成时删除

### Workflow 的生命周期

Workflow 的生命周期取决于它作为 Node 被使用的方式：
- 如果是 `NodeTypeSubWorkflow`，生命周期与父 Flow 相同
- 如果是独立使用（如 latte 模块），由上层模块管理

## 存储方式

### Flow 的存储

**Redis 存储**（代码位置：`pkg/lib/authenticationflow/store.go`）：

```go
// Key 格式
app:{appID}:authenticationflow_flow:{flowID}      // Flow 存在性标记
app:{appID}:authenticationflow_state:{stateToken}   // Flow 状态数据
app:{appID}:authenticationflow_session:{flowID}     // Session 数据
```

**存储结构**：
```go
// Flow 存储（JSON 序列化）
type Flow struct {
    FlowID     string
    StateToken string
    Intent     Intent  // 序列化为 Kind + Data
    Nodes      []Node  // 序列化为 Type + Simple/SubFlow
}

// Session 存储（JSON 序列化）
type Session struct {
    FlowID         string
    OAuthSessionID string
    ClientID       string
    RedirectURI    string
    // ... 其他 OAuth/SAML 相关字段
}
```

### Workflow 的存储

Workflow 本身不提供独立的存储机制，但在 workflow 包中有类似的存储实现：

```go
// pkg/lib/workflow/store.go
const Lifetime = duration.UserInteraction

func (s *StoreImpl) CreateWorkflow(ctx context.Context, workflow *Workflow) error
func (s *StoreImpl) GetWorkflowByInstanceID(ctx context.Context, instanceID string) (*Workflow, error)
```

**注意**：在 authenticationflow 中，Workflow 作为 Node 的一部分存储在 Flow 的 JSON 中。

## 关键类

### Flow 相关关键类

| 类 | 文件 | 职责 |
|---|------|------|
| `Service` | `service.go` | Flow 生命周期管理（创建、获取、输入处理） |
| `Store` / `StoreImpl` | `store.go` | Redis 存储操作 |
| `Flow` | `workflow.go` | Flow 数据结构 |
| `Session` | `session.go` | 认证会话管理 |
| `Accept` / `AcceptResult` | `accept.go` | 输入处理与流程推进 |
| `Flows` | `workflows.go` | Flow 上下文容器（Root/Nearest） |
| `Intent` 接口 | 各 Intent 文件 | 流程意图实现（如 IntentLoginFlow） |

### Workflow 相关关键类

| 类 | 文件 | 职责 |
|---|------|------|
| `Workflow` | `workflow.go` | Workflow 数据结构 |
| `Workflows` | `workflow.go` | Workflow 上下文容器 |
| `Node` / `NodeSimple` | `node.go` | 工作流节点 |
| `Intent` 接口 | `intent.go` | 工作流意图接口 |
| `InputReactor` | `input.go` | 输入响应接口 |
| `Effect` | `effect.go` | 副作用接口 |

## 案例：Login 流程的执行过程

### 配置示例

```yaml
- name: default_login_flow
  type: login
  steps:
    - type: identify
      one_of:
        - identification: email
        - identification: phone
        - identification: username
    - type: authenticate
      one_of:
        - authentication: primary_password
    - type: authenticate
      one_of:
        - authentication: secondary_totp
        - authentication: secondary_oob_otp_sms
```

### 场景 1：创建 Login Flow

**触发**：用户访问登录页面，前端调用 API 创建流程

**关键代码**（`pkg/lib/authenticationflow/service.go:83-103`）：
```go
// 1. 创建 Session
session := NewSession(sessionOptions)  // 生成 FlowID
ctx = session.MakeContext(ctx, s.Deps)

// 2. 存储 Session
err = s.Store.CreateSession(ctx, session)

// 3. 创建 Flow
flow = NewFlow(session.FlowID, publicFlow)  // publicFlow 是 IntentLoginFlow
// 初始 StateToken 生成：newStateToken()

// 4. 初始 Accept 推进流程
for shouldAccept {
    flows := NewFlows(flow)
    err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
    // 流程推进到第一个等待输入的点（identify 步骤）
}

// 5. 存储 Flow
createFlow(ctx, flow)  // 写入 Redis
```

**创建的 Redis 数据**：
```
app:{appID}:authenticationflow_flow:{flowID} -> "app:{appID}:authenticationflow_flow:{flowID}" (TTL)
app:{appID}:authenticationflow_state:{stateToken} -> {Flow JSON} (TTL)
app:{appID}:authenticationflow_session:{flowID} -> {Session JSON} (TTL)
```

### 场景 2：用户输入邮箱进行 Identify

**触发**：用户输入邮箱并提交

**关键代码**（`pkg/lib/authenticationflow/service.go:306-387`）：
```go
// 1. 根据 StateToken 获取 Flow
flow, err := s.Store.GetFlowByStateToken(ctx, stateToken)

// 2. 执行 feedInput
flow, flowAction, err = s.feedInput(ctx, session, stateToken, rawMessage)

// 在 feedInput 内部：
for shouldAccept {
    flows := NewFlows(flow)
    
    // 3. 应用 RunEffects（如记录日志）
    err = ApplyRunEffects(ctx, s.Deps, flows)
    
    // 4. 接受输入
    err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
    // -> 调用 IntentLoginFlowSteps.ReactTo()
    // -> 创建 IntentLoginFlowStepIdentify 子流程
    // -> 创建 NodeIdentify（记录用户选择）
}

// 5. 存储更新后的 Flow（StateToken 已更新）
err = s.Store.CreateFlow(ctx, flow)
```

**IntentLoginFlowSteps.ReactTo**（`pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go:53-130`）：
```go
func (i *IntentLoginFlowSteps) ReactTo(...) (authflow.ReactToResult, error) {
    step := steps[i.NextStepIndex].(*config.AuthenticationFlowLoginFlowStep)
    
    switch step.Type {
    case config.AuthenticationFlowLoginFlowStepTypeIdentify:
        // 创建 Identify 步骤的 Intent
        stepIdentify, err := NewIntentLoginFlowStepIdentify(...)
        // 返回 SubFlow
        result = authflow.NewSubFlow(stepIdentify)
    }
    
    i.NextStepIndex = i.NextStepIndex + 1
    return result, nil
}
```

**更新后的 Flow 结构**：
```json
{
  "flow_id": "flow_xxx",
  "state_token": "new_state_token_xxx",  // 已更新
  "intent": {"kind": "IntentLoginFlow", "data": {...}},
  "nodes": [
    {"type": "SIMPLE", "simple": {"kind": "NodePreInitialize", ...}},
    {"type": "SUB_FLOW", "sub_flow": {
      "intent": {"kind": "IntentLoginFlowSteps", ...},
      "nodes": [
        {"type": "SUB_FLOW", "sub_flow": {
          "intent": {"kind": "IntentLoginFlowStepIdentify", ...},
          "nodes": [
            {"type": "SIMPLE", "simple": {"kind": "NodeIdentify", "identification": "email"}}
          ]
        }}
      ]
    }}
  ]
}
```

### 场景 3：用户输入密码进行 Primary Authentication

**触发**：用户已输入邮箱完成 Identify，现在输入密码并提交

**当前状态（场景 2 结束后）**：
- StateToken: `state_token_step2_xxx`
- 当前在 `IntentLoginFlowStepIdentify` 子流程，已完成 Identify
- 下一步需要进入 `authenticate` 步骤（primary_password）

**执行流程**（`pkg/lib/authenticationflow/service.go:306-387`）：
```go
// 1. 根据场景 2 的 StateToken 获取 Flow
flow, err := s.Store.GetFlowByStateToken(ctx, "state_token_step2_xxx")

// 2. 执行 feedInput 处理密码输入
flow, flowAction, err = s.feedInput(ctx, session, stateToken, rawMessage)
// rawMessage 包含: {"password": "user_password", "index": 0}

// 在 feedInput 内部循环：
for shouldAccept {
    flows := NewFlows(flow)
    
    // 3. 应用 RunEffects
    err = ApplyRunEffects(ctx, s.Deps, flows)
    
    // 4. 接受输入并推进流程
    err = Accept(ctx, s.Deps, flows, acceptResult, rawMessage)
    // -> 调用 IntentLoginFlowSteps.ReactTo() (NextStepIndex=1)
    // -> 检测到 authenticate 步骤
    // -> 创建 IntentLoginFlowStepAuthenticate 子流程
}

// 5. 存储更新后的 Flow（StateToken 已更新为 state_token_step3_xxx）
err = s.Store.CreateFlow(ctx, flow)
```

**IntentLoginFlowSteps.ReactTo 进入 Authenticate 步骤**（`pkg/lib/authenticationflow/declarative/intent_login_flow_steps.go:81-92`）：
```go
case config.AuthenticationFlowLoginFlowStepTypeAuthenticate:
    stepAuthenticate, err := NewIntentLoginFlowStepAuthenticate(ctx, deps, flows, &IntentLoginFlowStepAuthenticate{
        FlowReference: i.FlowReference,
        StepName:      step.Name,
        JSONPointer:   authflow.JSONPointerForStep(i.JSONPointer, i.NextStepIndex),
        UserID:        i.userID(flows),  // 从 Identify 节点获取的 user_id
    }, i)
    result = authflow.NewSubFlow(stepAuthenticate)
```

**IntentLoginFlowStepAuthenticate 处理密码认证**（`pkg/lib/authenticationflow/declarative/intent_login_flow_step_authenticate.go:203-328`）：
```go
func (i *IntentLoginFlowStepAuthenticate) ReactTo(...) (authflow.ReactToResult, error) {
    switch {
    case !authenticationMethodSelected:
        // 用户选择 primary_password
        if inputTakeAuthenticationMethod.GetAuthenticationMethod() == 
           model.AuthenticationFlowAuthenticationPrimaryPassword {
            
            return authflow.NewSubFlow(&IntentUseAuthenticatorPassword{
                JSONPointer:    authflow.JSONPointerForOneOf(i.JSONPointer, idx),
                UserID:         i.UserID,
                Authentication: authentication,
            }), nil
        }
    }
}
```

**IntentUseAuthenticatorPassword 验证密码**（`pkg/lib/authenticationflow/declarative/intent_use_authenticator_password.go:70-127`）：
```go
func (i *IntentUseAuthenticatorPassword) ReactTo(...) (authflow.ReactToResult, error) {
    // 1. 获取用户的密码认证器列表
    as, err := deps.Authenticators.List(ctx, i.UserID, 
        authenticator.KeepKind(model.AuthenticatorKindPrimary),
        authenticator.KeepType(model.AuthenticatorTypePassword))
    
    // 2. 构建密码 Spec
    spec := &authenticator.Spec{
        Password: &authenticator.PasswordSpec{
            PlainPassword: inputTakePassword.GetPassword(),
        },
    }
    
    // 3. 验证密码
    info, verifyResult, err := deps.Authenticators.VerifyOneWithSpec(ctx,
        i.UserID, model.AuthenticatorTypePassword, as, spec, verifyOptions)
    
    // 4. 创建认证节点
    return authflow.NewNodeSimple(&NodeDoUseAuthenticatorPassword{
        Authenticator:          info,           // 认证器信息
        PasswordChangeRequired: verifyResult.Password.RequireUpdate(),
        PasswordChangeReason:   reason,
        JSONPointer:            i.JSONPointer,
    }), nil
}
```

**更新后的 Flow 结构**：
```json
{
  "flow_id": "flow_abc123",
  "state_token": "state_token_step3_xxx",
  "intent": {"kind": "IntentLoginFlow", "data": {"target_user_id": "", ...}},
  "nodes": [
    {"type": "SIMPLE", "simple": {"kind": "NodePreInitialize", ...}},
    {"type": "SUB_FLOW", "sub_flow": {
      "intent": {"kind": "IntentLoginFlowSteps", "data": {"next_step_index": 2}},
      "nodes": [
        // Step 1: Identify (已完成)
        {"type": "SUB_FLOW", "sub_flow": {
          "intent": {"kind": "IntentLoginFlowStepIdentify", ...},
          "nodes": [
            {"type": "SIMPLE", "simple": {"kind": "NodeIdentify", "identification": "email", "user_id": "user_xyz"}}
          ]
        }},
        // Step 2: Authenticate (Primary Password, 已完成)
        {"type": "SUB_FLOW", "sub_flow": {
          "intent": {"kind": "IntentLoginFlowStepAuthenticate", 
            "data": {
              "step_name": "step2",
              "user_id": "user_xyz",
              "options": [{"authentication": "primary_password"}],
              "did_authenticated_before_this_step": false
            }
          },
          "nodes": [
            // 选择认证方式
            {"type": "SIMPLE", "simple": {"kind": "NodeAuthenticationMethod", "authentication": "primary_password"}},
            // 密码验证子流程
            {"type": "SUB_FLOW", "sub_flow": {
              "intent": {"kind": "IntentUseAuthenticatorPassword", ...},
              "nodes": [
                {"type": "SIMPLE", "simple": {
                  "kind": "NodeDoUseAuthenticatorPassword",
                  "authenticator": {
                    "id": "authn_pwd_001",
                    "type": "password",
                    "user_id": "user_xyz"
                  },
                  "password_change_required": false,
                  "json_pointer": "/steps/1/oneOf/0"
                }}
              ]
            }}
          ]
        }}
      ]
    }}
  ]
}
```

**Redis 存储内容（场景 3 结束后）**：

```bash
# 1. Flow 存在性 Key（TTL 剩余）
GET app:myapp:authenticationflow_flow:flow_abc123
# "app:myapp:authenticationflow_flow:flow_abc123"
TTL app:myapp:authenticationflow_flow:flow_abc123
# (integer) 3585  # 约1小时，duration.UserInteraction

# 2. State Token Key（已更新为新 token）
GET app:myapp:authenticationflow_state:state_token_step3_xxx
```

```json
{
  "flow_id": "flow_abc123",
  "state_token": "state_token_step3_xxx",
  "intent": {
    "kind": "IntentLoginFlow",
    "data": {
      "target_user_id": "",
      "flow_reference": {"type": "login", "name": "default_login_flow"},
      "json_pointer": ""
    }
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodePreInitialize",
        "data": {}
      }
    },
    {
      "type": "SUB_FLOW",
      "sub_flow": {
        "intent": {
          "kind": "IntentLoginFlowSteps",
          "data": {
            "flow_reference": {"type": "login", "name": "default_login_flow"},
            "next_step_index": 2,
            "json_pointer": ""
          }
        },
        "nodes": [
          {
            "type": "SUB_FLOW",
            "sub_flow": {
              "intent": {
                "kind": "IntentLoginFlowStepIdentify",
                "data": {
                  "flow_reference": {"type": "login", "name": "default_login_flow"},
                  "step_name": "step1",
                  "json_pointer": "/steps/0"
                }
              },
              "nodes": [
                {
                  "type": "SIMPLE",
                  "simple": {
                    "kind": "NodeIdentify",
                    "data": {
                      "identification": "email",
                      "identity_type": "login_id",
                      "login_id_type": "email",
                      "user_id": "user_xyz"
                    }
                  }
                }
              ]
            }
          },
          {
            "type": "SUB_FLOW",
            "sub_flow": {
              "intent": {
                "kind": "IntentLoginFlowStepAuthenticate",
                "data": {
                  "flow_reference": {"type": "login", "name": "default_login_flow"},
                  "step_name": "step2",
                  "json_pointer": "/steps/1",
                  "user_id": "user_xyz",
                  "options": [
                    {"authentication": "primary_password", "device_token_enabled": false}
                  ],
                  "device_token_enabled": false,
                  "did_authenticated_before_this_step": false
                }
              },
              "nodes": [
                {
                  "type": "SIMPLE",
                  "simple": {
                    "kind": "NodeAuthenticationMethod",
                    "data": {
                      "authentication": "primary_password",
                      "json_pointer": "/steps/1/oneOf/0"
                    }
                  }
                },
                {
                  "type": "SUB_FLOW",
                  "sub_flow": {
                    "intent": {
                      "kind": "IntentUseAuthenticatorPassword",
                      "data": {
                        "json_pointer": "/steps/1/oneOf/0",
                        "user_id": "user_xyz",
                        "authentication": "primary_password"
                      }
                    },
                    "nodes": [
                      {
                        "type": "SIMPLE",
                        "simple": {
                          "kind": "NodeDoUseAuthenticatorPassword",
                          "data": {
                            "json_pointer": "/steps/1/oneOf/0",
                            "authenticator": {
                              "id": "authn_pwd_001",
                              "type": "password",
                              "user_id": "user_xyz",
                              "created_at": "2024-01-15T10:00:00Z"
                            },
                            "password_change_required": false
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        ]
      }
    }
  ]
}
```

```bash
# 3. Session Key（保持不变）
GET app:myapp:authenticationflow_session:flow_abc123
```

```json
{
  "flow_id": "flow_abc123",
  "oauth_session_id": "",
  "client_id": "my_client",
  "redirect_uri": "https://example.com/callback",
  "prompt": [],
  "state": "xyz_state",
  "ui_locales": "zh-CN",
  "bot_protection_verification_result": null
}
```

**Flow 结构变化对比**：

| 阶段 | StateToken | 当前步骤 | Nodes 深度 |
|------|-----------|---------|-----------|
| 创建后 | `state_token_init` | 等待 identify | 1 层 (NodePreInitialize) |
| Identify 后 | `state_token_step2` | 等待 primary auth | 3 层 (+ IntentLoginFlowSteps + IntentLoginFlowStepIdentify) |
| Password 后 | `state_token_step3` | 等待 secondary auth | 5 层 (+ IntentLoginFlowStepAuthenticate + IntentUseAuthenticatorPassword) |

**下一步（Secondary Authentication）**：
- 前端收到新的 StateToken (`state_token_step3_xxx`)
- 根据配置，下一个 authenticate 步骤需要 `secondary_totp` 或 `secondary_oob_otp_sms`
- 流程类似场景 3，进入 `IntentLoginFlowStepAuthenticate`，但 `did_authenticated_before_this_step=true`
- 验证成功后，进入 `IntentLoginFlowSteps` 的 nested steps 处理（如 `IntentLoginFlowPreAuthenticated`）
- 最终创建 `NodeDoCreateSession` 完成登录

### 场景 4：流程完成，删除 Flow

**触发**：Secondary authentication 完成，流程结束

**关键代码**（`pkg/lib/authenticationflow/service.go:607-622`）：
```go
func (s *Service) finishFlow(ctx context.Context, flow *Flow) (cookies []*http.Cookie, err error) {
    // 1. 应用所有 Effects（如更新用户最后登录时间）
    err = ApplyAllEffects(ctx, s.Deps, NewFlows(flow))
    
    // 2. 收集 Cookies（如 Session Cookie）
    cookies, err = CollectCookies(ctx, s.Deps, NewFlows(flow))
    
    return
}
```

**删除操作**（`pkg/lib/authenticationflow/service.go:362-370`）：
```go
// 1. 删除 Session
err = s.Store.DeleteSession(ctx, session)

// 2. 删除 Flow
err = s.Store.DeleteFlow(ctx, flow)

// Redis 中：
// - session key 被删除
// - flow key 被删除（但 state keys 保留，用于防止重复提交）
```

## 总结

### Flow vs Workflow 的本质区别

1. **Flow 是业务层概念**：
   - 专门处理认证相关的用户交互流程
   - 包含 Session、OAuth、Bot Protection 等业务逻辑
   - 存储在 Redis，有明确的 TTL

2. **Workflow 是引擎层概念**：
   - 通用的工作流执行框架
   - 提供 Intent/Node/Effect 的抽象机制
   - 可被任何模块复用（如 authenticationflow、latte 等）

3. **关系**：
   - Flow 内部使用 Workflow 的机制（Intent、Nodes）
   - Flow 的 Node 可以是 SubFlow（实现嵌套）
   - Workflow 的 Node 可以是 SubWorkflow

### 关键设计要点

1. **StateToken 机制**：每次流程变更都会生成新的 StateToken，防止重复提交和并发问题
2. **Session 隔离**：Session 包含 OAuth/SAML 信息，但不含 Web Session ID（安全设计）
3. **Effect 分层**：RunEffect（立即执行）和 OnCommitEffect（事务提交后执行）
4. **Redis 存储**：使用两个 key（flow key + state key）实现存在性检查与状态存储分离
