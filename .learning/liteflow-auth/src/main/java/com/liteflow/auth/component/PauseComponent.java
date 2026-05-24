package com.liteflow.auth.component;

import com.liteflow.auth.builder.LiteFlowChainBuilder;
import com.liteflow.auth.exception.PauseExecutionException;
import com.liteflow.auth.model.BranchDefinition;
import com.liteflow.auth.model.Option;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 暂停组件 - 当需要用户输入时抛出异常暂停执行
 */
@Slf4j
@Component
@LiteflowComponent("pauseForInput")
public class PauseComponent extends NodeComponent {

    @Autowired
    private LiteFlowChainBuilder chainBuilder;

    @Override
    public void process() {
        String currentChainId = this.getChainId();
        String currentNodeId = this.getNodeId();

        log.debug("在 chain [{}] node [{}] 暂停，等待用户输入", currentChainId, currentNodeId);

        // 从 chain 映射中获取当前可选项
        List<BranchDefinition> branches = chainBuilder.getCurrentBranches(currentChainId);

        if (branches == null || branches.isEmpty()) {
            log.warn("chain [{}] 没有可选项，继续执行", currentChainId);
            return;
        }

        // 获取步骤类型（从 context 或通过 chain 名称推断）
        String stepType = inferStepType(currentChainId);

        // 创建选项列表
        List<Option> options = branches.stream()
                .map(b -> Option.fromBranch(b, stepType))
                .toList();

        log.info("暂停执行，等待用户从以下选项中选择: {}",
                options.stream().map(Option::getId).toList());

        // 抛出暂停异常，包含当前选项
        throw new PauseExecutionException(options, currentChainId, currentNodeId);
    }

    /**
     * 从 chain ID 推断步骤类型
     */
    private String inferStepType(String chainId) {
        // 从 chain 名称推断步骤类型
        // 例如: default_login_flow_step_0 -> identify
        // 例如: default_login_flow_step_0_branch_0_step_0 -> authenticate

        if (chainId.contains("branch")) {
            // 嵌套步骤，通常是 authenticate
            return "authentication";
        } else {
            // 顶层步骤，通常是 identify
            return "identification";
        }
    }
}
