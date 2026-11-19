# [AI] F18 Stavy karty v aplikácii: akcie na karte, v pláne a v príchodoch

### Typ úlohy

Nová funkcia (malá)

### Cieľ

Správa stavov bola ťažkopádna a slovo „Ubytovať“ miatlo (stav sa nemenil). Doplniť akcie podľa stavu na všetkých miestach, kde s hosťom recepcia pracuje, a stav zreteľne zobraziť v pláne.

### Akceptačné kritériá

- [ ] Všade v UI premenovať „Ubytovať“ na **„Prideliť lôžko“** (texty v `sk.json`, testy)
- [ ] Detail karty: odznak stavu, priebeh v troch krokoch (Rezervovaná / Prítomný / Odhlásený s časmi) a tlačidlá podľa stavu presne podľa návrhu (`Príchod hosťa`, `Odchod hosťa`, `Storno…` s dialógom na dôvod, `Neprišiel`, `Zrušiť príchod`, `Neskorý príchod`) cez API z B08
- [ ] Plán: farby pásikov podľa stavu karty (rezervovaná čiarkovaný okraj, prítomný plná tmavomodrá, odhlásený sivá, neprišiel oranžová), ikony príchod dnes / odchod dnes / poznámka, aktualizovaná legenda
- [ ] Plán: menu na pásiku pravým tlačidlom myši (a klávesom Menu) s akciami podľa stavu, prvá je hlavná akcia dňa (Príchod hosťa / Odchod hosťa), Enter ju vykoná
- [ ] Plán: v hlavičke počty „Dnes príde“, „Odíde“, „V dome“ pre vybranú recepciu
- [ ] Príchody a odchody: pri každom riadku tlačidlo `Príchod` / `Odchod`, pri karte bez lôžka `Prideliť lôžko`; počítadlá „prijatých x / y“ a „odhlásených x / y“
- [ ] Chyby z B08 (`NO_BED_ASSIGNED`, `ARRIVAL_IN_FUTURE`, `INVALID_STATUS_TRANSITION`) majú slovenské texty; upozornenia na potvrdenie cez `<ConfirmWarningsDialog>`
- [ ] Všetky akcie cez `<WriteAction>` (offline neaktívne) a s oprávnením `RESERVATION_STATUS` / `RESERVATION_STATUS_FIX`
- [ ] Po akcii sa obnoví karta, dotknuté izby plánu a príchody/odchody (aj v trezore)
- [ ] Testy (MSW): príchod z karty, z plánu aj z príchodov; storno s dôvodom; neprišiel; zrušenie príchodu len s oprávnením; farby a ikony pásikov podľa stavu; offline neaktívne

### Dotknuté súbory / moduly

`frontend/src/features/reservations/`, `frontend/src/features/plan/`, `frontend/src/features/movements/`, `frontend/src/features/stays/`, `frontend/src/shared/i18n/sk.json`

### Mimo rozsahu

Hromadný príchod/odchod viacerých kariet naraz.

### Závislosti a podklady

- Závisí od: B08, F21
- Návrh: `docs/frontend/ui/Stavy.dc.html` (stavový diagram a pravidlá)
- Návrh obrazovky: `docs/frontend/ui/KartaV2.dc.html`, `docs/frontend/ui/PlanV2.dc.html`, `docs/frontend/ui/PrichodyV2.dc.html` (živé plátno: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE), štýl v `docs/frontend/ui/README.md`
