package com.liteflow.auth.component;

import com.liteflow.auth.builder.LiteFlowChainBuilder;
import com.liteflow.auth.state.FlowExecutionState;
import com.liteflow.auth.state.StateManager;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeSwitchComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 分支选择 SWITCH 组件
 * 根据用户选择跳转到对应的分支 chain
 */
@Slf4j
@Component
@LiteflowComponent("branchSwitch")
public class BranchSwitchComponent extends NodeSwitchComponent {

    @Autowired
    private LiteFlowChainBuilder chainBuilder;

    @Autowired
    private StateManager stateManager;

    @Override
    public String processSwitch() throws Exception {
        String currentChainId = this.getChainId();
        String flowId = this.getSlot().getRequestId();

        // 从 Redis 获取用户选择
        String userChoice = stateManager.getAndClearUserChoice(flowId);

        if (userChoice == null || userChoice.isEmpty()) {
            throw new IllegalStateException("用户选择为空，无法决定分支");
        }

        log.info("在 chain [{}] 根据用户选择 [{}] 切换分支", currentChainId, userChoice);

        // 查找目标 chain
        String targetChainId = chainBuilder.getTargetChainId(currentChainId, userChoice);

        if (targetChainId == null) {
            throw new IllegalArgumentException("无效的用户选择: " + userChoice);
        }

        if ("FLOW_END".equals(targetChainId)) {
            log.info("到达叶子节点，流程结束");
            return "flowEndComponent";
        }

        log.info("跳转到分支 chain: {}", targetChainId);
        return targetChainId;
    }
}
