# [AI] F19 Nová rezervácia (formulár, aj z plánu)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepčná a vedúca recepcie zakladajú novú rezervačnú kartu: vyhľadajú existujúceho klienta alebo zadajú nového, vyplnia pobyt a voliteľne hneď pridelia lôžko. Z plánu sa formulár otvorí s vybraným lôžkom a dátumom.

### Akceptačné kritériá

- [ ] Tlačidlo „+ Nová rezervácia“ v hlavičke plánu a v zozname kariet (len s `RESERVATION_EDIT`, len online)
- [ ] Z plánu: na voľnom lôžku a dni v menu (pravé tlačidlo) „Nová rezervácia sem…“ otvorí formulár s predvyplneným lôžkom a dátumom príchodu
- [ ] Formulár podľa návrhu: 1. Hostia (vyhľadanie cez `GET /api/clients`, pridanie nového klienta, hlavný hosť), 2. Pobyt (príchod, odchod, počet nocí sa dopočíta, kategória, dospelí, deti, platiteľ, lekár, príznaky), 3. Lôžko (z plánu / vybrať iné voľné / zatiaľ neprideľovať), 4. Poznámka
- [ ] Súhrn vpravo s kontrolou voľných lôžok kategórie v termíne (`GET /api/rooms/available`)
- [ ] Uloženie: „Uložiť a prideliť lôžko“ → `POST /api/reservations/with-stay`, „Uložiť len kartu“ → `POST /api/reservations`; potom otvorí detail novej karty (Späť vedie tam, odkiaľ sa formulár otvoril)
- [ ] Validácia (react-hook-form + zod) a chyby z `fieldViolations`; upozornenia na potvrdenie cez dialóg
- [ ] Odchod z rozpísaného formulára sa pýta na potvrdenie
- [ ] Testy (MSW): založenie s existujúcim klientom, s novým klientom, z plánu s lôžkom, obsadené lôžko → chyba a karta sa nezaloží, validácia dátumov

### Dotknuté súbory / moduly

`frontend/src/features/reservations/` (nový formulár), `frontend/src/features/plan/` (vstup z plánu)

### Mimo rozsahu

Úprava hostí existujúcej karty, skupinové karty.

### Závislosti a podklady

- Závisí od: B11, F18, F21
- Návrh: `docs/HLD-ubytovanie.md` UC1–UC4
- Návrh obrazovky: `docs/frontend/ui/NovaRezervacia.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
