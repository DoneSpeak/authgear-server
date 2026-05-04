package learning.authflow.model;

public enum StepType {
    IDENTIFY,
    AUTHENTICATE,
    VERIFY,
    CREATE_AUTHENTICATOR,
    USER_PROFILE,
    RECOVERY_CODE,
    PROMPT_CREATE_PASSKEY,
    FINISHED;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
