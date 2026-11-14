# [AI] P06 Audit čítania osobných údajov

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Zaznamenať každé čítanie osobných údajov klienta (kto, kedy, ktorých klientov a v akom rozsahu), aby bolo možné odpovedať na žiadosť podľa GDPR. Od tohto tiketu musí každý endpoint s osobnými údajmi používať `@AuditedRead`.

### Akceptačné kritériá

- [ ] Tabuľka `audit_subject_access` podľa HLD kap. 7.2 (mesačné partície, trigger proti `UPDATE`/`DELETE`), `data_scope` = `NAME | CONTACT | FULL`
- [ ] Anotácia `@AuditedRead(operation, scope)`: metóda vráti výsledok spolu so zoznamom ID klientov; aspekt **pred odoslaním odpovede** zapíše `audit_event` typu `READ` a hromadne `audit_subject_access`; ak sa audit nepodarí zapísať, odpoveď sa neodošle (500)
- [ ] Použité na existujúcich endpointoch: `GET /api/reservations` (`NAME`), `GET /api/reservations/{id}` (`CONTACT`)
- [ ] `GET /api/audit/clients/{clientId}/access?from&to` (oprávnenie `AUDIT_VIEW`) vráti, kto a kedy čítal údaje klienta
- [ ] Čítania bez osobných údajov (katalóg, blokácie) sa neauditujú
- [ ] Testy: zobrazenie zoznamu zapíše jeden `READ` záznam so všetkými ID klientov na stránke; detail zapíše `CONTACT`; zlyhanie zápisu auditu zabráni odoslaniu dát; prehľad prístupov ku klientovi vráti správne záznamy

### Dotknuté súbory / moduly

Modul `audit`, `reservation` (anotácie na čítacích metódach), migrácia `V6__audit_read.sql`

### Mimo rozsahu

Exporty (CSV/PDF), uchovávanie a archivácia.

### Závislosti a podklady

- Závisí od: [P05]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 7.4
