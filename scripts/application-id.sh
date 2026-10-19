#!/usr/bin/env bash
# Source from host Bash scripts. Node is the single APK metadata reader.
resolve_application_id() {
    APPLICATION_ARGS=()
    local package_options=() arg helper
    while (( $# )); do
        arg=$1; shift
        case "$arg" in
            --package)
                (( $# )) || { echo '--package requires a value' >&2; return 2; }
                package_options+=(--package "$1"); shift ;;
            --package=*) package_options+=("$arg") ;;
            *) APPLICATION_ARGS+=("$arg") ;;
        esac
    done
    helper="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/application-id.cjs"
    if [[ "$(uname -s)" == MINGW* ]]; then helper=$(cygpath -m "$helper"); fi
    PACKAGE=$("${NODE:-node}" "$helper" "${package_options[@]}") || return
    export PACKAGE
}
