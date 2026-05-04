package learning.authflow.response;

import learning.authflow.model.FlowType;
import lombok.Data;

/**
 * 认证流程响应
 */
@Data
public class AuthflowResponse {
    private String flowId;
    private String stateToken;
    private FlowType type;
    private String name;
    private Action action;
}
