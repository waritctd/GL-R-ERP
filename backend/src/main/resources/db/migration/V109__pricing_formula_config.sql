-- BRANCH 1 of the sales pricing-formula redesign: config storage + CEO editing UI ONLY.
--
-- Stores the CEO's real selling-price formula (his own spreadsheet) as versioned config:
--   C  = P x E                                  goods cost in THB
--   i  = C x insurance_value_factor x insurance_rate x insurance_buffer   (1.15 x 0.0045 x B1)
--   F  = freight[origin_country, thickness_mm, qty_sqm]  flat THB, per factory shipment
--   T  = duty_pct[product_type]
--   S  = clearance_fee[qty_sqm]                  flat THB, per factory shipment
--   TC = [ (C + i + F) x (1 + T) x cost_buffer ] + S            (B2)
--   UC = TC / Q                                  Q = that factory shipment's total sqm
--   SP = RoundUp[ UC x (1 + margin_pct) x selling_buffer , to nearest selling_price_round_up_to ]
--
-- insurance_buffer / cost_buffer / selling_buffer (B1/B2/B3) are deliberate cost-buffer
-- multipliers -- NOT VAT. The customer quotation separately adds VAT 7%, unchanged by this table.
--
-- ============================================================================================
-- BAND CONVENTION -- freight thickness_mm/qty_sqm bands and clearance qty_sqm bands are all
-- HALF-OPEN: [min, max) -- min INCLUSIVE, max EXCLUSIVE. A NULL max means +infinity (the
-- open-ended top band). A value x falls in a band iff:
--
--       min <= x  AND  (max IS NULL OR x < max)
--
-- The pricing engine (a later branch) MUST implement the lookup with EXACTLY that test. Getting
-- either boundary wrong (e.g. using <= against max) mis-prices every deal that lands exactly on
-- a band edge. quantity_sqm and thickness_mm are NUMERIC, not integer -- orders routinely use
-- fractional sqm/mm -- so bands are seeded CONTIGUOUS with no gap: band N's max always equals
-- band N+1's min (e.g. [1,101) is immediately followed by [101,451), never [1,100] / [101,450]).
-- ============================================================================================
--
-- This migration ONLY adds config storage + seed data. It does not touch PricingCostingService,
-- PricingDecisionService, PriceCalcService, or sales.price_calc_config (that table keeps serving
-- the separate catalog price calculator, untouched). The engine that reads this config is a later
-- branch.
--
-- Versioning model: ONE parent row (sales.pricing_formula_config) owns three child tables
-- (freight/duty/clearance). A costing can reference a single formula_config_id and reproduce the
-- exact snapshot used at the time. Children are NEVER versioned independently -- a new version
-- always inserts a full new parent + full new set of children; old versions and their children are
-- retained for audit, never UPDATEd or DELETEd.

CREATE TABLE sales.pricing_formula_config (
    formula_config_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version                   INT           NOT NULL DEFAULT 1,
    insurance_value_factor    NUMERIC(12,6) NOT NULL DEFAULT 1.150000,   -- the 1.15
    insurance_rate            NUMERIC(12,6) NOT NULL DEFAULT 0.004500,   -- the 0.0045
    insurance_buffer          NUMERIC(12,6) NOT NULL DEFAULT 1.070000,   -- B1
    cost_buffer               NUMERIC(12,6) NOT NULL DEFAULT 1.070000,   -- B2
    selling_buffer            NUMERIC(12,6) NOT NULL DEFAULT 1.070000,   -- B3
    default_margin_pct        NUMERIC(8,6)  NOT NULL DEFAULT 0.200000,
    selling_price_round_up_to NUMERIC(12,4) NOT NULL DEFAULT 10.0000,
    is_current                BOOLEAN       NOT NULL DEFAULT TRUE,
    effective_from            DATE          NOT NULL DEFAULT CURRENT_DATE,
    updated_by                BIGINT REFERENCES hr.employee(employee_id),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_pricing_formula_config_nonnegative CHECK (
        insurance_value_factor >= 0 AND insurance_rate >= 0 AND insurance_buffer >= 0
        AND cost_buffer >= 0 AND selling_buffer >= 0 AND selling_price_round_up_to > 0
    ),
    CONSTRAINT chk_pricing_formula_config_margin_range CHECK (
        default_margin_pct >= 0 AND default_margin_pct <= 1
    )
);

-- At most one current config row at a time -- a unique partial index on a column that is only
-- ever TRUE in matching rows enforces the singleton (same trick as a unique index on a boolean
-- flag column, scoped by the WHERE predicate).
CREATE UNIQUE INDEX ux_pricing_formula_config_current
    ON sales.pricing_formula_config (is_current) WHERE is_current;

