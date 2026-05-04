package learning.authflow.usecase;

import learning.authflow.core.FlowInstance;
import learning.authflow.core.FlowNode;
import learning.authflow.core.StateTokenManager;
import learning.authflow.exception.InvalidStateTokenException;
import learning.authflow.flowdef.FlowDefinition;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.StepDefinition;
import learning.authflow.input.AuthflowInputJson;
import learning.authflow.model.FlowType;
import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import learning.authflow.core.AuthflowEngine;
import learning.authflow.core.IdGenerator;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UC-03: 状态令牌管理
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UC-03: 状态令牌管理")
class StateTokenManagementTest {

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
    @DisplayName("每次状态变更应生成新的stateToken")
    void test_uc03_stateChange_generatesNewToken() {
        // Given - 创建流程
        FlowDefinition def = FlowDefinition.builder()
            .name("default")
            .type(FlowType.LOGIN)
            .steps(List.of(
                StepDefinition.builder().type(StepType.IDENTIFY).build(),
                StepDefinition.builder().type(StepType.AUTHENTICATE).build()
            ))
            .build();

        when(flowProvider.get("default")).thenReturn(def);
        when(idGenerator.generate()).thenReturn("flow-123");

        FlowInstance flowA = engine.create("login", "default");

        // Given - 流程实例
        FlowInstance flow = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flow.setFlowName("default");
        flow.setStateToken(flowA.getStateToken());

        when(stateStorage.getFlowByStateToken(flowA.getStateToken())).thenReturn(flow);
        when(flowProvider.get("default")).thenReturn(def);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(identifyHandler);
        when(identifyHandler.handle(any(), any())).thenReturn(
            StepResult.builder().complete(true).build());

        // When
        FlowInstance flowB = engine.execute(flowA.getStateToken(),
            AuthflowInputJson.from("{}"));

        // Then
        assertThat(flowA.getStateToken()).isNotEqualTo(flowB.getStateToken());
    }

    @Test
    @DisplayName("无效stateToken应抛出401异常")
    void test_uc03_invalidToken_throwsUnauthorizedException() {
        // Given
        when(stateStorage.getFlowByStateToken("authflowstate_invalid"))
            .thenThrow(new InvalidStateTokenException("Invalid token"));

        // When/Then
        assertThrows(InvalidStateTokenException.class, () -> {
            engine.execute("authflowstate_invalid", AuthflowInputJson.from("{}"));
        });
    }

    @Test
    @DisplayName("stateToken应包含正确格式前缀")
    void test_uc03_tokenFormat_containsCorrectPrefix() {
        // Given
        FlowDefinition def = FlowDefinition.builder()
            .name("default")
            .type(FlowType.LOGIN)
            .steps(List.of(StepDefinition.builder().type(StepType.IDENTIFY).build()))
            .build();

        when(flowProvider.get("default")).thenReturn(def);
        when(idGenerator.generate()).thenReturn("flow-123");

        // When
        FlowInstance flow = engine.create("login", "default");

        // Then
        assertThat(flow.getStateToken()).startsWith("authflowstate_");
    }

    private FlowInstance createFlowWithNode(int index, String nodeId, StepType type) {
        FlowInstance flow = new FlowInstance();
        flow.setFlowId("flow-123");
        flow.setFlowType(FlowType.LOGIN);
        flow.setCurrentNodeIndex(index);

        FlowNode node = new FlowNode();
        node.setNodeId(nodeId);
        node.setStepType(type);
        node.setType(NodeType.SIMPLE);
        flow.setNodes(new ArrayList<>(List.of(node)));

        return flow;
    }
}
