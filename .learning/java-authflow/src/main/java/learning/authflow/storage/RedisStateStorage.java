package learning.authflow.storage;

import learning.authflow.core.FlowInstance;
import learning.authflow.exception.InvalidStateTokenException;
import learning.authflow.json.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis状态存储实现 - 双Key设计
 * 专门负责 FlowInstance 的持久化
 */
public class RedisStateStorage implements StateStorage {
    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;
    private static final String FLOW_KEY_PREFIX = "authflow:flow:";
    private static final String STATE_KEY_PREFIX = "authflow:state:";

    public RedisStateStorage(StringRedisTemplate redisTemplate,
                              @Value("${authflow.storage.ttl:900}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void createFlow(FlowInstance flow) {
        String flowKey = FLOW_KEY_PREFIX + flow.getFlowId();
        String stateKey = STATE_KEY_PREFIX + flow.getStateToken();
        String json = GsonFactory.getGson().toJson(flow);

        redisTemplate.opsForValue().set(flowKey, "", Duration.ofSeconds(ttlSeconds));
        redisTemplate.opsForValue().set(stateKey, json, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public FlowInstance getFlowByStateToken(String stateToken) {
        String stateKey = STATE_KEY_PREFIX + stateToken;
        String json = redisTemplate.opsForValue().get(stateKey);
        if (json == null) {
            throw new InvalidStateTokenException("State token not found");
        }

        FlowInstance flow = GsonFactory.getGson().fromJson(json, FlowInstance.class);

        // 验证 flow 是否有效（未被删除）
        String flowKey = FLOW_KEY_PREFIX + flow.getFlowId();
        Boolean flowExists = redisTemplate.hasKey(flowKey);
        if (!Boolean.TRUE.equals(flowExists)) {
            throw new InvalidStateTokenException("Flow not found or expired");
        }

        return flow;
    }

    @Override
    public void deleteFlow(String flowId) {
        String flowKey = FLOW_KEY_PREFIX + flowId;
        redisTemplate.delete(flowKey);
    }
}
