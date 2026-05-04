package learning.authflow.usecase;

import learning.authflow.core.FlowInstance;
import learning.authflow.core.FlowNode;
import learning.authflow.core.StateTokenManager;
import learning.authflow.flowdef.FlowDefinition;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.StepDefinition;
import learning.authflow.input.AuthflowInput;
import learning.authflow.input.AuthflowInputJson;
import learning.authflow.model.FlowType;
import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import learning.authflow.core.AuthflowEngine;
import learning.authflow.core.IdGenerator;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
import learning.authflow.step.handlers.IdentifyHandler;
import learning.authflow.step.registry.StepHandlerRegistry;
import learning.authflow.storage.SessionStorage;
import learning.authflow.storage.StateStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UC-02: 执行流程步骤
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UC-02: 执行流程步骤")
class ExecuteStepTest {

    @Mock private StateStorage stateStorage;
    @Mock private SessionStorage sessionStorage;
    @Mock private StepHandlerRegistry handlerRegistry;
    @Mock private FlowDefinitionProvider flowProvider;
    @Mock private IdGenerator idGenerator;
    @Mock private StepHandler identifyHandler;

    private AuthflowEngine engine;
    private StateTokenManager tokenManager;

    @BeforeEach
    void setUp() {
        tokenManager = new StateTokenManager();
        engine = new AuthflowEngine(stateStorage, sessionStorage, handlerRegistry, flowProvider,
                                    tokenManager, idGenerator);
    }

    @Test
    @DisplayName("完成identify步骤应推进到authenticate步骤")
    void test_uc02_completeIdentifyStep_advancesToAuthenticateStep() {
        // Given - 流程定义
        FlowDefinition def = FlowDefinition.builder()
            .name("default")
            .type(FlowType.LOGIN)
            .steps(List.of(
                StepDefinition.builder().type(StepType.IDENTIFY).build(),
                StepDefinition.builder().type(StepType.AUTHENTICATE).build()
            ))
            .build();

        // Given - 流程实例在identify步骤
        FlowInstance flow = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flow.setFlowName("default");

        when(stateStorage.getFlowByStateToken("token-123")).thenReturn(flow);
        when(flowProvider.get("default")).thenReturn(def);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(identifyHandler);
        when(identifyHandler.handle(any(), any())).thenReturn(
            StepResult.builder().complete(true).build());

        // When
        FlowInstance resultFlow = engine.execute("token-123",
            AuthflowInputJson.from("{\"identification\":\"phone\",\"login_id\":\"+8613800138000\"}"));

        // Then
        assertThat(resultFlow.getStateToken()).isNotEqualTo("token-123");
        assertThat(resultFlow.getNodes().get(resultFlow.getCurrentNodeIndex()).getStepType()).isEqualTo(StepType.AUTHENTICATE);
    }

    @Test
    @DisplayName("未完成步骤应停留在当前步骤")
    void test_uc02_incompleteStep_remainsOnCurrentStep() {
        // Given - 流程定义
        FlowDefinition def = FlowDefinition.builder()
            .name("default")
            .type(FlowType.LOGIN)
            .steps(List.of(
                StepDefinition.builder().type(StepType.IDENTIFY).build()
            ))
            .build();

        // Given - 流程实例在identify步骤
        FlowInstance flow = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flow.setFlowName("default");

        when(stateStorage.getFlowByStateToken("token-123")).thenReturn(flow);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(identifyHandler);
        when(identifyHandler.handle(any(), any())).thenReturn(
            StepResult.builder().complete(false).build());

        // When
        FlowInstance resultFlow = engine.execute("token-123",
            AuthflowInputJson.from("{\"identification\":\"phone\"}"));

        // Then - 仍在identify步骤，但token已更新
        assertThat(resultFlow.getNodes().get(resultFlow.getCurrentNodeIndex()).getStepType()).isEqualTo(StepType.IDENTIFY);
        assertThat(resultFlow.getStateToken()).isNotEqualTo("token-123");
    }

    @Test
    @DisplayName("最后步骤完成后应返回finished状态")
    void test_uc02_lastStepCompletion_returnsFinishedState() {
        // Given - 流程定义（单步骤）
        FlowDefinition def = FlowDefinition.builder()
            .name("single")
            .type(FlowType.LOGIN)
            .steps(List.of(
                StepDefinition.builder().type(StepType.IDENTIFY).build()
            ))
            .build();

        // Given - 流程实例在最后步骤
        FlowInstance flow = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flow.setFlowName("single");

        when(stateStorage.getFlowByStateToken("token-123")).thenReturn(flow);
        when(flowProvider.get("single")).thenReturn(def);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(identifyHandler);
        when(identifyHandler.handle(any(), any())).thenReturn(
            StepResult.builder().complete(true).build());

        // When
        FlowInstance resultFlow = engine.execute("token-123",
            AuthflowInputJson.from("{}"));

        // Then
        assertThat(resultFlow.getNodes().get(resultFlow.getCurrentNodeIndex()).getStepType()).isEqualTo(StepType.FINISHED);
    }

    private FlowInstance createFlowWithNode(int index, String nodeId, StepType type) {
        FlowInstance flow = new FlowInstance();
        flow.setFlowId("flow-123");
        flow.setFlowType(FlowType.LOGIN);
        flow.setStateToken("token-123");
        flow.setCurrentNodeIndex(index);

        FlowNode node = new FlowNode();
        node.setNodeId(nodeId);
        node.setStepType(type);
        node.setType(NodeType.SIMPLE);
        flow.setNodes(new ArrayList<>(List.of(node)));

        return flow;
    }
}
