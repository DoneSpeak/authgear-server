package com.liteflow.auth.state;

import com.liteflow.auth.model.ExecutionResult;
import com.liteflow.auth.model.Option;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 流程执行状态 - 存储在 Redis 中
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowExecutionState implements Serializable {
    private String flowId;              // 流程实例唯一标识
    private String flowName;            // 流程定义名称
    private String currentChainId;      // 当前执行的 chain 名称
    private String currentNodeId;       // 当前节点
    private List<String> path;          // 用户选择路径
    private Map<String, Object> context; // 业务上下文数据
    private ExecutionResult.ExecutionStatus status;  // 执行状态
    private List<Option> currentOptions; // 当前可选项
    private String userChoice;          // 用户当前选择（用于恢复执行）

    /**
     * 创建新的执行状态
     */
    public static FlowExecutionState create(String flowId, String flowName, String initialChainId) {
        return FlowExecutionState.builder()
                .flowId(flowId)
                .flowName(flowName)
                .currentChainId(initialChainId)
                .path(new java.util.ArrayList<>())
                .context(new java.util.HashMap<>())
                .status(ExecutionResult.ExecutionStatus.NEED_INPUT)
                .build();
    }

    /**
     * 添加选择到路径
     */
    public void addToPath(String choice) {
        if (path == null) {
            path = new java.util.ArrayList<>();
        }
        path.add(choice);
    }

    /**
     * 判断是否已完成
     */
    public boolean isCompleted() {
        return status == ExecutionResult.ExecutionStatus.COMPLETED;
    }

    /**
     * 标记为完成
     */
    public void markCompleted() {
        this.status = ExecutionResult.ExecutionStatus.COMPLETED;
    }

    /**
     * 标记为错误
     */
    public void markError(String message) {
        this.status = ExecutionResult.ExecutionStatus.ERROR;
    }
}
