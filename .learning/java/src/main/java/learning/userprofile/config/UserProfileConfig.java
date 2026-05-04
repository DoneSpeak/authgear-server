package learning.userprofile.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UserProfileProperties.class)
public class UserProfileConfig {
}
