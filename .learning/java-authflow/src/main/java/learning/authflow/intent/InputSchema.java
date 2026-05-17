package learning.authflow.intent;

import java.util.Map;

public interface InputSchema {
    String getType();
    Map<String, Object> getProperties();
}
