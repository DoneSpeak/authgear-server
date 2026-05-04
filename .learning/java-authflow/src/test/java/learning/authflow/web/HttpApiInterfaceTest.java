package learning.authflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import learning.authflow.core.AuthflowService;
import learning.authflow.model.FlowType;
import learning.authflow.model.StepType;
import learning.authflow.response.Action;
import learning.authflow.response.AuthflowResponse;
import learning.authflow.web.dto.CreateFlowRequest;
import learning.authflow.web.dto.ExecuteStepRequest;
import learning.authflow.web.dto.GetStateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-07: HTTP API接口
 */
@WebMvcTest(AuthflowController.class)
@ContextConfiguration(classes = {TestControllerConfig.class})
@DisplayName("UC-07: HTTP API接口")
class HttpApiInterfaceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthflowService authflowService;

    @Nested
    @DisplayName("POST /api/v1/authentication_flows")
    class CreateFlowEndpointTest {

        @Test
        @DisplayName("创建流程端点应返回200和正确响应结构")
        void test_uc07_createFlowEndpoint_returns200WithCorrectStructure() throws Exception {
            CreateFlowRequest request = new CreateFlowRequest("login", "default");
            AuthflowResponse response = createMockResponse("token-123", StepType.IDENTIFY);

            when(authflowService.create("login", "default")).thenReturn(response);

            mockMvc.perform(post("/api/v1/authentication_flows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state_token").value("token-123"))
                .andExpect(jsonPath("$.result.type").value("LOGIN"))
                .andExpect(jsonPath("$.result.action.type").value("IDENTIFY"));
        }

        @Test
        @DisplayName("缺少必填字段应返回400")
        void test_uc07_missingRequiredFields_returns400() throws Exception {
            CreateFlowRequest request = new CreateFlowRequest();

            mockMvc.perform(post("/api/v1/authentication_flows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/authentication_flows/states/input")
    class ExecuteStepEndpointTest {

        @Test
        @DisplayName("执行步骤端点应返回200和新stateToken")
        void test_uc07_executeStepEndpoint_returns200WithNewToken() throws Exception {
            ExecuteStepRequest request = new ExecuteStepRequest("token-123", "{\"code\":\"123\"}");
            AuthflowResponse response = createMockResponse("new-token", StepType.FINISHED);

            when(authflowService.execute("token-123", "{\"code\":\"123\"}"))
                .thenReturn(response);

            mockMvc.perform(post("/api/v1/authentication_flows/states/input")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state_token").value("new-token"))
                .andExpect(jsonPath("$.result.action.type").value("FINISHED"));
        }

        @Test
        @DisplayName("无效stateToken应返回401和错误结构")
        void test_uc07_invalidToken_returns401WithErrorStructure() throws Exception {
            ExecuteStepRequest request = new ExecuteStepRequest("invalid-token", "{}");

            when(authflowService.execute(any(), any()))
                .thenThrow(new learning.authflow.exception.InvalidStateTokenException("Invalid token"));

            mockMvc.perform(post("/api/v1/authentication_flows/states/input")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.name").value("Unauthorized"))
                .andExpect(jsonPath("$.reason").value("InvalidStateToken"))
                .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/authentication_flows/states")
    class GetStateEndpointTest {

        @Test
        @DisplayName("获取状态端点应返回当前流程状态")
        void test_uc07_getStateEndpoint_returnsCurrentFlowState() throws Exception {
            GetStateRequest request = new GetStateRequest("token-123");
            AuthflowResponse response = createMockResponse("token-123", StepType.AUTHENTICATE);

            when(authflowService.getState("token-123")).thenReturn(response);

            mockMvc.perform(post("/api/v1/authentication_flows/states")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state_token").value("token-123"));
        }
    }

    @Nested
    @DisplayName("通用API行为")
    class CommonApiBehaviorTest {

        @Test
        @DisplayName("非法JSON应返回400")
        void test_uc07_invalidJson_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/authentication_flows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{invalid}"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("错误的HTTP方法应返回405")
        void test_uc07_wrongHttpMethod_returns405() throws Exception {
            mockMvc.perform(get("/api/v1/authentication_flows"))
                .andExpect(status().isMethodNotAllowed());
        }
    }

    private AuthflowResponse createMockResponse(String token, StepType actionType) {
        AuthflowResponse response = new AuthflowResponse();
        response.setStateToken(token);
        response.setType(FlowType.LOGIN);
        response.setName("default");

        Action action = new Action();
        action.setType(actionType);
        response.setAction(action);

        return response;
    }
}
