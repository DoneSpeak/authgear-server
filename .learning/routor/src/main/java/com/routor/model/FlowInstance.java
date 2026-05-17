package com.routor.model;

import java.util.*;

/**
 * 流程实例 - 运行时状态
 */
public class FlowInstance {
    private String flowId;
    private String flowName;
    private Deque<StackFrame> stack;
    private List<String> path;
    private State state;
    private Map<String, Object> context;

    public FlowInstance() {
        this.stack = new ArrayDeque<>();
        this.path = new ArrayList<>();
        this.state = State.NEED_INPUT;
        this.context = new HashMap<>();
    }

    public String getFlowId() {
        return flowId;
    }

    public void setFlowId(String flowId) {
        this.flowId = flowId;
    }

    public String getFlowName() {
        return flowName;
    }

    public void setFlowName(String flowName) {
        this.flowName = flowName;
    }

    public Deque<StackFrame> getStack() {
        return stack;
    }

    public void setStack(Deque<StackFrame> stack) {
        this.stack = stack;
    }

    public List<String> getPath() {
        return path;
    }

    public void setPath(List<String> path) {
        this.path = path;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    /**
     * 获取当前栈帧
     */
    public StackFrame currentFrame() {
        return stack.peek();
    }

    /**
     * 检查是否已完成
     */
    public boolean isComplete() {
        return state == State.COMPLETED || stack.isEmpty();
    }
}
