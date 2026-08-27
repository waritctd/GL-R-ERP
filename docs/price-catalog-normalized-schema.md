# Price Catalog — Normalized Schema for the CEO Selling-Price Formula

**Date:** 2026-08-17
**Goal:** make `price_catalog` a schema the CEO's selling-price formula can consume directly.
**Companion:** [`price-catalog-reconciliation-2026-08-17.md`](./price-catalog-reconciliation-2026-08-17.md)

**Owner rulings applied (2026-08-17):**
1. **Scope is tiles only.** Sanitaryware / trim / accessory get no separate duty treatment —
   everything in the catalog prices as `TILE` 30%.
2. **Thickness comes from per-collection defaults, CEO-configurable** alongside the other CEO settings.
3. **Linear-metre products convert to sqm mathematically**, and the conversion is CEO-visible.
4. **Per-tile spec wins; conservative only when the spec is unknown.** *"If it has specific
   information for that tile it should be according to each tile."* — see §4.5, which shows this
   clause resolves the `max()` question, and §4.6 for why `max()` cannot carry the conservatism.
5. **Units normalized end-to-end** so sales never sees factory vocabulary — §4.8.
6. **`price_catalog` is unfrozen** for this work. The `CLAUDE.md` sales/CRM freeze no longer
   blocks Stages 1–7.

---

## 1. The formula, and what it demands of the catalog

From `สูตรคำนวนราคาขาย (1).xlsx`, matching `V109__pricing_formula_config.sql`:

```
C  = P × E                                       goods cost in THB
i  = C × 1.15 × 0.0045 × B1                      insurance
F  = freight[origin_country, thickness_mm, qty_sqm]     flat THB per shipment
T  = duty_pct[TILE] = 30%                        (tiles-only scope)
S  = clearance[qty_sqm]                          flat THB per shipment
TC = [ (C + i + F) × (1 + T) × B2 ] + S
UC = TC / Q                                      Q = shipment total in **sqm**
SP = RoundUp[ UC × (1 + M) × B3 , to nearest 10 ]
```

B1/B2/B3 are the three ×1.07 **cost buffers — not VAT** (VAT is added later on the quotation).

With the tiles-only ruling, the formula joins the catalog on **three** dimensions, not four:

| Symbol | Join key needed | Catalog today | Status |
|--------|-----------------|---------------|--------|
| `P`, `E` | `price` + `currency` → `sales.fx_rates` | `price`, `currency` | ✅ works |
| `Q` | price expressed **per sqm** | `price_unit` + `sqm_per_piece` / `sqm_per_box` | ⚠️ 585 rows |
| `F` | `(origin_country, thickness_mm, qty_sqm)` | `factories.country`, `thickness_mm` | ❌ 9,411 rows |
| `T` | constant `TILE` 30% | — | ✅ **resolved by ruling 1** |

### Measured priceability

| Metric | Rows | % |
|--------|-----:|--:|
| Total in `product_prices` | 22,455 | 100% |
| Can resolve freight key + sqm basis today | 12,490 | 55.6% |
| Blocked — no usable `thickness_mm` | 9,411 | 41.9% |
| Blocked — no sqm basis | 585 | 2.6% |
| **Blocked on duty** | **0** | **0%** — was 100% before ruling 1 |

**After the work below, the residual is 24 rows** — 3 with out-of-band thickness, 21 with no
parseable dimensions. **22,431 rows priceable (99.893%)**, simulated against live prod data in §6.

---

## 2. State of the implementation

Precise, because it changes what needs building:

- ✅ **V109 config storage + CEO editing UI is implemented** — `sales.pricing_formula_config`,
  `pricing_freight_rate` (39 rows), `pricing_duty_rate`, `pricing_clearance_fee` (4 rows), plus
  `PricingFormulaConfigRepository` and `CeoSettingsPage.jsx`.
