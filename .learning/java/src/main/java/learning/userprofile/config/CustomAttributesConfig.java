package learning.userprofile.config;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Config for custom attributes: list of attribute definitions.
 * Mirrors Authgear CustomAttributesConfig.
 */
@Data
public class CustomAttributesConfig {
    @Valid
    private List<CustomAttributeDefinition> attributes = new ArrayList<>();

    public Optional<CustomAttributeDefinition> findByPointer(String pointer) {
        return attributes.stream().filter(a -> pointer.equals(a.getPointer())).findFirst();
    }

    public Optional<CustomAttributeDefinition> findById(String id) {
        return attributes.stream().filter(a -> id.equals(a.getId())).findFirst();
    }
}
