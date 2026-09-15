-- Item 4 (direct-deal quotation, owner ruling 2026-09-16) — "ไม่รับมัดจำ" + a fixed choice of three
-- 100%-payment terms, for a document that asks for no deposit at all.
--
-- Deposit 0% stops being expressible as an ordinary typed percentage in this same release (the
-- editor's preset chips stay 30/50/custom, and the custom input now REJECTS 0 with a message
-- pointing at the new "ไม่รับมัดจำ" checkbox) -- but sales.quotation.deposit_percent = 0 remains
-- exactly how "no deposit" is stored (backend @Min(0) is UNCHANGED), so a zero-deposit document
-- still needs to say WHY: one of BEFORE_DELIVERY / ON_DELIVERY / ON_OR_BEFORE_DELIVERY.
--
-- A NEW column, not a reuse of remainder_mode (V165): remainder_mode names what happens to "the
-- rest" AFTER a deposit (CREDIT days, or before/upon delivery) -- it is meaningless once there is
-- no deposit to take a remainder of. Overloading it would make "deposit_percent = 0, remainder_mode
-- = ON_DELIVERY" ambiguous between "the legacy pre-V181 zero-deposit fallback text" and "the rep
-- picked ON_DELIVERY as the full-payment term" with no way to tell the two apart on read. A fresh,
-- honestly-named column sidesteps that: DealQuotationService forces remainder_mode/credit_days back
-- to NULL whenever deposit_percent resolves to 0 (see #resolveFullPaymentTerm's own Javadoc), and
-- DealQuotationRenderAdapter#depositLine falls back to the ORIGINAL remainder_mode/credit_days-based
-- text ONLY when full_payment_term is NULL -- which is true for every row this migration runs
-- against (nothing before this feature ever wrote it), so every existing zero-deposit document
-- (approved or not) keeps printing byte-for-byte what it always did until a rep resaves it with a
-- term chosen.
--
-- NULLable, with a CHECK rather than NOT NULL DEFAULT: a zero-deposit DRAFT may be saved before the
-- rep has picked a term (DealQuotationService#create/#update allow it); only #submit refuses to
-- advance such a document further. The CHECK still guards against an invalid CODE ever landing in
-- the column, which is the actual risk a nullable column needs guarding against.
--
-- Rollback: revert the application code (DealQuotationDtos/Requests/Service/Repository/
-- RenderAdapter, WastageCalculator's three constants, and the frontend terms card/quotationMeta),
-- then
--     ALTER TABLE sales.quotation DROP CONSTRAINT chk_quotation_full_payment_term;
--     ALTER TABLE sales.quotation DROP COLUMN full_payment_term;
-- No backfill concern either direction: every row this migration ever touches stores NULL until a
-- rep explicitly picks a term through the NEW checkbox+dropdown, so dropping it loses only what the
-- rollback is already reverting the means to produce.

ALTER TABLE sales.quotation
    ADD COLUMN full_payment_term VARCHAR(30);

ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_full_payment_term CHECK (
        full_payment_term IS NULL
        OR full_payment_term IN ('BEFORE_DELIVERY', 'ON_DELIVERY', 'ON_OR_BEFORE_DELIVERY')
    );

COMMENT ON COLUMN sales.quotation.full_payment_term IS
    'Direct-deal quotation (origin = DEAL_DIRECT) only: one of BEFORE_DELIVERY / ON_DELIVERY / ON_OR_BEFORE_DELIVERY, meaningful ONLY when deposit_percent resolves to exactly 0 ("ไม่รับมัดจำ" ticked) -- DealQuotationService forces it back to NULL on any other deposit percentage. NULL on a zero-deposit DRAFT means the rep has not chosen a term yet (submit() then refuses to advance it) and on every LEGACY zero-deposit row that predates this column (2026-09-16) -- DealQuotationRenderAdapter keeps printing such a row''s existing remainder_mode/credit_days-based text byte-for-byte when this is NULL, so an already-approved document never changes.';
