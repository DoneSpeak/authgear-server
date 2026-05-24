# Routor 流程执行器设计文档

## 概述

Routor 是一个基于栈的声明式流程执行引擎，用于执行嵌套结构的认证/业务流程。它从 YAML 配置加载流程定义，通过 API 接收用户选择，自动推进流程直到完成。

## 需求背景

### 原始需求

通过 `name` 初始化流程，`steps` 表示从上到下顺序执行，`one_of` 表示从中选择一个执行。用户输入选择路径直到所有路径执行完成。

### 输入案例

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

**案例 1**: `identification: oauth` → 完成  
**案例 2**: `identification: email` → `authentication: primary_password` → `authentication: secondary_totp` → 完成

## 设计决策

### 选择的方案

**方案 C：栈式执行器（Stack-based Executor）**

使用执行栈管理嵌套步骤，栈顶是当前活动节点，支持自动推进子步骤。

### 决策理由

1. **自然支持嵌套**：YAML 有嵌套结构，栈是最自然的表达方式
2. **自动路径跟踪**：栈自动维护用户选择路径
3. **支持暂停/恢复**：栈状态可序列化保存
4. **借鉴 Authgear**：参考 SubFlow 思想但大幅简化

## 数据模型

### 流程定义（YAML 解析结果）

```java
public class FlowDefinition {
    private String name;                    // 流程名称
    private List<StepDefinition> steps;     // 顶层步骤列表
}

public class StepDefinition {
    private String type;                    // "identify" | "authenticate"
    private List<BranchDefinition> oneOf;     // 可选分支
    private List<StepDefinition> steps;     // 子步骤（嵌套）
}

public class BranchDefinition {
    private String identification;          // 如 "email", "oauth"
    private String authentication;          // 如 "primary_password"
    private List<StepDefinition> steps;     // 选择此分支后的子步骤
}
```

### 执行状态（可序列化）

```java
public class FlowInstance {
    private String flowId;                  // 唯一标识
    private String flowName;                // 对应的流程定义名称
    private Deque<StackFrame> stack;        // 执行栈（核心！）
    private List<String> path;              // 用户选择路径
    private State state;                    // 当前状态
    private Map<String, Object> context;    // 上下文数据
}

public class StackFrame {
    private int stepIndex;                  // 当前步骤在 steps 列表中的索引
    private List<StepDefinition> steps;     // 当前层级的步骤列表
    private String selectedBranch;            // 当前步骤选择的分支
}
```

### 执行结果

```java
public class ExecutionResult {
    private State state;                    // NEED_INPUT / COMPLETED / ERROR
    private List<Option> options;           // 当前可选分支
    private List<String> path;              // 当前执行路径
    private String message;                 // 提示信息
}

public enum State {
    NEED_INPUT,     // 需要用户选择
    COMPLETED,      // 流程完成
    ERROR           // 执行错误
}

public class Option {
    private String id;                      // 选项标识
    private String type;                    // "identification" | "authentication"
    private String displayName;             // 显示名称
    private boolean hasSubSteps;            // 是否有子步骤
}
```

## 核心组件

### FlowLoader

职责：从 YAML 文件加载流程定义

```java
public class FlowLoader {
    public Map<String, FlowDefinition> loadAll(String yamlContent);
    public FlowDefinition load(String yamlContent, String flowName);
}
```

### FlowEngine

职责：创建和执行流程实例

```java
public class FlowEngine {
    private final Map<String, FlowDefinition> definitions;
    
    public FlowInstance create(String flowName);
    public ExecutionResult execute(FlowInstance instance, String input);
    public String serialize(FlowInstance instance);
    public FlowInstance deserialize(String json);
}
```

### StackExecutor

职责：管理执行栈，处理步骤推进

```java
public class StackExecutor {
    public void initialize(FlowInstance instance, FlowDefinition definition);
    public void processInput(FlowInstance instance, String input);
    public List<Option> getCurrentOptions(FlowInstance instance);
    public boolean isComplete(FlowInstance instance);
}
```

## 执行流程详解

### 初始化状态

```
stack: [{stepIndex: 0, steps: [identify step], selectedBranch: null}]
path: []
state: NEED_INPUT
options: [oauth, passkey, email]
```

### 用户输入 "email"

```
stack: [
  {stepIndex: 0, steps: [identify step], selectedBranch: "email"},
  {stepIndex: 0, steps: [authenticate step], selectedBranch: null}
]
path: ["email"]
state: NEED_INPUT
options: [primary_passkey, primary_password, primary_oob_otp_email]
```

### 用户输入 "primary_password"

```
stack: [
  {stepIndex: 0, steps: [identify step], selectedBranch: "email"},
  {stepIndex: 0, steps: [authenticate step], selectedBranch: "primary_password"},
  {stepIndex: 0, steps: [authenticate step], selectedBranch: null}
]
path: ["email", "primary_password"]
state: NEED_INPUT
options: [secondary_totp]
```

