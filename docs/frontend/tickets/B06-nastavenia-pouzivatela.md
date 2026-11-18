# [AI] B06 Nastavenia používateľa (farby a predvolený pohľad plánu)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Každý používateľ si v pláne ubytovania nastaví farby a predvolený pohľad a nastavenie ho nasleduje na každom počítači. Je to tiket 15 z HLD-ubytovanie.

### Akceptačné kritériá

- [ ] Tabuľka `user_preference(user_id, key, value jsonb, version)` (nová migrácia)
- [ ] `GET /api/me/preferences` vráti všetky nastavenia používateľa; `GET /api/me/preferences/{key}` jedno (404 ak neexistuje)
- [ ] `PUT /api/me/preferences/{key}` uloží hodnotu; povolené kľúče `plan.colors`, `plan.defaultView`, `plan.showExtraBeds`, `plan.showHolidays`, `reception.default`; iný kľúč → 422; max. veľkosť hodnoty 16 kB
- [ ] Optimistic locking cez `version` (`If-Match` alebo pole `version`), konflikt → 409
- [ ] Zápis je auditovaný (`@AuditedOperation`)
- [ ] Testy: uloženie a načítanie, neznámy kľúč, konflikt verzie, používateľ nevidí nastavenia iného

### Dotknuté súbory / moduly

Modul `settings`

### Mimo rozsahu

Systémové nastavenia (`app_setting`), e-mailové oznámenia.

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/HLD-ubytovanie.md` kap. 9
