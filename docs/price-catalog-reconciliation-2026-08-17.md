# Price Catalog — Source ↔ Prod Reconciliation & Remediation Plan

**Date:** 2026-08-17
**Prod DB:** Supabase `tdyzcqzxmhtxpbouewud` (GL&R), schema `price_catalog`
**Sources:** 10 factory workbooks in `~/Downloads/Price List/`
**Prod state at audit:** 22,455 rows in `product_prices`, 10 versions, 9 factories, staging empty

Every number below was measured — source rows counted with openpyxl/xlrd, prod rows counted with SQL.
Nothing here is inferred from the import profiles.

---

## 1. Reconciliation table

| # | Factory | Source file | Source data rows | Prod rows | Δ | Verdict |
|---|---------|-------------|-----------------:|----------:|---:|---------|
| 1 | Panaria | Panaria Price List Euro 2026.xlsx | 1,419 | 1,419 | 0 | ✅ exact |
| 2 | LEA | LEA price list EURO 2026.xlsx | 1,626 | 1,626 | 0 | ✅ exact |
| 3 | CDE | CDE price list Euro 2026.xls | 1,640 | 1,640 | 0 | ✅ rows exact — but see **D3** |
| 4 | Padana | Padana Price List Euro 2026.xlsx | 9,076 | 9,076 | 0 | ✅ exact |
| 5 | Equipe | Equipe EXTRACOMUNITARIOS.xlsx | 2,124 | 2,124 | 0 | ✅ exact, 3-tier prices captured |
| 6 | Vives | Vives articulosVives 7A febrero2026.xlsx | 4,618 | 4,617 | −1 | ✅ correct (r4622 is a legal disclaimer) |
| 7 | Bode | Bode new quotation GLR 260623.xls | 48 (44 product + 4 footer) | 215 | — | ✅ correct (comma-split → 215 SKUs) |
| 8 | CITY | CITY Price-List EURO 2026.xlsx | 83 (74 + 9) | 74 | −9 | ✅ correct dedup — verified identical |
| 9 | **REFIN** | REFIN Price-List EUR 2026.xlsx | 1,867 | 1,664 | **−203** | ⚠️ **63 rows genuinely lost** |
| 10 | **(none)** | **2026 GENERAL EXPORT PRICE LIST FOR GLR.xlsx** | **135** | **0** | **−135** | ❌ **never imported** |

**Headline:** 8 of 10 files are clean. Two problems — REFIN loses 63 real rows, and one entire
factory is missing from the system.

### 1a. CITY −9 is correct, not loss
All 9 `Outdoor Solutions` rows are byte-identical duplicates of rows already in
`Contract Solutions` (same code, collection, item, size, thickness, price). Verified pairwise for
all 9 codes: `LN70, LN71, MO52, MO53, RI73, RI74, RI75, RN25, RN26`. Dedup was right.
Only the provenance is misleading — see **D5**.

### 1b. REFIN −203 decomposes into two very different things

| Sheet | Source | Prod | Δ | Assessment |
|-------|-------:|-----:|---:|------------|
| Collections | 1,605 | 1,598 | −7 | mostly OK (4 rows share a NULL-code `180X240` key) |
| RELIEFS_recap | 53 | 0 | −53 | ✅ **correct** — verified identical to `Collections` (RN03, RK81, RK82, RE25 … same price) |
| OUT2.0_recap | 60 | 0 | −60 | ✅ **correct** — verified identical to `Collections` (OR00, OR02, RN23, OB61 … same price) |
| Large-Slabs_recap | 37 | 17 | −20 | ⚠️ 20 correctly absorbed, **17 leaked through as case-duplicates** (D2) |
| OUT2.0_accessories | 19 | 19 | 0 | ✅ exact |
| **Trim-Tiles** | **74** | **17** | **−57** | ❌ **real loss** (D1) |
| **Balneo-Project** | **19** | **13** | **−6** | ❌ **real loss** (D1) |

The "recap" sheets are genuinely redundant summary views — collapsing them is correct behaviour.
**The real loss is 63 rows: 57 from Trim-Tiles and 6 from Balneo-Project.**

---

