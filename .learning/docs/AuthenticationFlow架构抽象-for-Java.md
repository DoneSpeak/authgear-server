# Authentication Flow 架构抽象 (for Java)

## 1. 核心概念

### 1.1 Flow（流程）
Flow 是认证流程的容器，包含唯一标识和状态。

```java
public class Flow {
    private String flowId;           // 流程唯一ID
    private String stateToken;       // 状态令牌（每次变更更新）
    private Intent intent;           // 流程意图（驱动流程的核心）
    private List<Node> nodes;        // 流程节点（执行历史）
}
```

### 1.2 Intent（意图）
Intent 是流程的驱动力，决定流程如何响应输入并前进。所有 Intent 必须实现 `InputReactor` 接口。

```java
public interface Intent extends Kinder, InputReactor {
    // Intent 是标记接口，组合了类型标识和输入响应能力
}

// 类型标识接口
public interface Kinder {
    String kind();  // 返回唯一类型标识，如 "IntentCreateAuthenticatorOOBOTP"
}
```

### 1.3 Node（节点）
Node 是流程执行的基本单元，有两种类型：
- **Simple Node**：简单的流程步骤
- **SubFlow Node**：嵌套的子流程

```java
public class Node {
    private NodeType type;           // SIMPLE 或 SUB_FLOW
    private NodeSimple simple;       // 简单节点内容
    private Flow subFlow;            // 子流程（当 type = SUB_FLOW）
}

public interface NodeSimple extends Kinder {
    // 简单节点标记接口
}
```

## 2. 简化算法流程

### 2.1 Accept 循环核心逻辑

```
┌─────────────────────────────────────────────────────────────────┐
│                         Accept 循环                              │
└─────────────────────────────────────────────────────────────────┘

开始
  │
  ▼
┌─────────────────┐
│ 查找InputReactor │ ◄─────────────────────────────────────┐
│ (Intent或Node)   │                                      │
└────────┬────────┘                                      │
         │                                               │
         ▼                                               │
┌─────────────────┐     否    ┌─────────────┐           │
│ 需要用户输入?    │ ────────► │   结束流程   │ ────────► │
│ (InputSchema)   │            │   (ErrEOF)  │           │
└────────┬────────┘            └─────────────┘           │
         │ 是                                           │
         ▼                                               │
┌─────────────────┐     失败   ┌─────────────┐           │
│  验证并解析输入  │ ────────► │  等待新输入  │ ────────► │
│  (MakeInput)    │            │ (ErrNoChange)│          │
└────────┬────────┘            └─────────────┘           │
         │ 成功                                           │
         ▼                                               │
┌─────────────────┐                                      │
│  执行ReactTo    │                                      │
│  (返回下一步)    │                                      │
└────────┬────────┘                                      │
         │                                               │
         ▼                                               │
┌─────────────────┐     替换   ┌─────────────┐           │
│   节点类型?      │ ────────► │  替换节点    │ ────────► │
│                 │            │(ErrReplace) │           │
└────────┬────────┘            └─────────────┘           │
         │ 新增                                            │
         ▼                                               │
┌─────────────────┐                                      │
│  追加Node到Flow  │ ─────────────────────────────────────┘
│  (appendNode)   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  执行RunEffect   │
│  (副作用立即执行) │
└────────┬────────┘
         │
         ▼
      继续循环
```

### 2.2 状态流转简化图

```
                    ┌─────────────┐
                    │   创建Flow   │
                    │(Intent驱动) │
                    └──────┬──────┘
                           │
                           ▼
              ┌────────────────────────┐
              │      Accept循环        │
              │  ┌──────────────────┐  │
              │  │ 1. CanReactTo    │  │
              │  │ 2. ReactTo       │  │
              │  │ 3. AppendNode    │  │
              │  │ 4. RunEffect     │  │
              │  └──────────────────┘  │
              └───────────┬────────────┘
                          │
            ┌─────────────┼─────────────┐
            ▼             ▼             ▼
      ┌─────────┐   ┌─────────┐   ┌─────────┐
      │等待输入  │   │ 完成    │   │ 子流程   │
      │(返回)   │   │(ErrEOF) │   │(SubFlow)│
      └─────────┘   └────┬────┘   └────┬────┘
                         │             │
                         ▼             │
                   ┌──────────┐         │
                   │应用副作用│ ◄───────┘
                   │(OnCommit)│ (子流程完成)
                   └────┬─────┘
                        │
                        ▼
                   ┌──────────┐
                   │  结束    │
                   └──────────┘
```

