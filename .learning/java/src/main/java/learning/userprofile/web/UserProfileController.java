package learning.userprofile.web;

import learning.userprofile.config.ProfileRole;
import learning.userprofile.service.CustomAttributesService;
import learning.userprofile.service.StandardAttributesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 演示用：按 role 更新/读取 standard 与 custom attributes。
 * 实际使用时应从安全上下文解析 role，这里用 query 参数简化。
 */
@RestController
@RequestMapping("/api/users/{userId}/profile")
public class UserProfileController {

    private final StandardAttributesService standardAttributesService;
    private final CustomAttributesService customAttributesService;

    public UserProfileController(StandardAttributesService standardAttributesService,
                                 CustomAttributesService customAttributesService) {
        this.standardAttributesService = standardAttributesService;
        this.customAttributesService = customAttributesService;
    }

    @GetMapping("/standard")
    public ResponseEntity<Map<String, Object>> getStandardAttributes(
            @PathVariable String userId,
            @RequestParam(defaultValue = "PORTAL_UI") ProfileRole role) {
        return ResponseEntity.ok(standardAttributesService.readStandardAttributes(userId, role));
    }

    @PutMapping("/standard")
    public ResponseEntity<Void> updateStandardAttributes(
            @PathVariable String userId,
            @RequestParam(defaultValue = "PORTAL_UI") ProfileRole role,
            @RequestBody Map<String, Object> body) {
        standardAttributesService.updateStandardAttributes(userId, role, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/custom")
    public ResponseEntity<Map<String, Object>> getCustomAttributes(
            @PathVariable String userId,
            @RequestParam(defaultValue = "PORTAL_UI") ProfileRole role) {
        return ResponseEntity.ok(customAttributesService.readCustomAttributes(userId, role));
    }

    @PutMapping("/custom")
    public ResponseEntity<Void> updateCustomAttributes(
            @PathVariable String userId,
            @RequestParam(defaultValue = "PORTAL_UI") ProfileRole role,
            @RequestBody Map<String, Object> body) {
        customAttributesService.updateCustomAttributes(userId, role, body);
        return ResponseEntity.noContent().build();
    }
}