## 2. Defects, ranked

### D1 — CRITICAL: the unique key degenerates when there is no product code

```sql
CONSTRAINT uq_price UNIQUE NULLS NOT DISTINCT
    (version_id, product_code, grade, size_raw, surface)
```

`NULLS NOT DISTINCT` means NULL equals NULL. When a sheet has no `Code` column,
`product_code`, `grade` and `surface` are all NULL and the key collapses to
**`(version_id, size_raw)`** — so every product sharing a size overwrites the others,
via `ON CONFLICT ... DO UPDATE` in `PriceImportService.java:358`.

**Verified impact — REFIN Trim-Tiles (74 → 17):** the sheet has no `Code` column, so all 74
step/skirting/gutter trims collapse onto 17 distinct sizes. Survivors keep a consistent
name↔price pair (no mispricing), but 57 products are simply **absent**:

| Size | In prod | Missing from prod |
|------|---------|-------------------|
| 33X150 | GRADINO €244 | GRADINO ANG DX/SX €318.5, SCALINO ELEMENTO L €177.5, … |
| 30X60 | GRADINO €92 | GRADINO ANG DX/SX €128.5, … |

**Verified impact — REFIN Balneo-Project (19 → 13):** entire product variants are gone, with a
direct price consequence. `SCARICO A VISTA` (visible drain) and `SCARICO A SCOMPARSA`
(concealed drain) are different products at different prices; only the first survives:

| Product | Size | Surface | Source price | In prod? |
|---------|------|---------|-------------:|----------|
| LAVABO SOSPESO CON SCARICO **A VISTA** | 120X50 h.15 | MATT/SOFT | €3,930.00 | ✅ |
| LAVABO SOSPESO CON SCARICO **A SCOMPARSA** | 120X50 h.15 | MATT/SOFT | €4,028.00 | ❌ **gone** |
| LAVABO SOSPESO CON SCARICO **A VISTA** | 120X50 h.15 | LUCIDO | €4,175.50 | ✅ |
| LAVABO SOSPESO CON SCARICO **A SCOMPARSA** | 120X50 h.15 | LUCIDO | €4,273.00 | ❌ **gone** |

A quote for a concealed-drain basin currently has no price to draw on — or silently reads the
€98-cheaper visible-drain price if matched by size.

**This defect also blocks file #10.** The GENERAL EXPORT workbook has **no product code column at
all**. Measured: of its 103 priced rows, there are only **15 distinct normalised sizes** — so
importing it on today's engine would collapse 103 rows to 15 and **silently discard 85%** of the
file. D1 must be fixed first.

**Fix:** generate a deterministic surrogate `product_code` at parse time whenever the source has
no code — e.g. `sha1(source_sheet | collection | product_name | size_raw | surface)` truncated,
prefixed `AUTO-`. Stable across re-imports, so the incremental-merge path
(`PriceImportService.java:379`) keeps working. The `allow_missing_code: true` flag already in the
REFIN profile and the profile note *"Trim-Tiles ไม่มีคอลัมน์ Code -> surrogate key"* show this was
designed but never implemented.

### D2 — HIGH: `size_raw` is not normalised, so case-only duplicates leak through

`'120X278'` and `'120x278'` are different keys. Measured: **34 REFIN rows in 17 duplicate groups**
(`Collections` vs `Large-Slabs_recap`). Prices agree in all 17, so nothing is mispriced today —
but the same product appears twice in the catalog, and the next price list that changes one copy
and not the other creates a real conflict.

Format drift across the whole catalog:

| Factory | lowercase `x` | embedded whitespace | native format |
|---------|--------------:|--------------------:|---------------|
| Padana | 9,068 | 8,967 | `20 x 20` |
| CDE | 1,526 | 0 | `080x600` (zero-padded) |
| LEA | 1,422 | 37 | ` 300x600` (leading space) |
| Panaria | 1,361 | 0 | ` 150x300` |
| Equipe | 29 | 2,124 | parsed from Spanish description |
| Bode | 205 | 0 | `600x1200` |
| REFIN | 31 | 13 | mixed `120X278` / `120x278` |
| Vives | 0 | 0 | `15'8X31'6` (apostrophe decimal) |

