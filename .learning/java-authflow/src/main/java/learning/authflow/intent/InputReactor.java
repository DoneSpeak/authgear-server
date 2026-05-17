package learning.authflow.intent;

import learning.authflow.core.FlowContext;
import learning.authflow.input.AuthflowInput;

public interface InputReactor {
    InputSchema canReactTo(FlowContext context);
    ReactResult reactTo(FlowContext context, AuthflowInput input);
}
