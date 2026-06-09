#!/usr/bin/env bash
# Generate Shou.xcodeproj from project.yml. Run once on a Mac before opening in Xcode.
#   brew install xcodegen   # if you don't have it
#   ./generate.sh && open Shou.xcodeproj
set -euo pipefail
cd "$(cd -P "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
if ! command -v xcodegen >/dev/null 2>&1; then
  echo "xcodegen not found — install it with:  brew install xcodegen" >&2
  exit 1
fi
xcodegen generate
echo "✓ Shou.xcodeproj generated. Open it, then set your signing Team on both targets."
