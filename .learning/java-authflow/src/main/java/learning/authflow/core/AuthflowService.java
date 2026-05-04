package learning.authflow.core;

import learning.authflow.flowdef.FlowDefinition;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.StepDefinition;
import learning.authflow.input.AuthflowInput;
import learning.authflow.input.AuthflowInputJson;
import learning.authflow.model.StepType;
import learning.authflow.response.Action;
import learning.authflow.response.ActionData;
import learning.authflow.response.AuthflowResponse;
import learning.authflow.storage.StateStorage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 认证流程服务层
 * 应用服务层：组合环境变量与输入解析，构建响应
 */
@Service
public class AuthflowService {
    private final AuthflowEngine engine;
    private final StateStorage stateStorage;
    private final StateTokenManager tokenManager;
    private final FlowDefinitionProvider flowProvider;

    public AuthflowService(AuthflowEngine engine,
                           StateStorage stateStorage,
                           StateTokenManager tokenManager,
                           FlowDefinitionProvider flowProvider) {
        this.engine = engine;
        this.stateStorage = stateStorage;
        this.tokenManager = tokenManager;
        this.flowProvider = flowProvider;
    }

    /**
     * 创建新流程
     */
    public AuthflowResponse create(String type, String name) {
        FlowInstance flow = engine.create(type, name);
        return toResponse(flow);
    }

    /**
     * 执行步骤
     * Service层：将JSON字符串封装为AuthflowInput
     */
    public AuthflowResponse execute(String stateToken, String jsonInput) {
        AuthflowInput input = jsonInput != null ? AuthflowInputJson.from(jsonInput) : null;
        FlowInstance flow = engine.execute(stateToken, input);
        return toResponse(flow);
    }

    /**
     * 获取当前状态
     */
    public AuthflowResponse getState(String stateToken) {
        FlowInstance flow = stateStorage.getFlowByStateToken(stateToken);
        return toResponse(flow);
    }

    /**
     * 将 FlowInstance 转换为 AuthflowResponse
     */
    private AuthflowResponse toResponse(FlowInstance flow) {
        AuthflowResponse response = new AuthflowResponse();
        response.setStateToken(flow.getStateToken());
        response.setType(flow.getFlowType());
        response.setName(flow.getFlowName());

        FlowNode currentNode = flow.getCurrentNode();
        if (currentNode == null) {
            throw new IllegalStateException("No current node available");
        }
        Action action = new Action();
        action.setType(currentNode.getStepType());
        action.setData(buildActionData(flow, currentNode));

        response.setAction(action);
        return response;
    }

    private ActionData buildActionData(FlowInstance flow, FlowNode currentNode) {
        ActionData data = new ActionData();
        StepType stepType = currentNode.getStepType();

        switch (stepType) {
            case IDENTIFY:
            case AUTHENTICATE:
                FlowDefinition def = flowProvider.get(flow.getFlowName());
                int stepIndex = flow.getCurrentNodeIndex();
                // 安全检查：确保索引在范围内
                if (stepIndex < def.getSteps().size()) {
                    StepDefinition stepDef = def.getSteps().get(stepIndex);
                    if (stepType == StepType.IDENTIFY) {
                        data.setType("identification_data");
                        data.setOptions(buildIdentifyOptions(stepDef));
                    } else {
                        data.setType("authentication_data");
                        data.setOptions(buildAuthenticateOptions(stepDef));
                    }
                }
                break;
            case VERIFY:
                data.setType("verify_data");
                break;
            case CREATE_AUTHENTICATOR:
                data.setType("create_authenticator_data");
                break;
            case USER_PROFILE:
                data.setType("user_profile_data");
                break;
            case FINISHED:
                data.setType("finished_data");
                break;
            default:
                data.setType(null);
        }

        return data;
    }

    private List<ActionData.Option> buildIdentifyOptions(StepDefinition stepDef) {
        if (stepDef.getOneOf() == null) {
            return null;
        }
        return stepDef.getOneOf().stream()
            .filter(b -> b.getIdentification() != null)
            .map(b -> {
                ActionData.Option option = new ActionData.Option();
                option.setIdentification(b.getIdentification());
                return option;
            })
            .collect(Collectors.toList());
    }

    private List<ActionData.Option> buildAuthenticateOptions(StepDefinition stepDef) {
        if (stepDef.getOneOf() == null) {
            return null;
        }
        return stepDef.getOneOf().stream()
            .filter(b -> b.getAuthentication() != null)
            .map(b -> {
                ActionData.Option option = new ActionData.Option();
                option.setAuthentication(b.getAuthentication());
                return option;
            })
            .collect(Collectors.toList());
    }
}
