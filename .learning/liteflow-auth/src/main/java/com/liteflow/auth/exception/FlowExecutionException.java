package com.liteflow.auth.exception;

/**
 * 流程执行异常
 */
public class FlowExecutionException extends RuntimeException {
    public FlowExecutionException(String message) {
        super(message);
    }

    public FlowExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