### 2.3 关键判断逻辑

```
FindInputReactor:
  ├─ 1. 检查最后一个 Node
  │     ├─ 是 Simple Node 且实现了 InputReactor? → 返回该 Node
  │     └─ 是 SubFlow Node? → 递归进入子流程
  │
  └─ 2. 检查 Intent
        └─ Intent 实现了 InputReactor → 返回 Intent

CanReactTo 决策:
  ├─ 返回 InputSchema → 需要用户输入（前端展示表单）
  ├─ 返回 null       → 不需要输入，可直接执行 ReactTo
  └─ 抛出 ErrEOF    → 流程已完成，无需进一步操作

ReactTo 决策:
  ├─ 返回 Node       → 追加新节点到流程
  ├─ 返回 SubFlow    → 启动嵌套子流程
  ├─ 抛出 ErrEOF     → 当前意图完成
  └─ 抛出 ErrSameNode → 状态改变但节点不变（停止循环）
```

## 3. 流程配置解析算法

### 3.1 配置结构说明

```yaml
# signup_flows 结构解析
signup_flows:
- name: default_signup_flow          # Flow 名称
  steps:                            # 步骤列表（按顺序执行）
  - name: setup_phone              # 步骤名称（可被 target_step 引用）
    type: identify                 # 步骤类型：识别身份
    one_of:                        # 分支选项（用户可选）
    - identification: phone         # 具体识别方式：手机号
  
  - type: create_authenticator    # 步骤类型：创建认证器
    one_of:
    - authentication: primary_oob_otp_sms  # 认证方式：短信验证码
      target_step: setup_phone      # 关键：从 setup_phone 步骤获取手机号
  
  - type: verify                   # 步骤类型：验证声明（如邮箱/手机验证）
    target_step: setup_phone      # 验证 setup_phone 中的手机号
```

### 3.2 配置生成算法

```
生成 Flow 配置的算法:
─────────────────────────────────────────────────────────────
输入: AppConfig cfg
输出: AuthenticationFlow

1. 创建 Flow 对象
   flow = new Flow()
   flow.name = "default_signup_flow"

2. 遍历配置生成 Steps
   for each identityType in cfg.authentication.identities:
       step = generateIdentifyStep(identityType)
       flow.steps.add(step)
       
       # 根据身份类型生成后续认证步骤
       if identityType == phone:
           authStep = generateCreateAuthenticatorStep(
               authentication = primary_oob_otp_sms,
               targetStep = step.name    # 引用前一步
           )
           flow.steps.add(authStep)
           
           # 添加验证步骤
           verifyStep = generateVerifyStep(targetStep = step.name)
           flow.steps.add(verifyStep)

3. 返回 flow
─────────────────────────────────────────────────────────────
```

### 3.3 target_step 解析算法

target_step 是 Flow 配置的关键机制，用于在步骤间传递数据。

```
解析 target_step 的算法:
─────────────────────────────────────────────────────────────
输入: Flows flows, String targetStepName
输出: IdentityInfo 或 AuthenticatorInfo

1. 从根流程查找目标步骤
   targetStepFlow = findTargetStep(flows.root, targetStepName)

2. 获取目标步骤的 Intent
   intent = targetStepFlow.intent

3. 检查 Intent 类型并提取信息
   if intent instanceof IntentLoginFlowStepAuthenticateTarget:
       info = intent.getIdentityInfo(ctx, deps, flows)
       return info
   
   if intent instanceof IntentSignupFlowStepIdentify:
       # 从已执行的节点中查找选择的身份
       milestone = findMilestoneInCurrentFlow(
           flows, 
           MilestoneDidSelectIdentity.class
       )
       return milestone.getIdentityInfo()

4. 抛出错误：InvalidTargetStep
─────────────────────────────────────────────────────────────
```

### 3.4 步骤执行流程示例

以 signup_flow 为例，展示 Flow 执行的完整流程：

