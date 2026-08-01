-- Fix for "สร้างคำขอราคา" not autofilling: the catalog product picked while creating a deal
-- (TicketCreateModal's brand/model catalog picker) was never persisted anywhere. Per
-- TicketItemRequest/TicketItemDto's own (now-removed) comment, the catalog id/price were "UI-only
-- -- never sent in the onSubmit payload", so sales.ticket_item had nothing for
-- PricingRequestCreateModal.emptyItemFromTicketItem to seed productId/catalogProductCode from,
-- forcing a re-search of the catalog at pricing-request time even though the product was already
-- identified at deal-creation time.
--
-- catalog_price_id points at the SAME target as sales.pricing_request_item.product_id
-- (price_catalog.product_prices.price_id, repointed there by V68 -- see that migration's own
-- header for why product_id is NOT sales.catalog(catalog_id)). The frontend's api.catalog.prices
-- (GET /catalog/prices) returns ProductPriceDto, whose priceId IS that price_id.
--
-- Both columns are nullable so every existing ticket_item row stays valid (a deal line entered
-- as "custom", not picked from the catalog, legitimately has no catalog identity). ON DELETE
-- SET NULL: if a catalog price row is later retired, the deal line itself must not be blocked
-- or cascaded away -- it just loses its catalog cross-reference and reverts to free text.
ALTER TABLE sales.ticket_item
    ADD COLUMN catalog_price_id BIGINT,
    ADD COLUMN catalog_product_code VARCHAR(80);

ALTER TABLE sales.ticket_item
    ADD CONSTRAINT fk_ticket_item_catalog_price
        FOREIGN KEY (catalog_price_id) REFERENCES price_catalog.product_prices(price_id) ON DELETE SET NULL;

CREATE INDEX idx_ticket_item_catalog_price_id ON sales.ticket_item(catalog_price_id);
