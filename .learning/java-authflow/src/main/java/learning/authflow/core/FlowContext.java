package learning.authflow.core;

import learning.authflow.intent.Intent;
import learning.authflow.intent.impl.StepHandlerIntent;
import learning.authflow.intent.registry.IntentRegistry;
import learning.authflow.milestone.Milestone;
import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;
import learning.authflow.step.registry.StepHandlerRegistry;
import learning.authflow.storage.SessionStorage;
import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * 流程运行时上下文 - 支持 Accept-Loop 状态管理和里程碑查询
 * SRP：负责运行时状态管理，不直接持久化
 */
public class FlowContext {
    @Getter
    private final FlowInstance flow;
    private final IntentRegistry intentRegistry;
    private final StepHandlerRegistry stepHandlerRegistry;
    private final SessionStorage sessionStorage;

    private final Deque<IntentFrame> stack = new ArrayDeque<>();

    public FlowContext(FlowInstance flow, IntentRegistry registry,
                       StepHandlerRegistry stepHandlerRegistry, SessionStorage sessionStorage) {
        this.flow = flow;
        this.intentRegistry = registry;
        this.stepHandlerRegistry = stepHandlerRegistry;
        this.sessionStorage = sessionStorage;
    }

    /**
     * 从 FlowInstance 创建运行时上下文
     */
    public static FlowContext from(FlowInstance flow, IntentRegistry registry,
                                   StepHandlerRegistry stepHandlerRegistry, SessionStorage sessionStorage) {
        FlowContext ctx = new FlowContext(flow, registry, stepHandlerRegistry, sessionStorage);
        ctx.rebuildStack();
        return ctx;
    }

    /**
     * 从当前节点重建运行时 Intent 栈
     * 桥接旧 StepHandler 和新 Intent 架构
     */
    private void rebuildStack() {
        // 获取当前节点的步骤类型
        StepType currentStepType = getCurrentStepType();
        if (currentStepType == null) {
            return;
        }

        // 查找对应的 StepHandler（使用 try-catch 避免异常中断）
        try {
            StepHandler handler = stepHandlerRegistry.get(currentStepType);
            if (handler != null) {
                // 创建桥接 Intent
                Intent bridgeIntent = new StepHandlerIntent(
                    currentStepType, handler, sessionStorage, flow.getFlowId()
                );
                stack.push(new IntentFrame(bridgeIntent));
            }
        } catch (UnsupportedOperationException e) {
            // 如果没有对应的 handler，栈保持为空
        }
    }

    /**
     * 获取当前步骤类型
     */
    private StepType getCurrentStepType() {
        int currentIndex = flow.getCurrentNodeIndex();
        if (currentIndex >= 0 && currentIndex < flow.getNodes().size()) {
            FlowNode node = flow.getNodes().get(currentIndex);
            return node != null ? node.getStepType() : null;
        }
        return null;
    }

    /**
     * 推进到下一个步骤（更新 FlowInstance 的当前节点索引）
     */
    public void advanceToNextStep() {
        int nextIndex = flow.getCurrentNodeIndex() + 1;
        if (nextIndex < flow.getNodes().size()) {
            flow.setCurrentNodeIndex(nextIndex);
        }
        // 重建栈以处理新步骤
        stack.clear();
        rebuildStack();
    }

    /**
     * 获取用户ID（从 Session）
     */
    public String getUserId() {
        Session session = sessionStorage.getOrCreate(flow.getFlowId());
        return session != null ? session.userId() : null;
    }

    /**
     * 检查是否存在指定类型的里程碑
     */
    public boolean hasMilestone(Class<? extends Milestone> type) {
        return findMilestone(type).isPresent();
    }

    /**
     * 查找指定类型的里程碑
     */
    public <T extends Milestone> Optional<T> findMilestone(Class<T> type) {
        for (IntentFrame frame : stack) {
            for (Milestone m : frame.milestones) {
                if (type.isInstance(m)) {
                    return Optional.of(type.cast(m));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 添加里程碑到当前 Intent 帧
     */
    public void addMilestone(Milestone milestone) {
        if (!stack.isEmpty()) {
            stack.peek().milestones.add(milestone);
        }
    }

    /**
     * 将 Intent 压入栈
     */
    public void pushIntent(Intent intent) {
        stack.push(new IntentFrame(intent));
    }

    /**
     * 获取当前 Intent
     */
    public Intent getCurrentIntent() {
        return stack.isEmpty() ? null : stack.peek().intent;
    }

    /**
     * 获取最后一个节点
     */
    public FlowNode getLastNode() {
        if (stack.isEmpty()) return null;
        List<FlowNode> nodes = stack.peek().nodes;
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }

    /**
     * 追加节点到当前 Intent 帧，同时更新 FlowInstance 的线性结构（向后兼容）
     */
    public void appendNode(FlowNode node) {
        if (!stack.isEmpty()) {
            stack.peek().nodes.add(node);
        }
        // 同时更新 FlowInstance 的线性结构以保持向后兼容
        if (flow != null && node != null) {
            flow.getNodes().add(node);
            flow.setCurrentNodeIndex(flow.getNodes().size() - 1);
        }
    }

    /**
     * 检查是否有父级 Intent
     */
    public boolean hasParentIntent() {
        return stack.size() > 1;
    }

    /**
     * 弹出到父级 Intent
     */
    public void popToParent() {
        if (stack.size() > 1) {
            stack.pop();
        }
    }

    /**
     * Intent 帧 - 内部类，管理单个 Intent 的运行时状态
     */
    @Getter
    private static class IntentFrame {
        final Intent intent;
        final List<Milestone> milestones = new ArrayList<>();
        final List<FlowNode> nodes = new ArrayList<>();

        IntentFrame(Intent intent) {
            this.intent = intent;
        }
    }
}
