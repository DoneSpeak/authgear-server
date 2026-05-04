package learning.authflow.step.handlers;

import learning.authflow.core.StepContext;
import learning.authflow.exception.InvalidCredentialsException;
import learning.authflow.input.AuthflowInput;
import learning.authflow.provider.OobOtpProvider;
import learning.authflow.step.StepResult;
import lombok.Data;

/**
 * 主 OOB OTP SMS 认证处理器
 * 处理 authentication: "primary_oob_otp_sms"
 */
public class PrimaryOobOtpSmsAuthenticateHandler extends AuthenticateHandler {
    private final OobOtpProvider oobOtpProvider;

    public PrimaryOobOtpSmsAuthenticateHandler(OobOtpProvider oobOtpProvider) {
        this.oobOtpProvider = oobOtpProvider;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        String userId = ctx.getSession().userId();
        String loginId = ctx.getSession().loginId();

        OtpInput authInput = input.as(OtpInput.class);
        String otp = authInput.getOtp();

        if (otp == null || otp.isEmpty()) {
            throw new InvalidCredentialsException("OTP is required");
        }

        boolean valid = oobOtpProvider.verifyOtp(loginId, "sms", otp);
        if (!valid) {
            throw new InvalidCredentialsException("Invalid OTP");
        }

        return StepResult.builder()
            .complete(true)
            .userId(userId)
            .build();
    }

    @Data
    public static class OtpInput {
        private String otp;
        private int index;
    }
}
