package learning.authflow.provider.inmemory;

import learning.authflow.provider.PasswordAuthenticatorProvider;

import java.util.Map;

/**
 * 内存密码认证器实现
 * 用于测试环境
 */
public class InMemoryPasswordAuthenticatorProvider implements PasswordAuthenticatorProvider {
    private final Map<String, String> passwordStore; // key: userId, value: password

    public InMemoryPasswordAuthenticatorProvider(Map<String, String> passwordStore) {
        this.passwordStore = passwordStore;
    }

    @Override
    public boolean verifyPassword(String userId, String password) {
        String storedPassword = passwordStore.get(userId);
        if (storedPassword == null) {
            return false;
        }
        return storedPassword.equals(password);
    }
}
