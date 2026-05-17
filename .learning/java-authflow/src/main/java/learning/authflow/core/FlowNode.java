package learning.authflow.core;

import learning.authflow.milestone.Milestone;
import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import learning.authflow.step.StepResult;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 流程节点 - 单个步骤的执行记录
 * 支持树形结构和子流程
 */
@Data
public class FlowNode implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nodeId;
    private NodeType type;
    private StepType stepType;
    private StepResult result;
    private Map<String, Object> data = new HashMap<>();

    // For sub-flow nodes - 支持子流程嵌套
    private IntentNode subIntent;

    // 标记节点是否已完成
    private boolean completed = false;
}