- ❌ **The engine that applies the formula does not exist yet.** Only
  `PricingFormulaConfigRepository` (`findCurrent` / `createNewVersion`) touches those tables — no
  code computes `TC`/`UC`/`SP`. Matches the migration's own comment: *"The engine that reads this
  config is a later branch."*
- ⚠️ **The older `PriceCalcService` (V26) cannot serve the real catalog.** It resolves factory
  country from `sales.factory_config`, which holds 4 stale demo rows — `Cotto Industry`,
  `Duragres Thailand`, `SCG Ceramics`, `Panaria SpA`. **None matches a real catalog factory**
  (`Panaria SpA` ≠ `Panaria`), so every lookup falls through.

---

## 3. Defects that block the formula structurally

### F1 — `origin_country` will never join

| Side | Column | Type | Values |
|------|--------|------|--------|
| Catalog | `price_catalog.factories.country` | `CHAR(2)` nullable | `IT`, `ES`, `CN` |
| Formula | `sales.pricing_freight_rate.origin_country` | `VARCHAR(100)` | `Italy`, `Spain`, `China` |

Free text on one side, ISO code on the other, no FK. **Every freight lookup returns nothing.**
The Excel puts Italy and Spain on identical rates, but V109 seeded them as separate rows — so ISO
codes map 1:1 and no grouping logic is needed.

### F2 — `thickness_mm` missing for 41.9%, and it is not an import bug

| Factory | Rows | NULL thickness | Collections to configure | Source has thickness? |
|---------|-----:|---------------:|-------------------------:|-----------------------|
| Vives | 4,617 | **4,617 (100%)** | 93 | ❌ no |
| Padana | 9,076 | 2,435 | 56 | ❌ no (`Spessore` absent from header) |
| Equipe | 2,124 | 2,122 | 69 | ❌ no |
| Bode | 215 | 209 | 22 | ❌ no |
| REFIN | 1,664 | 22 | 2 | ✅ yes |
| LEA | 1,626 | 3 | 1 | ✅ yes |
| CDE / CITY / Panaria | 3,133 | 0 | — | ✅ yes |
| **Total** | | **9,408** | **244** | |

Four of nine workbooks genuinely lack thickness, so no parser change can fix this. **But the
configuration surface is small: 244 (factory, collection) pairs** — comparable to the 39 freight
rows the CEO already maintains. Ruling 2 is therefore practical.

3 further rows carry a thickness outside the seeded bands `[3, 21)` and match nothing.

### F3 — `sqm_per_box` holds **linear metres** for per-metre products *(new — found while verifying ruling 3)*

For `price_unit = 'per_linear_m'` rows, the source `m²/Box` column does not contain m²:

| Product | Size | pcs/box | `sqm_per_box` | ÷ pcs | tile length | **true** m²/pc |
|---------|------|--------:|--------------:|------:|------------:|---------------:|
| CITY `AVORIO BATTISCOPA R.` | 7×60 | 10 | 6.0000 | 0.6000 | **0.600 m** | 0.0420 |

`6 ÷ 10 = 0.60` is exactly the tile **length**, not its area (0.042 m²). The column holds
**linear metres per box**, overstating area by **14.3×**.

This matters because `Q` is the shipment total in sqm and drives both the freight and clearance
bands. A mixed order that sums `sqm_per_box` naively inflates `Q`, pushing the shipment into a
higher band — a silent five-figure THB error. §4.5 corrects it.

---

## 4. Proposed normalized schema

Design rule: **every formula join key becomes a constrained, referenced column.** No free text, no
silent defaults; rows that cannot be priced say so rather than pricing wrongly.

### 4.1 `price_catalog.country` — one canonical country (fixes F1)