CREATE TABLE sales.pricing_freight_rate (
    freight_rate_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    formula_config_id  BIGINT NOT NULL REFERENCES sales.pricing_formula_config(formula_config_id) ON DELETE CASCADE,
    origin_country      VARCHAR(100)  NOT NULL,
    thickness_min_mm    NUMERIC(8,2)  NOT NULL,
    thickness_max_mm    NUMERIC(8,2)  NOT NULL,
    qty_min_sqm         NUMERIC(12,2) NOT NULL,
    qty_max_sqm         NUMERIC(12,2),          -- NULL = open-ended top band
    amount_thb          NUMERIC(14,4) NOT NULL,
    -- Half-open [min, max) -- see the BAND CONVENTION note above. min == max would be an empty
    -- band that can never match anything, so it is rejected too, not just min > max.
    CONSTRAINT chk_pricing_freight_rate_thickness_range CHECK (thickness_min_mm >= 0 AND thickness_min_mm < thickness_max_mm),
    CONSTRAINT chk_pricing_freight_rate_qty_range CHECK (qty_min_sqm >= 0 AND (qty_max_sqm IS NULL OR qty_min_sqm < qty_max_sqm)),
    CONSTRAINT chk_pricing_freight_rate_amount_nonnegative CHECK (amount_thb >= 0)
);

CREATE INDEX idx_pricing_freight_rate_config ON sales.pricing_freight_rate(formula_config_id);

CREATE TABLE sales.pricing_duty_rate (
    duty_rate_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    formula_config_id  BIGINT NOT NULL REFERENCES sales.pricing_formula_config(formula_config_id) ON DELETE CASCADE,
    product_type        VARCHAR(50)  NOT NULL,   -- code
    product_label        VARCHAR(100) NOT NULL,   -- Thai display name
    duty_pct             NUMERIC(8,6) NOT NULL,   -- stored as FRACTION (0.30 = 30%)
    CONSTRAINT chk_pricing_duty_rate_pct_range CHECK (duty_pct >= 0 AND duty_pct <= 1),
    CONSTRAINT uq_pricing_duty_rate_type UNIQUE (formula_config_id, product_type)
);

CREATE INDEX idx_pricing_duty_rate_config ON sales.pricing_duty_rate(formula_config_id);

CREATE TABLE sales.pricing_clearance_fee (
    clearance_fee_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    formula_config_id  BIGINT NOT NULL REFERENCES sales.pricing_formula_config(formula_config_id) ON DELETE CASCADE,
    qty_min_sqm         NUMERIC(12,2) NOT NULL,
    qty_max_sqm         NUMERIC(12,2),           -- NULL = open-ended
    amount_thb          NUMERIC(14,4) NOT NULL,
    -- Half-open [min, max) -- see the BAND CONVENTION note above; min == max is an empty band.
    CONSTRAINT chk_pricing_clearance_fee_qty_range CHECK (qty_min_sqm >= 0 AND (qty_max_sqm IS NULL OR qty_min_sqm < qty_max_sqm)),
    CONSTRAINT chk_pricing_clearance_fee_amount_nonnegative CHECK (amount_thb >= 0)
);

CREATE INDEX idx_pricing_clearance_fee_config ON sales.pricing_clearance_fee(formula_config_id);

-- Seed: version 1, current. All defaults per the CEO's sheet already match the column DEFAULTs
-- above, but every value is spelled out explicitly here so the seed row is self-documenting and
-- doesn't silently drift if a column default is ever changed.
INSERT INTO sales.pricing_formula_config (
    version, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer, selling_buffer,
    default_margin_pct, selling_price_round_up_to, is_current, effective_from
) VALUES (
    1, 1.150000, 0.004500, 1.070000, 1.070000, 1.070000,
    0.200000, 10.0000, TRUE, CURRENT_DATE
);

-- Seed: freight matrix. Quantity bands normalised from the sheet's overlapping "1-100 / 101-450 /
-- 450-800 / 801-1000" into contiguous HALF-OPEN bands with an open-ended top band (see BAND
-- CONVENTION above):
--   band1 = [1,101), band2 = [101,451), band3 = [451,801), band4 = [801,NULL)
-- Thickness bands are likewise half-open:
--   [3,8), [8,12), [12,17), [17,21)
--
-- Cells that are blank in the CEO's sheet are seeded as NO ROW here (not a zero-amount row). A
-- missing (origin_country, thickness, qty) combination must cause a loud failure in the pricing
-- engine (branches 3-5) -- e.g. "no freight rate configured for this shipment" -- and must NEVER
-- resolve to a silent 0 THB freight cost.
INSERT INTO sales.pricing_freight_rate (
    formula_config_id, origin_country, thickness_min_mm, thickness_max_mm, qty_min_sqm, qty_max_sqm, amount_thb
)
SELECT (SELECT formula_config_id FROM sales.pricing_formula_config WHERE is_current = TRUE),
       v.origin_country, v.thickness_min_mm, v.thickness_max_mm, v.qty_min_sqm, v.qty_max_sqm, v.amount_thb
