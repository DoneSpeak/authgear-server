package learning.authflow.flowdef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于YAML配置的流程定义提供者
 */
public class YamlPropertiesFlowDefinitionProvider implements FlowDefinitionProvider {
    private final Map<String, FlowDefinition> flows;

    public YamlPropertiesFlowDefinitionProvider(AuthflowProperties properties) {
        if (properties.getFlows() != null && properties.getFlows().getDefinitions() != null) {
            this.flows = properties.getFlows().getDefinitions().stream()
                .collect(Collectors.toMap(
                    FlowDefinition::getName,
                    Function.identity(),
                    (a, b) -> { throw new IllegalStateException("Duplicate flow: " + a.getName()); },
                    LinkedHashMap::new
                ));
        } else {
            this.flows = Collections.emptyMap();
        }
    }

    @Override
    public FlowDefinition get(String flowName) {
        return flows.get(flowName);
    }

    @Override
    public List<FlowDefinition> getAll() {
        return List.copyOf(flows.values());
    }
}
