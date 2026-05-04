package learning.authflow.step.handlers;

import learning.authflow.core.StepContext;
import learning.authflow.input.AuthflowInput;
import learning.authflow.step.StepResult;

/**
 * 认证处理器抽象基类（内部使用，不实现 StepHandler）
 * 所有认证方式处理器（包括组合器和叶子节点）继承此类
 */
public abstract class AuthenticateHandler {

    public abstract StepResult handle(StepContext ctx, AuthflowInput input);
}