```
用户注册流程执行时序:
─────────────────────────────────────────────────────────────

Step 1: identify (setup_phone)
├─ IntentSignupFlowStepIdentify
├─ CanReactTo: 返回 InputSchemaTakePhone (需要用户输入手机号)
├─ 用户输入: phone = "+86138xxxxxxxx"
├─ ReactTo: 
│  ├─ 创建 Identity Spec (phone)
│  ├─ 创建 NodeDoCreateIdentity → 实际创建 _auth_identity 记录
│  └─ 返回 NodeDidSelectIdentity (里程碑)
└─ 状态: Flow.nodes = [NodeDidSelectIdentity]

Step 2: create_authenticator (primary_oob_otp_sms)
├─ IntentCreateAuthenticatorOOBOTP
├─ 解析 target_step = setup_phone
│  └─ 从 Step 1 的 NodeDidSelectIdentity 获取手机号
├─ CanReactTo: 
│  ├─ 发现用户已有该手机号的认证器? 
│  ├─ 否 → 需要验证 → 返回 null (启动验证子流程)
│  └─ 是 → 跳过验证
├─ ReactTo: 启动子流程 IntentVerifyClaim
│  └─ SubFlow: 发送短信验证码 → 用户输入 → 验证
├─ 验证通过后:
│  └─ 创建 NodeDoCreateAuthenticator → 创建 _auth_authenticator 记录
└─ 状态: Flow.nodes += [NodeVerifyClaim, NodeDoCreateAuthenticator]

Step 3: verify (setup_phone)
├─ IntentSignupFlowStepVerify
├─ 解析 target_step = setup_phone
├─ 检查手机号的声明验证状态
└─ 如果未验证，启动验证流程（类似 Step 2）

Step 4: identify (setup_email)
├─ 同 Step 1，但处理 email
└─ 用户输入: email = "user@example.com"

Step 5: create_authenticator (primary_oob_otp_email)
├─ 同 Step 2，但处理邮箱验证码
└─ 创建邮箱对应的 OOB OTP 认证器

Step 6: create_authenticator (primary_password)
├─ IntentCreateAuthenticatorPassword
├─ CanReactTo: 返回 InputSchemaSetupPassword
├─ 用户输入: password = "********"
├─ ReactTo:
│  ├─ 密码强度检查
│  ├─ 创建 NodeDoCreateAuthenticator → 创建密码认证器
│  └─ 返回 NodeDidSelectAuthenticator
└─ Flow 完成 (ErrEOF)
─────────────────────────────────────────────────────────────
```

### 3.5 login_flow 与 signup_flow 的区别

```
Login Flow 关键差异:
─────────────────────────────────────────────────────────────

1. identify 步骤后连接 authenticate（而非 create_authenticator）
   
   Login Flow:
   identify → authenticate → [可能的其他 authenticate] → 完成
   
   Signup Flow:
   identify → create_authenticator → verify → [更多步骤] → 完成

2. authenticate 步骤从已有认证器中选择
   
   getAuthenticationOptionsForLogin() 算法:
   ├─ 查询用户所有 Identity
   ├─ 查询用户所有 Authenticator
   ├─ 根据 step.one_of 配置过滤可用选项
   │  ├─ primary_oob_otp_sms → 查找手机号的 OOB 认证器
   │  ├─ primary_password → 查找密码认证器
   │  └─ ...
   └─ 返回选项列表给前端

3. 认证验证流程
   User 选择 primary_oob_otp_sms:
   ├─ IntentUseAuthenticatorOOBOTP
   ├─ CanReactTo: 返回 InputSchemaTakeOOBOTPCode
   ├─ 用户输入验证码
   ├─ ReactTo: 验证 OTP
   └─ 成功 → 返回 MilestoneDidAuthenticate
─────────────────────────────────────────────────────────────
```

## 4. 核心接口

### 3.1 InputReactor（输入响应器）
这是整个流程引擎的核心接口，定义了流程如何响应输入。

