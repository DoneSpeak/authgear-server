package learning.authflow.exception;

/**
 * Accept-Loop 超过最大迭代次数时抛出。
 */
public class AcceptLoopLimitExceededException extends AuthflowException {
    private static final String NAME = "AcceptLoopLimitExceeded";
    private static final String REASON = "AcceptLoopLimitExceeded";
    private static final int CODE = 500;

    public AcceptLoopLimitExceededException(String message) {
        super(NAME, REASON, message, CODE);
    }
}
