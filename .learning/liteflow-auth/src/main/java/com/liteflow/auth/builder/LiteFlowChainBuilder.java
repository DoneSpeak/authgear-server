package com.liteflow.auth.builder;

import com.liteflow.auth.model.BranchDefinition;
import com.liteflow.auth.model.FlowDefinition;
import com.liteflow.auth.model.StepDefinition;
import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.core.FlowExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LiteFlow Chain 动态构建器
 * 将 FlowDefinition 转换为 LiteFlow Chain
 */
@Slf4j
@Component
public class LiteFlowChainBuilder {

    @Autowired
    private FlowExecutor flowExecutor;

    // 存储 flow 中所有 chain 的映射关系
    private final Map<String, ChainMapping> chainMappings = new HashMap<>();

    /**
     * Chain 映射关系
     */
    public static class ChainMapping {
        private final String chainId;
        private final String flowName;
        private final int stepIndex;
        private final List<BranchDefinition> branches;

        public ChainMapping(String chainId, String flowName, int stepIndex, List<BranchDefinition> branches) {
            this.chainId = chainId;
            this.flowName = flowName;
            this.stepIndex = stepIndex;
            this.branches = branches;
        }

        public String getChainId() { return chainId; }
        public String getFlowName() { return flowName; }
        public int getStepIndex() { return stepIndex; }
        public List<BranchDefinition> getBranches() { return branches; }
    }

    /**
     * 构建流程的所有 chain
     */
    public void buildFlowChains(FlowDefinition flowDefinition) {
        String flowName = flowDefinition.getName();
        log.info("开始构建流程 '{}' 的 chains", flowName);

        // 1. 首先收集所有 chain ID
        collectChainIds(flowDefinition);

        // 2. 创建根 chain
        String rootChainId = flowName;

        if (flowDefinition.getSteps() != null && !flowDefinition.getSteps().isEmpty()) {
            // 3. 递归构建子 chain
            String firstSubChainId = buildStepChain(flowName, 0, flowDefinition.getSteps().get(0));

            // 4. 根 chain 指向第一个步骤 chain
            // 子 chain 需要用 @ 前缀引用
            LiteFlowChainELBuilder.createChain()
                    .setChainName(rootChainId)
                    .setEL("THEN(@" + firstSubChainId + ")")
                    .build();

            log.info("流程 '{}' 的 chains 构建完成", flowName);
        }
    }

    /**
     * 第一步：收集所有 chain ID
     */
    public void collectChainIds(FlowDefinition flowDefinition) {
        collectStepChains(flowDefinition.getName(), 0, flowDefinition.getSteps());
    }

    private void collectStepChains(String flowName, int startIndex, List<StepDefinition> steps) {
        if (steps == null) return;

        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            String chainId = String.format("%s_step_%d", flowName, startIndex + i);

            if (step.hasOneOf()) {
                // 保存映射关系
                chainMappings.put(chainId, new ChainMapping(chainId, flowName, startIndex + i, step.getOneOf()));

                // 收集分支 chain
                for (int j = 0; j < step.getOneOf().size(); j++) {
                    BranchDefinition branch = step.getOneOf().get(j);
                    if (!branch.isLeaf()) {
                        String branchFlowName = flowName + "_step_" + (startIndex + i) + "_branch_" + j;
                        collectStepChains(branchFlowName, 0, branch.getSteps());
                    }
                }
            }
        }
    }

    /**
     * 构建步骤 chain
     * 返回该 chain 的 ID
     */
    private String buildStepChain(String flowName, int stepIndex, StepDefinition step) {
        String chainId = String.format("%s_step_%d", flowName, stepIndex);

        // 如果有 one_of 分支
        if (step.hasOneOf()) {
            List<BranchDefinition> branches = step.getOneOf();

            // 先为每个分支创建子 chain（如果有子步骤）
            for (int i = 0; i < branches.size(); i++) {
                BranchDefinition branch = branches.get(i);
                if (!branch.isLeaf()) {
                    // 递归构建子步骤 chain
                    buildBranchChain(flowName, stepIndex, i, branch);
                }
            }

            // 创建当前 step 的 chain: pause -> switch
            // 使用 EL 格式: THEN(pauseForInput, branchSwitch)
            String el = "THEN(pauseForInput, branchSwitch)";

            LiteFlowChainELBuilder.createChain()
                    .setChainName(chainId)
                    .setEL(el)
                    .build();

            log.debug("创建 SWITCH chain: {}", chainId);
        } else {
            // 没有分支，直接执行逻辑节点
            String componentId = step.getType() + "Component";

            LiteFlowChainELBuilder.createChain()
                    .setChainName(chainId)
                    .setEL("THEN(" + componentId + ")")
                    .build();

            log.debug("创建普通 chain: {}", chainId);
        }

        return chainId;
    }

    /**
     * 构建分支的子 chain
     */
    private String buildBranchChain(String flowName, int parentStepIndex, int branchIndex, BranchDefinition branch) {
        String branchChainId = String.format("%s_step_%d_branch_%d", flowName, parentStepIndex, branchIndex);

        if (branch.getSteps() != null && !branch.getSteps().isEmpty()) {
            // 递归构建第一个子步骤
            String firstSubStepChainId = buildStepChain(
                    flowName + "_step_" + parentStepIndex + "_branch_" + branchIndex,
                    0,
                    branch.getSteps().get(0)
            );

            // 分支 chain 指向第一个子步骤
            // 子 chain 需要用 @ 前缀引用
            LiteFlowChainELBuilder.createChain()
                    .setChainName(branchChainId)
                    .setEL("THEN(@" + firstSubStepChainId + ")")
                    .build();
        }

        return branchChainId;
    }

    /**
     * 获取 chain 映射关系
     */
    public ChainMapping getChainMapping(String chainId) {
        return chainMappings.get(chainId);
    }

    /**
     * 根据用户选择获取目标 chain ID
     */
    public String getTargetChainId(String currentChainId, String userChoice) {
        ChainMapping mapping = chainMappings.get(currentChainId);
        if (mapping == null || mapping.getBranches() == null) {
            return null;
        }

        List<BranchDefinition> branches = mapping.getBranches();
        for (int i = 0; i < branches.size(); i++) {
            BranchDefinition branch = branches.get(i);
            if (branch.getBranchId().equals(userChoice)) {
                // 如果分支有子步骤，返回分支 chain
                if (!branch.isLeaf()) {
                    return String.format("%s_step_%d_branch_%d", mapping.getFlowName(), mapping.getStepIndex(), i);
                }
                // 如果是叶子节点，返回完成标记
                return "FLOW_END";
            }
        }

        return null;
    }

    /**
     * 根据 chainId 获取当前选项列表
     */
    public List<BranchDefinition> getCurrentBranches(String chainId) {
        ChainMapping mapping = chainMappings.get(chainId);
        return mapping != null ? mapping.getBranches() : null;
    }
}