Note `width_mm`/`height_mm` are already parsed correctly almost everywhere (only 27 LEA + 8 Padana
+ 4 Panaria + 19 REFIN nulls), so the numeric path is sound — it is the **key** that is dirty.

**Fix:** add a normalised generated column and move the constraint onto it:
`size_norm = upper(regexp_replace(btrim(size_raw), '\s+', '', 'g'))`. Keep `size_raw` for display.

### D3 — HIGH: CDE linear-metre products have `price_unit = 'unknown'` (164 rows)

CDE's `UOM` column holds three values: `M2` (899), `PC` (577), **`M` (164)**. `M` is not in the
engine's mapping, so those rows land as `'unknown'`. They are all linear trim
(`BULLNOSE AVORIO LPP 12*80X600` etc., €15.00). Any quote maths that assumes m² or piece against
these is wrong. Note the engine already emits `per_linear_m` for CITY (`ml`) and REFIN — so the
target value exists, only the `M` alias is missing.

Also 1 REFIN row is `'unknown'`.

### D4 — MEDIUM: `attributes` is empty for three factories

`attributes` is `{}` for REFIN (1,664), Bode (215), CITY (74) — 1,953 rows with no barcode/UOM
passthrough. Other factories populate it correctly (CDE carries `{"barcode": "…"}`). REFIN and
CITY both have barcode-adjacent columns in source that are being dropped.

### D5 — MEDIUM: provenance is wrong or absent after dedup

CITY's 9 deduped Outdoor rows record `source_sheet = 'Contract Solutions'`. REFIN's 113
recap-sheet rows record nothing at all. When a price is disputed you cannot tell which sheet it
came from. `ON CONFLICT DO UPDATE` should append the losing sheet to `attributes.also_in_sheets`.

### D6 — LOW: version metadata is thin

- `effective_from` is **NULL on all 10 versions** — there is no way to answer "what was the price
  on date X", and no guard against loading an old list over a new one.
- CDE has an orphan `version_id=1` in `DRAFT` with `row_count=1640` but **0 actual rows**
  (superseded by `version_id=2`). It should be `ARCHIVED` or deleted.
- `_product_prices_backup` (22,455 rows) is undocumented — confirm it is a pre-migration snapshot
  and either document or drop it.

### D7 — LOW: `error_count` conflates footers with real errors

Bode's 4 "errors" are the `REMARKS:` block (rows 56–58, 61); Vives' 1 is the row-4622 disclaimer.
Both are correctly rejected, but they surface to the CEO as import errors. Add a
`footer_stop_after_blank_rows` or `stop_at_regex` profile option so clean imports report 0 errors.

### D8 — LOW: REFIN profile hardcodes `PRICE 2025` on a 2026 file

`Trim-Tiles` actually uses `PRICE 2026` while the other sheets use `PRICE 2025` in a 2026 file.
The `column_aliases` list covers both, but the primary `"price": "PRICE 2025"` is misleading and
will break the day REFIN relabels. Match on a `PRICE\s*\d{4}` pattern instead.

---

## 3. File #10 — `2026 GENERAL EXPORT PRICE LIST FOR GLR.xlsx`

Not in `factories`, not in `import_profiles`, no version, **0 rows in prod.**
Workbook metadata: company `CHINA`, authors `gxc_g` / `A Jacky`. A Chinese supplier distinct from
Bode. 7 sheets banded by tile size: `90X180`, `80X180`, `75X150`, `60X120`, `60X60`, `2CM`,
`Export Price List For Big Slab ` (note the trailing space).

**Measured shape:** 135 non-empty rows after the header, of which **103 carry a size + price**
(the other 32 are section bands and continuation rows). 86 of those 103 carry a comma-enumerated
colour list, which expands to roughly **301 SKUs**. Only **15 distinct sizes** exist across the
whole file — which is exactly why D1 is fatal here.

This is the **hardest** structure of the ten. It needs nine engine capabilities:

