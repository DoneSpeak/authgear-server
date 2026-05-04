package learning.authflow.step.handlers;

import com.google.gson.JsonObject;
import learning.authflow.core.Session;
import learning.authflow.core.StepContext;
import learning.authflow.exception.InvalidCredentialsException;
import learning.authflow.input.AuthflowInput;
import learning.authflow.model.StepType;
import learning.authflow.provider.LoginIdProvider;
import learning.authflow.step.StepHandler;
import learning.authflow.step.StepResult;

import java.util.Map;

/**
 * 认证处理器组合器
 * 根据 input 中的 authentication 字段分发到具体处理器
 * 是 AUTHENTICATE 步骤的唯一实现
 */
public class CompositeAuthenticateHandler implements StepHandler {
    private final LoginIdProvider loginIdProvider;
    private final Map<String, AuthenticateHandler> handlers;

    /**
     * @param loginIdProvider 登录ID查询服务
     * @param handlers 认证处理器映射，key 为 authentication 字段值
     */
    public CompositeAuthenticateHandler(LoginIdProvider loginIdProvider,
                                       Map<String, AuthenticateHandler> handlers) {
        this.loginIdProvider = loginIdProvider;
        this.handlers = handlers;
    }

    @Override
    public StepType getType() {
        return StepType.AUTHENTICATE;
    }

    @Override
    public StepResult handle(StepContext ctx, AuthflowInput input) {
        // 提取 authentication 字段
        String method = extractMethod(input);

        // 从 Session 查找用户（使用 record accessor 方法）
        String identification = ctx.getSession().identification();
        String loginId = ctx.getSession().loginId();

        if (identification == null || loginId == null) {
            throw new InvalidCredentialsException("Identification context missing");
        }

        String userId = loginIdProvider.findUserId(loginId, identification);
        if (userId == null) {
            throw new InvalidCredentialsException("User not found");
        }

        // 保存 userId 到 Session（通过 with 方法创建新实例）
        Session updatedSession = ctx.getSession().withUserId(userId);
        ctx.setSession(updatedSession);

        // 获取具体处理器
        AuthenticateHandler handler = handlers.get(method);
        if (handler == null) {
            throw new InvalidCredentialsException("Unsupported authentication method: " + method);
        }

        // 委派给具体处理器
        return handler.handle(ctx, input);
    }

    private String extractMethod(AuthflowInput input) {
        JsonObject json = input.as(JsonObject.class);
        if (!json.has("authentication")) {
            throw new InvalidCredentialsException("Missing authentication method");
        }
        return json.get("authentication").getAsString();
    }
}
