-- Stock-sourced deal line items: lets sales flag a line "from warehouse" and record its own
-- selling price. CAPTURE-ONLY today -- see the "out of scope" note below for what this does
-- NOT yet do.
--
-- MIGRATION NUMBERING: this is V183. Verified free by listing this worktree's own
-- backend/src/main/resources/db/migration (tops out at V182__deal_quotation_item_round_to_full_box.sql)
-- immediately before writing this file. As with every prior migration on this repo, the true
-- production-numbering conflict is tracked separately and must be re-checked again before merge
-- if time has passed or other worktrees have advanced.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Context / owner ruling this migration acts on
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Asked how sales should price items sold out of warehouse stock, the CEO described a manual
-- weekly promotion/clearance process run entirely outside the ERP today. The product owner's
-- concrete simplification for a first ERP implementation: let sales mark a line item as "from
-- warehouse" at deal-entry time and type in that line's own selling price. This is a per-line
-- choice -- a deal can mix both kinds, the same "mixed stock+import" pattern already supported
-- for fulfilment (V148's own qty_from_stock, a DIFFERENT and pre-existing field -- see the column
-- comment below for why this migration does not reuse it).
--
-- CAPTURE-ONLY today -- nothing downstream reads this pair yet. Wiring it into the
-- PricingRequest -> factory-quote -> CEO-cost-approval -> CustomerQuotation chain (skipping some
-- or all of those steps for a flagged line, or feeding stock_sale_price into
-- CustomerQuotationService -- the customer-facing quotation PDF, whose arithmetic is reconciled
-- against 9 real reference documents) is explicitly OUT of scope here -- a separate, higher-risk
-- follow-up. Until that lands, a line flagged sourced_from_stock can still be attached to, and
-- priced through, an ordinary PricingRequest exactly as before -- this migration and its Java/JS
-- call sites only let sales flag+price a line at entry/edit time and persist it.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- sales.ticket_item.sourced_from_stock / stock_sale_price
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- sourced_from_stock is a NEW, SEPARATE flag -- NOT a reuse of the existing qty_from_stock column
-- (V54). qty_from_stock is a commission-input declaration ("how much of this line's ordered
-- quantity came from stock"), gated to DealStage.ORDER_RECEIVED+ and set by
-- {sales-owner, import, ceo} for CommissionRepository's weighting math; it is explicitly NOT an
-- inventory/pricing signal (see its own column/Javadoc). sourced_from_stock is set by sales at
-- deal ENTRY time (any stage), is a pricing-path signal, and is boolean -- a wholly different
-- decision made by a different actor at a different point in the workflow.
--
-- NOT NULL DEFAULT false: every ticket_item row that exists before this migration runs (and
-- every future row nothing sets it on) keeps today's meaning -- priced through the normal
-- PricingRequest chain.
ALTER TABLE sales.ticket_item
    ADD COLUMN sourced_from_stock boolean NOT NULL DEFAULT false;

-- stock_sale_price: sales-typed selling price for a stock-sourced line, in the item's display
-- currency (sales.ticket_item.currency), same NUMERIC(14,2) shape as the other item-level money
-- columns on this table (proposed_price/approved_price/manual_price). Nullable -- only
-- meaningful (and only allowed, see the CHECK below) when sourced_from_stock is true.
ALTER TABLE sales.ticket_item
    ADD COLUMN stock_sale_price numeric(14,2);

-- The pairing is enforced at the DB layer, not just in TicketService: a stock-sourced line must
-- carry a real positive price, and a non-stock line must carry none (never a stray value left
-- over from an unchecked toggle). TicketService.create/mergeEditedItemsPreservingPricing enforce
-- the same rule before this CHECK would ever fire -- see those methods' own comments -- so this
-- is a backstop against a direct/malformed write, the same belt-and-suspenders posture V148's
-- weight_multiplier CHECK already sets on this same table.
ALTER TABLE sales.ticket_item
    ADD CONSTRAINT chk_ticket_item_stock_sale_price
        CHECK ((sourced_from_stock = false AND stock_sale_price IS NULL)
            OR (sourced_from_stock = true AND stock_sale_price > 0));