```java
public interface InputReactor {
    /**
     * 检查是否可以响应输入
     * @return InputSchema（输入模式），返回 null 表示可以响应 nil 输入
     */
    InputSchema canReactTo(Context ctx, Dependencies deps, Flows flows);

    /**
     * 响应输入并返回下一步
     * @return ReactToResult（通常是 Node 或 NodeWithDelayedOneTimeFunction）
     */
    ReactToResult reactTo(Context ctx, Dependencies deps, Flows flows, Input input);
}
```

### 2.2 InputSchema（输入模式）
定义输入的验证和解析规则。

```java
public interface InputSchema {
    JSONPointer getJsonPointer();              // 指向流程配置的 JSON 指针
    AuthenticationFlowObject getFlowRootObject();  // 流程根对象
    SchemaBuilder schemaBuilder();             // JSON Schema 构建器
    Input makeInput(Context ctx, byte[] rawMessage);  // 解析输入
}
```

### 2.3 Input（输入标记）
```java
public interface Input {
    // 标记接口，标识这是一个输入类型
}
```

## 4. 流程执行机制

### 4.1 Accept 循环详细实现
流程引擎的核心执行循环，持续处理输入直到流程完成。

```java
public class FlowEngine {
    private static final int MAX_LOOP = 100;

    public void accept(Context ctx, Dependencies deps, Flows flows, 
                       AcceptResult result, InputFunction inputFn) {
        int loopCount = 0;
        boolean changed = false;

        while (true) {
            loopCount++;
            if (loopCount > MAX_LOOP) {
                throw new IllegalStateException("流程循环次数超过限制");
            }

            // 1. 查找可以响应输入的组件
            FindInputReactorResult reactor = findInputReactor(ctx, deps, flows);

            // 2. 获取输入（根据 schema 验证和解析）
            Input input = inputFn.apply(reactor.getInputSchema());

            // 3. 响应输入，获取下一步
            ReactToResult reactResult = reactor.getInputReactor()
                .reactTo(ctx, deps, reactor.getFlows(), input);

            // 4. 处理特殊错误（如 ErrIncompatibleInput, ErrSameNode, ErrReplaceNode）

            // 5. 将新节点追加到流程
            appendNode(ctx, deps, reactor.getFlows(), reactResult.getNode());
            changed = true;

            // 如果返回 ErrEOF，流程结束
            // 如果返回 ErrNoChange 且未改变，流程暂停等待新输入
        }
    }
}
```

### 3.2 查找 InputReactor
优先查找最后一个节点，如果没有则查找 Intent。

```java
public FindInputReactorResult findInputReactor(Context ctx, Dependencies deps, Flows flows) {
    // 1. 先检查最后一个节点
    if (!flows.getNearest().getNodes().isEmpty()) {
        Node lastNode = getLastNode(flows.getNearest());
        FindInputReactorResult result = findInputReactorForNode(ctx, deps, flows, lastNode);
        if (result != null) return result;
        // 如果节点返回 ErrEOF，继续检查 Intent
    }

    // 2. 检查 Intent
    InputSchema schema = flows.getNearest().getIntent().canReactTo(ctx, deps, flows);
    if (schema == null || schema != null) {
        return new FindInputReactorResult(flows, flows.getNearest().getIntent(), schema);
    }

    throw new ErrEOF();
}
```

## 5. Milestone（里程碑）模式

Milestone 用于标记流程中的关键节点，支持在流程树中查找特定状态。

```java
// 里程碑标记接口
public interface Milestone {
    // 标记接口
}

// 查找当前流程中的最后一个里程碑
public static <T extends Milestone> MilestoneResult<T> findMilestoneInCurrentFlow(
        Flows flows, Class<T> milestoneType) {
    Flow flow = flows.getNearest();
    T found = null;
    Flows newFlows = flows;

    // 逆序遍历节点，找最后一个匹配的里程碑
    for (int i = flow.getNodes().size() - 1; i >= 0; i--) {
        Node node = flow.getNodes().get(i);
        if (node.getType() == NodeType.SIMPLE) {
            if (milestoneType.isInstance(node.getSimple())) {
                found = milestoneType.cast(node.getSimple());
                newFlows = flows.replace(flow);
                break;
            }
        } else if (node.getType() == NodeType.SUB_FLOW) {
            if (milestoneType.isInstance(node.getSubFlow().getIntent())) {
                found = milestoneType.cast(node.getSubFlow().getIntent());
                newFlows = flows.replace(node.getSubFlow());
                break;
            }
        }
    }

    return new MilestoneResult<>(found, newFlows, found != null);
}

// 示例：创建认证器里程碑
public interface MilestoneFlowCreateAuthenticator extends Milestone {
    MilestoneDoCreateAuthenticator getMilestoneDoCreateAuthenticator(Flows flows);
}

// 示例：选择认证方式里程碑
public interface MilestoneDidSelectAuthenticationMethod extends Milestone {
    AuthenticationFlowAuthentication getSelectedAuthenticationMethod();
}
```

