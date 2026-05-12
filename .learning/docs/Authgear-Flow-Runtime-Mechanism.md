# Authgear Authentication Flow 运行机制深度解析

基于 `email_password_primary_oob_otp_email` 配置的技术分析。

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

## 问题澄清与核心机制

### 问题回顾

用户的理解：
1. "每次都会执行最后一个 node"
2. "primary_oob_otp_email 由多个 node 完成执行"
3. "需要下一步则添加 Node"
4. "input 时取出最后一个 node 处理"
5. "node 完成后按 JSONPointer 找下一个任务"
6. "可能存在多个相同 JSONPointer 的 node"

### 客观技术分析

基于代码的验证结果：

| 理解 | 验证结果 | 说明 |
|------|----------|------|
| 执行最后一个 node | **部分正确** | 不是"执行"，而是优先检查最后一个 node 是否能接收输入 |
| primary_oob_otp_email 多个 node | **不正确** | 一个 IntentUseAuthenticatorOOBOTP (SubFlow) 完成，内部用 Milestone 跟踪状态 |
| 需要下一步则添加 Node | **不准确** | Node 追加是 ReactTo 的结果，不是显式判断"是否需要下一步" |
| input 时取最后一个 node | **部分正确** | FindInputReactor 优先检查最后一个 node，但 Intent 也可能接收输入 |
| 按 JSONPointer 找下一个任务 | **不正确** | JSONPointer 用于定位配置位置，不是运行时任务调度 |
| 多个相同 JSONPointer 的 node | **技术上可能** | 但每个 node 代表不同的执行状态，非重复 |

---

## 核心机制详解

### 1. FindInputReactor：输入路由机制

```go
// pkg/lib/authenticationflow/input.go:86-114
func FindInputReactorForFlow(ctx context.Context, deps *Dependencies, flows Flows) (*FindInputReactorResult, error) {
    if len(flows.Nearest.Nodes) > 0 {
        // 关键：优先检查最后一个 node
        lastNode := flows.Nearest.Nodes[len(flows.Nearest.Nodes)-1]
        findInputReactorResult, err := FindInputReactorForNode(ctx, deps, flows, &lastNode)
        if err == nil {
            return findInputReactorResult, nil
        }
        // ErrEOF 则 fallthrough 检查 Intent
        if !errors.Is(err, ErrEOF) {
            return nil, err
        }
    }

    // 检查 Intent 是否能接收输入
    inputSchema, err := flows.Nearest.Intent.CanReactTo(ctx, deps, flows)
    if err == nil {
        return &FindInputReactorResult{
            Flows:        flows,
            InputReactor: flows.Nearest.Intent,
            InputSchema:  inputSchema,
        }, nil
    }
    return nil, err
}
```

**关键发现**：
- 系统**优先检查最后一个 node**，而不是"总是执行最后一个 node"
- 如果最后一个 node 返回 `ErrEOF`（表示已完成），才会检查 Intent
- 这是"最近活跃节点优先"策略

### 2. Node 追加机制与 Accept 循环

```go
// pkg/lib/authenticationflow/accept.go:242-267
case *Node:
    nextNode = *reactToResult
// ...
err = appendNode(ctx, deps, findInputReactorResult.Flows, nextNode)
if err != nil {
    return
}
changed = true
```

```go
// pkg/lib/authenticationflow/accept.go:271-273
func appendNode(ctx context.Context, deps *Dependencies, flows Flows, node Node) error {
    flows.Nearest.Nodes = append(flows.Nearest.Nodes, node)
    // ...
}
```

**关键发现**：
- Node 是**追加到末尾**，不是替换
- Node 追加是 `ReactTo` 的结果，不是显式的"下一步判断"
- 每个 ReactTo 可以返回 Node 或 NodeWithDelayedOneTimeFunction

#### 为什么需要 Accept 循环？

用户可能有疑问："每次 input 处理一个 node，成功就返回，为什么需要循环？"

**核心原因：一个 Input 可能触发连锁反应**

