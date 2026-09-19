-- V184: move the ใบขอซื้อ from per-BRAND to per-FACTORY grain, and add per-factory progress
-- (S12-S17). ALTERs V154's tables; V154 itself is on main (e2e6f202, deployed with V151-V155) and
-- is never edited in place.
--
-- Replaces a separate, never-merged/never-applied V184 by Yang.Pongburit
-- (origin/feat/per-factory-import-tracking, commit 83f4fa78) that modelled the same S12-S17 steps
-- on a brand-new sales.factory_import_progress table. This migration instead carries the steps as
-- columns on the EXISTING sales.import_request aggregate (V154), so "issued" and "in progress" stay
-- one row, one lifecycle, one document -- see the plan this branch implements
-- (import-request-per-factory-PLAN.md) for the full reasoning.
--
-- "Never applied anywhere" (repeated throughout this file) is NOT the same claim as "safe to
-- re-run" -- this migration has itself been rewritten more than once while it sat unapplied on
-- this branch, and THAT is fine (Flyway only cares about the checksum of what finally merges). It
-- stops being fine the moment any dev/CI database has actually run migrate against an EARLIER
-- draft of this same V184__import_request_per_factory_progress.sql: Flyway pins that draft's
-- checksum into hr.flyway_schema_history under version 184, and a later migrate against a
-- DIFFERENT-content V184 (same version, new checksum) does not silently re-apply the new content --
-- it fails outright on the checksum mismatch. That database needs `flyway repair` (or a manual
-- flyway_schema_history fix-up) before it can move again; simply re-running migrate does not.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. price_catalog.factories -- confirmed the authoritative factory master (V163's own header):
--    "price_catalog.factories is the REAL 9 factories... the master-data table the catalog
--    import/pricing engine keys everything off of". sales.factory_config (the OTHER historical
--    factory table, V25) was folded into this one and DROPPED by V163 -- there is now exactly one
--    factory table, and this migration extends it rather than reviving a second.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- Provenance for a factory row created on the fly from an unresolved ใบขอซื้อ line (owner decision
-- 2), so a bad auto-created row is findable and distinguishable from real catalog-import master
-- data. All three nullable: NULL means "seeded the normal way", not "auto-created before this
-- column existed" -- there is no existing auto-created data to backfill.
ALTER TABLE price_catalog.factories
    ADD COLUMN created_source VARCHAR(30),
    ADD COLUMN created_by_id  BIGINT,
    ADD COLUMN created_at     TIMESTAMPTZ,
    ADD CONSTRAINT chk_factories_created_source
        CHECK (created_source IS NULL OR created_source IN ('IMPORT_REQUEST'));

COMMENT ON COLUMN price_catalog.factories.created_source IS
    'How this row came to exist. NULL = normal catalog-import/seed master data. ''IMPORT_REQUEST'' '
    '= auto-created because a ใบขอซื้อ line named a factory with no existing match -- see '
    'ImportRequestService''s factory-resolution cascade. Lets a bad auto-created row be found and '
    'reviewed rather than silently blending into real master data.';

