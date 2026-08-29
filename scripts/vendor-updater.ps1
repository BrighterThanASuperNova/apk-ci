<#
.SYNOPSIS
  Vendor the canonical updater sources from apk-ci into an app repo.

.DESCRIPTION
  The updater is vendored rather than published as an AAR or wired as a
  submodule, because the agent has to be able to read and reason about it in
  the repo it is editing. CI diffs each vendored copy against this canonical
  copy, ignoring line 1 (the package declaration) - which is exactly the line
  this script rewrites.

  Run this after ANY change to shared/android/*.kt, for all three apps in the
  same pass, or CI will fail the drift check.

.EXAMPLE
  .\scripts\vendor-updater.ps1 -AppDir '..\Personal Android App Development\AppBlocker' -Package com.example.appblocker
#>
param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [Parameter(Mandatory = $true)][string]$Package
)

$ErrorActionPreference = 'Stop'

$canonicalDir = Join-Path $PSScriptRoot '..\shared\android'
if (-not (Test-Path $canonicalDir)) { throw "canonical sources not found at $canonicalDir" }

$targetDir = Join-Path $AppDir ("app\src\main\java\" + ($Package -replace '\.', '\') + "\update")
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

Get-ChildItem $canonicalDir -Filter *.kt | ForEach-Object {
    $lines = Get-Content $_.FullName
    if ($lines[0] -notmatch '^package\s') {
        throw "$($_.Name): line 1 must be the package declaration (the drift check strips exactly one line)"
    }
    # Rewrite only line 1; every other byte must match canonical.
    $lines[0] = "package $Package.update"
    $dest = Join-Path $targetDir $_.Name
    # LF endings: these are compared byte-for-byte against canonical on an
    # ubuntu runner, where CRLF would fail the diff.
    $text = ($lines -join "`n") + "`n"
    [IO.File]::WriteAllText($dest, $text, (New-Object Text.UTF8Encoding $false))
    Write-Output "vendored $($_.Name) -> $dest"
}

Write-Output ""
Write-Output "Remember: AppUpdater must be called from MainActivity."
Write-Output "  import $Package.update.AppUpdater"
Write-Output "  override fun onResume() { super.onResume(); AppUpdater.checkOnResume(this, BuildConfig.UPDATE_MANIFEST_URL) }"