### 用户输入 "secondary_totp"

```
stack: [
  {stepIndex: 0, steps: [identify step], selectedBranch: "email"},
  {stepIndex: 0, steps: [authenticate step], selectedBranch: "primary_password"},
  {stepIndex: 0, steps: [authenticate step], selectedBranch: "secondary_totp"}
]
path: ["email", "primary_password", "secondary_totp"]
state: COMPLETED
```

## 核心算法实现

### 处理输入

```java
public void processInput(FlowInstance instance, String input) {
    StackFrame currentFrame = instance.getStack().peek();
    StepDefinition currentStep = currentFrame.getSteps().get(currentFrame.getStepIndex());
    
    // 1. 验证输入是否有效
    BranchDefinition selectedBranch = findBranch(currentStep.getOneOf(), input);
    if (selectedBranch == null) {
        throw new IllegalArgumentException("无效选择: " + input);
    }
    
    // 2. 记录选择
    currentFrame.setSelectedBranch(input);
    instance.getPath().add(input);
    
    // 3. 如果分支有子步骤，压入新栈帧
    if (selectedBranch.getSteps() != null && !selectedBranch.getSteps().isEmpty()) {
        StackFrame newFrame = new StackFrame(0, selectedBranch.getSteps(), null);
        instance.getStack().push(newFrame);
    } else {
        // 4. 否则推进当前步骤
        advanceStep(instance);
    }
}
```

### 推进步骤

```java
private void advanceStep(FlowInstance instance) {
    StackFrame frame = instance.getStack().peek();
    frame.setStepIndex(frame.getStepIndex() + 1);
    
    // 如果当前层级的步骤全部完成，弹出栈
    if (frame.getStepIndex() >= frame.getSteps().size()) {
        instance.getStack().pop();
        
        // 递归检查父层级
        if (!instance.getStack().isEmpty()) {
            advanceStep(instance);
        } else {
            instance.setState(State.COMPLETED);
        }
    }
}
```

## API 使用示例

```java
// 1. 加载流程定义
String yaml = Files.readString(Path.of("login_flows.yaml"));
FlowLoader loader = new FlowLoader();
FlowEngine engine = new FlowEngine(loader.loadAll(yaml));

// 2. 创建流程实例
FlowInstance flow = engine.create("default_login_flow");

// 3. 执行循环
ExecutionResult result;
do {
    result = engine.execute(flow, userInput);
    
    if (result.getState() == State.NEED_INPUT) {
        System.out.println("请选择: " + result.getOptions());
        userInput = scanner.nextLine();
    }
    
    System.out.println("当前路径: " + result.getPath());
} while (result.getState() != State.COMPLETED);

System.out.println("流程完成! 最终路径: " + result.getPath());
```

## 错误处理

| 错误类型 | 场景 | 处理方式 |
|---------|------|---------|
| 无效输入 | 用户选择了不在 `one_of` 中的选项 | 返回 ERROR 状态，提示有效选项 |
| 流程未找到 | 指定的 flow name 不存在 | 抛出 IllegalArgumentException |
| 栈为空 | 尝试执行已完成的流程 | 返回 COMPLETED 状态 |
| 无效 YAML | 配置文件解析失败 | 抛出 ParseException |

## 目录结构

```
.learning/routor/
├── src/main/java/com/routor/
│   ├── model/
│   │   ├── FlowDefinition.java
│   │   ├── StepDefinition.java
│   │   ├── BranchDefinition.java
│   │   ├── FlowInstance.java
│   │   ├── StackFrame.java
│   │   ├── ExecutionResult.java
│   │   ├── Option.java
│   │   └── State.java
│   ├── engine/
│   │   ├── FlowEngine.java
│   │   ├── FlowLoader.java
│   │   └── StackExecutor.java
│   └── util/
│       └── YamlParser.java
├── src/main/resources/
│   └── flows.yaml
├── src/test/java/
│   └── FlowEngineTest.java
└── pom.xml
```

## 测试策略

1. **单元测试**：测试各个组件独立功能
2. **集成测试**：测试完整流程执行
3. **边界测试**：测试空流程、无效输入、深层嵌套

## 扩展性考虑

1. **自定义步骤类型**：通过注册表扩展新的 `type`
2. **钩子机制**：在状态转换时触发回调
3. **条件分支**：支持基于 context 的条件判断

## 参考实现

借鉴 Authgear 的以下设计：
- Accept-Loop 模式
- SubFlow 嵌套思想
- Milestone 状态跟踪
- 节点追加机制

但大幅简化：
- 移除 Intent 概念
- 简化 InputSchema
- 移除 Effect 系统
- 纯内存执行，可选序列化
