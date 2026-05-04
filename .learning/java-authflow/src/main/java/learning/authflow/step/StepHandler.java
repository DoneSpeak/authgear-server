package learning.authflow.step;

import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.model.StepType;

/**
 * 步骤处理器接口 - OCP扩展点
 * 实现类通过 @Component 自动注册到 Spring 容器
 */
public interface StepHandler {
    /**
     * 返回处理的步骤类型
     */
    StepType getType();

    /**
     * 执行步骤逻辑
     * @param ctx 运行时上下文（非持久化）
     * @param input 封装的输入（延迟解析）
     * @return 步骤执行结果
     */
    StepResult handle(StepContext ctx, AuthflowInput input);
}
