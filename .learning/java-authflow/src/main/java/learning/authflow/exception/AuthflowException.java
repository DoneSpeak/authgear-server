package learning.authflow.exception;

import lombok.Getter;

import java.util.Map;

/**
 * 认证流程基础异常
 */
@Getter
public class AuthflowException extends RuntimeException {
    private final String name;
    private final String reason;
    private final int code;
    private final Map<String, Object> info;

    public AuthflowException(String name, String reason, String message, int code) {
        super(message);
        this.name = name;
        this.reason = reason;
        this.code = code;
        this.info = null;
    }

    public AuthflowException(String name, String reason, String message, int code, Map<String, Object> info) {
        super(message);
        this.name = name;
        this.reason = reason;
        this.code = code;
        this.info = info;
    }
}
