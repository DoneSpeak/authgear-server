package learning.authflow.intent;

import learning.authflow.milestone.Milestone;
import java.util.Map;

public interface Intent extends InputReactor {
    String getKind();
    
    default boolean supportsAuthentication(String authentication) {
        return false;
    }
    
    default void addMilestone(Milestone milestone) {}
}
