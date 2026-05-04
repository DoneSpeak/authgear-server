package learning.authflow.storage;

import learning.authflow.core.FlowInstance;

/**
 * 状态存储接口 - 符合ISP原则，只定义必要方法
 */
public interface StateStorage {
    /**
     * 创建/保存 Flow 状态
     * 每次状态变更调用，创建新的 state key
     */
    void createFlow(FlowInstance flow);

    /**
     * 通过 stateToken 查询 Flow
     * @throws InvalidStateTokenException 如果 token 无效或过期
     */
    FlowInstance getFlowByStateToken(String stateToken);

    /**
     * 删除 Flow（标记删除，state keys 自然过期）
     */
    void deleteFlow(String flowId);
}
