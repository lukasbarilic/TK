# [AI] P02 Katalóg ubytovacích kapacít a demo dáta

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Vytvoriť tabuľky a čítacie API pre ubytovacie kapacity (strediská, recepcie, budovy, izby, lôžka, kategórie, platitelia, blokácie) a načítať do nich demo dáta, aby ďalšie tikety mali s čím pracovať.

### Akceptačné kritériá

- [ ] Flyway migrácia vytvorí tabuľky podľa HLD kap. 4.1, 4.3, 4.4 a 4.5: `centre`, `reception`, `building` (s `reception_id`), `base_category`, `room_category`, `department`, `room`, `bed` (`type` = `REGULAR | EXTRA`), `room_feature`, `room_feature_assignment`, `payer` (`type` = `SELF | INSURER | COMPANY`), `block_reason`, `room_block`
- [ ] Názvy tabuliek a stĺpcov sú anglické, `snake_case`, presne podľa HLD; zapisované tabuľky majú stĺpce z `BaseEntity`; `created_by` / `updated_by` sú zatiaľ `BIGINT NULL` bez cudzieho kľúča (doplní P03)
- [ ] `room_block` má obmedzenie `EXCLUDE USING gist (room_id WITH =, daterange(date_from, date_to, '[)') WITH &&)` a kontrolu `date_from < date_to`
- [ ] `bed` má unikátnu dvojicu `(room_id, bed_number)`; počet lôžok `EXTRA` = `room.extra_beds`
- [ ] JPA entity a repozitáre v module `catalog` (blokácie v module `blocking`), doménové objekty bez JPA anotácií
- [ ] Čítacie endpointy (zatiaľ bez prihlásenia, zabezpečí ich P03): `GET /api/catalog/centres`, `/receptions`, `/buildings`, `/rooms?buildingId=`, `/rooms/{id}` (aj s lôžkami), `/room-categories`, `/payers`, `GET /api/room-blocks?from&to&roomId`
- [ ] `DemoDataLoader` (len profil `demo`, len ak je `centre` prázdna) načíta CSV z `backend/src/main/resources/demo/` – skopírovať sem súbory katalógu z `docs/demo-data/` (`centres`, `receptions`, `buildings`, `base_categories`, `room_categories`, `departments`, `rooms`, `payers`, `block_reasons`, `room_blocks`); lôžka vygeneruje podľa `docs/demo-data/README.md`
- [ ] Po štarte v profile `demo` vráti `GET /api/catalog/rooms` 56 izieb, každá s lôžkami vrátane prístelky
- [ ] Demo dáta sa nemenia ani nedopĺňajú o skutočné údaje; nové testovacie dáta sú len fiktívne (pozri `docs/demo-data/README.md`)
- [ ] Integračné testy: migrácia, načítanie demo dát, `EXCLUDE` na `room_block` odmietne prekrývajúce sa blokácie tej istej izby

### Dotknuté súbory / moduly

Moduly `catalog`, `blocking`; `db/migration/V2__catalog.sql` (alebo viac súborov), `backend/src/main/resources/demo/*.csv`

### Mimo rozsahu

Zápis do číselníkov cez API (správa katalógu), filtre izieb, vytváranie blokácií cez API. Audit (P04) – loader bude do auditu napojený v P04.

### Závislosti a podklady

- Závisí od: [P01]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 4.1, 4.3, 4.4, 4.5
- Dáta: `docs/demo-data/` a jeho `README.md`
