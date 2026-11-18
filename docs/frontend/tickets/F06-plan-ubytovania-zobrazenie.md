# [AI] F06 Plán ubytovania – zobrazenie, filtre a živé zmeny

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Hlavná obrazovka recepcie: izby a lôžka × dni s pásikmi pobytov, držaných lôžok a blokácií, ktorá sa sama aktualizuje pri zmenách iných používateľov a funguje aj offline.

### Akceptačné kritériá

- [ ] Čistá funkcia `buildPlanVM` podľa DLD kap. 9.1 s unit testami (orezanie na okno, blokácia cez všetky lôžka, extra lôžko, prázdna izba)
- [ ] Mriežka podľa DLD kap. 9.2: prilepený ľavý stĺpec a hlavička, virtualizované riadky, pásiky od polovice dňa, víkendy, čiara obchodného dňa, šrafovanie dní bez lokálnych dát, 3 úrovne zoomu
- [ ] Farby z `plan.colors` (B06), inak predvolené
- [ ] Filtre podľa DLD kap. 9.4; posledný pohľad sa uloží do `plan.defaultView`
- [ ] Klik na pásik → bočný panel s pobytom a kartou (offline z trezoru)
- [ ] Živé zmeny: `plan-changed` obnoví len dotknuté izby, `business-day-changed` celý plán; vlastné zmeny (rovnaký `auditEventId`) sa nespracujú dvakrát
- [ ] Offline: dáta z trezoru cez `offlineQuery`, lišta s vekom dát
- [ ] Výkon: 200 izieb × 31 dní (generované testovacie dáta) – prvé vykreslenie do 500 ms na vývojovom PC; meranie popísané v PR
- [ ] Testy komponentov: vykreslenie pásikov na správnych stĺpcoch, filter `freeOnly`, aktualizácia po `plan-changed`, offline stav

### Dotknuté súbory / moduly

`frontend/src/features/plan/`

### Mimo rozsahu

Zápisy z plánu (F07, F08), filtre izieb podľa vlastností (UC17), kontingenty.

### Závislosti a podklady

- Závisí od: [F05], [B06]
- Návrh: `docs/frontend/DLD-frontend.md` kap. 9; `docs/HLD-ubytovanie.md` kap. 6.2, 6.4
- Návrh obrazovky: `docs/frontend/ui/Main.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
