package learning.authflow.provider;

/**
 * 登录ID查询接口
 * 根据 login_id 和 identification 类型查找用户
 */
public interface LoginIdProvider {
    /**
     * 根据 login_id 和 identification 类型查找用户
     * @param loginId 登录ID（如邮箱、手机号）
     * @param identification 标识类型（如 email, phone）
     * @return 用户ID，未找到返回 null
     */
    String findUserId(String loginId, String identification);
}