```sql
CREATE TABLE price_catalog.country (
    country_code CHAR(2) PRIMARY KEY,          -- ISO 3166-1 alpha-2
    name_en      TEXT NOT NULL,
    name_th      TEXT NOT NULL
);
INSERT INTO price_catalog.country VALUES
    ('IT','Italy','อิตาลี'), ('ES','Spain','สเปน'),
    ('CN','China','จีน'),    ('TH','Thailand','ไทย');

ALTER TABLE price_catalog.factories
    ALTER COLUMN country SET NOT NULL,
    ADD CONSTRAINT fk_factories_country
        FOREIGN KEY (country) REFERENCES price_catalog.country(country_code);

ALTER TABLE sales.pricing_freight_rate
    ADD COLUMN origin_country_code CHAR(2) REFERENCES price_catalog.country(country_code);
UPDATE sales.pricing_freight_rate SET origin_country_code =
    CASE origin_country WHEN 'Italy' THEN 'IT' WHEN 'Spain' THEN 'ES'
                        WHEN 'China' THEN 'CN' END;
ALTER TABLE sales.pricing_freight_rate
    ALTER COLUMN origin_country_code SET NOT NULL,
    DROP COLUMN origin_country;
```

### 4.2 Duty — no new dimension needed *(ruling 1)*

Tiles-only scope means duty is a **constant lookup of `TILE` = 30%**, already seeded in
`sales.pricing_duty_rate`. Consequences:

- ❌ **No `product_type` column is added to `product_prices`.** One less dimension to maintain.
- ❌ **The keyword-classification backfill is dropped entirely.** It would have reclassified
  2,071 rows (9.2% of the catalog) on a `TORO|BORDO|ANGOLARE|…` regex that also matches full-tile
  product names — a duty error on every false positive. Ruling 1 removes that risk at the root.
- ✅ `GLASS_MOSAIC` (10%) stays seeded and unused, harmless, ready if scope widens.

The engine resolves duty as `duty_pct WHERE product_type = 'TILE'` — one row, no per-product join.

### 4.3 `product_prices` — canonical pricing basis

```sql
ALTER TABLE price_catalog.product_prices
    -- normalized dedup key (also fixes D2 in the reconciliation report)
    ADD COLUMN size_norm TEXT GENERATED ALWAYS AS
        (upper(regexp_replace(btrim(coalesce(size_raw,'')), '\s+', '', 'g'))) STORED,

    -- profile height of a per-metre trim piece, in metres (NULL for area products)
    ADD COLUMN sqm_per_linear_m NUMERIC(10,6),

    -- P expressed per sqm: the single value the formula consumes and the CEO sanity-checks
    ADD COLUMN price_per_sqm NUMERIC(14,6) GENERATED ALWAYS AS (
        CASE price_unit
            WHEN 'per_sqm'      THEN price
            WHEN 'per_piece'    THEN price / NULLIF(sqm_per_piece,    0)
            WHEN 'per_box'      THEN price / NULLIF(sqm_per_box,      0)
            WHEN 'per_linear_m' THEN price / NULLIF(sqm_per_linear_m, 0)
            ELSE NULL
        END
    ) STORED,

    ADD COLUMN pricing_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (pricing_status IN
               ('PRICEABLE','NO_SQM_BASIS','NO_THICKNESS','NO_DIMENSIONS','UNVERIFIED'));
```

`price_per_sqm` is **generated**, so it can never drift from its inputs. For that to hold, the
importer must first normalize `sqm_per_piece` using a two-step fallback:

```
1. sqm_per_piece = sqm_per_box / pcs_per_box            when the box figures exist
2. sqm_per_piece = width_mm × height_mm / 1,000,000     from the parsed size
3. otherwise → pricing_status = 'NO_DIMENSIONS'
```

**Step 2 is load-bearing and easy to miss.** 132 `per_piece` rows have *neither* `sqm_per_piece`
*nor* `sqm_per_box`, so step 1 cannot help them — measured, step 1 alone leaves 133 rows blocked.
Step 2 recovers **112 of those 132** from their parsed dimensions (LEA 72, REFIN 30, Vives 10),
leaving 20. The same geometric rule is what §4.5 applies to per-metre products.

