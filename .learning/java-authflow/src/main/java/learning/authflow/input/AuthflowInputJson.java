package learning.authflow.input;

import com.google.gson.Gson;
import learning.authflow.json.GsonFactory;

/**
 * Gson实现 - 内置Gson，用户只需实现as方法
 */
public class AuthflowInputJson implements AuthflowInput {
    private final String json;
    private final Gson gson;

    private AuthflowInputJson(String json, Gson gson) {
        this.json = json;
        this.gson = gson;
    }

    /**
     * 工厂方法：使用默认Gson创建
     */
    public static AuthflowInput from(String json) {
        return new AuthflowInputJson(json, GsonFactory.getGson());
    }

    /**
     * 工厂方法：使用自定义Gson创建
     */
    public static AuthflowInput from(String json, Gson gson) {
        return new AuthflowInputJson(json, gson);
    }

    @Override
    public <T> T as(Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    /**
     * 获取原始JSON（用于日志或调试）
     */
    public String raw() {
        return json;
    }
}