```
场景：创建新 Flow 时传入 nil input

循环 1: IntentLoginFlowStepIdentify.CanReactTo 返回 InputSchema
        ReactTo 处理 nil input -> 返回 NodeDoUseIdentity
        appendNode
        
循环 2: NodeDoUseIdentity.CanReactTo 返回 ErrEOF（已完成）
        回退检查 IntentLoginFlowStepIdentify -> 已完成
        检查 IntentLoginFlowSteps -> 下一步可用
        CanReactTo 返回 InputSchema（可自动处理）
        ReactTo 处理 nil input -> 返回 IntentCreateIdentity
        
循环 3: IntentCreateIdentity.CanReactTo 返回 nil（可自动）
        ReactTo 创建 identity -> 返回 NodeDoCreateIdentity
        
...继续直到无法自动推进
```

**典型场景：**

| 场景 | 循环次数 | 说明 |
|------|----------|------|
| 创建 Flow（nil input）| 2-5 次 | 自动推进到第一步 |
| Password 认证 | 2-3 次 | 创建节点 + 完成 Milestone |
| 选择 OOB OTP | 4-5 次 | 创建 SubFlow + 选择认证器 + 创建 NodeAuthenticationOOB |
| OTP Code 验证 | 2-3 次 | 验证 + 完成 Milestone |

**设计哲学：** "尽可能自动化，只在需要时等待用户"

```go
for {
    reactor := FindCanReactTo()
    
    if reactor.CanAutoProcess() {
        result := reactor.AutoProcess()
        AppendNode(result)
        continue  // 可能有更多自动步骤
    }
    
    if reactor.NeedUserInput() {
        if hasInput {
            result := reactor.Process(input)
            AppendNode(result)
            continue  // 处理后可能有后续自动步骤
        } else {
            return  // 等待用户输入
        }
    }
}
```

### 3. Milestone 状态跟踪机制（替代多 Node）

```go
// pkg/lib/authenticationflow/milestone.go:16-41
func FindMilestoneInCurrentFlow[T Milestone](flows Flows) (t T, newFlows Flows, found bool) {
    w := flows.Nearest
    for _, node := range w.Nodes {
        switch n.Type {
        case NodeTypeSimple:
            if m, ok := n.Simple.(T); ok {
                t = m
                found = true
            }
        case NodeTypeSubFlow:
            if m, ok := n.SubFlow.Intent.(T); ok {
                t = m
                newFlows = flows.Replace(n.SubFlow) // 切换到 SubFlow
                found = true
            }
        }
    }
    return
}
```

**关键发现**：
- **不是通过多个 Nodes 来跟踪状态**
- 而是通过 **Milestone 接口**在现有 Nodes 中查找状态
- 例如：`FindMilestoneInCurrentFlow[MilestoneDidSelectAuthenticator]` 检查是否已选择认证器

### 4. primary_oob_otp_email 的真实执行流程

```
Nodes 链变化过程：

[初始创建 Flow]
Intent: IntentLoginFlowStepIdentify
Nodes: []

[提交 email 后]
Nodes: [NodeDoUseIdentity]
      └─ IntentLoginFlowStepIdentify 完成

[自动进入下一步 - Password]
Intent: IntentLoginFlowStepAuthenticate (step 2)
Nodes: [NodeDoUseIdentity]

[提交 password 后]
Nodes: [NodeDoUseIdentity, 
        NodeDoUseAuthenticatorPassword,
        NodeDoUseAuthenticatorSimple]
      └─ IntentLoginFlowStepAuthenticate (step 2) 完成

[自动进入下一步 - OOB OTP]
Intent: IntentLoginFlowStepAuthenticate (step 3)
Nodes: [NodeDoUseIdentity, 
        NodeDoUseAuthenticatorPassword,
        NodeDoUseAuthenticatorSimple]

[提交选择 primary_oob_otp_email 后]
Nodes: [NodeDoUseIdentity, 
        NodeDoUseAuthenticatorPassword,
        NodeDoUseAuthenticatorSimple,
        NodeDidSelectAuthenticator,  ← 选择认证器
        NodeAuthenticationOOB]      ← SubFlow 展开后的实际节点
      └─ IntentUseAuthenticatorOOBOTP 作为 SubFlow 存在于 NodeAuthenticationOOB

[注意：IntentUseAuthenticatorOOBOTP 是 Intent，不是 Node]
```

**关键发现**：
- `IntentUseAuthenticatorOOBOTP` 是 **SubFlow Intent**，不是 Node
- 它在 Nodes 中表现为 `NodeTypeSubFlow`
- 内部状态（authenticatorSelected, claimVerified, authenticated）通过 **Milestone** 跟踪，不是通过多个 Nodes