### 4.4 `price_catalog.collection_thickness_default` — CEO-configurable *(ruling 2)*

```sql
CREATE TABLE price_catalog.collection_thickness_default (
    default_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_id   BIGINT NOT NULL REFERENCES price_catalog.factories(factory_id),
    collection   TEXT,                     -- NULL = whole-factory fallback
    size_norm    TEXT,                     -- NULL = whole-collection (the normal case)
    thickness_mm NUMERIC(8,2) NOT NULL CHECK (thickness_mm > 0),
    note         TEXT,
    updated_by   BIGINT REFERENCES hr.employee(employee_id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_collection_thickness UNIQUE NULLS NOT DISTINCT
        (factory_id, collection, size_norm)
);
```

**Resolution order, most specific first:**
`product_prices.thickness_mm` → `(factory, collection, size_norm)` → `(factory, collection)` →
`(factory)` → **unresolved ⇒ `pricing_status = 'NO_THICKNESS'`**.

**No global default.** A guessed thickness selects a freight band that can differ by ฿50,000 per
shipment; refusing to price is the correct failure.

Expected CEO workload: **244 collection-level rows**. `size_norm` stays NULL for almost all of them
— it exists only for collections where a trim piece differs from its field tile.

**CEO settings UI** — a new *"ความหนาเริ่มต้นตามคอลเลกชัน"* section beside the existing
freight/duty/clearance editors, grouped by factory, showing affected row count per collection and
flagging any collection still unset.

### 4.5 Linear-metre → sqm conversion, defined mathematically *(ruling 3)*

A trim piece sold by the linear metre has a rectangular face of `profile_height × length`. One
linear metre of it covers `profile_height` square metres.

**Resolution hierarchy** (ruling 4 — per-tile spec wins):

```
1. CEO override for this collection/size        → use it
2. The tile's own parsed dimensions             → sqm_per_linear_m = min(width_mm,height_mm)/1000
3. Neither available                            → pricing_status = 'NO_DIMENSIONS'
```

**Why step 2 uses `min`, not `max`.** Ruling 4 says *per-tile spec wins*, and a row that has both
`width_mm` and `height_mm` **is** a tile with a known spec — its geometry is not a guess. For a
7×60 cm battiscopa the profile is unambiguously the 7 cm edge; the 60 cm is the piece's length,
already accounted for by "per linear metre". So the per-tile branch takes the true geometry.
`max()` would claim one linear metre of skirting covers 0.60 m² instead of 0.07 m² — an **8.6×**
overstatement of physical area, which is what sales and the packing list would then show.

The conservatism ruling 4 asks for still applies — but to the *unknown* case, and via §4.6 rather
than by distorting a known geometry.

**Worked example** — CITY `RQ81 AVORIO BATTISCOPA R.`, 7×60 cm, €22.00/ml:

```
sqm_per_linear_m = min(70, 600) / 1000 = 0.07 m²
price_per_sqm    = 22.00 / 0.07        = €314.29 / m²
```

Validation across all 453 per-metre rows: profile heights resolve to **55–100 mm** (CDE bullnose
55–100, CITY and REFIN battiscopa 70) — all plausible skirting/bullnose profiles. **452 of 453
resolve**; one REFIN row has no parseable dimensions and becomes `NO_DIMENSIONS`.

Note the goods cost `C` is **invariant** to this choice — `price_per_sqm × sqm` always equals
`price_per_linear_m × linear_metres`, so the factor cancels. It only moves `Q`, which is exactly
why §4.6 matters.

**This also fixes F3.** Once `sqm_per_linear_m` exists, the mislabelled box quantity is corrected
on read rather than trusted:

```sql
true_sqm_per_box = sqm_per_box × sqm_per_linear_m      -- 6.0 ml × 0.07 = 0.42 m²
```

The shipment total `Q` must use `true_sqm_per_box` for per-metre lines, never the raw column.

