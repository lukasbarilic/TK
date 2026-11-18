# [AI] F13 Administrácia používateľov

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Administrátor zakladá a upravuje používateľov, priraďuje im roly s rozsahom na stredisko alebo recepciu, resetuje heslá a odomyká účty.

### Akceptačné kritériá

- [ ] Zoznam `GET /api/admin/users` s filtrom podľa mena a stavu
- [ ] Založenie (`POST`) a úprava (`PATCH`, vrátane deaktivácie) s validáciou z `fieldViolations`
- [ ] Priradenie rolí `PUT /api/admin/users/{id}/roles`: rola (z B07), stredisko, recepcia, platnosť od–do
- [ ] Reset hesla a odomknutie; jednorazové heslo sa zobrazí raz s tlačidlom Kopírovať a upozornením, že sa už nezobrazí
- [ ] Len s `USER_ADMIN`, len online
- [ ] Testy (MSW): založenie, chyba validácie, priradenie roly s rozsahom, reset hesla zobrazí heslo raz

### Dotknuté súbory / moduly

`frontend/src/features/admin/`

### Mimo rozsahu

Správa rolí a oprávnení, číselníky, systémové nastavenia.

### Závislosti a podklady

- Závisí od: [F03], [B07]
- Návrh: `docs/HLD-ubytovanie.md` kap. 8
- Návrh obrazovky: `docs/frontend/ui/Pouzivatelia.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
