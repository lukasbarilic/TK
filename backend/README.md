# TheraSuite – backend ubytovania

Spring Boot 4 (Java 25), PostgreSQL 17, Flyway. Návrh: [`docs/HLD-ubytovanie.md`](../docs/HLD-ubytovanie.md).

## Spustenie lokálne

Potrebné: Java 25 (`java -version`), Docker Desktop (beží „Engine running“).

### 1. Databáza (raz, z koreňa repozitára)
```bash
cp .env.example .env              # len lokálne hodnoty, .env sa necommituje
docker compose up -d postgres     # voliteľne aj pgadmin: docker compose up -d postgres pgadmin
```

### 2. Backend (v priečinku `backend/`)

Git Bash / Linux / macOS:
```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

PowerShell (parameter `-D…` musí byť v úvodzovkách, inak ho PowerShell rozdelí na bodkách):
```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Backend beží na <http://localhost:8080>, je pripravený, keď sa v logu objaví `Started TheraSuiteApplication`.
Spring Boot súbor `.env` nečíta; pripojenie k DB používa predvolené hodnoty z `application-local.yml`
(`therasuite` / `therasuite` na `localhost:5432`), ktoré zodpovedajú `.env.example`.

- Health: <http://localhost:8080/actuator/health>, info: `/actuator/info`
- Swagger UI (profily `local`, `demo`): <http://localhost:8080/swagger-ui.html>
- pgAdmin (prehliadač databázy): <http://localhost:5050> (heslo k DB: `POSTGRES_PASSWORD` z `.env`)
- Frontend: v `frontend/` `npm ci` a `npm run dev`, potom <http://localhost:5173> (pozri `frontend/README.md`)

### 3. Prihlásenie – predvolené heslá

| Profil | Používateľ | Heslo |
|---|---|---|
| `local` | `admin` (alebo `APP_BOOTSTRAP_ADMIN_USERNAME`) | `APP_BOOTSTRAP_ADMIN_PASSWORD`; ak nie je nastavená, **vygeneruje sa a raz sa vypíše do logu** |
| `demo` | `admin`, `recepcna.hlavna`, `recepcna.pavilony`, `veduca`, `auditor` | `Demo123!` (fiktívni používatelia z `src/main/resources/demo/users.csv`) |

**Profil `local`:** administrátor sa vytvorí len pri prvom štarte nad prázdnou databázou. Hľadaj v logu riadok
```
WARN ... BootstrapAdminRunner : Bootstrap: vytvorený administrátor 'admin' s jednorazovým heslom: <HESLO> ...
```
Pri prvom prihlásení systém vyžaduje zmenu hesla. Vlastné heslo namiesto vygenerovaného nastav pred prvým štartom:

```bash
APP_BOOTSTRAP_ADMIN_PASSWORD='MojeHeslo-123' ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
```powershell
$env:APP_BOOTSTRAP_ADMIN_PASSWORD = "MojeHeslo-123"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

**Profil `demo`:** ako `local` + fiktívne demo dáta (izby, rezervácie, používatelia); načítajú sa len do prázdnej databázy.
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

**Zabudnuté heslo / začať od nuly** (zmaže všetky lokálne dáta):
```bash
docker compose down -v && docker compose up -d postgres
```

### Tipy pre Windows
- Ak sú v logu namiesto diakritiky otázniky, pred štartom spusti `chcp 65001`.
- Ak PowerShell hlási *running scripts is disabled*, raz spusti
  `Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned`.

Profily: `local` (DB z docker-compose), `demo` (ako `local` + fiktívne demo dáta), `test` (Testcontainers).

## Testy
```bash
./mvnw -B verify      # testy + Spotless kontrola + JaCoCo report (target/site/jacoco)
./mvnw spotless:apply # automatické formátovanie
```
Integračné testy spúšťajú PostgreSQL cez Testcontainers, preto musí bežať Docker.
