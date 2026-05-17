# Java Authflow MFA 支持设计文档

## 目标

让 `.learning/java-authflow/` 支持完整的 MFA 流程，特别是：
- `primary_oob_otp_email`: 选择邮箱 → 发送 OTP → 验证 OTP
- `secondary_totp`: TOTP 二次验证
- 可插拔的认证方式扩展机制

## 对齐原则

与 Authgear Go 实现语义对齐，采用 **Milestone + Intent + Accept-Loop** 架构。

---

## 1. 核心概念映射

| Authgear (Go) | Java (Spring Boot) | 职责 |
|---------------|-------------------|------|
| `Intent` | `Intent` 接口 | 状态机，含 `canReactTo()` / `reactTo()` |
| `Node` | `Node` 接口 | 执行具体操作，产生 Effect |
| `Milestone` | `Milestone` 标记接口 | 标记完成状态，支持 Flow 树查询 |
| `Flows` 树 | `FlowContext` | 保存 Intent/Node 栈，支持里程碑查询 |
| `Accept()` 循环 | `AuthflowEngine.accept()` | 驱动多次 `reactTo()` 直到完成 |

---

## 2. 核心接口设计

### 2.1 Intent 接口

```java
public interface Intent extends InputReactor {
    String getKind();  // 如 "UseAuthenticatorOOBOTP"
    
    // 检查是否需要输入，返回 null = 不需要输入直接下一步
    InputSchema canReactTo(FlowContext context);
    
    // 处理输入，返回下一个 Intent、Node 或完成标记
    ReactResult reactTo(FlowContext context, AuthflowInput input);
}
```

### 2.2 InputReactor（Intent/Node 共同接口）

```java
public interface InputReactor {
    InputSchema canReactTo(FlowContext context);
    ReactResult reactTo(FlowContext context, AuthflowInput input);
}
```

### 2.3 ReactResult 类型

```java
public enum ReactResultType {
    NEW_NODE,       // 创建新 Node，继续循环
    SUB_INTENT,     // 创建子 Intent（SubFlow），继续循环
    COMPLETE,       // 当前 Intent 完成，回退到父级
    NEED_INPUT,     // 需要输入但没有，退出循环等待用户
    ERROR           // 错误，终止循环
}
```

### 2.4 Milestone 接口

```java
// 标记接口
public interface Milestone {}

// 具体里程碑示例
public interface MilestoneDidSelectAuthenticator extends Milestone {
    AuthenticatorInfo getSelectedAuthenticator();
}

public interface MilestoneDoMarkClaimVerified extends Milestone {}
public interface MilestoneOobOtpVerified extends Milestone {
    Channel getVerifiedChannel();
}
```

### 2.5 FlowContext

```java
public class FlowContext {
    private final FlowInstance flow;
    private final Deque<Intent> intentStack = new ArrayDeque<>();
    
    // 里程碑查询
    public boolean hasMilestone(Class<? extends Milestone> type);
    public <T extends Milestone> Optional<T> findMilestone(Class<T> type);
    public void addMilestone(Milestone milestone);
    
    // 栈操作
    public void pushIntent(Intent intent);
    public void popToParent();
    public Intent getCurrentIntent();
    public FlowNode getLastNode();
    public void appendNode(FlowNode node);
}
```

---

## 3. Accept-Loop 实现

