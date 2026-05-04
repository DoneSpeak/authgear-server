package learning.authflow.step.registry;

import learning.authflow.model.StepType;
import learning.authflow.step.StepHandler;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 处理器注册表 - 收集所有 StepHandler 实现
 */
public class StepHandlerRegistry {
    private final Map<StepType, StepHandler> handlers;

    public StepHandlerRegistry(List<StepHandler> handlerList) {
        this.handlers = handlerList.stream()
            .collect(Collectors.toMap(
                StepHandler::getType,
                Function.identity()
            ));
    }

    public StepHandler get(StepType type) {
        StepHandler handler = handlers.get(type);
        if (handler == null) {
            throw new UnsupportedOperationException("Unsupported step type: " + type);
        }
        return handler;
    }
}
