# [AI] B05 Zoznam hostí s miestom ubytovania pre lekára (JSON, CSV)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Lekár potrebuje zoznam svojich pacientov a ich izieb k danému dňu; recepcia a vedúca recepcie aj pre všetkých hostí. Je to tiket 14 z HLD-ubytovanie bez PDF.

### Akceptačné kritériá

- [ ] `GET /api/reports/guest-locations?date&centreId&buildingId&doctorId&format=json|csv` podľa HLD-ubytovanie kap. 8.5 (stĺpce, pravidlá výberu, zmena izby počas dňa)
- [ ] Oprávnenia: `GUEST_LOCATION_VIEW` (lekár vidí len karty s `attending_doctor_id` = on), `GUEST_LOCATION_VIEW_ALL`, `GUEST_LOCATION_EXPORT` pre CSV; doplniť kódy do `Permissions` a seed predvolených rolí novou migráciou
- [ ] Bez `date` sa použije obchodný deň hlavnej recepcie používateľa
- [ ] JSON aj CSV `@AuditedRead` (`GUEST_LOCATION_VIEW` resp. `GUEST_LOCATION_EXPORT`, rozsah `NAME`)
- [ ] CSV v UTF-8 s BOM a oddeľovačom `;` (otvorí sa správne v Exceli)
- [ ] JSON podporuje ETag a `304` (B01)
- [ ] Testy: lekár vidí len svojich pacientov; s `VIEW_ALL` všetkých; CSV bez `EXPORT` → 403; hosť so zmenou izby v daný deň má dva riadky

### Dotknuté súbory / moduly

Nový modul `report`

### Mimo rozsahu

PDF výstup, prevzatie pacienta lekárom (HLD-ubytovanie tiket 19).

### Závislosti a podklady

- Závisí od: [B01]
- Návrh: `docs/HLD-ubytovanie.md` kap. 8.5