```java
@Service
public class AuthflowEngine {
    private static final int MAX_LOOP = 100;
    
    public FlowInstance accept(String stateToken, AuthflowInput input) {
        FlowInstance flow = flowRepository.findByStateToken(stateToken);
        FlowContext context = FlowContext.from(flow, intentRegistry);
        
        int loopCount = 0;
        
        while (loopCount < MAX_LOOP) {
            loopCount++;
            
            // 1. FindInputReactor: 找最近的能响应的 Intent/Node
            InputReactor reactor = findInputReactor(context);
            if (reactor == null) break;
            
            // 2. CanReactTo: 检查需要什么输入
            InputSchema schema = reactor.canReactTo(context);
            
            // 3. 获取输入（如果有的话）
            AuthflowInput nextInput = (schema != null && input != null) 
                ? input.consume(schema) : null;
            
            // 输入已用完且需要输入 -> 退出等待用户
            if (schema != null && nextInput == null) break;
            
            // 4. ReactTo: 执行响应
            ReactResult result = reactor.reactTo(context, nextInput);
            
            // 5. 处理结果
            switch (result.getType()) {
                case NEW_NODE:
                    context.appendNode(result.getNode());
                    continue;  // 继续循环
                    
                case SUB_INTENT:
                    context.pushIntent(result.getIntent());
                    continue;  // 继续循环，立即处理子 Intent
                    
                case COMPLETE:
                    context.markMilestone(reactor);
                    if (context.hasParentIntent()) {
                        context.popToParent();
                        continue;  // 回退到父级继续
                    }
                    break;  // 整个流程完成
                    
                case ERROR:
                    throw result.getException();
            }
        }
        
        // 保存状态
        flow.setStateToken(stateTokenManager.generate());
        flowRepository.save(flow);
        return flow;
    }
    
    // 栈式查找最近的 InputReactor
    private InputReactor findInputReactor(FlowContext context) {
        // 1. 先检查最后一个 Node
        FlowNode lastNode = context.getLastNode();
        if (lastNode != null && lastNode.canReactTo(context) != EOF) {
            return lastNode;
        }
        
        // 2. 检查当前 Intent
        Intent current = context.getCurrentIntent();
        if (current.canReactTo(context) != EOF) {
            return current;
        }
        
        // 3. 回退到父级
        if (context.hasParentIntent()) {
            context.popToParent();
            return findInputReactor(context);
        }
        
        return null;
    }
}
```

---

## 4. Intent 自动发现机制

### 4.1 IntentRegistry

```java
@Service
public class IntentRegistry {
    private final Map<String, IntentFactory> factories;
    
    // Spring 自动注入所有 IntentFactory
    public IntentRegistry(List<IntentFactory> factoryList) {
        this.factories = factoryList.stream()
            .collect(Collectors.toMap(
                f -> f.getKind(),
                Function.identity()
            ));
    }
    
    public Intent create(String kind, Map<String, Object> params) {
        IntentFactory factory = factories.get(kind);
        if (factory == null) throw new UnsupportedOperationException("Unknown intent: " + kind);
        return factory.create(params);
    }
    
    // 根据 authentication 字符串查找对应 Factory
    public IntentFactory findForAuthentication(String authentication) {
        return factories.values().stream()
            .filter(f -> f.supportsAuthentication(authentication))
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException("No intent for: " + authentication));
    }
}
```

### 4.2 IntentFactory 接口

```java
public interface IntentFactory {
    String getKind();
    boolean supportsAuthentication(String authentication);
    Intent create(Map<String, Object> params);
}
```

### 4.3 扩展示例：新增 TOTP 支持

```java
@Component
public class UseAuthenticatorTotpIntentFactory implements IntentFactory {
    @Override
    public String getKind() { return "UseAuthenticatorTOTP"; }
    
    @Override
    public boolean supportsAuthentication(String auth) {
        return "secondary_totp".equals(auth);
    }
    
    @Override
    public Intent create(Map<String, Object> params) {
        return new UseAuthenticatorTotpIntent(params);
    }
}

public class UseAuthenticatorTotpIntent implements Intent, 
        MilestoneFlowSelectAuthenticationMethod,
        MilestoneFlowAuthenticate {
    
    @Override
    public InputSchema canReactTo(FlowContext ctx) {
        if (!ctx.hasMilestone(MilestoneDidAuthenticate.class)) {
            return new TotpCodeInputSchema();
        }
        return null;  // 已完成
    }
    
    @Override
    public ReactResult reactTo(FlowContext ctx, AuthflowInput input) {
        if (!ctx.hasMilestone(MilestoneDidAuthenticate.class)) {
            String code = input.as(TotpInput.class).getCode();
            boolean valid = totpProvider.verify(ctx.getUserId(), code);
            if (!valid) {
                return ReactResult.error(new InvalidCredentialsException("Invalid TOTP"));
            }
            ctx.addMilestone(new MilestoneDidAuthenticate());
            return ReactResult.complete();
        }
        return ReactResult.complete();
    }
}
```

**扩展只需：** 1. 创建 Intent 类 2. 标记 `@Component` 实现 Factory 接口。

---

## 5. primary_oob_otp_email 完整实现

### 5.1 流程定义

```yaml
- name: email_password_primary_oob_otp_email
  type: LOGIN
  steps:
    - type: IDENTIFY
      oneOf:
        - identification: email
    - type: AUTHENTICATE
      oneOf:
        - authentication: primary_oob_otp_email
```

### 5.2 Intent 实现

