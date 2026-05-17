package com.routor;

import com.routor.engine.FlowEngine;
import com.routor.engine.FlowLoader;
import com.routor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整流程测试 - 验证用户需求的两个案例
 */
class FullFlowTest {
    private static final String COMPLEX_YAML = """
        login_flows:
          - name: default_login_flow
            type: LOGIN
            steps:
              - type: identify
                oneOf:
                  - identification: oauth
                  - identification: passkey
                  - identification: email
                    steps:
                      - type: authenticate
                        oneOf:
                          - authentication: primary_passkey
                          - authentication: primary_password
                            steps:
                              - type: authenticate
                                oneOf:
                                  - authentication: secondary_totp
                          - authentication: primary_oob_otp_email
                            steps:
                              - type: authenticate
                                oneOf:
                                  - authentication: secondary_totp
        """;

    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        FlowLoader loader = new FlowLoader();
        engine = new FlowEngine(loader.loadAll(COMPLEX_YAML));
    }

    @Test
    void case1_oauthDirectComplete() {
        // 案例 1: identification: oauth -> 直接完成
        FlowInstance flow = engine.create("default_login_flow");

        // 第一步：获取选项
        ExecutionResult result1 = engine.execute(flow, null);
        assertEquals(State.NEED_INPUT, result1.getState());
        assertEquals(3, result1.getOptions().size());

        // 选择 oauth
        ExecutionResult result2 = engine.execute(flow, "oauth");
        assertEquals(State.COMPLETED, result2.getState());
        assertEquals(1, result2.getPath().size());
        assertEquals("oauth", result2.getPath().get(0));
    }

    @Test
    void case2_emailPasswordTotp() {
        // 案例 2: email -> primary_password -> secondary_totp
        FlowInstance flow = engine.create("default_login_flow");

        // 第一步：identify
        engine.execute(flow, null);
        ExecutionResult result1 = engine.execute(flow, "email");
        assertEquals(State.NEED_INPUT, result1.getState());
        assertEquals("email", result1.getPath().get(0));

        // 第二步：authenticate - 选择 primary_password
        ExecutionResult result2 = engine.execute(flow, "primary_password");
        assertEquals(State.NEED_INPUT, result2.getState());
        assertEquals(2, result2.getPath().size());
        assertEquals("primary_password", result2.getPath().get(1));

        // 第三步：authenticate - 选择 secondary_totp
        ExecutionResult result3 = engine.execute(flow, "secondary_totp");
        assertEquals(State.COMPLETED, result3.getState());
        assertEquals(3, result3.getPath().size());
        assertEquals("secondary_totp", result3.getPath().get(2));

        // 验证完整路径
        assertEquals("email", result3.getPath().get(0));
        assertEquals("primary_password", result3.getPath().get(1));
        assertEquals("secondary_totp", result3.getPath().get(2));
    }

    @Test
    void case3_emailOobTotp() {
        // 案例 3: email -> primary_oob_otp_email -> secondary_totp
        FlowInstance flow = engine.create("default_login_flow");

        engine.execute(flow, null);
        engine.execute(flow, "email");
        ExecutionResult result = engine.execute(flow, "primary_oob_otp_email");
        assertEquals(State.NEED_INPUT, result.getState());

        ExecutionResult finalResult = engine.execute(flow, "secondary_totp");
        assertEquals(State.COMPLETED, finalResult.getState());
        assertEquals(3, finalResult.getPath().size());
    }

    @Test
    void shouldHandleInvalidSelection() {
        FlowInstance flow = engine.create("default_login_flow");

        engine.execute(flow, null);
        ExecutionResult result = engine.execute(flow, "invalid_option");

        assertEquals(State.ERROR, result.getState());
        assertNotNull(result.getMessage());
    }

    @Test
    void shouldSerializeAndDeserialize() {
        FlowInstance flow = engine.create("default_login_flow");
        engine.execute(flow, null);
        engine.execute(flow, "email");

        String serialized = engine.serialize(flow);
        assertNotNull(serialized);

        FlowInstance restored = engine.deserialize(serialized);
        assertNotNull(restored);
        assertEquals(flow.getFlowId(), restored.getFlowId());
        assertEquals(flow.getPath(), restored.getPath());
    }
}
