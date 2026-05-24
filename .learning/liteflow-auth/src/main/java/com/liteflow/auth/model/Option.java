package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * 用户可选项 - 用于前端展示
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Option {
    private String id;           // 选项标识
    private String type;         // "identification" | "authentication"
    private String displayName;  // 显示名称
    private boolean hasSubSteps; // 是否有子步骤

    /**
     * 从分支定义创建选项
     */
    public static Option fromBranch(BranchDefinition branch, String type) {
        return Option.builder()
                .id(branch.getBranchId())
                .type(type)
                .displayName(branch.getBranchId())
                .hasSubSteps(!branch.isLeaf())
                .build();
    }
}
