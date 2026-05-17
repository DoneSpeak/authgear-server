package learning.authflow.provider.inmemory;

import learning.authflow.provider.OobOtpProvider;

import java.security.SecureRandom;
import java.util.Map;

/**
 * 内存 OOB OTP 实现
 * 用于测试环境
 */
public class InMemoryOobOtpProvider implements OobOtpProvider {
    private final Map<String, String> otpStore; // key: loginId:channel, value: otp
    private final SecureRandom random = new SecureRandom();

    public InMemoryOobOtpProvider(Map<String, String> otpStore) {
        this.otpStore = otpStore;
    }

    @Override
    public String generateOtp(String loginId, String channel) {
        // 生成 6 位数字 OTP
        String otp = String.format("%06d", random.nextInt(1000000));
        otpStore.put(loginId + ":" + channel, otp);
        return otp;
    }

    @Override
    public boolean sendOtp(String loginId, String channel, String otp) {
        otpStore.put(loginId + ":" + channel, otp);
        return true;
    }

    @Override
    public boolean verifyOtp(String loginId, String channel, String otp) {
        String storedOtp = otpStore.get(loginId + ":" + channel);
        if (storedOtp == null) {
            return false;
        }
        if (storedOtp.equals(otp)) {
            otpStore.remove(loginId + ":" + channel);
            return true;
        }
        return false;
    }
}
