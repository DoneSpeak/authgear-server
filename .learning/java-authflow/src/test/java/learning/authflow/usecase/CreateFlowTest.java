package learning.authflow.usecase;

import learning.authflow.core.FlowInstance;
import learning.authflow.core.StateTokenManager;
import learning.authflow.exception.FlowNotFoundException;
import learning.authflow.flowdef.FlowDefinition;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.StepDefinition;
import learning.authflow.model.FlowType;
import learning.authflow.model.StepType;
import learning.authflow.response.AuthflowResponse;
import learning.authflow.core.AuthflowEngine;
import learning.authflow.core.IdGenerator;
import learning.authflow.step.registry.StepHandlerRegistry;
import learning.authflow.storage.StateStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UC-01: 创建认证流程
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UC-01: 创建认证流程")
class CreateFlowTest {

    @Mock private StateStorage stateStorage;
    @Mock private StepHandlerRegistry handlerRegistry;
    @Mock private FlowDefinitionProvider flowProvider;
    @Mock private IdGenerator idGenerator;

    private AuthflowEngine engine;
    private StateTokenManager tokenManager;

    @BeforeEach
    void setUp() {
        tokenManager = new StateTokenManager();
        engine = new AuthflowEngine(stateStorage, handlerRegistry, flowProvider,
                                    tokenManager, idGenerator);
    }

    @Test
    @DisplayName("创建登录流程应返回identify步骤")
    void test_uc01_createLoginFlow_returnsIdentifyStep() {
        // Given
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

        // When
        AuthflowResponse response = engine.create("login", "default");

        // Then
        assertThat(response.getStateToken()).startsWith("authflowstate_");
        assertThat(response.getType()).isEqualTo(FlowType.LOGIN);
        assertThat(response.getAction().getType()).isEqualTo(StepType.IDENTIFY);
    }

    @Test
    @DisplayName("创建注册流程应返回identify步骤")
    void test_uc01_createSignupFlow_returnsIdentifyStep() {
        // Given
        FlowDefinition def = FlowDefinition.builder()
            .name("signup_default")
            .type(FlowType.SIGNUP)
            .steps(List.of(
                StepDefinition.builder().type(StepType.IDENTIFY).build()
            ))
            .build();

        when(flowProvider.get("signup_default")).thenReturn(def);
        when(idGenerator.generate()).thenReturn("flow-456");

        // When
        AuthflowResponse response = engine.create("signup", "signup_default");

        // Then
        assertThat(response.getType()).isEqualTo(FlowType.SIGNUP);
        assertThat(response.getAction().getType()).isEqualTo(StepType.IDENTIFY);
    }

    @Test
    @DisplayName("不存在的流程定义应抛出异常")
    void test_uc01_createWithNonExistentFlow_throwsFlowNotFoundException() {
        // Given
        when(flowProvider.get("non_existent")).thenReturn(null);

        // When/Then
        assertThrows(FlowNotFoundException.class, () -> {
            engine.create("login", "non_existent");
        });
    }

    @Test
    @DisplayName("空步骤流程应直接返回finished状态")
    void test_uc01_createEmptyStepsFlow_returnsFinishedState() {
        // Given
        FlowDefinition def = FlowDefinition.builder()
            .name("empty")
            .type(FlowType.LOGIN)
            .steps(Collections.emptyList())
            .build();

        when(flowProvider.get("empty")).thenReturn(def);
        when(idGenerator.generate()).thenReturn("flow-789");

        // When
        AuthflowResponse response = engine.create("login", "empty");

        // Then
        assertThat(response.getAction().getType()).isEqualTo(StepType.FINISHED);
    }
}
