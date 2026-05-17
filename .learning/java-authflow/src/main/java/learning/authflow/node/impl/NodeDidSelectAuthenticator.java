package learning.authflow.node.impl;

import learning.authflow.core.FlowContext;
import learning.authflow.core.FlowNode;
import learning.authflow.input.AuthflowInput;
import learning.authflow.intent.InputReactor;
import learning.authflow.intent.InputSchema;
import learning.authflow.intent.ReactResult;
import learning.authflow.model.AuthenticatorInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 已选择认证器的节点
 * 此节点自动完成，用于记录用户选择的认证器
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeDidSelectAuthenticator extends FlowNode implements InputReactor {
    private final AuthenticatorInfo authenticator;

    public NodeDidSelectAuthenticator(AuthenticatorInfo authenticator) {
        this.authenticator = authenticator;
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
