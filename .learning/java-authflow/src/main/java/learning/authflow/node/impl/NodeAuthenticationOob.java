package learning.authflow.node.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.core.FlowNode;
import learning.authflow.exception.InvalidCredentialsException;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.InputReactor;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.intent.impl.OtpCodeInput;
import learning.authflow.intent.impl.OtpCodeInputSchema;
import learning.authflow.model.AuthenticatorInfo;
import learning.authflow.model.Channel;
import learning.authflow.model.OtpForm;
import learning.authflow.model.OtpPurpose;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * OOB OTP 认证节点
 * 负责发送 OTP 和验证 OTP
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeAuthenticationOob extends FlowNode implements InputReactor {
    private final AuthenticatorInfo authenticator;
    private final Channel channel;
    private final OtpPurpose purpose;
    private final OtpForm form;
    private boolean otpSent = false;

    public NodeAuthenticationOob(AuthenticatorInfo authenticator, Channel channel,
                                  OtpPurpose purpose, OtpForm form) {
        this.authenticator = authenticator;
        this.channel = channel;
        this.purpose = purpose;
        this.form = form;
        setType(learning.authflow.model.NodeType.SIMPLE);
    }

    @Override
    public InputSchema canReactTo(FlowContext context) {
        if (!otpSent) {
            sendOtp();
            otpSent = true;
        }
        // 等待用户输入 OTP code
        return new OtpCodeInputSchema(maskTarget(authenticator.getOobTarget()), channel);
    }

    @Override
    public ReactResult reactTo(FlowContext context, AuthflowInput input) {
        // 处理重发请求 - 需要检查 input 是否有 isResendRequest 方法
        // 由于 AuthflowInput 接口只有 as 方法，我们通过检查特定类型来处理
        // TODO: 更优雅的重发请求处理

        // 验证 OTP
        String code = input.as(OtpCodeInput.class).getCode();
        boolean valid = verifyOtp(code);

        if (valid) {
            setCompleted(true);
            return ReactResult.complete();
        } else {
            return ReactResult.error(new InvalidCredentialsException("Invalid OTP"));
        }
    }

    private void sendOtp() {
        // TODO: 调用 OobOtpProvider 发送 OTP
    }

    private boolean verifyOtp(String code) {
        // TODO: 调用 OobOtpProvider 验证 OTP
        return true;
    }

    private String maskTarget(String target) {
        // TODO: 脱敏处理，如 u***@example.com
        if (target == null) {
            return "";
        }
        if (target.contains("@")) {
            // Email: 显示首字母和域名
            String[] parts = target.split("@");
            if (parts.length == 2) {
                String local = parts[0];
                String domain = parts[1];
                if (local.length() > 1) {
                    return local.charAt(0) + "***@" + domain;
                }
            }
        }
        // Phone: 显示后 4 位
        if (target.length() > 4) {
            return "****" + target.substring(target.length() - 4);
        }
        return target;
    }
}
