package learning.authflow.step.handlers;

import learning.authflow.core.StepContext;
import learning.authflow.exception.InvalidCredentialsException;
import learning.authflow.input.AuthflowInput;
import learning.authflow.provider.PasswordAuthenticatorProvider;
import learning.authflow.step.StepResult;
import lombok.Data;

/**
 * 主密码认证处理器
 * 处理 authentication: "primary_password"
 */
public class PrimaryPasswordAuthenticateHandler extends AuthenticateHandler {
    private final PasswordAuthenticatorProvider passwordProvider;

    public PrimaryPasswordAuthenticateHandler(PasswordAuthenticatorProvider passwordProvider) {
        this.passwordProvider = passwordProvider;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        // 从 Session 获取 userId（使用 record accessor 方法）
        String userId = ctx.getSession().userId();
        if (userId == null) {
            throw new InvalidCredentialsException("User ID not found in session");
        }

        PasswordInput authInput = input.as(PasswordInput.class);

        String password = authInput.getPassword();
        if (password == null || password.isEmpty()) {
            throw new InvalidCredentialsException("Password is required");
        }

        boolean valid = passwordProvider.verifyPassword(userId, password);
        if (!valid) {
            throw new InvalidCredentialsException("Invalid password");
        }

        return StepResult.builder()
            .complete(true)
            .userId(userId)
            .build();
    }

    @Data
    public static class PasswordInput {
        private String password;
    }
}