-- OWNER DECISION 09-18 (supersedes an earlier draft of this migration that used a silent 'XX'
-- "Unknown" sentinel): "Sales should be forced to select country ... if there is unknown make it
-- อื่นๆ and let the input." A real ISO country is never known at auto-create time (the line
-- carries only a factory NAME), but rather than fabricate one (a silent 'XX' row nobody actually
-- chose) or fabricate a real country (e.g. defaulting to Thailand, asserting something nobody told
-- us), the CALLER is now required to supply one -- see ImportRequestService's factory-resolution
-- cascade, which refuses to create a factory with no country and inserts nothing until it has one.
--
-- 'ZZ' (ISO 3166-1's own user-assigned range, so it can never collide with a real future ISO
-- code) is the one legitimate catch-all for a genuinely unknown/uncommon origin: unlike the old
-- 'XX' sentinel, choosing it is a DELIBERATE, VISIBLE choice (it sorts last in every country
-- picker -- see FactoryConfigRepository#listCountries) and it always carries a human-typed
-- free-text country name (factories.country_other below), so a reader of the row knows WHAT the
-- caller actually meant, not just that nobody knew.
INSERT INTO price_catalog.country (country_code, name_en, name_th)
VALUES ('ZZ', 'Other', 'อื่นๆ')
ON CONFLICT DO NOTHING;

-- Paired with country='ZZ': the free-text name typed when a factory's real country is not in the
-- picker. Two CHECKs, not one combined expression, so a failure names which half broke: ZZ without
-- text is "you chose อื่นๆ but did not say what", non-ZZ WITH text is stray data nothing should
-- have been able to write (both ImportRequestService- and FactoryConfigRepository-facing callers
-- validate this before it ever reaches SQL -- see ImportRequestService#requireValidNewFactoryCountry and
-- PriceImportService#requireValidCountryOther -- so these CHECKs are a backstop, not the only
-- guard, matching this table's own chk_factories_created_source one ALTER up).
--
-- NOT VALID unnecessary in principle (no 'ZZ' row can exist before this migration runs), but added
-- anyway for the same "disciplined default" reasoning chk_import_request_factory below documents:
-- harmless when the rule already holds, and one fewer thing to get right if it somehow does not.
ALTER TABLE price_catalog.factories
    ADD COLUMN country_other VARCHAR(100),
    ADD CONSTRAINT chk_factories_country_other_required_for_zz
        CHECK (country <> 'ZZ' OR NULLIF(btrim(country_other), '') IS NOT NULL) NOT VALID,
    ADD CONSTRAINT chk_factories_country_other_only_for_zz
        CHECK (country = 'ZZ' OR country_other IS NULL) NOT VALID;

COMMENT ON COLUMN price_catalog.factories.country_other IS
    'Free text typed when country=''ZZ'' (อื่นๆ / "other") -- the real country name, for a supplier '
    'whose country is not in the price_catalog.country picker. NULL for every other country: see '
    'chk_factories_country_other_required_for_zz / chk_factories_country_other_only_for_zz, both '
    'paired CHECKs on this column and price_catalog.factories.country.';

-- Read-only confirmation, LandedCostCalculator itself untouched per this branch's hard boundaries:
-- a 'ZZ'-country factory has no sales.pricing_freight_rate row either (same reasoning the old 'XX'
-- draft of this migration gave), so LandedCostCalculator's freight lookup for any item ever priced
-- off it misses cleanly and the item is marked uncostable (V156) -- not crashed, not silently
-- zero-costed. In practice this path is not even reachable today: LandedCostCalculator resolves
-- country/thickness ONLY via price_catalog.product_prices.factory_id (the catalog link), and an
-- auto-created factory by definition has no product_prices rows pointing at it yet (that is WHY
-- the line's factory could not be resolved to an existing master row).

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. sales.import_request -- brand grain becomes factory grain; brand stays as a display snapshot.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE sales.import_request
    ALTER COLUMN brand DROP NOT NULL,
    ADD COLUMN factory_id   BIGINT REFERENCES price_catalog.factories(factory_id),
    ADD COLUMN factory_name VARCHAR(255);

COMMENT ON COLUMN sales.import_request.brand IS
    'Display snapshot only as of V184 -- the distinct brand(s) of this factory''s lines on the deal, '
    'joined for the header/filename. No longer the grouping key (factory_id is) and no longer '
    'NOT NULL: a factory can carry lines spanning more than one brand, and this column cannot state '
    'them all as a single value the way it could when it WAS the key.';

COMMENT ON COLUMN sales.import_request.factory_id IS
    'The grouping key as of V184 (was brand). References price_catalog.factories -- see this '
    'migration''s header for why that table, not a revived sales.factory_config, is the master. '
    'Nullable only because chk_import_request_factory below is NOT VALID for legacy safety; the '
    'service never inserts a row without one.';

COMMENT ON COLUMN sales.import_request.factory_name IS
    'Display snapshot of factory_id''s name at the time this row was created, matching how every '
    'other snapshot column on this table (customer_name, project_name, ...) is frozen rather than '
    'joined live.';

-- NOT VALID: asserts the rule for every FUTURE row without scanning (or failing boot over) any
-- legacy per-brand row this migration leaves with factory_id NULL. sales.import_request is 0 rows
-- in every environment this branch has been checked against (see the pre-merge prod check below),
-- so there is no legacy data in practice -- NOT VALID is the disciplined default anyway.
--
-- (Cheap nit, REVIEW ROUND 2: this comment used to say "the same reasoning V154 already applies to
-- its own CHECK constraints" -- that is false. V154 CREATEs sales.import_request fresh, so every
-- CHECK it declares is a plain inline column constraint with no pre-existing rows to worry about
-- and never spells NOT VALID at all; grep V154__import_request_document.sql yourself. The actual
-- precedent for NOT VALID is this migration's OWN price_catalog.factories ALTERs just above
-- (chk_factories_country_other_required_for_zz/_only_for_zz) and chk_import_request_step_pairing/
-- chk_import_request_first_issued_pairing further down -- all four ALTER an EXISTING table, which is
-- the actual reason NOT VALID applies here and never did on V154's CREATE TABLE.
ALTER TABLE sales.import_request
    ADD CONSTRAINT chk_import_request_factory CHECK (factory_id IS NOT NULL) NOT VALID;

-- Drop the brand-keyed partial uniqueness (superseded by the factory-keyed pair below) and
-- re-create it on (ticket_id, factory_id) -- same two-index reasoning V154 documents at length: a
-- correction must be preparable as a DRAFT while the previous version is still ISSUED.
DROP INDEX sales.ux_import_request_ticket_brand_issued;
DROP INDEX sales.ux_import_request_ticket_brand_draft;

CREATE UNIQUE INDEX ux_import_request_ticket_factory_issued
    ON sales.import_request(ticket_id, factory_id)
    WHERE status = 'ISSUED';

CREATE UNIQUE INDEX ux_import_request_ticket_factory_draft
    ON sales.import_request(ticket_id, factory_id)
    WHERE status = 'DRAFT';

-- ── Progress columns (S12-S17), carried forward across a revision ──────────────────────────────
ALTER TABLE sales.import_request
    ADD COLUMN import_step        VARCHAR(20),
    ADD COLUMN import_step_at     DATE,
    ADD COLUMN import_step_by_id  BIGINT,
    ADD COLUMN import_step_by_name VARCHAR(200),
    ADD COLUMN import_step_note   VARCHAR(500),
    ADD CONSTRAINT chk_import_request_step CHECK (import_step IN (
        'CONTACTED', 'ORDERED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_CUSTOMS', 'RECEIVED'
    )),
    -- A DRAFT has no step yet (it has not been issued, so CONTACTED has not happened); anything
    -- past DRAFT has one, set at issue time. Same "impossible half-state" discipline V154 already
    -- applies with chk_import_request_issued_fields.
    --
    -- NOT VALID (2026-09-18 correction): this migration is not yet applied anywhere, but the table
    -- it alters is not new -- a legacy ISSUED/SUPERSEDED row from before this migration existed would
    -- have import_step NULL (the column did not exist yet), which the VALID form of this CHECK would
    -- reject at ALTER time and fail boot. NOT VALID asserts the rule for every FUTURE write only,
    -- matching chk_import_request_factory's own reasoning two ALTERs up.
    ADD CONSTRAINT chk_import_request_step_pairing CHECK (
        (status = 'DRAFT' AND import_step IS NULL) OR (status <> 'DRAFT' AND import_step IS NOT NULL)
    ) NOT VALID;

COMMENT ON COLUMN sales.import_request.import_step IS
    'GLA-100''s six steps (the owner''s S12-S17 sheet), the no-purchase-order replacement for '
    'sales.factory_purchase_order''s per-PO status. Deliberately a NEW, separate vocabulary from '
    'FulfilmentStatus.IMPORT_SEQUENCE (IR_ISSUED/IR_SENT/SHIPPING/GOODS_RECEIVED) -- a step name is '
    'never written as a ticket_event.kind or a notification type; see ImportRequestStep''s Javadoc.';

COMMENT ON COLUMN sales.import_request.import_step_at IS
    'Backdatable: import may record a step after the fact (e.g. logging PICKED_UP a day late), so '
    'this is caller-supplied, not defaulted to now().';

-- ── Lead time / expected arrival (owner decision 09-18 #2) ─────────────────────────────────────
-- "they also need to automatically fill estimations of how many days it'll take for the item to
-- arrive like the direct quote form" -- autofilled from the factory's country on DRAFT creation
-- (th.co.glr.hr.importrequest.LeadTimeDefaults, a fixed-in-code map mirroring
-- frontend/src/features/quotations/quotationMeta.js's ORIGIN_COUNTRY_OPTIONS -- see that class's
-- own Javadoc for why this is two independent copies of the same numbers with no drift guard),
-- editable by the owning rep/CEO while DRAFT and by import/CEO after ISSUE, and REQUIRED (both
-- non-null) before a form may be issued -- see ImportRequestService#issue.
ALTER TABLE sales.import_request
    ADD COLUMN lead_time_min_days SMALLINT,
    ADD COLUMN lead_time_max_days SMALLINT,
    -- Both null (not yet estimated) or both a real, ordered, sane-bounded range -- the same
    -- "impossible half-state" discipline chk_import_request_step_pairing just above applies. No
    -- NOT VALID needed -- and NOT because sales.import_request happens to be 0 rows everywhere this
    -- branch has been checked (that would stop being true the moment ANY row existed, legacy or
    -- not, and this CHECK still has to hold then). The real reason is narrower and does not depend
    -- on the table's row count at all: lead_time_min_days/lead_time_max_days are BRAND NEW columns,
    -- just added by this same ALTER, so every row that already exists -- however many there are --
    -- reads NULL for both of them, which is exactly the first branch of this CHECK. A VALID check
    -- can never fail against data it did not exist to constrain.
    ADD CONSTRAINT chk_import_request_lead_time CHECK (
        (lead_time_min_days IS NULL AND lead_time_max_days IS NULL)
        OR (lead_time_min_days IS NOT NULL AND lead_time_max_days IS NOT NULL
            AND lead_time_min_days >= 1 AND lead_time_min_days <= lead_time_max_days
            AND lead_time_max_days <= 365)
    );

COMMENT ON COLUMN sales.import_request.lead_time_min_days IS
    'Estimated days-to-arrival, low end. Autofilled from the factory''s country '
    '(th.co.glr.hr.importrequest.LeadTimeDefaults) when a default exists, editable thereafter, '
    'carried forward on revision, and required (with lead_time_max_days) before ISSUE.';

COMMENT ON COLUMN sales.import_request.lead_time_max_days IS
    'Estimated days-to-arrival, high end. See lead_time_min_days -- always set or cleared together '
    '(chk_import_request_lead_time).';

-- ── First-issue anchor for the arrival estimate (REVIEW ROUND 1, S7 -- owner default, flagged for
-- confirmation) ─────────────────────────────────────────────────────────────────────────────────
-- OWNER DEFAULT DECISION (S7, 2026-09-18 review round): the printed/derived arrival estimate
-- anchors on the FIRST issue date of this (deal, factory)'s whole revision chain -- the day the
-- factory was first CONTACTED -- not on whichever individual version's own issue_date happens to be
-- current. Without this column the anchor would silently move every time a typo revision is issued
-- (issue_date is re-stamped to "today" on EVERY issue, including a same-day correction), which would
-- quietly push a customer-facing arrival estimate out by however long the correction took to raise --
-- exactly the failure S7 exists to prevent. Set to today on a FIRST issue (no predecessor), carried
-- forward UNCHANGED on every subsequent revision's issue (see ImportRequestService#issue) -- flagged
-- here for the owner because "first issue, not latest issue" is a default this branch chose, not
-- something the owner was asked about by name.
ALTER TABLE sales.import_request
    ADD COLUMN first_issued_date DATE,
    -- Same "impossible half-state" pairing chk_import_request_step_pairing already applies to
    -- import_step: a DRAFT has never been issued, so it cannot have a first-issue date either. NOT
    -- VALID for the same legacy-safety reason chk_import_request_step_pairing gives (this migration
    -- is unapplied everywhere, but the table it alters is not new).
    ADD CONSTRAINT chk_import_request_first_issued_pairing CHECK (
        (status = 'DRAFT' AND first_issued_date IS NULL)
        OR (status <> 'DRAFT' AND first_issued_date IS NOT NULL)
    ) NOT VALID;

COMMENT ON COLUMN sales.import_request.first_issued_date IS
    'The date this (deal, factory)''s import_request chain was FIRST issued -- i.e. import_step '
    'reached CONTACTED for the first time. Carried forward, never overwritten, on every later '
    'revision''s issue, so a same-day or later correction cannot silently move the estimated '
    'arrival window (see ImportRequestService#withPageCount, which derives expectedArrivalFrom/To '
    'from THIS column, not issue_date). NULL while DRAFT, matching import_step''s own pairing.';

-- ── Order-email draft, per factory (owner decision 09-18 #3 §B) ───────────────────────────────
-- Mirrors the factory RFQ composer (FactoryQuoteService#emailBody and friends): the backend drafts
-- an email, a human copies it and sends it from their own mail client, and this system NEVER sends
-- mail on its own. Regenerated at ISSUE and again at every REVISION's issue (ImportRequestService),
-- so the draft always reflects the row's OWN snapshot -- the same "stored, not live" discipline
-- every other printed/derived field on this table already follows.
ALTER TABLE sales.import_request
    ADD COLUMN email_to           VARCHAR(255),
    ADD COLUMN email_subject      VARCHAR(255),
    ADD COLUMN email_body         TEXT,
    ADD COLUMN email_sent_at      TIMESTAMPTZ,
    ADD COLUMN email_sent_by_id   BIGINT,
    ADD COLUMN email_sent_by_name VARCHAR(200),
    -- REVIEW ROUND 3, item 3 (S-D regression): a SNAPSHOT of the issuing user's email at the moment
    -- of issue -- V154's own issued_by_id/issued_by_name carry who, but never their email, and
    -- hr.employee.email can change (or differ from what a session's UserPrincipal carried) between
    -- issue and any later setLeadTime edit. Without a stable snapshot, "regenerate only if the body
    -- still equals what generation would have produced" has no deterministic baseline to recompute
    -- against, and "always keep the ORIGINAL signer" would otherwise mean re-resolving a CURRENT
    -- email that can legitimately differ from the one actually embedded in the stored text.
    ADD COLUMN issued_by_email    VARCHAR(255);

COMMENT ON COLUMN sales.import_request.issued_by_email IS
    'Snapshot of the issuing user''s email at the moment of issue (REVIEW ROUND 3, item 3) -- read '
    'alongside issued_by_name as the order-email draft''s ORIGINAL signature, so a later '
    'setLeadTime-triggered regeneration (ImportRequestService#setLeadTime) never re-signs the email '
    'as whoever happened to be editing the lead time instead.';

COMMENT ON COLUMN sales.import_request.email_to IS
    'Order-email draft (owner decision 09-18 #3 §B) -- the factory''s price_catalog.factories.email '
    'at the time this version was issued, snapshotted like every other issue-time value on this '
    'table. May be NULL: not every factory master row carries an email address.';

COMMENT ON COLUMN sales.import_request.email_body IS
    'The drafted English order-email body -- greeting, per-line detail, required-by/expected-arrival, '
    'signature. A DRAFT ONLY: nothing in this codebase sends it. A human copies it (and the '
    'downloaded IR PDF) into their own mail client. See ImportRequestService#buildEmailDraft.';

COMMENT ON COLUMN sales.import_request.email_sent_at IS
    'Set by POST /api/import-requests/{id}/mark-email-sent once a human has actually sent the '
    'drafted email by hand -- a record that a human acted, never a system send. Idempotent: a second '
    'mark-sent call 409s rather than re-stamping. NULL means "not yet sent" (or not yet issued).';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 3. sales.import_request_item -- structured colour/surface, second pass (owner decision 09-18 #3
--    §A: "IR really should be filled").
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Carried SEPARATELY from the existing `note` column rather than folded into it. `note` is the
-- printed form's free-text sub-row (business-writable, prefilled once from these two at creation --
-- see ImportRequestLinePrefill#mergeNote -- and never silently re-derived over a hand-typed value
-- afterwards); these two are the structured values the order-email draft's Colour/Surface/Size
-- detail line reads directly, so building that line never has to re-parse a formatted Thai string
-- ("สี Grigio · ผิว Matte") back apart into its two halves. Same nullable, unconstrained shape as
-- their source columns (sales.ticket_item.color/texture, sales.pricing_request_item.color/texture).
ALTER TABLE sales.import_request_item
    ADD COLUMN color   VARCHAR(255),
    ADD COLUMN texture VARCHAR(255);

COMMENT ON COLUMN sales.import_request_item.color IS
    'Structured colour, resolved at draft creation from ticket_item.color, else the order-confirmed '
    'pricing_request_item''s (ImportRequestLinePrefill#firstNonBlank). Feeds the order-email draft''s '
    'Colour/Surface/Size line and the `note` sub-row''s auto-derivation -- kept separate from `note` '
    'so neither has to be reverse-parsed out of the other.';

COMMENT ON COLUMN sales.import_request_item.texture IS
    'Structured surface/texture -- see the color column''s comment on this table.';

-- REVIEW ROUND 2, S-A: the order-email draft's per-line header ("<Brand> <Model>  (Code: X)", same
-- header/code fallback shape as FactoryQuoteService's own item block) needs BOTH the brand and the
-- raw model text, not just `code` (which is already a resolved CATALOG-CODE-preferred cascade --
-- see ImportRequestQueryRepository#factoryResolutionCandidates -- and so is the wrong value to
-- print as a "model"). Carried the same nullable, snapshot-at-creation way as color/texture above;
-- never overwritten by a business edit (ImportRequestService only ever writes these via
-- replaceItems, at draft creation or revision, matching every other line field's own discipline).
ALTER TABLE sales.import_request_item
    ADD COLUMN brand VARCHAR(255),
    ADD COLUMN model VARCHAR(255);

COMMENT ON COLUMN sales.import_request_item.brand IS
    'The deal line''s product brand (ticket_item.brand), snapshotted at draft creation -- feeds the '
    'order-email draft''s per-line header ("<Brand> <Model>  (Code: X)"). Separate from '
    'sales.import_request.brand (the FORM-level display snapshot of the distinct brand(s) on this '
    'factory''s lines, joined for the printed header) -- this one is per LINE.';

COMMENT ON COLUMN sales.import_request_item.model IS
    'The deal line''s raw hand-typed model text (ticket_item.model), snapshotted at draft creation --'
    ' deliberately NOT the same value as `code` (which prefers the catalog product code, then the '
    'order-confirmed pricing_request_item''s own code, and only falls back to this same model text '
    'when neither exists) -- see ImportRequestQueryRepository#factoryResolutionCandidates.';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 4. sales.ticket_event -- IMPORT_STEP_ADVANCED and IMPORT_REQUEST_EMAIL_SENT join chk_event_kind
--    (last re-declared V78).
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Re-declared in FULL, matching V78's own precedent ("Forward-only fix: re-declare chk_event_kind
-- ONE more time... matching TicketEventKind.java's full current constant list exactly"). Diffed by
-- script against TicketEventKind.java before writing this: V78's 42 values match the java class's
-- 42 real ticket_event kinds (46 constants minus the 4 DEAL_QUOTATION_* notification-only values,
-- which TicketEventKind.java's own comment says are never passed into TicketRepository#addEvent*)
-- exactly, zero drift either direction -- so this list is V78's 42 plus exactly these two new
-- values. COORDINATED with the pricing session's V186 (see this branch's plan's "chk_event_kind
-- COORDINATION" section): V186 re-declares the list as V78 + these same two + its own
-- DEAL_QUOTATION_REORDERED. If this migration is ever edited again after V186 merges, that third
-- value must be added here too, or the two migrations' final states disagree on what a later
-- re-declaration overwrites.
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED',
    'ON_HOLD','DORMANT','RESUMED','POLICY_CHANGED',
    'QUOTATION_SENT','QUOTATION_ACCEPTED','QUOTATION_REJECTED',
    'PAYMENT_RECORDED','BILLING_UPDATED',
    'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED',
    'ORDER_CONFIRMED_FROM_QUOTATION',
    'IMPORT_STEP_ADVANCED','IMPORT_REQUEST_EMAIL_SENT'
));

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Pre-merge prod check (owner runs, read-only) before this deploys:
--   SELECT count(*) FROM sales.import_request;               -- expect 0 (table has no service/
--                                                                controller yet in prod)
--   SELECT count(*) FROM sales.import_request_item;          -- expect 0
--   SELECT * FROM sales.document_sequence WHERE doc_type = 'IMPORT_REQUEST';  -- expect no row
--   SELECT version FROM hr.flyway_schema_history WHERE version IN ('154','184');  -- expect 154
--                                                                present, 184 absent
-- Also check every other worktree under .claude/worktrees and shared dev/CI DBs for a stray V184
-- with a different checksum (Yang's original, never applied anywhere per this migration's header).
-- ─────────────────────────────────────────────────────────────────────────────────────────────
