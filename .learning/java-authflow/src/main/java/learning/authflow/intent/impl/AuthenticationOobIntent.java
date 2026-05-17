package learning.authflow.intent.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.Intent;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.milestone.*;
import learning.authflow.model.AuthenticatorInfo;
import learning.authflow.model.Channel;
import learning.authflow.model.OtpForm;
import learning.authflow.model.OtpPurpose;
import learning.authflow.node.impl.NodeAuthenticationOob;
import learning.authflow.node.impl.NodeDoUpdateLastUsedChannel;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * OOB OTP 验证子 Intent。
 * 处理 OTP 的发送和验证。
 */
public class AuthenticationOobIntent implements Intent {

    private final String userId;
    private final AuthenticatorInfo authenticator;
    private final OtpPurpose purpose;
    private final OtpForm form;

    public AuthenticationOobIntent(String userId, AuthenticatorInfo authenticator,
                                   OtpPurpose purpose, OtpForm form) {
        this.userId = userId;
        this.authenticator = authenticator;
        this.purpose = purpose;
        this.form = form;
    }

    @Override
    public String getKind() {
        return "AuthenticationOOB";
    }

    @Override
    public InputSchema canReactTo(FlowContext context) {
        // 阶段1: 选择渠道（如果只有一个则自动）
        if (!context.hasMilestone(MilestoneOobOtpVerified.class)) {
            List<Channel> channels = getAvailableChannels();
            if (channels.size() == 1) {
                return null; // 自动选择
            }
            return new SelectChannelSchema(channels);
        }

        // 阶段2: 更新最后使用渠道（自动）
        if (!context.hasMilestone(MilestoneOobOtpLastUsedChannelUpdated.class)) {
            return null; // 自动处理
        }

        // 已完成
        return null;
    }

    @Override
    public ReactResult reactTo(FlowContext context, AuthflowInput input) {
        // 阶段1: 选择渠道并创建 Node 发送 OTP
        if (!context.hasMilestone(MilestoneOobOtpVerified.class)) {
            List<Channel> channels = getAvailableChannels();
            Channel channel = channels.size() == 1
                ? channels.get(0)
                : input.as(SelectChannelInput.class).getChannel();

            // 创建 Node，Node 负责发送 OTP 和等待验证
            return ReactResult.newNode(new NodeAuthenticationOob(
                authenticator, channel, purpose, form
            ));
        }

        // 阶段2: 更新最后使用渠道
        if (!context.hasMilestone(MilestoneOobOtpLastUsedChannelUpdated.class)) {
            Channel channel = context.findMilestone(MilestoneOobOtpVerified.class)
                .orElseThrow(() -> new IllegalStateException("No OTP verified milestone"))
                .getChannel();
            return ReactResult.newNode(new NodeDoUpdateLastUsedChannel(channel));
        }

        return ReactResult.complete();
    }

    private List<Channel> getAvailableChannels() {
        // TODO: 根据 authenticator 类型确定可用渠道
        // 如 email 认证器只能用 EMAIL 渠道
        return List.of(Channel.EMAIL);
    }
}
