# [AI] P03 Používatelia, roly, oprávnenia a prihlásenie

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Zaviesť aktérov systému: lokálne účty, ktoré zakladá administrátor, roly s oprávneniami (aj s limitom a rozsahom na stredisko či recepciu) a prihlásenie. Od tohto tiketu sú všetky endpointy `/api/**` chránené.

### Akceptačné kritériá

- [ ] Tabuľky podľa HLD kap. 8.2 a 8.4: `user_account` (vrátane `must_change_password`, `failed_login_count`, `locked_until`, `password_changed_at`, `default_reception_id`), `role`, `permission`, `role_permission` (`limit_value`), `user_role` (`centre_id`, `reception_id`, `valid_from`, `valid_to`); doplnené cudzie kľúče `created_by` / `updated_by` → `user_account` vo všetkých existujúcich tabuľkách
- [ ] Seed migrácia: všetky oprávnenia z HLD kap. 8.3 + `RESERVATION_VIEW`, `RESERVATION_EDIT`; predvolené roly `RECEPTIONIST`, `RECEPTION_MANAGER`, `DOCTOR`, `ADMIN`, `AUDITOR`, `SYSTEM` s oprávneniami podľa kap. 8.3 (`STAY_CREATE` s limitom 365); technický používateľ `system` (nedá sa prihlásiť)
- [ ] Prihlásenie `POST /api/auth/login` (JSON), `POST /api/auth/logout`; serverová relácia s cookie `HttpOnly`, `Secure`, `SameSite=Strict`; CSRF ochrana vhodná pre SPA (token v cookie + hlavička); vypršanie po nečinnosti podľa `security.session.idleTimeout`
- [ ] Heslá: Argon2id (Spring Security `Argon2PasswordEncoder`); po `security.login.maxFailedAttempts` neúspešných pokusoch `status = LOCKED`
- [ ] Bootstrap: pri prvom štarte bez používateľov vznikne administrátor z `APP_BOOTSTRAP_ADMIN_USERNAME` a `APP_BOOTSTRAP_ADMIN_PASSWORD` (s `must_change_password = true`); ak premenné chýbajú, vygeneruje jednorazové heslo a vypíše ho raz do logu
- [ ] Kým má používateľ `must_change_password`, môže volať len `POST /api/me/password` a `POST /api/auth/logout`
- [ ] `GET /api/me`: používateľ, roly, efektívne oprávnenia (s limitmi a rozsahom), predvolená recepcia
- [ ] Správa (oprávnenie `USER_ADMIN`): `GET/POST/PATCH /api/admin/users`, `PUT /api/admin/users/{id}/roles`, `POST /api/admin/users/{id}/reset-password`, `POST /api/admin/users/{id}/unlock`; používateľ sa nemaže, len `DISABLED`
- [ ] Služba `PermissionChecker`: `require(permission)`, `require(permission, receptionId)`, `limit(permission)` (najvyšší limit zo všetkých platných rolí); kód kontroluje oprávnenia, nikdy názvy rolí
- [ ] Čítacie endpointy z P02 vyžadujú `PLAN_VIEW`; `/actuator/health`, `/api/auth/login` a v profiloch `local`/`demo` Swagger sú verejné
- [ ] V profile `demo` sa vytvoria používatelia z `docs/demo-data/users.csv` (heslo bez vynútenej zmeny)
- [ ] Testy: prihlásenie, zamknutie po N pokusoch, vynútená zmena hesla, bootstrap, 401 bez prihlásenia, 403 bez oprávnenia, rozsah roly na recepciu, výpočet limitu pri viacerých rolách

### Dotknuté súbory / moduly

Modul `iam`, konfigurácia Spring Security, migrácie `V3__iam.sql`, seed oprávnení a rolí

### Mimo rozsahu

OIDC / firemné prihlásenie, dvojfaktorové overenie, pravidlá zloženia hesla nad rámec minimálnej dĺžky 10 znakov. Audit prihlásení (P04).

### Závislosti a podklady

- Závisí od: [P02]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 8
