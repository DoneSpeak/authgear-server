package learning.authflow.core;

import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import learning.authflow.step.StepResult;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程节点 - 单个步骤的执行记录
 */
@Data
public class FlowNode {
    private String nodeId;
    private NodeType type;
    private StepType stepType;
    private StepResult result;
    private Map<String, Object> data = new HashMap<>();
}