**CEO settings UI** — a *"การแปลงหน่วยเมตรเชิงเส้นเป็นตารางเมตร"* panel stating the rule and the
worked example, plus a per-collection override of the effective profile height (hierarchy step 1)
for factories whose trim deviates.

### 4.6 Conservatism for unknown specs — an explicit uplift, not a distorted geometry

Ruling 4 wants cost over-stated rather than under-stated when a tile's spec is unknown. Inflating
the area factor cannot deliver that, for two measured reasons.

**Reason 1 — freight is not monotonic in quantity.** Overstating `Q` pushes the shipment into a
higher qty band, and for China that band is sometimes *cheaper*:

| Origin | Thickness | Freight by qty band (1‑101 → 101‑451 → 451‑801 → 801+) | Rises with Q? |
|--------|-----------|--------------------------------------------------------|---------------|
| China | 3–8 mm | 60,000 → 60,000 → **50,000** → 50,000 | ❌ **falls** |
| China | 8–12 mm | 30,000 → 50,000 → 70,000 → **50,000** | ❌ **falls** |
| China | 12–17 mm | 30,000 → 50,000 → 70,000 | ✅ |
| Italy / Spain | all bands | monotonically increasing | ✅ |

So for Chinese product — Bode today, plus the unimported GENERAL EXPORT factory — an inflated `Q`
**reduces** freight by up to ฿20,000. `max()` would achieve the opposite of the intent.

**Reason 2 — `UC = TC / Q` divides by the same `Q`.** A larger `Q` mechanically lowers unit cost,
so the per-m² figure sales quotes drops. If a customer applies that rate to the tile's *true* area,
they are undercharged by the same 8.6×.

**Recommended instead** — a visible multiplier that can only ever move cost upward:

```sql
ALTER TABLE sales.pricing_formula_config
    ADD COLUMN unknown_spec_uplift_pct NUMERIC(8,6) NOT NULL DEFAULT 0.150000
        CHECK (unknown_spec_uplift_pct >= 0 AND unknown_spec_uplift_pct <= 1);
```

Applied to `TC` only when a line's spec was defaulted rather than known:

```
TC_final = TC × (1 + unknown_spec_uplift_pct)      when thickness or area came from a fallback
```

This is directional by construction, auditable per line, tunable by the CEO beside the other
buffers, and leaves physical area honest for sales and the packing list. **Default 15% is a
placeholder — the CEO should set it.**

### 4.7 `price_catalog.v_priceable_product` — the engine's only surface

```sql
CREATE VIEW price_catalog.v_priceable_product AS
SELECT p.price_id, p.factory_id, f.name AS factory,
       f.country AS origin_country_code,
       p.product_code, p.grade, p.collection, p.product_name,
       p.size_raw, p.size_norm,
       COALESCE(p.thickness_mm, d.thickness_mm) AS thickness_mm,
       'TILE'::VARCHAR(32) AS product_type,          -- tiles-only scope
       p.currency, p.price AS source_price, p.price_unit,
       p.price_per_sqm, p.sqm_per_linear_m,
       CASE WHEN p.price_unit = 'per_linear_m'
            THEN p.sqm_per_box * p.sqm_per_linear_m  -- F3 correction
            ELSE p.sqm_per_box END AS true_sqm_per_box,
       p.pricing_status,
       (p.pricing_status = 'PRICEABLE') AS is_priceable
FROM price_catalog.product_prices p
JOIN price_catalog.factories           f ON f.factory_id = p.factory_id
JOIN price_catalog.price_list_versions v ON v.version_id = p.version_id
LEFT JOIN LATERAL (
    SELECT t.thickness_mm FROM price_catalog.collection_thickness_default t
     WHERE t.factory_id = p.factory_id
       AND (t.collection IS NULL OR t.collection = p.collection)
       AND (t.size_norm  IS NULL OR t.size_norm  = p.size_norm)
     ORDER BY (t.size_norm IS NOT NULL) DESC, (t.collection IS NOT NULL) DESC
     LIMIT 1
) d ON TRUE
WHERE v.status = 'ACTIVE';
```

