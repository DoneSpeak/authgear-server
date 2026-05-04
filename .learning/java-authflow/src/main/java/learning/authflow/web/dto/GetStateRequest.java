package learning.authflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取状态请求
 */
@Data
public class GetStateRequest {
    @NotBlank
    private String stateToken;

    public GetStateRequest() {
    }

    public GetStateRequest(String stateToken) {
        this.stateToken = stateToken;
    }
}
