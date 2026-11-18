# Aktualizácie aplikácie (F16)

Návrh: DLD kap. 12. Aplikácia sa aktualizuje zo servera zákazníka, internet netreba.

## Ako to funguje

- Pri štarte a potom každú hodinu (len ak je spojenie `online`) jadro stiahne `<serverUrl>/updates/latest.json`.
  Podpis balíka sa overuje verejným kľúčom z `frontend/src-tauri/tauri.conf.json` (`plugins.updater.pubkey`).
- Je novšia verzia → lišta „Je k dispozícii nová verzia“; po kliknutí sa MSI nainštaluje v režime `passive` a aplikácia sa reštartuje. Trezor ostáva.
- `GET /api/client-config` vracia `minClientVersion`. Ak je verzia aplikácie nižšia (semver), zobrazí sa blokujúca obrazovka „Je potrebná aktualizácia“. Offline sa nič nekontroluje.

## Jednorazová príprava kľúčov

```powershell
npx tauri signer generate -w $HOME\.tauri\therasuite-updater.key
```

- **Verejný kľúč** (`.key.pub`) vložte do `plugins.updater.pubkey` v `tauri.conf.json` (zatiaľ je tam zástupný text) a commitnite.
- **Súkromný kľúč** je tajomstvo: nikdy ho nedávajte do repozitára. Skript ho číta z `TAURI_SIGNING_PRIVATE_KEY` (obsah) alebo `TAURI_SIGNING_PRIVATE_KEY_PATH` (cesta), heslo z `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.
- Strata kľúča = už nasadené aplikácie nepríjmu nové aktualizácie; kľúč zálohujte.

## Vytvorenie vydania

1. Zvýšte `version` v `frontend/src-tauri/tauri.conf.json`, `frontend/src-tauri/Cargo.toml` a `frontend/package.json` (musia sa zhodovať).
2. Nastavte premenné prostredia so súkromným kľúčom a spustite vo `frontend/`:

   ```powershell
   .\scripts\vytvor-aktualizaciu.ps1 -BaseUrl https://server.example.invalid -Notes "Popis zmien"
   ```

3. Výstup je v `frontend/dist-updates/<verzia>/`: `*.msi`, `*.msi.sig`, `latest.json`.

Tvar `latest.json`:

```json
{
  "version": "0.2.0",
  "notes": "Popis zmien",
  "pub_date": "2026-11-18T10:00:00Z",
  "platforms": {
    "windows-x86_64": {
      "signature": "<obsah .sig>",
      "url": "https://server.example.invalid/updates/TheraSuite_0.2.0_x64_en-US.msi"
    }
  }
}
```

## Nahratie na server zákazníka

Súbory sa nahrávajú ručne (automatické nahratie nie je súčasťou F16).

1. Nahrajte `*.msi` do adresára `updates/` na serveri (napr. `/srv/therasuite/updates/`).
2. **Ako posledný** nahrajte `latest.json` do toho istého adresára, aby klienti nevideli verziu bez balíka.
3. Reverzná proxy musí servírovať adresár na `<serverUrl>/updates/` a požiadavky `/updates/**` nesmie posielať na backend. Príklad pre nginx:

   ```nginx
   location /updates/ {
       alias /srv/therasuite/updates/;
       add_header Cache-Control "no-cache";
   }
   ```

   Proxy musí byť na HTTPS (aplikácia v produkcii HTTP nepripustí). Súbory sú verejne čitateľné, no bez platného podpisu ich aplikácia nenainštaluje.
4. Overenie: `curl https://server.example.invalid/updates/latest.json` vráti JSON a URL v ňom je dostupná.

## Vynútenie aktualizácie

Ak backend prestane podporovať staršiu verziu, zvýšte `minClientVersion` (v `application.yml`, `app.client.*`, pozri B01). Staršie klienty sa zablokujú obrazovkou s tlačidlom Aktualizovať; kým nie je na serveri nová verzia, tlačidlo je neaktívne.

## Poznámky

- Zostavenie MSI (`bundle.active`) a nasadenie na počítače rieši F17; pre skript treba bundle zapnúť.
- Rust časť (`update_check`, `update_install`) nebola v rámci tohto tiketu skompilovaná ani otestovaná na Windows; pred vydaním ju treba overiť ručne.
