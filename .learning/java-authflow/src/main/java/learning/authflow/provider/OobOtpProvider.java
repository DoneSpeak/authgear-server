package learning.authflow.provider;

/**
 * OOB OTP 提供者接口
 * 生成、发送和验证 OTP
 */
public interface OobOtpProvider {
    /**
     * 生成并发送 OTP 到指定渠道
     * @param loginId 登录ID
     * @param channel 渠道（如 sms, email）
     * @return 生成的 OTP
     */
    String generateOtp(String loginId, String channel);

    /**
     * 发送 OTP 到指定渠道
     * @param loginId 登录ID
     * @param channel 渠道（如 sms, email）
     * @param otp 一次性验证码
     * @return 发送是否成功
     */
    boolean sendOtp(String loginId, String channel, String otp);

    /**
     * 验证 OTP
     * @param loginId 登录ID
     * @param channel 渠道（如 sms, email）
     * @param otp 一次性验证码
     * @return 验证是否成功
     */
    boolean verifyOtp(String loginId, String channel, String otp);
}
