package learning.userprofile.validation;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates standard attributes: allowed keys (additionalProperties: false) and per-field format.
 * Mirrors Authgear pkg/lib/authn/stdattrs/validate.go.
 */
public class StandardAttributesValidator {

    /** Keys that must not be stored (derived). */
    private static final Set<String> DERIVED_KEYS = Set.of("email_verified", "phone_number_verified", "updated_at");

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "email", "phone_number", "preferred_username",
            "family_name", "given_name", "middle_name", "name", "nickname",
            "picture", "profile", "website", "gender", "birthdate", "zoneinfo", "locale",
            "address"
    );

    private static final Pattern EMAIL = Pattern.compile("^[^@]+@[^@]+\\.[^@]+$");
    private static final Pattern BIRTHDATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Set<String> ADDRESS_KEYS = Set.of("formatted", "street_address", "locality", "region", "postal_code", "country");

    /**
     * Remove derived attributes from map (mutates copy).
     */
    public Map<String, Object> withDerivedRemoved(Map<String, Object> attrs) {
        Map<String, Object> out = new HashMap<>(attrs != null ? attrs : Map.of());
        DERIVED_KEYS.forEach(out::remove);
        return out;
    }

    /**
     * Validate standard attributes. Keys must be in ALLOWED_KEYS; values must match format.
     *
     * @throws IllegalArgumentException with message describing the error
     */
    public void validate(Map<String, Object> attrs) {
        if (attrs == null) return;
        for (String key : attrs.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("standard_attributes: disallowed key '" + key + "'. Allowed: " + ALLOWED_KEYS);
            }
        }
        validateStringFormat("email", attrs.get("email"), EMAIL, "email format");
        validateStringMinLength("preferred_username", attrs.get("preferred_username"), 1);
        validateStringMinLength("family_name", attrs.get("family_name"), 1);
        validateStringMinLength("given_name", attrs.get("given_name"), 1);
        validateStringMinLength("middle_name", attrs.get("middle_name"), 1);
        validateStringMinLength("name", attrs.get("name"), 1);
        validateStringMinLength("nickname", attrs.get("nickname"), 1);
        validateStringMinLength("gender", attrs.get("gender"), 1);
        validateStringFormat("birthdate", attrs.get("birthdate"), BIRTHDATE, "YYYY-MM-DD");
        validateAddress(attrs.get("address"));
    }

    private void validateStringFormat(String key, Object val, Pattern pattern, String desc) {
        if (val == null) return;
        if (!(val instanceof String s)) {
            throw new IllegalArgumentException("standard_attributes." + key + ": expected string");
        }
        if (!pattern.matcher(s).matches()) {
            throw new IllegalArgumentException("standard_attributes." + key + ": invalid " + desc);
        }
    }

    private void validateStringMinLength(String key, Object val, int minLen) {
        if (val == null) return;
        if (!(val instanceof String s)) {
            throw new IllegalArgumentException("standard_attributes." + key + ": expected string");
        }
        if (s.length() < minLen) {
            throw new IllegalArgumentException("standard_attributes." + key + ": min length " + minLen);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateAddress(Object val) {
        if (val == null) return;
        if (!(val instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("standard_attributes.address: expected object");
        }
        for (Object k : m.keySet()) {
            if (!ADDRESS_KEYS.contains(k.toString())) {
                throw new IllegalArgumentException("standard_attributes.address: disallowed key '" + k + "'");
            }
        }
        for (String sub : List.of("formatted", "street_address", "locality", "region", "postal_code", "country")) {
            Object v = m.get(sub);
            if (v != null && !(v instanceof String)) {
                throw new IllegalArgumentException("standard_attributes.address." + sub + ": expected string");
            }
            if (v instanceof String s && s.isEmpty()) {
                throw new IllegalArgumentException("standard_attributes.address." + sub + ": min length 1");
            }
        }
    }
}
