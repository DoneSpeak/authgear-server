# Authgear 2FA（双因素认证）配置与实现详解

本文档详细介绍 Authgear 中 2FA（Two-Factor Authentication，双因素认证）的配置方式、执行流程和 HTTP API 调用示例。

---

## 目录

1. [2FA 概述](#2fa-概述)
2. [配置示例](#配置示例)
3. [执行流程详解](#执行流程详解)
4. [HTTP 请求示例](#http-请求示例)
5. [内部实现机制](#内部实现机制)
6. [数据存储机制](#数据存储机制)
7. [常见问题](#常见问题)

---

## 2FA 概述

双因素认证（2FA）要求用户提供两种不同类别的认证因素：

| 因素类别 | 示例 |
|---------|------|
| 知识因素（Something you know） | 密码、PIN |
| 拥有因素（Something you have） | 手机（接收 OTP）、TOTP 应用 |
| 生物因素（Something you are） | 指纹、面部识别 |

Authgear 支持的 2FA 组合：

- **主认证**（第一因素）：`primary_password`, `primary_oob_otp_email`, `primary_oob_otp_sms`, `primary_passkey`
- **次认证**（第二因素）：`secondary_totp`, `secondary_oob_otp_email`, `secondary_oob_otp_sms`, `secondary_password`

---

## 配置示例

### 基本 2FA 配置

```yaml
# 登录流程：邮箱 + 密码 + TOTP
login_flows:
  - name: default_login_flow
    steps:
      - type: identify
        one_of:
          - identification: email
            steps:
              - type: authenticate
                one_of:
                  - authentication: primary_password
                    steps:
                      - type: authenticate
                        one_of:
                          - authentication: secondary_totp
```

### 多选项 2FA 配置

```yaml
# 登录流程：支持多种主认证 + 多种次认证
login_flows:
  - name: flexible_2fa_login
    steps:
      - type: identify
        one_of:
          - identification: email
            steps:
              - type: authenticate
                one_of:
                  # 选项1: 邮箱验证码
                  - authentication: primary_oob_otp_email
                    steps:
                      - type: authenticate
                        one_of:
                          - authentication: secondary_totp
                          - authentication: secondary_oob_otp_sms
                  
                  # 选项2: 密码
                  - authentication: primary_password
                    steps:
                      - type: authenticate
                        one_of:
                          - authentication: secondary_totp
                          - authentication: secondary_oob_otp_email
                  
                  # 选项3: Passkey
                  - authentication: primary_passkey
                    # Passkey 本身足够安全，不需要第二因素
```

### 配置结构说明

```
identification: email              # 第一关：识别用户身份
  └─ steps:
       └─ authenticate            # 第二关：主认证（第一因素）
            ├─ primary_password    # 选项1：密码认证
            │    └─ steps:
            │         └─ authenticate    # 第三关：次认证（第二因素）
            │              └─ secondary_totp
            │
            └─ primary_oob_otp_email   # 选项2：邮箱OTP
                 └─ steps:
                      └─ authenticate
                           └─ secondary_totp
```

---

## 执行流程详解

### 完整执行流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              登录流程启动                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Step 1: Identify (识别)                               │
│  Intent: IntentLoginFlowStepIdentify                                          │
│  功能: 让用户选择身份识别方式                                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 用户选择 email
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Node: NodeDoUseIdentity                               │
│  记录: {type: "login_id", login_id: "user@example.com"}                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 触发嵌套步骤
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SubFlow: IntentLoginFlowSteps                             │
│  JSONPointer: "/steps/0/oneOf/0" (指向 email 分支)                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Step 2: Authenticate (主认证)                           │
│  Intent: IntentLoginFlowStepAuthenticate                                      │
│  选项: [primary_password, primary_oob_otp_email, primary_passkey]              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
              [password]        [oob_otp_email]      [passkey]
                    │                 │                 │
                    ▼                 ▼                 ▼
        ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
        │ IntentUseAuth    │ │ IntentUseAuth    │ │ IntentUseAuth    │
        │ enticatorPassword│ │ enticatorOOBOTP  │ │ enticatorPasskey │
        └──────────────────┘ └──────────────────┘ └──────────────────┘
                                      │
                                      ▼ 以 primary_oob_otp_email 为例
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SubFlow: IntentUseAuthenticatorOOBOTP                       │
│                                                                               │
│  Phase 1: 选择认证器 → NodeDidSelectAuthenticator                              │
│       │                                                                       │
│       ▼                                                                       │
│  Phase 2: 验证 OTP                                                            │
│       └─ SubFlow: IntentAuthenticationOOB                                      │
│            ├─ NodeAuthenticationOOB (发送 OTP)                               │
│            ├─ 等待用户输入验证码                                                │
│            └─ NodeDoMarkClaimVerified (验证成功)                               │
│       │                                                                       │
│       ▼                                                                       │
│  Phase 3: 完成认证 → NodeDoUseAuthenticatorSimple                            │
│  Milestone: MilestoneDidAuthenticate ✓                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 主认证完成，检查嵌套 steps
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SubFlow: IntentLoginFlowSteps (嵌套)                       │
│  JSONPointer: "/steps/0/oneOf/0/steps/0/oneOf/X" (指向具体认证分支)            │
│  读取: primary_oob_otp_email 下的 steps 配置                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Step 3: Authenticate (次认证/2FA)                          │
│  Intent: IntentLoginFlowStepAuthenticate                                      │
│  选项: [secondary_totp, secondary_oob_otp_sms]                                │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 用户选择 secondary_totp
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SubFlow: IntentUseAuthenticatorTOTP                         │
│  功能: 验证 TOTP 码（如 Google Authenticator）                                 │
│  Milestone: MilestoneDidAuthenticate ✓                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ 所有步骤完成
┌─────────────────────────────────────────────────────────────────────────────┐
│                              登录成功                                          │
│  - 生成 Session
│  - 返回访问令牌
└─────────────────────────────────────────────────────────────────────────────┘
```

### 状态流转详解

#### Milestone 状态检查顺序

```go
// IntentLoginFlowStepAuthenticate.CanReactTo

1. deviceTokenInspected          // 设备令牌已检查
2. authenticationMethodSelected  // 已选择认证方式
3. authenticated                 // 已完成认证
4. deviceTokenCreatedIfRequested // 设备令牌已创建
5. nestedStepsHandled            // 嵌套步骤已处理 ← 关键
6. ErrEOF                        // 流程结束
```

#### nestedStepsHandled 触发机制

```go
// 当主认证完成但 nestedStepsHandled = false 时
// 触发嵌套步骤处理

case !nestedStepsHandled:
    authentication := i.authenticationMethod(flows)  // 获取已选择的认证方式
    return authflow.NewSubFlow(&IntentLoginFlowSteps{
        FlowReference: i.FlowReference,
        JSONPointer:   i.jsonPointer(step, authentication),  // 指向具体分支
    }), nil
```

---

## HTTP 请求示例

### API 端点说明

| 操作 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 创建流程 | POST | `/api/v1/authentication_flows` | 初始化认证流程 |
| 获取流程 | POST | `/api/v1/authentication_flows/states` | 通过 `state_token` 获取当前状态 |
| 提交输入 | POST | `/api/v1/authentication_flows/states/input` | 提交用户输入推进流程 |

---

### 场景：邮箱 → OTP → TOTP 2FA

#### 1. 初始化登录流程

```http
POST /api/v1/authentication_flows
Content-Type: application/json

{
  "type": "login",
  "name": "default_login_flow"
}
```

**响应：**

```json
{
  "result": {
    "state_token": "authflowstate_xxx",
    "type": "login",
    "name": "default_login_flow",
    "action": {
      "type": "identify",
      "data": {
        "options": [
          {"identification": "email"},
          {"identification": "phone"},
          {"identification": "oauth"}
        ]
      }
    }
  }
}
```

---

#### 2. 选择邮箱识别

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_xxx",
  "input": {
    "identification": "email"
  }
}
```

**响应：**

```json
{
  "result": {
    "state_token": "authflowstate_yyy",
    "action": {
      "type": "authenticate",
      "data": {
        "options": [
          {"authentication": "primary_oob_otp_email"},
          {"authentication": "primary_password"},
          {"authentication": "primary_passkey"}
        ]
      }
    }
  }
}
```

---

#### 3. 选择邮箱 OTP 作为主认证

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_yyy",
  "input": {
    "authentication": "primary_oob_otp_email"
  }
}
```

**响应（等待 OTP 发送）：**

```json
{
  "result": {
    "state_token": "authflowstate_zzz",
    "action": {
      "type": "authenticate",
      "authentication": "primary_oob_otp_email",
      "data": {
        "otp_form": "code",
        "masked_display_name": "u***@example.com",
        "channel": "email"
      }
    }
  }
}
```

---

#### 4. 提交 OTP 验证码（主认证）

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_zzz",
  "input": {
    "code": "123456"
  }
}
```

**响应（主认证完成，进入 2FA）：**

```json
{
  "result": {
    "state_token": "authflowstate_aaa",
    "action": {
      "type": "authenticate",
      "data": {
        "options": [
          {"authentication": "secondary_totp"},
          {"authentication": "secondary_oob_otp_sms"}
        ]
      }
    }
  }
}
```

---

#### 5. 选择 TOTP 作为第二因素

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_aaa",
  "input": {
    "authentication": "secondary_totp"
  }
}
```

**响应（等待 TOTP 码）：**

```json
{
  "result": {
    "state_token": "authflowstate_bbb",
    "action": {
      "type": "authenticate",
      "authentication": "secondary_totp",
      "data": {
        "totp_display_name": "TOTP"
      }
    }
  }
}
```

---

#### 6. 提交 TOTP 验证码（2FA）

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_bbb",
  "input": {
    "code": "987654"
  }
}
```

**响应（登录成功）：**

```json
{
  "result": {
    "state_token": "authflowstate_ccc",
    "action": {
      "type": "finished",
      "data": {
        "finish_redirect_uri": "/",
        "user": {
          "id": "user_xxx",
          "email": "user@example.com"
        }
      }
    }
  }
}
```

---

### 场景：密码 + TOTP 2FA

```http
# 1. 初始化（同上）

# 2. 选择邮箱识别（同上）

# 3. 选择密码认证
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_yyy",
  "input": {
    "authentication": "primary_password"
  }
}

# 响应：等待密码输入

# 4. 提交密码
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_zzz",
  "input": {
    "password": "user_password"
  }
}

# 响应：进入 2FA 选择

# 5. 选择 TOTP
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_aaa",
  "input": {
    "authentication": "secondary_totp"
  }
}

# 6. 提交 TOTP 码（同上）
```

---

### 获取流程状态

```http
POST /api/v1/authentication_flows/states
Content-Type: application/json

{
  "state_token": "authflowstate_xxx"
}
```

**响应：** 返回与输入接口相同的流程状态结构

---

### Batch Input（批量输入）

支持在一次请求中提交多个输入：

```http
POST /api/v1/authentication_flows/states/input
Content-Type: application/json

{
  "state_token": "authflowstate_xxx",
  "batch_input": [
    {"identification": "email"},
    {"authentication": "primary_password"},
    {"password": "user_password"}
  ]
}
```

---

## 内部实现机制

### 核心组件关系

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              核心组件关系图                                  │
└─────────────────────────────────────────────────────────────────────────────┘

IntentLoginFlowStepAuthenticate
    │
    ├─ CanReactTo() ──→ 检查 Milestones
    │
    ├─ ReactTo() ────→ 创建 SubFlow
    │       │
    │       ├─ IntentUseAuthenticatorPassword ──→ NodeDoUseAuthenticatorPassword
    │       ├─ IntentUseAuthenticatorOOBOTP ──────→ SubFlow(IntentAuthenticationOOB)
    │       │                                          ├─ NodeAuthenticationOOB
    │       │                                          ├─ NodeDoMarkClaimVerified
    │       │                                          └─ NodeDoUpdateLastUsedChannel
    │       ├─ IntentUseAuthenticatorPasskey ─────→ NodeDoUseAuthenticatorPasskey
    │       └─ IntentUseAuthenticatorTOTP ──────→ NodeDoUseAuthenticatorSimple
    │
    └─ OutputData() ──→ 返回可选认证方式列表
```

### Milestone 机制

```go
// 关键 Milestone 定义
type MilestoneDidAuthenticate interface {
    authflow.Milestone
    MilestoneDidAuthenticate()
}

type MilestoneNestedSteps interface {
    authflow.Milestone
    MilestoneNestedSteps()
}

// 查找 Milestone
_, _, authenticated := authflow.FindMilestoneInCurrentFlow[MilestoneDidAuthenticate](flows)
_, _, nestedStepsHandled := authflow.FindMilestoneInCurrentFlow[MilestoneNestedSteps](flows)
```

### SubFlow 嵌套机制

```go
// 启动 OTP 验证子流程
return authflow.NewSubFlow(&IntentAuthenticationOOB{
    JSONPointer:    n.JSONPointer,
    UserID:         n.UserID,
    Purpose:        otp.PurposeOOBOTP,
    Authentication: n.Authentication,
    Info:           info,
    Form:           otpForm,
}), nil

// 启动嵌套步骤处理
return authflow.NewSubFlow(&IntentLoginFlowSteps{
    FlowReference: i.FlowReference,
    JSONPointer:   i.jsonPointer(step, authentication),
}), nil
```

### JSONPointer 定位机制

```
/steps/0                                        # 第1个顶层步骤 (identify)
/steps/0/oneOf/0                                # identify 的第1个选项 (email)
/steps/0/oneOf/0/steps/0                        # email 的第1个嵌套步骤 (authenticate)
/steps/0/oneOf/0/steps/0/oneOf/1                # authenticate 的第2个选项 (primary_password)
/steps/0/oneOf/0/steps/0/oneOf/1/steps/0        # primary_password 的第1个嵌套步骤 (2FA)
/steps/0/oneOf/0/steps/0/oneOf/1/steps/0/oneOf/0 # 2FA 的第1个选项 (secondary_totp)
```

---

## 数据存储机制

### 存储架构

Authgear 使用 **Redis** 作为认证流程状态的存储介质，支持高并发读写和自动过期。

### 存储模型

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           数据存储架构图                                      │
└─────────────────────────────────────────────────────────────────────────────┘

Redis
├── Flow State（流程状态）
│   ├── Key: app:{appID}:authenticationflow_state:{stateToken}
│   │   Value: Flow 对象（JSON 序列化）
│   │   TTL: 24 小时（UserInteraction）
│   │
│   └── Key: app:{appID}:authenticationflow_flow:{flowID}
│       Value: 存在标记（空值）
│       TTL: 24 小时
│
├── Session（会话信息）
│   └── Key: app:{appID}:authenticationflow_session:{flowID}
│       Value: Session 对象（JSON 序列化）
│       TTL: 24 小时
│
└── Effect Side Effects（副作用状态）
    └── Key: app:{appID}:autheffect:{effectID}
        Value: 副作用执行结果
        TTL: 24 小时
```

### 核心存储操作

#### Flow 创建

```go
// pkg/lib/authenticationflow/store.go:24-46
func (s *StoreImpl) CreateFlow(ctx context.Context, flow *Flow) error {
    bytes, err := json.Marshal(flow)
    if err != nil {
        return err
    }

    return s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        stateKey := redisFlowStateKey(s.AppID, flow.StateToken)
        ttl := Lifetime  // 24 小时

        // 存储 flow 存在标记
        _, err := conn.SetEx(ctx, flowKey, []byte(flowKey), ttl).Result()
        if err != nil {
            return err
        }

        // 存储 flow 完整状态
        _, err = conn.SetEx(ctx, stateKey, bytes, ttl).Result()
        if err != nil {
            return err
        }

        return nil
    })
}
```

#### Flow 读取

```go
// pkg/lib/authenticationflow/store.go:49-78
func (s *StoreImpl) GetFlowByStateToken(ctx context.Context, stateToken string) (*Flow, error) {
    stateKey := redisFlowStateKey(s.AppID, stateToken)
    var flow Flow
    err := s.Redis.WithConnContext(ctx, func(ctx context.Context, conn redis.Redis_6_0_Cmdable) error {
        // 1. 获取 flow JSON 数据
        bytes, err := conn.Get(ctx, stateKey).Bytes()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound
        }

        err = json.Unmarshal(bytes, &flow)
        if err != nil {
            return err
        }

        // 2. 验证 flow 是否有效（检查 flowID 是否存在）
        flowKey := redisFlowKey(s.AppID, flow.FlowID)
        _, err = conn.Get(ctx, flowKey).Result()
        if errors.Is(err, goredis.Nil) {
            return ErrFlowNotFound  // flow 已被删除
        }

        return nil
    })
    return &flow, err
}
```

#### Session 管理

```go
// Session 创建
func (s *StoreImpl) CreateSession(ctx context.Context, session *Session) error {
    bytes, err := json.Marshal(session)
    // ... 存储到 Redis
}

// Session 读取
func (s *StoreImpl) GetSession(ctx context.Context, flowID string) (*Session, error) {
    sessionKey := redisFlowSessionKey(s.AppID, flowID)
    // ... 从 Redis 读取
}
```

### 存储的数据结构

#### Flow 对象结构

```json
{
  "flow_id": "authflow_v1b2h3k4j5m6n7p8q9r0s1t2u3v4w5x6",
  "state_token": "authflowstate_y7z8a9b0c1d2e3f4g5h6i7j8k9l0m1n2",
  "intent": {
    "kind": "IntentLoginFlowStepAuthenticate",
    "json_pointer": "/steps/0",
    "step_name": "authenticate",
    "user_id": "user_xxx",
    "options": [
      {
        "authentication": "primary_oob_otp_email"
      },
      {
        "authentication": "primary_oob_otp_sms"
      }
    ],
    "device_token_enabled": true,
    "did_authenticated_before_this_step": false
  },
  "nodes": [
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDoUseIdentity",
        "identity": {
          "id": "identity_email_123",
          "type": "login_id",
          "login_id_key": "email",
          "login_id": "user@example.com",
          "user_id": "user_xxx"
        }
      }
    },
    {
      "type": "SIMPLE",
      "simple": {
        "kind": "NodeDidSelectAuthenticator",
        "authenticator": {
          "id": "authenticator_oob_email_456",
          "type": "oob_otp",
          "kind": "primary",
          "oob_otp": {
            "email": "user@example.com",
            "channel": "email"
          },
          "user_id": "user_xxx"
        }
      }
    },
    {
      "type": "SUB_FLOW",
      "flow": {
        "flow_id": "",
        "state_token": "",
        "intent": {
          "kind": "IntentAuthenticationOOB",
          "json_pointer": "/steps/0/one_of/0",
          "user_id": "user_xxx",
          "authenticator_id": "authenticator_oob_email_456",
          "channel": "email",
          "websocket_channel_name": "ws_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
        },
        "nodes": [
          {
            "type": "SIMPLE",
            "simple": {
              "kind": "NodeAuthenticationOOB",
              "user_id": "user_xxx",
              "purpose": "authentication",
              "form": "code",
              "info": {
                "id": "authenticator_oob_email_456",
                "type": "oob_otp",
                "oob_otp": {
                  "email": "user@example.com"
                }
              },
              "channel": "email",
              "websocket_channel_name": "ws_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
              "authentication": "primary_oob_otp_email"
            }
          },
          {
            "type": "SIMPLE",
            "simple": {
              "kind": "NodeDoMarkClaimVerified",
              "claim_name": "email",
              "claim_value": "user@example.com"
            }
          }
        ]
      }
    }
  ]
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `flow_id` | string | Flow 的唯一标识符，格式为 `authflow_` 前缀 + 32 位随机字符串 |
| `state_token` | string | 状态令牌，用于后续请求中获取和继续流程，格式为 `authflowstate_` 前缀 + 32 位随机字符串 |
| `intent` | object | 当前 Flow 的意图（Intent），定义了流程的目标和状态 |
| `intent.kind` | string | Intent 的类型，如 `IntentLoginFlowStepAuthenticate`、`IntentUseAuthenticatorOOBOTP` 等 |
| `intent.json_pointer` | string | 当前步骤在 YAML 配置中的 JSON Pointer 路径，如 `/steps/0` |
| `intent.step_name` | string | 步骤名称，如 `identify`、`authenticate` |
| `intent.user_id` | string | 当前用户的 ID |
| `intent.options` | array | 可用的认证选项列表 |
| `nodes` | array | 已执行的节点列表，记录流程执行历史 |
| `nodes[].type` | string | 节点类型：`SIMPLE`（简单节点）或 `SUB_FLOW`（子流程）|
| `nodes[].simple` | object | SIMPLE 类型的节点数据，包含 kind 和具体字段 |
| `nodes[].simple.kind` | string | 简单节点的类型，如 `NodeDoUseIdentity`、`NodeDidSelectAuthenticator` 等 |
| `nodes[].flow` | object | SUB_FLOW 类型的子流程数据，包含完整的 Flow 结构 |

#### Session 对象结构

```json
{
  "flow_id": "authflow_abc123...",
  "created_at": "2026-05-18T10:00:00Z",
  "expires_at": "2026-05-19T10:00:00Z",
  "client_id": "my_app",
  "redirect_uri": "https://example.com/callback",
  "user_id_hint": "",
  "login_hint": "user@example.com"
}
```

### Key 命名规范

| 类型 | Key 格式 | 示例 |
|------|---------|------|
| Flow | `app:{appID}:authenticationflow_flow:{flowID}` | `app:myapp:authenticationflow_flow:authflow_abc123` |
| State | `app:{appID}:authenticationflow_state:{stateToken}` | `app:myapp:authenticationflow_state:authflowstate_xyz789` |
| Session | `app:{appID}:authenticationflow_session:{flowID}` | `app:myapp:authenticationflow_session:authflow_abc123` |

### TTL 策略

所有认证流程相关的 Redis Key 使用统一的 TTL：

```go
// pkg/lib/authenticationflow/store.go:17
const Lifetime = duration.UserInteraction  // 默认 24 小时

// pkg/lib/authenticationflow/dependencies.go
var UserInteraction = 24 * time.Hour  // 用户交互有效期
```

### 2FA 流程中的数据变化

以 "邮箱 → OTP → TOTP" 为例：

```
步骤1: 创建流程
  Redis:
    flow:{flowID} = ""
    state:{stateToken} = {Flow: IntentLoginFlowStepIdentify}

步骤2: 选择 email
  Redis:
    state:{newStateToken} = {Flow: IntentLoginFlowStepAuthenticate, Nodes: [NodeDoUseIdentity]}

步骤3: 选择 primary_oob_otp_email
  Redis:
    state:{newStateToken} = {Flow: IntentUseAuthenticatorOOBOTP, Nodes: [NodeDidSelectAuthenticator]}

步骤4: 提交 OTP
  Redis:
    state:{newStateToken} = {Flow: SubFlow(IntentAuthenticationOOB), Nodes: [NodeAuthenticationOOB, NodeDoMarkClaimVerified]}

步骤5: 选择 secondary_totp
  Redis:
    state:{newStateToken} = {Flow: IntentUseAuthenticatorTOTP}

步骤6: 提交 TOTP，登录成功
  Redis:
    flow:{flowID} = DEL  (删除)
    state:{stateToken} = DEL (删除)
```

### 故障恢复

#### 流程状态丢失

如果 Redis 中的 flow state 过期或丢失：

```
GetFlowByStateToken → ErrFlowNotFound
                      ↓
                返回错误给客户端
                      ↓
            客户端需要重新创建流程
```

#### Session 恢复

如果 Session 存在但 Flow 丢失：

```go
// Service 会自动重新创建 Flow
// 基于 Session 中的信息恢复流程
```

---

## 常见问题

### Q1: 如何配置可选的 2FA？

```yaml
- type: authenticate
  one_of:
    - authentication: primary_password
  steps:
    - type: authenticate
      one_of:
        - authentication: secondary_totp
      optional: true    # ← 2FA 变为可选
```

### Q2: 2FA 失败如何处理？

Authgear 会在 2FA 失败时保持当前状态，允许用户重试。可以通过 `x_max_attempt` 配置重试次数。

### Q3: 如何跳过 2FA（受信任设备）？

```yaml
- type: authenticate
  one_of:
    - authentication: primary_password
      device_token: true    # ← 启用设备令牌
  steps:
    - type: authenticate
      one_of:
        - authentication: secondary_totp
```

启用后，首次 2FA 成功后会在设备上创建令牌，下次登录可跳过 2FA。

### Q4: 支持的 2FA 组合有哪些？

| 主认证 | 支持的次认证 |
|-------|-------------|
| primary_password | secondary_totp, secondary_oob_otp_email, secondary_oob_otp_sms, secondary_password |
| primary_oob_otp_email | secondary_totp, secondary_oob_otp_sms |
| primary_oob_otp_sms | secondary_totp, secondary_oob_otp_email |
| primary_passkey | 通常不需要 2FA |

---

## 相关文档

- [Authgear Node and SubFlow](Authgear-Node-and-SubFlow.md) - Node 和 SubFlow 核心概念
- [Authgear Nested Steps Implementation](Authgear-Nested-Steps-Implementation.md) - 嵌套步骤实现机制
- [Authgear Flow Runtime Mechanism](Authgear-Flow-Runtime-Mechanism.md) - 运行时机制详解
- [OOB OTP Code Submission Flow](OOB-OTP-Code-Submission-Flow.md) - OTP 提交流程
