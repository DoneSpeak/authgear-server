package com.liteflow.auth.engine;

import com.liteflow.auth.builder.LiteFlowChainBuilder;
import com.liteflow.auth.exception.FlowNotFoundException;
import com.liteflow.auth.exception.PauseExecutionException;
import com.liteflow.auth.model.BranchDefinition;
import com.liteflow.auth.model.ExecutionResult;
import com.liteflow.auth.model.FlowDefinition;
import com.liteflow.auth.model.Option;
import com.liteflow.auth.parser.FlowDefinitionParser;
import com.liteflow.auth.state.FlowExecutionState;
import com.liteflow.auth.state.StateManager;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流程执行引擎
 * 核心类，负责创建和执行流程
 */
@Slf4j
@Component
public class FlowExecutionEngine implements SmartInitializingSingleton {

    @Autowired
    private FlowExecutor flowExecutor;

    @Autowired
    private LiteFlowChainBuilder chainBuilder;

    @Autowired
    private StateManager stateManager;

    @Autowired
    private FlowDefinitionParser parser;

    private Map<String, FlowDefinition> flowDefinitions;

    /**
     * 所有单例 bean 初始化完成后执行
     * 确保 LiteFlow 组件已扫描注册后再构建 chains
     */
    @Override
    public void afterSingletonsInstantiated() {
        log.info("初始化流程执行引擎...");

        try {
            // 给 LiteFlow 一点时间来完成组件扫描
            Thread.sleep(100);

            // 1. 加载 YAML 文件
            ClassPathResource resource = new ClassPathResource("login_flows.yml");
            try (InputStream is = resource.getInputStream()) {
                flowDefinitions = parser.parse(is);
            }

            log.info("加载了 {} 个流程定义", flowDefinitions.size());

            // 2. 为每个流程构建 LiteFlow chains
            for (FlowDefinition definition : flowDefinitions.values()) {
                chainBuilder.buildFlowChains(definition);
                log.info("构建流程 '{}' 的 chains 完成", definition.getName());
            }

            log.info("流程执行引擎初始化完成");
        } catch (Exception e) {
            log.error("初始化流程执行引擎失败", e);
            throw new RuntimeException("初始化流程执行引擎失败", e);
        }
    }

    /**
     * 创建流程实例
     */
    public ExecutionResult create(String flowName) {
        FlowDefinition definition = flowDefinitions.get(flowName);
        if (definition == null) {
            throw new FlowNotFoundException(flowName);
        }

        String flowId = stateManager.generateFlowId();
        log.info("创建流程实例 - flowName: {}, flowId: {}", flowName, flowId);

        // 初始化状态
        FlowExecutionState state = FlowExecutionState.create(flowId, flowName, flowName);
        stateManager.saveState(state);

        // 执行第一步（获取第一个可选项）
        return executeFirstStep(state);
    }

    /**
     * 执行流程（带用户选择）
     */
    public ExecutionResult execute(String flowId, String userChoice) {
        FlowExecutionState state = stateManager.loadState(flowId);
        if (state == null) {
            throw new IllegalStateException("流程不存在或已过期: " + flowId);
        }

        if (state.isCompleted()) {
            return ExecutionResult.completed(flowId, state.getPath());
        }

        log.info("执行流程 - flowId: {}, 用户选择: {}", flowId, userChoice);

        // 1. 添加选择到路径
        stateManager.addChoice(flowId, userChoice);

        // 2. 设置用户选择到上下文
        stateManager.setUserChoice(flowId, userChoice);

        // 3. 执行 LiteFlow
        return executeLiteFlow(state, userChoice);
    }

    /**
     * 获取当前状态
     */
    public ExecutionResult getState(String flowId) {
        FlowExecutionState state = stateManager.loadState(flowId);
        if (state == null) {
            throw new IllegalStateException("流程不存在或已过期: " + flowId);
        }

        if (state.isCompleted()) {
            return ExecutionResult.completed(flowId, state.getPath());
        }

        return ExecutionResult.needInput(flowId, state.getCurrentOptions(), state.getPath());
    }

