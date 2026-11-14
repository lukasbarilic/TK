# [AI] P07 Recepcie, obchodný deň a nočná uzávierka

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Zaviesť obchodný deň každej recepcie, ktorý sa mení až po nočnej uzávierke, nie o polnoci. Všetky pravidlá s dátumom („nie spätne“, voľné izby od dnes) budú používať tento deň.

### Akceptačné kritériá

- [ ] Tabuľky `business_day` (unikátne `(reception_id, business_date)`, práve jeden `OPEN` na recepciu – čiastočný unikátny index) a `closing_rule` (`code`, `enabled`, `severity` = `BLOCKING | INFO`, `reception_id NULL` = všetky) podľa HLD kap. 4.5 a 5.8
- [ ] Pri štarte sa pre recepciu bez obchodného dňa otvorí deň = dnešný dátum v `organization.timezone` (alebo `app.business-day.initial`, ak je nastavené)
- [ ] `BusinessDateProvider`: `current(receptionId)`, `currentForRoom(roomId)` (cez `building.reception_id`), `latestOf(roomIds)` pre presuny medzi recepciami; v teste nastaviteľný pevný deň; v produkčnom kóde sa nepoužíva `LocalDate.now()` (ArchUnit pravidlo pre moduly okrem `businessday`)
- [ ] Endpointy: `GET /api/receptions/{id}/business-day`, `POST /api/receptions/{id}/business-day/close/preview`, `POST /api/receptions/{id}/business-day/close` (oprávnenie `BUSINESS_DAY_CLOSE` s rozsahom na recepciu)
- [ ] Pravidlá uzávierky ako samostatné implementácie rozhrania `ClosingRule` (zisťujú len v budovách danej recepcie):
  - `DEPARTURES_NOT_CHECKED_OUT` – karty `CHECKED_IN` s `departure_date ≤ obchodný deň` (predvolene `INFO`)
  - `NO_SHOW` – karty `RESERVED` s `arrival_date ≤ obchodný deň` (predvolene `INFO`)
  - (`CHECKED_IN_WITHOUT_STAY` doplní P09)
- [ ] `preview` vráti zistenia rozdelené na blokujúce a informatívne; `close` s blokujúcimi zisteniami vráti 409, inak v jednej auditovanej transakcii (`BUSINESS_DAY_CLOSE`) uzavrie deň, uloží `closing_summary` a otvorí `business_date + 1`
- [ ] Testy: uzávierka posunie deň len svojej recepcii; vypnuté pravidlo sa nevyhodnocuje; blokujúce pravidlo zastaví uzávierku; bez oprávnenia pre danú recepciu 403

### Dotknuté súbory / moduly

Modul `businessday`, migrácia `V7__business_day.sql`, ArchUnit test

### Mimo rozsahu

Pripomienka zabudnutej uzávierky a oznámenia, denná zostava do PDF, živá udalosť `business-day-changed` (P12), správa pravidiel cez API (zatiaľ len seed v migrácii).

### Závislosti a podklady

- Závisí od: [P05]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 5.8
