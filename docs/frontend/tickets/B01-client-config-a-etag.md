# [AI] B01 Konfigurácia klienta, počítadlo zmien recepcie a ETag

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Desktopová aplikácia potrebuje vedieť, či je jej verzia ešte podporovaná a s akými offline nastaveniami má pracovať. Pravidelná synchronizácia 200 klientov nesmie zaťažovať server, preto čítacie endpointy pre offline dáta podporia ETag a `304 Not Modified`.

### Akceptačné kritériá

- [ ] `GET /api/client-config` (prihlásený používateľ, bez osobitného oprávnenia) vracia `apiVersion`, `minClientVersion`, `serverTime`, `idleTimeoutSec` a `offline { planDaysBack, planDaysAhead, syncIntervalSec, maxAgeDays }` podľa DLD kap. 13; hodnoty z `application.yml` (`app.client.*`), `maxAgeDays` predvolene `null`
- [ ] Nová tabuľka `reception_change_seq(reception_id PK, seq bigint, changed_at)` (nová Flyway migrácia); `seq` sa zvýši v tej istej transakcii pri každom zápise, ktorý mení pobyty, blokácie alebo karty v budovách recepcie a pri uzávierke
- [ ] `GET /api/accommodation-plan` s parametrom `receptionId` vracia hlavičku `ETag` odvodenú z `seq` recepcie, rozsahu dátumov, filtrov a ID používateľa; pri zhodnom `If-None-Match` vráti `304` **bez tela a bez audit záznamu čítania**
- [ ] Číselníky `GET /api/catalog/*` vracajú `ETag` a podporujú `304`; výsledky sú v Caffeine cache (TTL 10 min)
- [ ] Spoločný mechanizmus (napr. pomocná trieda `ConditionalGet`) použiteľný aj v B02, B03, B05
- [ ] Testy: `client-config` vráti hodnoty z konfigurácie; zápis pobytu zvýši `seq` len dotknutej recepcie; zrušená transakcia `seq` nezvýši; `304` pri nezmenenom pláne a `200` po zmene; pri `304` nevznikne záznam v `audit_subject_access`

### Dotknuté súbory / moduly

Modul `shared` (ETag), `accommodation`, `catalog`, `businessday`, nový endpoint v module `iam` alebo `settings`

### Mimo rozsahu

Systémová tabuľka nastavení (HLD-ubytovanie kap. 9), ETag na ďalších endpointoch.

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/frontend/HLD-frontend.md` kap. 3.4 a 5, `docs/frontend/DLD-frontend.md` kap. 13
