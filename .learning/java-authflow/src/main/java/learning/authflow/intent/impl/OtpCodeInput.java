package learning.authflow.intent.impl;

import lombok.Data;

/**
 * OTP 验证码输入数据
 */
public class OtpCodeInput {
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
