package learning.authflow.intent.impl;

import learning.authflow.intent.Intent;
import learning.authflow.intent.IntentFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * UseAuthenticatorOobOtpIntent 的工厂。
 * 支持所有包含 "oob_otp" 的 authentication 类型。
 */
@Component
public class UseAuthenticatorOobOtpIntentFactory implements IntentFactory {

    @Override
    public String getKind() {
        return "UseAuthenticatorOOBOTP";
    }

    @Override
    public boolean supportsAuthentication(String authentication) {
        return authentication != null && authentication.contains("oob_otp");
    }

    @Override
    public Intent create(Map<String, Object> params) {
        return new UseAuthenticatorOobOtpIntent(params);
    }
}
