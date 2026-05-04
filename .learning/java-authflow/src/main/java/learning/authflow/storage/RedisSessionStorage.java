package learning.authflow.storage;

import learning.authflow.core.Session;
import learning.authflow.json.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis Session 存储实现
 */
public class RedisSessionStorage implements SessionStorage {
    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;
    private static final String SESSION_KEY_PREFIX = "authflow:session:";

    public RedisSessionStorage(StringRedisTemplate redisTemplate,
                                @Value("${authflow.storage.ttl:900}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void save(Session session) {
        String sessionKey = SESSION_KEY_PREFIX + session.flowId();
        String json = GsonFactory.getGson().toJson(session);
        redisTemplate.opsForValue().set(sessionKey, json, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public Session getOrCreate(String flowId) {
        String sessionKey = SESSION_KEY_PREFIX + flowId;
        String json = redisTemplate.opsForValue().get(sessionKey);
        if (json == null) {
            return new Session(flowId);
        }
        return GsonFactory.getGson().fromJson(json, Session.class);
    }
}
