package learning.authflow.flowdef;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 认证流程配置属性
 */
@ConfigurationProperties(prefix = "authflow")
public class AuthflowProperties {
    private Storage storage;
    private Flows flows;

    @lombok.Data
    public static class Storage {
        private long ttl = 900;  // 默认15分钟
    }

    @lombok.Data
    public static class Flows {
        private String configPath = "classpath:flows.yaml";
        private List<FlowDefinition> definitions;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Flows getFlows() {
        return flows;
    }

    public void setFlows(Flows flows) {
        this.flows = flows;
    }
}
