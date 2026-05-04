package learning.authflow.exception;

/**
 * 无效输入异常
 */
public class InvalidInputException extends AuthflowException {
    public InvalidInputException(String message) {
        super("BadRequest", "InvalidInput", message, 400);
    }
}
