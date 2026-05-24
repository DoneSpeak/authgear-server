package com.liteflow.auth.exception;

import com.liteflow.auth.model.Option;
import lombok.Getter;

import java.util.List;

/**
 * 暂停执行异常 - 当需要用户输入时抛出
 */
@Getter
public class PauseExecutionException extends RuntimeException {
    private final List<Option> options;
    private final String currentChainId;
    private final String currentNodeId;

    public PauseExecutionException(List<Option> options, String currentChainId, String currentNodeId) {
        super("等待用户输入");
        this.options = options;
        this.currentChainId = currentChainId;
        this.currentNodeId = currentNodeId;
    }

    public PauseExecutionException(List<Option> options) {
        this(options, null, null);
    }
}
