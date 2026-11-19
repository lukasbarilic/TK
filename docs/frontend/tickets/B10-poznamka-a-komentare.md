# [AI] B10 Poznámka a komentáre k rezervačnej karte

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Recepcia potrebuje k ubytovaniu zapisovať poznámky: jednu pevnú poznámku karty, ktorú vidno aj v pláne, a komentáre s autorom a časom.

### Akceptačné kritériá

- [ ] Stĺpce `note` (max. 2000 znakov) a `note_in_plan` (boolean) v tabuľke `reservation`; úprava cez existujúci `PATCH /api/reservations/{id}` (s `version`)
- [ ] Tabuľka `reservation_comment(id, reservation_id, author_id, created_at, text)`; `text` max. 2000 znakov, komentár sa nedá upraviť, len skryť (`hidden_at`, `hidden_by`) s oprávnením `RESERVATION_EDIT`
- [ ] `GET /api/reservations/{id}/comments` (`RESERVATION_VIEW`), `POST …/comments` (`RESERVATION_EDIT`), `POST …/comments/{commentId}/hide`
- [ ] Detail karty (`GET /api/reservations/{id}` a `GET /api/offline/reservations`) obsahuje `note`, `noteInPlan` a posledných 20 komentárov – aby boli dostupné offline
- [ ] Pásik `STAY` v `GET /api/accommodation-plan` má príznak `hasNote` (len ak `note_in_plan`) a počet komentárov `commentCount`
- [ ] Zápisy auditované; čítanie poznámky a komentárov je čítanie karty (rozsah `CONTACT`)
- [ ] Zmena poznámky alebo nový komentár zvýši `reception_change_seq` a pošle `plan-changed` (kvôli ikone v pláne)
- [ ] Testy: uloženie a zobrazenie poznámky, limit dĺžky, pridanie a skrytie komentára, oprávnenia, príznak v pláne, obsah v offline balíku

### Dotknuté súbory / moduly

Modul `reservation`, `accommodation` (príznak na pásiku), migrácia

### Mimo rozsahu

Komentáre k jednotlivým hosťom, prílohy, @zmienky a oznámenia.

### Závislosti a podklady

- Závisí od: –
- Návrh: `docs/frontend/DLD-frontend.md` kap. 5 (offline dáta), kap. 7 (denník čítaní)
- Návrh obrazovky: `docs/frontend/ui/KartaV2.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
