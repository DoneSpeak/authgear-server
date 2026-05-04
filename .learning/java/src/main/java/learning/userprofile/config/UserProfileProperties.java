package learning.userprofile.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * User profile config bound from application.yml.
 * Mirrors Authgear UserProfileConfig (standard_attributes + custom_attributes).
 */
@ConfigurationProperties(prefix = "app.user-profile")
@Validated
@Data
public class UserProfileProperties {
    @Valid
    private StandardAttributesConfig standardAttributes = new StandardAttributesConfig();
    @Valid
    private CustomAttributesConfig customAttributes = new CustomAttributesConfig();

    @PostConstruct
    public void setDefaults() {
        standardAttributes.setDefaults();
        if (customAttributes.getAttributes() == null) return;
        customAttributes.getAttributes().forEach(attr -> {
            if (attr.getAccessControl() == null) {
                attr.setAccessControl(new UserProfileAttributesAccessControl());
            }
            if (attr.getAccessControl().getPortalUi() == null) {
                attr.getAccessControl().setPortalUi(AccessControlLevel.READWRITE);
            }
        });
    }
}
