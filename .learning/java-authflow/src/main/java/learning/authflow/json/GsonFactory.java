package learning.authflow.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Gson工厂 - 提供配置好的Gson实例
 */
public class GsonFactory {
    private static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .create();

    public static Gson getGson() {
        return GSON;
    }
}
