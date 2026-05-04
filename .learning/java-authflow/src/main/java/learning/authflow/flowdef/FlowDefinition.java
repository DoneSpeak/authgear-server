package learning.authflow.flowdef;

import learning.authflow.model.FlowType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 流程定义
 */
@Data
@Builder
public class FlowDefinition {
    private String name;
    private FlowType type;
    private List<StepDefinition> steps;
}
