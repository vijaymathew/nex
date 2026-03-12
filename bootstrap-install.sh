#!/usr/bin/env bash
set -euo pipefail

REPO_OWNER="${NEX_REPO_OWNER:-vijaymathew}"
REPO_NAME="${NEX_REPO_NAME:-nex}"
REPO_REF="${NEX_INSTALL_REF:-main}"
TARGET="jvm"
INSTALL_DEPS=false
PASS_THROUGH_ARGS=()

usage() {
    cat <<'EOF'
Bootstrap installer for Nex.

This script downloads a Nex source archive from GitHub and runs the
project's install.sh script locally.

Usage:
  ./bootstrap-install.sh [jvm|nodejs] [--install-deps] [--ref <git-ref>] [--prefix <dir>]

Examples:
  ./bootstrap-install.sh
  ./bootstrap-install.sh jvm --install-deps
  ./bootstrap-install.sh jvm --prefix "$HOME/.local"
  ./bootstrap-install.sh jvm --ref v0.1.0

Environment overrides:
  NEX_REPO_OWNER   GitHub owner/user (default: vijaymathew)
  NEX_REPO_NAME    GitHub repo name (default: nex)
  NEX_INSTALL_REF  Branch, tag, or commit to install (default: main)
EOF
}

require_command() {
    local command_name="$1"

    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Error: required command not found: $command_name" >&2
        exit 1
    fi
}

download_archive() {
    local url="$1"
    local output="$2"

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$output"
        return
    fi

    if command -v wget >/dev/null 2>&1; then
        wget -qO "$output" "$url"
        return
    fi

    echo "Error: either curl or wget is required to download Nex." >&2
    exit 1
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            jvm|nodejs)
                TARGET="$1"
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
            --ref)
                if [[ $# -lt 2 ]]; then
                    echo "Error: --ref requires a git ref." >&2
                    exit 1
                fi
                REPO_REF="$2"
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

main() {
    parse_args "$@"
    require_command tar
    require_command mktemp

    local temp_dir archive_url archive_path extracted_dir
    temp_dir="$(mktemp -d)"
    archive_path="$temp_dir/${REPO_NAME}.tar.gz"
    archive_url="https://github.com/${REPO_OWNER}/${REPO_NAME}/archive/${REPO_REF}.tar.gz"

    cleanup() {
        rm -rf "$temp_dir"
    }
    trap cleanup EXIT

    echo "Downloading Nex from ${archive_url}"
    download_archive "$archive_url" "$archive_path"

    tar -xzf "$archive_path" -C "$temp_dir"
    extracted_dir="$(find "$temp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

    if [[ -z "$extracted_dir" || ! -f "$extracted_dir/install.sh" ]]; then
        echo "Error: downloaded archive does not contain install.sh." >&2
        exit 1
    fi

    if [[ "$INSTALL_DEPS" == true ]]; then
        echo "Running Nex installer for target '${TARGET}' with dependency installation enabled"
    else
        echo "Running Nex installer for target '${TARGET}'"
    fi

    (
        cd "$extracted_dir"
        bash ./install.sh "${PASS_THROUGH_ARGS[@]}"
    )
}

main "$@"
