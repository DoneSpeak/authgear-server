package learning.authflow.milestone;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 里程碑 - 记录关键状态变更
 * 用于跟踪流程执行过程中的重要节点
 */
@Data
@Builder
public class Milestone {
    // 里程碑类型
    private String type;

    // 关联的Intent类型
    private String intentKind;

    // 关联的步骤类型
    private String stepType;

    // 创建时间
    @Builder.Default
    private Instant createdAt = Instant.now();

    // 里程碑数据
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    // 是否完成
    @Builder.Default
    private boolean completed = false;

    // 完成时间
    private Instant completedAt;

    // 标记为完成
    public void markCompleted() {
        this.completed = true;
        this.completedAt = Instant.now();
    }

    // 添加里程碑数据
    public void addData(String key, Object value) {
        this.data.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        return (T) this.data.get(key);
    }
}
