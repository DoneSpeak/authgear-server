package learning.userprofile.service;

import learning.userprofile.config.CustomAttributeDefinition;
import learning.userprofile.config.CustomAttributesConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts between representation form (pointer-based, API) and storage form (id-based, DB).
 * Mirrors Authgear toStorageForm / fromStorageForm.
 */
public class CustomAttributesFormConverter {

    private final CustomAttributesConfig config;

    public CustomAttributesFormConverter(CustomAttributesConfig config) {
        this.config = config;
    }

    /**
     * Representation form (keys = pointer path, e.g. x_company_id) -> storage form (keys = config id).
     */
    public Map<String, Object> toStorageForm(Map<String, Object> representationForm) {
        Map<String, Object> out = new HashMap<>();
        for (CustomAttributeDefinition attr : config.getAttributes()) {
            Object val = getByPointer(representationForm, attr.getPointer());
            if (val != null) {
                out.put(attr.getId(), val);
            }
        }
        return out;
    }

    /**
     * Storage form (keys = config id) -> representation form (keys = pointer path).
     */
    public Map<String, Object> fromStorageForm(Map<String, Object> storageForm) {
        Map<String, Object> out = new HashMap<>();
        if (storageForm == null) return out;
        for (CustomAttributeDefinition attr : config.getAttributes()) {
            Object val = storageForm.get(attr.getId());
            if (val != null) {
                setByPointer(out, attr.getPointer(), val);
            }
        }
        return out;
    }

    private static Object getByPointer(Map<String, Object> map, String pointer) {
        if (pointer == null || pointer.isEmpty()) return null;
        String key = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        key = key.replace("~1", "/").replace("~0", "~");
        return map.get(key);
    }

    private static void setByPointer(Map<String, Object> map, String pointer, Object value) {
        if (pointer == null || pointer.isEmpty()) return;
        String key = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        key = key.replace("~1", "/").replace("~0", "~");
        map.put(key, value);
    }
}
