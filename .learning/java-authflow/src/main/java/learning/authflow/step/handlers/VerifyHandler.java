package learning.authflow.step.handlers;

import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import lombok.Data;

/**
 * 验证步骤处理器（OTP验证等）
 */
public class VerifyHandler implements StepHandler {

    @Override
    public StepType getType() {
        return StepType.VERIFY;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        VerifyInput verifyInput = input.as(VerifyInput.class);

        // 验证OTP码
        if ("123456".equals(verifyInput.getCode())) {
            return StepResult.builder()
                .complete(true)
                .build();
        }

        // OTP错误，不完成步骤
        return StepResult.builder()
            .complete(false)
            .build();
    }

    @Data
    public static class VerifyInput {
        private String code;
    }
}
