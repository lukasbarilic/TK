package sk.therasuite.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.iam.application.BootstrapAdminRunner;
import sk.therasuite.iam.application.BootstrapProperties;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;

class BootstrapAdminRunnerTest {

  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final IamQueries queries = mock(IamQueries.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);

  BootstrapAdminRunnerTest() {
    when(encoder.encode(any())).thenAnswer(i -> "hash:" + i.getArgument(0));
    when(users.saveAndFlush(any(UserAccount.class)))
        .thenAnswer(i -> {
          UserAccount u = i.getArgument(0);
          org.springframework.test.util.ReflectionTestUtils.setField(u, "id", 5L);
          return u;
        });
    when(queries.roleId("ADMIN")).thenReturn(Optional.of(7L));
    doAnswer(
            i -> {
              i.<Consumer<Object>>getArgument(0).accept(null);
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
  }

  private BootstrapAdminRunner runner(String username, String password) {
    return new BootstrapAdminRunner(
        users, queries, encoder, new BootstrapProperties(username, password), tx);
  }

  @Test
  void createsAdminFromEnvironmentWithMustChangePassword() {
    runner("spravca", "Pociatocne-heslo-1").run(null);

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(users).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getUsername()).isEqualTo("spravca");
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash:Pociatocne-heslo-1");
    assertThat(saved.getValue().isMustChangePassword()).isTrue();
    verify(queries).addAssignment(anyLong(), eq(7L), eq(null), eq(null), eq(null), eq(null));
  }

  @Test
  void generatesOneTimePasswordWhenMissing() {
    runner(null, null).run(null);

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(users).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getUsername()).isEqualTo("admin");
    assertThat(saved.getValue().getPasswordHash()).startsWith("hash:").hasSize(5 + 16);
    assertThat(saved.getValue().isMustChangePassword()).isTrue();
  }

  @Test
  void doesNothingWhenUsersAlreadyExist() {
    when(users.countByUsernameNot("system")).thenReturn(1L);

    runner("spravca", "Pociatocne-heslo-1").run(null);

    verify(users, never()).saveAndFlush(any());
  }
}
