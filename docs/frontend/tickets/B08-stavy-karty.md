# [AI] B08 Stavy rezervačnej karty: príchod, odchod, storno, neprišiel

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Pri teste sa stav karty po pridelení lôžka nemenil, lebo backend nemá operácie, ktoré stav menia. Doplniť stavový model podľa návrhu: Rezervovaná → Prítomný (príchod) → Odhlásený (odchod), z Rezervovanej Stornovaná alebo Neprišiel, a opravy (zrušenie príchodu v ten istý deň, neskorý príchod).

### Akceptačné kritériá

- [ ] Nový stav `NO_SHOW` v `ReservationStatus` a v CHECK obmedzení tabuľky `reservation` (nová Flyway migrácia); stĺpce `checked_in_at`, `checked_out_at`, `cancelled_at`, `cancel_reason` (max. 500 znakov)
- [ ] `POST /api/reservations/{id}/check-in`: len zo stavu `RESERVED`, karta musí mať pridelené lôžko pre všetkých hostí (inak 409 `NO_BED_ASSIGNED`), dátum príchodu ≤ obchodný deň recepcie (skorší príchod → 409 `ARRIVAL_IN_FUTURE`), výsledok `CHECKED_IN` + `checked_in_at`
- [ ] `POST /api/reservations/{id}/check-out`: len z `CHECKED_IN`; ak je obchodný deň skôr ako dátum odchodu, pobyty sa skrátia k obchodnému dňu (skorší odchod, upozornenie na potvrdenie `EARLY_DEPARTURE` podľa HLD-ubytovanie 6.3); výsledok `CHECKED_OUT`
- [ ] `POST /api/reservations/{id}/cancel` s povinným `reason`: len z `RESERVED`; zmaže budúce pobyty (uvoľní lôžka); výsledok `CANCELLED`
- [ ] `POST /api/reservations/{id}/no-show`: len z `RESERVED` a len ak je dátum príchodu ≤ obchodný deň; uvoľní pobyty od obchodného dňa; výsledok `NO_SHOW`
- [ ] Opravy: `POST …/undo-check-in` (z `CHECKED_IN` späť na `RESERVED`, len v obchodný deň príchodu) a `POST …/revert-no-show` (z `NO_SHOW` na `RESERVED`, lôžko sa nevracia automaticky); obe len s novým oprávnením `RESERVATION_STATUS_FIX`
- [ ] Nové oprávnenie `RESERVATION_STATUS` (príchod, odchod, storno, neprišiel) pre roly recepčná a vedúca recepcie; `RESERVATION_STATUS_FIX` len vedúca recepcie (seed migrácia)
- [ ] Všetky operácie: optimistic locking (`version` v tele), audit zápisu s pôvodným a novým stavom, zvýšenie `reception_change_seq` a SSE `plan-changed` pre dotknuté izby
- [ ] Pridelenie lôžka (`POST /reservations/{id}/stays`) stav karty nemení (overené testom)
- [ ] Odpoveď operácií vracia aktualizovaný detail karty
- [ ] Testy: každý povolený prechod; zakázané prechody → 409 `INVALID_STATUS_TRANSITION`; príchod bez lôžka; skorý odchod skráti pobyt; storno uvoľní lôžka; opravy bez oprávnenia → 403; audit a SSE udalosť

### Dotknuté súbory / moduly

Modul `reservation` (doména, služba, controller), `accommodation` (úprava pobytov), `iam` (oprávnenia), migrácia

### Mimo rozsahu

Hromadný príchod/odchod skupiny, poplatky za storno, check-in cez online systém.

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/frontend/ui/Stavy.dc.html` (stavový diagram a pravidlá); `docs/HLD-ubytovanie.md` kap. 6.3 (upozornenia na potvrdenie)
