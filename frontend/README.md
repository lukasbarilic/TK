# TheraSuite – frontend

React 19 + TypeScript (strict) + Vite, Mantine, TanStack Query, React Router, i18next (`src/shared/i18n/sk.json`).
Testy: Vitest + Testing Library + MSW. Štruktúra a pravidlá: `docs/frontend/DLD-frontend.md` kap. 1.

## Spustenie (web režim)

```bash
npm ci                 # ak chýba package-lock.json, tak npm install
npm run dev            # http://localhost:5173
```

`npm run dev` proxuje `/api` a `/actuator` na `http://localhost:8080`, preto musí bežať backend
(pozri koreňový `CLAUDE.md`: `docker compose up -d postgres` a v `backend/`
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`).

## Skripty

| Príkaz | Účel |
|---|---|
| `npm run dev` | vývojový server s proxy na backend |
| `npm run build` | kontrola typov (`tsc -b`) a produkčný build do `dist/` |
| `npm run lint` | ESLint (vrátane zákazu importov medzi `src/features/*`) |
| `npm test -- --run` | jednorazové spustenie testov (bez `--run` ide watch režim) |
| `npm run api:gen` | vygeneruje `src/shared/api/schema.d.ts` z OpenAPI backendu |

## Generovanie typov API

1. Spusti backend s profilom `local` (OpenAPI je na `http://localhost:8080/v3/api-docs`).
2. `npm run api:gen`
3. Zmenený `src/shared/api/schema.d.ts` commitni spolu so zmenou backendu.

Volania servera idú len cez `src/shared/api/` (`apiCall` z `client.ts` nad `Platform.http`).

## Platforma

`src/shared/platform/` skrýva rozdiel medzi prehliadačom (`web.ts`: fetch s cookie a CSRF hlavičkou,
`EventSource`, trezor v pamäti) a Tauri (`tauri.ts`, zatiaľ len kostra – príde v F02).
