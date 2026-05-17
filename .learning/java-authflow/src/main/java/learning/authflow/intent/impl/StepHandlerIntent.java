package learning.authflow.intent.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.core.Session;
import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.Intent;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import learning.authflow.storage.SessionStorage;
import lombok.RequiredArgsConstructor;

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
        // StepHandler 总是需要输入
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
        StepResult result = handler.handle(stepCtx, input);

        // 保存更新后的 Session
        sessionStorage.save(stepCtx.getSession());

        // 根据结果返回 ReactResult
        if (result.isComplete()) {
            // 标记当前节点完成
            markCurrentNodeComplete(context);
            // 推进到下一步
            context.advanceToNextStep();
            return ReactResult.complete();
        } else {
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
     * Step 输入模式
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
            return Map.of("stepType", stepType.name());
        }
    }
}
