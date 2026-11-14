# vytvor-tikety.ps1
# Založí GitHub issues pre prvý prototyp zo súborov P01-*.md ... P12-*.md.
# Spúšťajte z priečinka repozitára (gh musí byť prihlásené).
#
#   powershell -ExecutionPolicy Bypass -File vytvor-tikety.ps1 -TicketsPath "C:\Projects\DevPod\prototyp\tickets" [-DryRun]

param(
    [Parameter(Mandatory = $true)][string]$TicketsPath,
    [string]$Label = "prototyp-1",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding $false

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "Chýba GitHub CLI (gh)." }
$files = Get-ChildItem -Path $TicketsPath -Filter "P*.md" | Sort-Object Name
if ($files.Count -eq 0) { throw "V $TicketsPath nie sú žiadne tikety P*.md" }

# Načítanie tiketov: prvý riadok "# Názov", zvyšok je telo
$tickets = foreach ($f in $files) {
    $text  = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
    $lines = $text -split "`r?`n"
    $title = $lines[0] -replace '^#\s*', ''
    $code  = ([regex]::Match($title, 'P\d{2}')).Value
    [pscustomobject]@{ Code = $code; Title = $title; Body = ($lines[1..($lines.Count - 1)] -join "`n").Trim(); Number = $null }
}

Write-Host "Repozitár: $(gh repo view --json nameWithOwner -q .nameWithOwner)"
Write-Host "Tiketov: $($tickets.Count), štítok: $Label"
if ($DryRun) { $tickets | ForEach-Object { Write-Host " - $($_.Title)" }; return }

gh label create $Label --color 0E8A16 --description "Prvý prototyp backendu ubytovania" --force | Out-Null

$tmp = [IO.Path]::GetTempFileName()
try {
    # 1. kolo: založenie issues
    foreach ($t in $tickets) {
        [IO.File]::WriteAllText($tmp, $t.Body, $utf8)
        $url = gh issue create --title $t.Title --body-file $tmp --label $Label
        $t.Number = [int](($url -split '/')[-1])
        Write-Host "#$($t.Number)  $($t.Title)"
    }

    # 2. kolo: odkazy [P03] -> #číslo
    $map = @{}; $tickets | ForEach-Object { $map[$_.Code] = $_.Number }
    foreach ($t in $tickets) {
        $body = [regex]::Replace($t.Body, '\[(P\d{2})\]', { param($m) "$($m.Groups[1].Value) (#$($map[$m.Groups[1].Value]))" })
        if ($body -ne $t.Body) {
            [IO.File]::WriteAllText($tmp, $body, $utf8)
            gh issue edit $t.Number --body-file $tmp | Out-Null
        }
    }
}
finally { Remove-Item $tmp -ErrorAction SilentlyContinue }

Write-Host "`nHotovo. Začnite pridaním štítku ai-agent na $($tickets[0].Code) (#$($tickets[0].Number))." -ForegroundColor Green
