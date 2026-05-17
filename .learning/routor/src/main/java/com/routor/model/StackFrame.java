package com.routor.model;

import java.util.List;

/**
 * 执行栈帧 - 表示一层步骤执行上下文
 */
public class StackFrame {
    private int stepIndex;                    // 当前步骤在 steps 列表中的索引
    private List<StepDefinition> steps;       // 当前层级的步骤列表
    private String selectedBranch;            // 当前步骤选择的分支

    public StackFrame() {}

    public StackFrame(int stepIndex, List<StepDefinition> steps, String selectedBranch) {
        this.stepIndex = stepIndex;
        this.steps = steps;
        this.selectedBranch = selectedBranch;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }

    public String getSelectedBranch() {
        return selectedBranch;
    }

    public void setSelectedBranch(String selectedBranch) {
        this.selectedBranch = selectedBranch;
    }

    /**
     * 获取当前步骤定义
     */
    public StepDefinition currentStep() {
        if (steps == null || stepIndex >= steps.size()) {
            return null;
        }
        return steps.get(stepIndex);
    }
}
