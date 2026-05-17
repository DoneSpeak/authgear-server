package learning.authflow.core;

import learning.authflow.exception.InvalidStateTokenException;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.*;
import learning.authflow.intent.registry.IntentRegistry;
import learning.authflow.storage.StateStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 认证流程引擎。
 * 使用 Accept-Loop 机制驱动 Intent 执行，直到需要用户输入或流程完成。
 */
@Service
@RequiredArgsConstructor
public class AuthflowEngine {

    /** 最大循环次数，防止无限循环 */
    private static final int MAX_LOOP = 100;

    /** EOF 标记，表示 Intent/Node 已完成 */
    private static final InputSchema EOF = null;

    private final StateStorage stateStorage;
    private final IntentRegistry intentRegistry;
    private final StateTokenManager stateTokenManager;
    private final IdGenerator idGenerator;

    /**
     * 执行 Accept 循环，处理输入并推进流程。
     *
     * @param stateToken 当前状态令牌
     * @param input 用户输入（可能为 null，表示自动推进）
     * @return 更新后的流程实例
     * @throws InvalidStateTokenException 如果 state token 无效
     */
    public FlowInstance accept(String stateToken, AuthflowInput input) {
        // 1. 获取流程实例
        FlowInstance flow = stateStorage.getFlowByStateToken(stateToken);
        if (flow == null) {
            throw new InvalidStateTokenException("Invalid or expired state token: " + stateToken);
        }

        // 2. 创建运行时上下文
        FlowContext context = FlowContext.from(flow, intentRegistry);

        // 3. Accept-Loop 主循环
        int loopCount = 0;

        while (loopCount < MAX_LOOP) {
            loopCount++;

            // 3.1 查找最近的 InputReactor（Node 优先，然后 Intent，然后父级）
            AcceptResult result = findInputReactor(context);
            if (result == null) {
                // 没有能响应的 Reactor，流程完成
                break;
            }

            InputReactor reactor = result.getReactor();
            InputSchema schema = result.getSchema();

            // 3.2 如果需要输入但没有，退出循环等待用户
            if (schema != null && input == null) {
                break; // 等待用户输入
            }

            // 3.3 执行 ReactTo
            ReactResult reactResult = reactor.reactTo(context, input);

            // 3.4 处理结果
            switch (reactResult.getType()) {
                case NEW_NODE:
                    // 创建新 Node，继续循环
                    context.appendNode(reactResult.getNode());
                    continue;

                case SUB_INTENT:
                    // 创建子 Intent，继续循环（立即处理子 Intent）
                    context.pushIntent(reactResult.getSubIntent());
                    continue;

                case COMPLETE:
                    // 当前 Reactor 完成，如果有父级 Intent 则回退
                    if (context.hasParentIntent()) {
                        context.popToParent();
                        continue;
                    }
                    // 没有父级，整个流程完成
                    break;

                case NEED_INPUT:
                    // 需要输入，退出循环等待用户
                    break;

                case SAME_NODE:
                    // 留在当前 Node（如 OTP 重发）
                    continue;

                case ERROR:
                    // 发生错误，抛出异常
                    throw new RuntimeException(reactResult.getError());
            }

            // 其他情况退出循环
            break;
        }

        // 4. 更新 state token 并保存
        flow.setStateToken(stateTokenManager.generateToken());
        stateStorage.createFlow(flow);

        return flow;
    }

    /**
     * 查找最近的能响应的 InputReactor。
     * 策略：检查当前 Intent，如果返回 EOF 则递归检查父级。
     *
     * @param context 流程上下文
     * @return AcceptResult 包含 reactor 和 schema，如果没有则返回 null
     */
    private AcceptResult findInputReactor(FlowContext context) {
        // 1. 检查当前 Intent
        Intent currentIntent = context.getCurrentIntent();
        if (currentIntent != null) {
            InputSchema schema = currentIntent.canReactTo(context);
            if (schema != EOF) {
                return new AcceptResult(currentIntent, schema);
            }
            // Intent 返回 EOF，可能需要回退到父级
        }

        // 2. 如果有父级 Intent，回退并递归查找
        if (context.hasParentIntent()) {
            context.popToParent();
            return findInputReactor(context);
        }

        // 没有能响应的 Reactor 了
        return null;
    }

    /**
     * 创建新流程。
     *
     * @param type 流程类型（如 "login", "signup"）
     * @param name 流程定义名称
     * @return 新创建的流程实例
     */
    public FlowInstance create(String type, String name) {
        // TODO: 实现创建新流程的逻辑
        // 需要与 FlowDefinitionProvider 集成
        throw new UnsupportedOperationException("create() not yet implemented");
    }
}
