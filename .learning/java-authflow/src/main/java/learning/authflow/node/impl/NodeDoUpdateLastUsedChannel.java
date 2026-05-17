package learning.authflow.node.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.core.FlowNode;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.InputReactor;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.model.Channel;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 更新最后使用渠道的节点
 * 此节点自动完成，用于记录用户最后使用的 OOB OTP 渠道
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeDoUpdateLastUsedChannel extends FlowNode implements InputReactor {
    private final Channel channel;

    public NodeDoUpdateLastUsedChannel(Channel channel) {
        this.channel = channel;
        setType(learning.authflow.model.NodeType.SIMPLE);
        setCompleted(true); // 此 Node 自动完成
    }

    @Override
    public InputSchema canReactTo(FlowContext context) {
        return null; // EOF - 已完成
    }

    @Override
    public ReactResult reactTo(FlowContext context, AuthflowInput input) {
        return ReactResult.complete();
    }
}