FROM (VALUES
    -- Italy: thickness [3,8) mm
    ('Italy', 3, 8, 1, 101, 80000),
    ('Italy', 3, 8, 101, 451, 90000),
    ('Italy', 3, 8, 451, 801, 100000),
    ('Italy', 3, 8, 801, NULL, 100000),
    -- Italy: thickness [8,12) mm
    ('Italy', 8, 12, 1, 101, 50000),
    ('Italy', 8, 12, 101, 451, 80000),
    ('Italy', 8, 12, 451, 801, 90000),
    ('Italy', 8, 12, 801, NULL, 100000),
    -- Italy: thickness [12,17) mm (band4 blank in sheet -- no row)
    ('Italy', 12, 17, 1, 101, 50000),
    ('Italy', 12, 17, 101, 451, 90000),
    ('Italy', 12, 17, 451, 801, 100000),
    -- Italy: thickness [17,21) mm (band3/band4 blank in sheet -- no rows)
    ('Italy', 17, 21, 1, 101, 60000),
    ('Italy', 17, 21, 101, 451, 100000),

    -- Spain: identical values to Italy (the sheet groups them; separate rows let the CEO
    -- diverge them later without a schema change)
    ('Spain', 3, 8, 1, 101, 80000),
    ('Spain', 3, 8, 101, 451, 90000),
    ('Spain', 3, 8, 451, 801, 100000),
    ('Spain', 3, 8, 801, NULL, 100000),
    ('Spain', 8, 12, 1, 101, 50000),
    ('Spain', 8, 12, 101, 451, 80000),
    ('Spain', 8, 12, 451, 801, 90000),
    ('Spain', 8, 12, 801, NULL, 100000),
    ('Spain', 12, 17, 1, 101, 50000),
    ('Spain', 12, 17, 101, 451, 90000),
    ('Spain', 12, 17, 451, 801, 100000),
    ('Spain', 17, 21, 1, 101, 60000),
    ('Spain', 17, 21, 101, 451, 100000),

    -- China
    ('China', 3, 8, 1, 101, 60000),
    ('China', 3, 8, 101, 451, 60000),
    ('China', 3, 8, 451, 801, 50000),
    ('China', 3, 8, 801, NULL, 50000),
    ('China', 8, 12, 1, 101, 30000),
    ('China', 8, 12, 101, 451, 50000),
    ('China', 8, 12, 451, 801, 70000),
    ('China', 8, 12, 801, NULL, 50000),
    -- China: thickness [12,17) mm (band4 blank in sheet -- no row)
    ('China', 12, 17, 1, 101, 30000),
    ('China', 12, 17, 101, 451, 50000),
    ('China', 12, 17, 451, 801, 70000),
    -- China: thickness [17,21) mm (band3/band4 blank in sheet -- no rows)
    ('China', 17, 21, 1, 101, 40000),
    ('China', 17, 21, 101, 451, 50000)
) AS v(origin_country, thickness_min_mm, thickness_max_mm, qty_min_sqm, qty_max_sqm, amount_thb);

-- Seed: duty rates. The sheet also lists ก็อกน้ำ 20% and ยาแนว 10%, but the owner confirmed those
-- are OUT OF SCOPE for this flow -- deliberately NOT seeded. The table supports adding them via
-- config later (CEO edits, no migration needed).
INSERT INTO sales.pricing_duty_rate (formula_config_id, product_type, product_label, duty_pct)
SELECT (SELECT formula_config_id FROM sales.pricing_formula_config WHERE is_current = TRUE),
       v.product_type, v.product_label, v.duty_pct
FROM (VALUES
    ('TILE', 'กระเบื้อง', 0.300000),
    ('GLASS_MOSAIC', 'โมเสคแก้ว', 0.100000)
) AS v(product_type, product_label, duty_pct);

-- Seed: clearance fees, same normalised (half-open, contiguous) quantity bands as the freight
-- matrix -- [1,101), [101,451), [451,801), [801,NULL).
INSERT INTO sales.pricing_clearance_fee (formula_config_id, qty_min_sqm, qty_max_sqm, amount_thb)
SELECT (SELECT formula_config_id FROM sales.pricing_formula_config WHERE is_current = TRUE),
       v.qty_min_sqm, v.qty_max_sqm, v.amount_thb
FROM (VALUES
    (1, 101, 8000),
    (101, 451, 12000),
    (451, 801, 15000),
    (801, NULL, 20000)
) AS v(qty_min_sqm, qty_max_sqm, amount_thb);
