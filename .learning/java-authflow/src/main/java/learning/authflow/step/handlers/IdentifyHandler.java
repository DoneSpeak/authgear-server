package learning.authflow.step.handlers;

import learning.authflow.core.ExecutionContext;
import learning.authflow.core.Session;
import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import lombok.Data;

/**
 * 识别步骤处理器
 */
public class IdentifyHandler implements StepHandler {

    @Override
    public StepType getType() {
        return StepType.IDENTIFY;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        IdentifyInput identifyInput = input.as(IdentifyInput.class);

        // 验证输入
        if (identifyInput.getIdentification() == null || identifyInput.getLoginId() == null) {
            throw new IllegalArgumentException("Missing required fields: identification or login_id");
        }

        // 保存到 Session 供后续步骤使用（通过 with 方法创建新实例）
        Session updatedSession = ctx.getSession()
            .withIdentification(identifyInput.getIdentification())
            .withLoginId(identifyInput.getLoginId());
        ctx.setSession(updatedSession);

        return StepResult.builder()
            .complete(true)
            .build();
    }

    @Data
    public static class IdentifyInput {
        private String identification;  // "phone", "email"
        private String loginId;
    }
}
