package com.liteflow.auth.state;

import com.google.gson.Gson;
import com.liteflow.auth.model.ExecutionResult;
import com.liteflow.auth.model.Option;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 状态管理器
 * 使用 Redis 存储流程执行状态
 */
@Slf4j
@Component
public class StateManager {

    private static final String KEY_PREFIX = "liteflow:auth:";
    private static final long EXPIRE_DAYS = 7;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final Gson gson = new Gson();

    /**
     * 生成唯一的 flow ID
     */
    public String generateFlowId() {
        return "flow-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 保存状态
     */
    public void saveState(FlowExecutionState state) {
        String key = buildKey(state.getFlowId());
        String value = gson.toJson(state);

        redisTemplate.opsForValue().set(key, value, EXPIRE_DAYS, TimeUnit.DAYS);
        log.debug("保存状态 - flowId: {}, key: {}", state.getFlowId(), key);
    }

    /**
     * 加载状态
     */
    public FlowExecutionState loadState(String flowId) {
        String key = buildKey(flowId);
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            log.warn("状态不存在 - flowId: {}", flowId);
            return null;
        }

        FlowExecutionState state = gson.fromJson(value, FlowExecutionState.class);
        log.debug("加载状态 - flowId: {}", flowId);
        return state;
    }

    /**
     * 删除状态
     */
    public void deleteState(String flowId) {
        String key = buildKey(flowId);
        redisTemplate.delete(key);
        log.debug("删除状态 - flowId: {}", flowId);
    }

    /**
     * 更新状态为等待输入
     */
    public void updateNeedInput(String flowId, String currentChainId, String currentNodeId,
                                 List<Option> options, List<String> path) {
        FlowExecutionState state = loadState(flowId);
        if (state == null) {
            throw new IllegalStateException("流程状态不存在: " + flowId);
        }

        state.setCurrentChainId(currentChainId);
        state.setCurrentNodeId(currentNodeId);
        state.setCurrentOptions(options);
        state.setPath(path);
        state.setStatus(ExecutionResult.ExecutionStatus.NEED_INPUT);

        saveState(state);
    }

    /**
     * 更新状态为完成
     */
    public void updateCompleted(String flowId, List<String> path) {
        FlowExecutionState state = loadState(flowId);
        if (state == null) {
            throw new IllegalStateException("流程状态不存在: " + flowId);
        }

        state.setPath(path);
        state.setStatus(ExecutionResult.ExecutionStatus.COMPLETED);
        state.setCurrentOptions(null);

        saveState(state);
        log.info("流程完成 - flowId: {}, path: {}", flowId, path);
    }

    /**
     * 添加选择到路径
     */
    public void addChoice(String flowId, String choice) {
        FlowExecutionState state = loadState(flowId);
        if (state == null) {
            throw new IllegalStateException("流程状态不存在: " + flowId);
        }

        state.addToPath(choice);
        saveState(state);
    }

    /**
     * 设置用户选择（用于恢复执行）
     */
    public void setUserChoice(String flowId, String choice) {
        FlowExecutionState state = loadState(flowId);
        if (state == null) {
            throw new IllegalStateException("流程状态不存在: " + flowId);
        }

        state.setUserChoice(choice);
        saveState(state);
    }

    /**
     * 获取用户选择并清除
     */
    public String getAndClearUserChoice(String flowId) {
        FlowExecutionState state = loadState(flowId);
        if (state == null) {
            return null;
        }

        String choice = state.getUserChoice();
        state.setUserChoice(null);
        saveState(state);
        return choice;
    }

    /**
     * 构建 Redis key
     */
    private String buildKey(String flowId) {
        return KEY_PREFIX + flowId;
    }
}
