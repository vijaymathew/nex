@echo off
rem Nex command-line launcher for Windows (bin/nex is the Linux and macOS
rem one). It finds Nex and Java, puts Nex's jars and any --classpath entries
rem on the JVM classpath, and starts nex.cli, which handles the command line
rem itself (see src/nex/cli.clj).
rem
rem It runs a prebuilt Nex release (scripts/package-release.sh): this
rem script's parent folder holds nex.jar, its dependency jars in deps\, and
rem a "classpath" file listing them. Only Java 17 or later is needed.
rem NEX_HOME, when set, names the folder to use instead.
rem
rem Keep this file's line endings CRLF (see .gitattributes): cmd.exe can
rem miss labels in a batch file with bare LF endings.

setlocal EnableExtensions DisableDelayedExpansion

set "MIN_JAVA=17"

if not defined NEX_HOME for %%I in ("%~dp0..") do set "NEX_HOME=%%~fI"
if "%NEX_HOME:~-1%"=="\" set "NEX_HOME=%NEX_HOME:~0,-1%"
if not exist "%NEX_HOME%\nex.jar" goto no_nex
if not exist "%NEX_HOME%\classpath" goto no_nex

rem JAVA_HOME's Java when it has one, else java.exe on PATH.
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE goto no_java

rem Java's version, from the JDK's "release" file when there is one, which
rem is much cheaper than starting a JVM to ask it. findstr picks out the
rem one line, since others in that file can be too long for for /f.
set "JAVA_VERSION="
set "JAVA_MAJOR="
for %%J in ("%JAVA_EXE%") do set "JAVA_RELEASE=%%~dpJ..\release"
if exist "%JAVA_RELEASE%" for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"JAVA_VERSION=" "%JAVA_RELEASE%" 2^>nul') do set "JAVA_VERSION=%%~B"
if not defined JAVA_VERSION for /f "tokens=3" %%V in ('call "%JAVA_EXE%" -version 2^>^&1 ^| findstr /i /c:"version"') do set "JAVA_VERSION=%%~V"
rem The major number: 21 for 21.0.5, 8 for 1.8.0_402.
if defined JAVA_VERSION for /f "tokens=1,2 delims=.-+_" %%A in ("%JAVA_VERSION%") do (
    if "%%A"=="1" (set "JAVA_MAJOR=%%B") else set "JAVA_MAJOR=%%A"
)
if defined JAVA_MAJOR if %JAVA_MAJOR% LSS %MIN_JAVA% goto old_java

rem Nex's own jars, listed relative to NEX_HOME in its classpath file.
set "NEX_CP="
for /f "usebackq delims=" %%L in ("%NEX_HOME%\classpath") do call :add_to_cp "%NEX_HOME%\%%L"

rem The value of the last --classpath option, if any, with each relative
rem entry made absolute: the JVM runs in NEX_HOME, but the entries are
rem relative to the folder nex was started from. It goes on the real JVM
rem classpath from process start, so both compile-time interop resolution
rem and runtime class linkage see it; nex.cli drops the option itself. Its
rem entries are separated by ";", so it must be quoted:
rem   nex app.nex --classpath "a.jar;b.jar"
rem (shift leaves %* below untouched).
set "CLASSPATH_ARG="
:scan_args
if "%~1"=="" goto args_scanned
if /i "%~1"=="--classpath" if not "%~2"=="" set "CLASSPATH_ARG=%~2"
shift
goto scan_args
:args_scanned
set "EXTRA_CP="
:split_classpath
if not defined CLASSPATH_ARG goto classpath_split
rem for /f on a quoted string, unlike plain for, never globs "dir\*".
set "CLASSPATH_REST=%CLASSPATH_ARG%"
set "CLASSPATH_ARG="
for /f "eol=| tokens=1,* delims=;" %%A in ("%CLASSPATH_REST%") do (
    call :add_extra_cp "%%A"
    set "CLASSPATH_ARG=%%B"
)
goto split_classpath
:classpath_split

rem Relative paths on the command line are resolved by nex.cli against this
rem folder, since the JVM runs in NEX_HOME; programs see it as their working
rem directory.
set "NEX_USER_DIR=%CD%"
set "USER_DIR_ARG=%CD%"
if "%USER_DIR_ARG:~-1%"=="\" set "USER_DIR_ARG=%USER_DIR_ARG%."

rem Extra JVM options, written -J<option> as for bin/nex.
if not defined J_OPTIONS set "J_OPTIONS=-J--enable-native-access=ALL-UNNAMED"
set "JVM_OPTS=%J_OPTIONS:-J=%"

pushd "%NEX_HOME%"
"%JAVA_EXE%" -XX:+UseSerialGC %JVM_OPTS% "-Dnex.user.dir=%USER_DIR_ARG%" "-Dnex.home=%NEX_HOME%" -cp "%NEX_CP%%EXTRA_CP%" clojure.main -m nex.cli %*
set "STATUS=%ERRORLEVEL%"
popd
exit /b %STATUS%

:add_to_cp
if defined NEX_CP (set "NEX_CP=%NEX_CP%;%~1") else set "NEX_CP=%~1"
exit /b 0

rem Add %1 to EXTRA_CP, as a full path (%~f1 resolves it against the current
rem folder, which is still the one nex was started from). A trailing
rem backslash would escape the closing quote on the java command line.
:add_extra_cp
set "ENTRY=%~f1"
if "%ENTRY:~-1%"=="\" set "ENTRY=%ENTRY%."
set "EXTRA_CP=%EXTRA_CP%;%ENTRY%"
exit /b 0

:no_nex
echo Error: no Nex installation found in %NEX_HOME% 1>&2
echo (expected nex.jar and classpath from a Nex release). 1>&2
exit /b 1

:no_java
echo Error: Nex needs Java %MIN_JAVA% or later, and no Java was found. 1>&2
echo Install one from https://adoptium.net/ (or set JAVA_HOME). 1>&2
exit /b 1

:old_java
echo Error: Nex needs Java %MIN_JAVA% or later, but %JAVA_EXE% is Java %JAVA_VERSION%. 1>&2
echo Install a newer one from https://adoptium.net/ (or set JAVA_HOME). 1>&2
exit /b 1
