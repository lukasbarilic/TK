# [AI] F03 Prihlásenie, zmena hesla a oprávnenia v UI

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Používateľ sa prihlási lokálnym účtom, pri prvom prihlásení si zmení heslo a vidí len tie časti aplikácie, na ktoré má oprávnenie.

### Akceptačné kritériá

- [ ] Obrazovka prihlásenia (`POST /api/auth/login`) s chybami: zlé meno/heslo, zamknutý účet (s časom odomknutia, ak ho API vráti), server nedostupný
- [ ] Pri `mustChangePassword` sa zobrazí len zmena hesla (`POST /api/me/password`), potom pokračovanie do aplikácie
- [ ] `GET /api/me` po prihlásení; `useMe()` a `usePermission(code, receptionId?)` vyhodnocujú oprávnenia vrátane rozsahu na recepciu (`scopes`, `null` = všetky)
- [ ] Menu a trasy podľa HLD-frontend kap. 6; trasa bez oprávnenia → stránka „Nemáte oprávnenie“; úvodná obrazovka podľa roly
- [ ] Odhlásenie (`POST /api/auth/logout`)
- [ ] 401 z ktorejkoľvek požiadavky → dialóg „Relácia vypršala“ s heslom nad aktuálnou obrazovkou; po prihlásení sa obrazovka obnoví
- [ ] V `AppShell` výber recepcie (z recepcií v rozsahu, predvolená `defaultReceptionId`) a jej obchodný deň (`GET /api/receptions/{id}/business-day`)
- [ ] Testy (MSW): úspešné prihlásenie, povinná zmena hesla, menu pre recepčnú vs. lekára vs. admina, rozsah na recepciu, dialóg pri 401

### Dotknuté súbory / moduly

`frontend/src/features/auth/`, `frontend/src/app/`

### Mimo rozsahu

Offline odomknutie a trezor (F04), zamknutie po nečinnosti (F05).

### Závislosti a podklady

- Závisí od: [F02]
- Návrh: `docs/frontend/DLD-frontend.md` kap. 10, 11; `docs/HLD-ubytovanie.md` kap. 8.4
- Návrh obrazovky: `docs/frontend/ui/Prihlasenie.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
