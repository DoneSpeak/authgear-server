package learning.userprofile.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * One entry: pointer -> access control.
 * Mirrors Authgear StandardAttributesAccessControlConfig.
 */
@Data
public class StandardAttributesAccessControlEntry {
    /** JSON pointer, e.g. /email, /given_name. Must be one of allowed standard pointers. */
    @NotBlank
    private String pointer;

    @NotNull
    @Valid
    private UserProfileAttributesAccessControl accessControl;

    /** Allowed standard pointers (OIDC + address). */
    public static final Set<String> ALLOWED_POINTERS = Set.of(
            "/email", "/phone_number", "/preferred_username",
            "/family_name", "/given_name", "/picture", "/gender", "/birthdate",
            "/zoneinfo", "/locale", "/name", "/nickname", "/middle_name",
            "/profile", "/website", "/address"
    );
}
