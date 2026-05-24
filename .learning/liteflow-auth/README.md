# LiteFlow 登录认证流程

使用 LiteFlow 流程引擎实现的声明式登录认证系统，支持从 YAML 配置动态构建流程。

## 项目概述

本项目使用 **LiteFlow**（版本 2.15.3）作为底层流程引擎，实现了基于栈的声明式登录认证流程。复用原有的 YAML 配置格式，支持动态分支选择和状态持久化。

## 核心特性

- **声明式配置**：使用 YAML 定义登录流程，支持嵌套步骤
- **动态构建**：运行时从 YAML 动态生成 LiteFlow Chain
- **分支选择**：支持 `one_of` 分支结构，通过 SWITCH 组件实现动态跳转
- **状态持久化**：使用 Redis 存储流程执行状态，支持断点续传
- **暂停/恢复**：通过异常机制实现等待用户输入，恢复后继续执行

## 技术栈

- **Java 21**
- **Spring Boot 3.2.0**
- **LiteFlow 2.15.3**
- **Redis**（用于状态存储）
- **SnakeYAML**（YAML 解析）
- **Gson**（JSON 序列化）

## 目录结构

```
.
├── src/main/java/com/liteflow/auth/
│   ├── LiteFlowAuthApplication.java     # 应用程序入口
│   ├── config/                          # 配置类
│   │   └── LiteFlowConfig.java
│   ├── parser/                          # YAML 解析器
│   │   ├── FlowDefinitionParser.java
│   │   └── YamlFlowDefinition.java
│   ├── builder/                         # Chain 构建器
│   │   └── LiteFlowChainBuilder.java
│   ├── component/                       # LiteFlow 组件
│   │   ├── PauseComponent.java          # 暂停等待输入
│   │   ├── BranchSwitchComponent.java   # 分支选择
│   │   ├── IdentifyComponent.java       # 身份识别
│   │   ├── AuthenticateComponent.java    # 认证
│   │   └── FlowEndComponent.java        # 流程结束
│   ├── engine/                          # 执行引擎
│   │   └── FlowExecutionEngine.java
│   ├── state/                           # 状态管理
│   │   ├── StateManager.java
│   │   └── FlowExecutionState.java
│   ├── model/                           # 数据模型
│   │   ├── FlowDefinition.java
│   │   ├── StepDefinition.java
│   │   ├── BranchDefinition.java
│   │   ├── ExecutionResult.java
│   │   ├── ExecutionRequest.java
│   │   └── CreateRequest.java
│   ├── controller/                      # REST API
│   │   └── FlowController.java
│   └── exception/                       # 异常类
│       ├── PauseExecutionException.java
│       ├── FlowExecutionException.java
│       └── FlowNotFoundException.java
├── src/main/resources/
│   ├── login_flows.yml                  # 流程定义 YAML
│   └── application.yml                  # 应用配置
└── pom.xml
```

## 快速开始

### 1. 环境准备

确保已安装：
- JDK 21
- Maven 3.8+
- Redis 服务（默认端口 6379）

### 2. 配置 Redis

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    database: 0
```

### 3. 启动应用

```bash
# 编译
mvn clean compile

# 运行
mvn spring-boot:run
```

### 4. 测试 API

**创建流程：**
```bash
curl -X POST http://localhost:8080/api/flow/create \
  -H "Content-Type: application/json" \
  -d '{"flowName": "default_login_flow"}'
```

**响应示例：**
```json
{
  "flowId": "flow-abc123",
  "status": "NEED_INPUT",
  "options": [
    {"id": "oauth", "type": "identification", "displayName": "oauth", "hasSubSteps": false},
    {"id": "passkey", "type": "identification", "displayName": "passkey", "hasSubSteps": false},
    {"id": "email", "type": "identification", "displayName": "email", "hasSubSteps": true}
  ],
  "path": []
}
```

**提交选择：**
```bash
curl -X POST http://localhost:8080/api/flow/flow-abc123/execute \
  -H "Content-Type: application/json" \
  -d '{"choice": "email"}'
```

**获取状态：**
```bash
curl http://localhost:8080/api/flow/flow-abc123
```

## 流程配置

### YAML 格式

流程定义位于 `src/main/resources/login_flows.yml`：

```yaml
login_flows:
  - name: default_login_flow
    steps:
      - type: identify
        one_of:
          - identification: oauth
          - identification: passkey
          - identification: email
            steps:
              - type: authenticate
                one_of:
                  - authentication: primary_passkey
                  - authentication: primary_password
                    steps:
                      - type: authenticate
                        one_of:
                          - authentication: secondary_totp
                  - authentication: primary_oob_otp_email
                    steps:
                      - type: authenticate
                        one_of:
                          - authentication: secondary_totp
