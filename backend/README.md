# TheraSuite – backend ubytovania

Spring Boot 4 (Java 25), PostgreSQL 17, Flyway. Návrh: [`docs/HLD-ubytovanie.md`](../docs/HLD-ubytovanie.md).

## Spustenie lokálne
```bash
cp ../.env.example ../.env          # len lokálne hodnoty
docker compose -f ../docker-compose.yml up -d postgres   # alebo z koreňa: docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
- Health: <http://localhost:8080/actuator/health>, info: `/actuator/info`
- Swagger UI (profily `local`, `demo`): <http://localhost:8080/swagger-ui.html>

Profily: `local` (DB z docker-compose), `demo` (ako `local`, demo dáta pribudnú neskôr), `test` (Testcontainers).

## Testy
```bash
./mvnw -B verify      # testy + Spotless kontrola + JaCoCo report (target/site/jacoco)
./mvnw spotless:apply # automatické formátovanie
```
Integračné testy spúšťajú PostgreSQL cez Testcontainers, preto musí bežať Docker.
