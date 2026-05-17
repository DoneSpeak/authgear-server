package learning.authflow.core;

import learning.authflow.intent.Intent;
import learning.authflow.intent.registry.IntentRegistry;
import learning.authflow.milestone.Milestone;
import lombok.Getter;

import java.util.*;

/**
 * 流程运行时上下文 - 支持 Accept-Loop 状态管理和里程碑查询
 * SRP：负责运行时状态管理，不直接持久化
 */
public class FlowContext {
    @Getter
    private final FlowInstance flow;
    private final IntentRegistry intentRegistry;

    private final Deque<IntentFrame> stack = new ArrayDeque<>();

    public FlowContext(FlowInstance flow, IntentRegistry registry) {
        this.flow = flow;
        this.intentRegistry = registry;
    }

    /**
     * 从 FlowInstance 创建运行时上下文
     */
    public static FlowContext from(FlowInstance flow, IntentRegistry registry) {
        FlowContext ctx = new FlowContext(flow, registry);
        ctx.rebuildStack();
        return ctx;
    }

    /**
     * 从序列化的 IntentNode 树重建运行时 Intent 栈
     * TODO: 这个实现将在后续任务中完善
     */
    private void rebuildStack() {
        // 目前为空实现，后续根据序列化结构重建
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
     * 追加节点到当前 Intent 帧
     */
    public void appendNode(FlowNode node) {
        if (!stack.isEmpty()) {
            stack.peek().nodes.add(node);
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
