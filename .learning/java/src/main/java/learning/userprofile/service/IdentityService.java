package learning.userprofile.service;

import java.util.List;

/**
 * Provides the set of email / phone_number / preferred_username values
 * that the user "owns" (e.g. from verified identities).
 * Used to enforce that standard_attributes only allow writing values
 * the user already owns. When no identity model exists, do not register
 * a bean; StandardAttributesService will skip the identity-owned check.
 */
public interface IdentityService {

    List<String> listOwnedEmails(String userId);

    List<String> listOwnedPhoneNumbers(String userId);

    List<String> listOwnedPreferredUsernames(String userId);
}