### 5. IntentUseAuthenticatorOOBOTP 内部状态机

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go:50-78
func (n *IntentUseAuthenticatorOOBOTP) CanReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows) (authflow.InputSchema, error) {
    // 通过 Milestone 查找状态，不是通过多个 Nodes
    _, _, authenticatorSelected := authflow.FindMilestoneInCurrentFlow[MilestoneDidSelectAuthenticator](flows)
    _, _, claimVerified := authflow.FindMilestoneInCurrentFlow[MilestoneDoMarkClaimVerified](flows)
    _, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)

    switch {
    case !authenticatorSelected:
        // 第一步：选择认证器
        return &InputSchemaUseAuthenticatorOOBOTP{...}, nil
    case !claimVerified:
        // 第二步：验证 claim（OTP 验证）
        return nil, nil
    case !authenticated:
        // 第三步：完成认证 milestone
        return nil, nil
    default:
        return nil, authflow.ErrEOF
    }
}
```

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go:81-132
func (n *IntentUseAuthenticatorOOBOTP) ReactTo(ctx context.Context, deps *authflow.Dependencies, flows authflow.Flows, input authflow.Input) (authflow.ReactToResult, error) {
    switch {
    case !authenticatorSelected:
        // 返回 NodeDidSelectAuthenticator
        return authflow.NewNodeSimple(&NodeDidSelectAuthenticator{...}), nil
    case !claimVerified:
        // 返回 IntentAuthenticationOOB (SubFlow)
        return authflow.NewSubFlow(&IntentAuthenticationOOB{...}), nil
    case !authenticated:
        // 返回 NodeDoUseAuthenticatorSimple
        return authflow.NewNodeSimple(&NodeDoUseAuthenticatorSimple{...}), nil
    }
}
```

**状态流转**（在同一个 Intent 内）：

```
IntentUseAuthenticatorOOBOTP (作为 SubFlow 存在)
  │
  ├─ CanReactTo: !authenticatorSelected
  │   ReactTo: 返回 NodeDidSelectAuthenticator ──┐
  │                                              │
  ├─ CanReactTo: authenticatorSelected=true       │
  │              !claimVerified                   │
  │   ReactTo: 返回 IntentAuthenticationOOB ─────┤
  │     (进入子流程，创建 NodeAuthenticationOOB) │
  │                                              │
  ├─ CanReactTo: claimVerified=true             │
  │              !authenticated                   │
  │   ReactTo: 返回 NodeDoUseAuthenticatorSimple ┤
  │                                              │
  └─ CanReactTo: authenticated=true              │
       返回 ErrEOF (完成) ◄──────────────────────┘
```

### 6. JSONPointer 的真实作用

```go
// pkg/lib/authenticationflow/declarative/intent_use_authenticator_oob_otp.go:21-26
type IntentUseAuthenticatorOOBOTP struct {
    JSONPointer    jsonpointer.T                          // 指向配置中的位置
    UserID         string                                 
    Authentication model.AuthenticationFlowAuthentication 
    Options        []AuthenticateOption                   
}
```

**关键发现**：
- JSONPointer **不是**运行时任务调度指针
- 它指向 **YAML 配置中的位置**，用于：
  1. 错误定位（告知用户哪个配置步骤出错）
  2. 查找 FlowRootObject（获取该步骤的配置参数）
  3. 日志和调试

```yaml
# 配置示例
steps:
  - type: AUTHENTICATE                                    # /steps/1
    oneOf:
      - authentication: primary_password                   # /steps/1/oneOf/0
  - type: AUTHENTICATE                                    # /steps/2
    oneOf:
      - authentication: primary_oob_otp_email             # /steps/2/oneOf/0
```

IntentUseAuthenticatorOOBOTP.JSONPointer = `/steps/2/oneOf/0`

---

## 运行机制总结

### Accept 循环工作流程

