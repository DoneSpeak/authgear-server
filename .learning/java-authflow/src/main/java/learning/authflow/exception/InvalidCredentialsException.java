package learning.authflow.exception;

/**
 * 无效凭证异常
 */
public class InvalidCredentialsException extends AuthflowException {
    public InvalidCredentialsException(String message) {
        super("Unauthorized", "InvalidCredentials", message, 401);
    }
}
