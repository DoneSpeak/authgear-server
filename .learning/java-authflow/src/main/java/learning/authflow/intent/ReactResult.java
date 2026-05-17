package learning.authflow.intent;

import learning.authflow.core.FlowNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReactResult {
    public enum Type {
        NEW_NODE, SUB_INTENT, COMPLETE, NEED_INPUT, ERROR, SAME_NODE
    }
    
    private final Type type;
    private final FlowNode node;
    private final Intent subIntent;
    private final Exception error;
    
    public static ReactResult newNode(FlowNode node) {
        return new ReactResult(Type.NEW_NODE, node, null, null);
    }
    
    public static ReactResult subIntent(Intent intent) {
        return new ReactResult(Type.SUB_INTENT, null, intent, null);
    }
    
    public static ReactResult complete() {
        return new ReactResult(Type.COMPLETE, null, null, null);
    }
    
    public static ReactResult needInput() {
        return new ReactResult(Type.NEED_INPUT, null, null, null);
    }
    
    public static ReactResult sameNode() {
        return new ReactResult(Type.SAME_NODE, null, null, null);
    }
    
    public static ReactResult error(Exception e) {
        return new ReactResult(Type.ERROR, null, null, e);
    }
}
