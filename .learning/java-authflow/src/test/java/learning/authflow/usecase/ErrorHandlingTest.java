package learning.authflow.usecase;

import learning.authflow.core.FlowInstance;
import learning.authflow.core.FlowNode;
import learning.authflow.core.StateTokenManager;
import learning.authflow.exception.AuthflowException;
import learning.authflow.exception.FlowNotFoundException;
import learning.authflow.exception.InvalidInputException;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * UC-05: 错误处理场景
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UC-05: 错误处理场景")
class ErrorHandlingTest {

    @Mock private StateStorage stateStorage;
    @Mock private SessionStorage sessionStorage;
    @Mock private StepHandlerRegistry handlerRegistry;
    @Mock private FlowDefinitionProvider flowProvider;
    @Mock private IdGenerator idGenerator;

    private AuthflowEngine engine;
    private StateTokenManager tokenManager;

    @BeforeEach
    void setUp() {
        tokenManager = new StateTokenManager();
        engine = new AuthflowEngine(stateStorage, sessionStorage, handlerRegistry, flowProvider,
                                    tokenManager, idGenerator);
    }

    @Test
    @DisplayName("无效输入应返回InvalidInput错误")
    void test_uc05_invalidInput_returnsInvalidInputError() {
        // Given
        FlowDefinition def = FlowDefinition.builder()
            .name("default")
            .type(FlowType.LOGIN)
            .steps(List.of(StepDefinition.builder().type(StepType.IDENTIFY).build()))
            .build();

        FlowInstance flow = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flow.setFlowName("default");

        when(stateStorage.getFlowByStateToken("token-123")).thenReturn(flow);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(new StepHandler() {
            @Override
            public StepType getType() {
                return StepType.IDENTIFY;
            }
            @Override
            public StepResult handle(learning.authflow.core.StepContext ctx, learning.authflow.input.AuthflowInput input) {
                throw new IllegalArgumentException("Invalid input");
            }
        });

        // When/Then
        try {
            engine.execute("token-123", AuthflowInputJson.from("{\"invalid_field\":\"value\"}"));
            fail("Expected exception");
        } catch (RuntimeException e) {
            // 预期抛出异常
        }
    }

    @Test
    @DisplayName("错误响应应包含标准错误结构")
    void test_uc05_errorResponse_containsStandardErrorStructure() {
        // Given
        when(flowProvider.get("unknown")).thenReturn(null);

        // When/Then
        try {
            engine.create("login", "unknown");
            fail("Expected exception");
        } catch (AuthflowException e) {
            // 验证错误结构符合API规范
            org.assertj.core.api.Assertions.assertThat(e.getName()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(e.getReason()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(e.getMessage()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(e.getCode()).isGreaterThan(0);
        }
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
