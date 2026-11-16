# CLAUDE.md – TheraSuite

Pokyny pre AI agenta. Čítaš ich pri každom spustení, preto sú stručné.
Ak niečo z tohto súboru v repozitári ešte neexistuje (projekt začína od nuly),
vytvor to presne podľa tejto štruktúry a konvencií.

## O projekte
TheraSuite – webová aplikácia zložená z backendu (REST API) a frontendu (SPA).

## Technológie
- Backend: Java 25, Spring Boot 4, Maven (s Maven Wrapperom `mvnw`)
- Frontend: React + TypeScript, Vite, Vitest + Testing Library, ESLint
- Komunikácia: REST/JSON, backend na `/api/**`
- Databáza: PostgreSQL 17 + Flyway (migrácie v `backend/src/main/resources/db/migration`); testy cez Testcontainers (potrebujú bežiaci Docker)
- Hlavný návrh: `docs/HLD-ubytovanie.md`

## Štruktúra repozitára
- `backend/` – Spring Boot aplikácia
  - `src/main/java/sk/therasuite/` – kód, balíčky podľa domén (napr. `patient/`, `appointment/`),
    v každej doméne `*Controller`, `*Service`, `*Repository`, DTO
  - `src/test/java/...` – testy v rovnakej štruktúre
- `frontend/` – React aplikácia
  - `src/features/<doména>/` – komponenty, hooky a API volania jednej domény
  - `src/shared/` – spoločné komponenty a utility
- `.github/` – CI a šablóny (nemeniť)

## Lokálne spustenie backendu
1. `cp .env.example .env` (len lokálne hodnoty, `.env` sa necommituje)
2. `docker compose up -d postgres`
3. v `backend/`: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
Profily: `local` (DB z docker-compose, Swagger UI na `/swagger-ui.html`), `demo` (ako `local` + demo dáta), `test` (Testcontainers).

## Príkazy
Backend (spúšťaj v `backend/`):
- Build + testy: `./mvnw -B verify`
- Len testy: `./mvnw -B test`

Frontend (spúšťaj v `frontend/`):
- Inštalácia: `npm ci` (ak chýba `package-lock.json`, tak `npm install`)
- Lint: `npm run lint`
- Testy: `npm test -- --run`
- Build: `npm run build`

Pred dokončením úlohy VŽDY spusti build a testy tej časti, ktorej sa zmena týka
(pri zmene API obe). Ak niečo padá, oprav to alebo v zhrnutí jasne napíš, čo a prečo neprešlo.

## Konvencie
- Backend: konštruktorová injekcia, žiadne `@Autowired` na poliach; DTO ako Java `record`;
  validácia cez `jakarta.validation`; chyby cez `@RestControllerAdvice` s jednotným JSON formátom.
- Každý nový endpoint má test (`@WebMvcTest` alebo `@SpringBootTest`), každá služba unit test.
- Frontend: funkčné komponenty, striktný TypeScript (žiadne `any`), API volania len cez `src/shared/api/`.
- Každý nový komponent s logikou má test vo Viteste.
- Commit správy: `typ: krátky popis (#číslo-issue)`, typy `feat`, `fix`, `test`, `refactor`, `docs`, `chore`.

## Demo a testovacie dáta
Len **fiktívne**: žiadne skutočné mená, adresy, kontakty, rodné čísla ani čísla dokladov (OP, pas).
Ak je údaj potrebný, použi zjavne vymyslenú hodnotu (napr. „Ukážková 1, 000 00 Demovo“, doklad `XX000000`, e-mail `@example.invalid`).

## Čoho sa nedotýkať
- `.github/workflows/` – CI a nasadenie
- databázové migrácie, ktoré už existujú – meniť len novou migráciou
- `.env*`, `application-prod.*` a akékoľvek tajomstvá
- verejné API kontrakty – nemeniť bez výslovného zadania v tikete

## Ako pracovať na tiketoch
1. Ak tiket nemá jasný cieľ alebo akceptačné kritériá, neimplementuj – polož otázky.
2. Rob čo najmenšie zmeny, žiadne nesúvisiace refaktoringy.
3. Ku každej zmene správania pridaj alebo uprav test.
4. Zhrnutie píš po slovensky: čo sa zmenilo, ako je to overené, na čo sa pozrieť pri review.
