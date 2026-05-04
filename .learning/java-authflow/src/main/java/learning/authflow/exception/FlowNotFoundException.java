package learning.authflow.exception;

/**
 * 流程未找到异常
 */
public class FlowNotFoundException extends AuthflowException {
    public FlowNotFoundException(String message) {
        super("NotFound", "FlowNotFound", message, 404);
    }
}
