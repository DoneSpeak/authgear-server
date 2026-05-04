package learning.authflow.core;

/**
 * 流程会话数据 - 跨步骤共享的上下文数据
 * 使用 record 实现不可变性，通过 with 方法创建新实例
 */
public record Session(String flowId, String identification, String loginId, String userId) {

    public Session(String flowId) {
        this(flowId, null, null, null);
    }

    public Session withIdentification(String identification) {
        return new Session(flowId, identification, loginId, userId);
    }

    public Session withLoginId(String loginId) {
        return new Session(flowId, identification, loginId, userId);
    }

    public Session withUserId(String userId) {
        return new Session(flowId, identification, loginId, userId);
    }
}
