package sk.therasuite.iam.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.iam.application.MeView.PermissionView;
import sk.therasuite.iam.application.MeView.RoleView;
import sk.therasuite.iam.application.MeView.Scope;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.IamQueries.Grant;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;

@Service
public class MeService {

  private final UserAccountRepository users;
  private final IamQueries queries;

  public MeService(UserAccountRepository users, IamQueries queries) {
    this.users = users;
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public MeView view(long userId) {
    UserAccount u = users.findById(userId).orElseThrow();
    LocalDate today = LocalDate.now();
    List<RoleView> roles =
        queries.assignments(userId).stream()
            .filter(a -> a.validOn(today))
            .map(a ->
                new RoleView(
                    a.roleCode(),
                    a.roleName(),
                    a.centreId(),
                    a.receptionId(),
                    a.validFrom(),
                    a.validTo()))
            .toList();
    Map<String, List<Grant>> byCode = new LinkedHashMap<>();
    queries.grants(userId, today).stream()
        .sorted((a, b) -> a.code().compareTo(b.code()))
        .forEach(g -> byCode.computeIfAbsent(g.code(), k -> new java.util.ArrayList<>()).add(g));
    List<PermissionView> permissions =
        byCode.entrySet().stream()
            .map(e -> {
              List<Grant> grants = e.getValue();
              Integer limit =
                  grants.stream().anyMatch(g -> g.limit() != null)
                      ? PermissionChecker.maxLimit(grants)
                      : null;
              List<Scope> scopes =
                  grants.stream()
                      .map(g -> new Scope(g.centreId(), g.receptionId()))
                      .distinct()
                      .toList();
              return new PermissionView(e.getKey(), limit, scopes);
            })
            .toList();
    return new MeView(
        u.getId(),
        u.getUsername(),
        u.getDisplayName(),
        u.getEmail(),
        u.getLanguage(),
        u.isMustChangePassword(),
        u.getDefaultReceptionId(),
        roles,
        permissions);
  }
}
