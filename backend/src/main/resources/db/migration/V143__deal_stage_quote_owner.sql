-- V143: split the designer quotation stage from the owner quotation stage.
--
-- V50 created chk_ticket_sales_stage over the 14-stage pipeline, in which a single stage
-- 'QUOTE_DESIGN_SIDE' stood for BOTH S4 (quote the designer) and S5 (quote the project owner) —
-- TicketService mapped the DESIGNER and OWNER quotation recipients onto the same stage.
--
-- Those are two different documents. The product list is the same, but quantity, price and terms
-- differ by recipient: S4 carries model + price with a nominal quantity and terms usually omitted,
-- while S5 carries the negotiated price, the owner's real quantity and the import lead time. The
-- CEO tracks them as separate milestones on the S1–S20 sheet, and the merge left sales_stage
-- unable to answer "has the owner been quoted yet?" — which is precisely the milestone before
-- OWNER_SIGNOFF (S6).
--
-- This migration only WIDENS the constraint's value set. Deliberately NOT done here:
--
--   * 'QUOTE_DESIGN_SIDE' is neither renamed nor renumbered. Its string value is carried by every
--     historical sales.ticket.sales_stage row, by the uat seeds and by the frontend stage
--     metadata; th.co.glr.hr.ticket.DealStage.ORDER is the Java mirror of the pipeline order and
--     places QUOTE_OWNER between QUOTE_DESIGN_SIDE and OWNER_SIGNOFF without moving anything else.
--   * No backfill. A historical QUOTE_DESIGN_SIDE row is genuinely ambiguous — it could have been
--     either recipient, because the code that wrote it did not distinguish them — so rewriting any
--     of them to QUOTE_OWNER would fabricate history. Old rows keep the merged meaning; only
--     forward writes are split.
--
-- Keep this set in sync with th.co.glr.hr.ticket.DealStage.ORDER, which is its Java mirror.

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_sales_stage;
ALTER TABLE sales.ticket
    ADD CONSTRAINT chk_ticket_sales_stage CHECK (sales_stage IN (
        'LEAD_APPROACH','PRESENTATION',
        'SPEC_APPROVED','QUOTE_DESIGN_SIDE','QUOTE_OWNER','OWNER_SIGNOFF',
        'AWAITING_BUYER','QUOTE_BUYER','NEGOTIATION',
        'ORDER_RECEIVED','DEPOSIT_RECEIVED','PROCUREMENT',
        'DELIVERY_SCHEDULING','DELIVERED','CLOSED_PAID'));
