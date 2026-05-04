package learning.userprofile.web;

import learning.userprofile.service.UserinfoService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OIDC-style userinfo endpoint: GET/POST /oauth2/userinfo.
 * For demo, userId, scope and firstParty can be supplied by query params;
 * in production these would come from SecurityContext (JWT/session).
 */
@RestController
public class UserinfoController {

    private final UserinfoService userinfoService;

    public UserinfoController(UserinfoService userinfoService) {
        this.userinfoService = userinfoService;
    }

    @GetMapping(value = "/oauth2/userinfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getUserinfo(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "profile,email") String scope,
            @RequestParam(required = false, defaultValue = "false") boolean firstParty) {
        List<String> scopes = parseScopes(scope);
        Map<String, Object> body = userinfoService.getUserinfo(userId, scopes, firstParty);
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/oauth2/userinfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> postUserinfo(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "profile,email") String scope,
            @RequestParam(required = false, defaultValue = "false") boolean firstParty) {
        List<String> scopes = parseScopes(scope);
        Map<String, Object> body = userinfoService.getUserinfo(userId, scopes, firstParty);
        return ResponseEntity.ok(body);
    }

    private static List<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scope.split("[,\\s]+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
