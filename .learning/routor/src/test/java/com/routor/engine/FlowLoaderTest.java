package com.routor.engine;

import com.routor.model.FlowDefinition;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FlowLoaderTest {
    private static final String TEST_YAML = """
        login_flows:
          - name: test_flow
            type: LOGIN
            steps:
              - type: identify
                oneOf:
                  - identification: email
        """;

    @Test
    void shouldLoadFlowFromYaml() {
        FlowLoader loader = new FlowLoader();
        FlowDefinition flow = loader.load(TEST_YAML, "test_flow");

        assertNotNull(flow);
        assertEquals("test_flow", flow.getName());
        assertEquals("LOGIN", flow.getType());
        assertEquals(1, flow.getSteps().size());
        assertEquals("identify", flow.getSteps().get(0).getType());
    }

    @Test
    void shouldLoadAllFlows() {
        FlowLoader loader = new FlowLoader();
        Map<String, FlowDefinition> flows = loader.loadAll(TEST_YAML);

        assertEquals(1, flows.size());
        assertTrue(flows.containsKey("test_flow"));
    }

    @Test
    void shouldReturnNullForNonExistentFlow() {
        FlowLoader loader = new FlowLoader();
        FlowDefinition flow = loader.load(TEST_YAML, "non_existent");

        assertNull(flow);
    }
}
