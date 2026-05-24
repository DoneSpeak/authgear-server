package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 流程定义模型 - 对应 YAML 中的 flow 定义
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowDefinition {
    private String name;
    private List<StepDefinition> steps;
}
