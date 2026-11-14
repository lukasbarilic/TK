# [AI] P08 Doména: interval pobytu a kontrola obsadenosti

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Naprogramovať jadro pravidiel ubytovania ako čistú doménu bez Springu a JPA, dôkladne pokrytú unit testami. Na ňom budú stáť všetky zápisy pobytov (P09, P11).

### Akceptačné kritériá

- [ ] Hodnotový objekt `StayPeriod` (`from`, `to`, polootvorený interval `[from, to)`): `nights()`, `overlaps()`, `contains(date)`, `splitAt(date)`, `withEnd(date)`; neplatný interval (`from ≥ to`) nejde vytvoriť
- [ ] Doménová služba `OccupancyGuard` overí navrhovaný stav (zoznam zmien pobytov) voči obsadenosti a vráti výsledok so zoznamom **chýb** a **upozornení na potvrdenie** podľa HLD kap. 5.2:
  1. lôžko a izba sú aktívne, izba `bookable`, pri lôžku `EXTRA` vyhovuje vek hosťa → chyba `BED_UNAVAILABLE`, `EXTRA_BED_AGE`
  2. kolízia na lôžku → `BED_OCCUPIED`
  3. kolízia s blokáciou → `ROOM_BLOCKED`; s pobytom karty s výhradným užívaním izby (**držané lôžko**) → upozornenie `RELEASES_HELD_BED`
  4. karta s výhradným užívaním do neprázdnej izby → `ROOM_NOT_EMPTY`
  5. začiatok zmeny < obchodný deň izby → `PAST_CHANGE` (ak nemá oprávnenie na spätné zmeny)
  6. deň sťahovania mimo `[arrival_date, departure_date)` karty → `OUTSIDE_RESERVATION`
  7. pobyt `locked` alebo karta `do_not_move` → upozornenie `STAY_LOCKED` (bez oprávnenia `STAY_MOVE_LOCKED` chyba)
  8. dĺžka nad limit oprávnenia `STAY_CREATE` → `MAX_NIGHTS_EXCEEDED`
  9. iná kategória izby než karty bez oprávnenia `STAY_OTHER_CATEGORY` → `CATEGORY_MISMATCH`
- [ ] Vstupy sú doménové záznamy (izba, lôžka, existujúce pobyty a blokácie v intervale, karta, obchodný deň, oprávnenia a limity používateľa); služba nič nenačítava sama
- [ ] Overuje sa **konečný stav celej dávky** zmien, nie každý krok zvlášť (výmena dvoch klientov musí prejsť)
- [ ] Služba `HeldBedCalculator`: pre izbu a interval vráti lôžka držané kartami s výhradným užívaním (pre plán ubytovania)
- [ ] Unit testy pre každé pravidlo (úspech aj porušenie), hraničné dni (odchod a príchod v ten istý deň nie je kolízia), výmenu dvoch pobytov, rozdelenie pobytu, prístelku; pokrytie domény ≥ 90 %

### Dotknuté súbory / moduly

`accommodation/domain` (bez závislostí na Spring a JPA)

### Mimo rozsahu

Perzistencia, REST API, kategória pre cenu, turnusy kontingentov.

### Závislosti a podklady

- Závisí od: [P07]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 3 (držané lôžko), 5.1–5.4
