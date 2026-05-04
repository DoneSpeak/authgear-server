package learning.authflow.core;

import java.util.UUID;

/**
 * UUID ID生成器
 */
public class UUIDIdGenerator implements IdGenerator {
    @Override
    public String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