The `status = 'ACTIVE'` filter matters: nothing enforces it today, and the orphan CDE `DRAFT`
version 1 proves stale versions linger.

### 4.8 Unit normalization — one vocabulary for sales *(ruling 5)*

Today a salesperson looking across the catalog meets **eleven** source unit strings meaning four
things, plus a column whose meaning changes per row:

| Confusion | Detail |
|-----------|--------|
| Source UOM vocabulary | `MQ`, `M2`, `mq`, `PC`, `PZ`, `pcs`, `set`, `Pieza`, `Caja`, `ml`, `M` |
| `price_unit` | 5 values incl. a meaningless `unknown` (165 rows) |
| `sqm_per_box` | holds **linear metres** for per-metre rows (F3) — 14.3× wrong |
| `size_raw` | 5 formats: `20 x 20`, ` 150x600`, `080x600`, `15'8X31'6`, `60X60X0.95` |
| Two prices per row | source-unit price and derived per-m² price, unlabelled |

**Fix: separate _how it is sold_ from _how it is priced_.** Pricing is always per m² (the formula
demands it); selling stays in whatever the customer orders.

```sql
ALTER TABLE price_catalog.product_prices
    ADD COLUMN order_unit VARCHAR(12)
        CHECK (order_unit IN ('SQM','PIECE','BOX','LINEAR_M')),
    ADD COLUMN sqm_per_order_unit   NUMERIC(12,6),   -- true physical m² per one order unit
    ADD COLUMN price_per_order_unit NUMERIC(14,4);   -- price of one order unit, source currency
```

Canonical mapping applied at import — no factory vocabulary survives into the schema:

| Source strings | `order_unit` |
|----------------|--------------|
| `MQ`, `M2`, `mq` | `SQM` |
| `PC`, `PZ`, `pcs`, `set`, `Pieza` | `PIECE` |
| `Caja` | `BOX` |
| `ml`, `M` | `LINEAR_M` |

**`unknown` is retired.** After the CDE `UOM = 'M'` fix (D3) every row maps to a real unit, so the
value is dropped from the `price_unit` CHECK rather than left as a silent bucket.

**Canonical size display**, derived from the parsed millimetres so all 5 source formats collapse:

```sql
ADD COLUMN size_display TEXT GENERATED ALWAYS AS (
    CASE WHEN width_mm IS NULL OR height_mm IS NULL THEN size_raw
         ELSE trim(to_char(width_mm/10,'FM999990.9') || '×' ||
                   to_char(height_mm/10,'FM999990.9') || ' cm' ||
                   COALESCE(' · ' || to_char(thickness_mm,'FM999990.9') || ' mm', ''))
    END) STORED;
```

`60×120 cm · 9 mm` — one format, every factory. `size_raw` is retained for audit only.

**What each audience reads:**

| Audience | Columns |
|----------|---------|
| Sales / quotation UI | `size_display`, `order_unit`, `price_per_order_unit`, `currency` |
| Pricing engine | `price_per_sqm`, `sqm_per_order_unit`, `thickness_mm`, `origin_country_code` |
| Audit / import debugging | `size_raw`, `price_unit`, `price`, `source_sheet`, `source_row` |

Sales never sees `sqm_per_box`, `MQ`, or `080x600`. The engine never guesses a unit.

> **Currency stays in source currency (EUR/USD) in the catalog.** THB appears only on a quotation,
> where the FX rate is snapshotted with a date. Storing a THB price in the catalog would go stale
> silently — the one place a "normalized" unit would do harm.

### 4.9 Retire the duplicate factory registry

`sales.factory_config` (4 stale demo rows) and `price_catalog.factories` (9 real) are two
registries of one concept, and `PriceCalcService` reads the wrong one. Consolidate onto
`price_catalog.factories`, moving `factory_config.email` across.

