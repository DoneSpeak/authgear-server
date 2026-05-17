package learning.authflow.step.handlers;

import com.google.gson.JsonObject;
import learning.authflow.core.StepContext;
import learning.authflow.exception.InvalidCredentialsException;
import learning.authflow.input.AuthflowInput;
import learning.authflow.provider.OobOtpProvider;
import learning.authflow.step.StepResult;
import lombok.Data;

/**
 * 主 OOB OTP Email 认证处理器
 * 处理 authentication: "primary_oob_otp_email"
 *
 * 两阶段流程：
 * 1. 用户提交 authentication + index → 发送 OTP，返回等待状态
 * 2. 用户提交 code/otp → 验证 OTP，完成认证
 */
public class PrimaryOobOtpEmailAuthenticateHandler extends AuthenticateHandler {
    private final OobOtpProvider oobOtpProvider;

    public PrimaryOobOtpEmailAuthenticateHandler(OobOtpProvider oobOtpProvider) {
        this.oobOtpProvider = oobOtpProvider;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        String userId = ctx.getSession().userId();
        String loginId = ctx.getSession().loginId();

        // 解析输入
        JsonObject json = input.as(JsonObject.class);

        // 检查是否有 otp/code 字段（第二阶段的验证）
        String otp = null;
        if (json.has("otp")) {
            otp = json.get("otp").getAsString();
        } else if (json.has("code")) {
            otp = json.get("code").getAsString();
        }

        // 第一阶段：没有 OTP，发送 OTP
        if (otp == null || otp.isEmpty()) {
            // 获取 index（选择哪个邮箱）
            int index = 0; // 默认第一个
            if (json.has("index")) {
                index = json.get("index").getAsInt();
            }

            // 发送 OTP（实际项目中这里会调用邮件服务）
            // 模拟发送 OTP
            String generatedOtp = oobOtpProvider.generateOtp(loginId, "email");
            System.out.println("[OTP] Sending OTP " + generatedOtp + " to " + loginId);

            // 返回需要输入 OTP 的状态（不完成步骤）
            return StepResult.builder()
                .complete(false) // 不完成，等待用户输入 OTP
                .build();
        }

        // 第二阶段：有 OTP，验证
        boolean valid = oobOtpProvider.verifyOtp(loginId, "email", otp);
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
        private String code; // 兼容字段
        private int index;
    }
}