| # | Feature | Example | Engine support |
|---|---------|---------|----------------|
| 1 | **No product code at all** | only SERIES + COLOR + SURFACE + SIZE | ❌ blocked by **D1** |
| 2 | Section bands interleaved in data | `1、90X180CM`, `MARBLE`, `STONE & CONTEMPORARY` | ❌ must skip, ideally capture as category |
| 3 | **COLOR is a comma-enumerated list** | `1-BEIGE, 2-GREIGE, 3-GRIGIO, 4-…` → N SKUs from 1 row | ⚠️ Bode's `split_column` splits on `,` but does not strip the `N-` index prefix |
| 4 | **Three price tiers per row** | `LCL(EXW)<500sqm` / `LCL(EXW)>500sqm` / `FCL(FOB)` | ⚠️ Equipe's `price_column_rule` + `price_variants` fits — needs a CEO-selected default |
| 5 | Non-numeric price cells | `MOQ 1000SQM` in the `<500sqm` column | ❌ must route to `attributes.moq`, not an error |
| 6 | Size embeds thickness | `60X60X0.95` → 600 × 600 × 9.5 mm | ❌ current parsers expect 2-part |
| 7 | Per-sheet header row differs | 7 on six sheets, **5** on Big Slab | ✅ per-sheet `header_row` already supported |
| 8 | Big Slab has 4 extra columns | `PACKING FEE (USD/PC)`, `PACKAGE`, `20' CONTAINER`, `40' CONTAINER` | ⚠️ → `attributes` |
| 9 | **Trailing REMARKS = surcharge rules** | `15*30*0.95CM-ADD USD3.40/SQM` | ❌ real pricing modifiers with nowhere to live |
| 10 | Fill-down on SN + SERIES | merged cells, surface variants on continuation rows | ✅ `fill_down` supported |
| 11 | No currency column | USD implied only by the remarks text | ✅ `defaults.currency` |
| 12 | Newlines / full-width chars | `NATURALE \n(MIX BODY)`, `RIVERSTONE（NEWS)` | ⚠️ needs whitespace + full-width normalisation |

**Item 9 needs an owner decision before build.** Those surcharge rules
(`30*30*0.95CM-ADD USD…`, `15*15*0.95CM-ADD USD6.6/SQM`) are per-size price adders that the
current schema cannot express. Options: (a) capture into `attributes.surcharge_note` as text for
manual application, (b) expand into real rows per size, (c) new `price_modifiers` table.
Recommend **(a)** for this pass — it is honest, loses nothing, and defers a schema change.

---

## 4. Remediation plan

Sequenced so each stage is independently mergeable and verifiable. **Stage 1 must land before
Stage 4** — importing file #10 on today's engine would silently destroy ~78% of it.

### Stage 0 — Freeze & snapshot (no code)
- Confirm `_product_prices_backup` is a valid pre-migration snapshot; document or drop it.
- Record current per-factory row counts as the regression baseline (table in §1).
- ⚠️ **Note:** `CLAUDE.md` freezes the sales/CRM stack, and `price_catalog` is part of it.
  Stages 1–5 need an explicit owner exemption before implementation starts.

### Stage 1 — Fix the degenerate key (**CRITICAL — do first**)
Branch: `fix/catalog-surrogate-product-code`
1. `ImportEngine`: when `allow_missing_code` and the source has no code, synthesise
   `AUTO-<sha1(sheet|collection|name|size|surface)[:12]>`. Deterministic, so re-imports are stable.
2. Re-import REFIN into a new version; assert **Trim-Tiles = 74** and **Balneo-Project = 19**.
3. Mutation check: revert step 1, confirm the assertion in step 2 **fails**. A green test here
   proves nothing unless the fixture actually contains code-less rows.
4. Verify `LAVABO SOSPESO CON SCARICO A SCOMPARSA` €4,028 / €4,273 is present.

### Stage 2 — Normalise the key
Branch: `fix/catalog-size-normalisation`
1. Add `size_norm` (generated: upper + strip all whitespace); rebuild `uq_price` on `size_norm`.
2. Keep `size_raw` untouched for display.
3. Assert REFIN case-duplicates drop 34 → 17.
4. Re-verify all 9 factories still reconcile to §1 (this constraint change is the highest
   regression risk in the plan — a too-aggressive normalisation will *merge* legitimate rows).

