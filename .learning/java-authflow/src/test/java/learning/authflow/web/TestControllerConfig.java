package learning.authflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import learning.authflow.core.AuthflowService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 测试配置
 */
@TestConfiguration
public class TestControllerConfig {

    @Bean
    public AuthflowController authflowController(AuthflowService service) {
        return new AuthflowController(service);
    }

    @Bean
    public AuthflowExceptionHandler exceptionHandler() {
        return new AuthflowExceptionHandler();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
