-- V210: move deals that a customer-facing quotation already put past the lead stages. DATA MIGRATION ON PROD —
-- needs the owner's deploy approval (CLAUDE.md: a migration is an owner-approved operation with a rollback plan).
--
-- ── Why ──────────────────────────────────────────────────────────────────────────────────────
-- Sales flow redesign slice 1 (spec 2026-11-15 §7.2, review findings H2 and round-2 N1). A quotation that reached
-- the customer moves its deal onto the recipient's quote stage. On production, 178 of 289 approved quotations are
-- recipient UNSPECIFIED (rows written before quotation recipients existed, and the revisions/reorders that inherit
-- that), and their deals are therefore still at the lead stages although they were quoted. Following the
-- owner-approved V204 reading (a direct quotation is addressed to a buyer — V204 gave every such deal a BUYER party),
-- TicketService#applyApprovedQuotation now advances an UNSPECIFIED approval/issue like a BUYER one
-- (TicketService#stageForUnspecifiedRecipient); this migration does the same once for the deals approved before it.
--
-- ── What it does ─────────────────────────────────────────────────────────────────────────────
-- For every ACTIVE deal holding a quotation in APPROVED / ISSUED / SENT / ACCEPTED:
--   * target stage = QUOTE_BUYER when it is on the deal's route, else the nearest on-route stage before it — the SQL
--     restatement of TicketService#stageForUnspecifiedRecipient. `off_route` below restates DealRoute's OFF_ROUTE
--     table verbatim; QUOTE_BUYER is on every route (incl. BUYER_DIRECT and UNSPECIFIED), so every channel targets
--     QUOTE_BUYER today. ApprovedQuotationStageBackfillMigrationIntegrationTest pins this to the Java for every channel.
--   * FORWARD ONLY: the deal moves only if its current stage comes strictly before the target in DealStage.ORDER
--     (the array below, in pipeline order). A deal already at or past the target is untouched.
--   * sets sales_stage and stage_updated_at, and writes one STAGE_CHANGED ticket_event noted
--     'ย้ายอัตโนมัติจากใบเสนอราคาที่อนุมัติแล้ว (ย้อนหลัง)'.
--   * Both timestamps are the quotation's own approval/issue time (COALESCE(approved_at, issued_at)), NOT the deploy
--     time: a STAGE_CHANGED event counts as a touch for the 14-day quiet rule, so stamping it now() would make
--     long-quiet deals look freshly worked. ticket.created_at is not touched. The event's actor is the approver
--     (else the issuer, else the deal owner); actor_name 'ระบบ' marks it as automatic.
-- Lost / cancelled / on-hold / dormant / completed deals (lifecycle <> 'ACTIVE') are untouched.
--
-- Edited in place (round-2 review): V210 is unmerged and has been applied nowhere but test databases.
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
-- DealRoute.OFF_ROUTE, verbatim. A channel absent here (DESIGNER_LED, UNSPECIFIED) skips nothing.
off_route (channel, stage) AS (
    VALUES ('OWNER_DIRECT', 'QUOTE_DESIGN_SIDE'),
           ('BUYER_DIRECT', 'QUOTE_DESIGN_SIDE'), ('BUYER_DIRECT', 'QUOTE_OWNER'),
           ('BUYER_DIRECT', 'OWNER_SIGNOFF'), ('BUYER_DIRECT', 'AWAITING_BUYER')
),
target AS (
    SELECT t.ticket_id,
           t.sales_stage AS from_stage,
           -- TicketService#stageForUnspecifiedRecipient: QUOTE_BUYER, else the nearest on-route stage before it.
           (SELECT u.stage
              FROM stage_order, unnest(stage_order.stages) WITH ORDINALITY AS u(stage, pos)
             WHERE u.pos <= array_position(stage_order.stages, 'QUOTE_BUYER')
               AND NOT EXISTS (SELECT 1 FROM off_route o WHERE o.channel = t.entry_channel AND o.stage = u.stage)
             ORDER BY u.pos DESC
             LIMIT 1) AS to_stage,
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
