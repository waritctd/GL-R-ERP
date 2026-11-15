-- V210: move deals that a customer-facing quotation already put past the lead stages. DATA MIGRATION ON PROD —
-- needs the owner's deploy approval (CLAUDE.md: a migration is an owner-approved operation with a rollback plan).
--
-- ── Why ──────────────────────────────────────────────────────────────────────────────────────
-- Sales flow redesign slice 1 (spec 2026-11-15 §7.2, review finding H2). A quotation that reached the customer
-- moves its deal onto the recipient's quote stage — but only when the recipient is named (DESIGNER / OWNER /
-- BUYER). On production, 178 of 289 approved quotations are recipient UNSPECIFIED (rows written before quotation
-- recipients existed, and the revisions/reorders that inherit that), and 123 ACTIVE quotation-only deals are
-- therefore still at LEAD_APPROACH (S1) although they were quoted. The new board would show them under
-- "เสนอสินค้า" forever. From this release, TicketService#applyApprovedQuotation advances an UNSPECIFIED-recipient
-- approval/issue to the first quote stage on the deal's route; this migration does the same once for the deals
-- approved before it existed.
--
-- ── What it does ─────────────────────────────────────────────────────────────────────────────
-- For every ACTIVE deal holding a quotation in APPROVED / ISSUED / SENT / ACCEPTED (any recipient):
--   * target stage = the first quote stage on the deal's route — the SQL restatement of
--     TicketService#firstQuoteStageOnRoute / DealRoute: OWNER_DIRECT → QUOTE_OWNER (QUOTE_DESIGN_SIDE is off its
--     route), BUYER_DIRECT → none (both are off its route; nothing is guessed), every other channel including
--     UNSPECIFIED/NULL → QUOTE_DESIGN_SIDE. ApprovedQuotationStageBackfillMigrationIntegrationTest pins this CASE to
--     the Java method for every channel.
--   * FORWARD ONLY: the deal moves only if its current stage comes strictly before the target in DealStage.ORDER
--     (the array below, in pipeline order). A deal already at or past a quote stage is untouched.
--   * sets sales_stage and stage_updated_at, and writes one STAGE_CHANGED ticket_event noted
--     'ย้ายอัตโนมัติจากใบเสนอราคาที่อนุมัติแล้ว (ย้อนหลัง)'.
--   * Both timestamps are the quotation's own approval/issue time (COALESCE(approved_at, issued_at)), NOT the deploy
--     time: a STAGE_CHANGED event counts as a touch for the 14-day quiet rule, so stamping it now() would make 123
--     long-quiet deals look freshly worked. ticket.created_at is not touched. The event's actor is the approver
--     (else the issuer, else the deal owner); actor_name 'ระบบ' marks it as automatic.
-- Lost / cancelled / on-hold / dormant / completed deals (lifecycle <> 'ACTIVE') are untouched.
--
-- ── Safety ───────────────────────────────────────────────────────────────────────────────────
-- Idempotent: a second run finds every moved deal already at its target and does nothing (the integration test
-- runs it twice). No schema change. Rollback: every row it wrote is identifiable —
--   SELECT ticket_id, from_status FROM sales.ticket_event
--    WHERE kind = 'STAGE_CHANGED' AND message = 'ย้ายอัตโนมัติจากใบเสนอราคาที่อนุมัติแล้ว (ย้อนหลัง)';
-- and setting sales_stage back to from_status for those tickets (then deleting the events) reverses it.
-- Dry run on prod before deploy (read-only):
--   the `target` CTE below as a SELECT, filtered by the same forward-only predicate, gives the exact list.
WITH stage_order AS (
    SELECT ARRAY['LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER',
                 'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED',
                 'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID'] AS stages
),
target AS (
    SELECT t.ticket_id,
           t.sales_stage AS from_stage,
           CASE t.entry_channel
               WHEN 'OWNER_DIRECT' THEN 'QUOTE_OWNER'
               WHEN 'BUYER_DIRECT' THEN NULL
               ELSE 'QUOTE_DESIGN_SIDE'
           END AS to_stage,
           q.touched_at,
           q.actor_id
      FROM sales.ticket t
      CROSS JOIN LATERAL (
          SELECT COALESCE(vq.approved_at, vq.issued_at) AS touched_at,
                 COALESCE(vq.approved_by, vq.issued_by, t.created_by) AS actor_id
            FROM sales.quotation vq
           WHERE vq.ticket_id = t.ticket_id
             AND vq.doc_status IN ('APPROVED', 'ISSUED', 'SENT', 'ACCEPTED')
           ORDER BY COALESCE(vq.approved_at, vq.issued_at) DESC NULLS LAST, vq.quotation_id DESC
           LIMIT 1
      ) q
     WHERE t.lifecycle = 'ACTIVE'
),
moved AS (
    UPDATE sales.ticket t
       SET sales_stage      = target.to_stage,
           stage_updated_at = COALESCE(target.touched_at, now())
      FROM target, stage_order
     WHERE t.ticket_id = target.ticket_id
       AND target.to_stage IS NOT NULL
       AND COALESCE(array_position(stage_order.stages, t.sales_stage::text), 0)
           < array_position(stage_order.stages, target.to_stage)
    RETURNING t.ticket_id, target.from_stage, target.to_stage, target.touched_at, target.actor_id
)
INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status, message, created_at)
SELECT ticket_id, actor_id, 'ระบบ', 'STAGE_CHANGED', from_stage, to_stage,
       'ย้ายอัตโนมัติจากใบเสนอราคาที่อนุมัติแล้ว (ย้อนหลัง)', COALESCE(touched_at, now())
  FROM moved;
