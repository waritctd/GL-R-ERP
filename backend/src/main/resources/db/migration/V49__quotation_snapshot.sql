-- Freeze issued quotations: item-data snapshot at issue time, mirroring the existing
-- sales.deposit_notice_item pattern (V12 "Snapshot of items at issue time"). Today
-- TicketService.getQuotationXlsx/Pdf re-render from LIVE ticket data on every download,
-- so a quotation re-downloaded after a later revision shows its original number with
-- today's edited items/prices — a legal-compliance problem for an issued commercial
-- document. This table plus the customer/project columns below let the service freeze
-- what was true at issue time; pre-V49 quotations have no snapshot rows and keep
-- rendering from live data (legacy fallback).
--
-- Column types mirror sales.ticket_item as it exists today (brand/model/color/texture/
-- size, qty, qty_sqm, unit_basis, raw_unit) — note ticket_item's shape has moved since
-- V6 (brand/model/texture were renamed from product_name/product_code/size in V8; a
-- separate `size` column was added in V9), so these match the current columns, not V6's.
CREATE TABLE sales.quotation_item (
    quotation_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quotation_id       BIGINT NOT NULL REFERENCES sales.quotation(quotation_id),
    seq                INT NOT NULL,
    brand              VARCHAR(255),
    model              VARCHAR(80),
    color              VARCHAR(80),
    texture            VARCHAR(80),
    size               VARCHAR(80),
    qty                NUMERIC(12,2),
    qty_sqm            NUMERIC(12,4),
    unit_basis         VARCHAR(10),
    raw_unit           VARCHAR(30),
    unit_price         NUMERIC(14,2) NOT NULL,
    amount             NUMERIC(14,2) NOT NULL
);

CREATE INDEX idx_quotation_item_quotation ON sales.quotation_item(quotation_id);

-- Customer + project header, frozen at issue time. Nullable — legacy (pre-V49) rows
-- stay NULL and the service falls back to a live customer/ticket lookup for them.
ALTER TABLE sales.quotation
    ADD COLUMN customer_name    VARCHAR(255),
    ADD COLUMN customer_address TEXT,
    ADD COLUMN customer_tax_id  VARCHAR(20),
    ADD COLUMN customer_phone   VARCHAR(50),
    ADD COLUMN project_name     VARCHAR(255);

-- Guard against the double-click-generate race: TicketRepository.createQuotation reads
-- MAX(quotation_version) and inserts nextVersion with no lock in between, so two
-- concurrent generateQuotation calls for the same ticket can both compute the same next
-- version and insert two rows with the same (ticket_id, quotation_version). deposit_notice
-- got its twin guard in V45 (ux_deposit_notice_ticket_version); sales.quotation never did.
--
-- Defensive dedupe first, since this runs against real demo/uat/prod data and a prior
-- race could already have produced duplicates: renumber every row but the earliest in
-- each duplicate (ticket_id, quotation_version) group to a version past that ticket's
-- current max, WITHOUT deleting any row. A fresh/seed database (no demo seed inserts
-- into sales.quotation — checked db/migration-demo) has nothing to dedupe, so this is a
-- no-op there; on hosted environments where a race already happened, it repairs the data
-- instead of failing the migration outright.
WITH ranked AS (
    SELECT quotation_id, ticket_id,
           ROW_NUMBER() OVER (PARTITION BY ticket_id, quotation_version ORDER BY quotation_id) AS rn,
           MAX(quotation_version) OVER (PARTITION BY ticket_id) AS ticket_max_version
      FROM sales.quotation
), dupes AS (
    SELECT quotation_id, ticket_id,
           ticket_max_version + ROW_NUMBER() OVER (PARTITION BY ticket_id ORDER BY quotation_id) AS new_version
      FROM ranked
     WHERE rn > 1
)
UPDATE sales.quotation q
   SET quotation_version = d.new_version
  FROM dupes d
 WHERE q.quotation_id = d.quotation_id;

CREATE UNIQUE INDEX ux_quotation_ticket_version ON sales.quotation(ticket_id, quotation_version);
