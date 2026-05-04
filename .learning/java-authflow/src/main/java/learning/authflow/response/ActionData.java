package learning.authflow.response;

import lombok.Data;

import java.util.List;

/**
 * 动作数据
 */
@Data
public class ActionData {
    private String type;
    private List<Option> options;

    @Data
    public static class Option {
        private String identification;
        private String authentication;
    }
}
