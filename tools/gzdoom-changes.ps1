# GZDoom's own source is not kept in this repository (it is GZDoom's): only what this project changes in it, in
# gzdoom-changes\ (a patch against the commit it is built from, and the files that are new).
#   powershell -File tools\gzdoom-changes.ps1 export    gzdoom\ (the working checkout) -> gzdoom-changes\  (before a commit)
#   powershell -File tools\gzdoom-changes.ps1 apply     a fresh clone of GZDoom at that commit + gzdoom-changes\ -> gzdoom\
param([Parameter(Mandatory = $true)][ValidateSet("export", "apply")][string]$what)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$changes = Join-Path $root "gzdoom-changes"
$checkout = Join-Path $root "gzdoom"

if ($what -eq "export") {
    Push-Location $checkout
    $commit = (& git rev-parse HEAD).Trim()
    if (Test-Path $changes) { [IO.Directory]::Delete($changes, $true) }
    New-Item -ItemType Directory -Force $changes | Out-Null
    & git diff HEAD --output="$changes\gzdoom.patch"
    foreach ($file in (& git ls-files --others --exclude-standard)) {
        if ($file -like "build/*") { continue }
        $target = Join-Path "$changes\new-files" $file
        New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
        Copy-Item $file $target
    }
    Pop-Location
    Set-Content "$changes\COMMIT" $commit -Encoding ascii
    "exported the changes against GZDoom $commit"
} else {
    if (Test-Path $checkout) { throw "gzdoom\ is there already" }
    $commit = (Get-Content "$changes\COMMIT").Trim()
    & git clone https://github.com/ZDoom/gzdoom.git $checkout
    Push-Location $checkout
    & git checkout $commit
    & git apply "$changes\gzdoom.patch"
    Pop-Location
    Copy-Item "$changes\new-files\*" $checkout -Recurse -Force
    "gzdoom\ is GZDoom $commit with this project's changes: tools\build-gzdoom.cmd builds it"
}
