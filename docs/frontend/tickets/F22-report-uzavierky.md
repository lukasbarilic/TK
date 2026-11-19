# [AI] F22 Nočná uzávierka: report dňa, výhľad obsadenosti, tlač a archív

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Obrazovka uzávierky ukáže pred uzavretím report dňa: príchody a odchody v osobách, ľudí v dome, obsadenosť komplexu a budov a výhľad na 14 dní. Po uzavretí je report v archíve a dá sa vytlačiť.

### Akceptačné kritériá

- [ ] Dlaždice: Príchody (osoby, z toho neprišli), Odchody, V dome na konci dňa (ráno, +príchody, −odchody), Obsadenosť komplexu (%, obsadené / použiteľné lôžka), Voľné lôžka (a blokované) – dáta z `report` v náhľade uzávierky (B09)
- [ ] Tabuľka obsadenosti podľa budov s riadkom „Komplex spolu“ a pruhom percent; pod ňou vysvetlenie výpočtu
- [ ] Graf výhľadu obsadenosti na 14 dní: jedna séria, stĺpce jednej farby, popis prvého, najvyššieho a posledného dňa, tooltip s dátumom a percentom, `aria-label` so zhrnutím; víkendy zvýraznené na osi
- [ ] Sekcia „Kontrola pred uzavretím“: blokujúce zistenia (ak sú, tlačidlo uzavretia je neaktívne) a neprišlí, ktorým uzavretie nastaví stav Neprišiel
- [ ] „Tlačiť report“ – tlačová verzia na A4 na výšku (bez menu, s hlavičkou recepcie, dňa a času)
- [ ] „Archív reportov“: zoznam uzavretých dní a zobrazenie uloženého reportu (len čítanie, tlač)
- [ ] Offline: obrazovka neaktívna s vysvetlením (report počíta server)
- [ ] Testy (MSW): zobrazenie čísel, neaktívne uzavretie pri blokujúcom zistení, archív, tlačová verzia bez menu

### Dotknuté súbory / moduly

`frontend/src/features/business-day/`

### Mimo rozsahu

PDF zo servera, štatistiky za obdobie.

### Závislosti a podklady

- Závisí od: B09, F21
- Návrh: `docs/HLD-ubytovanie.md` kap. 5.8
- Návrh obrazovky: `docs/frontend/ui/UzavierkaReport.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
