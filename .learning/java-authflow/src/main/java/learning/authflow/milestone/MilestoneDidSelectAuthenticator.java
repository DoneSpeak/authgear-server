package learning.authflow.milestone;

import learning.authflow.model.AuthenticatorInfo;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MilestoneDidSelectAuthenticator implements Milestone {
    private static final long serialVersionUID = 1L;
    private final AuthenticatorInfo authenticator;
}
