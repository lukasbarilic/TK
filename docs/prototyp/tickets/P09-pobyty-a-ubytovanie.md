# [AI] P09 Pobyty a ubytovanie klienta

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Umožniť recepčnej ubytovať klienta z rezervačnej karty na konkrétne lôžko alebo prístelku (UC1, UC2, UC4) a zobraziť ubytovanie karty. Pokrýva aj voľné izby pre kartu.

### Akceptačné kritériá

- [ ] Tabuľka `stay` podľa HLD kap. 4.2 s `version`; obmedzenie `EXCLUDE USING gist (bed_id WITH =, daterange(date_from, date_to, '[)') WITH &&)` a `date_from < date_to`; porušenie `EXCLUDE` sa preloží na `BED_OCCUPIED` (409)
- [ ] `DemoDataLoader` načíta `stays.csv` (prevod `room_id` + `bed_number` + `bed_type` na `bed_id`)
- [ ] `GET /api/reservations/{id}/stays` – „Zobraziť ubytovanie“ (budova, izba, lôžko, typ lôžka, interval, kategória pre cenu, `locked`) – `@AuditedRead` so `NAME`
- [ ] `GET /api/rooms/available?from&to&categoryId&allCategories&receptionId&extraBeds` – izby s aspoň jedným voľným lôžkom v celom intervale (UC1; `allCategories=true` ukáže aj iné kategórie)
- [ ] `POST /api/reservations/{id}/stays` – telo `{ bedId, from, to, adjustReservationDates, confirm: [] }`; oprávnenie `STAY_CREATE`; operácia `STAY_CREATE` (`@AuditedOperation`, UC1/UC2/UC4)
  - kontrola cez `OccupancyGuard` (P08) s obchodným dňom izby (P07), limitom nocí a rozsahom roly na recepciu
  - ak sa interval líši od dátumov karty a `adjustReservationDates = true`, upraví príchod/odchod karty; inak 422 `OUTSIDE_RESERVATION`
  - `price_category_id` = kategória izby (režim `FOLLOW_ROOM`; ostatné režimy neskôr)
  - kontrola obsadenosti sa zopakuje v tej istej transakcii tesne pred zápisom
- [ ] `DELETE /api/stays/{id}` – zruší budúci pobyt (začiatok ≥ obchodný deň), operácia `STAY_DELETE`
- [ ] Chyby ako `ProblemDetail` s `code`; upozornenia na potvrdenie ako 409 s `"confirmable": true` a `warnings`, opakovanie s `confirm` prejde (HLD kap. 6.3)
- [ ] Pravidlo uzávierky `CHECKED_IN_WITHOUT_STAY` (predvolene `BLOCKING`) – karta `CHECKED_IN` bez pobytu v obchodný deň
- [ ] Testy: ubytovanie na voľné lôžko, na prístelku, kolízia, ubytovanie spätne (bez/s oprávnením), karta s výhradným užívaním do obsadenej izby, upozornenie na držané lôžko a jeho potvrdenie, prekročenie limitu nocí, súbeh dvoch požiadaviek na to isté lôžko (jedna 201, druhá 409), audit zápisu

### Dotknuté súbory / moduly

Modul `accommodation` (`api`, `application`, `infrastructure`), migrácia `V8__stay.sql`, demo loader, `businessday` (nové pravidlo)

### Mimo rozsahu

Sťahovanie a zmena dĺžky (P11), plán ubytovania (P10), hromadné ubytovanie, režimy kategórie pre cenu, pridelenie lekára, check-in.

### Závislosti a podklady

- Závisí od: [P06], [P08]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 5.2, 6.1, 6.3; use casy UC1, UC2, UC4
