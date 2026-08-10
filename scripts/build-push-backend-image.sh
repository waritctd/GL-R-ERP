#!/usr/bin/env bash
#
# Build the backend image WITH the licensed Thai fonts, and push it to the registry Render pulls
# from. See #666 and backend/fonts/README.md.
#
# Why this script exists: Render builds from a fresh git clone, and the fonts are proprietary and
# git-ignored, so a Render-built image can never contain them. The image is built here instead —
# on a machine that can reach the fonts — and Render deploys the result.
#
#   ./scripts/build-push-backend-image.sh v2026-08-10
#   ./scripts/build-push-backend-image.sh v2026-08-10 --also-latest
#
# Environment:
#   GLR_IMAGE_REPO   registry path to push to, e.g. docker.io/yourorg/glr-hr-backend.
#                    Must match image.url in render.yaml (minus the tag).
#   GLR_FONTS_URL    optional. If set, fetched and unpacked into backend/fonts/ before building.
#                    Expected to be a .tar.gz of the .ttf/.ttc files (flat, no leading directory).
#                    If unset, backend/fonts/ must already be populated by hand.
#
# The fonts are copied into the build context and are git-ignored; this script never commits them
# and never leaves them anywhere outside backend/fonts/.
set -euo pipefail

TAG="${1:-}"
if [ -z "$TAG" ]; then
  echo "usage: $0 <tag> [--also-latest]" >&2
  echo "  e.g. $0 v2026-08-10" >&2
  exit 2
fi
ALSO_LATEST="${2:-}"

REPO="${GLR_IMAGE_REPO:-}"
if [ -z "$REPO" ]; then
  echo "ERROR: set GLR_IMAGE_REPO to the registry path in render.yaml's image.url (minus the tag)." >&2
  echo "  e.g. export GLR_IMAGE_REPO=docker.io/yourorg/glr-hr-backend" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
FONT_DIR="backend/fonts"

# Fetch the fonts if a source is configured. Deliberately not defaulted to any URL — where the
# licensed files live is per-organisation and must not be guessed or hardcoded.
if [ -n "${GLR_FONTS_URL:-}" ]; then
  echo "==> fetching fonts into $FONT_DIR"
  TMP_ARCHIVE="$(mktemp -t glr-fonts-XXXXXX.tar.gz)"
  # shellcheck disable=SC2064  # expand TMP_ARCHIVE now, not at trap time
  trap "rm -f '$TMP_ARCHIVE'" EXIT
  curl -fsSL "$GLR_FONTS_URL" -o "$TMP_ARCHIVE"
  tar -xzf "$TMP_ARCHIVE" -C "$FONT_DIR"
fi

# Fail here rather than inside the build: the Docker error is correct but the fix is local.
if ! find "$FONT_DIR" -type f \( -iname '*.ttf' -o -iname '*.ttc' -o -iname '*.otf' \) -print -quit | grep -q .; then
  echo "ERROR: no font files in $FONT_DIR." >&2
  echo "  Either set GLR_FONTS_URL, or copy the licensed .ttf/.ttc files in by hand." >&2
  echo "  Required families are listed in backend/fonts/README.md." >&2
  exit 1
fi

# Guard against the one mistake that would be unrecoverable: fonts reaching git history. They are
# git-ignored, so this only trips if someone has force-added them.
if git ls-files --error-unmatch "$FONT_DIR"/*.ttf "$FONT_DIR"/*.ttc "$FONT_DIR"/*.otf >/dev/null 2>&1; then
  echo "ERROR: font files are TRACKED by git. They are proprietary and must never be committed." >&2
  echo "  Fix with: git rm --cached $FONT_DIR/*.ttf $FONT_DIR/*.ttc $FONT_DIR/*.otf" >&2
  exit 1
fi

echo "==> building $REPO:$TAG"
# The Dockerfile verifies every required family is registered AND that fontconfig resolves each to
# itself rather than substituting, so a bad font set fails here instead of in front of a customer.
docker build -t "$REPO:$TAG" -f backend/Dockerfile backend

if [ "$ALSO_LATEST" = "--also-latest" ]; then
  docker tag "$REPO:$TAG" "$REPO:latest"
fi

echo "==> pushing $REPO:$TAG"
docker push "$REPO:$TAG"
if [ "$ALSO_LATEST" = "--also-latest" ]; then
  echo "==> pushing $REPO:latest"
  docker push "$REPO:latest"
fi

cat <<EOF

Done: $REPO:$TAG

Next:
  1. Point render.yaml's image.url at this tag (or push :latest and redeploy).
  2. Deploy from the Render dashboard — autoDeploy is off by design.
  3. Verify inside the running container:
       fc-match "Angsana New" family     # must print "Angsana New", not a substitute
  4. Download a quotation PDF and compare against a Windows-authored reference.
     Per #666 that comparison is the acceptance signal — a green test suite is not,
     because the renderer tests pass under substitution.
EOF
