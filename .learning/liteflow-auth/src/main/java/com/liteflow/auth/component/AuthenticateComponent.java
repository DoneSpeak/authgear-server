package com.liteflow.auth.component;

import com.liteflow.auth.state.StateManager;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 认证组件
 * 执行具体的认证逻辑（密码验证、TOTP 验证等）
 */
@Slf4j
@Component
@LiteflowComponent("authenticateComponent")
public class AuthenticateComponent extends NodeComponent {

    @Autowired
    private StateManager stateManager;

    @Override
    public void process() {
        String chainId = this.getChainId();
        String flowId = this.getSlot().getRequestId();
        log.info("执行认证 - chain: {}, flowId: {}", chainId, flowId);

        // 从 Redis 获取认证方式
        String authMethod = stateManager.getAndClearUserChoice(flowId);
        if (authMethod != null) {
            log.info("认证方式: {}", authMethod);
        }

        // 目前作为占位，实际项目中实现具体逻辑
    }
}
