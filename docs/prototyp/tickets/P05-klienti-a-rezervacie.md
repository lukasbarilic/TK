# [AI] P05 Klienti a rezervačné karty

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Zaviesť rezervačné karty (`reservation`) s hosťami a klientmi, aby bolo koho ubytovať. Recepčná vie kartu vyhľadať, zobraziť, založiť a upraviť.

### Akceptačné kritériá

- [ ] Tabuľky podľa HLD kap. 4.2: `client`, `reservation`, `reservation_guest`, `reservation_group`, `reservation_party` (malá skupina – partneri v jednej izbe; `reservation.party_id`); stav karty `RESERVED | CHECKED_IN | CHECKED_OUT | CANCELLED`, `price_category_mode` (predvolene `FOLLOW_ROOM`), `price_type` (predvolene `PER_BED`)
- [ ] Kontroly v DB aj v kóde: `arrival_date < departure_date`, počet nocí ≤ 365, `adults ≥ 1`
- [ ] Endpointy (oprávnenie `RESERVATION_VIEW` na čítanie, `RESERVATION_EDIT` na zápis):
  - `GET /api/reservations?q&status&from&to&receptionId` – vyhľadávanie podľa mena hosťa alebo čísla karty (`pg_trgm`), stránkovanie
  - `GET /api/reservations/{id}` – karta s hosťami
  - `POST /api/reservations` – založenie karty s novým alebo existujúcim klientom, dátumami, objednanou kategóriou, platiteľom, počtom osôb, `exclusive_room_use`; číslo karty sa vygeneruje
  - `PATCH /api/reservations/{id}` – zmena dátumov, kategórie, platiteľa, príznakov (`do_not_move`, `early_check_in`, `late_check_out`, `exclusive_room_use`); s kontrolou `version` (409 pri konflikte)
- [ ] Všetky zápisy cez `@AuditedOperation` (`RESERVATION_CREATE`, `RESERVATION_UPDATE`)
- [ ] `DemoDataLoader` načíta `clients`, `reservation_groups`, `reservations` (vytvorí `reservation_party` z hodnôt `party_id`), `reservation_guests` z `docs/demo-data/`
- [ ] DTO pre zoznam obsahuje len meno, rok narodenia, číslo karty, dátumy, stav a kategóriu; kontaktné údaje len v detaile
- [ ] Testovacie fixtures používajú len vymyslené osoby (napr. „Adam Testovský“, narodený 1970-01-01); žiadne skutočné mená, rodné čísla, doklady ani adresy
- [ ] Testy: validácie, vyhľadávanie s diakritikou aj bez nej, konflikt verzie, audit pri založení a zmene, 403 bez oprávnenia

### Dotknuté súbory / moduly

Modul `reservation`, migrácia `V5__reservation.sql`, demo loader, seed oprávnení (ak chýbajú)

### Mimo rozsahu

Pobyty a ubytovanie (P09), check-in/check-out ako samostatné operácie, storno s uvoľnením pobytov, lekár na karte, skupinové operácie. Audit čítania osobných údajov doplní P06.

### Závislosti a podklady

- Závisí od: [P04]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 4.2
- Dáta: `docs/demo-data/`
