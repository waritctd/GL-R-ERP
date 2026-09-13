-- Quotation v2 -- freeze the APPROVER on an approved direct-deal quotation (owner ruling 2026-09-13:
-- "Already-sent quotations never change afterwards"; and, for quotations approved before this
-- migration, owner ruling 2026-09-13: "Freeze them unsigned." -- see BACKFILL at the end).
--
-- MIGRATION NUMBERING: origin/develop and origin/main both top out at V174 (catalog size cm
-- display), so V175 is the next free number ABOVE both maxima -- a lower number merged later would
-- be silently skipped on the prod deploy (validate-on-migrate is false there; see CLAUDE.md).
--
-- THE DEFECT. Until now the approver's printed name (Thai, and the English name the F-SM-008 form
-- prints) was LEFT JOINed from hr.employee at read time, and the signature image was read LIVE
-- from hr.employee_signature on every PDF/XLSX render. So an approver who later replaced or deleted
-- their signature, or whose employee name was corrected, silently changed every quotation they had
-- ever approved the next time it was downloaded. V167 already fixed the same class of defect for
-- the ผู้สั่งซื้อ contact; this is the approver half.
--
-- THE SHAPE: a 1:1 side table rather than more columns on sales.quotation (V167's precedent).
--   1. ROW PRESENCE is the "snapshot taken" signal. A nullable image column alone cannot tell
--      "approved before this migration, never snapshotted" apart from "snapshotted, approver had
--      no signature on file" -- and those two MUST stay distinguishable (see BACKFILL below).
--      snapshotted_at is NOT NULL on every row, so an absent row is the only "not taken" state.
--   2. The signature is a BYTEA blob only the render path reads. sales.quotation is the wide,
--      frequently-updated row shared with the pricing chain (CustomerQuotationRepository) and the
--      dashboard; keeping the blob off it means none of those reads or updates ever carry it, and
--      pricing-chain rows never grow approver columns that are meaningless for them.
--
-- WRITTEN BY DealQuotationRepository#approve, in the SAME statement as the PENDING_APPROVAL ->
-- APPROVED compare-and-set (a data-modifying CTE), so a quotation can never be APPROVED without its
-- snapshot, nor carry a snapshot from an approval that lost the race. A quotation can only be
-- approved once, so nothing rewrites a row afterwards (the approve statement's ON CONFLICT clause is
-- defensive only). A revision is a new quotation row and gets its own snapshot at its own approval.
--
-- approver_id is a soft reference (no FK), like V167's contact_id: the snapshot must outlive
-- anything that happens to the employee row. Name widths: first 100 + ' ' + last 100 -> 201,
-- rounded to 255 (V167's convention). Mime width mirrors hr.employee_signature.mime_type (V165).
CREATE TABLE IF NOT EXISTS sales.quotation_approver_snapshot (
    quotation_id        BIGINT PRIMARY KEY REFERENCES sales.quotation(quotation_id) ON DELETE CASCADE,
    approver_id         BIGINT NOT NULL,
    approver_name_th    VARCHAR(255),
    approver_name_en    VARCHAR(255),
    signature_mime_type VARCHAR(40),
    signature_image     BYTEA,
    snapshotted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT quotation_approver_snapshot_signature_pair
        CHECK ((signature_image IS NULL) = (signature_mime_type IS NULL))
);

COMMENT ON TABLE sales.quotation_approver_snapshot IS
    'Frozen approver identity for an approved DEAL_DIRECT quotation (V175, owner ruling 2026-09-13). One row per approved quotation, written atomically with the approval; row presence means the snapshot was taken. Never rewritten.';
COMMENT ON COLUMN sales.quotation_approver_snapshot.approver_id IS
    'sales.quotation.approved_by at approval time. Soft reference, no FK: the snapshot must outlive the employee row.';
COMMENT ON COLUMN sales.quotation_approver_snapshot.approver_name_th IS
    'Approver Thai name (first_name_th last_name_th) as it was at approval; NULL if blank then.';
COMMENT ON COLUMN sales.quotation_approver_snapshot.approver_name_en IS
    'Approver English name (first_name_en last_name_en) as it was at approval; NULL if blank then (the English document falls back to the Thai name).';
COMMENT ON COLUMN sales.quotation_approver_snapshot.signature_mime_type IS
    'hr.employee_signature.mime_type at approval; NULL exactly when signature_image is NULL.';
COMMENT ON COLUMN sales.quotation_approver_snapshot.signature_image IS
    'hr.employee_signature.image bytes at approval; NULL = the approver had no signature on file then, and the document prints none forever.';
COMMENT ON COLUMN sales.quotation_approver_snapshot.snapshotted_at IS
    'When the snapshot was taken: the approval instant, or the backfill instant for a quotation approved before V175.';

-- ── BACKFILL: APPLIED -- owner ruling 2026-09-13, "Freeze them unsigned." ───────────────────────
-- Every DEAL_DIRECT quotation already approved when this migration runs (APPROVED, SUPERSEDED, or
-- any other post-approval state) is frozen NOW, at the approver's names and signature AS THEY ARE
-- AT MIGRATION TIME. From this deploy on, a later signature upload/replace/delete or employee
-- rename never reaches those documents.
--
-- ⚠️ Known, accepted consequence: on production every such row (8 at the time of writing -- 4
-- APPROVED + 4 SUPERSEDED, all approved 2026-09-11) was approved with NO signature on file,
-- so all 8 are frozen with the approver's names and NO signature, PERMANENTLY -- a signature the
-- approver uploads afterwards will not appear on them. That is the owner's explicit choice; a
-- document that needs a signature gets one through a new revision, which snapshots at its own
-- approval.
--
-- Idempotent (ON CONFLICT DO NOTHING -- an existing snapshot is never refreshed) and skips DRAFT and
-- PENDING_APPROVAL. Cheap: a handful of rows. The read/render path keeps a live fallback for a row
-- with no snapshot, but after this statement no approved row lacks one.
-- DealQuotationApproverSnapshotIntegrationTest#backfill_* executes this whole file as shipped.
INSERT INTO sales.quotation_approver_snapshot
    (quotation_id, approver_id, approver_name_th, approver_name_en,
     signature_mime_type, signature_image, snapshotted_at)
SELECT q.quotation_id, q.approved_by,
       NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''),
       NULLIF(TRIM(CONCAT_WS(' ', e.first_name_en, e.last_name_en)), ''),
       es.mime_type, es.image, now()
  FROM sales.quotation q
  LEFT JOIN hr.employee e            ON e.employee_id  = q.approved_by
  LEFT JOIN hr.employee_signature es ON es.employee_id = q.approved_by
 WHERE q.origin = 'DEAL_DIRECT'
   AND q.approved_by IS NOT NULL
   AND q.doc_status NOT IN ('DRAFT', 'PENDING_APPROVAL')
ON CONFLICT (quotation_id) DO NOTHING;
