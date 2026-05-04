package learning.userprofile;

import learning.userprofile.domain.AuthUser;
import learning.userprofile.repository.AuthUserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;

/**
 * 启动时插入一个示例用户，便于调用 API 演示。
 */
@Configuration
public class DataInitializer {

    @Bean
    public ApplicationRunner init(AuthUserRepository repo) {
        return args -> {
            if (repo.existsById("user-1")) return;
            var u = new AuthUser();
            u.setId("user-1");
            u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());
            u.setStandardAttributes(Map.of("given_name", "Alice", "family_name", "Smith", "email", "alice@example.com"));
            u.setCustomAttributes(Map.of("company_id", "acme", "score", 85));
            repo.save(u);
        };
    }
}
