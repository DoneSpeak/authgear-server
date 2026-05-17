package com.routor.engine;

import com.routor.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * 流程引擎 - 主入口
 */
public class FlowEngine {
    private final Map<String, FlowDefinition> definitions;
    private final StackExecutor executor;
    private final ObjectMapper objectMapper;

    public FlowEngine(Map<String, FlowDefinition> definitions) {
        this.definitions = definitions;
        this.executor = new StackExecutor();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 创建新流程实例
     */
    public FlowInstance create(String flowName) {
        FlowDefinition definition = definitions.get(flowName);
        if (definition == null) {
            throw new IllegalArgumentException("Flow not found: " + flowName);
        }

        FlowInstance instance = new FlowInstance();
        instance.setFlowId(generateId());
        instance.setFlowName(flowName);
        instance.setState(State.NEED_INPUT);

        executor.initialize(instance, definition);

        return instance;
    }

    /**
     * 执行流程
     */
    public ExecutionResult execute(FlowInstance instance, String input) {
        if (instance.isComplete()) {
            return ExecutionResult.completed(instance.getPath());
        }

        if (input != null) {
            try {
                executor.processInput(instance, input);
            } catch (IllegalArgumentException e) {
                return ExecutionResult.error(e.getMessage(), instance.getPath());
            }
        }

        if (instance.isComplete()) {
            return ExecutionResult.completed(instance.getPath());
        }

        return ExecutionResult.needInput(
            executor.getCurrentOptions(instance),
            instance.getPath()
        );
    }

    /**
     * 序列化流程实例
     */
    public String serialize(FlowInstance instance) {
        try {
            return objectMapper.writeValueAsString(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize flow instance", e);
        }
    }

    /**
     * 反序列化流程实例
     */
    public FlowInstance deserialize(String json) {
        try {
            return objectMapper.readValue(json, FlowInstance.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize flow instance", e);
        }
    }

    private String generateId() {
        return "flow_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
