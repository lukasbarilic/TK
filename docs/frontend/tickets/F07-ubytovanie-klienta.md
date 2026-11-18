# [AI] F07 Ubytovanie klienta z karty a z plánu

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepčná ubytuje klienta na lôžko alebo prístelku buď z rezervačnej karty (výber voľnej izby), alebo priamo kliknutím do plánu (UC1–UC4).

### Akceptačné kritériá

- [ ] Z detailu karty: „Ubytovať“ → `GET /api/rooms/available` (filter kategórie, „všetky kategórie“ len s `STAY_OTHER_CATEGORY`) → výber lôžka → `POST /api/reservations/{id}/stays`
- [ ] Z plánu: klik na voľné lôžko a deň → dialóg s vyhľadaním karty v stave `RESERVED` bez pobytu → potvrdenie
- [ ] Limit nocí z oprávnenia `STAY_CREATE` sa zobrazí a UI nepustí dlhší pobyt (rozhoduje server)
- [ ] Upozornenia na potvrdenie (409 `confirmable`) cez `<ConfirmWarningsDialog>` so slovenskými textami kódov; chyby podľa DLD kap. 8
- [ ] Po úspechu sa dotknuté izby v pláne a v trezore aktualizujú z odpovede
- [ ] Akcie sú len cez `<WriteAction>` (offline neaktívne)
- [ ] Testy (MSW): úspešné ubytovanie z karty aj z plánu, potvrdenie upozornenia a opakované odoslanie s `confirm`, chyba `BED_OCCUPIED`, offline neaktívne tlačidlo

### Dotknuté súbory / moduly

`frontend/src/features/stays/`, `frontend/src/features/plan/`, `frontend/src/features/reservations/`

### Mimo rozsahu

Ubytovanie s novou kartou (`POST /stays/with-new-reservation`), hromadné ubytovanie.

### Závislosti a podklady

- Závisí od: [F06], [F09]
- Návrh: `docs/frontend/DLD-frontend.md` kap. 8, 9.3; `docs/HLD-ubytovanie.md` UC1–UC4
- Návrh obrazovky: `docs/frontend/ui/Ubytovat.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
