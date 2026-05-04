package learning.userprofile.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * One custom attribute definition: id (storage key), pointer (API key), type, access control.
 * Mirrors Authgear CustomAttributesAttributeConfig.
 */
@Data
public class CustomAttributeDefinition {
    /** Unique id, used as key in DB (storage form). */
    @NotBlank
    private String id;
    /** JSON pointer for API (representation form), e.g. /x_company_id. Must not clash with standard. */
    @NotBlank
    private String pointer;
    @NotNull
    private CustomAttributeType type;
    @Valid
    private UserProfileAttributesAccessControl accessControl = new UserProfileAttributesAccessControl();

    /** For number/integer. */
    private Double minimum;
    private Double maximum;
    /** For enum type. */
    private List<String> enumValues;
}
