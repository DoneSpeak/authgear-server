package learning.authflow.intent.registry;

import learning.authflow.intent.Intent;
import learning.authflow.intent.IntentFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Intent 注册表。
 * 自动收集所有 IntentFactory Bean，提供 Intent 的创建和查找功能。
 */
@Service
public class IntentRegistry {

    // 按 kind 索引的工厂映射
    private final Map<String, IntentFactory> factoriesByKind;

    // 所有工厂的列表（用于按 authentication 查找）
    private final List<IntentFactory> allFactories;

    /**
     * 构造函数。Spring 自动注入所有 IntentFactory Bean。
     * @param factories 所有 IntentFactory 实现
     */
    public IntentRegistry(List<IntentFactory> factories) {
        this.allFactories = factories;
        this.factoriesByKind = factories.stream()
            .collect(Collectors.toMap(
                IntentFactory::getKind,
                Function.identity()
            ));
    }

    /**
     * 根据 kind 创建 Intent 实例。
     * @param kind Intent 类型标识
     * @param params 构造参数
     * @return Intent 实例
     * @throws UnsupportedOperationException 如果 kind 未知
     */
    public Intent create(String kind, Map<String, Object> params) {
        IntentFactory factory = factoriesByKind.get(kind);
        if (factory == null) {
            throw new UnsupportedOperationException("Unknown intent kind: " + kind);
        }
        return factory.create(params);
    }

    /**
     * 根据 authentication 字符串查找对应的工厂。
     * 用于根据 flows.yaml 中的 authentication 字段找到对应的 Intent。
     * @param authentication 如 "primary_oob_otp_email", "secondary_totp"
     * @return 对应的 IntentFactory
     * @throws UnsupportedOperationException 如果没有匹配的工厂
     */
    public IntentFactory findForAuthentication(String authentication) {
        return allFactories.stream()
            .filter(f -> f.supportsAuthentication(authentication))
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException(
                "No intent factory for authentication: " + authentication));
    }

    /**
     * 注册额外的工厂（用于动态注册）。
     * @param factory 要注册的工厂
     */
    public void register(IntentFactory factory) {
        factoriesByKind.put(factory.getKind(), factory);
    }

    /**
     * 获取所有已注册的 kind。
     * @return kind 集合
     */
    public Set<String> getRegisteredKinds() {
        return factoriesByKind.keySet();
    }
}
