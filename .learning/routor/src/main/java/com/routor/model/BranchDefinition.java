package com.routor.model;

import java.util.List;

/**
 * 分支定义 - 表示 one_of 中的一个选项
 */
public class BranchDefinition {
    private String identification;
    private String authentication;
    private List<StepDefinition> steps;

    public BranchDefinition() {}

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public String getAuthentication() {
        return authentication;
    }

    public void setAuthentication(String authentication) {
        this.authentication = authentication;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }

    /**
     * 获取分支标识符（identification 或 authentication）
     */
    public String getId() {
        return identification != null ? identification : authentication;
    }

    public boolean hasSubSteps() {
        return steps != null && !steps.isEmpty();
    }
}
