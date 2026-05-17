package com.routor.engine;

import com.routor.model.*;

import java.util.*;

/**
 * 栈执行器 - 管理执行栈和步骤推进
 */
public class StackExecutor {

    /**
     * 初始化流程实例
     */
    public void initialize(FlowInstance instance, FlowDefinition definition) {
        if (definition.getSteps() != null && !definition.getSteps().isEmpty()) {
            StackFrame frame = new StackFrame(0, definition.getSteps(), null);
            instance.getStack().push(frame);
        }
    }

    /**
     * 获取当前可选选项
     */
    public List<Option> getCurrentOptions(FlowInstance instance) {
        StackFrame frame = instance.currentFrame();
        if (frame == null) {
            return Collections.emptyList();
        }

        StepDefinition currentStep = frame.currentStep();
        if (currentStep == null || !currentStep.hasOneOf()) {
            return Collections.emptyList();
        }

        List<Option> options = new ArrayList<>();
        for (BranchDefinition branch : currentStep.getOneOf()) {
            String id = branch.getId();
            String type = branch.getIdentification() != null ? "identification" : "authentication";
            options.add(new Option(id, type, capitalize(id), branch.hasSubSteps()));
        }

        return options;
    }

    /**
     * 处理用户输入，推进流程
     */
    public void processInput(FlowInstance instance, String input) {
        if (instance.isComplete()) {
            throw new IllegalStateException("Flow is already completed");
        }

        StackFrame frame = instance.currentFrame();
        StepDefinition currentStep = frame.currentStep();

        if (currentStep == null) {
            throw new IllegalStateException("No current step");
        }

        // 查找匹配的分支
        BranchDefinition selectedBranch = findBranch(currentStep.getOneOf(), input);
        if (selectedBranch == null) {
            instance.setState(State.ERROR);
            throw new IllegalArgumentException("Invalid selection: " + input);
        }

        // 记录选择
        frame.setSelectedBranch(input);
        instance.getPath().add(input);

        // 如果分支有子步骤，压入新栈帧
        if (selectedBranch.hasSubSteps()) {
            StackFrame newFrame = new StackFrame(0, selectedBranch.getSteps(), null);
            instance.getStack().push(newFrame);
        } else {
            // 否则推进当前步骤
            advanceStep(instance);
        }
    }

    /**
     * 检查是否完成
     */
    public boolean isComplete(FlowInstance instance) {
        return instance.isComplete();
    }

    /**
     * 查找匹配的分支
     */
    private BranchDefinition findBranch(List<BranchDefinition> branches, String input) {
        if (branches == null) return null;
        for (BranchDefinition branch : branches) {
            if (input.equals(branch.getId())) {
                return branch;
            }
        }
        return null;
    }

    /**
     * 推进到下一步骤
     */
    private void advanceStep(FlowInstance instance) {
        StackFrame frame = instance.currentFrame();
        frame.setStepIndex(frame.getStepIndex() + 1);

        // 如果当前层级的步骤全部完成，弹出栈
        if (frame.getStepIndex() >= frame.getSteps().size()) {
            instance.getStack().pop();

            // 递归检查父层级
            if (!instance.getStack().isEmpty()) {
                advanceStep(instance);
            } else {
                instance.setState(State.COMPLETED);
            }
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
