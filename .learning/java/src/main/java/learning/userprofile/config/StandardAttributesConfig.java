package learning.userprofile.config;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Config for standard attributes: population + per-pointer access control.
 * Mirrors Authgear StandardAttributesConfig.
 */
@Data
public class StandardAttributesConfig {
    private StandardAttributesPopulationConfig population = new StandardAttributesPopulationConfig();
    @Valid
    private List<StandardAttributesAccessControlEntry> accessControl = new ArrayList<>();

    /** Default readwrite pointers (commonly editable). */
    private static final List<String> DEFAULT_READWRITE_POINTERS = List.of(
            "/email", "/phone_number", "/preferred_username",
            "/family_name", "/given_name", "/picture", "/gender", "/birthdate",
            "/zoneinfo", "/locale"
    );
    /** Default hidden pointers. */
    private static final List<String> DEFAULT_HIDDEN_POINTERS = List.of(
            "/name", "/nickname", "/middle_name", "/profile", "/website", "/address"
    );

    /**
     * Apply defaults: add missing pointers with readwrite or hidden.
     */
    public void setDefaults() {
        var byPointer = accessControl.stream()
                .collect(Collectors.toMap(StandardAttributesAccessControlEntry::getPointer, e -> e));
        for (String p : DEFAULT_READWRITE_POINTERS) {
            if (!byPointer.containsKey(p)) {
                var entry = new StandardAttributesAccessControlEntry();
                entry.setPointer(p);
                var ac = new UserProfileAttributesAccessControl();
                ac.setEndUser(AccessControlLevel.HIDDEN);
                ac.setBearer(AccessControlLevel.READONLY);
                ac.setPortalUi(AccessControlLevel.READWRITE);
                entry.setAccessControl(ac);
                accessControl.add(entry);
                byPointer.put(p, entry);
            }
        }
        for (String p : DEFAULT_HIDDEN_POINTERS) {
            if (!byPointer.containsKey(p)) {
                var entry = new StandardAttributesAccessControlEntry();
                entry.setPointer(p);
                var ac = new UserProfileAttributesAccessControl();
                ac.setEndUser(AccessControlLevel.HIDDEN);
                ac.setBearer(AccessControlLevel.HIDDEN);
                ac.setPortalUi(AccessControlLevel.HIDDEN);
                entry.setAccessControl(ac);
                accessControl.add(entry);
            }
        }
    }

    /** Get level for (pointer, role). */
    public AccessControlLevel getLevel(String pointer, ProfileRole role) {
        return accessControl.stream()
                .filter(e -> pointer.equals(e.getPointer()))
                .findFirst()
                .map(e -> levelFor(e.getAccessControl(), role))
                .orElse(AccessControlLevel.HIDDEN);
    }

    private static AccessControlLevel levelFor(UserProfileAttributesAccessControl ac, ProfileRole role) {
        return switch (role) {
            case END_USER -> ac.getEndUser();
            case BEARER -> ac.getBearer();
            case PORTAL_UI -> ac.getPortalUi();
        };
    }
}
