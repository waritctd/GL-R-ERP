-- Spec 0001 child 1: a quotation is real unless a rep marks it as a sample.
-- Existing rows all stay real (FALSE). This migration changes no stored value: only the operator's reviewed list
-- (spec 0001 child 3) may mark an old quotation.
ALTER TABLE sales.quotation
    ADD COLUMN is_sample BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_sample_direct CHECK (NOT is_sample OR origin = 'DEAL_DIRECT');

CREATE INDEX idx_quotation_sample_ticket ON sales.quotation (ticket_id) WHERE is_sample;

-- Same columns as V223, with samples left out of the value pick and the waiting bucket.
CREATE OR REPLACE VIEW analytics.stg_deal AS
SELECT b.*,
       (b.is_open AND NOT b.is_test
        AND row_number() OVER (PARTITION BY b.project_key, (b.is_open AND NOT b.is_test)
                               ORDER BY b.deal_value DESC NULLS LAST, b.ticket_id) = 1) AS is_project_headline
  FROM (
    SELECT t.ticket_id,
           t.code,
           t.created_by AS rep_id,
           e.nickname   AS rep_name,
           t.sales_stage,
           ds.stage_no,
           ds.phase,
           t.lifecycle,
           (t.lifecycle = 'ACTIVE' AND COALESCE(ds.is_open, TRUE)) AS is_open,
           COALESCE(ds.is_won, FALSE) AS is_won,
           t.entry_channel,
           COALESCE('P' || t.project_id::text, 'T' || t.ticket_id::text) AS project_key,
           COALESCE(td.is_test, nm.by_name) AS is_test,
           nm.by_name                       AS test_by_name,
           (td.ticket_id IS NOT NULL)       AS has_test_override,
           q.quotation_id                   AS value_quotation_id,
           COALESCE(q.total_amount, t.imported_value, t.estimated_value) AS deal_value,
           la.last_activity_at,
           GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (now() - la.last_activity_at)) / 86400))::int AS days_quiet,
           (t.lifecycle = 'ACTIVE'
            AND NOT COALESCE(ds.is_deliver_collect, FALSE)
            AND la.last_activity_at < now() - make_interval(days => 14)
            AND NOT EXISTS (SELECT 1 FROM sales.visit uv
                             WHERE uv.ticket_id = t.ticket_id AND uv.status = 'PLANNED' AND uv.owner_id = t.created_by
                               AND uv.planned_date BETWEEN (now() AT TIME ZONE 'Asia/Bangkok')::date
                                                       AND (now() AT TIME ZONE 'Asia/Bangkok')::date + 14)) AS is_quiet,
           (NULLIF(btrim(t.designer_name), '') IS NOT NULL) AS has_designer,
           (t.win_probability IS NOT NULL)                  AS has_win_probability,
           (t.expected_quarter IS NOT NULL)                 AS has_expected_quarter,
           (t.next_follow_up_at IS NOT NULL)                AS has_next_follow_up,
           CASE WHEN NOT (t.lifecycle = 'ACTIVE' AND COALESCE(ds.is_open, TRUE)) THEN NULL
                WHEN t.sales_stage = 'ORDER_RECEIVED' THEN 'ORDER_NO_DEPOSIT'
                WHEN COALESCE(ds.is_won, FALSE) THEN NULL
                WHEN EXISTS (SELECT 1 FROM sales.quotation aq WHERE aq.ticket_id = t.ticket_id
                                AND NOT aq.is_sample AND aq.doc_status IN ('APPROVED', 'ISSUED', 'SENT', 'ACCEPTED')) THEN 'QUOTE_ACCEPTED'
           END AS wait_bucket,
           (GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (now() - la.last_activity_at)) / 86400)) > 30) AS is_stale_30d
      FROM sales.ticket t
      LEFT JOIN hr.employee e ON e.employee_id = t.created_by
      LEFT JOIN analytics.dim_stage ds ON ds.raw_stage = t.sales_stage
      LEFT JOIN analytics.test_deal td ON td.ticket_id = t.ticket_id
      LEFT JOIN LATERAL (
          SELECT vq.quotation_id, vq.total_amount
            FROM sales.quotation vq
            LEFT JOIN sales.deal_party vp ON vp.party_id = vq.party_id
           WHERE vq.ticket_id = t.ticket_id
             AND NOT vq.is_sample
             AND vq.doc_status IN ('APPROVED', 'ISSUED', 'SENT', 'ACCEPTED', 'EXPIRED')
           ORDER BY (vq.doc_status <> 'EXPIRED') DESC, COALESCE(vp.is_headline, FALSE) DESC,
                    COALESCE(vq.approved_at, vq.issued_at) DESC NULLS LAST, vq.quotation_id DESC
           LIMIT 1
      ) q ON TRUE
      CROSS JOIN LATERAL (
          SELECT (COALESCE(t.title, '') ~* '(\m(test|demo)\M|ทดสอบ)' OR COALESCE(t.customer_name, '') ~* '(\m(test|demo)\M|ทดสอบ)') AS by_name
      ) nm
      CROSS JOIN LATERAL (
          SELECT GREATEST(t.created_at,
                     (SELECT max(a.created_at) FROM sales.deal_activity a WHERE a.ticket_id = t.ticket_id),
                     (SELECT max(ev.created_at) FROM sales.ticket_event ev
                       WHERE ev.ticket_id = t.ticket_id AND ev.kind IN ('STAGE_CHANGED', 'QUOTATION_ISSUED'))) AS last_activity_at
      ) la
  ) b;
