# Inštalácia aplikácie TheraSuite (F17)

Návrh: HLD-frontend kap. 2.4.

## Požiadavky

- Windows 10 alebo 11, x64.
- **Certifikát internej CA** zákazníka v úložisku Windows (*Trusted Root Certification Authorities*, pre celý počítač). Server beží na HTTPS s certifikátom internej CA; bez dôvery v ňu sa aplikácia nepripojí.
- Odporúčame **BitLocker** (šifrovanie disku). Trezor s offline dátami je šifrovaný, BitLocker je ďalšia vrstva pri strate počítača.
- WebView2 netreba pripravovať: inštalátor ho doinštaluje, ak chýba (potrebuje vtedy jednorazovo internet alebo interný prístup k stiahnutiu).

## Vytvorenie balíka (vývojár)

Na Windows vo `frontend/` (potrebné Rust a WiX Toolset, ktorý Tauri stiahne samo):

```powershell
npm ci
npm run tauri:build
```

Výsledok: `frontend\src-tauri\target\release\bundle\msi\TheraSuite_<verzia>_x64_sk-SK.msi`. Verzia sa berie z `package.json`.
Podpis Authenticode zatiaľ nie je súčasťou balíka (otvorená otázka), Windows SmartScreen môže pri ručnom spustení upozorniť.

## Inštalácia

Balík sa inštaluje pre celý počítač (vyžaduje práva správcu).

Interaktívne: dvojklik na MSI. Adresu servera nastaví IT cez vlastnosť `SERVER_URL` (pozri nižšie), inak sa aplikácia na ňu opýta pri prvom spustení.

### Tichá inštalácia

```powershell
msiexec /i TheraSuite_0.1.0_x64_sk-SK.msi /qn SERVER_URL=https://server.example.invalid
```

- `SERVER_URL` zapíše `%ProgramData%\TheraSuite\config.json` v tvare `{"serverUrl": "https://server.example.invalid"}`.
- Hodnota nesmie obsahovať úvodzovky, apostrof ani medzery; inak inštalácia skončí chybou.
- Bez `SERVER_URL` sa súbor nevytvorí (ani sa neprepíše existujúci). Súbor môže IT vytvoriť aj ručne.
- Adresa zadaná používateľom pri prvom spustení (`%APPDATA%\TheraSuite\config.json`) má prednosť pred súborom v `%ProgramData%`.
- Log inštalácie: pridajte `/l*v instal.log`.

### GPO (Active Directory)

1. MSI dajte na sieťové zdieľanie čitateľné pre účty počítačov.
2. *Group Policy Management* → GPO na OU s počítačmi → *Computer Configuration → Policies → Software Settings → Software installation → New → Package*, vyberte MSI, *Assigned*.
3. Adresu servera cez `SERVER_URL` GPO neumožňuje priamo; použite *Advanced* + transform (`.mst`) so `SERVER_URL`, alebo súbor `%ProgramData%\TheraSuite\config.json` rozdajte cez *Group Policy Preferences → Files*.

### Intune

1. *Apps → Windows → Add → Line-of-business app*, nahrajte MSI (alebo Win32 app cez `.intunewin`).
2. Pri Win32 app: *Install command* `msiexec /i "TheraSuite_0.1.0_x64_sk-SK.msi" /qn SERVER_URL=https://server.example.invalid`, *Uninstall command* `msiexec /x "{ProductCode}" /qn`.
3. *Detection rule*: MSI product code (alebo súbor `C:\Program Files\TheraSuite\TheraSuite.exe`).
4. Priraďte skupine počítačov (*Required*). Certifikát internej CA rozdajte profilom *Trusted certificate*.

## Prvé spustenie

1. Spustite *TheraSuite* z ponuky Štart.
2. Ak je `SERVER_URL` nastavená, aplikácia ide rovno na prihlásenie. Inak sa opýta na adresu servera.
3. Prvé prihlásenie musí byť **online**; vznikne trezor pre používateľa na tomto počítači. Offline sa potom môže prihlásiť len ten, kto sa tu už raz prihlásil online.
4. Nové verzie aplikácia sťahuje zo servera zákazníka (pozri `AKTUALIZACIE.md`).

## Odinštalovanie a trezory

Odinštalovanie (*Nastavenia → Aplikácie* alebo `msiexec /x … /qn`) odstráni **len aplikáciu**. Trezory používateľov (`%LOCALAPPDATA%\TheraSuite\vaults\`) a nastavenia (`%APPDATA%\TheraSuite`) ostanú, aby sa nestratili offline dáta pri preinštalovaní. Súbor `%ProgramData%\TheraSuite\config.json` tiež ostáva.

Úplné zmazanie dát (napr. vyradenie počítača) – pre každého používateľa Windows:

```powershell
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\TheraSuite"
Remove-Item -Recurse -Force "$env:APPDATA\TheraSuite"
```

a raz pre počítač:

```powershell
Remove-Item -Recurse -Force "$env:ProgramData\TheraSuite"
```

Pri skripte spúšťanom ako SYSTEM zmažte rovnaké priečinky v každom profile `C:\Users\<používateľ>\AppData\Local\TheraSuite` a `...\AppData\Roaming\TheraSuite`.

## Ručné overenie na čistom Windows 11

Postup na overenie pred vydaním (výsledok zapíšte do PR): čistá VM bez WebView2 → tichá inštalácia so `SERVER_URL` → existuje `config.json` → aplikácia sa spustí a ukáže prihlásenie → odinštalovanie → `vaults` ostali.
