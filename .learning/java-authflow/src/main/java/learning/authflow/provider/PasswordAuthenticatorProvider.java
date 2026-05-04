package learning.authflow.provider;

/**
 * 密码认证器接口
 * 验证用户密码
 */
public interface PasswordAuthenticatorProvider {
    /**
     * 验证用户密码
     * @param userId 用户ID
     * @param password 明文密码
     * @return 验证是否成功
     */
    boolean verifyPassword(String userId, String password);
}
