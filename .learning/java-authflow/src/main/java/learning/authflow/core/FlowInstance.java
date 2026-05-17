package learning.authflow.core;

import learning.authflow.model.FlowType;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程实例 - 持久化状态
 * SRP：只负责持久化状态
 *
 * 支持树形结构存储执行历史，同时保留线性结构用于向后兼容
 */
@Data
public class FlowInstance implements Serializable {
    private static final long serialVersionUID = 1L;

    // 流程元数据
    private String flowId;
    private FlowType flowType;
    private String flowName;

    // ===== 新树形结构 =====
    // Tree structure: root intent node（替代旧的线性 nodes 列表）
    private IntentNode rootIntent;

    // Current path for fast navigation (e.g., ["0", "authenticate", "oob"])
    private List<String> currentPath = new ArrayList<>();

    // ===== 旧线性结构（向后兼容）=====
    // 执行历史（追加不可变）
    private List<FlowNode> nodes = new ArrayList<>();

    // 当前活跃节点索引（关键！O(1)直接索引）
    private int currentNodeIndex = -1;

    // 状态令牌（每次变更更新，仅用于响应客户端）
    private String stateToken;

    // 最小化用户标识（跨步骤恢复上下文）
    private String userId;
    private String identityId;

    // ===== 兼容方法 =====

    /**
     * 获取当前节点 - 兼容旧线性结构
     * TODO: 未来从树结构中获取当前节点
     */
    @Deprecated
    public FlowNode getCurrentNode() {
        // 优先从线性结构获取（兼容旧代码）
        if (currentNodeIndex >= 0 && currentNodeIndex < nodes.size()) {
            return nodes.get(currentNodeIndex);
        }
        // TODO: 从树结构中获取当前节点
        return null;
    }
}
