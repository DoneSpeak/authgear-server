package learning.authflow.flowdef;

import learning.authflow.model.StepType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 步骤定义
 */
@Data
@Builder
public class StepDefinition {
    private String name;
    private StepType type;
    private List<BranchDefinition> oneOf;
    private StepDefinition subFlow;

    public boolean hasOneOf() {
        return oneOf != null && !oneOf.isEmpty();
    }
}
