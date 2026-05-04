package learning.authflow.input;

/**
 * 流程输入接口 - 封装原始JSON，延迟解析
 * 符合ISP：只有一个as方法
 */
public interface AuthflowInput {
    /**
     * 将输入转换为指定类型
     * @param clazz 目标类型
     * @return 解析后的对象
     */
    <T> T as(Class<T> clazz);
}
