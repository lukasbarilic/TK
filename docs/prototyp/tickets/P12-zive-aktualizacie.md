# [AI] P12 Živé aktualizácie plánu (SSE)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Keď recepčná zmení ubytovanie alebo spraví uzávierku, ostatní používatelia s otvoreným plánom sa o tom dozvedia hneď, bez tlačidla Obnoviť.

### Akceptačné kritériá

- [ ] `GET /api/events` (Server-Sent Events, oprávnenie `PLAN_VIEW`) otvorí spojenie pre prihláseného používateľa
- [ ] Udalosti podľa HLD kap. 6.4:
  - `plan-changed` – `{ receptionId, roomIds, from, to, auditEventId }` po každej zmene pobytu (P09, P11)
  - `business-day-changed` – `{ receptionId, businessDate }` po uzávierke (P07)
  - `heartbeat` každých 25 s
- [ ] Udalosť sa odošle **až po úspešnom COMMIT** (`@TransactionalEventListener(AFTER_COMMIT)`); pri zrušenej transakcii sa nepošle nič
- [ ] Udalosť dostanú len používatelia s rozsahom na danú recepciu
- [ ] Udalosti **neobsahujú osobné údaje** (len ID a dátumy)
- [ ] Rozosielanie v pamäti s rozhraním `EventBroadcaster`, aby sa neskôr dalo nahradiť PostgreSQL `LISTEN/NOTIFY`; odpojení klienti sa upracú
- [ ] Spojenie funguje cez reverse proxy (hlavičky `Cache-Control: no-cache`, `X-Accel-Buffering: no`) a nespadne na vypršaní relácie skôr než ostatné požiadavky
- [ ] Testy: po presune pobytu príde `plan-changed` s dotknutými izbami; po odmietnutom zápise nepríde nič; používateľ inej recepcie udalosť nedostane; po uzávierke príde `business-day-changed`; udalosť neobsahuje mená

### Dotknuté súbory / moduly

Modul `notification` (živé udalosti), `accommodation` a `businessday` (publikovanie udalostí)

### Mimo rozsahu

Oznámenia používateľom (zvonček, e-mail), viac inštancií backendu, `Last-Event-ID` dobiehanie zmeškaných udalostí.

### Závislosti a podklady

- Závisí od: [P11]
- Návrh: `docs/HLD-ubytovanie.md`, kap. 6.4
