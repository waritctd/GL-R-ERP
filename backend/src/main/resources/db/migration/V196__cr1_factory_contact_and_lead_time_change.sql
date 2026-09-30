-- CR-1 (GLA-167): factory-contact flow + lead-time change requests.
--
-- Forward-only. V195 is claimed by another branch (stock-lines slice 2), so this starts at V196.
-- Prod note: a lower version merged AFTER a higher one is silently skipped (validate-on-migrate is
-- false there and out-of-order is unset), so do not deploy an image containing this file unless
-- every lower pending migration is in the same image or already applied.
--
-- 1) "ติดต่อโรงงานแล้ว" replaces "ส่งแล้ว": the factory_quote records WHEN a human contacted the
--    factory, WHO, and an optional note. The old send() set status REQUESTED with email_sent_at /
--    requested_at / sent_by; those rows ARE contacted quotes (owner ruling R4), so they are
--    backfilled below from their own sent date. DRAFT rows stay NULL: not contacted yet.
ALTER TABLE sales.factory_quote
    ADD COLUMN contacted_on   DATE,
    ADD COLUMN contacted_note TEXT,
    ADD COLUMN contacted_by   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN contacted_at   TIMESTAMPTZ;

UPDATE sales.factory_quote
   SET contacted_on = (COALESCE(email_sent_at, requested_at, received_at, created_at)
                       AT TIME ZONE 'Asia/Bangkok')::date,
       contacted_at = COALESCE(email_sent_at, requested_at, received_at, created_at),
       contacted_by = sent_by
 WHERE status <> 'DRAFT'
   AND contacted_on IS NULL
   -- a DRAFT that was later CANCELLED/SUPERSEDED never had a sent/requested/received time, and was
   -- never contacted: leave it NULL rather than invent a date from created_at.
   AND COALESCE(email_sent_at, requested_at, received_at) IS NOT NULL;

COMMENT ON COLUMN sales.factory_quote.contacted_on IS 'Date (Asia/Bangkok) import or the CEO contacted the factory. NULL = not contacted yet (DRAFT). Final: there is no undo.';
COMMENT ON COLUMN sales.factory_quote.contacted_at IS 'When the contacted step was recorded in the system.';

-- 2) Currency and price unit are fixed by Sales on the request line and locked for import.
--    Nullable: legacy lines carry neither, and receive() accepts anything for those.
ALTER TABLE sales.pricing_request_item
    ADD COLUMN requested_currency         VARCHAR(10),
    ADD COLUMN requested_price_unit_basis VARCHAR(30),
    ADD CONSTRAINT chk_pricing_request_item_requested_price_unit_basis CHECK (
        requested_price_unit_basis IS NULL
        OR requested_price_unit_basis IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M'));

COMMENT ON COLUMN sales.pricing_request_item.requested_currency IS 'Currency Sales asked the factory to quote in. NULL = legacy line (any currency accepted).';
COMMENT ON COLUMN sales.pricing_request_item.requested_price_unit_basis IS 'Price unit basis Sales asked for (same vocabulary as factory_quote_item.unit_basis). NULL = legacy line.';

-- 3) Lead-time change request: one per factory quote, import raises it, the owning rep or a sales
--    manager decides it as a whole.
CREATE TABLE sales.lead_time_change (
    lead_time_change_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_quote_id    BIGINT NOT NULL REFERENCES sales.factory_quote(factory_quote_id) ON DELETE CASCADE,
    pricing_request_id  BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason              TEXT NOT NULL,
    requested_by        BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by          BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    decided_at          TIMESTAMPTZ,
    decision_reason     TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- optimistic concurrency: bumped on every import edit; approve/reject must quote the version they saw
    version             INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT chk_lead_time_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);

CREATE UNIQUE INDEX uq_lead_time_change_one_pending_per_quote
    ON sales.lead_time_change (factory_quote_id) WHERE status = 'PENDING';
CREATE INDEX ix_lead_time_change_pricing_request ON sales.lead_time_change (pricing_request_id);

CREATE TABLE sales.lead_time_change_line (
    lead_time_change_id     BIGINT NOT NULL REFERENCES sales.lead_time_change(lead_time_change_id) ON DELETE CASCADE,
    pricing_request_item_id BIGINT NOT NULL REFERENCES sales.pricing_request_item(pricing_request_item_id) ON DELETE CASCADE,
    old_min_days            INTEGER,
    old_max_days            INTEGER,
    new_min_days            INTEGER NOT NULL,
    new_max_days            INTEGER NOT NULL,
    PRIMARY KEY (lead_time_change_id, pricing_request_item_id),
    CONSTRAINT chk_lead_time_change_line_range CHECK (new_min_days >= 1 AND new_min_days <= new_max_days)
);

COMMENT ON TABLE sales.lead_time_change IS 'CR-1: import-raised request to change the lead time of a factory quote''s lines; decided once, as a whole, by the owning rep or a sales manager. Never blocks the pricing chain.';
COMMENT ON TABLE sales.lead_time_change_line IS 'CR-1: one ticked line of a lead-time change, with the old value snapshotted at request time.';

-- 4) The IR's lead time comes from the deal's current QUOTATION items (owner ruling B-R4/B-R5). The
--    quotation-derived value the IR was built from is stored beside the live lead time so that a
--    revise can tell "the quotation changed" (take the new value) from "import edited it by hand"
--    (keep the manual value). NULL = built from the country default, or created before this column.
ALTER TABLE sales.import_request
    ADD COLUMN derived_lead_time_min_days SMALLINT,
    ADD COLUMN derived_lead_time_max_days SMALLINT;

COMMENT ON COLUMN sales.import_request.derived_lead_time_min_days IS 'Min lead time (days) derived from the deal''s current quotation items when this IR was built. NULL = country default or pre-V196. Lets revise tell a changed quotation from a manual lead-time edit.';
COMMENT ON COLUMN sales.import_request.derived_lead_time_max_days IS 'Max lead time (days) derived from the deal''s current quotation items when this IR was built. See derived_lead_time_min_days.';
