# [AI] F21 Navigácia Späť a drobčeky na všetkých obrazovkách

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Desktopová aplikácia nemá tlačidlo Späť prehliadača a z detailu karty a ďalších stránok sa nedalo vrátiť. Doplniť vlastnú navigáciu späť, ktorá vráti na pôvodné miesto vrátane pozície v pláne.

### Akceptačné kritériá

- [ ] Spoločný komponent hlavičky stránky s tlačidlom **Späť** a drobčekmi (napr. „Plán ubytovania / 17. 11. ALF 102 / Karta R-2026-0140“) na všetkých obrazovkách, ktoré nie sú v hlavnom menu (detail karty, nová rezervácia, detail auditu, report z archívu …)
- [ ] Späť ide na predchádzajúcu obrazovku v aplikácii (história React Routera); ak história nie je (otvorené priamo), ide na nadradenú obrazovku podľa drobčekov
- [ ] Plán si pri odchode pamätá dátumový rozsah, filtre, zoom a pozíciu rolovania a po návrate ich obnoví (v pamäti relácie, nie v `plan.defaultView`)
- [ ] Klávesové skratky Alt+← / Alt+→ a bočné tlačidlá myši (Back/Forward) fungujú v okne Tauri aj v prehliadači
- [ ] Odkazy z plánu, príchodov, uzávierky a zoznamu hostí na kartu odovzdávajú pôvod (odkiaľ prišiel), aby drobčeky ukazovali správnu cestu
- [ ] Testy: návrat z karty do plánu obnoví rozsah a filtre; Alt+←; priamy vstup na kartu bez histórie → Späť vedie na zoznam kariet

### Dotknuté súbory / moduly

`frontend/src/app/` (router, layout), `frontend/src/shared/ui/` (hlavička stránky), `frontend/src/features/plan/`

### Mimo rozsahu

Viac okien aplikácie naraz, záložky.

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/frontend/DLD-frontend.md` kap. 1 (štruktúra), kap. 9.4 (filtre plánu)
- Návrh obrazovky: `docs/frontend/ui/KartaV2.dc.html`, `docs/frontend/ui/NovaRezervacia.dc.html` (tlačidlo Späť a drobčeky v hlavičke) (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
