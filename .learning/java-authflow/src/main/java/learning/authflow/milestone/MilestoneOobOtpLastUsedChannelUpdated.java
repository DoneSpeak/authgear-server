package learning.authflow.milestone;

import learning.authflow.model.Channel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 标记：OOB OTP 最后使用的渠道已更新
 */
@Data
@AllArgsConstructor
public class MilestoneOobOtpLastUsedChannelUpdated implements Milestone {
    private static final long serialVersionUID = 1L;
    private final Channel channel;
}
