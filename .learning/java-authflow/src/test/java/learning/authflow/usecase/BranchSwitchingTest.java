package learning.authflow.usecase;

import learning.authflow.core.FlowInstance;
import learning.authflow.core.FlowNode;
import learning.authflow.core.StateTokenManager;
import learning.authflow.flowdef.BranchDefinition;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UC-04: 分支切换场景
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UC-04: 分支切换场景")
class BranchSwitchingTest {

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
    @DisplayName("从StateA选择phone分支产生StateB")
    void test_uc04_selectPhoneBranchFromStateA_createsStateB() {
        // Given - 分支流程定义
        FlowDefinition def = FlowDefinition.builder()
            .name("multi_id")
            .type(FlowType.LOGIN)
            .steps(List.of(
                StepDefinition.builder()
                    .type(StepType.IDENTIFY)
                    .oneOf(List.of(
                        BranchDefinition.builder().identification("phone").build(),
                        BranchDefinition.builder().identification("email").build()
                    ))
                    .build(),
                StepDefinition.builder().type(StepType.AUTHENTICATE).build()
            ))
            .build();

        // Given - 创建流程
        when(flowProvider.get("multi_id")).thenReturn(def);
        when(idGenerator.generate()).thenReturn("flow-123");

        FlowInstance flowA = engine.create("login", "multi_id");

        // Given - 流程实例在identify步骤
        FlowInstance flow = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flow.setFlowId("flow-123");
        flow.setFlowName("multi_id");
        flow.setStateToken(flowA.getStateToken());

        when(stateStorage.getFlowByStateToken(flowA.getStateToken())).thenReturn(flow);
        when(flowProvider.get("multi_id")).thenReturn(def);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(identifyHandler);
        when(identifyHandler.handle(any(), any())).thenReturn(
            StepResult.builder().complete(true).build());

        // When - 选择phone分支
        FlowInstance flowB = engine.execute(flowA.getStateToken(),
            AuthflowInputJson.from("{\"branch\":\"phone\"}"));

        // Then - 验证流程推进到新步骤且token更新
        assertThat(flowB.getStateToken()).isNotEqualTo(flowA.getStateToken());
        assertThat(flowB.getCurrentNodeIndex()).isGreaterThan(flowA.getCurrentNodeIndex());
    }

    @Test
    @DisplayName("回退StateA选择email分支产生StateC")
    void test_uc04_switchToEmailBranchFromStateA_createsStateC() {
        // Given - 分支流程定义
        FlowDefinition def = FlowDefinition.builder()
            .name("multi_id")
            .type(FlowType.LOGIN)
            .steps(List.of(
                StepDefinition.builder()
                    .type(StepType.IDENTIFY)
                    .oneOf(List.of(
                        BranchDefinition.builder().identification("phone").build(),
                        BranchDefinition.builder().identification("email").build()
                    ))
                    .build(),
                StepDefinition.builder().type(StepType.AUTHENTICATE).build()
            ))
            .build();

        // Given - 创建流程
        when(flowProvider.get("multi_id")).thenReturn(def);
        when(idGenerator.generate()).thenReturn("flow-123");

        FlowInstance flowA = engine.create("login", "multi_id");

        // Given - 流程实例（用于phone分支）
        FlowInstance flowPhone = createFlowWithNode(0, "0", StepType.IDENTIFY);
        flowPhone.setFlowId("flow-123");
        flowPhone.setFlowName("multi_id");
        flowPhone.setStateToken(flowA.getStateToken());

        when(stateStorage.getFlowByStateToken(flowA.getStateToken())).thenReturn(flowPhone);
        when(flowProvider.get("multi_id")).thenReturn(def);
        when(handlerRegistry.get(StepType.IDENTIFY)).thenReturn(identifyHandler);
        when(identifyHandler.handle(any(), any())).thenReturn(
            StepResult.builder().complete(true).build());

        // When - 使用原始StateA选择email分支
        FlowInstance flowC = engine.execute(flowA.getStateToken(),
            AuthflowInputJson.from("{\"branch\":\"email\"}"));

        // Then - 验证流程推进到新步骤且token更新
        assertThat(flowC.getStateToken()).isNotEqualTo(flowA.getStateToken());
        assertThat(flowC.getCurrentNodeIndex()).isGreaterThan(flowA.getCurrentNodeIndex());
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
