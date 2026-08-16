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
export GLR_IMAGE_REPO=ghcr.io/waritctd/glr-hr-backend   # must match render.yaml's image.url, minus the tag
export GLR_FONTS_URL="https://your-private-storage/thai-fonts.tar.gz?<signature>"
./scripts/build-push-backend-image.sh v2026-08-14
```

`GLR_FONTS_URL` is optional — leave it unset and populate `backend/fonts/` by hand instead. The
script refuses to build on an empty folder, and refuses outright if the font files have somehow
become git-tracked.

Pushing to GHCR needs a token with `write:packages` — `gh`'s default scopes do **not** include it
(`gh auth refresh -h github.com -s write:packages,read:packages`, then
`gh auth token | docker login ghcr.io -u <user> --password-stdin`).

`--also-latest` exists but prefer not to use it here: `render.yaml` pins an explicit tag so a deploy
is a deliberate act, and a moving `:latest` undermines that.

### Check the font set before you build

```bash
./scripts/check-fonts.sh              # checks backend/fonts/
./scripts/check-fonts.sh /some/dir    # or a set you have just been handed
```

Same family+style comparison the Dockerfile makes, in a second instead of two minutes into a
build. It reads the required list **out of `backend/Dockerfile`**, so the two cannot drift — add a
face there and this script follows automatically.

It uses `fc-scan` on the files rather than `fc-match`, deliberately: `fc-match` queries the fonts
installed on *your machine*, and a Mac with Angsana New installed system-wide would report success
for a completely empty `backend/fonts/`. `fc-scan` reads the actual files that get COPYed in.

### What the build verifies: every FAMILY + STYLE pair, not every family

```
fc-match --format '%{family[0]}|%{style[0]}' "Angsana New:style=Bold"   # must print exactly "Angsana New|Bold"
```

`fc-match` never fails. Ask it for something absent and it cheerfully returns whatever it would
substitute — and it substitutes the two halves **independently**:

| asked for | got | what fell back |
|---|---|---|
| `Nonexistent Font:style=Bold` | `Verdana\|Bold` | family |
| `BrowalliaUPC:style=Bold` | `BrowalliaUPC\|Regular` | **style only — family held** |

That second row is why a family-only check is not enough. It is not hypothetical: this folder
carried `angsa.ttf` but no `angsab.ttf` for months. `fc-list` found "Angsana New" and
`fc-match "Angsana New"` resolved to itself, so the old family-only loop passed — while all three
**bold** Angsana cells in `quotation_template.xls` (company header, ใบเสนอราคา title, totals row)
rendered in a substitute. Comparing the `family|style` pair is what catches it.

The guard was mutation-checked on 2026-08-14: removing `angsab.ttf` fails the build with
`asked fontconfig for 'Angsana New|Bold' and got 'Angsana New|Regular'`, and only that face fails.

To re-derive the required list rather than trusting this table, read the workbook's own BIFF font
table — `bls >= 700` is bold. The list above was confirmed that way, not just from documentation.

**An empty `backend/fonts/` WARNS and continues.** #670 made it fatal — the right instinct, since
a fontless image renders customer documents in substitutes and a build-log warning nobody reads is
exactly how that shipped unnoticed. It was reverted under a UAT phase-1 deadline: Render builds
from a fresh clone where this folder is always empty, so fatal meant the service could not deploy
at all. `--build-arg ALLOW_MISSING_FONTS=false` makes it fatal again, for a build that *should*
carry fonts.

The substitution guard from #670 is **kept**: if fonts are supplied and fontconfig substitutes one
anyway, the build fails.

**Render (hosted): runs a pre-built image, so the fonts are present.** `render.yaml` is on
`runtime: image` pointing at a private GHCR tag built by `scripts/build-push-backend-image.sh`.
Render builds from a fresh clone where this folder is always empty, so a Render-built image can
never carry the fonts — that is the whole reason for the pre-built route.

Two things that bite if forgotten:

- **`--platform linux/amd64` is mandatory.** Render runs amd64. This repo is developed on Apple
  Silicon, where `docker build` defaults to arm64 and produces an image Render pulls and then
  cannot start, failing at deploy time long after the build looked fine. The script passes it.
- **The registry must be PRIVATE and Render needs credentials.** The image embeds licensed fonts,
  and this repo is public. Render pulls via a registry credential named `ghcr-glr` (Dashboard →
  Settings → Registry Credentials) holding a GitHub PAT with `read:packages`.

**On-prem (future):** natural fit — your build server/CI job fetches `thai-fonts.tar.gz` into
`backend/fonts/` as a step before `docker build`, exactly like the script does.

## Local dev (macOS)

Since these can't live in the repo, keep a personal copy directly in your font directory instead
of this folder:

```bash
cp /path/to/your/local/thai-fonts/*.ttf /path/to/your/local/thai-fonts/*.ttc ~/Library/Fonts/
# then restart the backend
```
