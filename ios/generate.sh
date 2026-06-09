#!/usr/bin/env bash
# Generate Shou.xcodeproj from a project spec. Run on a Mac before opening in Xcode.
#   brew install xcodegen          # if you don't have it
#   ./generate.sh                  # FREE build (no Apple payment needed) — the default
#   ./generate.sh --full           # FULL build (paid account: widget + WOL + Control)
# Then:  open Shou.xcodeproj  and set your signing Team.
set -euo pipefail
cd "$(cd -P "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

spec="project.yml"
if [ "${1:-}" = "--full" ]; then
  spec="project-full.yml"
  echo "→ FULL (paid) build — needs a paid Apple Developer account."
else
  echo "→ FREE build — installs with a free Apple ID (7-day signing, no widget/WOL)."
fi

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "xcodegen not found — install it with:  brew install xcodegen" >&2
  exit 1
fi
xcodegen generate --spec "$spec"
echo "✓ Shou.xcodeproj generated from $spec. Open it, then set your signing Team."
