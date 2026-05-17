package learning.authflow.intent.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.Intent;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.milestone.*;
import learning.authflow.model.AuthenticatorInfo;
import learning.authflow.model.OtpForm;
import learning.authflow.model.OtpPurpose;
import learning.authflow.node.impl.NodeDidSelectAuthenticator;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OOB OTP 认证 Intent。
 * 处理如 primary_oob_otp_email、primary_oob_otp_sms 等认证方式。
 *
 * 流程：
 * 1. 选择认证器（通过 index）
 * 2. 创建子 Intent 处理 OTP 发送和验证
 * 3. 完成认证
 */
public class UseAuthenticatorOobOtpIntent implements Intent,
        MilestoneFlowSelectAuthenticationMethod,
        MilestoneDidSelectAuthenticationMethod,
        MilestoneFlowAuthenticate {

    private final Map<String, Object> params;

    public UseAuthenticatorOobOtpIntent(Map<String, Object> params) {
        this.params = params;
    }

    @Override
    public String getKind() {
        return "UseAuthenticatorOOBOTP";
    }

    @Override
    public InputSchema canReactTo(FlowContext context) {
        // 阶段1: 需要选择认证器
        if (!context.hasMilestone(MilestoneDidSelectAuthenticator.class)) {
            return new SelectIndexSchema(getOptions(context));
        }

        // 阶段2: 创建 OTP 验证子流程（自动）
        if (!context.hasMilestone(MilestoneDoMarkClaimVerified.class)) {
            return null; // 自动处理，不需要用户输入
        }

        // 已完成
        return null;
    }

    @Override
    public ReactResult reactTo(FlowContext context, AuthflowInput input) {
        // 阶段1: 选择认证器
        if (!context.hasMilestone(MilestoneDidSelectAuthenticator.class)) {
            int index = input.as(SelectIndexInput.class).getIndex();
            AuthenticatorInfo auth = pickAuthenticator(context, index);

            context.addMilestone(new MilestoneDidSelectAuthenticator(auth));

            // 创建选择认证器的 Node
            return ReactResult.newNode(new NodeDidSelectAuthenticator(auth));
        }

        // 阶段2: 创建 OTP 验证子 Intent
        if (!context.hasMilestone(MilestoneDoMarkClaimVerified.class)) {
            AuthenticatorInfo auth = context.findMilestone(MilestoneDidSelectAuthenticator.class)
                .orElseThrow(() -> new IllegalStateException("No authenticator selected"))
                .getAuthenticator();

            Intent subIntent = new AuthenticationOobIntent(
                context.getFlow().getUserId(),
                auth,
                getPurpose(),
                getOtpForm()
            );

            return ReactResult.subIntent(subIntent);
        }

        // 阶段3: 完成
        return ReactResult.complete();
    }

    @Override
    public void addMilestone(Milestone milestone) {
        // 由 FlowContext 管理 milestones
    }

    // 辅助方法（需要实现）
    private List<AuthenticatorInfo> getOptions(FlowContext context) {
        // TODO: 从 context 或 params 获取可用的认证器列表
        return List.of();
    }

    private AuthenticatorInfo pickAuthenticator(FlowContext context, int index) {
        List<AuthenticatorInfo> options = getOptions(context);
        if (index < 0 || index >= options.size()) {
            throw new IllegalArgumentException("Invalid authenticator index: " + index);
        }
        return options.get(index);
    }

    private OtpPurpose getPurpose() {
        // TODO: 根据 authentication 类型确定用途
        return OtpPurpose.OOBOTP;
    }

    private OtpForm getOtpForm() {
        // TODO: 根据配置确定 OTP 形式（code/link）
        return OtpForm.CODE;
    }

    @Override
    public AuthenticatorInfo getSelectedAuthenticator() {
        // 从 milestone 中获取已选择的认证器
        // 此方法需要 FlowContext，当前实现是骨架
        return null;
    }
}
