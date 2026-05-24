package com.liteflow.auth;

import com.liteflow.auth.model.BranchDefinition;
import com.liteflow.auth.model.FlowDefinition;
import com.liteflow.auth.model.StepDefinition;
import com.liteflow.auth.parser.FlowDefinitionParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YAML 解析器测试
 */
public class FlowDefinitionParserTest {

    private final FlowDefinitionParser parser = new FlowDefinitionParser();

    @Test
    public void testParseValidYaml() {
        String yaml = """
            login_flows:
              - name: test_flow
                steps:
                  - type: identify
                    one_of:
                      - identification: oauth
                      - identification: email
                        steps:
                          - type: authenticate
                            one_of:
                              - authentication: primary_password
              """;

        Map<String, FlowDefinition> flows = parser.parse(yaml);

        assertNotNull(flows);
        assertEquals(1, flows.size());

        FlowDefinition flow = flows.get("test_flow");
        assertNotNull(flow);
        assertEquals("test_flow", flow.getName());
        assertNotNull(flow.getSteps());
        assertEquals(1, flow.getSteps().size());

        StepDefinition step = flow.getSteps().get(0);
        assertEquals("identify", step.getType());
        assertTrue(step.hasOneOf());
        assertEquals(2, step.getOneOf().size());

        BranchDefinition emailBranch = step.getOneOf().get(1);
        assertEquals("email", emailBranch.getIdentification());
        assertNotNull(emailBranch.getSteps());
        assertEquals(1, emailBranch.getSteps().size());
    }

    @Test
    public void testParseEmptyYaml() {
        String yaml = "";
        assertThrows(Exception.class, () -> parser.parse(yaml));
    }

    @Test
    public void testParseInvalidYaml() {
        String yaml = "invalid: yaml: content: [[";
        assertThrows(Exception.class, () -> parser.parse(yaml));
    }
}
