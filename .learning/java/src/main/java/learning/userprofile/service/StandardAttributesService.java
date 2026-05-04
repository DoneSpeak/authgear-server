package learning.userprofile.service;

import learning.userprofile.config.AccessControlLevel;
import learning.userprofile.config.ProfileRole;
import learning.userprofile.config.UserProfileProperties;
import learning.userprofile.domain.AuthUser;
import learning.userprofile.repository.AuthUserRepository;
import learning.userprofile.validation.StandardAttributesValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Update and read standard attributes with validation and access control.
 * Mirrors Authgear StdAttrsService / UpdateStandardAttributes.
 * When IdentityService is present, enforces identity-owned check for
 * email / phone_number / preferred_username.
 */
@Service
public class StandardAttributesService {

    private final AuthUserRepository userRepository;
    private final UserProfileProperties userProfile;
    private final Optional<IdentityService> identityService;
    private final StandardAttributesValidator validator = new StandardAttributesValidator();

    public StandardAttributesService(AuthUserRepository userRepository,
                                     UserProfileProperties userProfile,
                                     Optional<IdentityService> identityService) {
        this.userRepository = userRepository;
        this.userProfile = userProfile;
        this.identityService = identityService != null ? identityService : Optional.empty();
    }

    @Transactional
    public void updateStandardAttributes(String userId, ProfileRole role, Map<String, Object> stdAttrs) {
        Map<String, Object> cleaned = validator.withDerivedRemoved(stdAttrs != null ? stdAttrs : Map.of());
        validator.validate(cleaned);

        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

        for (String key : cleaned.keySet()) {
            AccessControlLevel level = userProfile.getStandardAttributes().getLevel("/" + key, role);
            if (level != AccessControlLevel.READWRITE) {
                throw new SecurityException("no write access to standard attribute: " + key + " for role " + role);
            }
        }

        identityService.ifPresent(idSvc -> checkIdentityOwned(userId, idSvc, cleaned));

        Map<String, Object> merged = new HashMap<>(user.getStandardAttributes());
        merged.putAll(cleaned);
        user.setStandardAttributes(merged);
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
    }

    /**
     * Ensures email / phone_number / preferred_username values are in the user's owned set.
     */
    private void checkIdentityOwned(String userId, IdentityService idSvc, Map<String, Object> cleaned) {
        if (cleaned.containsKey("email")) {
            Object val = cleaned.get("email");
            if (val instanceof String s && !s.isBlank()) {
                List<String> owned = idSvc.listOwnedEmails(userId);
                if (!owned.contains(s)) {
                    throw new SecurityException("email not owned by user: " + s);
                }
            }
        }
        if (cleaned.containsKey("phone_number")) {
            Object val = cleaned.get("phone_number");
            if (val instanceof String s && !s.isBlank()) {
                List<String> owned = idSvc.listOwnedPhoneNumbers(userId);
                if (!owned.contains(s)) {
                    throw new SecurityException("phone_number not owned by user: " + s);
                }
            }
        }
        if (cleaned.containsKey("preferred_username")) {
            Object val = cleaned.get("preferred_username");
            if (val instanceof String s && !s.isBlank()) {
                List<String> owned = idSvc.listOwnedPreferredUsernames(userId);
                if (!owned.contains(s)) {
                    throw new SecurityException("preferred_username not owned by user: " + s);
                }
            }
        }
    }

    /**
     * Read standard attributes filtered by role (hidden attributes omitted).
     */
    public Map<String, Object> readStandardAttributes(String userId, ProfileRole role) {
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : user.getStandardAttributes().entrySet()) {
            AccessControlLevel level = userProfile.getStandardAttributes().getLevel("/" + e.getKey(), role);
            if (level == AccessControlLevel.READONLY || level == AccessControlLevel.READWRITE) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