---

## 5. Migration sequence

Stages 1–2 are the reconciliation report's D1/D2 and remain prerequisites — normalizing rows that
were never imported fixes nothing.

| Stage | Branch | Delivers | Gate |
|-------|--------|----------|------|
| **1** | `fix/catalog-surrogate-product-code` | surrogate codes; recovers REFIN's 63 lost rows | Trim-Tiles = 74, Balneo = 19 |
| **2** | `feat/catalog-country-normalization` | §4.1 country lookup + FK both sides | freight lookup returns a row for all 9 factories |
| **3** | `feat/catalog-sqm-basis` | §4.3 `size_norm`, `price_per_sqm`, 2-step `sqm_per_piece` derivation | `NO_SQM_BASIS` 585 → 473 (both steps) |
| **4** | `feat/catalog-linear-metre-conversion` | §4.5 conversion + F3 box correction + CEO panel | `NO_SQM_BASIS` 473 → 21 |
| **5** | `feat/catalog-collection-thickness` | §4.4 table + resolution + CEO editor | `NO_THICKNESS` 9,411 → 3 once 244 rows are filled |
| **6** | `feat/catalog-unit-normalization` | §4.8 `order_unit`, `size_display`; retire `unknown` | 0 rows `unknown`; 1 size format |
| **7** | `feat/catalog-priceable-view` | §4.7 + retire `factory_config` | view returns ACTIVE rows only |
| **8** | `feat/pricing-formula-engine` | the engine, reading §4.7, incl. §4.6 uplift | reproduces the Excel worked example exactly |

`price_catalog` is **unfrozen** as of 2026-08-17, so these proceed without a further exemption.
One branch each, one implementation agent per branch, per `CLAUDE.md`.

**Stage 7 acceptance:** feed the Excel's own inputs through the engine and match `SP` to the
satang. Half-open band boundaries `[min, max)` are the highest-risk detail — V109's header calls
this out explicitly — so test values landing exactly on **101, 451, 801 sqm** and **8, 12, 17 mm**.

**Stage 4 acceptance:** assert `price_per_sqm = 314.285714` for CITY `RQ81` (€22.00 ÷ 0.07), and
assert `true_sqm_per_box = 0.42` where the raw column says `6.0`.

---

## 6. Residual after all stages

Simulated against live prod data with every rule above applied:

| Condition | Rows | Handling |
|-----------|-----:|----------|
| Thickness outside seeded bands `[3, 21)` | 3 | CEO extends a band or sets a size-level default |
| No parseable dimensions ⇒ no sqm basis | 21 | `NO_DIMENSIONS`, quote manually |
| **Everything else** | **22,431** | **`PRICEABLE` (99.893%)** |

The 21 are `per_piece` and per-metre rows whose `size_raw` never parsed into `width_mm`/`height_mm`
(2 LEA, 18 REFIN, 1 REFIN per-metre). Fixing the size parser for them is optional cleanup, not a
blocker — 21 rows is a reasonable manual-quote tail.

---

## 7. Open items

1. **CEO to supply 244 collection-level thickness defaults** (Stage 5). Largest single input;
   Vives (93), Equipe (69) and Padana (56) are the bulk. The editor should ship first so data entry
   can run in parallel with Stages 6–8.
2. **CEO to set `unknown_spec_uplift_pct`** (§4.6). Default 15% is a placeholder, not a
   recommendation.
3. **Confirm `min(width, height)` for known geometry** — §4.5 argues this follows from ruling 4's
   own "according to each tile" clause, and §4.6 shows `max()` would invert the intent on Chinese
   freight. Flagging explicitly because it reads against the letter of the `max()` instruction.

*Closed by owner ruling 2026-08-17: tiles-only scope; per-collection CEO-configured thickness;
mathematical linear-metre conversion; unit normalization; **`price_catalog` unfrozen**.*
