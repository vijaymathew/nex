#!/usr/bin/env bash
set -euo pipefail

REPO_OWNER="${NEX_REPO_OWNER:-vijaymathew}"
REPO_NAME="${NEX_REPO_NAME:-nex}"
VERSION="${NEX_VERSION:-latest}"
# Setting NEX_INSTALL_REF asks for a build from source, as it always has.
REPO_REF="${NEX_INSTALL_REF:-}"
FROM_SOURCE=false
[[ -n "$REPO_REF" ]] && FROM_SOURCE=true
INSTALL_DEPS=false
PASS_THROUGH_ARGS=()

usage() {
    cat <<'EOF'
Bootstrap installer for Nex.

By default this downloads the prebuilt Nex release from GitHub, which needs
only Java 17 or later, checks it against the release's published checksums,
and runs the install.sh inside it. With --from-source it instead downloads
a source archive and builds Nex from it, which also needs the Clojure CLI.

Usage:
  ./bootstrap-install.sh [jvm] [--version <version>] [--install-deps] [--prefix <dir>]
  ./bootstrap-install.sh [jvm] --from-source [--ref <git-ref>] [--install-deps] [--prefix <dir>]

Options:
  --version <version>  Release to install, e.g. 0.6.5 (default: the latest)
  --from-source        Build and install from source instead of the prebuilt
                       release: the main branch, or --ref
  --ref <git-ref>      Branch, tag, or commit to build, e.g. v0.6.4
                       (implies --from-source)
  --install-deps       Install missing prerequisites without asking
  --prefix <dir>       Install under <dir> (default: /usr/local)

Examples:
  ./bootstrap-install.sh
  ./bootstrap-install.sh --install-deps
  ./bootstrap-install.sh --prefix "$HOME/.local"
  ./bootstrap-install.sh --version 0.6.5
  ./bootstrap-install.sh --from-source
  ./bootstrap-install.sh --from-source --ref v0.6.4

A release without a prebuilt archive (0.6.4 and earlier) is built from
source instead, after saying so.

Environment overrides:
  NEX_REPO_OWNER   GitHub owner/user (default: vijaymathew)
  NEX_REPO_NAME    GitHub repo name (default: nex)
  NEX_VERSION      Same as --version
  NEX_INSTALL_REF  Same as --ref
EOF
}

require_command() {
    local command_name="$1"

    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Error: required command not found: $command_name" >&2
        exit 1
    fi
}

