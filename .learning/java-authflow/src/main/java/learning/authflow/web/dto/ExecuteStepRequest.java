package learning.authflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行步骤请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteStepRequest {
    @NotBlank
    private String stateToken;

    private String input;       // JSON字符串
}
