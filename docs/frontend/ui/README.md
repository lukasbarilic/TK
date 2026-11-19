# Návrh obrazoviek – TheraSuite (desktop)

Živý návrh na dizajnovom plátne: https://claude.ai/artifact/FNHjH1bPXuxz3gij9bmbyE

Súbory v tomto priečinku sú zdrojová kópia plátna k 18. 11. 2026 (formát Design Component `.dc.html` + `canvas.json`). Sú to **návrhy obrazoviek**, nie kód aplikácie; mimo plátna sa samostatne nevykreslia. Pri zmene návrhu je zdrojom pravdy plátno, kópiu tu treba obnoviť.

| Súbor | Obrazovka | Tiket |
|---|---|---|
| `Prihlasenie.dc.html` | Prihlásenie online aj offline | F03, F04 |
| `Main.dc.html` | Plán ubytovania, detail pásika, offline stav | F05, F06, F08 |
| `Ubytovat.dc.html` | Ubytovanie – výber lôžka, upozornenie na potvrdenie | F07 |
| `Karta.dc.html` | Rezervačná karta | F09 |
| `Prichody.dc.html` | Príchody a odchody | F10 |
| `Uzavierka.dc.html` | Nočná uzávierka | F11 |
| `Lekar.dc.html` | Zoznam hostí pre lekára | F12 |
| `Pouzivatelia.dc.html` | Administrácia používateľov | F13 |
| `Audit.dc.html` | Audit a prístupy ku klientovi | F14 |

## Vizuálny štýl

| Prvok | Hodnota |
|---|---|
| Pozadie aplikácie | `#EEF5FC` (svetlomodré) |
| Ľavé menu | `#DCEBFA`, okraj `#C9DCEF` |
| Karty, tabuľky, panely | `#FFFFFF`, okraj `#C9DCEF`, zaoblenie 12 px |
| Hlavná farba (tlačidlá, ubytovaný hosť) | `#1E5FA8`, tmavšia `#154A85` |
| Rezervovaný hosť | výplň `#CFE3F7`, okraj `#5B8FC9` |
| Text / sekundárny text | `#13243A` / `#4A5D75` |
| Upozornenia a offline | pozadie `#FFF1E0`, text `#7A3705`, akcent `#B4540A` |
| Online stav | pozadie `#DDF2F0`, text `#0B5C5C` |
| Písmo | Inter (400, 500, 600, 700) |
| Logo | „TheraSuite“ a pod ním menšie „by N-teq“ |

## v2 – úpravy po teste (19. 11. 2026)

| Súbor | Obrazovka | Tiket |
|---|---|---|
| `Stavy.dc.html` | Stavy rezervačnej karty a pravidlá prechodov | B08, F18 |
| `KartaV2.dc.html` | Karta: tlačidlá podľa stavu, priebeh, poznámka, komentáre, Späť | F18, F20, F21 |
| `NovaRezervacia.dc.html` | Nová rezervácia (aj z plánu) | F19 |
| `PlanV2.dc.html` | Plán: farby stavov, ikony, menu na pásiku | F18 |
| `PrichodyV2.dc.html` | Príchody a odchody s tlačidlami | F18 |
| `UzavierkaReport.dc.html` | Uzávierka: report dňa, obsadenosť, výhľad 14 dní | F22 |

Farby stavov: Rezervovaná `#CFE3F7` s čiarkovaným okrajom `#3F79B8`, Prítomný `#1E5FA8`, Odhlásený `#D5DCE4`, Neprišiel `#FFF1E0` s okrajom `#D2691E`, Stornovaná `#F7E7DE`. Poznámka ku karte: panel `#FFF8E8`, okraj `#EBCB8B`.
