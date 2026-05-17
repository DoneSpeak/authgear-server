package com.routor.model;

import java.util.List;

/**
 * 流程定义 - 对应 YAML 中的一个流程
 */
public class FlowDefinition {
    private String name;
    private String type;
    private List<StepDefinition> steps;

    public FlowDefinition() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }
}
