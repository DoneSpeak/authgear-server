package learning.authflow.exception;

/**
 * 无效状态令牌异常
 */
public class InvalidStateTokenException extends AuthflowException {
    public InvalidStateTokenException(String message) {
        super("Unauthorized", "InvalidStateToken", message, 401);
    }
}
