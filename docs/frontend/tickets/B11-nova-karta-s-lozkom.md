# [AI] B11 Nová rezervačná karta s vyhľadaním klienta a pridelením lôžka

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepčná a vedúca recepcie musia vedieť založiť novú rezerváciu, aj priamo z plánu na vybrané lôžko. Backend vie kartu založiť, chýba vyhľadanie klienta, lekár a príznaky pri založení a založenie s pridelením lôžka v jednom kroku.

### Akceptačné kritériá

- [ ] `GET /api/clients?q=&limit=10` (oprávnenie `RESERVATION_EDIT`): vyhľadanie podľa mena a priezviska bez diakritiky, vracia ID, meno, rok narodenia a dátum posledného pobytu; `@AuditedRead` s rozsahom `NAME`
- [ ] `POST /api/reservations` doplniť o voliteľné `attendingDoctorId`, `earlyCheckIn`, `lateCheckOut`, `doNotMove`, `note`, `noteInPlan` (B10)
- [ ] Nový `POST /api/reservations/with-stay`: telo = `CreateRequest` + `bedId` (a voliteľne `extraBed`); v jednej transakcii založí kartu a pridelí lôžko na celé obdobie podľa pravidiel z P09 (vrátane upozornení na potvrdenie a `confirm`); pri chybe pridelenia sa karta nezaloží
- [ ] Obe operácie vyžadujú `RESERVATION_EDIT`; `with-stay` navyše `STAY_CREATE` s limitom nocí
- [ ] Overiť seed: roly recepčná a vedúca recepcie majú `RESERVATION_EDIT` (podľa V4 áno) – doplniť test, ktorý to stráži
- [ ] Audit zápisu, `reception_change_seq` a SSE `plan-changed` pri `with-stay`
- [ ] Testy: vyhľadanie s diakritikou; založenie s novým aj existujúcim klientom; `with-stay` úspech, obsadené lôžko → 409 a karta nevznikne; recepčná bez `STAY_CREATE` limitu

### Dotknuté súbory / moduly

Modul `reservation`, `accommodation`

### Mimo rozsahu

Duplicitné klienty (zlúčenie), skupinové karty, import z online rezervácií.

### Závislosti a podklady

- Závisí od: B10
- Návrh: `docs/HLD-ubytovanie.md` UC1–UC4 (ubytovanie s novou kartou)
- Návrh obrazovky: `docs/frontend/ui/NovaRezervacia.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
