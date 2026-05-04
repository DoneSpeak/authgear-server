package learning.authflow.core;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 状态令牌管理器
 */
public class StateTokenManager {
    private static final int TOKEN_LENGTH = 48;
    private static final String TOKEN_PREFIX = "authflowstate_";
    private final SecureRandom secureRandom;

    public StateTokenManager() {
        this.secureRandom = new SecureRandom();
    }

    /**
     * 生成高熵随机StateToken
     * 格式: authflowstate_<48字符随机Base64Url>
     * 不编码状态，仅作为客户端响应标识，不用于存储
     */
    public String generateToken() {
        byte[] bytes = new byte[36];  // 36字节 -> 48字符Base64Url
        secureRandom.nextBytes(bytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return TOKEN_PREFIX + randomPart;
    }

    public void validateTokenFormat(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new IllegalArgumentException("Invalid token format");
        }
        String randomPart = token.substring(TOKEN_PREFIX.length());
        if (randomPart.length() != TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid token length");
        }
        if (!randomPart.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("Invalid token characters");
        }
    }
}
