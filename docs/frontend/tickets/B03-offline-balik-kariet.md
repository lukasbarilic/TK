# [AI] B03 Hromadný detail rezervačných kariet pre offline režim

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Desktopová aplikácia drží offline detail kariet, ktoré sú v okne plánu. Namiesto stoviek volaní `GET /api/reservations/{id}` ich stiahne jedným volaním, a po živej zmene len karty na dotknutých izbách.

### Akceptačné kritériá

- [ ] `GET /api/offline/reservations?receptionId&from&to` (oprávnenie `RESERVATION_VIEW`) vráti detail všetkých kariet, ktoré majú v budovách recepcie pobyt v intervale `[from, to)` alebo príchod v tomto intervale
- [ ] `GET /api/offline/reservations?ids=1,2,3` vráti detail vybraných kariet (max. 200 ID), len tých, na ktoré má používateľ rozsah
- [ ] Tvar položky je rovnaký ako `GET /api/reservations/{id}` (karta, hostia, kontaktné údaje, pobyty, `version`) – žiadna nová štruktúra DTO
- [ ] Načítanie bez N+1 (test počtu SQL dotazov)
- [ ] `@AuditedRead` s rozsahom `CONTACT` a ID všetkých klientov v odpovedi
- [ ] Variant `receptionId&from&to` podporuje ETag a `304` (B01)
- [ ] Testy: výber podľa intervalu (pobyt cez hranicu intervalu je zahrnutý), rozsah na recepciu, limit 200 ID, audit čítania, `304`

### Dotknuté súbory / moduly

Modul `reservation` (nový controller `OfflineReservationController`), `accommodation` (výber kariet)

### Mimo rozsahu

Delta synchronizácia („zmeny od“), stránkovanie.

### Závislosti a podklady

- Závisí od: [B01]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 3.2, `docs/frontend/DLD-frontend.md` kap. 5
