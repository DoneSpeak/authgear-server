package com.liteflow.auth.component;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 身份识别组件
 * 执行身份识别逻辑
 */
@Slf4j
@Component
@LiteflowComponent("identifyComponent")
public class IdentifyComponent extends NodeComponent {

    @Override
    public void process() {
        String chainId = this.getChainId();
        log.info("执行身份识别 - chain: {}", chainId);

        // 这里可以实现具体的身份识别逻辑
        // 例如: OAuth 回调处理、邮箱格式验证等

        // 目前作为占位，实际项目中实现具体逻辑
    }
}
