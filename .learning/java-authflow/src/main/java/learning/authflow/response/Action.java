package learning.authflow.response;

import learning.authflow.model.StepType;
import lombok.Data;

/**
 * 当前步骤动作
 */
@Data
public class Action {
    private StepType type;
    private ActionData data;
}
