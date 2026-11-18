# [AI] F09 Rezervačné karty: vyhľadávanie a detail (aj offline)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepčná vyhľadá kartu podľa mena alebo čísla a otvorí jej detail s hosťami a pobytmi. Počas výpadku vyhľadáva v kartách uložených v trezore.

### Akceptačné kritériá

- [ ] Vyhľadávanie online cez `GET /api/reservations` (parametre podľa OpenAPI), stránkovanie
- [ ] Detail `GET /api/reservations/{id}`: karta, hostia, kontaktné údaje (len s `RESERVATION_VIEW`), pobyty, lekár; odkazy na plán (skok na izbu a dátum)
- [ ] Offline: detail z trezoru (`reservation/<id>`), vyhľadávanie lokálne v uložených kartách podľa mena (bez diakritiky, nezáleží na veľkosti písmen) a čísla karty; viditeľné upozornenie, že sa hľadá len v kartách uložených na tomto PC
- [ ] Úprava karty (`PATCH /api/reservations/{id}`) len online cez `<WriteAction>`, s optimistic locking (`version`)
- [ ] Testy: vyhľadanie online, detail, offline detail a lokálne vyhľadanie s diakritikou, karta mimo trezoru offline → prázdny stav

### Dotknuté súbory / moduly

`frontend/src/features/reservations/`

### Mimo rozsahu

Zakladanie nových kariet, skupiny kariet.

### Závislosti a podklady

- Závisí od: [F05]
- Návrh: `docs/frontend/DLD-frontend.md` kap. 5, 10
- Návrh obrazovky: `docs/frontend/ui/Karta.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