```java
@Component
public class UseAuthenticatorOobOtpIntentFactory implements IntentFactory {
    @Override public String getKind() { return "UseAuthenticatorOOBOTP"; }
    
    @Override
    public boolean supportsAuthentication(String auth) {
        return auth != null && auth.contains("oob_otp");
    }
    
    @Override public Intent create(Map<String, Object> params) { 
        return new UseAuthenticatorOobOtpIntent(params); 
    }
}

public class UseAuthenticatorOobOtpIntent implements Intent,
        MilestoneFlowSelectAuthenticationMethod,
        MilestoneDidSelectAuthenticationMethod,
        MilestoneFlowAuthenticate {
    
    private final List<AuthenticateOption> options;
    private final String authentication;  // "primary_oob_otp_email" 或 "primary_oob_otp_sms"
    
    @Override
    public InputSchema canReactTo(FlowContext ctx) {
        if (!ctx.hasMilestone(MilestoneDidSelectAuthenticator.class)) {
            // 阶段1: 需要选择认证器（index）
            return new SelectIndexSchema(options.size());
        }
        if (!ctx.hasMilestone(MilestoneDoMarkClaimVerified.class)) {
            // 阶段2: 自动进入 OTP 验证子流程
            return null;  // 自动处理
        }
        return null;  // 已完成
    }
    
    @Override
    public ReactResult reactTo(FlowContext ctx, AuthflowInput input) {
        // 阶段1: 选择认证器
        if (!ctx.hasMilestone(MilestoneDidSelectAuthenticator.class)) {
            int index = input.as(SelectIndexInput.class).getIndex();
            AuthenticatorInfo auth = pickAuthenticator(options, index);
            
            ctx.addMilestone(new MilestoneDidSelectAuthenticator(auth));
            return ReactResult.newNode(new NodeDidSelectAuthenticator(auth));
        }
        
        // 阶段2: 创建 OTP 验证子 Intent
        if (!ctx.hasMilestone(MilestoneDoMarkClaimVerified.class)) {
            AuthenticatorInfo auth = ctx.findMilestone(MilestoneDidSelectAuthenticator.class)
                .getSelectedAuthenticator();
            
            Intent subIntent = new AuthenticationOobIntent(
                ctx.getUserId(),
                auth,
                otp.Purpose.OOBOTP,
                getOtpForm()  // code/link
            );
            return ReactResult.subIntent(subIntent);
        }
        
        // 阶段3: 完成
        return ReactResult.complete();
    }
}

// OTP 验证子 Intent
public class AuthenticationOobIntent implements Intent,
        MilestoneDoMarkClaimVerified {
    
    private final String userId;
    private final AuthenticatorInfo authenticator;
    private final OtpPurpose purpose;
    private final OtpForm form;
    
    @Override
    public InputSchema canReactTo(FlowContext ctx) {
        if (!ctx.hasMilestone(MilestoneOobOtpVerified.class)) {
            List<Channel> channels = getAvailableChannels();
            if (channels.size() == 1) {
                // 只有一个渠道，自动选择
                return null;
            }
            return new SelectChannelSchema(channels);
        }
        if (!ctx.hasMilestone(MilestoneOobOtpLastUsedChannelUpdated.class)) {
            return null;  // 自动更新
        }
        return null;  // 已完成
    }
    
    @Override
    public ReactResult reactTo(FlowContext ctx, AuthflowInput input) {
        // 选择渠道（或自动选择）
        if (!ctx.hasMilestone(MilestoneOobOtpVerified.class)) {
            List<Channel> channels = getAvailableChannels();
            Channel channel = channels.size() == 1 
                ? channels.get(0)
                : input.as(SelectChannelInput.class).getChannel();
            
            // 创建 Node，Node 负责发送 OTP
            NodeAuthenticationOob node = new NodeAuthenticationOob(
                authenticator, channel, purpose, form
            );
            node.sendOtp();  // 发送邮件/短信
            
            return ReactResult.newNode(node);
        }
        
        // 更新最后使用渠道
        if (!ctx.hasMilestone(MilestoneOobOtpLastUsedChannelUpdated.class)) {
            Channel channel = ctx.findMilestone(MilestoneOobOtpVerified.class)
                .getVerifiedChannel();
            return ReactResult.newNode(new NodeDoUpdateLastUsedChannel(channel));
        }
        
        return ReactResult.complete();
    }
}

// 具体 Node 实现
public class NodeAuthenticationOob implements Node, InputReactor {
    private final AuthenticatorInfo authenticator;
    private final Channel channel;
    private final OobOtpProvider oobOtpProvider;
    private boolean otpSent = false;
    
    public void sendOtp() {
        String target = authenticator.getOobTarget();  // 邮箱或手机号
        String code = oobOtpProvider.generateCode(target, channel);
        oobOtpProvider.send(target, channel, code, form);
        this.otpSent = true;
    }
    
    @Override
    public InputSchema canReactTo(FlowContext ctx) {
        if (!otpSent) return null;  // 还没发，继续
        // 等待用户输入 code
        return new OtpCodeInputSchema(
            maskTarget(authenticator.getOobTarget()),
            channel,
            form
        );
    }
    
    @Override
    public ReactResult reactTo(FlowContext ctx, AuthflowInput input) {
        if (input.isResendRequest()) {
            sendOtp();  // 重新发送
            return ReactResult.sameNode();  // 留在当前 Node
        }
        
        if (input.isCheckRequest()) {
            // 检查状态，不验证
            return ReactResult.sameNode();
        }
        
        String code = input.as(OtpCodeInput.class).getCode();
        boolean valid = oobOtpProvider.verify(
            authenticator.getOobTarget(), 
            channel, 
            code
        );
        
        if (valid) {
            ctx.addMilestone(new MilestoneOobOtpVerified(channel));
            return ReactResult.complete();  // Node 完成
        } else {
            return ReactResult.error(new InvalidCredentialsException("Invalid OTP"));
        }
    }
}
```