# Download URL to OUTPUT; fails (without printing anything) when the URL
# cannot be fetched. Returns 4 when the server says there is no such file
# (curl's exit 22 and wget's 8 are an HTTP error response), so a release
# without a prebuilt archive can be told apart from a failed download.
download() {
    local url="$1"
    local output="$2"
    local status=0

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$output" 2>/dev/null || status=$?
        [[ $status -eq 22 ]] && return 4
        return $status
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$output" "$url" || status=$?
        [[ $status -eq 8 ]] && return 4
        return $status
    else
        echo "Error: either curl or wget is required to download Nex." >&2
        exit 1
    fi
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            jvm)
                PASS_THROUGH_ARGS+=("$1")
                shift
                ;;
            --install-deps)
                INSTALL_DEPS=true
                PASS_THROUGH_ARGS+=("$1")
                shift
                ;;
            --prefix)
                if [[ $# -lt 2 ]]; then
                    echo "Error: --prefix requires a directory argument." >&2
                    exit 1
                fi
                PASS_THROUGH_ARGS+=("$1" "$2")
                shift 2
                ;;
            --version)
                if [[ $# -lt 2 ]]; then
                    echo "Error: --version requires a version, such as 0.6.5." >&2
                    exit 1
                fi
                VERSION="$2"
                shift 2
                ;;
            --from-source)
                FROM_SOURCE=true
                shift
                ;;
            --ref)
                if [[ $# -lt 2 ]]; then
                    echo "Error: --ref requires a git ref." >&2
                    exit 1
                fi
                REPO_REF="$2"
                FROM_SOURCE=true
                shift 2
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                PASS_THROUGH_ARGS+=("$1")
                shift
                ;;
        esac
    done
}

# The release tag to install: v<VERSION>, or the latest release's tag.
release_tag() {
    if [[ "$VERSION" != latest ]]; then
        echo "v${VERSION#v}"
        return
    fi
    local json="$temp_dir/latest.json" tag
    if ! download "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest" "$json"; then
        echo "Error: could not find the latest Nex release on GitHub." >&2
        echo "Name one with --version, or build from source with --from-source --ref main." >&2
        exit 1
    fi
    tag="$(sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' "$json" | head -n 1)"
    if [[ -z "$tag" ]]; then
        echo "Error: could not read the latest Nex release's tag from GitHub." >&2
        exit 1
    fi
    echo "$tag"
}

# Check ARCHIVE against the release's SHA256SUMS. A release that published
# no checksums is installed with a warning; a mismatch stops the install.
verify_checksum() {
    local tag="$1" archive="$2"
    local name sums="$temp_dir/SHA256SUMS" expected actual
    name="$(basename "$archive")"

    if ! download "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${tag}/SHA256SUMS" "$sums"; then
        echo "Warning: release ${tag} publishes no checksums; installing without checking one." >&2
        return
    fi
    expected="$(awk -v n="$name" '$2 == n || $2 == "*" n { print $1 }' "$sums" | head -n 1)"
    if [[ -z "$expected" ]]; then
        echo "Error: release ${tag}'s SHA256SUMS has no entry for ${name}." >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        actual="$(sha256sum "$archive" | awk '{ print $1 }')"
    elif command -v shasum >/dev/null 2>&1; then
        actual="$(shasum -a 256 "$archive" | awk '{ print $1 }')"
    else
        echo "Warning: no sha256sum or shasum found; installing without checking the checksum." >&2
        return
    fi
    if [[ "$actual" != "$expected" ]]; then
        echo "Error: ${name} does not match its published checksum; it may be damaged." >&2
        echo "  expected ${expected}" >&2
        echo "  got      ${actual}" >&2
        exit 1
    fi
    echo "Checksum OK"
}

# Run the install.sh in the one folder the archive in temp_dir unpacked to.
run_installer() {
    local extracted_dir
    extracted_dir="$(find "$temp_dir/unpacked" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

    if [[ -z "$extracted_dir" || ! -f "$extracted_dir/install.sh" ]]; then
        echo "Error: downloaded archive does not contain install.sh." >&2
        exit 1
    fi

    if [[ "$INSTALL_DEPS" == true ]]; then
        echo "Running the Nex installer with dependency installation enabled"
    else
        echo "Running the Nex installer"
    fi

    (
        cd "$extracted_dir"
        bash ./install.sh "${PASS_THROUGH_ARGS[@]+"${PASS_THROUGH_ARGS[@]}"}"
    )
}

# The prebuilt archive of release TAG, downloaded into temp_dir.
release_archive() {
    echo "$temp_dir/nex-${1#v}.tar.gz"
}

# Download release TAG's prebuilt archive. Returns 4 when the release has
# none, so the caller can build it from source instead; stops on any other
# download failure.
fetch_release() {
    local tag="$1" url status=0
    url="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${tag}/nex-${tag#v}.tar.gz"

    echo "Downloading Nex ${tag#v} from ${url}"
    download "$url" "$(release_archive "$tag")" || status=$?
    if [[ $status -ne 0 && $status -ne 4 ]]; then
        echo "Error: could not download ${url}" >&2
        exit 1
    fi
    return $status
}

install_release() {
    local tag="$1" archive
    archive="$(release_archive "$tag")"
    verify_checksum "$tag" "$archive"

    mkdir -p "$temp_dir/unpacked"
    tar -xzf "$archive" -C "$temp_dir/unpacked"
    run_installer
}

install_from_source() {
    local ref="$1" archive_url archive_path
    archive_path="$temp_dir/${REPO_NAME}-source.tar.gz"
    archive_url="https://github.com/${REPO_OWNER}/${REPO_NAME}/archive/${ref}.tar.gz"

    echo "Downloading Nex source from ${archive_url}"
    if ! download "$archive_url" "$archive_path"; then
        echo "Error: could not download ${archive_url}" >&2
        exit 1
    fi

    mkdir -p "$temp_dir/unpacked"
    tar -xzf "$archive_path" -C "$temp_dir/unpacked"
    run_installer
}

main() {
    parse_args "$@"
    require_command tar
    require_command mktemp

    # Global, not local: the EXIT trap below runs after main has returned,
    # when a local would already be unset (and `set -u` makes that fatal).
    temp_dir="$(mktemp -d)"
    cleanup() {
        rm -rf "$temp_dir"
    }
    trap cleanup EXIT

    if [[ "$FROM_SOURCE" == true ]]; then
        install_from_source "${REPO_REF:-main}"
        return
    fi

    local tag
    tag="$(release_tag)"
    # Only a missing archive falls back to a build from source: everything
    # after the download runs outside this `if`, so `set -e` still applies.
    if fetch_release "$tag"; then
        install_release "$tag"
    else
        echo "Release ${tag} has no prebuilt archive; building it from source instead"
        echo "(this also needs the Clojure CLI, which the installer can install)."
        install_from_source "$tag"
    fi
}

main "$@"
