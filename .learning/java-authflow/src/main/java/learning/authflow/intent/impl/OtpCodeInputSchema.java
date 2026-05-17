package learning.authflow.intent.impl;

import learning.authflow.intent.InputSchema;
import learning.authflow.model.Channel;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * OTP 验证码输入模式
 * 用于让用户输入收到的 OTP 验证码
 */
@RequiredArgsConstructor
public class OtpCodeInputSchema implements InputSchema {
    private final String target;  // 脱敏后的目标（如 u***@example.com）
    private final Channel channel;

    @Override
    public String getType() {
        return "otp_code";
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of(
            "target", target,
            "channel", channel,
            "length", 6  // 默认 6 位验证码
        );
    }
}
