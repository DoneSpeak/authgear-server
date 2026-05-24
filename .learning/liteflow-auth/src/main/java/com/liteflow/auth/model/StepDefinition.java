package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 步骤定义模型 - 对应 YAML 中的 step 定义
 * 支持两种类型：identify 和 authenticate
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepDefinition {
    private String type;  // "identify" or "authenticate"
    private List<BranchDefinition> oneOf;
    private List<StepDefinition> steps;  // 子步骤（嵌套）

    /**
     * 判断是否为叶子节点（没有子步骤）
     */
    public boolean isLeaf() {
        return steps == null || steps.isEmpty();
    }

    /**
     * 判断是否有分支选择
     */
    public boolean hasOneOf() {
        return oneOf != null && !oneOf.isEmpty();
    }
}
