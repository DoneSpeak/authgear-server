package learning.authflow.intent.impl;

import learning.authflow.intent.InputSchema;
import learning.authflow.model.AuthenticatorInfo;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 选择索引的输入模式
 * 用于让用户从列表中选择一个项目
 */
public class SelectIndexSchema implements InputSchema {
    private final List<AuthenticatorInfo> options;

    public SelectIndexSchema(List<AuthenticatorInfo> options) {
        this.options = options;
    }

    @Override
    public String getType() {
        return "select_index";
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("options", options, "count", options.size());
    }
}
