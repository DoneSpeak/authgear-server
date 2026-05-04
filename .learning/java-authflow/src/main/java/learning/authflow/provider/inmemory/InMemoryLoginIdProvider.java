package learning.authflow.provider.inmemory;

import learning.authflow.provider.LoginIdProvider;

import java.util.Map;

/**
 * 内存登录ID查询实现
 * 用于测试环境
 */
public class InMemoryLoginIdProvider implements LoginIdProvider {
    private final Map<String, String> userStore; // key: loginId, value: userId

    public InMemoryLoginIdProvider(Map<String, String> userStore) {
        this.userStore = userStore;
    }

    @Override
    public String findUserId(String loginId, String identification) {
        return userStore.get(loginId);
    }
}