```
for {
    1. FindInputReactor()
       ├─ 检查最后一个 Node (InputReactor.CanReactTo)
       └─ 或检查 Intent (Intent.CanReactTo)
    
    2. inputSchema.MakeInput(rawMessage)
       └─ 验证并解析输入
    
    3. inputReactor.ReactTo(ctx, deps, flows, input)
       └─ 执行业务逻辑，返回 ReactToResult
    
    4. 处理 ReactToResult
       ├─ *Node: appendNode() 追加到末尾
       ├─ *SubFlow: 创建 SubFlow
       └─ 错误或特殊信号处理
}
```

### 状态跟踪双轨制

| 机制 | 用途 | 示例 |
|------|------|------|
| **Nodes 链** | 记录已完成的操作 | NodeDoUseIdentity, NodeDoUseAuthenticatorPassword |
| **Milestone** | 跟踪 Intent 内部状态 | authenticatorSelected, claimVerified, authenticated |

### 关键修正

1. **不是"执行最后一个 node"**
   - 而是"找到能接收输入的 InputReactor（可能是最后一个 Node 或 Intent）"

2. **primary_oob_otp_email 不是多个 node 完成**
   - 一个 `IntentUseAuthenticatorOOBOTP` (作为 SubFlow)
   - 内部状态用 Milestone 跟踪
   - 会产生 2-3 个 Nodes：NodeDidSelectAuthenticator, NodeAuthenticationOOB, NodeDoUseAuthenticatorSimple

3. **JSONPointer 不用于任务调度**
   - 指向配置位置，用于错误报告和配置查找

4. **Node 追加是 ReactTo 的结果**
   - 不是显式的"判断是否需要下一步"
   - ReactTo 根据当前状态决定返回什么

5. **Nodes 可能有相同 JSONPointer**
   - 例如：同一个 step 内多次 ReactTo 产生的 Nodes
   - 但每个 Node 代表不同的执行阶段

---

## 附录：Redis 存储的 Flow 数据结构示例

以下是 `email_password_primary_oob_otp_email` 流程在不同阶段存储在 Redis 中的实际数据结构。

### Flow 存储 Key 结构

```
Key: auth:flow:{flow_id}
Value: JSON 序列化的 Flow 对象
TTL: 通常 5-15 分钟（根据配置）
```

### 阶段 1：IDENTIFY 完成后

客户端提交 `{identification: "email", login_id: "user@example.com"}` 后，Redis 中存储的 Flow：

```json
{
  "flow_id": "flow_2k3m4n5o6p7q8r9s0t1u2v3w4x5y6z7",
  "state_token": "authflowstate_2IrRI8IB3ud0zS_7vwXp3hVbvuiu4v1G4yAoMyBYLdeQtyMa",
  "intent": {
    "kind": "IntentLoginFlow",
    "data": {
      "json_pointer": "",
      "flow_reference": {
        "type": "login",
        "name": "email_password_primary_oob_otp_email"
      },
      "user_id": "user_xyz789",
      "steps": [
        {
          "type": "identify",
          "one_of": [
            {
              "identification": "email"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_password"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_oob_otp_email"
            }
          ]
        }
      ]
    }
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseIdentity",
        "data": {
          "identity": {
            "id": "identity_abc123",
            "type": "login_id",
            "login_id": {
              "login_id_key": "email",
              "login_id": "user@example.com",
              "claims": {
                "email": "user@example.com"
              }
            },
            "user_id": "user_xyz789",
            "created_at": "2026-05-05T01:00:00Z",
            "updated_at": "2026-05-05T01:00:00Z"
          },
          "identity_spec": null
        }
      }
    }
  ]
}
```

**关键字段说明：**
- `intent.data.user_id`: 识别成功后设置为 `user_xyz789`
- `nodes[0].simple.data.identity`: 完整的 identity 信息
- `nodes` 数组长度为 1，表示已完成第一步

### 阶段 2：Password 认证完成后

客户端提交 `{authentication: "primary_password", password: "password123"}` 后：

