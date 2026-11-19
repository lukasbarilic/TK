# [AI] B09 Nočná uzávierka: stav Neprišiel a report dňa s obsadenosťou

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Výstup uzávierky bol nejasný. Uzávierka má dať report dňa: príchody a odchody v osobách, počet ľudí v dome, obsadenosť komplexu a budov a výhľad obsadenosti na 14 dní. Pri uzavretí nastaví hosťom, ktorí neprišli, stav Neprišiel a report uloží do archívu.

### Akceptačné kritériá

- [ ] `POST /api/receptions/{id}/business-day/close/preview` vracia okrem zistení aj `report`:
  - `arrivals { expectedPersons, arrivedPersons, noShowPersons }` a `departures { expectedPersons, departedPersons }` (osoby = dospelí + deti z kariet)
  - `inHouse { startOfDay, endOfDay }` (osoby so stavom `CHECKED_IN`)
  - `occupancy`: pre každú budovu recepcie a za komplex `beds` (bez prístelok), `occupied` (obsadené lôžka vrátane obsadených prístelok), `blocked`, `free`, `percent = occupied / (beds − blocked)`
  - `forecast`: 14 dní od nasledujúceho dňa, pre každý deň `date` a `percent` za komplex podľa pobytov kariet v stavoch `RESERVED` a `CHECKED_IN`
- [ ] Zistenie `NO_SHOW` (existujúce pravidlo) už neblokuje; pri `close` sa týmto kartám nastaví stav `NO_SHOW` cez službu z B08 (s auditom)
- [ ] Pri `close` sa report uloží do tabuľky `business_day_report(reception_id, business_date, report jsonb, closed_by, closed_at)` (nová migrácia)
- [ ] `GET /api/receptions/{id}/business-day/reports?from&to` (zoznam) a `GET …/reports/{date}` (detail) s oprávnením `BUSINESS_DAY_CLOSE` alebo `REPORT_VIEW` (nové, pre vedúcu recepcie)
- [ ] Report neobsahuje mená hostí okrem zoznamu zistení (tie už dnes ID kariet obsahujú) – archív nie je osobný údaj navyše
- [ ] Výpočet obsadenosti jedným dotazom na budovu (bez N+1); test výkonu nad demo dátami do 500 ms
- [ ] Testy: počty osôb pre príchody, odchody a neprišlých; obsadenosť s blokáciou a prístelkou; výhľad 14 dní; uloženie a načítanie archívu; po `close` majú neprišlí stav `NO_SHOW`

### Dotknuté súbory / moduly

Modul `businessday`, `report`, `accommodation` (dotazy obsadenosti), migrácia

### Mimo rozsahu

PDF report zo servera, štatistiky za obdobie (mesiac, rok), export do účtovníctva.

### Závislosti a podklady

- Závisí od: B08
- Návrh: `docs/HLD-ubytovanie.md` kap. 5.8
- Návrh obrazovky: `docs/frontend/ui/UzavierkaReport.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
