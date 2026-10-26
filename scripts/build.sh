#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
toolchain_dir="${THORSHELF_TOOLCHAIN_DIR:-/tmp/thorshelf-toolchain}"

if [[ -z "${JAVA_HOME:-}" && -x "$toolchain_dir/jdk/bin/java" ]]; then
    export JAVA_HOME="$toolchain_dir/jdk"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$toolchain_dir/android-sdk" ]]; then
    export ANDROID_HOME="$toolchain_dir/android-sdk"
fi

# Keep tool caches out of the source checkout and support restricted workspaces.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$toolchain_dir/gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$toolchain_dir/android-user-home}"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

if ! command -v java >/dev/null 2>&1; then
    echo 'Install JDK 17 and set JAVA_HOME before building.' >&2
    exit 1
fi

cd -- "$project_dir"
if [[ "$#" -eq 0 ]]; then
    set -- testDebugUnitTest lintDebug assembleDebug
fi
exec ./gradlew --no-daemon "$@"
