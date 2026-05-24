package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 执行结果模型 - 返回给客户端的响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionResult {
    private String flowId;
    private ExecutionStatus status;
    private List<String> path;
    private List<Option> options;
    private String message;
    private Map<String, Object> context;

    public enum ExecutionStatus {
        NEED_INPUT,   // 需要用户选择
        COMPLETED,    // 流程完成
        ERROR         // 执行错误
    }

    /**
     * 创建需要输入的结果
     */
    public static ExecutionResult needInput(String flowId, List<Option> options, List<String> path) {
        return ExecutionResult.builder()
                .flowId(flowId)
                .status(ExecutionStatus.NEED_INPUT)
                .options(options)
                .path(path)
                .build();
    }

    /**
     * 创建完成的结果
     */
    public static ExecutionResult completed(String flowId, List<String> path) {
        return ExecutionResult.builder()
                .flowId(flowId)
                .status(ExecutionStatus.COMPLETED)
                .path(path)
                .message("流程完成")
                .build();
    }

    /**
     * 创建错误的结果
     */
    public static ExecutionResult error(String flowId, String message) {
        return ExecutionResult.builder()
                .flowId(flowId)
                .status(ExecutionStatus.ERROR)
                .message(message)
                .build();
    }
}
