# Routor Flow Executor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个基于栈的声明式流程执行引擎，支持嵌套 YAML 配置和 API 驱动执行。

**Architecture:** 使用执行栈管理嵌套步骤，通过 FlowEngine 提供创建和执行 API，支持序列化持久化。

**Tech Stack:** Java 17, Maven, SnakeYAML, Jackson (JSON 序列化), JUnit 5

---

## File Structure Overview

```
.learning/routor/
├── src/main/java/com/routor/
│   ├── model/
│   │   ├── FlowDefinition.java      # 流程定义根类
│   │   ├── StepDefinition.java      # 步骤定义
│   │   ├── BranchDefinition.java    # 分支定义
│   │   ├── FlowInstance.java        # 运行时状态
│   │   ├── StackFrame.java          # 栈帧
│   │   ├── ExecutionResult.java     # 执行结果
│   │   ├── Option.java              # 选项
│   │   └── State.java               # 状态枚举
│   ├── engine/
│   │   ├── FlowEngine.java          # 流程引擎
│   │   ├── FlowLoader.java          # YAML 加载器
│   │   └── StackExecutor.java       # 栈执行器
│   └── util/
│       └── YamlParser.java          # YAML 解析工具
├── src/test/java/com/routor/
│   ├── engine/
│   │   ├── FlowEngineTest.java
│   │   ├── FlowLoaderTest.java
│   │   └── StackExecutorTest.java
│   └── resources/
│       └── test-flows.yaml
└── pom.xml
```

---

## Task 1: 创建 Maven 项目结构

