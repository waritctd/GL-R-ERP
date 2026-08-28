-- CEO per-item thickness override, so a deal line whose catalogue row carries no thickness can
-- still be COSTED rather than only manually priced.
--
-- The problem this closes. V156 made a missing thickness survivable: LandedCostCalculator sets the
-- line aside as UNCOSTABLE, writes it with NULL freight/duty and a stated reason, and approve()
-- refuses to let it through un-resolved. That was a real improvement over the previous 422 abort,
-- but it leaves the CEO exactly one way out -- manual_landed_cost_per_unit_thb (V141), i.e. typing
-- a landed cost in by hand. Every other input the formula needs (supplier price, FX, quantity,
-- duty, clearance) is present and correct; only the freight band's lookup key is missing. Forcing
-- a hand-keyed total for want of one number throws away the whole calculation.
--
-- 41.7% of the production catalogue has a NULL thickness_mm, and for some ranges NO source carries
-- it -- four of the nine factory workbooks have no thickness column at all -- so this is the
-- normal case for a large part of the catalogue, not a rare defect.
--
-- Why this is not "guessing a thickness". V153 deliberately refuses a global default, because an
-- INVENTED thickness silently selects a freight band that can differ by ~50,000 THB per shipment,
-- and refusing to price is the correct failure. That guarantee is unchanged here. The three ways a
-- thickness can be supplied are all explicit human input, never inference:
--
--   1. the catalogue row's own thickness_mm                        (the factory's own data)
--   2. price_catalog.collection_thickness_default                  (CEO, catalogue-wide, V153)
--   3. this column                                                 (CEO, this one deal line)
--
-- (1) and (2) are catalogue-grain: they answer "how thick is this PRODUCT", and (2) is the right
-- home whenever the answer generalises. This column is deal-grain -- the one-off, this-shipment-
-- only case where the CEO knows the thickness for THIS line (a sample run, a special-order slab, a
-- factory confirmation email) and writing it into the shared catalogue would be wrong. Precedence
-- is override -> catalogue chain, so a line-level answer never leaks into anyone else's pricing.
--
-- Modelled on V152's product_type_override, deliberately and in every respect: same table, same
-- nullable-means-no-override semantics, same CEO-only write path, same reason for living on
-- pricing_request_item rather than pricing_decision_item -- LandedCostCalculator reads
-- PricingRequestItemDto and must see the override on the VERY FIRST costing computed at
-- PricingDecisionService#startReview, before any pricing_decision_item row exists.
--
-- Unlike product_type_override there IS a CHECK constraint, because the valid range is a fixed
-- property of physical tile rather than CEO-configurable data: a non-positive thickness is
-- meaningless, and a zero would be actively dangerous -- it would select the LOWEST freight band
-- instead of refusing to price, the exact silent-mispricing failure V153's own comments exist to
-- prevent. The upper bound is deliberately absent: a slab thicker than the seeded [3,21) bands is
-- a real product, and it should surface as an explainable "no freight band matches" error from
-- PricingFormulaEngine#selectFreightRate rather than be rejected here as if it were a typo. This
-- mirrors the reasoning already written on ThicknessDefaultRequests.ThicknessDefaultEntry.

ALTER TABLE sales.pricing_request_item
    ADD COLUMN thickness_mm_override NUMERIC(8,2),
    ADD CONSTRAINT chk_pricing_request_item_thickness_override_positive
        CHECK (thickness_mm_override IS NULL OR thickness_mm_override > 0);

COMMENT ON COLUMN sales.pricing_request_item.thickness_mm_override IS
    'CEO override of the thickness used for THIS line''s freight-band lookup, when the catalogue '
    'row resolves none (V153''s chain: the row''s own thickness_mm, then '
    'price_catalog.collection_thickness_default). NULL = no override, use the catalogue chain; a '
    'line that still resolves nothing is UNCOSTABLE (V156), never priced on a guess. Deal-grain by '
    'design: for an answer that generalises to the product, use collection_thickness_default '
    'instead so the whole catalogue benefits. Set via PricingDecisionService#overrideItemThickness '
    '(CEO-only, DRAFT decisions only), which recomputes the bound costing in place.';
