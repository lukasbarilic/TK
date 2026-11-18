# [AI] F12 Zoznam hostí pre lekára (obrazovka, tlač, CSV, offline)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Lekár vidí svojich pacientov a ich izby k danému dňu, vytlačí si ich alebo exportuje do CSV, aj keď server nie je dostupný.

### Akceptačné kritériá

- [ ] Obrazovka z `GET /api/reports/guest-locations` (B05) s výberom dňa (predvolene obchodný deň), budovy a pri `GUEST_LOCATION_VIEW_ALL` aj lekára
- [ ] Stĺpce podľa HLD-ubytovanie kap. 8.5; hosť so zmenou izby v daný deň zvýraznený
- [ ] Tlač (HTML s hlavičkou: dátum, lekár, čas tlače) a CSV (`format=csv` online, offline z lokálnych dát v rovnakom formáte) len s `GUEST_LOCATION_EXPORT`; uloženie cez `Platform.files.save`
- [ ] Offline z trezoru (`guest-list/<date>`), len deň, ktorý je uložený; export offline sa zapíše do denníka (F15)
- [ ] Testy: lekár bez `VIEW_ALL` nemá výber lekára, export bez oprávnenia skrytý, offline zobrazenie, CSV obsah (BOM, `;`)

### Dotknuté súbory / moduly

`frontend/src/features/guest-list/`

### Mimo rozsahu

PDF zo servera, prevzatie pacienta.

### Závislosti a podklady

- Závisí od: [F05], [B05]
- Návrh: `docs/HLD-ubytovanie.md` kap. 8.5
- Návrh obrazovky: `docs/frontend/ui/Lekar.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
