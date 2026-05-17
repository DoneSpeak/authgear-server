package learning.authflow.exception;

/**
 * Intent 执行过程中发生错误。
 */
public class IntentExecutionException extends AuthflowException {
    private static final String NAME = "IntentExecutionError";
    private static final String REASON = "IntentExecutionFailed";
    private static final int CODE = 500;

    public IntentExecutionException(String message, Throwable cause) {
        super(NAME, REASON, message, CODE);
        initCause(cause);
    }
}
