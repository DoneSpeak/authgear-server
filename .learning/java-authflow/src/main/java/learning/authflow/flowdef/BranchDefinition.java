package learning.authflow.flowdef;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分支定义
 */
@Data
@Builder
public class BranchDefinition {
    private String identification;
    private String authentication;
    private String targetStep;
    private List<StepDefinition> steps;
}
