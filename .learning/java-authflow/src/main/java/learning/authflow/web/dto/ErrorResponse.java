package learning.authflow.web.dto;

import lombok.Data;

import java.util.Map;

/**
 * 错误响应
 */
@Data
public class ErrorResponse {
    private String name;     // Unauthorized, BadRequest
    private String reason;   // InvalidCredentials
    private String message;
    private int code;
    private Map<String, Object> info;
}
