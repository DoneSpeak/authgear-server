package learning.authflow.intent.impl;

import learning.authflow.model.Channel;
import lombok.Data;

/**
 * 选择渠道的输入数据
 */
public class SelectChannelInput {
    private Channel channel;

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }
}
