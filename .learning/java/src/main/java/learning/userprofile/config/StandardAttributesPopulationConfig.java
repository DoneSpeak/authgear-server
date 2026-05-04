package learning.userprofile.config;

import lombok.Data;

/**
 * When to populate standard attributes.
 * Mirrors Authgear StandardAttributesPopulationConfig.
 */
@Data
public class StandardAttributesPopulationConfig {
    /** none | on_signup */
    private String strategy = "on_signup";
}
