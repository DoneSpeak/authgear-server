package learning.authflow.config;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import learning.authflow.core.AuthflowEngine;
import learning.authflow.core.AuthflowService;
import learning.authflow.core.ExecutionContext;
import learning.authflow.core.IdGenerator;
import learning.authflow.core.StateTokenManager;
import learning.authflow.core.UUIDIdGenerator;
import learning.authflow.flowdef.AuthflowProperties;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.YamlPropertiesFlowDefinitionProvider;
import learning.authflow.json.GsonFactory;
import learning.authflow.step.StepHandler;
import learning.authflow.step.handlers.AuthenticateHandler;
import learning.authflow.step.handlers.IdentifyHandler;
import learning.authflow.step.handlers.VerifyHandler;
import learning.authflow.step.registry.StepHandlerRegistry;
import learning.authflow.storage.RedisStateStorage;
import learning.authflow.storage.StateStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.annotation.RequestScope;

import java.util.List;

/**
 * 认证流程配置类
 * 显式配置所有Bean，不使用@Component扫描
 */
@Configuration
@EnableConfigurationProperties(AuthflowProperties.class)
public class AuthflowConfig {

    @Bean
    public Gson gson() {
        return GsonFactory.getGson();
    }

    @Bean
    public StateTokenManager stateTokenManager() {
        return new StateTokenManager();
    }

    @Bean
    public IdGenerator idGenerator() {
        return new UUIDIdGenerator();
    }

    @Bean
    public FlowDefinitionProvider flowDefinitionProvider(AuthflowProperties properties) {
        return new YamlPropertiesFlowDefinitionProvider(properties);
    }

    @Bean
    public StateStorage stateStorage(StringRedisTemplate redisTemplate,
                                      @Value("${authflow.storage.ttl:900}") long ttlSeconds) {
        return new RedisStateStorage(redisTemplate, ttlSeconds);
    }

    @Bean
    public StepHandlerRegistry stepHandlerRegistry(List<StepHandler> handlers) {
        return new StepHandlerRegistry(handlers);
    }

    @Bean
    public AuthflowEngine authflowEngine(StateStorage stateStorage,
                                          StepHandlerRegistry handlerRegistry,
                                          FlowDefinitionProvider flowProvider,
                                          StateTokenManager tokenManager,
                                          IdGenerator idGenerator) {
        return new AuthflowEngine(stateStorage, handlerRegistry, flowProvider,
                                  tokenManager, idGenerator);
    }

    @Bean
    public AuthflowService authflowService(AuthflowEngine engine,
                                           StateStorage stateStorage,
                                           StateTokenManager tokenManager) {
        return new AuthflowService(engine, stateStorage, tokenManager);
    }

    // Step Handlers - 显式注册
    @Bean
    public IdentifyHandler identifyHandler() {
        return new IdentifyHandler();
    }

    @Bean
    public AuthenticateHandler authenticateHandler() {
        return new AuthenticateHandler();
    }

    @Bean
    public VerifyHandler verifyHandler() {
        return new VerifyHandler();
    }

    @Bean
    @RequestScope
    public ExecutionContext executionContext(HttpServletRequest request) {
        return new ExecutionContext(request);
    }
}
