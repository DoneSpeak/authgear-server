package learning.authflow.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建流程请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateFlowRequest {
    @NotBlank
    private String type;      // login, signup, reauth

    @NotBlank
    private String name;      // flow name
}