    /**
     * 执行第一步（获取初始可选项）
     */
    private ExecutionResult executeFirstStep(FlowExecutionState state) {
        String flowName = state.getFlowName();
        FlowDefinition definition = flowDefinitions.get(flowName);

        if (definition.getSteps() == null || definition.getSteps().isEmpty()) {
            stateManager.updateCompleted(state.getFlowId(), new ArrayList<>());
            return ExecutionResult.completed(state.getFlowId(), new ArrayList<>());
        }

        // 获取第一步的可选项
        var firstStep = definition.getSteps().get(0);
        if (firstStep.hasOneOf()) {
            List<Option> options = firstStep.getOneOf().stream()
                    .map(b -> Option.fromBranch(b, "identification"))
                    .toList();

            // 尝试执行第一个 chain 到第一个暂停点
            try {
                LiteflowResponse response = flowExecutor.execute2Resp(flowName, state.getFlowId());

                // 如果成功执行且没有抛出 PauseExecutionException，说明流程已完成
                if (response.isSuccess()) {
                    stateManager.updateCompleted(state.getFlowId(), new ArrayList<>());
                    return ExecutionResult.completed(state.getFlowId(), new ArrayList<>());
                }
            } catch (PauseExecutionException e) {
                // 遇到暂停，保存状态
                stateManager.updateNeedInput(
                        state.getFlowId(),
                        e.getCurrentChainId(),
                        e.getCurrentNodeId(),
                        e.getOptions(),
                        new ArrayList<>()
                );
                return ExecutionResult.needInput(state.getFlowId(), e.getOptions(), new ArrayList<>());
            } catch (Exception e) {
                if (e.getCause() instanceof PauseExecutionException) {
                    PauseExecutionException pause = (PauseExecutionException) e.getCause();
                    stateManager.updateNeedInput(
                            state.getFlowId(),
                            pause.getCurrentChainId(),
                            pause.getCurrentNodeId(),
                            pause.getOptions(),
                            new ArrayList<>()
                    );
                    return ExecutionResult.needInput(state.getFlowId(), pause.getOptions(), new ArrayList<>());
                }
                throw new RuntimeException("执行流程失败", e);
            }

            // 如果执行没有暂停，返回初始选项
            stateManager.updateNeedInput(
                    state.getFlowId(),
                    flowName,
                    null,
                    options,
                    new ArrayList<>()
            );
            return ExecutionResult.needInput(state.getFlowId(), options, new ArrayList<>());
        } else {
            // 没有分支，直接执行
            return executeLiteFlow(state, null);
        }
    }

    /**
     * 执行 LiteFlow
     */
    private ExecutionResult executeLiteFlow(FlowExecutionState state, String userChoice) {
        String flowName = state.getFlowName();
        String flowId = state.getFlowId();

        try {
            // 使用 request data 传递用户选择
            LiteflowResponse response = flowExecutor.execute2Resp(flowName, flowId, userChoice);

            if (!response.isSuccess()) {
                String message = response.getMessage() != null ? response.getMessage() : "执行失败";
                return ExecutionResult.error(flowId, message);
            }

            // 检查是否成功完成（没有抛出 PauseExecutionException）
            if (response.isSuccess()) {
                // 流程成功完成，没有更多暂停点
                stateManager.updateCompleted(flowId, state.getPath());
                return ExecutionResult.completed(flowId, state.getPath());
            }

            // 流程没有暂停但也没有成功，可能有问题
            String message = response.getMessage() != null ? response.getMessage() : "执行状态不明确";
            log.warn("流程执行后状态不明确 - flowId: {}, message: {}", flowId, message);
            return ExecutionResult.error(flowId, message);

        } catch (PauseExecutionException e) {
            // 遇到暂停点，保存状态
            stateManager.updateNeedInput(
                    flowId,
                    e.getCurrentChainId(),
                    e.getCurrentNodeId(),
                    e.getOptions(),
                    state.getPath()
            );
            return ExecutionResult.needInput(flowId, e.getOptions(), state.getPath());

        } catch (Exception e) {
            // 检查是否包装了 PauseExecutionException
            if (e.getCause() instanceof PauseExecutionException) {
                PauseExecutionException pause = (PauseExecutionException) e.getCause();
                stateManager.updateNeedInput(
                        flowId,
                        pause.getCurrentChainId(),
                        pause.getCurrentNodeId(),
                        pause.getOptions(),
                        state.getPath()
                );
                return ExecutionResult.needInput(flowId, pause.getOptions(), state.getPath());
            }

            log.error("执行流程失败 - flowId: {}", flowId, e);
            return ExecutionResult.error(flowId, "执行失败: " + e.getMessage());
        }
    }
}