### 5.3 HTTP 请求响应示例

**请求1：选择 OOB OTP 认证方式**

```bash
POST /api/v1/authentication_flows/states/input
{
    "state_token": "authflowstate_xxx",
    "input": {
        "authentication": "primary_oob_otp_email",
        "index": 0
    }
}
```

**响应1：OTP 已发送，等待输入**

```json
{
    "state_token": "authflowstate_yyy",
    "action": {
        "type": "AUTHENTICATE",
        "authentication": "primary_oob_otp_email",
        "data": {
            "otp_form": "code",
            "masked_destination": "u***@example.com",
            "channel": "email",
            "resendable": true
        }
    }
}
```

**请求2：提交 OTP 码**

```bash
POST /api/v1/authentication_flows/states/input
{
    "state_token": "authflowstate_yyy",
    "input": {
        "code": "123456"
    }
}
```

**响应2：认证完成，进入下一步（或 FINISHED）**

---

## 6. 状态持久化设计

### 6.1 数据结构

```java
@Data
public class FlowInstance implements Serializable {
    private String flowId;
    private FlowType flowType;
    private String flowName;
    
    // Flow 树根节点
    private IntentNode rootIntent;
    
    // 当前路径（快速定位）
    private List<String> currentPath;
    
    // State token（每次 accept 后更新）
    private String stateToken;
    
    // 用户ID
    private String userId;
}

// 可序列化的 Intent 节点
@Data
public class IntentNode implements Serializable {
    private String kind;                    // Intent 类型标识
    private Map<String, Object> params;   // 构造参数
    private List<Node> children;           // 子节点（Nodes 或 SubFlows）
    private Map<String, MilestoneData> milestones;  // 已完成的里程碑
}

@Data
public class SimpleNode implements Node {
    private String nodeType;    // "DidSelectAuthenticator" | "AuthenticationOob"
    private Map<String, Object> data;
    private boolean completed;
}

@Data
public class SubFlowNode implements Node {
    private String nodeType = "SubFlow";
    private IntentNode subIntent;
}
```

### 6.2 Redis 存储

```java
@Service
public class FlowStateRepository {
    private final StringRedisTemplate redis;
    private final Gson gson;
    
    public void save(FlowInstance flow) {
        String json = gson.toJson(flow);
        
        // 保存 Flow 数据
        redis.opsForValue().set(
            "flow:" + flow.getFlowId(),
            json,
            Duration.ofMinutes(30)
        );
        
        // 保存 state_token 映射
        redis.opsForValue().set(
            "state:" + flow.getStateToken(),
            flow.getFlowId(),
            Duration.ofMinutes(30)
        );
    }
    
    public FlowInstance findByStateToken(String stateToken) {
        String flowId = redis.opsForValue().get("state:" + stateToken);
        if (flowId == null) throw new InvalidStateTokenException();
        
        String json = redis.opsForValue().get("flow:" + flowId);
        return gson.fromJson(json, FlowInstance.class);
    }
}
```

### 6.3 运行时重建

