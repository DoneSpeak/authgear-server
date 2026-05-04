# Authflow - 轻量级 Java 认证流程组件

基于 Spring Boot 的认证流程引擎，支持灵活配置的登录/注册流程。

## 快速开始

### 1. 启动依赖服务

```bash
# 启动 Redis（带密码）
docker run -d -p 6379:6379 --name redis redis:7-alpine redis-server --requirepass opsbase123
```

### 2. 启动应用

```bash
# 设置 Redis 密码环境变量后启动
export REDIS_PASSWORD=opsbase123
make run
```

应用默认运行在 `http://localhost:8080`

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/authentication_flows` | POST | 创建认证流程 |
| `/api/v1/authentication_flows/states/input` | POST | 执行步骤输入 |
| `/api/v1/authentication_flows/states` | POST | 查询流程状态 |

## 完整登录案例

### 场景：使用默认流程登录

流程定义：`IDENTIFY` → `AUTHENTICATE`

#### Step 1: 创建登录流程

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows \
  -H "Content-Type: application/json" \
  -d '{
    "type": "login",
    "name": "default"
  }'
```

响应：
```json
{
  "result": {
    "flow_id": "a1b2c3d4e5f6...",
    "state_token": "authflowstate_xxx",
    "type": "LOGIN",
    "name": "default",
    "action": {
      "type": "IDENTIFY",
      "data": {}
    }
  }
}
```

#### Step 2: 提交身份信息

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_xxx",
    "input": {
      "identification": "email",
      "login_id": "user@example.com"
    }
  }'
```

响应：
```json
{
  "result": {
    "state_token": "authflowstate_yyy",
    "action": {
      "type": "AUTHENTICATE",
      "data": {}
    }
  }
}
```

#### Step 3: 提交认证凭证

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_yyy",
    "input": {
      "authentication": "primary_password",
      "password": "your-password"
    }
  }'
```

响应（登录成功）：
```json
{
  "result": {
    "state_token": "authflowstate_zzz",
    "action": {
      "type": "FINISHED",
      "data": {}
    }
  }
}
```

---

### 场景：手机号 + OTP 登录

流程定义：`IDENTIFY` → `AUTHENTICATE` → `VERIFY`

#### Step 1: 创建 OTP 登录流程

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows \
  -H "Content-Type: application/json" \
  -d '{
    "type": "login",
    "name": "phone_otp"
  }'
```

#### Step 2: 提交手机号

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_xxx",
    "input": {
      "identification": "phone",
      "login_id": "+8613800138000"
    }
  }'
```

#### Step 3: 请求发送 OTP

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_yyy",
    "input": {
      "authentication": "primary_oob_otp_sms"
    }
  }'
```

#### Step 4: 提交 OTP 验证码

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_zzz",
    "input": {
      "code": "123456"
    }
  }'
```

---

### 场景：注册新用户

流程定义：`IDENTIFY` → `CREATE_AUTHENTICATOR` → `USER_PROFILE`

#### Step 1: 创建注册流程

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows \
  -H "Content-Type: application/json" \
  -d '{
    "type": "signup",
    "name": "signup_default"
  }'
```

#### Step 2: 提交身份信息

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_xxx",
    "input": {
      "identification": "email",
      "login_id": "newuser@example.com"
    }
  }'
```

#### Step 3: 创建认证器（设置密码）

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_yyy",
    "input": {
      "authentication": "primary_password",
      "password": "SecurePass123!"
    }
  }'
```

#### Step 4: 填写用户资料

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states/input \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_zzz",
    "input": {
      "given_name": "张三",
      "family_name": "张"
    }
  }'
```

---

## 查询流程状态

```bash
curl -X POST http://localhost:8080/api/v1/authentication_flows/states \
  -H "Content-Type: application/json" \
  -d '{
    "state_token": "authflowstate_xxx"
  }'
```

## 可用流程定义

| 流程名称 | 类型 | 步骤 |
|---------|------|------|
| `default` | LOGIN | IDENTIFY → AUTHENTICATE |
| `phone_otp` | LOGIN | IDENTIFY → AUTHENTICATE → VERIFY |
| `multi_id` | LOGIN | IDENTIFY(phone/email) → AUTHENTICATE |
| `signup_default` | SIGNUP | IDENTIFY → CREATE_AUTHENTICATOR → USER_PROFILE |

## Makefile 命令

```bash
make build    # 编译打包
make run      # 前台运行
make start    # 后台启动
make stop     # 停止应用
make status   # 查看状态
make test     # 运行测试
make clean    # 清理构建
```

## 配置

编辑 `src/main/resources/application.yml`：

```yaml
server:
  port: 8080

authflow:
  storage:
    ttl: 900  # 流程状态过期时间（秒）

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

流程定义文件：`src/main/resources/flows.yaml`
