# [AI] F01 Kostra frontendu (web režim)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Založiť React aplikáciu v `frontend/` so štruktúrou a nástrojmi z DLD, ktorá beží v prehliadači proti lokálnemu backendu. Tauri pribudne v F02; táto kostra musí byť buildovateľná a testovateľná bez neho (aj AI agentom v CI).

### Akceptačné kritériá

- [ ] `frontend/` podľa DLD kap. 1: Vite + React 19 + TypeScript `strict`, ESLint (vrátane pravidla, že `features/*` neimportujú iné `features/*`), Vitest + Testing Library + MSW, Mantine, TanStack Query, React Router, i18next so `sk.json`
- [ ] Skripty `dev`, `build`, `lint`, `test`, `api:gen` podľa DLD kap. 1; `npm run dev` proxuje `/api` a `/actuator` na `http://localhost:8080`
- [ ] `src/shared/api/schema.d.ts` vygenerované z OpenAPI backendu (profil `local`) a commitnuté; `client.ts` s typovanou funkciou volania nad `Platform.http`
- [ ] `src/shared/platform/` s rozhraním `Platform` presne podľa DLD kap. 2 a implementáciou `web.ts` (fetch s `credentials: 'include'` a CSRF hlavičkou z cookie `XSRF-TOKEN`, `EventSource`, trezor v pamäti); `tauri.ts` zatiaľ len kostra, ktorá hodí chybu „nie je implementované“
- [ ] `shared/api/errors.ts` s `ApiError` podľa DLD kap. 8 a parsovaním `ProblemDetail` (vrátane `confirmable`, `warnings`, `fieldViolations`) + unit testy
- [ ] `AppShell` s prázdnym menu a stránkou „Plán ubytovania – pripravuje sa“; jednotný vzhľad (Mantine téma), slovenské texty len cez i18n
- [ ] `shared/time` s funkciami pre `YYYY-MM-DD` (pridať dni, rozdiel dní, zoznam dní, formát `po 17. 11.`) + unit testy vrátane prechodu na zimný čas
- [ ] `frontend/README.md`: spustenie, generovanie typov API
- [ ] `npm run lint`, `npm test -- --run` a `npm run build` prechádzajú

### Dotknuté súbory / moduly

`frontend/` (nový)

### Mimo rozsahu

Tauri (F02), prihlásenie (F03), akékoľvek obrazovky okrem prázdneho rámca.

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/frontend/DLD-frontend.md` kap. 1, 2, 8; `CLAUDE.md`
- Vizuálny štýl a logo: `docs/frontend/ui/README.md` (farby, písmo Inter, „TheraSuite“ a pod ním „by N-teq“)
