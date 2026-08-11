-- V140 — Import's pricing workflow collapses to THREE user-visible statuses.
--
-- Owner ruling 2026-08-11. Import's job is to keep Sales informed, and it needs exactly three
-- states to do that:
--
--     รับเรื่อง             = IMPORT_REVIEWING
--     เจรจาราคากับโรงงาน     = AWAITING_FACTORY_RESPONSE   (now also covers costing)
--     รอ CEO อนุมัติราคา     = READY_FOR_CEO_REVIEW
--
-- Two statuses are therefore retired:
--
--   COSTING_IN_PROGRESS  — merged INTO AWAITING_FACTORY_RESPONSE. Costing still happens (the
--     landed-cost calculation from sales.pricing_freight_rate / pricing_duty_rate is unchanged),
--     it simply no longer occupies a status of its own. Splitting "waiting for the factory" from
--     "costing what the factory said" described an internal step nobody outside Import could act
--     on, and it is the step the new one-click ส่งให้ CEO อนุมัติราคา action now runs end to end.
--
--   MORE_INFO_REQUIRED   — the ขอข้อมูลเพิ่มเติม round-trip is removed from the product. In
--     practice Import and Sales message each other directly rather than routing a question
--     through the request. Rows here resume at IMPORT_REVIEWING, which is where the old
--     respondInformation() sent them when no resume_status had been recorded.
--
-- NOTE ON THE SURVIVING NAME: AWAITING_FACTORY_RESPONSE is deliberately NOT renamed even though
-- it now covers costing too. Renaming would rewrite the status string in every historical
-- sales.pricing_request_event row (from_status/to_status), and the Thai display label is what
-- users actually read. Recorded as a follow-up, not done here.
--
-- FORWARD-ONLY. Data is migrated before the constraint is narrowed, so the constraint can never
-- reject a pre-existing row.

-- ── 1. Migrate live rows off the two retired statuses ─────────────────────────────────────
UPDATE sales.pricing_request
   SET status = 'AWAITING_FACTORY_RESPONSE',
       updated_at = now()
 WHERE status = 'COSTING_IN_PROGRESS';

-- resume_status carried "where to go back to" for a request parked in MORE_INFO_REQUIRED. It is
-- honoured here where it is still a legal target, so a request that was mid-negotiation when the
-- question was asked resumes there rather than being knocked back to the start of Import's queue.
UPDATE sales.pricing_request
   SET status = CASE
                  WHEN resume_status IN ('AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS')
                    THEN 'AWAITING_FACTORY_RESPONSE'
                  ELSE 'IMPORT_REVIEWING'
                END,
       resume_status = NULL,
       -- Mirrors the old resumeFromMoreInformation(): a request resuming at IMPORT_REVIEWING is
       -- by definition picked up, so picked_up_at must not be left null.
       --
       -- The submitted_at guard is what makes this UPDATE unable to fail on any pre-existing row.
       -- V59 carries CONSTRAINT chk_pricing_request_pickup_order CHECK (picked_up_at IS NULL OR
       -- submitted_at IS NOT NULL), so stamping picked_up_at on a row that was never submitted
       -- would violate it. Every row that reached MORE_INFO_REQUIRED through the application went
       -- DRAFT -> SUBMITTED -> ... , and the SUBMITTED transition sets submitted_at, so such a row
       -- should not exist — but "should not exist" is not a guarantee for a row that predates the
       -- constraint or arrived via a seed/import script, and a migration that aborts mid-deploy on
       -- one bad row is a far worse outcome than leaving that row's picked_up_at null.
       picked_up_at = CASE WHEN submitted_at IS NOT NULL
                             THEN COALESCE(picked_up_at, now())
                           ELSE picked_up_at END,
       updated_at = now()
 WHERE status = 'MORE_INFO_REQUIRED';

-- Any resume_status still pointing at a retired value is now meaningless — the request is no
-- longer parked, and nothing reads the column outside the removed round-trip.
UPDATE sales.pricing_request
   SET resume_status = NULL
 WHERE resume_status IN ('COSTING_IN_PROGRESS', 'MORE_INFO_REQUIRED');

-- ── 2. Narrow the status constraint (was V59 -> V61 -> V72 -> V74 -> V75) ──────────────────
ALTER TABLE sales.pricing_request
    DROP CONSTRAINT chk_pricing_request_status;
ALTER TABLE sales.pricing_request
    ADD CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE',
        'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION', 'COSTING_REVISION_REQUIRED',
        'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED', 'CANCELLED', 'SUPERSEDED'
    ));

-- ── 3. History is NOT rewritten ────────────────────────────────────────────────────────────
-- sales.pricing_request_event rows keep their original from_status/to_status values, including
-- 'COSTING_IN_PROGRESS' and 'MORE_INFO_REQUIRED', and the MORE_INFO_REQUESTED /
-- MORE_INFO_RESPONDED event kinds stay valid in PricingRequestEventKind.VALUES. An audit trail
-- that says a request once sat in a status the product no longer has is correct; one that has
-- been retconned to a status it never occupied is not. There is no CHECK constraint on those
-- columns, so nothing here needs to change for them to keep reading back.
