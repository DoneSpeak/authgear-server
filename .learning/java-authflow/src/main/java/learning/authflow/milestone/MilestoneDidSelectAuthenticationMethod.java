package learning.authflow.milestone;

import learning.authflow.model.AuthenticatorInfo;

/**
 * 标记：用户已选择认证方式
 */
public interface MilestoneDidSelectAuthenticationMethod extends Milestone {
    AuthenticatorInfo getSelectedAuthenticator();
}
