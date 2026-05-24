package com.liteflow.auth.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 流程结束组件
 * 标记流程完成
 */
@Slf4j
@Component
@LiteflowComponent("flowEndComponent")
public class FlowEndComponent extends NodeComponent {

    @Override
    public void process() {
        log.info("流程执行完成 - chain: {}", this.getChainId());
        // 流程结束，设置完成标记到上下文
        this.getSlot().setResponseData("COMPLETED");
    }
}
