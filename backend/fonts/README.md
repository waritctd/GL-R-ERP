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
generate anything itself. That means whatever process runs `docker build` is responsible for
putting the real `.ttf`/`.ttc` files into this folder **first**, from private storage you control
(a private S3/GCS/R2 bucket, an authenticated private host, whatever you already have), e.g.:

```bash
# however you fetch it — signed URL, private bucket CLI, scp, etc.
curl -fsSL "https://your-private-storage/thai-fonts.tar.gz?<signature>" -o /tmp/thai-fonts.tar.gz
tar -xzf /tmp/thai-fonts.tar.gz -C backend/fonts/
docker build -t glr-hr-backend backend
```

The build itself then verifies every required family actually registered — `fc-cache -f -v`
followed by an individual check per family (not one combined grep, so a single missing font fails
loudly instead of being masked by the others still matching):
```
RUN fc-cache -f -v && \
    for f in "Arial" "Calibri" "Cambria" "Tahoma" "Angsana New" "Browallia New" "BrowalliaUPC" "Cordia New"; do \
      fc-list | grep -qi "$f" || { echo "Missing font: $f" >&2; exit 1; }; \
    done
```
If `backend/fonts/` is empty (nothing fetched), this fails the build immediately with
`Missing font: <name>` rather than silently shipping an image with substitute fonts.

**Render (hosted):** Render's Docker builds run straight from a fresh git clone with no
pre-build hook, so this fetch step has to happen somewhere Render can run it — either switch
this service to build from a pre-built image you push yourself (fetch fonts + `docker build` +
`docker push` from your own machine/CI, point Render at that image instead of `dockerfilePath`),
or move the fetch into a Render-supported pre-build mechanism if one fits your setup. This is the
piece to work out once the storage location exists.

**On-prem (future):** natural fit — your build server/CI job fetches `thai-fonts.tar.gz` into
`backend/fonts/` as a step before `docker build`, exactly like the command above.

## Local dev (macOS)

Since these can't live in the repo, keep a personal copy directly in your font directory instead
of this folder:

```bash
cp /path/to/your/local/thai-fonts/*.ttf /path/to/your/local/thai-fonts/*.ttc ~/Library/Fonts/
# then restart the backend
```
