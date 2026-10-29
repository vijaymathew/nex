#!/bin/bash
# Build the prebuilt Nex release, which needs only Java 17+ to run:
#
#   target/release/nex-<version>/          the release folder
#   target/release/nex-<version>.tar.gz    it, for Linux and macOS
#   target/release/nex-<version>.zip       it, for Windows
#
# The folder holds:
#
#   bin/nex, bin/nex.cmd   the launchers (bash; Windows cmd)
#   nex.jar                Nex, AOT-compiled (scripts/build-dist.clj)
#   deps/*.jar, classpath  its dependency jars, and the list of all jars
#   lib/                   the Nex libraries that ship with Nex
#   aot-training.nex       the program the launcher trains its JVM cache on
#   install.sh             installs this folder under a prefix (Linux, macOS)
#   install.ps1            installs this folder for the current user (Windows)
#
# and target/release/SHA256SUMS lists the archives' checksums, which
# bootstrap-install.sh checks.
#
# The same files run on every platform: the jars are plain JVM bytecode, and
# JLine carries its own native terminal support for each OS.
#
# Usage: scripts/package-release.sh [--no-archives]
#   --no-archives   build only the folder (install.sh uses this)

set -euo pipefail
cd "$(dirname "$0")/.."

ARCHIVES=true
for arg in "$@"; do
    case "$arg" in
        --no-archives) ARCHIVES=false ;;
        *) echo "Usage: scripts/package-release.sh [--no-archives]" >&2; exit 1 ;;
    esac
done

VERSION="$(sed -n 's/^VERSION="\(.*\)"/\1/p' bin/nex)"
NAME="nex-$VERSION"
OUT="target/release/$NAME"

rm -rf target/dist target/release
clojure -M:dist
mkdir -p "$OUT/bin"

cp bin/nex "$OUT/bin/nex"
chmod 755 "$OUT/bin/nex"
# cmd.exe misreads labels in a batch file with bare LF line endings.
sed 's/\r*$/\r/' bin/nex.cmd > "$OUT/bin/nex.cmd"

cp target/dist/nex.jar target/dist/classpath "$OUT/"
cp -R target/dist/deps "$OUT/deps"
cp -R lib "$OUT/lib"
cp scripts/aot-training.nex LICENSE README.md install.sh install.ps1 "$OUT/"

if [[ "$ARCHIVES" == true ]]; then
    (
        cd target/release
        tar -czf "$NAME.tar.gz" "$NAME"
        rm -f "$NAME.zip"
        zip -qr "$NAME.zip" "$NAME"
        if command -v sha256sum > /dev/null; then
            sha256sum "$NAME.tar.gz" "$NAME.zip" > SHA256SUMS
        else
            shasum -a 256 "$NAME.tar.gz" "$NAME.zip" > SHA256SUMS
        fi
    )
    echo "Built target/release/$NAME.tar.gz, $NAME.zip and SHA256SUMS"
else
    echo "Built $OUT"
fi
