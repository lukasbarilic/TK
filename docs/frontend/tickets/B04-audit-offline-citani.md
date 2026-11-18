# [AI] B04 Audit zobrazení z lokálnej kópie (offline čítania)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Keď aplikácia zobrazí osobné údaje z lokálneho trezoru (počas výpadku alebo pri odpovedi `304`), zaznamená to do lokálneho denníka. Po obnovení spojenia ho pošle na server, aby audit čítania (HLD-ubytovanie kap. 7.4) obsahoval aj tieto zobrazenia.

### Akceptačné kritériá

- [ ] `POST /api/audit/offline-reads` (prihlásený používateľ) prijme dávku max. 500 záznamov podľa DLD kap. 7 (`uid`, `occurredAt`, `username`, `deviceId`, `operation`, `dataScope`, `clientIds`, `connection`)
- [ ] Záznam sa uloží ako audit udalosť typu `READ` s operáciou z tela, časom servera aj časom z klienta (`occurredAt`), `deviceId` a príznakom zdroja `OFFLINE` alebo `CACHED`; ID klientov do `audit_subject_access`
- [ ] Idempotencia: záznam s už prijatým `uid` sa preskočí (unikátny index nad `event_uid`), odpoveď vráti počty `accepted` a `duplicate`
- [ ] Odmietne záznamy, kde `username` nie je prihlásený používateľ (422 pre celú dávku), a `occurredAt` viac ako 1 rok v minulosti alebo v budúcnosti (> 10 min)
- [ ] Záznamy sa zobrazia v `GET /api/audit/clients/{id}/access` s označením zdroja
- [ ] Nová Flyway migrácia pre stĺpce zdroja a času klienta, ak v audite chýbajú
- [ ] Testy: uloženie a zobrazenie v prístupoch ku klientovi; opakované odoslanie nevytvorí duplicitu; cudzí `username` → 422; veľkosť dávky > 500 → 422

### Dotknuté súbory / moduly

Modul `audit`

### Mimo rozsahu

Podpis záznamov na klientovi, audit zápisov offline (zápisy offline nie sú).

### Závislosti a podklady

- Závisí od: [B01]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 4, `docs/frontend/DLD-frontend.md` kap. 7
