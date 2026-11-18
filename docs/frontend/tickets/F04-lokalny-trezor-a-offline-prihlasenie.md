# [AI] F04 Lokálny šifrovaný trezor a prihlásenie bez servera

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Offline dáta sa ukladajú na disk šifrovane pre každého používateľa zvlášť. Kľúč sa odvodí z hesla, takže to isté heslo umožní prihlásiť sa aj bez servera.

### Akceptačné kritériá

- [ ] Crate `crates/vault` bez závislosti na Tauri podľa DLD kap. 4.1 a 4.2 (SQLite cez `rusqlite` s `bundled`, Argon2id m=64 MiB t=3 p=1, AES-256-GCM s AAD, `zeroize`)
- [ ] Príkazy `vault_*` podľa DLD kap. 3.1; dáta mimo odomknutého trezoru → chyba `locked`; DEK sa nikdy nevracia do JS
- [ ] `shared/platform/tauri.ts` implementuje `Vault`; `web.ts` má trezor v pamäti s rovnakým správaním (pre testy UI)
- [ ] Priebeh prihlásenia podľa DLD kap. 11 vrátane: vytvorenie trezoru pri prvom online prihlásení, nový trezor pri inom hesle, `vault_wipe` pri deaktivovanom účte, offline prihlásenie, hláška pre používateľa bez trezoru na tomto PC, obmedzenie pokusov
- [ ] Zmena hesla volá `vault.rewrap`
- [ ] „Odhlásiť a vymazať údaje z tohto počítača“ v profile používateľa
- [ ] `cargo test` pre crate podľa DLD kap. 4.3 (vrátane testu, že `vault.db` neobsahuje otvorený text)
- [ ] Testy UI (MSW + webový trezor): offline prihlásenie OK, zlé heslo, chýbajúci trezor, deaktivovaný účet vymaže trezor

### Dotknuté súbory / moduly

`frontend/src-tauri/crates/vault/`, `frontend/src-tauri/src/commands.rs`, `frontend/src/features/auth/`, `frontend/src/shared/platform/`

### Mimo rozsahu

Synchronizácia dát (F05), denník čítaní (F15).

### Závislosti a podklady

- Závisí od: [F03]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 3.3, 4; `docs/frontend/DLD-frontend.md` kap. 4, 11
- Návrh obrazovky: `docs/frontend/ui/Prihlasenie.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
