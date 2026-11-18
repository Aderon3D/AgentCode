#!/usr/bin/env bash
# Fails when docs/ROADMAP.md cites a path that no longer exists.
# The roadmap is the only status file; this keeps it from rotting on renames.
set -euo pipefail
cd "$(dirname "$0")/.."

mapfile -t cited < <(
  grep -oE '`[A-Za-z0-9_./-]+\.(kt|kts|yml|md|sh)`' docs/ROADMAP.md \
    | tr -d '`' | sort -u
)

missing=0
for p in "${cited[@]}"; do
  if [ ! -e "$p" ]; then
    echo "ROADMAP cites a missing path: $p"
    missing=1
  fi
done

if [ "$missing" -ne 0 ]; then
  echo "Fix docs/ROADMAP.md (renamed or deleted artifact) and re-run."
  exit 1
fi
echo "roadmap ok: ${#cited[@]} cited paths exist"
