# [AI] F17 Inštalačný balík MSI a nasadenie na počítače

### Typ úlohy

Nová funkcia (malá)

### Cieľ

IT zákazníka nainštaluje aplikáciu na počítače recepcie a lekárov jedným MSI balíkom s prednastavenou adresou servera.

### Akceptačné kritériá

- [ ] `npm run tauri:build` vytvorí MSI pre Windows x64, inštalácia pre celý počítač, ikona a názov „TheraSuite“, verzia z `package.json`
- [ ] WebView2: inštalátor ho doinštaluje, ak chýba (`webviewInstallMode` = embedBootstrapper)
- [ ] Adresa servera sa dá nastaviť pri inštalácii vlastnosťou MSI `SERVER_URL`, ktorá zapíše `%ProgramData%\TheraSuite\config.json`
- [ ] Odinštalovanie nemaže trezory používateľov (len aplikáciu); v návode je postup, ako ich zmazať
- [ ] Návod `docs/frontend/INSTALACIA.md`: požiadavky (Windows 10/11 x64, certifikát internej CA v úložisku Windows, odporúčaný BitLocker), tichá inštalácia (`msiexec /i … /qn SERVER_URL=…`), GPO/Intune, prvé spustenie
- [ ] Ručne overené na čistom Windows 11 (popis v PR)

### Dotknuté súbory / moduly

`frontend/src-tauri/tauri.conf.json`, `frontend/src-tauri/wix/`, `docs/frontend/`

### Mimo rozsahu

Podpis Authenticode (otvorená otázka), CI build na Windows runneri.

### Závislosti a podklady

- Závisí od: [F16]
- Návrh: `docs/frontend/HLD-frontend.md` kap. 2.4
