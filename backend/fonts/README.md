# Thai document fonts (for XLS → PDF conversion)

The Excel document templates (`quotation_template.xls`, `deposit_notice_template.xls`,
`remaining_invoice_template.xls`) were authored on Windows and use the Windows Thai
fonts **Angsana New**, **Browallia New**, and **Cordia New** (plus the `*UPC` variants).

When the backend converts an XLS to PDF via LibreOffice, LibreOffice needs those exact
fonts installed — otherwise it substitutes a wider font and the header text overflows /
overlaps the certification badges.

## What's here

Exactly the family+style combinations the three live templates actually reference (checked
against each workbook's own font table — confirmed no renderer creates new fonts or bold/italic
styles at runtime, only reuses existing template cell styles), nothing more:

| Family        | Styles used         | File(s)                                    |
|---------------|----------------------|---------------------------------------------|
| Angsana New   | Regular, Bold        | `angsa.ttf` `angsab.ttf`                    |
| Browallia New | Regular, Bold        | `browa.ttf` `browab.ttf`                    |
| BrowalliaUPC  | Regular              | `browau.ttf`                                |
| Cordia New    | Regular              | `cordia.ttc` (a multi-face TTC; also carries the CordiaUPC faces, unused but not worth subsetting out) |
| Tahoma        | Regular              | `tahoma.ttf`                                |
| Arial         | Regular              | `arial.ttf`                                 |
| Calibri       | Regular, Bold, Italic| `calibri.ttf` `calibrib.ttf` `calibrii.ttf` |
| Cambria       | Bold                 | `cambriab.ttf`                              |

(~5.5MB total, down from ~9.8MB for the full family sets — bold-italic/italic/UPC variants that
no template cell actually uses were dropped.) If a template is edited to use a style not listed
here, re-check its font table (POI: `Workbook#getFontAt`) and add the matching file before
relying on it — otherwise LibreOffice silently substitutes a wider font for that one style only.

GL&R holds Windows/Office licences for all of these.

## These files must never be committed to this repo

This folder holds only `README.md` and `.gitignore` — the actual `.ttf`/`.ttc` files are
git-ignored and are never present here except transiently on your own machine for local dev.
They must not reach git history on any branch, including feature branches, since anything
merged to `main` is effectively public within the org.

## How the Docker build gets them: `backend/fonts/` must be populated before you build

The Dockerfile does a plain `COPY fonts/ /usr/local/share/fonts/glr/` — it does **not** fetch or
generate anything itself. Whatever process runs `docker build` must put the real `.ttf`/`.ttc`
files into this folder **first**, from private storage you control.

Use the script; it does the fetch, the safety checks, the build and the push:

```bash
export GLR_IMAGE_REPO=docker.io/yourorg/glr-hr-backend   # must match render.yaml's image.url
export GLR_FONTS_URL="https://your-private-storage/thai-fonts.tar.gz?<signature>"
./scripts/build-push-backend-image.sh v2026-08-10 --also-latest
```

`GLR_FONTS_URL` is optional — leave it unset and populate `backend/fonts/` by hand instead. The
script refuses to build on an empty folder, and refuses outright if the font files have somehow
become git-tracked.

### What the build verifies, and why it takes two checks

```
ARG ALLOW_MISSING_FONTS=false
RUN fc-cache -f -v && ...
      fc-list  | grep -qi "$f"        # 1. is a font by this NAME registered?
      fc-match "$f" family            # 2. does it RESOLVE to itself, or silently fall back?
```

1. **`fc-list`** catches a truncated or partial fetch — a family that never registered.
2. **`fc-match`** is the one that catches *substitution*, and it is the check this repo was
   missing. `fc-match` never fails: ask it for a font it does not have and it cheerfully returns
   whatever it would substitute. On a fontless image `fc-match "Angsana New" family` prints
   `DejaVu Sans` and `fc-match "Arial" family` prints `Liberation Sans` — measured, not assumed.
   That is exactly the production defect, and check 1 alone cannot see it.

**An empty `backend/fonts/` now FAILS the build** (#666). It used to warn and continue, which is
how the hosted service shipped substitute fonts for months with nothing but a build-log line
nobody read — and this README claimed it already failed, so the two disagreed. They agree now.
For a knowingly fontless throwaway build, opt out explicitly:
`docker build --build-arg ALLOW_MISSING_FONTS=true ...`. Never for anything a user will see.

**Render (hosted):** resolved — the service no longer builds from source. Render's Docker builds
run from a fresh git clone where this folder is always empty, so a Render-built image could never
contain the fonts. `render.yaml` now uses `runtime: image` and pulls a pre-built image instead
(#666). Build and push it with the script above, then deploy from the dashboard.

The cost, stated plainly: **a push to `main` no longer produces a deployable artifact.** Backend
changes need a build+push before the deploy. `autoDeploy: false` already made deploys manual, so
this adds a step rather than changing the shape of the workflow.

**On-prem (future):** natural fit — your build server/CI job fetches `thai-fonts.tar.gz` into
`backend/fonts/` as a step before `docker build`, exactly like the script does.

## Local dev (macOS)

Since these can't live in the repo, keep a personal copy directly in your font directory instead
of this folder:

```bash
cp /path/to/your/local/thai-fonts/*.ttf /path/to/your/local/thai-fonts/*.ttc ~/Library/Fonts/
# then restart the backend
```
