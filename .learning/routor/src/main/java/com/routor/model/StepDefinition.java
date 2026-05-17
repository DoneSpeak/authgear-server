package com.routor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 步骤定义
 */
public class StepDefinition {
    private String type;                      // "identify" | "authenticate"
    private List<BranchDefinition> oneOf;       // 可选分支
    private List<StepDefinition> steps;         // 子步骤（用于直接嵌套）

    public StepDefinition() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<BranchDefinition> getOneOf() {
        return oneOf;
    }

    public void setOneOf(List<BranchDefinition> oneOf) {
        this.oneOf = oneOf;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }

    @JsonIgnore
    public boolean hasOneOf() {
        return oneOf != null && !oneOf.isEmpty();
    }

    @JsonIgnore
    public boolean hasSubSteps() {
        return steps != null && !steps.isEmpty();
    }
}