## 6. Effect（副作用）系统

Effect 用于处理流程中的副作用，如数据库写入、发送邮件等。

```java
// 副作用接口
public interface Effect {
    // 标记接口
}

// 运行时执行的副作用
public interface RunEffect extends Effect {
    void doNotCallThisDirectly(Context ctx, Dependencies deps);
}

// 提交时执行的副作用
public interface OnCommitEffect extends Effect {
    void applyOnCommit(Context ctx, Dependencies deps);
}

// 获取副作用的接口
public interface EffectGetter {
    List<Effect> getEffects(Context ctx, Dependencies deps, Flows flows);
}

// 节点追加时自动执行 RunEffect
public void appendNode(Context ctx, Dependencies deps, Flows flows, Node node) {
    flows.getNearest().getNodes().add(node);

    // 遍历节点，执行所有 RunEffect
    traverseNode(node, (nodeSimple, flow) -> {
        if (nodeSimple instanceof EffectGetter) {
            List<Effect> effects = ((EffectGetter) nodeSimple)
                .getEffects(ctx, deps, flows.replace(flow));
            for (Effect eff : effects) {
                if (eff instanceof RunEffect) {
                    ((RunEffect) eff).doNotCallThisDirectly(ctx, deps);
                }
            }
        }
        return null;
    });
}
```

## 7. Flows（流程上下文）

Flows 封装了流程查询和导航的能力。

```java
public class Flows {
    private Flow root;       // 根流程
    private Flow nearest;    // 当前（最近）流程

    // 替换当前流程上下文
    public Flows replace(Flow newFlow) {
        return new Flows(root, newFlow);
    }

    // 从 Intent 到 Node 遍历
    public static void traverseIntentFromNodeToRoot(
            IntentVisitor visitor, Flow flow, NodeOrIntent currentNode) {
        // 实现从当前节点向上遍历到根 Intent
    }

    // 遍历整个流程树
    public static void traverseFlow(FlowTraverser traverser, Flow flow) {
        // 递归遍历所有节点和子流程
    }
}
```

## 8. 特殊错误类型

```java
// 流程结束
public class ErrEOF extends RuntimeException {
}

// 无变化（等待新输入）
public class ErrNoChange extends RuntimeException {
}

// 输入不兼容
public class ErrIncompatibleInput extends RuntimeException {
}

// 同一节点（停止循环）
public class ErrSameNode extends RuntimeException {
}

// 替换节点
public class ErrReplaceNode extends RuntimeException {
}

// 暂停并重试
public class ErrPauseAndRetryAccept extends RuntimeException {
}

// 切换流程
public class ErrorSwitchFlow extends RuntimeException {
    private FlowReference flowReference;
    private Input syntheticInput;
}

// 重写流程
public class ErrorRewriteFlow extends RuntimeException {
    private Intent intent;
    private List<Node> nodes;
    private Input syntheticInput;
}
```

## 9. Intent 实现示例

### 8.1 注册 Intent
```java
public class IntentRegistry {
    private static final Map<String, Supplier<Intent>> registry = new HashMap<>();

    public static void register(Intent intent) {
        String kind = intent.kind();
        if (registry.containsKey(kind)) {
            throw new IllegalStateException("重复的 Intent 类型: " + kind);
        }
        Class<?> clazz = intent.getClass();
        registry.put(kind, () -> {
            try {
                return (Intent) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
```

