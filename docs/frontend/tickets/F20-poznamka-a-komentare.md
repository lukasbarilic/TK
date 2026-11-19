# [AI] F20 Poznámka a komentáre na karte (aj offline na čítanie)

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepcia vidí a upravuje poznámku karty a pridáva komentáre. Poznámka označená „zobraziť v pláne“ má v pláne ikonu.

### Akceptačné kritériá

- [ ] Detail karty: žltý panel „Poznámka ku karte“ s úpravou (len `RESERVATION_EDIT`, len online) a voľbou „Zobraziť ikonu v pláne“
- [ ] Komentáre: zoznam (autor, čas, text, najnovšie hore), pole na nový komentár a tlačidlo „Pridať komentár“; skrytie komentára s potvrdením
- [ ] Plán: ikona poznámky na pásiku (`hasNote`), v bočnom paneli text poznámky a počet komentárov
- [ ] Offline: poznámka a posledné komentáre z trezoru (sú v detaile karty z B03/B10); pridanie a úprava neaktívne; čítanie sa zapisuje do denníka (F15)
- [ ] Testy (MSW): úprava poznámky s konfliktom verzie, pridanie komentára, ikona v pláne, offline zobrazenie

### Dotknuté súbory / moduly

`frontend/src/features/reservations/`, `frontend/src/features/plan/`

### Mimo rozsahu

Komentáre k hosťom, prílohy.

### Závislosti a podklady

- Závisí od: B10, F18
- Návrh: `docs/frontend/DLD-frontend.md` kap. 5 (offline dáta), kap. 7 (denník čítaní)
- Návrh obrazovky: `docs/frontend/ui/KartaV2.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
