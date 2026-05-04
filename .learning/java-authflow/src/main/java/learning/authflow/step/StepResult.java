package learning.authflow.step;

import lombok.Builder;
import lombok.Getter;

/**
 * 步骤执行结果
 */
@Getter
@Builder
public class StepResult {
    private final boolean complete;
    private final String userId;
    private final String identityId;
}
