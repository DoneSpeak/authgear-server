package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * 创建流程请求模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateRequest {
    private String flowName;
}
