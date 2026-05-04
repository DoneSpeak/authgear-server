package learning.userprofile.service;

import learning.userprofile.config.OidcScopeClaims;
import learning.userprofile.config.ProfileRole;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds OIDC-style userinfo response (GET /oauth2/userinfo).
 * First-party: all standard + custom attributes (BEARER role).
 * Third-party: only scope-allowed standard claims; no custom.
 */
@Service
public class UserinfoService {

    private final StandardAttributesService standardAttributesService;
    private final CustomAttributesService customAttributesService;

    public UserinfoService(StandardAttributesService standardAttributesService,
                           CustomAttributesService customAttributesService) {
        this.standardAttributesService = standardAttributesService;
        this.customAttributesService = customAttributesService;
    }

    /**
     * Returns userinfo as a map suitable for JSON response.
     * - sub: always userId
     * - first-party: all standard_attributes + custom_attributes (read with BEARER)
     * - third-party: only standard claims allowed by scopes; no custom_attributes
     */
    public Map<String, Object> getUserinfo(String userId, List<String> scopes, boolean firstParty) {
        Map<String, Object> response = new HashMap<>();
        response.put("sub", userId);

        Map<String, Object> standardAttrs = standardAttributesService.readStandardAttributes(userId, ProfileRole.BEARER);

        if (firstParty) {
            response.putAll(standardAttrs);
            Map<String, Object> customAttrs = customAttributesService.readCustomAttributes(userId, ProfileRole.BEARER);
            if (!customAttrs.isEmpty()) {
                response.put("custom_attributes", customAttrs);
            }
        } else {
            Set<String> allowedClaims = OidcScopeClaims.claimsAllowedByScopes(scopes);
            for (Map.Entry<String, Object> e : standardAttrs.entrySet()) {
                if (allowedClaims.contains(e.getKey())) {
                    response.put(e.getKey(), e.getValue());
                }
            }
        }

        return response;
    }
}