### 8.2 完整 Intent 示例
```java
public class IntentCreateAuthenticatorOOBOTP implements 
        Intent, 
        Milestone,
        MilestoneFlowSelectAuthenticationMethod,
        MilestoneDidSelectAuthenticationMethod,
        MilestoneFlowCreateAuthenticator,
        MilestoneSwitchToExistingUser {

    private JSONPointer jsonPointer;
    private String userId;
    private boolean isUpdatingExistingUser;
    private AuthenticationFlowAuthentication authentication;

    @Override
    public String kind() {
        return "IntentCreateAuthenticatorOOBOTP";
    }

    @Override
    public void milestone() {
        // 标记方法
    }

    @Override
    public InputSchema canReactTo(Context ctx, Dependencies deps, Flows flows) {
        // 1. 查找流程对象
        AuthenticationFlowObject flowRootObject = findNearestFlowObjectInFlow(deps, flows, this);
        AuthenticationFlowObject objectForOneOf = FlowObject(flowRootObject, jsonPointer);

        // 2. 获取配置
        OneOf oneOf = this.oneOf(objectForOneOf);
        boolean verificationRequired = oneOf.isVerificationRequired();
        String targetStepName = oneOf.getTargetStepName();

        // 3. 查找里程碑状态
        MilestoneResult<MilestoneDidSelectAuthenticator> m = 
            findMilestoneInCurrentFlow(flows, MilestoneDidSelectAuthenticator.class);
        boolean authenticatorSelected = m.isFound();

        MilestoneResult<MilestoneVerifyClaim> verifyClaim = 
            findMilestoneInCurrentFlow(flows, MilestoneVerifyClaim.class);
        boolean claimVerifiedInThisFlow = verifyClaim.isFound();

        MilestoneResult<MilestoneDoCreateAuthenticator> created = 
            findMilestoneInCurrentFlow(flows, MilestoneDoCreateAuthenticator.class);
        boolean createdInThisFlow = created.isFound();

        // 4. 根据状态决定返回什么 InputSchema
        if (!authenticatorSelected) {
            if (targetStepName != null && !targetStepName.isEmpty()) {
                return null;  // 不需要输入
            }
            return new InputSchemaTakeOOBOTPTarget(flowRootObject, jsonPointer, ...);
        }

        if (shouldVerifyInThisFlow && !claimVerifiedInThisFlow) {
            return null;  // 进入验证流程
        }

        if (!createdInThisFlow) {
            return null;  // 创建认证器
        }

        throw new ErrEOF();  // 流程结束
    }

    @Override
    public ReactToResult reactTo(Context ctx, Dependencies deps, Flows flows, Input input) {
        // 类似 canReactTo 的状态检查...

        if (!authenticatorSelected) {
            if (targetStepName != null && !targetStepName.isEmpty()) {
                // 从目标步骤获取目标
                String oobOTPTarget = getTargetFromStep(ctx, deps, flows, targetStepName);
                return newDidSelectAuthenticatorNode(ctx, deps, oobOTPTarget);
            }

            // 处理用户输入
            if (input instanceof InputTakeOOBOTPTarget) {
                InputTakeOOBOTPTarget inputTake = (InputTakeOOBOTPTarget) input;
                String target = inputTake.getTarget();
                return newDidSelectAuthenticatorNode(ctx, deps, target);
            }
        }

        if (shouldVerifyInThisFlow && !claimVerifiedInThisFlow) {
            // 启动验证子流程
            return newSubFlow(new IntentVerifyClaim(...));
        }

        if (!createdInThisFlow) {
            // 返回创建节点
            return newNodeSimple(new NodeDoCreateAuthenticator(authenticatorInfo));
        }

        throw new ErrIncompatibleInput();
    }

    private Node newDidSelectAuthenticatorNode(Context ctx, Dependencies deps, String target) {
        AuthenticatorInfo info = createAuthenticator(ctx, deps, userId, authentication, target);
        return new NodeSimple(new NodeDidSelectAuthenticator(info));
    }

    private Node newSubFlow(Intent intent) {
        return new Node(NodeType.SUB_FLOW, null, new Flow(intent));
    }

    private Node newNodeSimple(NodeSimple simple) {
        return new Node(NodeType.SIMPLE, simple, null);
    }
}
```

## 10. 配置生成模式