### Stage 3 — Unit & attribute correctness
Branch: `fix/catalog-uom-and-attributes`
1. Map CDE `UOM = 'M'` → `per_linear_m`; assert `unknown` goes 164 → 0.
2. Populate `attributes` for REFIN / Bode / CITY (barcode + raw UOM passthrough).
3. Record dedup provenance in `attributes.also_in_sheets` (fixes D5).
4. Add `stop_at_regex` / footer detection so Bode and Vives report `error_count = 0` (D7).

### Stage 4 — Onboard the missing factory (**after Stage 1**)
Branch: `feat/catalog-general-export-factory`
1. **Ask the owner the factory's real name** — the filename does not identify it and the workbook
   only says `CHINA`. Do not invent one.
2. Engine additions: section-band skip, `N-` prefix strip on colour explode, 3-part size parse,
   non-numeric price → `attributes.moq`, full-width/newline normalisation.
3. New profile with per-sheet `header_row` (7 × 6 sheets, 5 for Big Slab), `fill_down: [SN, SERIES]`,
   `price_column_rule` over the three tiers with `keep_all_as: price_variants`,
   `defaults: {currency: USD}`.
4. Capture the REMARKS surcharge block into `attributes.surcharge_note` (pending owner ruling).
5. Assert **103 priced rows before colour explode**, expanding to **~301 SKUs** after. Confirm the
   expanded count with the owner before activating the version.

### Stage 5 — Version hygiene
Branch: `chore/catalog-version-metadata`
1. Backfill `effective_from` (Panaria states `2026-01-01`; Bode's quote is dated `2026-06-23`;
   ask the owner for the rest — **do not guess**).
2. Archive the orphan CDE `version_id = 1`.
3. Add a guard rejecting an upload whose `effective_from` predates the current ACTIVE version.

---

## 5. What is explicitly NOT wrong

Worth stating so nobody "fixes" these:
- **Padana's 5,086 same-code rows are correct** — `Scelta` A01/A02 are grade 1 / grade 2 at
  different prices. Including `grade` in the key, Padana has **zero** true duplicates.
- **CITY's −9 is correct dedup**, verified field-by-field across all 9 codes.
- **REFIN's `RELIEFS_recap` and `OUT2.0_recap` collapsing to zero is correct** — they are recap
  views of `Collections` with identical prices.
- **Bode's 215 rows from 44 source rows is correct** — the comma-split expansion works.
- **Vives' −1 and Bode's −4 are footer text**, correctly rejected.
- Padana's workbook has a corrupt stylesheet (openpyxl cannot open it; Apache POI can). The
  importer is unaffected — a LibreOffice re-save confirms 9,076 rows, matching prod exactly.

---

## 6. Verification queries

```sql
-- Per-factory reconciliation vs the §1 baseline
SELECT f.name, v.status, v.row_count, count(p.price_id) AS actual
FROM price_catalog.price_list_versions v
JOIN price_catalog.factories f USING (factory_id)
LEFT JOIN price_catalog.product_prices p ON p.version_id = v.version_id
GROUP BY 1,2,3 ORDER BY 1;

-- D1 regression guard: code-less rows must survive
SELECT source_sheet, count(*) FROM price_catalog.product_prices
WHERE version_id = (SELECT version_id FROM price_catalog.price_list_versions v
                    JOIN price_catalog.factories f USING (factory_id)
                    WHERE f.name='REFIN' AND v.status='ACTIVE')
GROUP BY 1;   -- expect Trim-Tiles = 74, Balneo-Project = 19 after Stage 1

-- D2 guard: case-only duplicates
SELECT count(*) FROM (
  SELECT version_id, upper(btrim(product_code)) c,
         upper(regexp_replace(btrim(size_raw),'\s+','','g')) s
  FROM price_catalog.product_prices WHERE product_code IS NOT NULL
  GROUP BY 1,2,3 HAVING count(*) > 1) x;   -- expect 0 after Stage 2

-- D3 guard
SELECT count(*) FROM price_catalog.product_prices
WHERE price_unit = 'unknown';              -- expect 0 after Stage 3
```
