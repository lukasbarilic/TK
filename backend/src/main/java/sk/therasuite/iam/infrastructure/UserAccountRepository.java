package sk.therasuite.iam.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

  Optional<UserAccount> findByUsername(String username);

  boolean existsByUsername(String username);

  long countByUsernameNot(String username);

  List<UserAccount> findAllByOrderByUsernameAsc();

  @Query(
      "select new sk.therasuite.iam.infrastructure.UserFlags("
          + "u.status, u.mustChangePassword) from UserAccount u where u.id = :id")
  Optional<UserFlags> findFlags(long id);
}
