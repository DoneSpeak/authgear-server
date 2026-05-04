package learning.authflow.storage;

import learning.authflow.core.Session;

/**
 * Session 存储接口 - 专门负责 Session 的持久化
 */
public interface SessionStorage {
    /**
     * 保存 Session 到存储
     */
    void save(Session session);

    /**
     * 通过 flowId 获取或创建 Session
     * @return Session 对象，如果不存在返回新的空 Session
     */
    Session getOrCreate(String flowId);
}
