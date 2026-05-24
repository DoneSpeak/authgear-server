package com.liteflow.auth.exception;

/**
 * 流程未找到异常
 */
public class FlowNotFoundException extends FlowExecutionException {
    private final String flowName;

    public FlowNotFoundException(String flowName) {
        super("流程未找到: " + flowName);
        this.flowName = flowName;
    }

    public String getFlowName() {
        return flowName;
    }
}
