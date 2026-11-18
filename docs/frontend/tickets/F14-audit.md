# [AI] F14 Audit: vyhľadávanie, detail a prístupy ku klientovi

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Audítor a administrátor vyhľadajú audit záznamy, pozrú si detail zmeny a zistia, kto čítal údaje konkrétneho klienta.

### Akceptačné kritériá

- [ ] Vyhľadávanie `GET /api/audit/events` s filtrami (typ, entita, ID entity, používateľ, od–do, operácia), stránkovanie na serveri, filtre v URL
- [ ] Detail `GET /api/audit/events/{id}` so stavom pred a po zmene (prehľadné porovnanie JSON)
- [ ] Prístupy ku klientovi `GET /api/audit/clients/{id}/access` vrátane označenia `OFFLINE` / `CACHED` (B04)
- [ ] Len s `AUDIT_VIEW`, len online
- [ ] Testy (MSW): filtrovanie, stránkovanie, detail s porovnaním, prístupy s offline záznamom

### Dotknuté súbory / moduly

`frontend/src/features/audit/`

### Mimo rozsahu

Export auditu, archív.

### Závislosti a podklady

- Závisí od: [F03], [B04]
- Návrh: `docs/HLD-ubytovanie.md` kap. 7
- Návrh obrazovky: `docs/frontend/ui/Audit.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
