# [AI] P04 Audit zápisov

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Zabezpečiť, aby každému zápisu na backende predchádzal audit záznam (kto, kedy, aká operácia, aké zmeny) a aby zápis bez auditu nebol možný. Od tohto tiketu musí každá nová zápisová operácia používať `@AuditedOperation`.

### Akceptačné kritériá

- [ ] Tabuľky `audit_event` a `audit_change` podľa HLD kap. 7.2 (`event_type` = `WRITE | READ | LOGIN`, `outcome` = `SUCCESS | REJECTED`, JSON stĺpce ako `jsonb`), **rozdelené na mesačné partície** podľa `occurred_at`; pri štarte sa vytvoria partície pre aktuálny a nasledujúce 2 mesiace
- [ ] Trigger na oboch tabuľkách odmietne `UPDATE` a `DELETE`
- [ ] Anotácia `@AuditedOperation(operation, useCase)` na metódach aplikačných služieb: aspekt otvorí transakciu a **ako prvý krok** vloží `audit_event` (používateľ, snímka `username` a rolí, operácia, `request_payload` bez hesiel, `request_id`, IP, user agent)
- [ ] Hibernate event listener (`PostInsert`, `PostUpdate`, `PostDelete`) zapíše `audit_change` (typ zmeny, `before_state`, `after_state`, `changed_fields`) k aktuálnemu audit záznamu
- [ ] **Fail closed:** zmena auditovanej entity mimo otvoreného audit záznamu vyhodí výnimku a transakcia sa zruší
- [ ] Pri výnimke sa pôvodná transakcia zruší a v novej transakcii (`REQUIRES_NEW`) sa zapíše `audit_event` s `outcome = REJECTED` a `error_code`
- [ ] Odpoveď na úspešný zápis má hlavičku `X-Audit-Event-Id`
- [ ] Prihlásenie, neúspešné prihlásenie a zamknutie účtu sa zapisujú ako `LOGIN`
- [ ] Existujúce zápisy sú auditované: správa používateľov a hesiel (P03), bootstrap administrátora a `DemoDataLoader` (pod používateľom `system`, operácia `DEMO_DATA_LOAD`)
- [ ] Z `request_payload` a stavov entít sa vynechávajú polia s heslami a hashmi hesiel
- [ ] Endpointy (oprávnenie `AUDIT_VIEW`): `GET /api/audit/events?type&entityType&entityId&userId&from&to&operation` (stránkovanie), `GET /api/audit/events/{id}` (so zmenami)
- [ ] Testy: zápis vytvorí `audit_event` + `audit_change`; odmietnutý zápis vytvorí `REJECTED`; zmena entity mimo auditu zlyhá; `UPDATE`/`DELETE` na audit tabuľkách zlyhá; heslo sa v audite neobjaví

### Dotknuté súbory / moduly

Modul `audit`, `shared` (anotácia, aspekt), `iam` (audit prihlásenia), demo loader, migrácia `V4__audit.sql`

### Mimo rozsahu

Audit čítania (P06), uchovávanie a archivácia partícií (neskorší tiket), samostatný databázový používateľ bez práv `UPDATE`/`DELETE`.

### Závislosti a podklady

- Závisí od: [P03]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 7.1–7.3
