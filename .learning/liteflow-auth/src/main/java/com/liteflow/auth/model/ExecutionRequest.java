package com.liteflow.auth.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * 执行请求模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionRequest {
    private String choice;  // 用户选择
}
