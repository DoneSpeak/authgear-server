package learning.authflow.core;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程执行上下文 - 传递执行状态
 * SRP：只负责上下文传递，不持久化
 */
@Data
@Builder
public class FlowContext {
    // 当前流程实例
    private FlowInstance flowInstance;

    // 当前执行的Intent信息
    private String intentKind;
    private String stepType;

    // 临时状态（内存级，不持久化）
    @Builder.Default
    private Map<String, Object> transientState = new HashMap<>();

    // 从FlowInstance获取当前节点
    public FlowNode getCurrentNode() {
        return flowInstance != null ? flowInstance.getCurrentNode() : null;
    }

    // 添加上下文数据
    public void setAttribute(String key, Object value) {
        transientState.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) transientState.get(key);
    }
}
