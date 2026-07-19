# Thai document fonts (for XLS → PDF conversion)

The Excel document templates (`quotation_template.xls`, `deposit_notice_template.xls`,
`remaining_invoice_template.xls`) were authored on Windows and use the Windows Thai
fonts **Angsana New**, **Browallia New**, and **Cordia New** (plus the `*UPC` variants).

When the backend converts an XLS to PDF via LibreOffice, LibreOffice needs those exact
fonts installed — otherwise it substitutes a wider font and the header text overflows /
overlaps the certification badges.

## What to put here

Copy the TrueType files from a Windows machine (`C:\Windows\Fonts\`) into this folder:

| Family        | Files (regular / bold / italic / bold-italic)       |
|---------------|-----------------------------------------------------|
| Angsana New   | `angsa.ttf` `angsab.ttf` `angsai.ttf` `angsaz.ttf`  |
| Browallia New | `browa.ttf` `browab.ttf` `browai.ttf` `browaz.ttf`  |
| Cordia New    | `cordia.ttf` `cordiab.ttf` `cordiai.ttf` `cordiaz.ttf` |
| BrowalliaUPC  | `upcjl.ttf` (+ bold/italic variants if used)        |

These are proprietary Microsoft fonts, so they are **git-ignored** (see `.gitignore`)
and must be supplied per-environment. GL&R holds Windows licences for them.

## Local dev (macOS)

```bash
cp backend/fonts/*.ttf ~/Library/Fonts/
# then restart the backend
```

## Docker / Render (production)

The `backend/Dockerfile` copies this folder into the image and runs `fc-cache`, so once
the `.ttf` files are present here at build time they are installed automatically. No extra
steps — just rebuild the image.

## After installing the fonts

The header will then render exactly like Excel's "Save as PDF". At that point the temporary
font-width compensations in the renderers (forced fit-to-width, the title/name reposition)
can be removed so the output uses the template's native 86 % scale.
