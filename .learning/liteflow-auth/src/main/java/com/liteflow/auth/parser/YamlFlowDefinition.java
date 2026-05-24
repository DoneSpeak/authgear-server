package com.liteflow.auth.parser;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * YAML 流程定义的数据类
 * 用于 SnakeYAML 解析
 */
@Data
@NoArgsConstructor
public class YamlFlowDefinition {
    // 使用下划线命名，SnakeYAML 会自动映射
    private List<Flow> login_flows;

    // 提供 getter 供其他类使用
    public List<Flow> getLoginFlows() {
        return login_flows;
    }

    @Data
    @NoArgsConstructor
    public static class Flow {
        private String name;
        private List<Step> steps;
    }

    @Data
    @NoArgsConstructor
    public static class Step {
        private String type;
        private List<Branch> one_of;
        private List<Step> steps;

        public List<Branch> getOneOf() {
            return one_of;
        }
    }

    @Data
    @NoArgsConstructor
    public static class Branch {
        private String identification;
        private String authentication;
        private List<Step> steps;
    }
}
