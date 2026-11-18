# [AI] F11 Nočná uzávierka recepcie

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepčná alebo vedúca spraví nočnú uzávierku: pozrie si zistenia, a ak nič neblokuje, potvrdí uzavretie dňa.

### Akceptačné kritériá

- [ ] Obrazovka len s `BUSINESS_DAY_CLOSE` a pre recepcie v rozsahu
- [ ] `POST /api/receptions/{id}/business-day/close/preview` → zoznam zistení rozdelených na blokujúce a na vedomie, s odkazmi na karty a plán
- [ ] „Uzavrieť deň“ aktívne len bez blokujúcich zistení; potvrdzovací dialóg s dátumom; `POST …/close`
- [ ] Po úspechu: nový obchodný deň v `AppShell`, úplná synchronizácia recepcie
- [ ] Offline celé neaktívne s vysvetlením
- [ ] Testy (MSW): náhľad s blokujúcim zistením, úspešná uzávierka, 409 (niekto iný medzitým uzavrel)

### Dotknuté súbory / moduly

`frontend/src/features/business-day/`

### Mimo rozsahu

Nastavenie pravidiel uzávierky.

### Závislosti a podklady

- Závisí od: [F05]
- Návrh: `docs/HLD-ubytovanie.md` kap. 5.8
- Návrh obrazovky: `docs/frontend/ui/Uzavierka.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
