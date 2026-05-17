package learning.authflow.intent;

import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * Intent 注册表 - 负责创建和管理 Intent 实例
 * TODO: 实现 Intent 创建逻辑
 */
@Service
public class IntentRegistry {

    /**
     * 根据类型和参数创建 Intent 实例
     *
     * @param kind   Intent 类型标识
     * @param params 构造参数
     * @return Intent 实例，如果类型未知则返回 null
     */
    public Intent create(String kind, Map<String, Object> params) {
        // TODO: 实现 Intent 创建逻辑
        return null;
    }
}
