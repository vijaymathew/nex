<#
  Tests install.ps1 on a Windows machine (.github/workflows/release.yml runs
  it in both Windows PowerShell 5.1 and PowerShell 7), against the release
  archives in -Artifacts, without GitHub: Invoke-WebRequest and
  Invoke-RestMethod are replaced by functions of the same name that serve
  those files, which install.ps1 calls instead of the real cmdlets.

  It changes the current user's PATH (and puts it back), so run it on a
  throwaway machine such as a CI runner.

    ./test/release/install-ps1-test.ps1 -Artifacts artifacts -Installer ./install.ps1 -ReleaseDir dist/nex
#>
param(
    [Parameter(Mandatory = $true)][string]$Artifacts,   # nex-<version>.zip and SHA256SUMS
    [Parameter(Mandatory = $true)][string]$Installer,   # install.ps1 to test
    [Parameter(Mandatory = $true)][string]$ReleaseDir   # an unpacked release folder
)

$ErrorActionPreference = 'Stop'
$Artifacts = (Resolve-Path $Artifacts).Path
$Installer = (Resolve-Path $Installer).Path
$ReleaseDir = (Resolve-Path $ReleaseDir).Path
$script:Served = $Artifacts
$failures = 0

# What install.ps1 sees when a release has no such file.
class FakeHttpNotFound : System.Exception {
    [object]$Response
    FakeHttpNotFound([string]$Message) : base($Message) {
        $this.Response = [pscustomobject]@{ StatusCode = 404 }
    }
}

function Invoke-RestMethod {
    param([string]$Uri, [switch]$UseBasicParsing)
    $zip = Get-ChildItem -Path $script:Served -Filter 'nex-*.zip' | Select-Object -First 1
    return [pscustomobject]@{ tag_name = 'v' + $zip.BaseName.Substring(4) }
}

function Invoke-WebRequest {
    param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
    $file = Join-Path $script:Served ($Uri.Substring($Uri.LastIndexOf('/') + 1))
    if (-not (Test-Path $file)) { throw [FakeHttpNotFound]::new("404 Not Found: $Uri") }
    Copy-Item -Path $file -Destination $OutFile
}

function Check([bool]$Condition, [string]$What) {
    if ($Condition) {
        Write-Host "ok - $What" -ForegroundColor Green
    } else {
        Write-Host "FAILED - $What" -ForegroundColor Red
        $script:failures++
    }
}

function Get-UserPath {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
    if (-not $key) { return '' }
    try { return [string]$key.GetValue('Path', '', 'DoNotExpandEnvironmentNames') } finally { $key.Close() }
}

function Test-OnUserPath([string]$Dir) {
    return [bool]((Get-UserPath) -split ';' | Where-Object { $_.TrimEnd('\') -eq $Dir.TrimEnd('\') })
}

# Run install.ps1 with the named PARAMETERS; returns its exit status (0 when
# it succeeded: it exits 1 on failure).
function Invoke-Installer([string]$Script, [hashtable]$Parameters) {
    $global:LASTEXITCODE = 0
    & $Script @Parameters | Out-Host
    return $global:LASTEXITCODE
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('nex-ps1-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$savedPath = Get-UserPath
try {
    Write-Host "== download, check and install the latest release"
    $dir = Join-Path $root 'Nex Install (x86)'
    $status = Invoke-Installer $Installer @{ InstallDir = $dir }
    Check ($status -eq 0) 'install.ps1 succeeds'
    Check (Test-Path (Join-Path $dir 'nex.jar')) 'nex.jar is installed'
    Check (-not (Test-Path (Join-Path $dir 'install.sh'))) 'the Linux install.sh is left out'
    Check (Test-OnUserPath (Join-Path $dir 'bin')) 'bin is on the user PATH'
    $out = @(& nex.cmd eval 'print(5 * 5)' 2>&1 | ForEach-Object { "$_".Trim() })
    Check ($out -contains '25') "nex runs from PATH in this session (got: $($out -join ' | '))"

    Write-Host "== install again over it (an upgrade)"
    $status = Invoke-Installer $Installer @{ InstallDir = $dir }
    Check ($status -eq 0) 'reinstalling succeeds'
    $entries = @((Get-UserPath) -split ';' | Where-Object { $_.TrimEnd('\') -eq (Join-Path $dir 'bin') })
    Check ($entries.Count -eq 1) 'bin is on the user PATH once'

    Write-Host "== a damaged archive is refused"
    $bad = Join-Path $root 'bad-artifacts'
    Copy-Item -Path $Artifacts -Destination $bad -Recurse
    $zip = Get-ChildItem -Path $bad -Filter 'nex-*.zip' | Select-Object -First 1
    [IO.File]::AppendAllText($zip.FullName, 'junk')
    $script:Served = $bad
    $dir2 = Join-Path $root 'damaged'
    $status = Invoke-Installer $Installer @{ InstallDir = $dir2; NoModifyPath = $true }
    Check ($status -ne 0) 'install.ps1 fails'
    Check (-not (Test-Path (Join-Path $dir2 'nex.jar'))) 'nothing is installed'
    $script:Served = $Artifacts

    Write-Host "== a release without a Windows archive"
    $status = Invoke-Installer $Installer @{ InstallDir = (Join-Path $root 'old'); Version = '0.0.1'; NoModifyPath = $true }
    Check ($status -ne 0) 'install.ps1 fails'

    Write-Host "== a folder that is not a Nex install is left alone"
    $foreign = Join-Path $root 'foreign'
    New-Item -ItemType Directory -Path $foreign | Out-Null
    Set-Content -Path (Join-Path $foreign 'notes.txt') -Value 'keep me'
    $status = Invoke-Installer $Installer @{ InstallDir = $foreign; NoModifyPath = $true }
    Check ($status -ne 0) 'install.ps1 fails'
    Check (Test-Path (Join-Path $foreign 'notes.txt')) 'its files are kept'

    Write-Host "== install.ps1 inside a release folder installs that folder"
    $dir3 = Join-Path $root 'from-folder'
    $status = Invoke-Installer (Join-Path $ReleaseDir 'install.ps1') @{ InstallDir = $dir3; NoModifyPath = $true }
    Check ($status -eq 0) 'install.ps1 succeeds'
    Check (Test-Path (Join-Path $dir3 'bin\nex.cmd')) 'nex.cmd is installed'
    Check (-not (Test-OnUserPath (Join-Path $dir3 'bin'))) '-NoModifyPath leaves PATH alone'

    Write-Host "== uninstall"
    Set-Content -Path (Join-Path $dir 'my-notes.txt') -Value 'not Nex''s'
    $status = Invoke-Installer $Installer @{ InstallDir = $dir; Uninstall = $true }
    Check ($status -eq 0) 'install.ps1 -Uninstall succeeds'
    Check (-not (Test-Path (Join-Path $dir 'nex.jar'))) 'Nex is removed'
    Check (Test-Path (Join-Path $dir 'my-notes.txt')) 'other files in the folder are kept'
    Check (-not (Test-OnUserPath (Join-Path $dir 'bin'))) 'bin is off the user PATH'
} finally {
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
    $key.SetValue('Path', $savedPath, 'ExpandString')
    $key.Close()
    Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures -gt 0) {
    Write-Host "$failures check(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host 'All install.ps1 checks passed' -ForegroundColor Green
