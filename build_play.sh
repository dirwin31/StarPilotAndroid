#!/usr/bin/env bash
set -euo pipefail
ANDROID_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
"$ANDROID_ROOT/gradlew" -p "$ANDROID_ROOT" --no-daemon bundlePlayRelease lintPlayRelease