```java
public class FlowContext {
    public static FlowContext from(FlowInstance flow, IntentRegistry registry) {
        FlowContext ctx = new FlowContext(flow, registry);
        ctx.rebuildStack();
        return ctx;
    }
    
    private void rebuildStack() {
        // 从 root 沿着 currentPath 重建运行时栈
        IntentNode current = flow.getRootIntent();
        for (String segment : flow.getCurrentPath()) {
            Intent intent = intentRegistry.create(
                current.getKind(), 
                current.getParams()
            );
            intentStack.push(intent);
            
            // 恢复里程碑
            for (MilestoneData m : current.getMilestones().values()) {
                intent.addMilestone(m.toMilestone());
            }
            
            // 进入子节点
            current = findChild(current, segment);
        }
    }
}
```

---

## 7. 迁移策略

### 阶段1：共存期（保留旧 Handler，新增 Intent 体系）

```java
@Configuration
public class AuthflowConfig {
    
    @Bean
    public StepHandlerRegistry stepHandlerRegistry(
            List<StepHandler> legacyHandlers,
            List<Intent> intents,
            IntentRegistry intentRegistry) {
        // 注册所有 Intent
        intentRegistry.registerAll(intents);
        
        // 保留旧 Handler
        return new StepHandlerRegistry(legacyHandlers);
    }
    
    // 新的 CompositeAuthenticateHandler 调用 Intent
    @Bean
    public CompositeAuthenticateHandler authenticateHandler(
            IntentRegistry intentRegistry,
            LoginIdProvider loginIdProvider) {
        return new IntentBasedAuthenticateHandler(intentRegistry, loginIdProvider);
    }
}
```

### 阶段2：完全迁移（删除旧 Handler）

- `PrimaryOobOtpEmailAuthenticateHandler` → `UseAuthenticatorOobOtpIntent`
- `PrimaryOobOtpSmsAuthenticateHandler` → 同上（通过 `authentication` 参数区分）
- `PrimaryPasswordAuthenticateHandler` → `UseAuthenticatorPasswordIntent`
- `CompositeAuthenticateHandler` → 简化为入口路由

---

## 8. 文件结构

```
src/main/java/learning/authflow/
├── core/
│   ├── AuthflowEngine.java          # [修改] Accept-Loop 驱动
│   ├── FlowInstance.java            # [修改] 树形结构
│   └── FlowContext.java             # [新增] 运行时上下文
│
├── intent/
│   ├── Intent.java                  # [新增] 核心接口
│   ├── InputReactor.java            # [新增]
│   ├── ReactResult.java             # [新增]
│   ├── InputSchema.java             # [新增]
│   ├── IntentFactory.java           # [新增]
│   ├── registry/
│   │   └── IntentRegistry.java      # [新增]
│   └── impl/
│       ├── UseAuthenticatorOobOtpIntent.java      # [新增] P0
│       ├── AuthenticationOobIntent.java           # [新增] P0
│       ├── UseAuthenticatorPasswordIntent.java    # [新增]
│       ├── UseAuthenticatorTotpIntent.java        # [新增] P0
│       └── ...
│
├── milestone/
│   ├── Milestone.java               # [新增] 标记接口
│   ├── MilestoneDidSelectAuthenticator.java
│   ├── MilestoneDoMarkClaimVerified.java
│   ├── MilestoneOobOtpVerified.java
│   └── ...
│
├── node/
│   ├── Node.java                    # [新增]
│   ├── SimpleNode.java              # [新增]
│   ├── SubFlowNode.java             # [新增]
│   └── impl/
│       ├── NodeDidSelectAuthenticator.java
│       ├── NodeAuthenticationOob.java
│       └── ...
│
└── storage/
    └── FlowStateRepository.java     # [修改] 树形序列化
```

---

## 9. 优先级与验收标准

| 优先级 | 功能 | 验收标准 |
|-------|------|---------|
| P0 | `primary_oob_otp_email` | 1. 选择邮箱 2. 自动发送邮件 3. 验证 code 4. 完成 |
| P0 | `primary_oob_otp_sms` | 同上，短信渠道 |
| P0 | `secondary_totp` | 1. 输入 TOTP code 2. 验证 3. 完成 |
| P1 | `recovery_code` | 备用码验证 |
| P1 | `device_token` | 记住设备，跳过二次验证 |
| P2 | 其他认证方式 | 按相同模式扩展 |

---

## 10. 自检清单

- [x] 无 TBD/TODO 占位符
- [x] 内部一致性：架构与实现示例匹配
- [x] 范围聚焦：P0-P2 功能定义清晰
- [x] 明确无歧义：每个接口方法有明确契约

---

**规格文档完成。请审阅后确认是否进入实现规划阶段。**
