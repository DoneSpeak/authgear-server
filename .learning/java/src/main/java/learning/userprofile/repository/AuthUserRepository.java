package learning.userprofile.repository;

import learning.userprofile.domain.AuthUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserRepository extends JpaRepository<AuthUser, String> {
}
