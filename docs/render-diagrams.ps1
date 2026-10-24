# Renders every ```mermaid block in docs/architecture.md to a PNG in docs/images/.
#
# The markdown file is the single source: the diagrams are not kept separately as .mmd files,
# because two copies of a diagram is two diagrams that disagree six months from now. Each PNG is
# named after the "## n. Heading" it appears under.
#
#   ./docs/render-diagrams.ps1
#
# Needs Node (for npx). mermaid-cli pulls a headless Chromium on first use, so the first run is
# slow. Re-run it after editing a diagram; the PNGs are committed so a reader of the README does
# not need any of this.

$ErrorActionPreference = 'Stop'
$docs   = $PSScriptRoot
$source = Join-Path $docs 'architecture.md'
$images = Join-Path $docs 'images'
$tmp    = Join-Path $env:TEMP "spendsense-mermaid"

New-Item -ItemType Directory -Force -Path $images | Out-Null
New-Item -ItemType Directory -Force -Path $tmp    | Out-Null

# A white background and a wide canvas: these diagrams are read embedded in a README, where a
# transparent background turns black under a dark theme and unreadable text is worse than none.
#
# Written without a byte order mark. Windows PowerShell's -Encoding utf8 writes one, and
# mermaid-cli's JSON.parse rejects the file with "Unexpected token '﻿'".
$noBom = New-Object System.Text.UTF8Encoding $false
$config = Join-Path $tmp 'mermaid-config.json'
[IO.File]::WriteAllText($config,
    '{ "theme": "default", "themeVariables": { "fontFamily": "Segoe UI, Helvetica, Arial, sans-serif", "fontSize": "15px" } }',
    $noBom)

# mermaid-cli renders through headless Chrome. Rather than make every run download puppeteer's
# own pinned Chromium (~150MB, and it fails outright behind a proxy), point it at a Chrome that
# is already installed. Without a local Chrome, fall through and let puppeteer find its own -
# `npx puppeteer browsers install chrome` puts one in the cache.
$puppeteerArgs = @()
$chrome = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if ($chrome) {
    Write-Host "Rendering with $chrome"
    $pupCfg = Join-Path $tmp 'puppeteer-config.json'
    [IO.File]::WriteAllText($pupCfg,
        (@{ executablePath = $chrome; args = @('--no-sandbox') } | ConvertTo-Json -Compress), $noBom)
    $puppeteerArgs = @('-p', $pupCfg)
}

# -Encoding UTF8 is not optional. Windows PowerShell's Get-Content defaults to the system ANSI
# codepage, which turns every em dash in the source into three mojibake characters that then get
# rendered into the PNG.
$lines = Get-Content $source -Encoding UTF8
$blocks = @()
$current = $null
$n = 0

foreach ($line in $lines) {
    if ($line -match '^\s*```mermaid\s*$') { $current = New-Object System.Collections.Generic.List[string]; $n++; continue }
    if ($null -ne $current) {
        if ($line -match '^\s*```\s*$') {
            # Each block names its own file with a "%% slug: name" comment on its first line.
            # Mermaid ignores %% comments, so the name lives next to the diagram it names
            # instead of being derived from a heading that someone will reword.
            $slug = "diagram-$n"
            foreach ($l in $current) { if ($l -match '^\s*%%\s*slug:\s*([\w-]+)\s*$') { $slug = $Matches[1]; break } }
            $blocks += [pscustomobject]@{ Name = $slug; Lines = $current.ToArray() }
            $current = $null
        } else {
            $current.Add($line)
        }
    }
}

if ($blocks.Count -eq 0) { throw "No mermaid blocks found in $source" }
Write-Host "Found $($blocks.Count) diagram(s)."

foreach ($b in $blocks) {
    $mmd = Join-Path $tmp "$($b.Name).mmd"
    $png = Join-Path $images "$($b.Name).png"
    [IO.File]::WriteAllLines($mmd, $b.Lines, $noBom)
    Write-Host "  rendering $($b.Name).png"
    # npm writes deprecation notices to stderr, and under $ErrorActionPreference = 'Stop'
    # PowerShell turns a native command's stderr into a terminating NativeCommandError even
    # when the exit code is 0. Judge the run by the exit code instead.
    $ErrorActionPreference = 'Continue'
    npx -y @mermaid-js/mermaid-cli@11.4.2 -i $mmd -o $png -c $config -b white -s 2 @puppeteerArgs 2>&1 |
        Where-Object { $_ -notmatch 'npm warn' } | Out-Host
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "mermaid-cli failed on $($b.Name) (exit $code)" }
}

Write-Host "Wrote $($blocks.Count) PNG(s) to $images"
