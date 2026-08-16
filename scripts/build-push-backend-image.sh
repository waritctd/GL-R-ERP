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

# Guard against shipping stale code. v2026-08-17 was built from a tree still sitting at pre-merge
# main: its fonts were correct, its architecture was correct, the build log was clean and the push
# succeeded. NOTHING in the output revealed that the jar predated the fix it was built to ship.
# Only inspecting the compiled class inside the image caught it, after the fact.
#
# The test is "does HEAD contain origin/main", not "does HEAD equal it" — building a feature branch
# that is ahead of main is legitimate; building one that is BEHIND it is the mistake.
#
# GLR_SKIP_FRESHNESS_CHECK=true escapes it, for deliberately rebuilding an old commit.
if [ "${GLR_SKIP_FRESHNESS_CHECK:-}" != "true" ]; then
  git fetch -q origin main 2>/dev/null || \
    echo "  (warning: could not reach origin; freshness checked against the last known origin/main)" >&2
  if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
    if ! git merge-base --is-ancestor origin/main HEAD 2>/dev/null; then
      BEHIND=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo '?')
      echo "ERROR: HEAD is missing $BEHIND commit(s) from origin/main — this image would ship STALE code." >&2
      echo "  HEAD:        $(git rev-parse --short HEAD) $(git log -1 --format=%s | cut -c1-60)" >&2
      echo "  origin/main: $(git rev-parse --short origin/main) $(git log -1 --format=%s origin/main | cut -c1-60)" >&2
      echo "  Fix: build from a tree at origin/main, or rebase this branch onto it." >&2
      echo "  Override with GLR_SKIP_FRESHNESS_CHECK=true only if shipping old code is deliberate." >&2
      exit 1
    fi
  fi
fi

# Uncommitted backend changes land in the image but exist in no commit, so the revision label below
# would misdescribe it. Warn rather than fail: a local test build of a work-in-progress is valid.
if ! git diff --quiet -- backend 2>/dev/null || ! git diff --cached --quiet -- backend 2>/dev/null; then
  echo "  (warning: uncommitted changes under backend/ — they WILL be baked into this image," >&2
  echo "   and the revision label will point at HEAD, which does not contain them)" >&2
fi

GIT_REV="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
echo "==> building $REPO:$TAG  (revision ${GIT_REV:0:12})"
# The Dockerfile verifies every required family is registered AND that fontconfig resolves each to
# itself rather than substituting, so a bad font set fails here instead of in front of a customer.
#
# --platform linux/amd64 is REQUIRED, not a preference. Render runs amd64; docker build defaults to
# the host architecture, so running this on an Apple Silicon Mac — which is what this repo is
# developed on — silently produces an arm64 image that Render pulls and then cannot start. The
# failure surfaces as a deploy-time exec-format error, long after the build looked fine. Building
# under emulation is slower; that is the cost of an image that runs where it is deployed.
#
# --build-arg ALLOW_MISSING_FONTS=false makes a missing or substituted family fail the build. The
# empty-folder check above already guarantees fonts are present, but the flag also covers the case
# this script exists for: fonts present yet WRONG (a partial fetch, or a family fontconfig
# substitutes anyway). Shipping fonts is the whole point of this image — never build it permissive.
#
# The revision label records WHICH COMMIT this image was built from. Without it there is no way to
# ask a pushed image whether it contains a given fix — diagnosing the stale v2026-08-17 required
# extracting app.jar and reading a class file's constant pool. With it:
#   docker image inspect <tag> --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'
docker build --platform linux/amd64 --build-arg ALLOW_MISSING_FONTS=false \
  --label "org.opencontainers.image.revision=$GIT_REV" \
  --label "org.opencontainers.image.version=$TAG" \
  -t "$REPO:$TAG" -f backend/Dockerfile backend

# Read the label back off the built image rather than trusting that the flag took effect.
BUILT_REV=$(docker image inspect "$REPO:$TAG" \
  --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' 2>/dev/null || echo '')
if [ "$BUILT_REV" != "$GIT_REV" ]; then
  echo "ERROR: image revision label is '$BUILT_REV' but HEAD is '$GIT_REV'." >&2
  echo "  The image cannot be traced back to a commit — refusing to push it." >&2
  exit 1
fi

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
