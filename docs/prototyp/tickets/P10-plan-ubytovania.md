# [AI] P10 Plán ubytovania

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Poskytnúť frontendu dáta pre grafický plán ubytovania: izby, lôžka a pásiky pobytov, držaných lôžok a blokácií v zvolenom období (UC15, UC16, UC18).

### Akceptačné kritériá

- [ ] `GET /api/accommodation-plan?from&to&buildingIds&roomIds&receptionId&freeOnly&fullyFree` (oprávnenie `PLAN_VIEW`, výsledok obmedzený na budovy, ku ktorým má používateľ rozsah) vracia štruktúru podľa HLD kap. 6.2:
  - izby (budova, číslo, poschodie, kategória) s lôžkami (`REGULAR`, `EXTRA`)
  - pásiky `STAY` (karta, lôžko, interval, skrátené meno „Priezvisko M.“, stav karty, `locked`, príznaky `EARLY_CHECK_IN` / `LATE_CHECK_OUT`, `partyId`)
  - pásiky `HELD_BED` dopočítané cez `HeldBedCalculator` (v databáze nie sú)
  - pásiky `ROOM_BLOCK` (dôvod, poznámka)
  - pre každú recepciu jej aktuálny obchodný deň
- [ ] `freeOnly=true` vráti len izby s aspoň jedným voľným lôžkom v celom období; `fullyFree=true` len úplne prázdne izby (UC18)
- [ ] `roomIds` umožní načítať len vybrané izby (pre živé aktualizácie v P12)
- [ ] Dáta sa načítajú **štyrmi dotazmi** (izby s lôžkami, pobyty, blokácie, karty s výhradným užívaním) a poskladajú v pamäti; žiadne N+1 (overené testom počtu SQL dotazov)
- [ ] `@AuditedRead` s rozsahom `NAME` a ID všetkých klientov na pásikoch
- [ ] Výkonnostný test nad demo dátami: celý plán (56 izieb × 31 dní) do 300 ms na vývojovom počítači (test so značkou `performance`, nespúšťa sa v CI)
- [ ] Testy: pásiky pre pobyt cez hranicu obdobia sú orezané na obdobie, ale nesú skutočné dátumy; držané lôžka sa zobrazia len pre karty s výhradným užívaním; filter `freeOnly` a `fullyFree`; rozsah používateľa na recepciu

### Dotknuté súbory / moduly

Modul `accommodation` (read model `AccommodationPlan`), `blocking` (čítanie)

### Mimo rozsahu

Turnusy kontingentov v pláne, filtre izieb podľa vlastností (UC17), farby pásikov (nastavenia používateľa).

### Závislosti a podklady

- Závisí od: [P09]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 6.2; use casy UC15, UC16, UC18
