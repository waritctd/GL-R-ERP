#!/usr/bin/env bash
#
# Pre-flight: does backend/fonts/ actually provide every font face the Dockerfile demands?
#
#   ./scripts/check-fonts.sh                 # checks backend/fonts/
#   ./scripts/check-fonts.sh /some/other/dir
#
# Why this exists: the Dockerfile already verifies the fonts, but only ~2 minutes into a build,
# after the Maven layers. Running the same check in a second, before you start, turns a wasted
# build into an instant answer. It is also the only way to check a font set you have just been
# handed without building anything.
#
# Why it uses fc-scan and not fc-match: fc-match queries the fonts installed on THIS machine.
# Your Mac almost certainly has Angsana New installed system-wide, so fc-match would happily
# report success for a completely empty backend/fonts/. fc-scan reads the specific files instead,
# which is what actually gets COPYed into the image.
#
# The required list is parsed out of backend/Dockerfile rather than duplicated here, so the two
# can never drift. If a template starts using a new style, add it in the Dockerfile and this
# script follows automatically.
set -uo pipefail

cd "$(dirname "$0")/.."
FONT_DIR="${1:-backend/fonts}"
DOCKERFILE="backend/Dockerfile"

if ! command -v fc-scan >/dev/null 2>&1; then
  echo "ERROR: fc-scan not found. Install fontconfig (macOS: brew install fontconfig)." >&2
  exit 2
fi

if [ ! -d "$FONT_DIR" ]; then
  echo "ERROR: no such directory: $FONT_DIR" >&2
  exit 2
fi

# Pull the "Family|Style" specs out of the Dockerfile's `set --` list — the single place the
# required faces are defined, consumed by both the warn-branch and strict-branch loops via
# `for spec in "$@"; do`. Scoped to the `set --` statement itself (it ends at its own trailing
# `&&`) so this can never wander into a loop body and pick up shell-expansion noise instead of
# real specs — that is exactly what happened when this used to grep the "for spec in ... ; do"
# loop line, back when the loop spelled the specs out literally instead of reading "$@".
REQUIRED=$(sed -n '/set --/,/&&/p' "$DOCKERFILE" | grep -oE '"[^"]+\|[^"]+"' | tr -d '"')

# Trust nothing: validate what came out actually looks like N "Family|Style" specs before using
# it. A parse that silently returns plausible-looking garbage (e.g. the literal text of a shell
# expansion like `${spec%|*}`, which itself contains a "|" and so slips past a naive re-extract)
# is worse than one that returns nothing — the old empty-string guard below did not catch that
# failure mode, which is exactly how this broke last time the Dockerfile was reformatted.
bad=0
if [ -z "$REQUIRED" ]; then
  bad=1
elif printf '%s\n' "$REQUIRED" | grep -qE '[${}]'; then
  bad=1
elif printf '%s\n' "$REQUIRED" | grep -qvE '^[^|]+\|[^|]+$'; then
  bad=1
fi
if [ "$bad" -eq 1 ]; then
  echo "ERROR: could not parse the required font list from $DOCKERFILE." >&2
  echo "  The 'set -- ...' block may have been reformatted — fix this parser." >&2
  exit 2
fi

# Every face the folder actually provides. A .ttc holds several faces, so ask for all of them.
AVAILABLE=$(mktemp)
trap 'rm -f "$AVAILABLE"' EXIT
found_files=0
for f in "$FONT_DIR"/*.ttf "$FONT_DIR"/*.ttc "$FONT_DIR"/*.otf; do
  [ -e "$f" ] || continue
  found_files=$((found_files + 1))
  fc-scan --format '%{[]family,style{%{family[0]}|%{style[0]}\n}}' "$f" 2>/dev/null >> "$AVAILABLE" \
    || fc-scan --format '%{family[0]}|%{style[0]}\n' "$f" 2>/dev/null >> "$AVAILABLE"
done

if [ "$found_files" -eq 0 ]; then
  echo "ERROR: no .ttf/.ttc/.otf files in $FONT_DIR" >&2
  echo "  Set GLR_FONTS_URL or copy the licensed files in by hand — see backend/fonts/README.md." >&2
  exit 1
fi

sort -u "$AVAILABLE" -o "$AVAILABLE"
echo "scanned $found_files file(s) in $FONT_DIR — $(wc -l < "$AVAILABLE" | tr -d ' ') face(s) found"
echo

missing=0
while IFS= read -r spec; do
  [ -n "$spec" ] || continue
  if grep -Fxq "$spec" "$AVAILABLE"; then
    printf '  OK       %s\n' "$spec"
  else
    printf '  MISSING  %s\n' "$spec"
    missing=$((missing + 1))
  fi
done <<< "$REQUIRED"

echo
if [ "$missing" -eq 0 ]; then
  echo "All $(echo "$REQUIRED" | grep -c .) required faces present — the Dockerfile font guard should pass."
  exit 0
fi

echo "ERROR: $missing required face(s) missing." >&2
echo "  Both halves matter: fontconfig substitutes family and style INDEPENDENTLY, so a missing" >&2
echo "  bold face keeps the right family and silently renders Regular. That shipped for months." >&2
echo "  See backend/fonts/README.md for which file supplies which face." >&2
exit 1
