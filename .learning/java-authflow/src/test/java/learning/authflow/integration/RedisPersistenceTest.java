package learning.authflow.integration;

import learning.authflow.config.AuthflowConfig;
import learning.authflow.core.AuthflowService;
import learning.authflow.core.FlowInstance;
import learning.authflow.exception.InvalidStateTokenException;
import learning.authflow.model.StepType;
import learning.authflow.response.AuthflowResponse;
import learning.authflow.storage.StateStorage;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * UC-06: Redis存储持久化
 */
@Testcontainers
@SpringBootTest
@ContextConfiguration(classes = {AuthflowConfig.class})
@DisplayName("UC-06: Redis存储持久化")
class RedisPersistenceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private AuthflowService service;

    @Autowired
    private StateStorage stateStorage;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void test_uc06_setup() {
        assumeTrue(redis.isRunning(), "Redis container must be running");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("创建流程应持久化FlowInstance到Redis")
    void test_uc06_createFlow_persistsFlowInstanceToRedis() {
        AuthflowResponse response = service.create("login", "default");

        String stateKey = "authflow:state:" + response.getStateToken();
        String json = redisTemplate.opsForValue().get(stateKey);

        assertThat(json).isNotNull();
        assertThat(json).contains("\"flowId\"");
        assertThat(json).contains("\"stateToken\"");
    }

    @Test
    @DisplayName("序列化应正确处理复杂对象结构")
    void test_uc06_serialization_handlesComplexObjectStructure() {
        AuthflowResponse response = service.create("login", "default");

        FlowInstance flow = stateStorage.getFlowByStateToken(response.getStateToken());

        assertThat(flow.getFlowId()).isNotNull();
        assertThat(flow.getNodes()).isNotNull();
    }

    @Test
    @DisplayName("每次状态变更应创建新的stateKey")
    void test_uc06_stateChange_createsNewStateKey() {
        String stateA = service.create("login", "default").getStateToken();
        String stateB = service.execute(stateA, "{}").getStateToken();

        Set<String> keys = redisTemplate.keys("authflow:state:*");
        assertThat(keys).hasSize(2);
    }

    @Test
    @DisplayName("stateKey应设置正确TTL")
    void test_uc06_stateKey_hasCorrectTTL() {
        AuthflowResponse response = service.create("login", "default");

        Long ttl = redisTemplate.getExpire("authflow:state:" + response.getStateToken());

        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(900);
    }

    @Test
    @DisplayName("删除flow后所有stateKey应失效")
    void test_uc06_deleteFlow_invalidatesAllStateKeys() {
        String stateA = service.create("login", "default").getStateToken();
        String stateB = service.execute(stateA, "{}").getStateToken();
        String flowId = service.getState(stateA).getFlowId();

        stateStorage.deleteFlow(flowId);

        assertThrows(InvalidStateTokenException.class, () -> {
            stateStorage.getFlowByStateToken(stateA);
        });
        assertThrows(InvalidStateTokenException.class, () -> {
            stateStorage.getFlowByStateToken(stateB);
        });
    }

    @Test
    @DisplayName("过期后stateKey应自动删除")
    void test_uc06_expiredKey_isAutomaticallyRemoved() {
        AuthflowResponse response = service.create("login", "default");
        String stateKey = "authflow:state:" + response.getStateToken();

        redisTemplate.expire(stateKey, Duration.ofSeconds(1));

        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                assertThrows(InvalidStateTokenException.class, () -> {
                    stateStorage.getFlowByStateToken(response.getStateToken());
                });
            });
    }
}
