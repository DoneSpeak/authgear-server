package learning.authflow.step.handlers;

import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import lombok.Data;

/**
 * 认证步骤处理器
 */
public class AuthenticateHandler implements StepHandler {

    @Override
    public StepType getType() {
        return StepType.AUTHENTICATE;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        AuthenticateInput authInput = input.as(AuthenticateInput.class);

        // 简化处理：假设认证成功
        return StepResult.builder()
            .complete(true)
            .build();
    }

    @Data
    public static class AuthenticateInput {
        private String authentication;  // "primary_password", "primary_oob_otp_sms"
        private int index;
        private String channel;  // "sms", "whatsapp"
        private String password;
    }
}
