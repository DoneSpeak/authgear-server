package com.routor.engine;

import com.routor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {
    private static final String TEST_YAML = """
        login_flows:
          - name: test_flow
            type: LOGIN
            steps:
              - type: identify
                oneOf:
                  - identification: email
                  - identification: oauth
        """;

    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        FlowLoader loader = new FlowLoader();
        engine = new FlowEngine(loader.loadAll(TEST_YAML));
    }

    @Test
    void shouldCreateFlowInstance() {
        FlowInstance instance = engine.create("test_flow");

        assertNotNull(instance);
        assertNotNull(instance.getFlowId());
        assertEquals("test_flow", instance.getFlowName());
        assertFalse(instance.getStack().isEmpty());
    }

    @Test
    void shouldExecuteFlowAndReturnOptions() {
        FlowInstance instance = engine.create("test_flow");

        ExecutionResult result = engine.execute(instance, null);

        assertEquals(State.NEED_INPUT, result.getState());
        assertEquals(2, result.getOptions().size());
    }

    @Test
    void shouldCompleteFlow() {
        FlowInstance instance = engine.create("test_flow");

        engine.execute(instance, null);           // 获取选项
        ExecutionResult result = engine.execute(instance, "oauth");  // 选择 oauth

        assertEquals(State.COMPLETED, result.getState());
        assertTrue(result.getPath().contains("oauth"));
    }

    @Test
    void shouldThrowExceptionForNonExistentFlow() {
        assertThrows(IllegalArgumentException.class, () -> engine.create("non_existent"));
    }
}
