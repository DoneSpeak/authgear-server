package learning.userprofile.validation;

import learning.userprofile.config.CustomAttributeDefinition;
import learning.userprofile.config.CustomAttributeType;
import learning.userprofile.config.CustomAttributesConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates custom attributes using config-driven rules (type, min/max, enum).
 * Mirrors Authgear customattrs validation (generateSchemaString + validate).
 */
public class CustomAttributesValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@]+@[^@]+\\.[^@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9\\s-]+$");
    private static final Pattern URL = Pattern.compile("^https?://.+");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    private final CustomAttributesConfig config;

    public CustomAttributesValidator(CustomAttributesConfig config) {
        this.config = config;
    }

    /**
     * Validate representation form (pointer-based map) for given pointers.
     * Values are checked against attribute type and constraints.
     */
    public void validate(List<String> pointers, Map<String, Object> representationForm) {
        if (representationForm == null) return;
        for (String pointer : pointers) {
            var def = config.findByPointer(pointer).orElse(null);
            if (def == null) continue;
            Object val = getByPointer(representationForm, pointer);
            validateValue(def, val);
        }
    }

    private Object getByPointer(Map<String, Object> map, String pointer) {
        if (pointer == null || !pointer.startsWith("/")) return null;
        String key = pointer.substring(1).replace("~1", "/").replace("~0", "~");
        return map.get(key);
    }

    private void validateValue(CustomAttributeDefinition def, Object val) {
        if (val == null) return;
        switch (def.getType()) {
            case STRING -> validateString(val, 1);
            case NUMBER -> validateNumber(val, def.getMinimum(), def.getMaximum());
            case INTEGER -> validateInteger(val, def.getMinimum(), def.getMaximum());
            case ENUM -> validateEnum(val, def.getEnumValues());
            case EMAIL -> validateFormat(val, EMAIL, "email");
            case PHONE_NUMBER -> validateFormat(val, PHONE, "phone");
            case URL -> validateFormat(val, URL, "url");
            case COUNTRY_CODE -> validateFormat(val, COUNTRY_CODE, "ISO3166-1 alpha-2");
        }
    }

    private void validateString(Object val, int minLen) {
        if (!(val instanceof String s)) throw new IllegalArgumentException("expected string");
        if (s.length() < minLen) throw new IllegalArgumentException("min length " + minLen);
    }

    private void validateNumber(Object val, Double min, Double max) {
        double d = val instanceof Number n ? n.doubleValue() : Double.parseDouble(val.toString());
        if (min != null && d < min) throw new IllegalArgumentException("minimum " + min);
        if (max != null && d > max) throw new IllegalArgumentException("maximum " + max);
    }

    private void validateInteger(Object val, Double min, Double max) {
        long l = val instanceof Number n ? n.longValue() : Long.parseLong(val.toString());
        if (min != null && l < min.longValue()) throw new IllegalArgumentException("minimum " + min);
        if (max != null && l > max.longValue()) throw new IllegalArgumentException("maximum " + max);
    }

    private void validateEnum(Object val, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) throw new IllegalArgumentException("enum not configured");
        String s = val.toString();
        if (!allowed.contains(s)) throw new IllegalArgumentException("must be one of " + allowed);
    }

    private void validateFormat(Object val, Pattern pattern, String desc) {
        if (!(val instanceof String s)) throw new IllegalArgumentException("expected string");
        if (!pattern.matcher(s).matches()) throw new IllegalArgumentException("invalid " + desc);
    }
}
