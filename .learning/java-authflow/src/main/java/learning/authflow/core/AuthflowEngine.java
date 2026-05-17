package learning.authflow.core;

import learning.authflow.exception.AcceptLoopLimitExceededException;
import learning.authflow.exception.IntentExecutionException;
import learning.authflow.exception.InvalidStateTokenException;
import learning.authflow.flowdef.FlowDefinition;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.StepDefinition;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.*;
import learning.authflow.intent.registry.IntentRegistry;
import learning.authflow.model.FlowType;
import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import learning.authflow.storage.SessionStorage;
import learning.authflow.storage.StateStorage;
import learning.authflow.step.registry.StepHandlerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;

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
    private final FlowDefinitionProvider flowDefinitionProvider;
    private final StepHandlerRegistry stepHandlerRegistry;
    private final SessionStorage sessionStorage;

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

        // 2. 创建运行时上下文（桥接旧 StepHandler 和新 Intent 架构）
        FlowContext context = FlowContext.from(flow, intentRegistry, stepHandlerRegistry, sessionStorage);

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
            ReactResult reactResult;
            try {
                reactResult = reactor.reactTo(context, input);
            } catch (Exception e) {
                // 保存当前状态（即使处理失败）
                flow.setStateToken(stateTokenManager.generateToken());
                stateStorage.createFlow(flow);
                // 重新抛出异常
                throw e;
            }

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
                    // 当前 Reactor 完成，重建栈以处理下一个步骤
                    // 继续循环执行下一步
                    continue;

                case NEED_INPUT:
                    // 需要输入，退出循环等待用户
                    break;

                case SAME_NODE:
                    // 留在当前 Node（如 OTP 重发）
                    continue;

                case ERROR:
                    // 发生错误，抛出自定义异常
                    Exception error = reactResult.getError();
                    throw new IntentExecutionException(
                        "Intent execution failed: " + error.getMessage(),
                        error);
            }

            // 其他情况退出循环
            break;
        }

        // 检查是否因为达到最大循环次数而退出
        if (loopCount >= MAX_LOOP) {
            throw new AcceptLoopLimitExceededException(
                "Accept loop exceeded maximum iterations (" + MAX_LOOP + ")");
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
        // 1. 获取流程定义
        FlowDefinition flowDef = flowDefinitionProvider.get(name);
        if (flowDef == null) {
            throw new IllegalArgumentException("Flow not found: " + name);
        }

        // 2. 创建流程实例
        FlowInstance flow = new FlowInstance();
        flow.setFlowId(idGenerator.generate());
        flow.setFlowType(FlowType.valueOf(type.toUpperCase()));
        flow.setFlowName(name);
        flow.setStateToken(stateTokenManager.generateToken());
        flow.setCurrentPath(new ArrayList<>());

        // 3. 初始化线性结构 - 预创建所有步骤节点
        initializeFlowNodes(flow, flowDef);

        // 4. 创建根 IntentNode（用于序列化保存）
        IntentNode rootNode = new IntentNode();
        rootNode.setKind("FlowRoot");
        rootNode.setParams(Map.of("type", type, "name", name));
        flow.setRootIntent(rootNode);

        // 5. 保存初始状态
        stateStorage.createFlow(flow);

        return flow;
    }

    /**
     * 获取流程的第一个步骤类型
     */
    private StepType getInitialStepType(FlowDefinition flowDef) {
        if (flowDef.getSteps() != null && !flowDef.getSteps().isEmpty()) {
            return flowDef.getSteps().get(0).getType();
        }
        return StepType.IDENTIFY; // 默认
    }

    /**
     * 初始化流程节点 - 预创建所有步骤
     */
    private void initializeFlowNodes(FlowInstance flow, FlowDefinition flowDef) {
        if (flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
            return;
        }

        // 为每个步骤定义创建节点
        for (int i = 0; i < flowDef.getSteps().size(); i++) {
            StepDefinition stepDef = flowDef.getSteps().get(i);
            FlowNode node = new FlowNode();
            node.setNodeId(String.valueOf(i));
            node.setType(NodeType.SIMPLE);
            node.setStepType(stepDef.getType());
            node.setCompleted(false);
            flow.getNodes().add(node);
        }

        // 设置当前节点为第一个未完成的节点
        flow.setCurrentNodeIndex(0);
    }
}