```json
{
  "flow_id": "flow_2k3m4n5o6p7q8r9s0t1u2v3w4x5y6z7",
  "state_token": "authflowstate_Lb-_VWyRZJ_tFlNu5uIdj11VbBGSh3-w42dN8kKI_WWQ3rY9",
  "intent": {
    "kind": "IntentLoginFlow",
    "data": {
      "json_pointer": "",
      "flow_reference": {
        "type": "login",
        "name": "email_password_primary_oob_otp_email"
      },
      "user_id": "user_xyz789",
      "steps": [
        {
          "type": "identify",
          "one_of": [
            {
              "identification": "email"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_password"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_oob_otp_email"
            }
          ]
        }
      ]
    }
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseIdentity",
        "data": {
          "identity": {
            "id": "identity_abc123",
            "type": "login_id",
            "login_id": {
              "login_id_key": "email",
              "login_id": "user@example.com",
              "claims": {
                "email": "user@example.com"
              }
            },
            "user_id": "user_xyz789",
            "created_at": "2026-05-05T01:00:00Z",
            "updated_at": "2026-05-05T01:00:00Z"
          },
          "identity_spec": null
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseAuthenticatorPassword",
        "data": {
          "json_pointer": "/steps/1/oneOf/0",
          "authenticator": {
            "id": "authenticator_pwd_001",
            "type": "password",
            "kind": "primary",
            "user_id": "user_xyz789",
            "password": {
              "formatted": false
            }
          },
          "password_change_required": false,
          "password_change_required_reason": ""
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseAuthenticatorSimple",
        "data": {
          "authenticator": {
            "id": "authenticator_pwd_001",
            "type": "password",
            "kind": "primary",
            "user_id": "user_xyz789"
          },
          "user_id": "user_xyz789"
        }
      }
    }
  ]
}
```

**关键变化：**
- `state_token` 已更新为新值
- `nodes` 数组长度变为 3
- 新增 `NodeDoUseAuthenticatorPassword`（记录密码认证详情）
- 新增 `NodeDoUseAuthenticatorSimple`（标记认证完成 milestone）
- `json_pointer`: `/steps/1/oneOf/0` 指向配置中第2步（索引1）的 password 认证

### 阶段 3：选择 OOB OTP 后、发送邮件时

客户端提交 `{authentication: "primary_oob_otp_email"}` 选择 OOB 方式后：

```json
{
  "flow_id": "flow_2k3m4n5o6p7q8r9s0t1u2v3w4x5y6z7",
  "state_token": "authflowstate_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0",
  "intent": {
    "kind": "IntentLoginFlow",
    "data": {
      "json_pointer": "",
      "flow_reference": {
        "type": "login",
        "name": "email_password_primary_oob_otp_email"
      },
      "user_id": "user_xyz789",
      "steps": [
        {
          "type": "identify",
          "one_of": [
            {
              "identification": "email"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_password"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_oob_otp_email"
            }
          ]
        }
      ]
    }
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseIdentity",
        "data": {
          "identity": {
            "id": "identity_abc123",
            "type": "login_id",
            "login_id": {
              "login_id_key": "email",
              "login_id": "user@example.com",
              "claims": {
                "email": "user@example.com"
              }
            },
            "user_id": "user_xyz789",
            "created_at": "2026-05-05T01:00:00Z",
            "updated_at": "2026-05-05T01:00:00Z"
          },
          "identity_spec": null
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseAuthenticatorPassword",
        "data": {
          "json_pointer": "/steps/1/oneOf/0",
          "authenticator": {
            "id": "authenticator_pwd_001",
            "type": "password",
            "kind": "primary",
            "user_id": "user_xyz789"
          },
          "password_change_required": false,
          "password_change_required_reason": ""
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseAuthenticatorSimple",
        "data": {
          "authenticator": {
            "id": "authenticator_pwd_001",
            "type": "password",
            "kind": "primary",
            "user_id": "user_xyz789"
          },
          "user_id": "user_xyz789"
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDidSelectAuthenticator",
        "data": {
          "authenticator": {
            "id": "authenticator_oob_email_001",
            "type": "oob_otp_email",
            "kind": "primary",
            "user_id": "user_xyz789",
            "oob_otp": {
              "email": "user@example.com"
            }
          }
        }
      }
    },
    {
      "type": "SUB_FLOW",
      "flow": {
        "flow_id": "",
        "state_token": "",
        "intent": {
          "kind": "IntentUseAuthenticatorOOBOTP",
          "data": {
            "json_pointer": "/steps/2/oneOf/0",
            "user_id": "user_xyz789",
            "authentication": "primary_oob_otp_email",
            "options": [
              {
                "authentication": "primary_oob_otp_email",
                "channels": ["email"],
                "masked_display_name": "u***@example.com",
                "otp_form": "code"
              }
            ]
          }
        },
        "nodes": [
          {
            "type": "SIMPLE",
            "simple": {
              "kind": "NodeAuthenticationOOB",
              "data": {
                "json_pointer": "/steps/2/oneOf/0",
                "user_id": "user_xyz789",
                "purpose": "oob_otp",
                "form": "code",
                "info": {
                  "id": "authenticator_oob_email_001",
                  "type": "oob_otp_email",
                  "kind": "primary",
                  "user_id": "user_xyz789",
                  "oob_otp": {
                    "email": "user@example.com"
                  }
                },
                "channel": "email",
                "websocket_channel_name": "ws_channel_abc123xyz789",
                "authentication": "primary_oob_otp_email"
              }
            }
          }
        ]
      }
    }
  ]
}
```

