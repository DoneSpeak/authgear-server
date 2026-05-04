package learning.userprofile.service;

import learning.userprofile.config.AccessControlLevel;
import learning.userprofile.config.CustomAttributeDefinition;
import learning.userprofile.config.ProfileRole;
import learning.userprofile.config.UserProfileProperties;
import learning.userprofile.domain.AuthUser;
import learning.userprofile.repository.AuthUserRepository;
import learning.userprofile.validation.CustomAttributesValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Update and read custom attributes: validation, storage form conversion, access control.
 * Mirrors Authgear CustomAttrsService / UpdateCustomAttributesWithList.
 */
@Service
public class CustomAttributesService {

    private final AuthUserRepository userRepository;
    private final UserProfileProperties userProfile;
    private final CustomAttributesFormConverter formConverter;
    private final CustomAttributesValidator validator;

    public CustomAttributesService(AuthUserRepository userRepository, UserProfileProperties userProfile) {
        this.userRepository = userRepository;
        this.userProfile = userProfile;
        this.formConverter = new CustomAttributesFormConverter(userProfile.getCustomAttributes());
        this.validator = new CustomAttributesValidator(userProfile.getCustomAttributes());
    }

    /**
     * Update custom attributes (representation form: pointer -> value). Only provided pointers are updated.
     */
    @Transactional
    public void updateCustomAttributes(String userId, ProfileRole role, Map<String, Object> representationForm) {
        if (representationForm == null || representationForm.isEmpty()) return;

        List<String> pointers = new ArrayList<>();
        for (String key : representationForm.keySet()) {
            String pointer = key.startsWith("/") ? key : "/" + key;
            if (userProfile.getCustomAttributes().findByPointer(pointer).isPresent()) {
                pointers.add(pointer);
            }
        }
        validator.validate(pointers, representationForm);

        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

        Map<String, Object> currentRepr = formConverter.fromStorageForm(user.getCustomAttributes());
        for (String pointer : pointers) {
            AccessControlLevel level = getLevel(pointer, role);
            if (level != AccessControlLevel.READWRITE) {
                throw new SecurityException("no write access to custom attribute: " + pointer + " for role " + role);
            }
            Object val = getByPointer(representationForm, pointer);
            setByPointer(currentRepr, pointer, val);
        }

        Map<String, Object> storageForm = formConverter.toStorageForm(currentRepr);
        user.setCustomAttributes(storageForm);
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
    }

    /**
     * Read custom attributes in representation form, filtered by role.
     */
    public Map<String, Object> readCustomAttributes(String userId, ProfileRole role) {
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        Map<String, Object> repr = formConverter.fromStorageForm(user.getCustomAttributes());
        Map<String, Object> out = new HashMap<>();
        for (CustomAttributeDefinition attr : userProfile.getCustomAttributes().getAttributes()) {
            AccessControlLevel level = levelFor(attr, role);
            if (level == AccessControlLevel.READONLY || level == AccessControlLevel.READWRITE) {
                Object val = getByPointer(repr, attr.getPointer());
                if (val != null) setByPointer(out, attr.getPointer(), val);
            }
        }
        return out;
    }

    private AccessControlLevel getLevel(String pointer, ProfileRole role) {
        return userProfile.getCustomAttributes().findByPointer(pointer)
                .map(attr -> levelFor(attr, role))
                .orElse(AccessControlLevel.HIDDEN);
    }

    private static AccessControlLevel levelFor(CustomAttributeDefinition attr, ProfileRole role) {
        var ac = attr.getAccessControl();
        if (ac == null) return AccessControlLevel.HIDDEN;
        return switch (role) {
            case END_USER -> ac.getEndUser();
            case BEARER -> ac.getBearer();
            case PORTAL_UI -> ac.getPortalUi();
        };
    }

    private static Object getByPointer(Map<String, Object> map, String pointer) {
        String key = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        return map.get(key);
    }

    private static void setByPointer(Map<String, Object> map, String pointer, Object value) {
        String key = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        map.put(key, value);
    }
}
