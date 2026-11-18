# [AI] F08 Zmeny ubytovania v pláne: presun, dĺžka, výmena, odkladisko

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepčná mení ubytovanie ťahaním v pláne: presunie celý pobyt alebo jeho časť, zmení koniec pobytu, vymení dvoch klientov alebo cez odkladisko urobí viac presunov naraz (UC7–UC11, UC19).

### Akceptačné kritériá

- [ ] Interakcie podľa DLD kap. 9.3 (ťahanie, Alt + ťahanie, ťahanie okraja, kontextové menu, odkladisko, zrušenie budúceho ubytovania) cez `dnd-kit`
- [ ] Počas ťahania vyznačenie, či je cieľ podľa lokálnych dát voľný
- [ ] Presun zamknutého pobytu len s `STAY_MOVE_LOCKED`, spätná zmena len so `STAY_BACKDATE` (UI to skryje, rozhoduje server)
- [ ] Odkladisko ako stav UI; „Uložiť zmeny“ pošle `POST /api/stays/moves:batch`; pri chybe sa ukáže, ktorá položka zlyhala, a odkladisko ostane
- [ ] Konflikt verzie / obsadené lôžko → upozornenie a obnovenie dotknutých izieb; upozornenia na potvrdenie cez dialóg
- [ ] Ťahanie je plynulé aj pri 200 izbách (bez prekresľovania celej mriežky)
- [ ] Testy: výpočet príkazu presunu z pozície pustenia (celý pobyt, od dňa, iné lôžko tej istej izby), zmena dĺžky, výmena, dávka z odkladiska, konflikt 409, offline zakázané

### Dotknuté súbory / moduly

`frontend/src/features/plan/`, `frontend/src/features/stays/`

### Mimo rozsahu

Presun turnusov kontingentov, blokácie.

### Závislosti a podklady

- Závisí od: [F07]
- Návrh: `docs/frontend/DLD-frontend.md` kap. 9.3; `docs/HLD-ubytovanie.md` kap. 5.3, 5.4, UC7–UC11, UC19
- Návrh obrazovky: `docs/frontend/ui/Main.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