**关键变化：**
- `nodes` 数组长度变为 5
- 新增 `NodeDidSelectAuthenticator`（记录选择了 OOB OTP 认证器）
- 新增 `NodeTypeSubFlow`（SubFlow 节点，包含 `IntentUseAuthenticatorOOBOTP`）
- SubFlow 内部的 `nodes[0]` 是 `NodeAuthenticationOOB`（等待 OTP code 输入）
- `websocket_channel_name`: 用于实时推送 OTP 发送状态

### 阶段 4：OTP 验证完成后、流程结束前

客户端提交 `{code: "123456"}` 验证通过后：

```json
{
  "flow_id": "flow_2k3m4n5o6p7q8r9s0t1u2v3w4x5y6z7",
  "state_token": "authflowstate_final_token_before_finished",
  "intent": {
    "kind": "IntentLoginFlow",
    "data": {
      "json_pointer": "",
      "flow_reference": {
        "type": "login",
        "name": "email_password_primary_oob_otp_email"
      },
      "user_id": "user_xyz789",
      "steps": [
        {
          "type": "identify",
          "one_of": [
            {
              "identification": "email"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_password"
            }
          ]
        },
        {
          "type": "authenticate",
          "one_of": [
            {
              "authentication": "primary_oob_otp_email"
            }
          ]
        }
      ]
    }
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseIdentity",
        "data": {
          "identity": {
            "id": "identity_abc123",
            "type": "login_id",
            "login_id": {
              "login_id_key": "email",
              "login_id": "user@example.com",
              "claims": {
                "email": "user@example.com"
              }
            },
            "user_id": "user_xyz789"
          },
          "identity_spec": null
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseAuthenticatorPassword",
        "data": {
          "json_pointer": "/steps/1/oneOf/0",
          "authenticator": {
            "id": "authenticator_pwd_001",
            "type": "password",
            "kind": "primary",
            "user_id": "user_xyz789"
          },
          "password_change_required": false,
          "password_change_required_reason": ""
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseAuthenticatorSimple",
        "data": {
          "authenticator": {
            "id": "authenticator_pwd_001",
            "type": "password",
            "kind": "primary",
            "user_id": "user_xyz789"
          },
          "user_id": "user_xyz789"
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDidSelectAuthenticator",
        "data": {
          "authenticator": {
            "id": "authenticator_oob_email_001",
            "type": "oob_otp_email",
            "kind": "primary",
            "user_id": "user_xyz789",
            "oob_otp": {
              "email": "user@example.com"
            }
          }
        }
      }
    },
    {
      "type": "SUB_FLOW",
      "flow": {
        "flow_id": "",
        "state_token": "",
        "intent": {
          "kind": "IntentUseAuthenticatorOOBOTP",
          "data": {
            "json_pointer": "/steps/2/oneOf/0",
            "user_id": "user_xyz789",
            "authentication": "primary_oob_otp_email",
            "options": [
              {
                "authentication": "primary_oob_otp_email",
                "channels": ["email"],
                "masked_display_name": "u***@example.com",
                "otp_form": "code"
              }
            ]
          }
        },
        "nodes": [
          {
            "type": "SIMPLE",
            "simple": {
              "kind": "NodeAuthenticationOOB",
              "data": {
                "json_pointer": "/steps/2/oneOf/0",
                "user_id": "user_xyz789",
                "purpose": "oob_otp",
                "form": "code",
                "info": {
                  "id": "authenticator_oob_email_001",
                  "type": "oob_otp_email",
                  "kind": "primary",
                  "user_id": "user_xyz789",
                  "oob_otp": {
                    "email": "user@example.com"
                  }
                },
                "channel": "email",
                "websocket_channel_name": "ws_channel_abc123xyz789",
                "authentication": "primary_oob_otp_email"
              }
            }
          },
          {
            "type": "SIMPLE",
            "simple": {
              "kind": "NodeDoMarkClaimVerified",
              "data": {
                "claim": {
                  "name": "email",
                  "value": "user@example.com",
                  "verified_by": "oob_otp_email",
                  "verified_at": "2026-05-05T01:05:00Z"
                }
              }
            }
          },
          {
            "type": "SIMPLE",
            "simple": {
              "kind": "NodeDoUseAuthenticatorSimple",
              "data": {
                "authenticator": {
                  "id": "authenticator_oob_email_001",
                  "type": "oob_otp_email",
                  "kind": "primary",
                  "user_id": "user_xyz789"
                },
                "user_id": "user_xyz789"
              }
            }
          }
        ]
      }
    }
  ]
}
```

