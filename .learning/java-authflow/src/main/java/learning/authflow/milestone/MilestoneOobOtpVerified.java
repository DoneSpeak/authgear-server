package learning.authflow.milestone;

import learning.authflow.model.Channel;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MilestoneOobOtpVerified implements Milestone {
    private static final long serialVersionUID = 1L;
    private final Channel channel;
}
