# [AI] F15 Denník zobrazení z lokálnej kópie a jeho odoslanie

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Každé zobrazenie osobných údajov z trezoru (offline alebo pri `304`) sa zaznamená a po obnovení spojenia odošle do auditu, aby GDPR prehľad prístupov ku klientovi bol úplný.

### Akceptačné kritériá

- [ ] Záznam denníka podľa DLD kap. 7 pri zobrazení plánu, karty, príchodov a odchodov a zoznamu hostí z trezoru; export zoznamu offline ako `GUEST_LOCATION_EXPORT`
- [ ] Rovnaký pohľad s rovnakými klientmi sa do 5 minút nezapisuje znova
- [ ] Záznamy v trezore (`journal`), odoslanie po prechode do `online` a potom každú minútu v dávkach max. 500 na `POST /api/audit/offline-reads`; po `2xx` sa zmažú
- [ ] Pred „Odhlásiť a vymazať údaje“ sa denník odošle, ak je spojenie
- [ ] Testy: zápis pri offline zobrazení, potlačenie duplicít, odoslanie po reconnecte, opakované odoslanie po chybe servera, dávkovanie

### Dotknuté súbory / moduly

`frontend/src/shared/offline/`, `frontend/src/features/offline/`

### Mimo rozsahu

Podpisovanie záznamov.

### Závislosti a podklady

- Závisí od: [F06], [F09], [F10], [F12], [B04]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 4; `docs/frontend/DLD-frontend.md` kap. 7
