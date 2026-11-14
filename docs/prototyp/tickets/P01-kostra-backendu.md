# [AI] P01 Kostra backendu

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Vytvoriť kostru backendu ubytovania podľa `docs/HLD-ubytovanie.md` (kap. 2), na ktorej budú stavať všetky ďalšie tikety prototypu: Spring Boot aplikácia v `backend/`, PostgreSQL v Dockeri, Flyway, testy s Testcontainers a modulárna štruktúra balíkov.

### Akceptačné kritériá

- [ ] `backend/` obsahuje Spring Boot 4 projekt (Java 25, Maven Wrapper `mvnw`) so závislosťami: Web MVC, Data JPA, Validation, Security (zatiaľ všetko povolené), Actuator, Flyway, PostgreSQL driver, springdoc-openapi, Testcontainers (PostgreSQL), ArchUnit
- [ ] Pluginy z `backend/quality-plugins.xml` (Spotless, JaCoCo) sú v `pom.xml`; `./mvnw -B verify` prechádza
- [ ] `docker-compose.yml` v koreni repozitára so službou `postgres` (PostgreSQL 17, pomenovaný volume, port 5432, prihlasovacie údaje z `.env`, vzor v `.env.example`)
- [ ] Profily: `local` (DB z docker-compose), `demo` (ako `local` + načítanie demo dát v neskorších tiketoch), `test` (Testcontainers)
- [ ] Flyway migrácia `V1__baseline.sql` zapne rozšírenia `btree_gist` a `pg_trgm`; `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`
- [ ] Štruktúra balíkov podľa HLD kap. 2.3 a 2.5: pre každý modul (`iam`, `audit`, `catalog`, `reservation`, `accommodation`, `blocking`, `businessday`, `notification`, `settings`, `shared`) balík s podbalíkmi `api`, `application`, `domain`, `infrastructure` (zatiaľ len `package-info.java`)
- [ ] Spoločná trieda `BaseEntity` (`id`, `version` pre optimistické zamykanie, `created_at`, `created_by`, `updated_at`, `updated_by`) v `shared`
- [ ] Globálny `@RestControllerAdvice`, ktorý vracia chyby ako `ProblemDetail` (RFC 9457) s poľom `code` (napr. `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`); test pre 400 a 404
- [ ] `/actuator/health` a `/actuator/info` (s verziou aplikácie) fungujú; Swagger UI na `/swagger-ui.html` v profiloch `local` a `demo`
- [ ] ArchUnit test: balíky `..domain..` nezávisia od `org.springframework..` ani `jakarta.persistence..`; moduly nepoužívajú cudzie `..infrastructure..`
- [ ] Integračný test spustí aplikáciu nad Testcontainers PostgreSQL a overí, že Flyway prebehol a health je `UP`
- [ ] `CLAUDE.md` aktualizovaný: databáza PostgreSQL 17 + Flyway, testy cez Testcontainers, spustenie lokálne (`docker compose up -d postgres`, `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`), odkaz na `docs/HLD-ubytovanie.md` ako hlavný návrh; pravidlo: Demo a testovacie dáta sú **len fiktívne**: žiadne skutočné mená, adresy, kontakty, rodné čísla ani čísla dokladov (OP, pas). Ak je údaj potrebný, použije sa zjavne vymyslená hodnota (napr. „Ukážková 1, 000 00 Demovo“, doklad `XX000000`, e-mail `@example.invalid`).
- [ ] `README.md` v `backend/` popisuje spustenie lokálne a testov

### Dotknuté súbory / moduly

`backend/pom.xml`, `backend/src/main/java/**`, `backend/src/main/resources/application*.yml`, `backend/src/main/resources/db/migration/V1__baseline.sql`, `docker-compose.yml`, `.env.example`, `CLAUDE.md`, `backend/README.md`

### Mimo rozsahu

Žiadne doménové tabuľky ani endpointy (okrem actuatora). Žiadny frontend. Bezpečnosť len povolená (prihlásenie rieši P03).

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/HLD-ubytovanie.md`, kap. 2.2–2.5
