package learning.authflow.web;

import jakarta.validation.Valid;
import learning.authflow.core.AuthflowService;
import learning.authflow.response.AuthflowResponse;
import learning.authflow.web.dto.CreateFlowRequest;
import learning.authflow.web.dto.ExecuteStepRequest;
import learning.authflow.web.dto.GetStateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证流程控制器
 */
@RestController
@RequestMapping("/api/v1/authentication_flows")
public class AuthflowController {

    private final AuthflowService service;

    public AuthflowController(AuthflowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateFlowRequest request) {
        AuthflowResponse response = service.create(request.getType(), request.getName());
        return ResponseEntity.ok(wrapResult(response));
    }

    @PostMapping("/states/input")
    public ResponseEntity<Map<String, Object>> execute(@Valid @RequestBody ExecuteStepRequest request) {
        AuthflowResponse response = service.execute(request.getStateToken(), request.getInput());
        return ResponseEntity.ok(wrapResult(response));
    }

    @PostMapping("/states")
    public ResponseEntity<Map<String, Object>> getState(@Valid @RequestBody GetStateRequest request) {
        AuthflowResponse response = service.getState(request.getStateToken());
        return ResponseEntity.ok(wrapResult(response));
    }

    private Map<String, Object> wrapResult(AuthflowResponse response) {
        Map<String, Object> result = new HashMap<>();
        result.put("result", response);
        return result;
    }
}
