<#
.SYNOPSIS
  Zostaví podpísaný balík aktualizácie a vytvorí latest.json (docs/frontend/AKTUALIZACIE.md).
.DESCRIPTION
  Súkromný kľúč sa číta z premennej prostredia TAURI_SIGNING_PRIVATE_KEY (obsah kľúča)
  alebo TAURI_SIGNING_PRIVATE_KEY_PATH (cesta k súboru mimo repozitára); heslo kľúča z
  TAURI_SIGNING_PRIVATE_KEY_PASSWORD. Kľúč sa nikdy necommituje.
  Výstup: frontend/dist-updates/<verzia>/ s MSI, .sig a latest.json.
.PARAMETER BaseUrl
  Adresa servera zákazníka, napr. https://server.example.invalid (bez koncového lomítka).
.PARAMETER Notes
  Poznámky k vydaniu.
#>
param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [string]$Notes = ''
)
$ErrorActionPreference = 'Stop'

if (-not $env:TAURI_SIGNING_PRIVATE_KEY -and -not $env:TAURI_SIGNING_PRIVATE_KEY_PATH) {
  throw 'Nastavte TAURI_SIGNING_PRIVATE_KEY alebo TAURI_SIGNING_PRIVATE_KEY_PATH (súkromný kľúč mimo repozitára).'
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  $conf = Get-Content 'src-tauri/tauri.conf.json' -Raw | ConvertFrom-Json
  $version = $conf.version
  npm run tauri:build -- --bundles msi
  if ($LASTEXITCODE -ne 0) { throw 'tauri build zlyhal' }

  $msi = Get-ChildItem 'src-tauri/target/release/bundle/msi' -Filter "*_${version}_*.msi" | Select-Object -First 1
  if (-not $msi) { throw "MSI pre verziu $version sa nenašlo" }
  $sig = "$($msi.FullName).sig"
  if (-not (Test-Path $sig)) { throw "Chýba podpis $sig (bundle.createUpdaterArtifacts a kľúč?)" }

  $out = Join-Path $root "dist-updates/$version"
  New-Item -ItemType Directory -Force $out | Out-Null
  Copy-Item $msi.FullName, $sig $out

  $latest = [ordered]@{
    version   = $version
    notes     = $Notes
    pub_date  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    platforms = [ordered]@{
      'windows-x86_64' = [ordered]@{
        signature = (Get-Content $sig -Raw).Trim()
        url       = "$($BaseUrl.TrimEnd('/'))/updates/$($msi.Name)"
      }
    }
  }
  $latest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'latest.json') -Encoding utf8
  Write-Host "Hotovo: $out"
  Write-Host 'Nahrajte MSI do updates/ na serveri a latest.json AŽ AKO POSLEDNÝ súbor.'
} finally {
  Pop-Location
}
