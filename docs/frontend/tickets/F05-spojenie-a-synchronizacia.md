# [AI] F05 Stav spojenia, synchronizácia offline dát a režim len na čítanie

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Aplikácia rozpozná výpadok spojenia, prepne sa do režimu len na čítanie a ukazuje dáta z trezoru. Online dáta priebežne synchronizuje podľa živých udalostí a pravidelnej kontroly s ETagom.

### Akceptačné kritériá

- [ ] Stavový automat `ConnState` podľa DLD kap. 6 (heartbeat, kontrola `/actuator/health`, `session-expired`, `locked`); hook `useConnection()` s `canWrite`
- [ ] Lišta stavu v `AppShell`: online / overujem spojenie / offline s vekom dát / synchronizujem; komponent `<WriteAction>` pre všetky zápisové akcie
- [ ] Zamknutie po nečinnosti podľa `idleTimeoutSec` z `client-config`; odomknutie online aj offline
- [ ] `SyncEngine` podľa DLD kap. 5.3 (úplná, čiastočná po `plan-changed`, po `business-day-changed`, pravidelná s náhodným posunom, štart s oneskorením 0–30 s) a dátové sady podľa DLD kap. 5.1 a 5.2 filtrované oprávneniami
- [ ] `offlineQuery` podľa DLD kap. 5.4 (`source`, `syncedAt`, prázdny stav „nie sú uložené“); ETag sa ukladá v metadátach záznamu
- [ ] Upratovanie starých záznamov po úplnej synchronizácii
- [ ] Platnosť `offline.maxAgeDays` sa ukladá do trezoru
- [ ] Testy (MSW, falošné hodiny): prechod online → checking → offline → online; 401 → `session-expired`; čiastočná synchronizácia načíta len dotknuté izby a karty; `304` nezmení dáta; sady bez oprávnenia sa nesťahujú; upratovanie

### Dotknuté súbory / moduly

`frontend/src/shared/offline/`, `frontend/src/features/offline/`, `frontend/src/app/layout/`

### Mimo rozsahu

Denník čítaní (F15), konkrétne obrazovky.

### Závislosti a podklady

- Závisí od: [F04], [B01], [B02], [B03]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 3; `docs/frontend/DLD-frontend.md` kap. 5, 6
- Návrh obrazovky: `docs/frontend/ui/Main.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
