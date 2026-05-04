package learning.userprofile.config;

import lombok.Data;

/**
 * Per-attribute access control for end_user, bearer, portal_ui.
 * Mirrors Authgear UserProfileAttributesAccessControl.
 */
@Data
public class UserProfileAttributesAccessControl {
    private AccessControlLevel endUser = AccessControlLevel.HIDDEN;
    private AccessControlLevel bearer = AccessControlLevel.HIDDEN;
    private AccessControlLevel portalUi = AccessControlLevel.READWRITE;
}
