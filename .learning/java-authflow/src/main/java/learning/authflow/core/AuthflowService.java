package learning.authflow.core;

import learning.authflow.input.AuthflowInput;
import learning.authflow.input.AuthflowInputJson;
import learning.authflow.response.AuthflowResponse;
import learning.authflow.storage.StateStorage;
import org.springframework.stereotype.Service;

/**
 * 认证流程服务层
 * 应用服务层：组合环境变量与输入解析，然后调用引擎
 */
@Service
public class AuthflowService {
    private final AuthflowEngine engine;
    private final StateStorage stateStorage;
    private final StateTokenManager tokenManager;

    public AuthflowService(AuthflowEngine engine,
                           StateStorage stateStorage,
                           StateTokenManager tokenManager) {
        this.engine = engine;
        this.stateStorage = stateStorage;
        this.tokenManager = tokenManager;
    }

    /**
     * 创建新流程
     */
    public AuthflowResponse create(String type, String name) {
        return engine.create(type, name);
    }

    /**
     * 执行步骤
     * Service层：将原始JSON封装为AuthflowInput
     */
    public AuthflowResponse execute(String stateToken, String jsonInput) {
        AuthflowInput input = jsonInput != null ? AuthflowInputJson.from(jsonInput) : null;
        return engine.execute(stateToken, input);
    }

    /**
     * 获取当前状态
     */
    public AuthflowResponse getState(String stateToken) {
        FlowInstance flow = stateStorage.getFlowByStateToken(stateToken);
        return toResponse(flow);
    }

    private AuthflowResponse toResponse(FlowInstance flow) {
        AuthflowResponse response = new AuthflowResponse();
        response.setFlowId(flow.getFlowId());
        response.setStateToken(flow.getStateToken());
        response.setType(flow.getFlowType());
        response.setName(flow.getFlowName());

        FlowNode currentNode = flow.getNodes().get(flow.getCurrentNodeIndex());
        learning.authflow.response.Action action = new learning.authflow.response.Action();
        action.setType(currentNode.getStepType());
        action.setData(new learning.authflow.response.ActionData());

        if (currentNode.getData().containsKey("branch")) {
            action.setIdentification((String) currentNode.getData().get("branch"));
        }

        response.setAction(action);
        return response;
    }
}
