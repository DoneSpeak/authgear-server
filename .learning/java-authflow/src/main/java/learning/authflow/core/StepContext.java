package learning.authflow.core;

import learning.authflow.model.FlowType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 步骤运行时上下文 - 只包含流程相关信息
 * SRP：不负责HTTP或环境相关数据，也不负责输入解析
 */
@Getter
@Builder
public class StepContext {
    // 流程标识（从FlowInstance复制）
    private final String flowId;
    private final FlowType flowType;
    private final String flowName;

    // 当前节点信息
    private final FlowNode currentNode;
    private final int currentNodeIndex;

    // 用户标识（跨步骤传递）
    private final String userId;
    private final String identityId;

    // 跨步骤共享的 Session 数据（从 Redis 加载，可被更新）
    @Setter
    private Session session;

    // 本次执行的临时属性
    private final Map<String, Object> attributes;
}
