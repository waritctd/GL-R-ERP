-- V164: ฝ่ายนำเข้า must supply ความหนา when the catalog resolves none for a pricing-request line.
--
-- LandedCostCalculator.resolveThicknessMm reads ONLY the catalog link today
-- (catalog_price_id ?? product_id -> price_catalog.v_priceable_product.thickness_mm, which already
-- COALESCEs price_catalog.collection_thickness_default, V153). A line with NO catalog link at all
-- (a free-text line Import is pricing outside the catalogue, owner ruling 2026-08-11) has no
-- catalog row to attach a thickness to, so it can never resolve one no matter how many
-- collection-level defaults the CEO adds -- it needs a value of its own. This column is that value.
--
-- Deliberately per-LINE, not per-collection: unlike collection_thickness_default (shared across
-- every product in a (factory, collection)), an unlinked line has no factory/collection identity
-- to key a shared default on -- it is exactly the product a shared default cannot reach.
ALTER TABLE sales.pricing_request_item
    ADD COLUMN IF NOT EXISTS thickness_mm_override NUMERIC(8,2);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pricing_request_item_thickness_override'
    ) THEN
        ALTER TABLE sales.pricing_request_item
            ADD CONSTRAINT chk_pricing_request_item_thickness_override
            CHECK (thickness_mm_override IS NULL OR thickness_mm_override > 0);
    END IF;
END $$;

COMMENT ON COLUMN sales.pricing_request_item.thickness_mm_override IS
    'Hand-entered thickness (mm) for a line with no resolvable catalog thickness -- either no '
    'catalog link at all, or a linked row whose catalog thickness (own value or collection '
    'default) is still NULL. NULL means unset. LandedCostCalculator.resolveThicknessMm reads this '
    'FIRST, ahead of the catalog chain (V153) -- see PricingRequestItemThicknessService#setItemThickness for '
    'who may write it and why a linked line is routed to price_catalog.collection_thickness_default '
    'instead of here.';
