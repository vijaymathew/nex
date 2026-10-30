<#
.SYNOPSIS
    Installs Nex on Windows.

.DESCRIPTION
    Downloads the prebuilt Nex release from GitHub (the latest, or -Version),
    checks it against the release's published SHA256SUMS, unpacks it into
    -InstallDir and adds its bin folder to your PATH. Nex needs Java 17 or
    later; when there is none, the installer offers to install one with
    winget.

    Run from inside an unpacked release folder, it installs that folder
    instead of downloading one.

    Usage, in PowerShell:

        irm https://raw.githubusercontent.com/vijaymathew/nex/main/install.ps1 | iex

    With options:

        & ([scriptblock]::Create((irm https://raw.githubusercontent.com/vijaymathew/nex/main/install.ps1))) -Version 0.6.6

    or set NEX_VERSION, NEX_INSTALL_DIR or NEX_INSTALL_DEPS=1 before the
    one-line form.

    Works in Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER Version
    The release to install, such as 0.6.6 (default: the latest).

.PARAMETER InstallDir
    Where to install Nex (default: %LOCALAPPDATA%\Programs\nex).

.PARAMETER InstallDeps
    Install Java with winget, without asking, when no Java 17+ is found.

.PARAMETER NoModifyPath
    Do not add Nex's bin folder to your user PATH.

.PARAMETER Uninstall
    Remove Nex from -InstallDir and its bin folder from your user PATH.
#>
param(
    [string]$Version = $env:NEX_VERSION,
    [string]$InstallDir = $env:NEX_INSTALL_DIR,
    [switch]$InstallDeps,
    [switch]$NoModifyPath,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell's download progress bar makes downloads many times slower.
$ProgressPreference = 'SilentlyContinue'

$RepoOwner = if ($env:NEX_REPO_OWNER) { $env:NEX_REPO_OWNER } else { 'vijaymathew' }
$RepoName = if ($env:NEX_REPO_NAME) { $env:NEX_REPO_NAME } else { 'nex' }
$MinJava = 17
# What a release folder holds; only these are removed on upgrade or uninstall.
$NexItems = @('bin', 'deps', 'lib', 'nex.jar', 'classpath', 'aot-training.nex',
              'LICENSE', 'README.md', 'install.sh', 'install.ps1')

if ($env:NEX_INSTALL_DEPS -eq '1') { $InstallDeps = $true }
if (-not $InstallDir) { $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\nex' }
if (-not $Version) { $Version = 'latest' }

function Write-Step([string]$Message) { Write-Host $Message }
function Write-Ok([string]$Message) { Write-Host "  + $Message" -ForegroundColor Green }

# ---------------------------------------------------------------- downloads

# Windows PowerShell 5.1 does not offer TLS 1.2, which GitHub requires, unless
# asked to.
if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

# The HTTP status of a failed web request, or 0 when it never got one.
function Get-HttpStatus($ErrorRecord) {
    $response = $ErrorRecord.Exception.Response
    if ($response) { return [int]$response.StatusCode }
    return 0
}

# Download Url to OutFile. Returns $false when the server has no such file
# (HTTP 404); any other failure stops the install.
function Save-Url([string]$Url, [string]$OutFile) {
    try {
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
        return $true
    } catch {
        if ((Get-HttpStatus $_) -eq 404) { return $false }
        throw "could not download ${Url}: $($_.Exception.Message)"
    }
}

function Get-ReleaseTag {
    if ($Version -ne 'latest') { return 'v' + $Version.TrimStart('v') }
    $api = "https://api.github.com/repos/$RepoOwner/$RepoName/releases/latest"
    try {
        $release = Invoke-RestMethod -Uri $api -UseBasicParsing
    } catch {
        throw "could not find the latest Nex release on GitHub ($($_.Exception.Message)). Name one with -Version."
    }
    if (-not $release.tag_name) { throw 'could not read the latest Nex release''s tag from GitHub.' }
    return [string]$release.tag_name
}

# Check Archive against release Tag's SHA256SUMS. A release that published
# none is installed with a warning; a mismatch stops the install.
function Test-Checksum([string]$Tag, [string]$Archive, [string]$TempDir) {
    $name = Split-Path $Archive -Leaf
    $sums = Join-Path $TempDir 'SHA256SUMS'
    $url = "https://github.com/$RepoOwner/$RepoName/releases/download/$Tag/SHA256SUMS"
    if (-not (Save-Url $url $sums)) {
        Write-Warning "release $Tag publishes no checksums; installing without checking one."
        return
    }
    $expected = $null
    foreach ($line in Get-Content $sums) {
        $parts = $line -split '\s+', 2
        if ($parts.Count -eq 2 -and $parts[1].TrimStart('*') -eq $name) { $expected = $parts[0] }
    }
    if (-not $expected) { throw "release $Tag's SHA256SUMS has no entry for $name." }
    $actual = (Get-FileHash -Path $Archive -Algorithm SHA256).Hash
    if ($actual -ne $expected) {
        throw "$name does not match its published checksum; it may be damaged.`n  expected $expected`n  got      $actual"
    }
    Write-Ok 'Checksum OK'
}

# Download and unpack release Tag into TempDir; returns the unpacked folder.
function Get-Release([string]$Tag, [string]$TempDir) {
    $ver = $Tag.TrimStart('v')
    $zip = Join-Path $TempDir "nex-$ver.zip"
    $url = "https://github.com/$RepoOwner/$RepoName/releases/download/$Tag/nex-$ver.zip"
    Write-Step "Downloading Nex $ver from $url"
    if (-not (Save-Url $url $zip)) {
        throw ("release $Tag has no prebuilt Windows archive (releases before 0.6.5 have none). " +
               'Install a newer one with -Version, or use the Linux installer in WSL.')
    }
    Test-Checksum $Tag $zip $TempDir
    $unpacked = Join-Path $TempDir 'unpacked'
    Expand-Archive -Path $zip -DestinationPath $unpacked
    $folders = @(Get-ChildItem -Path $unpacked -Directory)
    if ($folders.Count -ne 1 -or -not (Test-Path (Join-Path $folders[0].FullName 'nex.jar'))) {
        throw "the downloaded archive is not a Nex release."
    }
    return $folders[0].FullName
}

# --------------------------------------------------------------------- Java

function Get-JavaExe {
    if ($env:JAVA_HOME) {
        $exe = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $exe) { return $exe }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    return $null
}

# Java's major version (21 for 21.0.5, 8 for 1.8.0), or 0 when unknown.
function Get-JavaMajor([string]$JavaExe) {
    $text = $null
    $release = Join-Path (Split-Path (Split-Path $JavaExe)) 'release'
    if (Test-Path $release) {
        $line = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
        if ($line) { $text = $line.Matches[0].Groups[1].Value }
    }
    if (-not $text) {
        # java -version prints to stderr, which Windows PowerShell turns into
        # errors under 'Stop'.
        $ErrorActionPreference = 'Continue'
        $out = (& $JavaExe -version 2>&1 | Out-String)
        if ($out -match 'version "([^"]+)"') { $text = $Matches[1] }
    }
    if (-not $text) { return 0 }
    $parts = $text -split '[.+_-]'
    if ($parts[0] -eq '1' -and $parts.Count -gt 1) { return [int]$parts[1] }
    return [int]$parts[0]
}

# Make PATH and JAVA_HOME in this session match the registry, after winget
# changed them for new sessions only.
function Update-SessionEnvironment {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = (@($machine, $user) | Where-Object { $_ }) -join ';'
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')) {
        $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    }
    if ($javaHome) { $env:JAVA_HOME = $javaHome }
}

function Confirm-Install([string]$What) {
    if ($InstallDeps) { return $true }
    try {
        $answer = Read-Host "Would you like to install $What with winget? (y/n)"
    } catch {
        return $false   # no console to ask on
    }
    return ($answer -match '^[Yy]')
}

function Install-Java {
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        throw "winget is not available to install Java. Install Java $MinJava or later from https://adoptium.net/ and run the installer again."
    }
    # Newest first: both are long-term-support releases.
    foreach ($id in @('EclipseAdoptium.Temurin.25.JDK', 'EclipseAdoptium.Temurin.21.JDK')) {
        Write-Step "Installing Java ($id)..."
        & winget.exe install --id $id --exact --silent --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -eq 0) {
            Update-SessionEnvironment
            Write-Ok 'Java installed'
            return
        }
    }
    throw "winget could not install Java. Install Java $MinJava or later from https://adoptium.net/ and run the installer again."
}

function Assert-Java {
    $exe = Get-JavaExe
    $major = if ($exe) { Get-JavaMajor $exe } else { 0 }
    if ($exe -and ($major -ge $MinJava -or $major -eq 0)) {
        if ($major -gt 0) { Write-Ok "Java $major" } else { Write-Ok "Java ($exe)" }
        return
    }
    if ($exe) {
        Write-Host "Java $major is installed, but Nex needs Java $MinJava or later."
    } else {
        Write-Host "Java is not installed. Nex needs Java $MinJava or later."
    }
    if (-not (Confirm-Install 'Java')) {
        throw "install Java $MinJava or later (https://adoptium.net/) and run the installer again, or re-run it with -InstallDeps."
    }
    Install-Java
    $exe = Get-JavaExe
    $major = if ($exe) { Get-JavaMajor $exe } else { 0 }
    if (-not $exe -or ($major -gt 0 -and $major -lt $MinJava)) {
        throw 'Java was installed, but this session cannot see it yet. Open a new terminal and run the installer again.'
    }
    Write-Ok "Java $major"
}

# --------------------------------------------------------------------- PATH

# The user PATH as stored, without expanding %VARIABLES% in it, so writing
# it back does not freeze them.
function Get-UserPathRaw {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
    if (-not $key) { return '' }
    try {
        return [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    } finally {
        $key.Close()
    }
}

function Set-UserPathRaw([string]$Value) {
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
    try {
        $key.SetValue('Path', $Value, [Microsoft.Win32.RegistryValueKind]::ExpandString)
    } finally {
        $key.Close()
    }
    # Setting a variable through .NET tells running programs (Explorer, new
    # terminals) that the environment changed; the registry write alone
    # does not.
    [Environment]::SetEnvironmentVariable('NEX_INSTALLER_REFRESH', '1', 'User')
    [Environment]::SetEnvironmentVariable('NEX_INSTALLER_REFRESH', $null, 'User')
}

function Test-SamePath([string]$A, [string]$B) {
    return ($A.TrimEnd('\') -eq $B.TrimEnd('\'))   # -eq ignores case
}

function Add-ToUserPath([string]$Dir) {
    $entries = @((Get-UserPathRaw) -split ';' | Where-Object { $_ })
    if (-not ($entries | Where-Object { Test-SamePath ([Environment]::ExpandEnvironmentVariables($_)) $Dir })) {
        Set-UserPathRaw (($entries + $Dir) -join ';')
        Write-Ok "Added $Dir to your user PATH"
    }
    if (-not (($env:Path -split ';') | Where-Object { $_ -and (Test-SamePath $_ $Dir) })) {
        $env:Path = "$Dir;$env:Path"
    }
}

function Remove-FromUserPath([string]$Dir) {
    $entries = @((Get-UserPathRaw) -split ';' | Where-Object { $_ })
    $kept = @($entries | Where-Object { -not (Test-SamePath ([Environment]::ExpandEnvironmentVariables($_)) $Dir) })
    if ($kept.Count -ne $entries.Count) {
        Set-UserPathRaw ($kept -join ';')
        Write-Ok "Removed $Dir from your user PATH"
    }
}

# ------------------------------------------------------------------ install

# Remove the files a Nex release put in InstallDir, and nothing else.
function Remove-NexFiles {
    if (-not (Test-Path $InstallDir)) { return }
    foreach ($item in $NexItems) {
        $path = Join-Path $InstallDir $item
        if (Test-Path $path) {
            try {
                Remove-Item -Path $path -Recurse -Force
            } catch {
                throw "could not remove $path ($($_.Exception.Message)). Close any running Nex program or editor using it and try again."
            }
        }
    }
}

function Install-Folder([string]$Source) {
    $existing = @(Get-ChildItem -Path $InstallDir -Force -ErrorAction SilentlyContinue)
    if ($existing.Count -gt 0 -and -not (Test-Path (Join-Path $InstallDir 'nex.jar'))) {
        throw "$InstallDir is not empty and does not hold a Nex installation; choose another folder with -InstallDir."
    }
    Remove-NexFiles
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    Get-ChildItem -Path $Source -Force |
        Where-Object { $_.Name -ne 'install.sh' } |
        Copy-Item -Destination $InstallDir -Recurse -Force
    Write-Ok "Installed Nex to $InstallDir"
}

# Run a real program through the installed command, so a broken install is
# reported here rather than on the user's first program.
function Test-Install {
    Write-Step 'Verifying installation...'
    $nex = Join-Path $InstallDir 'bin\nex.cmd'
    $ErrorActionPreference = 'Continue'
    $out = @(& $nex eval 'print(6 * 7)' 2>&1 | ForEach-Object { "$_".Trim() })
    if ($LASTEXITCODE -ne 0 -or -not ($out -contains '42')) {
        throw ("nex was installed to $InstallDir, but it could not run a test program. Its output was:`n    " +
               ($out -join "`n    ") +
               "`nPlease report this at https://github.com/$RepoOwner/$RepoName/discussions")
    }
    Write-Ok 'nex runs programs'
}

function Install-Nex {
    Write-Host "Nex installer for Windows"
    Write-Host "Install folder: $InstallDir"
    Write-Host ''

    Write-Step 'Checking prerequisites...'
    Assert-Java
    Write-Host ''

    $tempDir = Join-Path ([IO.Path]::GetTempPath()) ('nex-install-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    try {
        # A release folder this script was run from is installed as it is.
        $here = $PSScriptRoot
        if ($here -and (Test-Path (Join-Path $here 'nex.jar')) -and (Test-Path (Join-Path $here 'bin\nex.cmd'))) {
            if (Test-SamePath (Resolve-Path $here).Path ([IO.Path]::GetFullPath($InstallDir))) {
                throw "this is the installed copy in $InstallDir; run the installer from somewhere else."
            }
            Write-Step "Installing the release in $here"
            $source = $here
        } else {
            $source = Get-Release (Get-ReleaseTag) $tempDir
        }
        Install-Folder $source
    } finally {
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    $bin = Join-Path $InstallDir 'bin'
    if ($NoModifyPath) {
        Write-Host "Add $bin to your PATH to use 'nex' from anywhere."
    } else {
        Add-ToUserPath $bin
    }
    Write-Host ''
    Test-Install

    Write-Host ''
    Write-Host 'Nex installed successfully.' -ForegroundColor Green
    Write-Host 'Try it out (in a new terminal, if nex is not found in this one):'
    Write-Host '  nex                  # Start REPL'
    Write-Host '  nex help             # Show help'
    Write-Host '  nex eval "print(42)" # Evaluate code'
}

function Uninstall-Nex {
    Write-Step "Removing Nex from $InstallDir..."
    if (-not (Test-Path (Join-Path $InstallDir 'nex.jar'))) {
        Write-Host "No Nex installation found in $InstallDir."
    } else {
        Remove-NexFiles
        if (-not (Get-ChildItem -Path $InstallDir -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -Path $InstallDir -Force
        }
        Write-Ok 'Removed Nex'
    }
    Remove-FromUserPath (Join-Path $InstallDir 'bin')
}

# --------------------------------------------------------------------- main

$isScript = [bool]$PSCommandPath   # empty when run through `irm ... | iex`
try {
    if ($PSVersionTable.PSVersion.Major -ge 6 -and -not $IsWindows) {
        throw 'install.ps1 is for Windows; on Linux and macOS use bootstrap-install.sh.'
    }
    if ($Uninstall) { Uninstall-Nex } else { Install-Nex }
} catch {
    Write-Host ''
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    # exit would close the user's own PowerShell window under `iex`.
    if ($isScript) { exit 1 }
}
