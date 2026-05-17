package learning.authflow.intent.impl;

import learning.authflow.intent.InputSchema;
import learning.authflow.model.Channel;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 选择渠道的输入模式
 * 用于让用户选择 OTP 发送渠道
 */
public class SelectChannelSchema implements InputSchema {
    private final List<Channel> channels;

    public SelectChannelSchema(List<Channel> channels) {
        this.channels = channels;
    }

    @Override
    public String getType() {
        return "select_channel";
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("channels", channels);
    }
}
