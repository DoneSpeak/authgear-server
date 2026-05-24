package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 分支定义模型 - 对应 YAML 中的 one_of 分支
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchDefinition {
    private String identification;  // "email", "oauth", "passkey"
    private String authentication;  // "primary_password", "secondary_totp"
    private List<StepDefinition> steps;  // 选择此分支后的子步骤

    /**
     * 获取分支的唯一标识
     */
    public String getBranchId() {
        return identification != null ? identification : authentication;
    }

    /**
     * 判断是否为叶子分支（没有子步骤）
     */
    public boolean isLeaf() {
        return steps == null || steps.isEmpty();
    }
}
