-- BUSINESS-LOGIC CHANGE -- owner ruling 2026-10-01 (Ploy: "make the กำไร default to 30%"):
-- the default margin on the CEO's selling-price formula moves from 20% to 30%.
--
-- Two parts, both forward-only:
--
-- 1. The column DEFAULT on sales.pricing_formula_config.default_margin_pct becomes 0.300000, so any
--    future row inserted without an explicit margin gets 30% (V109 set 0.200000).
--
-- 2. The CURRENT config is republished at 30% -- but ONLY if it still carries the untouched V109
--    seed default of exactly 0.200000. A CEO who has already edited the margin on the CEO settings
--    page (e.g. to 0.24) keeps their value: this migration must never overwrite a deliberate
--    choice, so the DO block below is a no-op whenever the current row is not exactly 0.200000.
--
-- It republishes the way PricingFormulaConfigRepository.createNewVersion does, not by editing the
-- row in place: the existing current row is flipped to is_current = FALSE (its values untouched,
-- retained as history for any costing that froze its formula_config_id), and a new current version
-- (max(version) + 1) is inserted with every other scalar copied verbatim and the margin at 0.30,
-- together with a full copy of the freight / duty / clearance child rows (children are never
-- versioned independently -- see V109).
--
-- Deploy note: this is V197. It must ship in the same image as, or after, V195-V197; never deploy
-- an image containing V197 while a lower pending migration is neither in it nor already applied.

ALTER TABLE sales.pricing_formula_config
    ALTER COLUMN default_margin_pct SET DEFAULT 0.300000;

DO $$
DECLARE
    old_id BIGINT;
    new_id BIGINT;
BEGIN
    SELECT formula_config_id INTO old_id
      FROM sales.pricing_formula_config
     WHERE is_current = TRUE
       AND default_margin_pct = 0.200000;

    IF old_id IS NULL THEN
        RETURN;  -- CEO already customised the margin (or it is already 30%): leave it alone.
    END IF;

    UPDATE sales.pricing_formula_config SET is_current = FALSE WHERE formula_config_id = old_id;

    INSERT INTO sales.pricing_formula_config (
        version, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer, selling_buffer,
        default_margin_pct, selling_price_round_up_to, is_current, effective_from, updated_by, updated_at
    )
    SELECT (SELECT COALESCE(MAX(version), 0) + 1 FROM sales.pricing_formula_config),
           insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer, selling_buffer,
           0.300000, selling_price_round_up_to, TRUE, CURRENT_DATE, NULL, now()
      FROM sales.pricing_formula_config
     WHERE formula_config_id = old_id
    RETURNING formula_config_id INTO new_id;

    INSERT INTO sales.pricing_freight_rate (
        formula_config_id, origin_country_code, thickness_min_mm, thickness_max_mm,
        qty_min_sqm, qty_max_sqm, amount_thb
    )
    SELECT new_id, origin_country_code, thickness_min_mm, thickness_max_mm,
           qty_min_sqm, qty_max_sqm, amount_thb
      FROM sales.pricing_freight_rate
     WHERE formula_config_id = old_id;

    INSERT INTO sales.pricing_duty_rate (formula_config_id, product_type, product_label, duty_pct)
    SELECT new_id, product_type, product_label, duty_pct
      FROM sales.pricing_duty_rate
     WHERE formula_config_id = old_id;

    INSERT INTO sales.pricing_clearance_fee (formula_config_id, qty_min_sqm, qty_max_sqm, amount_thb)
    SELECT new_id, qty_min_sqm, qty_max_sqm, amount_thb
      FROM sales.pricing_clearance_fee
     WHERE formula_config_id = old_id;
END $$;
