package com.liteflow.auth.controller;

import com.liteflow.auth.engine.FlowExecutionEngine;
import com.liteflow.auth.model.CreateRequest;
import com.liteflow.auth.model.ExecutionRequest;
import com.liteflow.auth.model.ExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 流程控制器
 * REST API 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/flow")
public class FlowController {

    @Autowired
    private FlowExecutionEngine flowEngine;

    /**
     * 创建流程
     */
    @PostMapping("/create")
    public ResponseEntity<ExecutionResult> create(@RequestBody CreateRequest request) {
        log.info("创建流程请求 - flowName: {}", request.getFlowName());

        try {
            ExecutionResult result = flowEngine.create(request.getFlowName());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("创建流程失败", e);
            return ResponseEntity.badRequest()
                    .body(ExecutionResult.error(null, e.getMessage()));
        }
    }

    /**
     * 获取流程状态
     */
    @GetMapping("/{flowId}")
    public ResponseEntity<ExecutionResult> getState(@PathVariable String flowId) {
        log.debug("获取流程状态 - flowId: {}", flowId);

        try {
            ExecutionResult result = flowEngine.getState(flowId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取流程状态失败 - flowId: {}", flowId, e);
            return ResponseEntity.badRequest()
                    .body(ExecutionResult.error(flowId, e.getMessage()));
        }
    }

    /**
     * 执行流程（提交选择）
     */
    @PostMapping("/{flowId}/execute")
    public ResponseEntity<ExecutionResult> execute(
            @PathVariable String flowId,
            @RequestBody ExecutionRequest request) {
        log.info("执行流程请求 - flowId: {}, choice: {}", flowId, request.getChoice());

        try {
            ExecutionResult result = flowEngine.execute(flowId, request.getChoice());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("执行流程失败 - flowId: {}", flowId, e);
            return ResponseEntity.badRequest()
                    .body(ExecutionResult.error(flowId, e.getMessage()));
        }
    }
}
