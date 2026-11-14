# [AI] P11 Zmeny ubytovania: sťahovanie, dĺžka, dávka presunov, výmena

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Umožniť recepčnej presťahovať celý pobyt alebo jeho časť, zmeniť dĺžku pobytu, poslať naraz dávku presunov z odkladiska a vymeniť dvoch klientov (UC7–UC11).

### Akceptačné kritériá

- [ ] `POST /api/stays/{id}/move` – telo `{ targetBedId, fromDate?, confirm: [] }`:
  - bez `fromDate` sa presunie celý pobyt (UC8)
  - s `fromDate` sa pobyt rozdelí na `[from, fromDate)` na pôvodnom lôžku a nový pobyt `[fromDate, to)` na cieľovom lôžku (UC9); `fromDate` musí byť vo vnútri pobytu
  - `price_category_id` nového lôžka podľa režimu `FOLLOW_ROOM`
- [ ] `PATCH /api/stays/{id}` – `{ to?, locked?, version }`: mení sa len koniec (UC10); predĺženie posunie `departure_date` karty, skrátenie ho nemení; `locked` zapína/vypína zámok „nepresúvať“ (UC19)
- [ ] `POST /api/stays/moves:batch` – zoznam `{ stayId, targetBedId, fromDate? }`; overí sa **konečný stav celej dávky** a zapíše sa v jednej transakcii s jedným audit záznamom, alebo nič
- [ ] `POST /api/stays/swap` – `{ stayIdA, stayIdB }`; výmena lôžok dvoch pobytov (UC11); obe lôžka musia byť voľné počas celého obdobia druhého pobytu
- [ ] Všetky operácie: oprávnenie `STAY_MOVE` (a `STAY_MOVE_LOCKED` pre zamknuté), kontrola `OccupancyGuard` s obchodným dňom (pri presune medzi recepciami neskorší z dní), kontrola `version` (409 `STALE_DATA`), operácie `STAY_MOVE`, `STAY_RESIZE`, `STAY_MOVE_BATCH`, `STAY_SWAP` v audite
- [ ] Upozornenia na potvrdenie: `RELEASES_HELD_BED` (presun na držané lôžko – po potvrdení sa karte s výhradným užívaním príznak zruší, zmena ide do toho istého auditu), `STAY_LOCKED`, `PARTY_MEMBER_MOVED` (presúva sa len jeden člen malej skupiny)
- [ ] Odpoveď vráti aktualizované pásiky dotknutých izieb (rovnaký tvar ako v pláne ubytovania)
- [ ] Testy: presun celý / od dňa / spätne, zmena dĺžky (predĺženie posunie odchod karty, skrátenie nie), výmena dvoch klientov v plných izbách, dávka, kde jednotlivé kroky kolidujú, ale konečný stav je platný, dávka s jedným neplatným presunom nezapíše nič, presun na držané lôžko s potvrdením, súbeh s inou recepčnou (409)

### Dotknuté súbory / moduly

Modul `accommodation`

### Mimo rozsahu

Zmena kategórie pre cenu (UC12–UC14), hromadné ubytovanie, turnusy, upozornenie na nadväzujúce karty.

### Závislosti a podklady

- Závisí od: [P10]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 5.3, 5.4; use casy UC7–UC11, UC19
