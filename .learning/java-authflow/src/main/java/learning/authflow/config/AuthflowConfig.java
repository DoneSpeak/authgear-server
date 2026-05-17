package learning.authflow.config;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import learning.authflow.core.AuthflowEngine;
import learning.authflow.core.AuthflowService;
import learning.authflow.core.ExecutionContext;
import learning.authflow.core.IdGenerator;
import learning.authflow.core.StateTokenManager;
import learning.authflow.core.UUIDIdGenerator;
import learning.authflow.intent.registry.IntentRegistry;
import learning.authflow.flowdef.AuthflowProperties;
import learning.authflow.flowdef.FlowDefinitionProvider;
import learning.authflow.flowdef.YamlPropertiesFlowDefinitionProvider;
import learning.authflow.json.GsonFactory;
import learning.authflow.provider.LoginIdProvider;
import learning.authflow.provider.OobOtpProvider;
import learning.authflow.provider.PasswordAuthenticatorProvider;
import learning.authflow.provider.inmemory.InMemoryLoginIdProvider;
import learning.authflow.provider.inmemory.InMemoryOobOtpProvider;
import learning.authflow.provider.inmemory.InMemoryPasswordAuthenticatorProvider;
import learning.authflow.step.StepHandler;
import learning.authflow.step.handlers.AuthenticateHandler;
import learning.authflow.step.handlers.CompositeAuthenticateHandler;
import learning.authflow.step.handlers.IdentifyHandler;
import learning.authflow.step.handlers.PrimaryOobOtpEmailAuthenticateHandler;
import learning.authflow.step.handlers.PrimaryOobOtpSmsAuthenticateHandler;
import learning.authflow.step.handlers.PrimaryPasswordAuthenticateHandler;
import learning.authflow.step.handlers.VerifyHandler;
import learning.authflow.step.registry.StepHandlerRegistry;
import learning.authflow.storage.RedisSessionStorage;
import learning.authflow.storage.RedisStateStorage;
import learning.authflow.storage.SessionStorage;
import learning.authflow.storage.StateStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public SessionStorage sessionStorage(StringRedisTemplate redisTemplate,
                                          @Value("${authflow.storage.ttl:900}") long ttlSeconds) {
        return new RedisSessionStorage(redisTemplate, ttlSeconds);
    }

    @Bean
    public StepHandlerRegistry stepHandlerRegistry(List<StepHandler> handlers) {
        return new StepHandlerRegistry(handlers);
    }

    @Bean
    public AuthflowEngine authflowEngine(StateStorage stateStorage,
                                          IntentRegistry intentRegistry,
                                          StateTokenManager tokenManager,
                                          IdGenerator idGenerator,
                                          FlowDefinitionProvider flowDefinitionProvider,
                                          StepHandlerRegistry stepHandlerRegistry,
                                          SessionStorage sessionStorage) {
        return new AuthflowEngine(stateStorage, intentRegistry, tokenManager, idGenerator,
                                  flowDefinitionProvider, stepHandlerRegistry, sessionStorage);
    }

    @Bean
    public AuthflowService authflowService(AuthflowEngine engine,
                                           StateStorage stateStorage,
                                           StateTokenManager tokenManager,
                                           FlowDefinitionProvider flowProvider) {
        return new AuthflowService(engine, stateStorage, tokenManager, flowProvider);
    }

    // ========== Provider Beans with test data ==========

    @Bean
    public LoginIdProvider loginIdProvider() {
        Map<String, String> userStore = new HashMap<>();
        userStore.put("user@example.com", "user_123");
        userStore.put("+85298765432", "user_456");
        userStore.put("test@example.com", "user_789");
        return new InMemoryLoginIdProvider(userStore);
    }

    @Bean
    public PasswordAuthenticatorProvider passwordAuthenticatorProvider() {
        Map<String, String> passwordStore = new HashMap<>();
        passwordStore.put("user_123", "password123");
        passwordStore.put("user_456", "password456");
        passwordStore.put("user_789", "password789");
        return new InMemoryPasswordAuthenticatorProvider(passwordStore);
    }

    @Bean
    public OobOtpProvider oobOtpProvider() {
        Map<String, String> otpStore = new HashMap<>();
        otpStore.put("user@example.com:email", "123456");
        otpStore.put("+85298765432:sms", "654321");
        return new InMemoryOobOtpProvider(otpStore);
    }

    // ========== Step Handlers ==========

    @Bean
    public IdentifyHandler identifyHandler() {
        return new IdentifyHandler();
    }

    // 叶子认证处理器 Beans
    @Bean
    public PrimaryPasswordAuthenticateHandler primaryPasswordAuthenticateHandler(
            PasswordAuthenticatorProvider passwordProvider) {
        return new PrimaryPasswordAuthenticateHandler(passwordProvider);
    }

    @Bean
    public PrimaryOobOtpSmsAuthenticateHandler primaryOobOtpSmsAuthenticateHandler(
            OobOtpProvider oobOtpProvider) {
        return new PrimaryOobOtpSmsAuthenticateHandler(oobOtpProvider);
    }

    @Bean
    public PrimaryOobOtpEmailAuthenticateHandler primaryOobOtpEmailAuthenticateHandler(
            OobOtpProvider oobOtpProvider) {
        return new PrimaryOobOtpEmailAuthenticateHandler(oobOtpProvider);
    }

    // 组合处理器（实现 StepHandler）
    @Bean
    public CompositeAuthenticateHandler authenticateHandler(
            LoginIdProvider loginIdProvider,
            PrimaryPasswordAuthenticateHandler passwordHandler,
            PrimaryOobOtpSmsAuthenticateHandler smsHandler,
            PrimaryOobOtpEmailAuthenticateHandler emailHandler) {

        Map<String, AuthenticateHandler> handlers = new HashMap<>();
        handlers.put("primary_password", passwordHandler);
        handlers.put("primary_oob_otp_sms", smsHandler);
        handlers.put("primary_oob_otp_email", emailHandler);

        return new CompositeAuthenticateHandler(loginIdProvider, handlers);
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
