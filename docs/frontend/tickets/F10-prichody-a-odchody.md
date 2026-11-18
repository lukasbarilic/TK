# [AI] F10 Príchody a odchody

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepcia vidí, kto dnes a zajtra prichádza a odchádza, aj počas výpadku spojenia.

### Akceptačné kritériá

- [ ] Obrazovka s prepínačom dnes / zajtra (podľa obchodného dňa vybranej recepcie) a dvoma zoznamami z `GET /api/receptions/{id}/movements` (B02)
- [ ] Stĺpce podľa B02, triedenie podľa izby a mena, počty v hlavičke
- [ ] Klik na riadok → detail karty (F09)
- [ ] Offline z trezoru (`movements/<receptionId>:<date>`) s vekom dát
- [ ] Tlač zoznamu (tlač HTML z aplikácie)
- [ ] Testy: zobrazenie online, offline, prepnutie dňa, prázdny zoznam

### Dotknuté súbory / moduly

`frontend/src/features/movements/`

### Mimo rozsahu

Check-in a check-out (zmena stavu karty).

### Závislosti a podklady

- Závisí od: [F05], [F09], [B02]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 3.2
- Návrh obrazovky: `docs/frontend/ui/Prichody.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
