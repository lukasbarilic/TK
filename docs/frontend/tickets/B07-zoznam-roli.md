# [AI] B07 Zoznam rolí a oprávnení pre administráciu

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Administrátor pri priraďovaní rolí používateľovi potrebuje vidieť dostupné roly, ich oprávnenia a strediská/recepcie, na ktoré sa dajú obmedziť.

### Akceptačné kritériá

- [ ] `GET /api/admin/roles` (oprávnenie `USER_ADMIN`) vráti roly s kódom, názvom, popisom, príznakom `systemRole` a zoznamom oprávnení (kód, popis, limit)
- [ ] `GET /api/admin/permissions` vráti všetky oprávnenia (kód, modul, popis, `hasLimit`)
- [ ] Endpointy nemenia dáta a neauditujú sa (neobsahujú osobné údaje)
- [ ] Testy: bez `USER_ADMIN` → 403; zoznam obsahuje predvolené roly zo seed migrácie

### Dotknuté súbory / moduly

Modul `iam`

### Mimo rozsahu

Zakladanie a úprava rolí (`POST/PATCH /admin/roles`, `PUT /admin/roles/{id}/permissions`).

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/HLD-ubytovanie.md` kap. 8