```

### 执行路径示例

1. **OAuth 登录**：`oauth` → 完成
2. **Passkey 登录**：`passkey` → 完成
3. **邮箱 + 密码 + TOTP**：`email` → `primary_password` → `secondary_totp` → 完成
4. **邮箱 + OOB OTP + TOTP**：`email` → `primary_oob_otp_email` → `secondary_totp` → 完成

## 架构设计

### YAML 到 LiteFlow 的映射

| YAML 结构 | LiteFlow 元素 | 说明 |
|-----------|--------------|------|
| `steps` | Chain 顺序执行 | 线性步骤 |
| `one_of` | SWITCH 组件 | 动态分支选择 |
| 嵌套 `steps` | 子 Chain | 通过 THEN 跳转 |
| `identification` | IdentifyComponent | 身份识别节点 |
| `authentication` | AuthenticateComponent | 认证节点 |

### 暂停与恢复机制

1. **创建流程**：解析 YAML，构建 LiteFlow Chains，初始化状态
2. **遇到分支**：`PauseComponent` 抛出异常，返回可选项
3. **保存状态**：捕获异常，保存到 Redis，返回 NEED_INPUT
4. **用户输入**：调用 execute，恢复状态，设置用户选择到上下文
5. **继续执行**：`BranchSwitchComponent` 根据选择跳转对应分支
6. **完成判断**：到达叶子节点，标记 COMPLETED

## 核心组件说明

### FlowDefinitionParser

解析 YAML 流程定义为内部模型，支持嵌套结构。

### LiteFlowChainBuilder

动态构建 LiteFlow Chains，建立步骤和分支的映射关系。

### PauseComponent

当需要用户输入时抛出 `PauseExecutionException`，包含当前可选项。

### BranchSwitchComponent

根据上下文中的用户选择，返回目标 Chain ID，实现分支跳转。

### FlowExecutionEngine

核心执行引擎，负责：
- 初始化时加载 YAML 并构建 Chains
- 创建流程实例
- 执行流程（捕获暂停异常）
- 保存和恢复状态

### StateManager

使用 Redis 存储流程状态，支持：
- 生成唯一 flowId
- 保存/加载/删除状态
- 更新执行路径
- 设置用户选择

## API 参考

### POST /api/flow/create

创建新的流程实例。

**请求：**
```json
{
  "flowName": "default_login_flow"
}
```

**响应：**
```json
{
  "flowId": "flow-xxx",
  "status": "NEED_INPUT",
  "path": [],
  "options": [...],
  "message": null
}
```

### GET /api/flow/{flowId}

获取流程当前状态。

**响应：**
```json
{
  "flowId": "flow-xxx",
  "status": "NEED_INPUT",
  "path": ["email"],
  "options": [...],
  "message": null
}
```

### POST /api/flow/{flowId}/execute

提交用户选择，继续执行流程。

**请求：**
```json
{
  "choice": "primary_password"
}
```

**响应：**
```json
{
  "flowId": "flow-xxx",
  "status": "NEED_INPUT",
  "path": ["email", "primary_password"],
  "options": [...],
  "message": null
}
```

## 扩展开发

### 添加新的认证方式

1. 在 `login_flows.yml` 中添加新的分支：
```yaml
- authentication: new_auth_method
```

2. 在 `AuthenticateComponent` 中添加处理逻辑：
```java
if ("new_auth_method".equals(authMethod)) {
    // 实现认证逻辑
}
```

### 自定义组件

创建新的 LiteFlow Component：

```java
@LiteflowComponent("myCustomComponent")
public class MyCustomComponent extends NodeComponent {
    @Override
    public void process() {
        // 实现业务逻辑
    }
}
```

## 测试

### 运行单元测试

```bash
mvn test
```

### 手动测试 API

使用 curl 或 Postman 按照「快速开始」中的示例进行测试。

## 注意事项

1. **Redis 依赖**：确保 Redis 服务正常运行，否则流程无法创建和执行
2. **YAML 格式**：保持正确的缩进，错误的 YAML 会导致启动失败
3. **Chain 名称**：自动生成，不要手动修改
4. **状态过期**：Redis 中状态默认 7 天过期

## 参考文档

- [LiteFlow 官方文档](https://liteflow.yomahub.com/)
- [设计文档](../../docs/superpowers/specs/2026-05-18-liteflow-auth-design.md)

## License

MIT
