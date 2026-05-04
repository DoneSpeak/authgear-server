package learning.authflow.core;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashMap;
import java.util.Map;

/**
 * 执行环境上下文 - 包含HTTP请求相关信息
 * SRP：只负责环境相关数据，与流程逻辑分离
 */
public class ExecutionContext {
    private final HttpServletRequest request;

    public ExecutionContext(HttpServletRequest request) {
        this.request = request;
    }

    public String getClientIP() {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public String getUserAgent() {
        return request.getHeader("User-Agent");
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(name ->
            headers.put(name, request.getHeader(name))
        );
        return headers;
    }
}
