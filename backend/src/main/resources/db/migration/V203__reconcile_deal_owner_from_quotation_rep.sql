-- V203: one-time reconcile of deal ownership (Ploy ruling 2026-11-06). Before V202, admin staff keyed deals for
-- sales reps, so sales.ticket.created_by is the admin while the rep who really owns the deal is named on the
-- deal's quotation (sales_rep_display_id). For each deal, the latest quotation (highest quotation_id) that is not
-- CANCELLED/REJECTED decides: if it names an eligible rep other than the creator, that rep becomes the owner
-- (created_by) and the old creator becomes the keyer (keyed_by). Eligible = the same predicate as
-- QuotationDisplayNameEligibility (active, and sales division 'sa' or the can_create_quotation grant).
-- Idempotent: once re-owned, created_by equals the display rep, so a second run matches nothing.
UPDATE sales.ticket t
   SET keyed_by   = t.created_by,
       created_by = q.sales_rep_display_id
  FROM (SELECT DISTINCT ON (ticket_id) ticket_id, sales_rep_display_id
          FROM sales.quotation
         WHERE doc_status NOT IN ('CANCELLED', 'REJECTED')
         ORDER BY ticket_id, quotation_id DESC) q
 WHERE q.ticket_id = t.ticket_id
   AND q.sales_rep_display_id IS NOT NULL
   AND q.sales_rep_display_id <> t.created_by
   -- Not a deal already keyed through the V202 owner picker; not one whose commission is already recorded.
   AND t.keyed_by IS NULL
   AND NOT EXISTS (SELECT 1 FROM sales.commission_record cr WHERE cr.source_ticket_id = t.ticket_id)
   AND EXISTS (
       SELECT 1 FROM hr.employee e
        WHERE e.employee_id = q.sales_rep_display_id
          AND e.is_active
          AND (EXISTS (SELECT 1 FROM hr.division d
                        WHERE d.division_id = e.division_id
                          AND LOWER(TRIM(COALESCE(NULLIF(TRIM(d.source_code), ''), split_part(d.name_th, '-', 1)))) = 'sa')
               OR e.can_create_quotation));
