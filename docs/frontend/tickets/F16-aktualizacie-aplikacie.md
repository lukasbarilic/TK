# [AI] F16 Aktualizácie aplikácie a kontrola kompatibility verzie

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Aplikácia sa aktualizuje zo servera zákazníka bez internetu a nedovolí pracovať verzii, ktorú backend už nepodporuje.

### Akceptačné kritériá

- [ ] `tauri-plugin-updater` podľa DLD kap. 12; endpoint `<serverUrl>/updates/latest.json`, overenie podpisu verejným kľúčom
- [ ] Kontrola pri štarte a každú hodinu (len online); upozornenie „Je k dispozícii nová verzia“; inštalácia v režime `passive` a reštart
- [ ] `minClientVersion` z `client-config` (B01): staršia verzia → blokujúca obrazovka s aktualizáciou; offline sa nekontroluje
- [ ] Skript / postup na vytvorenie `latest.json` a podpísaného balíka (súkromný kľúč mimo repozitára, z premennej prostredia)
- [ ] Návod v `docs/frontend/AKTUALIZACIE.md`: kam na serveri zákazníka nahrať súbory, ako ich servíruje proxy
- [ ] Testy: porovnanie verzií (semver), blokujúca obrazovka pri nižšej verzii (MSW)

### Dotknuté súbory / moduly

`frontend/src-tauri/`, `frontend/src/features/updates/`, `docs/frontend/`

### Mimo rozsahu

Automatické nahratie na server zákazníka, CI pipeline (ručne).

### Závislosti a podklady

- Závisí od: [F02], [B01]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 2.4; `docs/frontend/DLD-frontend.md` kap. 12
