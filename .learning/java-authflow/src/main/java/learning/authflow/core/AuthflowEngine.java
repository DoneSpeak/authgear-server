package learning.authflow.core;

import learning.authflow.flowdef.BranchDefinition;
import learning.authflow.flowdef.FlowDefinition;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.StepDefinition;
import learning.authflow.input.AuthflowInput;
import learning.authflow.model.BranchSelection;
import learning.authflow.model.FlowType;
import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import learning.authflow.step.registry.StepHandlerRegistry;
import learning.authflow.storage.SessionStorage;
import learning.authflow.storage.StateStorage;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 认证流程引擎
 * DIP：依赖接口而非具体实现
 */
public class AuthflowEngine {
    private final StateStorage stateStorage;
    private final SessionStorage sessionStorage;
    private final StepHandlerRegistry handlerRegistry;
    private final FlowDefinitionProvider flowProvider;
    private final StateTokenManager stateTokenManager;
    private final IdGenerator idGenerator;

    public AuthflowEngine(StateStorage stateStorage,
                          SessionStorage sessionStorage,
                          StepHandlerRegistry handlerRegistry,
                          FlowDefinitionProvider flowProvider,
                          StateTokenManager stateTokenManager,
                          IdGenerator idGenerator) {
        this.stateStorage = stateStorage;
        this.sessionStorage = sessionStorage;
        this.handlerRegistry = handlerRegistry;
        this.flowProvider = flowProvider;
        this.stateTokenManager = stateTokenManager;
        this.idGenerator = idGenerator;
    }

    public FlowInstance create(String type, String name) {
        FlowDefinition def = flowProvider.get(name);
        if (def == null) {
            throw new learning.authflow.exception.FlowNotFoundException("Flow not found: " + name);
        }

        FlowInstance flow = new FlowInstance();
        flow.setFlowId(idGenerator.generate());
        flow.setFlowType(FlowType.valueOf(type.toUpperCase()));
        flow.setFlowName(name);
        flow.setStateToken(stateTokenManager.generateToken());
        flow.setNodes(new ArrayList<>());
        flow.setCurrentNodeIndex(-1);

        advance(flow, null, null);
        stateStorage.createFlow(flow);

        return flow;
    }

    public FlowInstance execute(String stateToken, AuthflowInput input) {
        FlowInstance flow = stateStorage.getFlowByStateToken(stateToken);
        StepContext context = buildStepContext(flow);

        StepHandler handler = handlerRegistry.get(context.getCurrentNode().getStepType());
        StepResult result = handler.handle(context, input);

        updateFlowWithResult(flow, result, input, context);

        flow.setStateToken(stateTokenManager.generateToken());
        stateStorage.createFlow(flow);

        return flow;
    }

    private StepContext buildStepContext(FlowInstance flow) {
        FlowNode currentNode = flow.getCurrentNode();
        if (currentNode == null) {
            throw new IllegalStateException("No current node available");
        }
        // 从 Redis 获取或创建 Session
        Session session = sessionStorage.getOrCreate(flow.getFlowId());
        return StepContext.builder()
            .flowId(flow.getFlowId())
            .flowType(flow.getFlowType())
            .flowName(flow.getFlowName())
            .currentNode(currentNode)
            .currentNodeIndex(flow.getCurrentNodeIndex())
            .userId(flow.getUserId())
            .session(session)
            .attributes(new HashMap<>())
            .build();
    }

    private void updateFlowWithResult(FlowInstance flow, StepResult result, AuthflowInput input, StepContext context) {
        if (result.getUserId() != null) {
            flow.setUserId(result.getUserId());
        }
        flow.getCurrentNode().setResult(result);
        // 将 Session 保存到 Redis
        sessionStorage.save(context.getSession());

        if (result.isComplete()) {
            advance(flow, result, input);
        }
    }

    private void advance(FlowInstance flow, StepResult previousResult, AuthflowInput input) {
        FlowDefinition def = flowProvider.get(flow.getFlowName());
        int nextIndex = flow.getCurrentNodeIndex() + 1;

        if (nextIndex < def.getSteps().size()) {
            StepDefinition nextStep = def.getSteps().get(nextIndex);
            String parentNodeId = flow.getCurrentNodeIndex() >= 0
                ? flow.getNodes().get(flow.getCurrentNodeIndex()).getNodeId()
                : null;

            if (nextStep.hasOneOf() && input != null) {
                BranchSelection selection = input.as(BranchSelection.class);
                String branch = selection.getBranch();
                String nodeId = (parentNodeId != null ? parentNodeId + "." : "") + branch + ".0";
                FlowNode node = createNode(flow, nextStep, nodeId);
                node.getData().put("branch", branch);
                flow.getNodes().add(node);
            } else {
                FlowNode node = createNode(flow, nextStep, String.valueOf(nextIndex));
                flow.getNodes().add(node);
            }
            flow.setCurrentNodeIndex(flow.getNodes().size() - 1);
        } else {
            FlowNode finishNode = new FlowNode();
            finishNode.setNodeId("finished");
            finishNode.setType(NodeType.SIMPLE);
            finishNode.setStepType(StepType.FINISHED);
            flow.getNodes().add(finishNode);
            flow.setCurrentNodeIndex(flow.getNodes().size() - 1);
        }
    }

    private FlowNode createNode(FlowInstance flow, StepDefinition stepDef, String nodeId) {
        FlowNode node = new FlowNode();
        node.setNodeId(nodeId);
        node.setType(stepDef.getSubFlow() != null ? NodeType.SUBFLOW : NodeType.SIMPLE);
        node.setStepType(stepDef.getType());
        node.setData(new HashMap<>());
        node.setResult(null);
        return node;
    }
}