**关键变化：**
- SubFlow 内部的 `nodes` 从 1 个增加到 3 个
- 新增 `NodeDoMarkClaimVerified`（标记 email claim 已验证）
- 新增 `NodeDoUseAuthenticatorSimple`（标记 OOB OTP 认证完成）
- 此时 Accept 循环会返回 `ErrEOF`，触发 `finishFlow()`
- 随后 Flow 和 Session 从 Redis 中删除

### 关键观察

**1. Node 序列化格式**

```go
// 每个 Node 存储为
type Node struct {
    Type    NodeType   // "SIMPLE" 或 "SUB_FLOW"
    Simple  NodeSimple // type=SIMPLE 时使用
    SubFlow *Flow      // type=SUB_FLOW 时使用
}

// Simple Node 的 data 包含 kind 和实际数据
type nodeSimpleJSON struct {
    Kind string          // 如 "NodeDoUseIdentity"
    Data json.RawMessage // 序列化的结构体数据
}
```

**2. SubFlow 嵌套结构**

```
Root Flow (IntentLoginFlow)
  └─ Nodes: [
       0: NodeDoUseIdentity
       1: NodeDoUseAuthenticatorPassword
       2: NodeDoUseAuthenticatorSimple
       3: NodeDidSelectAuthenticator
       4: NodeTypeSubFlow (IntentUseAuthenticatorOOBOTP)
          └─ SubFlow:
              └─ Intent: IntentUseAuthenticatorOOBOTP
              └─ Nodes: [
                   0: NodeAuthenticationOOB
                   1: NodeDoMarkClaimVerified
                   2: NodeDoUseAuthenticatorSimple
                 ]
     ]
```

**3. JSONPointer 分布**

| Node | JSONPointer | 说明 |
|------|-------------|------|
| NodeDoUseIdentity | 无 | 在 Identify Step 创建 |
| NodeDoUseAuthenticatorPassword | `/steps/1/oneOf/0` | 指向第2步 Password |
| NodeDidSelectAuthenticator | 无 | 由 Intent 内部创建 |
| NodeAuthenticationOOB | `/steps/2/oneOf/0` | 指向第3步 OOB OTP |
| NodeDoMarkClaimVerified | `/steps/2/oneOf/0` | 同属于第3步 |

**4. StateToken 变化轨迹**

```
初始创建: authflowstate_2IrRI8IB3ud0zS_7vwXp3hVbvuiu4v1G4yAoMyBYLdeQtyMa
         ↓ (IDENTIFY 完成, changed=true)
         authflowstate_Lb-_VWyRZJ_tFlNu5uIdj11VbBGSh3-w42dN8kKI_WWQ3rY9
         ↓ (Password 完成, changed=true)
         authflowstate_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0
         ↓ (选择 OOB OTP 后, changed=true)
         authflowstate_final_token_before_finished
         ↓ (OTP 验证完成, changed=true)
         [Flow 删除, 返回 finished]
```

每次 `changed = true` 时，`doAccept` 的 defer 函数会生成新的 `state_token`：

```go
// pkg/lib/authenticationflow/accept.go:110-113
defer func() {
    if changed {
        flows.Nearest.StateToken = newStateToken()
    }
}()
```
