package learning.authflow.flowdef;

import java.util.List;

/**
 * 流程定义提供者接口
 */
public interface FlowDefinitionProvider {
    FlowDefinition get(String flowName);
    List<FlowDefinition> getAll();
}