```java
public class LoginFlowConfigGenerator {
    
    public AuthenticationFlowLoginFlow generate(AppConfig cfg) {
        AuthenticationFlowLoginFlow flow = new AuthenticationFlowLoginFlow();
        flow.setName("default");
        flow.setSteps(new ArrayList<>());

        // 步骤 1：识别
        flow.getSteps().add(generateIdentifyStep(cfg));

        // 步骤 2：检查账户状态
        flow.getSteps().add(generateCheckAccountStatusStep());

        // 步骤 3：终止其他会话
        flow.getSteps().add(generateTerminateOtherSessionsStep());

        // 可选步骤：提示创建 Passkey
        generatePromptCreatePasskeyStep(cfg).ifPresent(flow.getSteps()::add);

        return flow;
    }

    private AuthenticationFlowLoginFlowStep generateIdentifyStep(AppConfig cfg) {
        AuthenticationFlowLoginFlowStep step = new AuthenticationFlowLoginFlowStep();
        step.setName("step_identify");
        step.setType(AuthenticationFlowLoginFlowStepType.IDENTIFY);
        step.setOneOf(new ArrayList<>());

        for (IdentityType identityType : cfg.getAuthentication().getIdentities()) {
            switch (identityType) {
                case LOGIN_ID:
                    step.getOneOf().addAll(generateIdentifyLoginID(cfg));
                    break;
                case OAUTH:
                    step.getOneOf().addAll(generateIdentifyOAuth(cfg));
                    break;
                case PASSKEY:
                    step.getOneOf().addAll(generateIdentifyPasskey(cfg));
                    break;
                case LDAP:
                    step.getOneOf().addAll(generateIdentifyLDAP(cfg));
                    break;
            }
        }

        return step;
    }
}
```

## 11. Service 层

```java
public class AuthenticationFlowService {
    private Dependencies deps;
    private FlowStore store;
    private ServiceDatabase database;

    public ServiceOutput createNewFlow(Context ctx, PublicFlow publicFlow, 
                                        SessionOptions sessionOptions) {
        // 1. 验证
        validateNewFlow(publicFlow, sessionOptions);

        // 2. 创建会话
        Session session = new Session(sessionOptions);
        ctx = session.makeContext(ctx, deps);
        store.createSession(ctx, session);

        // 3. 创建流程
        return createNewFlowWithSession(ctx, publicFlow, session);
    }

    private ServiceOutput createNewFlowWithSession(Context ctx, PublicFlow publicFlow, 
                                                   Session session) {
        Flow flow = new Flow(session.getFlowId(), publicFlow);

        // Accept 循环
        boolean shouldAccept = true;
        while (shouldAccept) {
            shouldAccept = false;
            AcceptResult acceptResult = new AcceptResult();
            Flows flows = new Flows(flow);

            database.readOnly(ctx, () -> {
                flowEngine.accept(ctx, deps, flows, acceptResult, null);
                FlowAction flowAction = getFlowAction(ctx, session, flow);
                return null;
            });

            processAcceptResult(ctx, session, flows, acceptResult);

            if (isErrPauseAndRetryAccept()) {
                shouldAccept = true;
            }
        }

        // 持久化流程状态
        store.createFlow(ctx, flow);

        return new ServiceOutput(session, flow, flowAction);
    }

    public ServiceOutput feedInput(Context ctx, String stateToken, byte[] rawMessage) {
        Flow flow = store.getFlowByStateToken(ctx, stateToken);
        Session session = store.getSession(ctx, flow.getFlowId());
        ctx = session.makeContext(ctx, deps);

        // 类似 createNewFlow 的 Accept 循环
        // ...

        return new ServiceOutput(session, flow, flowAction);
    }
}
```

## 12. 设计要点总结

| 概念 | 作用 | Java 实现建议 |
|------|------|--------------|
| **Flow** | 流程容器 | POJO + Builder |
| **Intent** | 流程驱动力 | 接口 + 抽象类 |
| **Node** | 执行单元 | 枚举类型 + 组合 |
| **InputReactor** | 输入响应 | 策略模式 |
| **Milestone** | 状态标记 | 接口继承 |
| **Effect** | 副作用处理 | 命令模式 |
| **Flows** | 流程导航 | 不可变对象 |
| **Accept** | 执行循环 | while + 状态机 |
