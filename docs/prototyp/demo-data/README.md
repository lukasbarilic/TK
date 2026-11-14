# Demo dáta pre prototyp

Štruktúra (izby, lôžka, termíny pobytov) je prevzatá z pôvodnej databázy `iszp_klient_ubytovanie.sqlite` a prevedená do nového modelu (docs/HLD-ubytovanie.md, kap. 4).

## Len fiktívne údaje

Demo a testovacie dáta **nesmú obsahovať nič reálne**. Z pôvodnej databázy sa neprenáša žiadne meno, dátum narodenia, adresa, kontakt, rodné číslo, číslo dokladu (OP, pas, víza) ani názov firmy, budovy či poisťovne.

| Údaj | Náhrada |
|---|---|
| Mená a priezviská klientov a kontaktných osôb | vymyslené kombinácie (priezviská typu Testovský, Ukážková, Nanečisto), náhodne, deterministicky |
| Dátum narodenia | náhodný dátum 1940–1989 |
| Budovy | Hotel Alfa, Kúpeľný dom Beta, Vila Gama, Penzión Delta, Hotel Epsilon |
| Platitelia | Samoplatca, Poisťovňa Alfa/Beta/Gama, kódy P00–P03 |
| Skupiny | Firma Demo s.r.o., Klub seniorov Ukážkovo… |
| Adresy, telefóny, e-maily, rodné čísla, doklady | **neuvádzajú sa vôbec**; ak ich neskôr model bude vyžadovať, použije sa zjavne vymyslená hodnota (napr. ulica „Ukážková 1, 000 00 Demovo“, doklad „XX000000“, e-mail `meno@example.invalid`) |

Automatická kontrola pri prevode overila, že sa v súboroch nevyskytuje žiadna hodnota osobných polí z pôvodnej databázy.
Načítava ich `DemoDataLoader` len v profile `demo` a len do prázdnej databázy.

| Súbor | Tabuľka | Poznámka |
|---|---|---|
| centres.csv | centre | jedno stredisko „Kúpele Demo“ |
| receptions.csv | reception | 2 recepcie: MAIN (budovy 1, 2, 5), PAV (budovy 3, 4) |
| buildings.csv | building | |
| base_categories.csv, room_categories.csv | base_category, room_category | |
| departments.csv | department | |
| rooms.csv | room | lôžka (`bed`) sa **generujú**: REGULAR 1..regular_beds, EXTRA regular_beds+1..regular_beds+extra_beds |
| payers.csv | payer | samoplatca + 3 poisťovne |
| block_reasons.csv, room_blocks.csv | block_reason, room_block | 1 pôvodná blokácia vynechaná (kolidovala s pobytom) |
| clients.csv | client | vymyslené osoby; bez rodných čísel, dokladov a adries |
| reservation_groups.csv | reservation_group | |
| reservations.csv | reservation | `party_id` = malá skupina (partneri v jednej izbe), hodnota je ID prvej karty dvojice |
| reservation_guests.csv | reservation_guest | |
| stays.csv | stay | `room_id` + `bed_number` + `bed_type` sa prevedú na `bed_id` |
| users.csv | user_account | **len demo**, heslo `Demo123!`, bez vynútenej zmeny hesla |

Kontrolované: žiadne prekrytie pobytov na lôžku, pobyty s výhradným užívaním izby (karty 130–133, 135, 139) sú v izbe samé,
pobyty sú v rámci dátumov karty a neprekrývajú sa s blokáciami.

Pozor: niektoré karty v stave `CHECKED_IN` majú odchod v minulosti – je to zámer pre ukážku nočnej uzávierky.
