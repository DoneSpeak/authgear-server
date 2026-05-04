package learning.userprofile.config;

import java.util.*;

/**
 * OIDC scope to standard claim names mapping.
 * Mirrors Authgear pkg/lib/oauth/scope.go scopeClaims.
 */
public final class OidcScopeClaims {

    /** OIDC profile scope: name, family_name, given_name, etc. */
    public static final String SCOPE_PROFILE = "profile";
    /** OIDC email scope. */
    public static final String SCOPE_EMAIL = "email";
    /** OIDC address scope. */
    public static final String SCOPE_ADDRESS = "address";
    /** OIDC phone scope. */
    public static final String SCOPE_PHONE = "phone";
    /** Authgear: full userinfo (all standard claims). */
    public static final String SCOPE_FULL_USERINFO = "https://authgear.com/scopes/full-userinfo";
    /** Authgear: full access. */
    public static final String SCOPE_FULL_ACCESS = "https://authgear.com/scopes/full-access";

    private static final Set<String> ALL_STANDARD_CLAIMS = Set.of(
            "name", "family_name", "given_name", "middle_name", "nickname",
            "preferred_username", "profile", "picture", "website",
            "gender", "birthdate", "zoneinfo", "locale", "updated_at",
            "email", "email_verified", "address",
            "phone_number", "phone_number_verified"
    );

    private static final Map<String, Set<String>> SCOPE_TO_CLAIMS = buildScopeToClaims();

    private static Map<String, Set<String>> buildScopeToClaims() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put(SCOPE_PROFILE, Set.of(
                "name", "family_name", "given_name", "middle_name", "nickname",
                "preferred_username", "profile", "picture", "website",
                "gender", "birthdate", "zoneinfo", "locale", "updated_at"));
        m.put(SCOPE_EMAIL, Set.of("email", "email_verified"));
        m.put(SCOPE_ADDRESS, Set.of("address"));
        m.put(SCOPE_PHONE, Set.of("phone_number", "phone_number_verified"));
        m.put(SCOPE_FULL_USERINFO, new HashSet<>(ALL_STANDARD_CLAIMS));
        m.put(SCOPE_FULL_ACCESS, new HashSet<>(ALL_STANDARD_CLAIMS));
        return Map.copyOf(m);
    }

    /**
     * Returns the set of standard claim names allowed by the given scopes.
     * If any scope is full_userinfo or full_access, all standard claims are allowed.
     */
    public static Set<String> claimsAllowedByScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        Set<String> allowed = new HashSet<>();
        for (String scope : scopes) {
            String s = scope == null ? null : scope.strip();
            if (s == null || s.isEmpty()) continue;
            Set<String> claims = SCOPE_TO_CLAIMS.get(s);
            if (claims != null) {
                allowed.addAll(claims);
            }
        }
        return allowed;
    }

    /**
     * Returns true if the given claim name is allowed by the given scopes.
     */
    public static boolean scopeAllowsClaim(String scope, String claimName) {
        if (claimName == null || claimName.isEmpty()) return false;
        Set<String> claims = SCOPE_TO_CLAIMS.get(scope);
        if (claims == null) return false;
        return claims.contains(claimName);
    }

    private OidcScopeClaims() {}
}
