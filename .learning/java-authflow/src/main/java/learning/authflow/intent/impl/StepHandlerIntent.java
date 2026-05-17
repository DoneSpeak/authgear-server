package learning.authflow.intent.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.core.FlowNode;
import learning.authflow.core.Session;
import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.Intent;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.milestone.Milestone;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import learning.authflow.storage.SessionStorage;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * StepHandler 桥接 Intent。
 * 包装旧的 StepHandler，使其能在新的 Intent 架构中工作。
 */
@RequiredArgsConstructor
public class StepHandlerIntent implements Intent {

    private final StepType stepType;
    private final StepHandler handler;
    private final SessionStorage sessionStorage;
    private final String flowId;

    @Override
    public String getKind() {
        return "StepHandler:" + stepType.name();
    }

    @Override
    public InputSchema canReactTo(FlowContext context) {
        // 检查当前步骤是否已完成
        int currentIndex = context.getFlow().getCurrentNodeIndex();
        if (currentIndex >= 0 && currentIndex < context.getFlow().getNodes().size()) {
            FlowNode node = context.getFlow().getNodes().get(currentIndex);
            if (node.isCompleted()) {
                // 当前步骤已完成，返回 EOF (null)
                return null;
            }
        }
        // 检查是否已经尝试过处理（通过里程碑）
        if (context.hasMilestone(StepHandlerAttemptedMilestone.class)) {
            // 已经尝试过处理，说明 Handler 返回了 complete(false)
            // 现在应该等待新输入，返回不同的 schema 表示需要下一阶段输入
            return new StepWaitingSchema(stepType);
        }
        // 首次调用，返回标准 schema
        return new StepInputSchema(stepType);
    }

    @Override
    public ReactResult reactTo(FlowContext context, AuthflowInput input) {
        // 创建 StepContext（兼容旧代码）
        Session session = sessionStorage.getOrCreate(flowId);
        StepContext stepCtx = StepContext.builder()
            .flowId(flowId)
            .flowType(context.getFlow().getFlowType())
            .flowName(context.getFlow().getFlowName())
            .currentNode(null) // 暂不设置
            .currentNodeIndex(context.getFlow().getCurrentNodeIndex())
            .session(session)
            .build();

        // 调用旧的 StepHandler
        StepResult result;
        try {
            result = handler.handle(stepCtx, input);
        } catch (Exception e) {
            // 如果 handler 抛出异常，保存 session 并重新抛出
            sessionStorage.save(stepCtx.getSession());
            throw e;
        }

        // 保存更新后的 Session
        sessionStorage.save(stepCtx.getSession());

        // 根据结果返回 ReactResult
        if (result.isComplete()) {
            // 标记当前节点完成
            markCurrentNodeComplete(context);
            // 推进到下一步（关键：这会更新 currentNodeIndex 并重建 Intent 栈）
            context.advanceToNextStep();
            return ReactResult.complete();
        } else {
            // 步骤未完成，记录已尝试过
            context.addMilestone(new StepHandlerAttemptedMilestone());
            return ReactResult.needInput();
        }
    }

    private void markCurrentNodeComplete(FlowContext context) {
        int currentIndex = context.getFlow().getCurrentNodeIndex();
        if (currentIndex >= 0 && currentIndex < context.getFlow().getNodes().size()) {
            context.getFlow().getNodes().get(currentIndex).setCompleted(true);
        }
    }

    /**
     * 标记 StepHandler 已尝试处理的里程碑
     */
    public static class StepHandlerAttemptedMilestone implements Milestone, Serializable {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 标准步骤输入模式（首次调用）
     */
    @RequiredArgsConstructor
    public static class StepInputSchema implements InputSchema {
        private final StepType stepType;

        @Override
        public String getType() {
            return "step:" + stepType.name().toLowerCase();
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("stepType", stepType.name(), "stage", "initial");
        }
    }

    /**
     * 步骤等待模式（已尝试过，等待下一阶段输入）
     */
    @RequiredArgsConstructor
    public static class StepWaitingSchema implements InputSchema {
        private final StepType stepType;

        @Override
        public String getType() {
            return "step:" + stepType.name().toLowerCase() + ":waiting";
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of(
                "stepType", stepType.name(),
                "stage", "waiting",
                "message", "Please provide additional input (OTP/code)"
            );
        }
    }
}
