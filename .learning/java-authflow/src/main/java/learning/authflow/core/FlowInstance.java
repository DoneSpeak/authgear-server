package learning.authflow.core;

import learning.authflow.model.FlowType;
import learning.authflow.model.NodeType;
import learning.authflow.model.StepType;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程实例 - 持久化状态
 * SRP：只负责持久化状态
 */
@Data
public class FlowInstance {
    // 流程元数据
    private String flowId;
    private FlowType flowType;
    private String flowName;

    // 执行历史（追加不可变）
    private List<FlowNode> nodes = new ArrayList<>();

    // 当前活跃节点索引（关键！O(1)直接索引）
    private int currentNodeIndex = -1;

    // 状态令牌（每次变更更新，仅用于响应客户端）
    private String stateToken;

    // 最小化用户标识（跨步骤恢复上下文）
    private String userId;
    private String identityId;

    public FlowNode getCurrentNode() {
        if (currentNodeIndex >= 0 && currentNodeIndex < nodes.size()) {
            return nodes.get(currentNodeIndex);
        }
        return null;
    }
}
