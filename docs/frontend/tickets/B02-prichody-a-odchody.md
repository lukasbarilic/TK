# [AI] B02 Príchody a odchody recepcie

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepcia potrebuje zoznam hostí, ktorí v daný deň prichádzajú a odchádzajú, aj počas výpadku spojenia. Endpoint dodá dáta, ktoré si desktopová aplikácia uloží offline.

### Akceptačné kritériá

- [ ] `GET /api/receptions/{id}/movements?date=YYYY-MM-DD` (oprávnenie `PLAN_VIEW`, rozsah používateľa na recepciu) vracia `{ receptionId, date, arrivals: [...], departures: [...] }`
- [ ] Riadok obsahuje: `reservationId`, číslo karty, skrátené meno „Priezvisko M.“ a rok narodenia, počet hostí, izbu a lôžko (ak je ubytovaný), dátumy príchodu a odchodu, stav karty, platiteľa
- [ ] Príchody = karty v budovách recepcie s dátumom príchodu `date` (stav `RESERVED` alebo `CHECKED_IN`); odchody = pobyty končiace `date`; rozhoduje budova izby, pri neubytovanej karte budova podľa kategórie/recepcie karty
- [ ] Bez `date` sa použije obchodný deň recepcie (`BusinessDateProvider`)
- [ ] `@AuditedRead` s rozsahom `NAME` a ID všetkých klientov
- [ ] ETag a `304` cez mechanizmus z B01
- [ ] Testy nad demo dátami: správne rozdelenie na príchody a odchody; používateľ inej recepcie dostane 403; `304` pri nezmenených dátach

### Dotknuté súbory / moduly

Modul `accommodation` alebo `reservation` (read model), `businessday`

### Mimo rozsahu

Zmena stavu karty (check-in / check-out) – otvorená otázka HLD-ubytovanie 12.2 č. 11.

### Závislosti a podklady

- Závisí od: [B01]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 3.2
