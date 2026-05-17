package learning.authflow.core;

import lombok.Data;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent节点 - 树形结构中的意图节点
 * 用于支持子流程和嵌套Intent
 */
@Data
public class IntentNode implements Serializable {
    private static final long serialVersionUID = 1L;

    private String kind;                    // Intent 类型标识
    private Map<String, Object> params = new HashMap<>();   // 构造参数
    private List<FlowNode> children = new ArrayList<>();    // 子节点（Nodes）
    private Map<String, Object> milestoneData = new HashMap<>();  // 已完成的里程碑
}
