package com.routor.model;

import java.util.Collections;
import java.util.List;

/**
 * 执行结果
 */
public class ExecutionResult {
    private final State state;
    private final List<Option> options;
    private final List<String> path;
    private final String message;

    private ExecutionResult(State state, List<Option> options, List<String> path, String message) {
        this.state = state;
        this.options = options != null ? options : Collections.emptyList();
        this.path = path != null ? path : Collections.emptyList();
        this.message = message;
    }

    public static ExecutionResult needInput(List<Option> options, List<String> path) {
        return new ExecutionResult(State.NEED_INPUT, options, path, null);
    }

    public static ExecutionResult completed(List<String> path) {
        return new ExecutionResult(State.COMPLETED, null, path, "流程完成");
    }

    public static ExecutionResult error(String message, List<String> path) {
        return new ExecutionResult(State.ERROR, null, path, message);
    }

    public State getState() {
        return state;
    }

    public List<Option> getOptions() {
        return options;
    }

    public List<String> getPath() {
        return path;
    }

    public String getMessage() {
        return message;
    }

    public boolean isNeedInput() {
        return state == State.NEED_INPUT;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isError() {
        return state == State.ERROR;
    }
}
