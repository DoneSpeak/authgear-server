package learning.authflow.intent;

import java.util.Map;

/**
 * Intent 工厂接口。
 * 每个具体的 Intent 实现类都应该有一个对应的工厂实现此接口，
 * 并注册为 Spring Bean，以便 IntentRegistry 自动发现。
 */
public interface IntentFactory {
    /**
     * 返回 Intent 类型标识，如 "UseAuthenticatorOOBOTP"
     */
    String getKind();

    /**
     * 检查是否支持给定的 authentication 字符串。
     * 例如 "primary_oob_otp_email" 应该被 UseAuthenticatorOobOtpIntentFactory 支持。
     */
    boolean supportsAuthentication(String authentication);

    /**
     * 创建 Intent 实例。
     * @param params 构造参数
     * @return Intent 实例
     */
    Intent create(Map<String, Object> params);
}