**Files:**
- Create: `.learning/routor/pom.xml`
- Create: `.learning/routor/src/main/java/com/routor/model/.gitkeep`
- Create: `.learning/routor/src/main/java/com/routor/engine/.gitkeep`
- Create: `.learning/routor/src/main/java/com/routor/util/.gitkeep`
- Create: `.learning/routor/src/test/java/com/routor/engine/.gitkeep`
- Create: `.learning/routor/src/test/resources/.gitkeep`

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.routor</groupId>
    <artifactId>routor-flow-executor</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.10.0</junit.version>
        <snakeyaml.version>2.2</snakeyaml.version>
        <jackson.version>2.15.2</jackson.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.yaml</groupId>
            <artifactId>snakeyaml</artifactId>
            <version>${snakeyaml.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.1.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建目录结构**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mkdir -p src/main/java/com/routor/model
mkdir -p src/main/java/com/routor/engine
mkdir -p src/main/java/com/routor/util
mkdir -p src/test/java/com/routor/engine
mkdir -p src/test/resources
touch src/main/java/com/routor/model/.gitkeep
touch src/main/java/com/routor/engine/.gitkeep
touch src/main/java/com/routor/util/.gitkeep
touch src/test/java/com/routor/engine/.gitkeep
touch src/test/resources/.gitkeep
```

- [ ] **Step 3: 验证项目结构**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn help:effective-pom -q
```

Expected: 命令成功执行，显示有效 POM 配置

- [ ] **Step 4: Commit**

```bash
git add .learning/routor/
git commit -m "chore: setup routor project structure"
```

---

## Task 2: 实现模型类 - State 和 Option

**Files:**
- Create: `src/main/java/com/routor/model/State.java`
- Create: `src/main/java/com/routor/model/Option.java`
- Create: `src/test/java/com/routor/model/StateTest.java`

- [ ] **Step 1: 编写 State 枚举测试**

```java
package com.routor.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateTest {
    @Test
    void stateShouldHaveCorrectValues() {
        assertEquals(3, State.values().length);
        assertNotNull(State.NEED_INPUT);
        assertNotNull(State.COMPLETED);
        assertNotNull(State.ERROR);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=StateTest -q
```

Expected: FAIL - "State 类不存在"

- [ ] **Step 3: 实现 State 枚举**

```java
package com.routor.model;

/**
 * 流程执行状态
 */
public enum State {
    NEED_INPUT,     // 需要用户选择
    COMPLETED,      // 流程完成
    ERROR           // 执行错误
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=StateTest -q
```

Expected: PASS

- [ ] **Step 5: 实现 Option 类测试**

```java
package com.routor.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptionTest {
    @Test
    void optionShouldStoreValues() {
        Option option = new Option("email", "identification", "Email", true);
        
        assertEquals("email", option.getId());
        assertEquals("identification", option.getType());
        assertEquals("Email", option.getDisplayName());
        assertTrue(option.isHasSubSteps());
    }
}
```

- [ ] **Step 6: 运行测试验证失败**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=OptionTest -q
```

Expected: FAIL - "Option 类不存在"

- [ ] **Step 7: 实现 Option 类**

```java
package com.routor.model;

/**
 * 用户可选择的选项
 */
public class Option {
    private final String id;
    private final String type;
    private final String displayName;
    private final boolean hasSubSteps;

    public Option(String id, String type, String displayName, boolean hasSubSteps) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.hasSubSteps = hasSubSteps;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isHasSubSteps() {
        return hasSubSteps;
    }

    @Override
    public String toString() {
        return id;
    }
}
```

- [ ] **Step 8: 运行测试验证通过**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=OptionTest -q
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/model/State.java
git add .learning/routor/src/main/java/com/routor/model/Option.java
git add .learning/routor/src/test/java/com/routor/model/
git commit -m "feat: add State enum and Option model"
```

---

## Task 3: 实现模型类 - 定义相关类

**Files:**
- Create: `src/main/java/com/routor/model/BranchDefinition.java`
- Create: `src/main/java/com/routor/model/StepDefinition.java`
- Create: `src/main/java/com/routor/model/FlowDefinition.java`

- [ ] **Step 1: 实现 BranchDefinition**

```java
package com.routor.model;

import java.util.List;

/**
 * 分支定义 - 表示 one_of 中的一个选项
 */
public class BranchDefinition {
    private String identification;
    private String authentication;
    private List<StepDefinition> steps;

    public BranchDefinition() {}

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public String getAuthentication() {
        return authentication;
    }

    public void setAuthentication(String authentication) {
        this.authentication = authentication;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }

    /**
     * 获取分支标识符（identification 或 authentication）
     */
    public String getId() {
        return identification != null ? identification : authentication;
    }

    public boolean hasSubSteps() {
        return steps != null && !steps.isEmpty();
    }
}
```

- [ ] **Step 2: 实现 StepDefinition**

```java
package com.routor.model;

import java.util.List;

/**
 * 步骤定义
 */
public class StepDefinition {
    private String type;                      // "identify" | "authenticate"
    private List<BranchDefinition> oneOf;       // 可选分支
    private List<StepDefinition> steps;         // 子步骤（用于直接嵌套）

    public StepDefinition() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<BranchDefinition> getOneOf() {
        return oneOf;
    }

    public void setOneOf(List<BranchDefinition> oneOf) {
        this.oneOf = oneOf;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }

    public boolean hasOneOf() {
        return oneOf != null && !oneOf.isEmpty();
    }

    public boolean hasSubSteps() {
        return steps != null && !steps.isEmpty();
    }
}
```

- [ ] **Step 3: 实现 FlowDefinition**

```java
package com.routor.model;

import java.util.List;

/**
 * 流程定义 - 对应 YAML 中的一个流程
 */
public class FlowDefinition {
    private String name;
    private String type;
    private List<StepDefinition> steps;

    public FlowDefinition() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/model/BranchDefinition.java
git add .learning/routor/src/main/java/com/routor/model/StepDefinition.java
git add .learning/routor/src/main/java/com/routor/model/FlowDefinition.java
git commit -m "feat: add definition model classes"
```

---

## Task 4: 实现运行时模型 - StackFrame 和 FlowInstance

**Files:**
- Create: `src/main/java/com/routor/model/StackFrame.java`
- Create: `src/main/java/com/routor/model/FlowInstance.java`

- [ ] **Step 1: 实现 StackFrame**

```java
package com.routor.model;

import java.util.List;

/**
 * 执行栈帧 - 表示一层步骤执行上下文
 */
public class StackFrame {
    private int stepIndex;                    // 当前步骤在 steps 列表中的索引
    private List<StepDefinition> steps;       // 当前层级的步骤列表
    private String selectedBranch;            // 当前步骤选择的分支

    public StackFrame() {}

    public StackFrame(int stepIndex, List<StepDefinition> steps, String selectedBranch) {
        this.stepIndex = stepIndex;
        this.steps = steps;
        this.selectedBranch = selectedBranch;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }

    public String getSelectedBranch() {
        return selectedBranch;
    }

    public void setSelectedBranch(String selectedBranch) {
        this.selectedBranch = selectedBranch;
    }

    /**
     * 获取当前步骤定义
     */
    public StepDefinition currentStep() {
        if (steps == null || stepIndex >= steps.size()) {
            return null;
        }
        return steps.get(stepIndex);
    }
}
```

- [ ] **Step 2: 实现 FlowInstance**

```java
package com.routor.model;

import java.util.*;

/**
 * 流程实例 - 运行时状态
 */
public class FlowInstance {
    private String flowId;
    private String flowName;
    private Deque<StackFrame> stack;
    private List<String> path;
    private State state;
    private Map<String, Object> context;

    public FlowInstance() {
        this.stack = new ArrayDeque<>();
        this.path = new ArrayList<>();
        this.state = State.NEED_INPUT;
        this.context = new HashMap<>();
    }

    public String getFlowId() {
        return flowId;
    }

    public void setFlowId(String flowId) {
        this.flowId = flowId;
    }

    public String getFlowName() {
        return flowName;
    }

    public void setFlowName(String flowName) {
        this.flowName = flowName;
    }

    public Deque<StackFrame> getStack() {
        return stack;
    }

    public void setStack(Deque<StackFrame> stack) {
        this.stack = stack;
    }

    public List<String> getPath() {
        return path;
    }

    public void setPath(List<String> path) {
        this.path = path;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    /**
     * 获取当前栈帧
     */
    public StackFrame currentFrame() {
        return stack.peek();
    }

    /**
     * 检查是否已完成
     */
    public boolean isComplete() {
        return state == State.COMPLETED || stack.isEmpty();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/model/StackFrame.java
git add .learning/routor/src/main/java/com/routor/model/FlowInstance.java
git commit -m "feat: add runtime model classes"
```

---

## Task 5: 实现 ExecutionResult

**Files:**
- Create: `src/main/java/com/routor/model/ExecutionResult.java`

- [ ] **Step 1: 实现 ExecutionResult**

```java
package com.routor.model;

import java.util.Collections;
import java.util.List;

/**
 * 执行结果
 */
public class ExecutionResult {
    private final State state;
    private final List<Option> options;
    private final List<String> path;
    private final String message;

    private ExecutionResult(State state, List<Option> options, List<String> path, String message) {
        this.state = state;
        this.options = options != null ? options : Collections.emptyList();
        this.path = path != null ? path : Collections.emptyList();
        this.message = message;
    }

    public static ExecutionResult needInput(List<Option> options, List<String> path) {
        return new ExecutionResult(State.NEED_INPUT, options, path, null);
    }

    public static ExecutionResult completed(List<String> path) {
        return new ExecutionResult(State.COMPLETED, null, path, "流程完成");
    }

    public static ExecutionResult error(String message, List<String> path) {
        return new ExecutionResult(State.ERROR, null, path, message);
    }

    public State getState() {
        return state;
    }

    public List<Option> getOptions() {
        return options;
    }

    public List<String> getPath() {
        return path;
    }

    public String getMessage() {
        return message;
    }

    public boolean isNeedInput() {
        return state == State.NEED_INPUT;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isError() {
        return state == State.ERROR;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/model/ExecutionResult.java
git commit -m "feat: add ExecutionResult"
```

---

## Task 6: 实现 FlowLoader

**Files:**
- Create: `src/main/java/com/routor/engine/FlowLoader.java`
- Create: `src/test/resources/test-flows.yaml`
- Create: `src/test/java/com/routor/engine/FlowLoaderTest.java`

- [ ] **Step 1: 创建测试 YAML 文件**

```yaml
login_flows:
  - name: default_login_flow
    type: LOGIN
    steps:
      - type: identify
        oneOf:
          - identification: oauth
          - identification: passkey
          - identification: email
            steps:
              - type: authenticate
                oneOf:
                  - authentication: primary_passkey
                  - authentication: primary_password
                    steps:
                      - type: authenticate
                        oneOf:
                          - authentication: secondary_totp
  - name: simple_flow
    type: LOGIN
    steps:
      - type: identify
        oneOf:
          - identification: email
```

- [ ] **Step 2: 编写 FlowLoader 测试**

```java
package com.routor.engine;

import com.routor.model.FlowDefinition;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FlowLoaderTest {
    private static final String TEST_YAML = """
        login_flows:
          - name: test_flow
            type: LOGIN
            steps:
              - type: identify
                oneOf:
                  - identification: email
        """;

    @Test
    void shouldLoadFlowFromYaml() {
        FlowLoader loader = new FlowLoader();
        FlowDefinition flow = loader.load(TEST_YAML, "test_flow");

        assertNotNull(flow);
        assertEquals("test_flow", flow.getName());
        assertEquals("LOGIN", flow.getType());
        assertEquals(1, flow.getSteps().size());
        assertEquals("identify", flow.getSteps().get(0).getType());
    }

    @Test
    void shouldLoadAllFlows() {
        FlowLoader loader = new FlowLoader();
        Map<String, FlowDefinition> flows = loader.loadAll(TEST_YAML);

        assertEquals(1, flows.size());
        assertTrue(flows.containsKey("test_flow"));
    }

    @Test
    void shouldReturnNullForNonExistentFlow() {
        FlowLoader loader = new FlowLoader();
        FlowDefinition flow = loader.load(TEST_YAML, "non_existent");

        assertNull(flow);
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=FlowLoaderTest -q
```

Expected: FAIL - "FlowLoader 类不存在"

- [ ] **Step 4: 实现 FlowLoader**

```java
package com.routor.engine;

import com.routor.model.BranchDefinition;
import com.routor.model.FlowDefinition;
import com.routor.model.StepDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.*;

/**
 * YAML 流程加载器
 */
public class FlowLoader {

    /**
     * 加载指定名称的流程
     */
    public FlowDefinition load(String yamlContent, String flowName) {
        Map<String, FlowDefinition> flows = loadAll(yamlContent);
        return flows.get(flowName);
    }

    /**
     * 加载所有流程
     */
    public Map<String, FlowDefinition> loadAll(String yamlContent) {
        Yaml yaml = createYaml();
        Map<String, Object> root = yaml.load(yamlContent);

        if (root == null || !root.containsKey("login_flows")) {
            return Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flowsData = (List<Map<String, Object>>) root.get("login_flows");

        Map<String, FlowDefinition> flows = new HashMap<>();
        for (Map<String, Object> flowData : flowsData) {
            FlowDefinition flow = parseFlow(flowData);
            flows.put(flow.getName(), flow);
        }

        return flows;
    }

    private Yaml createYaml() {
        LoaderOptions options = new LoaderOptions();
        return new Yaml(options);
    }

    @SuppressWarnings("unchecked")
    private FlowDefinition parseFlow(Map<String, Object> data) {
        FlowDefinition flow = new FlowDefinition();
        flow.setName((String) data.get("name"));
        flow.setType((String) data.get("type"));

        if (data.containsKey("steps")) {
            List<Map<String, Object>> stepsData = (List<Map<String, Object>>) data.get("steps");
            flow.setSteps(parseSteps(stepsData));
        }

        return flow;
    }

    private List<StepDefinition> parseSteps(List<Map<String, Object>> stepsData) {
        if (stepsData == null) return null;

        List<StepDefinition> steps = new ArrayList<>();
        for (Map<String, Object> stepData : stepsData) {
            steps.add(parseStep(stepData));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private StepDefinition parseStep(Map<String, Object> data) {
        StepDefinition step = new StepDefinition();
        step.setType((String) data.get("type"));

        if (data.containsKey("oneOf")) {
            List<Map<String, Object>> oneOfData = (List<Map<String, Object>>) data.get("oneOf");
            step.setOneOf(parseBranches(oneOfData));
        }

        if (data.containsKey("steps")) {
            List<Map<String, Object>> subStepsData = (List<Map<String, Object>>) data.get("steps");
            step.setSteps(parseSteps(subStepsData));
        }

        return step;
    }

    @SuppressWarnings("unchecked")
    private List<BranchDefinition> parseBranches(List<Map<String, Object>> branchesData) {
        if (branchesData == null) return null;

        List<BranchDefinition> branches = new ArrayList<>();
        for (Map<String, Object> branchData : branchesData) {
            BranchDefinition branch = new BranchDefinition();
            branch.setIdentification((String) branchData.get("identification"));
            branch.setAuthentication((String) branchData.get("authentication"));

            if (branchData.containsKey("steps")) {
                List<Map<String, Object>> stepsData = (List<Map<String, Object>>) branchData.get("steps");
                branch.setSteps(parseSteps(stepsData));
            }

            branches.add(branch);
        }
        return branches;
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=FlowLoaderTest -q
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/engine/FlowLoader.java
git add .learning/routor/src/test/java/com/routor/engine/FlowLoaderTest.java
git add .learning/routor/src/test/resources/test-flows.yaml
git commit -m "feat: implement FlowLoader"
```

---

## Task 7: 实现 StackExecutor

**Files:**
- Create: `src/main/java/com/routor/engine/StackExecutor.java`
- Create: `src/test/java/com/routor/engine/StackExecutorTest.java`

- [ ] **Step 1: 编写 StackExecutor 测试**

```java
package com.routor.engine;

import com.routor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class StackExecutorTest {
    private StackExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new StackExecutor();
    }

    @Test
    void shouldInitializeWithSteps() {
        StepDefinition step = new StepDefinition();
        step.setType("identify");
        FlowDefinition flow = new FlowDefinition();
        flow.setSteps(Collections.singletonList(step));

        FlowInstance instance = new FlowInstance();
        executor.initialize(instance, flow);

        assertFalse(instance.getStack().isEmpty());
        assertEquals(0, instance.currentFrame().getStepIndex());
    }

    @Test
    void shouldGetCurrentOptions() {
        BranchDefinition branch = new BranchDefinition();
        branch.setIdentification("email");

        StepDefinition step = new StepDefinition();
        step.setType("identify");
        step.setOneOf(Collections.singletonList(branch));

        FlowInstance instance = new FlowInstance();
        StackFrame frame = new StackFrame(0, Collections.singletonList(step), null);
        instance.getStack().push(frame);

        var options = executor.getCurrentOptions(instance);
        assertEquals(1, options.size());
        assertEquals("email", options.get(0).getId());
    }

    @Test
    void shouldProcessInputAndAdvance() {
        BranchDefinition branch = new BranchDefinition();
        branch.setIdentification("oauth");

        StepDefinition step = new StepDefinition();
        step.setType("identify");
        step.setOneOf(Collections.singletonList(branch));

        FlowInstance instance = new FlowInstance();
        StackFrame frame = new StackFrame(0, Collections.singletonList(step), null);
        instance.getStack().push(frame);

        executor.processInput(instance, "oauth");

        assertEquals(State.COMPLETED, instance.getState());
        assertTrue(instance.getPath().contains("oauth"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=StackExecutorTest -q
```

Expected: FAIL - "StackExecutor 类不存在"

- [ ] **Step 3: 实现 StackExecutor**

```java
package com.routor.engine;

import com.routor.model.*;

import java.util.*;

/**
 * 栈执行器 - 管理执行栈和步骤推进
 */
public class StackExecutor {

    /**
     * 初始化流程实例
     */
    public void initialize(FlowInstance instance, FlowDefinition definition) {
        if (definition.getSteps() != null && !definition.getSteps().isEmpty()) {
            StackFrame frame = new StackFrame(0, definition.getSteps(), null);
            instance.getStack().push(frame);
        }
    }

    /**
     * 获取当前可选选项
     */
    public List<Option> getCurrentOptions(FlowInstance instance) {
        StackFrame frame = instance.currentFrame();
        if (frame == null) {
            return Collections.emptyList();
        }

        StepDefinition currentStep = frame.currentStep();
        if (currentStep == null || !currentStep.hasOneOf()) {
            return Collections.emptyList();
        }

        List<Option> options = new ArrayList<>();
        for (BranchDefinition branch : currentStep.getOneOf()) {
            String id = branch.getId();
            String type = branch.getIdentification() != null ? "identification" : "authentication";
            options.add(new Option(id, type, capitalize(id), branch.hasSubSteps()));
        }

        return options;
    }

    /**
     * 处理用户输入，推进流程
     */
    public void processInput(FlowInstance instance, String input) {
        if (instance.isComplete()) {
            throw new IllegalStateException("Flow is already completed");
        }

        StackFrame frame = instance.currentFrame();
        StepDefinition currentStep = frame.currentStep();

        if (currentStep == null) {
            throw new IllegalStateException("No current step");
        }

        // 查找匹配的分支
        BranchDefinition selectedBranch = findBranch(currentStep.getOneOf(), input);
        if (selectedBranch == null) {
            instance.setState(State.ERROR);
            throw new IllegalArgumentException("Invalid selection: " + input);
        }

        // 记录选择
        frame.setSelectedBranch(input);
        instance.getPath().add(input);

        // 如果分支有子步骤，压入新栈帧
        if (selectedBranch.hasSubSteps()) {
            StackFrame newFrame = new StackFrame(0, selectedBranch.getSteps(), null);
            instance.getStack().push(newFrame);
        } else {
            // 否则推进当前步骤
            advanceStep(instance);
        }
    }

    /**
     * 检查是否完成
     */
    public boolean isComplete(FlowInstance instance) {
        return instance.isComplete();
    }

    /**
     * 查找匹配的分支
     */
    private BranchDefinition findBranch(List<BranchDefinition> branches, String input) {
        if (branches == null) return null;
        for (BranchDefinition branch : branches) {
            if (input.equals(branch.getId())) {
                return branch;
            }
        }
        return null;
    }

    /**
     * 推进到下一步骤
     */
    private void advanceStep(FlowInstance instance) {
        StackFrame frame = instance.currentFrame();
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

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=StackExecutorTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/engine/StackExecutor.java
git add .learning/routor/src/test/java/com/routor/engine/StackExecutorTest.java
git commit -m "feat: implement StackExecutor"
```

---

## Task 8: 实现 FlowEngine

**Files:**
- Create: `src/main/java/com/routor/engine/FlowEngine.java`
- Create: `src/test/java/com/routor/engine/FlowEngineTest.java`

- [ ] **Step 1: 编写 FlowEngine 测试**

```java
package com.routor.engine;

import com.routor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {
    private static final String TEST_YAML = """
        login_flows:
          - name: test_flow
            type: LOGIN
            steps:
              - type: identify
                oneOf:
                  - identification: email
                  - identification: oauth
        """;

    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        FlowLoader loader = new FlowLoader();
        engine = new FlowEngine(loader.loadAll(TEST_YAML));
    }

    @Test
    void shouldCreateFlowInstance() {
        FlowInstance instance = engine.create("test_flow");

        assertNotNull(instance);
        assertNotNull(instance.getFlowId());
        assertEquals("test_flow", instance.getFlowName());
        assertFalse(instance.getStack().isEmpty());
    }

    @Test
    void shouldExecuteFlowAndReturnOptions() {
        FlowInstance instance = engine.create("test_flow");

        ExecutionResult result = engine.execute(instance, null);

        assertEquals(State.NEED_INPUT, result.getState());
        assertEquals(2, result.getOptions().size());
    }

    @Test
    void shouldCompleteFlow() {
        FlowInstance instance = engine.create("test_flow");

        engine.execute(instance, null);           // 获取选项
        ExecutionResult result = engine.execute(instance, "oauth");  // 选择 oauth

        assertEquals(State.COMPLETED, result.getState());
        assertTrue(result.getPath().contains("oauth"));
    }

    @Test
    void shouldThrowExceptionForNonExistentFlow() {
        assertThrows(IllegalArgumentException.class, () -> engine.create("non_existent"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=FlowEngineTest -q
```

Expected: FAIL - "FlowEngine 类不存在"

- [ ] **Step 3: 实现 FlowEngine**

```java
package com.routor.engine;

import com.routor.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * 流程引擎 - 主入口
 */
public class FlowEngine {
    private final Map<String, FlowDefinition> definitions;
    private final StackExecutor executor;
    private final ObjectMapper objectMapper;

    public FlowEngine(Map<String, FlowDefinition> definitions) {
        this.definitions = definitions;
        this.executor = new StackExecutor();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 创建新流程实例
     */
    public FlowInstance create(String flowName) {
        FlowDefinition definition = definitions.get(flowName);
        if (definition == null) {
            throw new IllegalArgumentException("Flow not found: " + flowName);
        }

        FlowInstance instance = new FlowInstance();
        instance.setFlowId(generateId());
        instance.setFlowName(flowName);
        instance.setState(State.NEED_INPUT);

        executor.initialize(instance, definition);

        return instance;
    }

    /**
     * 执行流程
     */
    public ExecutionResult execute(FlowInstance instance, String input) {
        if (instance.isComplete()) {
            return ExecutionResult.completed(instance.getPath());
        }

        if (input != null) {
            try {
                executor.processInput(instance, input);
            } catch (IllegalArgumentException e) {
                return ExecutionResult.error(e.getMessage(), instance.getPath());
            }
        }

        if (instance.isComplete()) {
            return ExecutionResult.completed(instance.getPath());
        }

        return ExecutionResult.needInput(
            executor.getCurrentOptions(instance),
            instance.getPath()
        );
    }

    /**
     * 序列化流程实例
     */
    public String serialize(FlowInstance instance) {
        try {
            return objectMapper.writeValueAsString(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize flow instance", e);
        }
    }

    /**
     * 反序列化流程实例
     */
    public FlowInstance deserialize(String json) {
        try {
            return objectMapper.readValue(json, FlowInstance.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize flow instance", e);
        }
    }

    private String generateId() {
        return "flow_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=FlowEngineTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/engine/FlowEngine.java
git add .learning/routor/src/test/java/com/routor/engine/FlowEngineTest.java
git commit -m "feat: implement FlowEngine"
```

---

## Task 9: 添加完整流程测试

**Files:**
- Create: `src/test/java/com/routor/FullFlowTest.java`

- [ ] **Step 1: 编写完整流程测试**

```java
package com.routor;

import com.routor.engine.FlowEngine;
import com.routor.engine.FlowLoader;
import com.routor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整流程测试 - 验证用户需求的两个案例
 */
class FullFlowTest {
    private static final String COMPLEX_YAML = """
        login_flows:
          - name: default_login_flow
            type: LOGIN
            steps:
              - type: identify
                oneOf:
                  - identification: oauth
                  - identification: passkey
                  - identification: email
                    steps:
                      - type: authenticate
                        oneOf:
                          - authentication: primary_passkey
                          - authentication: primary_password
                            steps:
                              - type: authenticate
                                oneOf:
                                  - authentication: secondary_totp
                          - authentication: primary_oob_otp_email
                            steps:
                              - type: authenticate
                                oneOf:
                                  - authentication: secondary_totp
        """;

    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        FlowLoader loader = new FlowLoader();
        engine = new FlowEngine(loader.loadAll(COMPLEX_YAML));
    }

    @Test
    void case1_oauthDirectComplete() {
        // 案例 1: identification: oauth -> 直接完成
        FlowInstance flow = engine.create("default_login_flow");

        // 第一步：获取选项
        ExecutionResult result1 = engine.execute(flow, null);
        assertEquals(State.NEED_INPUT, result1.getState());
        assertEquals(3, result1.getOptions().size());

        // 选择 oauth
        ExecutionResult result2 = engine.execute(flow, "oauth");
        assertEquals(State.COMPLETED, result2.getState());
        assertEquals(1, result2.getPath().size());
        assertEquals("oauth", result2.getPath().get(0));
    }

    @Test
    void case2_emailPasswordTotp() {
        // 案例 2: email -> primary_password -> secondary_totp
        FlowInstance flow = engine.create("default_login_flow");

        // 第一步：identify
        engine.execute(flow, null);
        ExecutionResult result1 = engine.execute(flow, "email");
        assertEquals(State.NEED_INPUT, result1.getState());
        assertEquals("email", result1.getPath().get(0));

        // 第二步：authenticate - 选择 primary_password
        ExecutionResult result2 = engine.execute(flow, "primary_password");
        assertEquals(State.NEED_INPUT, result2.getState());
        assertEquals(2, result2.getPath().size());
        assertEquals("primary_password", result2.getPath().get(1));

        // 第三步：authenticate - 选择 secondary_totp
        ExecutionResult result3 = engine.execute(flow, "secondary_totp");
        assertEquals(State.COMPLETED, result3.getState());
        assertEquals(3, result3.getPath().size());
        assertEquals("secondary_totp", result3.getPath().get(2));

        // 验证完整路径
        assertEquals("email", result3.getPath().get(0));
        assertEquals("primary_password", result3.getPath().get(1));
        assertEquals("secondary_totp", result3.getPath().get(2));
    }

    @Test
    void case3_emailOobTotp() {
        // 案例 3: email -> primary_oob_otp_email -> secondary_totp
        FlowInstance flow = engine.create("default_login_flow");

        engine.execute(flow, null);
        engine.execute(flow, "email");
        ExecutionResult result = engine.execute(flow, "primary_oob_otp_email");
        assertEquals(State.NEED_INPUT, result.getState());

        ExecutionResult finalResult = engine.execute(flow, "secondary_totp");
        assertEquals(State.COMPLETED, finalResult.getState());
        assertEquals(3, finalResult.getPath().size());
    }

    @Test
    void shouldHandleInvalidSelection() {
        FlowInstance flow = engine.create("default_login_flow");

        engine.execute(flow, null);
        ExecutionResult result = engine.execute(flow, "invalid_option");

        assertEquals(State.ERROR, result.getState());
        assertNotNull(result.getMessage());
    }

    @Test
    void shouldSerializeAndDeserialize() {
        FlowInstance flow = engine.create("default_login_flow");
        engine.execute(flow, null);
        engine.execute(flow, "email");

        String serialized = engine.serialize(flow);
        assertNotNull(serialized);

        FlowInstance restored = engine.deserialize(serialized);
        assertNotNull(restored);
        assertEquals(flow.getFlowId(), restored.getFlowId());
        assertEquals(flow.getPath(), restored.getPath());
    }
}
```

- [ ] **Step 2: 运行完整测试**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -Dtest=FullFlowTest -q
```

Expected: All tests PASS

- [ ] **Step 3: 运行所有测试**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn test -q
```

Expected: All tests PASS (15+ tests)

- [ ] **Step 4: Commit**

```bash
git add .learning/routor/src/test/java/com/routor/FullFlowTest.java
git commit -m "test: add comprehensive full flow tests"
```

---

## Task 10: 添加示例应用程序

**Files:**
- Create: `src/main/java/com/routor/demo/FlowDemo.java`
- Create: `src/main/resources/flows.yaml`

- [ ] **Step 1: 创建示例 YAML 配置**

```yaml
login_flows:
  - name: default_login_flow
    type: LOGIN
    steps:
      - type: identify
        oneOf:
          - identification: oauth
          - identification: passkey
          - identification: email
            steps:
              - type: authenticate
                oneOf:
                  - authentication: primary_passkey
                  - authentication: primary_password
                    steps:
                      - type: authenticate
                        oneOf:
                          - authentication: secondary_totp
                  - authentication: primary_oob_otp_email
                    steps:
                      - type: authenticate
                        oneOf:
                          - authentication: secondary_totp
```

- [ ] **Step 2: 实现演示程序**

```java
package com.routor.demo;

import com.routor.engine.FlowEngine;
import com.routor.engine.FlowLoader;
import com.routor.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * 命令行演示程序
 */
public class FlowDemo {
    public static void main(String[] args) throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/flows.yaml"));
        FlowLoader loader = new FlowLoader();
        FlowEngine engine = new FlowEngine(loader.loadAll(yaml));

        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Routor Flow Executor Demo ===\n");
        System.out.println("可用流程:");
        System.out.println("  - default_login_flow\n");

        System.out.print("请输入流程名称 (默认: default_login_flow): ");
        String flowName = scanner.nextLine().trim();
        if (flowName.isEmpty()) {
            flowName = "default_login_flow";
        }

        FlowInstance flow = engine.create(flowName);
        System.out.println("\n流程已创建: " + flow.getFlowId());
        System.out.println("当前路径: []\n");

        ExecutionResult result;
        do {
            result = engine.execute(flow, null);

            if (result.isNeedInput()) {
                System.out.println("【" + result.getPath().size() + "】请选择:");
                for (Option opt : result.getOptions()) {
                    String hint = opt.isHasSubSteps() ? " (有子步骤)" : "";
                    System.out.println("  " + opt.getId() + hint);
                }

                System.out.print("\n输入选择: ");
                String input = scanner.nextLine().trim();
                result = engine.execute(flow, input);
            }

            System.out.println("当前路径: " + result.getPath());

            if (result.isError()) {
                System.out.println("错误: " + result.getMessage());
                System.out.println("请重新选择\n");
            }

        } while (!result.isCompleted() && !result.isError());

        if (result.isCompleted()) {
            System.out.println("\n✓ 流程完成!");
            System.out.println("最终路径: " + result.getPath());
        }

        scanner.close();
    }
}
```

- [ ] **Step 3: 验证演示程序编译**

```bash
cd /Users/ygr/Code/mygithub/authgear-server/.learning/routor
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add .learning/routor/src/main/java/com/routor/demo/FlowDemo.java
git add .learning/routor/src/main/resources/flows.yaml
git commit -m "feat: add demo application"
```

---

## 完成检查清单

- [ ] 所有 10 个任务已完成
- [ ] 所有测试通过 (`mvn test`)
- [ ] 代码编译成功 (`mvn compile`)
- [ ] 演示程序可运行

## 最终 Commit

```bash
git add .learning/routor/
git commit -m "feat: complete routor flow executor implementation

- Add model classes (State, Option, FlowDefinition, StepDefinition, BranchDefinition)
- Add runtime classes (StackFrame, FlowInstance, ExecutionResult)
- Implement FlowLoader for YAML parsing
- Implement StackExecutor for stack-based execution
- Implement FlowEngine as main API
- Add comprehensive tests covering all use cases
- Add demo application"
```
