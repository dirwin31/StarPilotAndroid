#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${1:?Pass the StarPilot checkout}/openpilot/starpilot/galaxy/web"
test -f "$SOURCE/index.html"
mkdir -p "$ROOT/app/src/main/assets/demo/web"
rsync -a --delete "$SOURCE/" "$ROOT/app/src/main/assets/demo/web/"
