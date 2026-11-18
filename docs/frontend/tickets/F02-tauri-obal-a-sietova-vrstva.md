# [AI] F02 Tauri obal, HTTP a SSE cez natívne jadro, konfigurácia servera

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Zabaliť frontend do Tauri 2 a presunúť komunikáciu so serverom do Rust jadra, aby fungovala session cookie so `SameSite=Strict`, CSRF a certifikáty internej CA zákazníka bez zmeny backendu.

### Akceptačné kritériá

- [ ] `frontend/src-tauri/` podľa DLD kap. 1 a 3; Cargo workspace s crate `app` (a prázdnym `crates/vault` pre F04)
- [ ] Príkazy `http_request`, `live_connect`, `live_disconnect`, `config_get`, `config_set_server_url`, `config_device_id`, `app_version` podľa DLD kap. 3.1; `capabilities/default.json` povoľuje len ich a plugin `dialog`
- [ ] HTTP: `reqwest` + `rustls-platform-verifier` + cookie jar v pamäti; automatické doplnenie `X-XSRF-TOKEN` pri zápisových metódach (DLD kap. 3.2); požiadavky len na nakonfigurovaný server a povolené cesty; `https://` povinné mimo debug buildu
- [ ] SSE: parsovanie udalostí, opätovné pripojenie s oneskorením podľa DLD kap. 3.2, udalosti `live://event` a `live://state`; po 401 sa nepripája
- [ ] Konfigurácia podľa DLD kap. 3.3 (`%APPDATA%` → `%ProgramData%` → obrazovka „Adresa servera“ s overením cez `GET /actuator/health`)
- [ ] `shared/platform/tauri.ts` implementuje `http`, `live`, `config`, `app.version` cez príkazy
- [ ] Prísne CSP v `tauri.conf.json`, vývojárske nástroje vypnuté v release builde
- [ ] Logy podľa DLD kap. 3.2 (bez tela a bez query parametrov)
- [ ] Rust unit testy: skladanie URL a odmietnutie nepovolenej cesty, parsovanie SSE (viacriadkové `data`, `id`, komentáre), doplnenie CSRF hlavičky
- [ ] Ručne overené na Windows a popísané v PR: prihlásenie proti backendu s profilom `demo` cez HTTPS so self-signed certifikátom pridaným do úložiska Windows, `GET /api/me`, príjem `heartbeat`

### Dotknuté súbory / moduly

`frontend/src-tauri/`, `frontend/src/shared/platform/tauri.ts`

### Mimo rozsahu

Trezor (F04), aktualizácie (F16), MSI balík (F17). Úprava CI workflow (robí sa ručne).

### Závislosti a podklady

- Závisí od: [F01]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 2.3, `docs/frontend/DLD-frontend.md` kap. 2, 3
