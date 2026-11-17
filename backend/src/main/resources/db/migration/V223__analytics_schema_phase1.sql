-- V223: analytics schema, phase 1. Read-only reporting layer over sales.* (views) plus a weekly metric store.
-- Nothing here is read by the portal's own screens; it only reads application tables. The schema is listed in
-- spring.flyway.schemas so a clean (tests) drops it before the tables it depends on.
-- Rollback: DROP SCHEMA analytics CASCADE.
-- COUPLING: the views below read sales.* / hr.* / customers.* columns. A later migration that alters or drops such a
-- column must drop and recreate the affected analytics views in that same migration (see analytics/README.md).

CREATE SCHEMA IF NOT EXISTS analytics;

-- ---- stage dimension: one row per th.co.glr.hr.ticket.DealStage.ORDER value (keep in sync; a test enforces it) ----
CREATE TABLE analytics.dim_stage (
    raw_stage TEXT PRIMARY KEY,
    stage_no  INT  NOT NULL,
    phase     TEXT NOT NULL,
    is_open   BOOLEAN NOT NULL,
    is_won    BOOLEAN NOT NULL,
    is_deliver_collect BOOLEAN NOT NULL DEFAULT FALSE  -- board column 5; QuietRule never calls these deals quiet
);
INSERT INTO analytics.dim_stage (raw_stage, stage_no, phase, is_open, is_won) VALUES
    ('LEAD_APPROACH',      1, 'LEAD',     TRUE,  FALSE),
    ('PRESENTATION',       2, 'LEAD',     TRUE,  FALSE),
    ('SPEC_APPROVED',      3, 'SPEC',     TRUE,  FALSE),
    ('QUOTE_DESIGN_SIDE',  4, 'SPEC',     TRUE,  FALSE),
    ('QUOTE_OWNER',        5, 'SPEC',     TRUE,  FALSE),
    ('OWNER_SIGNOFF',      6, 'SPEC',     TRUE,  FALSE),
    ('AWAITING_BUYER',     7, 'BIDDING',  TRUE,  FALSE),
    ('QUOTE_BUYER',        8, 'BIDDING',  TRUE,  FALSE),
    ('NEGOTIATION',        9, 'BIDDING',  TRUE,  FALSE),
    ('ORDER_RECEIVED',    10, 'ORDER',    TRUE,  TRUE),
    ('DEPOSIT_RECEIVED',  11, 'ORDER',    TRUE,  TRUE),
    ('PROCUREMENT',       12, 'ORDER',    TRUE,  TRUE),
    ('DELIVERY_SCHEDULING',13,'DELIVERY', TRUE,  TRUE),
    ('DELIVERED',         14, 'DELIVERY', TRUE,  TRUE),
    ('CLOSED_PAID',       15, 'DELIVERY', FALSE, TRUE);
UPDATE analytics.dim_stage SET is_deliver_collect = TRUE
 WHERE raw_stage IN ('DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID');

-- ---- brand normalisation ----
CREATE TABLE analytics.brand_alias (raw_key TEXT PRIMARY KEY, brand TEXT NOT NULL);
INSERT INTO analytics.brand_alias (raw_key, brand) VALUES
    ('daygres', 'Daugres'), ('overlnd', 'Overland'),
    ('equie', 'Equipe'), ('rquipe', 'Equipe'), ('equipr', 'Equipe'), ('wquipe', 'Equipe'),
    ('longju', 'Long Ju'), ('harmomy', 'Harmony'), ('cotto d'' este', 'Cotto d''Este'),
    ('wdc', 'WDC'), ('jnj', 'JNJ'), ('lea', 'LEA');

-- Strips leading stray Thai combining marks (U+0E31, U+0E34-U+0E3A, U+0E47-U+0E4E) and whitespace, lower-cases,
-- applies the alias table, else initcap. Leading vowels (เ แ โ ใ ไ) are real letters and stay. NULL or blank -> NULL.
CREATE FUNCTION analytics.norm_brand(raw TEXT) RETURNS TEXT
LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN k = '' THEN NULL ELSE COALESCE((SELECT a.brand FROM analytics.brand_alias a WHERE a.raw_key = k), initcap(k)) END
      FROM (SELECT lower(btrim(regexp_replace(COALESCE(raw, ''), '^[\u0E31\u0E34-\u0E3A\u0E47-\u0E4E\s]+', ''))) AS k) s
$$;

-- ---- manual test-deal flags ----
CREATE TABLE analytics.test_deal (
    ticket_id  BIGINT PRIMARY KEY,
    reason     TEXT,
    is_test    BOOLEAN NOT NULL DEFAULT TRUE,  -- FALSE = explicit "not a test" override that beats the name regex
    flagged_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---- stg_deal: one row per sales.ticket ----
-- Owner: created_by (V202: created_by is the owner who sells it and earns the commission; keyed_by is only the typist).
-- deal_value is EX-VAT. It follows the sales board's pick (SalesBoardRepository.ROW_SELECT / mapRow): the total_amount (the
-- persisted ex-VAT subtotal) of the deal's latest quotation in APPROVED/ISSUED/SENT/ACCEPTED/EXPIRED (non-expired first,
-- then the headline party, then newest), else the pipeline-import imported_value, else the rep's estimated_value. So it
-- is LOWER than the figure the sales board prints, which is the VAT-inclusive total. imported_value / estimated_value are
-- used as entered; their VAT basis is unverified. Currency is not converted (non-THB quotations are rare).
-- value_quotation_id is that picked quotation (NULL when the value fell back to the import / estimate); stg_quote_line
-- reads its lines from it, so the pick lives in this one place.
-- last_activity_at mirrors th.co.glr.hr.ticket.QuietRule.lastTouchedSql: the latest of created_at, any deal_activity row,
-- and a STAGE_CHANGED / QUOTATION_ISSUED ticket_event (other event kinds such as EDITED or COMMENTED do not count).
-- is_quiet mirrors QuietRule.quietSql: ACTIVE, not in the deliver-and-collect board column (dim_stage.is_deliver_collect),
-- last touched more than 14 days ago, and no PLANNED visit by the owner dated today..today+14 (Bangkok). The 14 is the
-- default of app.sales.quiet-days (QuietRule.DEFAULT_QUIET_DAYS); change both together.
-- is_test: analytics.test_deal decides when it has a row (is_test = false is an explicit "not a test" override);
-- otherwise title / customer_name containing the whole word test or demo (Latin, case-insensitive) or ทดสอบ.
-- is_project_headline: the one open, non-test deal of its project that fct_project_pipeline takes the value from
-- (largest deal_value, ties by ticket_id).
CREATE VIEW analytics.stg_deal AS
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
                                AND aq.doc_status IN ('APPROVED', 'ISSUED', 'SENT', 'ACCEPTED')) THEN 'QUOTE_ACCEPTED'
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

-- ---- fct_project_pipeline: open non-test deals collapsed to one row per project ----
-- Several bidding contractors quote the same job; the project counts once, at its headline (largest) deal. A project is
-- quiet only when all its deals are.
CREATE VIEW analytics.fct_project_pipeline AS
SELECT project_key,
       max(deal_value) FILTER (WHERE is_project_headline) AS value,
       count(*)                      AS deals,
       array_agg(DISTINCT rep_id)    AS reps,
       array_agg(ticket_id)          AS ticket_ids,
       min(days_quiet)               AS days_quiet,
       bool_and(is_quiet)            AS is_quiet
  FROM analytics.stg_deal
 WHERE is_open AND NOT is_test
 GROUP BY project_key;

-- ---- stg_quote_line: TILE lines of each live, non-test deal's VALUE quotation (stg_deal.value_quotation_id) ----
-- One quotation per deal, so revisions / alternatives are not double counted. Brand quoted figures use only open
-- headline deals (is_open, is_project_headline); ordered figures use won deals (is_ordered).
CREATE VIEW analytics.stg_quote_line AS
SELECT d.ticket_id,
       q.quotation_id,
       d.rep_id,
       analytics.norm_brand(i.brand) AS brand,
       i.model,
       i.size,
       i.amount,
       COALESCE(i.area_sqm, i.pieces_after_wastage * i.sqm_per_piece) AS sqm,
       d.is_won AS is_ordered,
       d.is_open,
       d.is_project_headline
  FROM analytics.stg_deal d
  JOIN sales.quotation q ON q.quotation_id = d.value_quotation_id
  JOIN sales.quotation_item i ON i.quotation_id = q.quotation_id
 WHERE i.line_type = 'TILE'
   AND d.lifecycle <> 'CANCELLED'
   AND NOT d.is_test;

-- ---- stores ----
CREATE TABLE analytics.metric_weekly (
    week_start  DATE NOT NULL,
    metric_key  TEXT NOT NULL,
    dimension   TEXT NOT NULL DEFAULT '',
    value       NUMERIC,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (week_start, metric_key, dimension)
);
CREATE TABLE analytics.run_log (
    run_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    week_start DATE NOT NULL,
    check_key  TEXT NOT NULL,
    passed     BOOLEAN NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---- data-quality checks ----
CREATE VIEW analytics.dq_check AS
SELECT 'stage_mapped'::text AS check_key, n = 0 AS passed, 'unmapped tickets=' || n AS detail
  FROM (SELECT count(*) AS n FROM analytics.stg_deal WHERE stage_no IS NULL) c
UNION ALL
SELECT 'quote_line_brand_present', n = 0, 'lines with no brand=' || n
  FROM (SELECT count(*) AS n FROM analytics.stg_quote_line WHERE brand IS NULL) c
UNION ALL
SELECT 'won_deal_has_value', n = 0, 'won deals with no value=' || n
  FROM (SELECT count(*) AS n FROM analytics.stg_deal WHERE is_won AND NOT is_test AND deal_value IS NULL) c
UNION ALL
SELECT 'test_flag_reviewed', n = 0, 'name-flagged test deals with no analytics.test_deal row=' || n
  FROM (SELECT count(*) AS n FROM analytics.stg_deal WHERE test_by_name AND NOT has_test_override) c;

-- ---- weekly computation ----
-- compute_week(p_week_start [, p_now]): p_week_start is a Bangkok Monday.
--  * Activity metrics (rep_stage_moves_week, rep_activities_week) describe the week itself and are always recomputed.
--  * Snapshot metrics (pipeline_*, rep_quiet_deal_share, hygiene_score, brand_*) and run_log rows describe the data as it
--    is now, so they are written only when p_week_start is the current or the most recently ended Bangkok week
--    (p_week_start >= current Bangkok Monday - 7); for older weeks the existing snapshot rows are left untouched.
-- Re-running a week replaces its metric rows; run_log keeps one row per check per run.
CREATE FUNCTION analytics.compute_week(p_week_start DATE, p_now TIMESTAMPTZ DEFAULT now()) RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE
    w_from TIMESTAMPTZ := p_week_start::timestamp AT TIME ZONE 'Asia/Bangkok';
    w_to   TIMESTAMPTZ := (p_week_start + 7)::timestamp AT TIME ZONE 'Asia/Bangkok';
    is_recent BOOLEAN := p_week_start >= date_trunc('week', p_now AT TIME ZONE 'Asia/Bangkok')::date - 7;
    n      INTEGER;
BEGIN
    DELETE FROM analytics.metric_weekly
     WHERE week_start = p_week_start
       AND (is_recent OR metric_key IN ('rep_stage_moves_week', 'rep_activities_week'));

    -- activity metrics per rep (every rep who owns a non-test deal)
    INSERT INTO analytics.metric_weekly (week_start, metric_key, dimension, value)
    SELECT p_week_start, m.k, 'rep=' || r.rep_id, m.v
      FROM (SELECT DISTINCT rep_id FROM analytics.stg_deal WHERE NOT is_test) r
      CROSS JOIN LATERAL (VALUES
        ('rep_stage_moves_week', (SELECT count(*)::numeric FROM sales.ticket_event ev
                                    JOIN analytics.stg_deal d ON d.ticket_id = ev.ticket_id
                                   WHERE d.rep_id = r.rep_id AND NOT d.is_test AND ev.kind = 'STAGE_CHANGED'
                                     AND ev.created_at >= w_from AND ev.created_at < w_to)),
        ('rep_activities_week', (SELECT count(*)::numeric FROM sales.deal_activity a
                                   JOIN analytics.stg_deal d ON d.ticket_id = a.ticket_id
                                  WHERE d.rep_id = r.rep_id AND NOT d.is_test
                                    AND a.activity_date >= p_week_start AND a.activity_date < p_week_start + 7))
      ) AS m(k, v);

    IF is_recent THEN
        INSERT INTO analytics.metric_weekly (week_start, metric_key, dimension, value)
        SELECT p_week_start, 'pipeline_value_raw', '',
               COALESCE((SELECT sum(deal_value) FROM analytics.stg_deal WHERE is_open AND NOT is_test), 0)
        UNION ALL
        SELECT p_week_start, 'pipeline_value_dedup', '',
               COALESCE((SELECT sum(value) FROM analytics.fct_project_pipeline), 0)
        UNION ALL
        SELECT p_week_start, 'pipeline_stale_share', '',
               COALESCE(round((SELECT sum(value) FROM analytics.fct_project_pipeline WHERE is_quiet)
                              / NULLIF((SELECT sum(value) FROM analytics.fct_project_pipeline), 0), 4), 0);

        INSERT INTO analytics.metric_weekly (week_start, metric_key, dimension, value)
        WITH open_deals AS (SELECT * FROM analytics.stg_deal WHERE is_open AND NOT is_test)
        SELECT p_week_start, m.k, 'rep=' || r.rep_id, m.v
          FROM (SELECT DISTINCT rep_id FROM analytics.stg_deal WHERE NOT is_test) r
          CROSS JOIN LATERAL (VALUES
            ('rep_quiet_deal_share', (SELECT round(count(*) FILTER (WHERE is_quiet)::numeric / NULLIF(count(*), 0), 4)
                                        FROM open_deals o WHERE o.rep_id = r.rep_id)),
            ('hygiene_score', (SELECT round(avg((has_designer::int + has_win_probability::int + has_expected_quarter::int
                                                 + has_next_follow_up::int) / 4.0), 4)
                                 FROM open_deals o WHERE o.rep_id = r.rep_id))
          ) AS m(k, v)
         WHERE m.v IS NOT NULL;

        INSERT INTO analytics.metric_weekly (week_start, metric_key, dimension, value)
        SELECT p_week_start, m.k, 'brand=' || l.brand, m.v
          FROM (SELECT brand,
                       COALESCE(sum(amount) FILTER (WHERE is_open AND is_project_headline), 0) AS quoted_value,
                       COALESCE(sum(sqm) FILTER (WHERE is_open AND is_project_headline), 0) AS quoted_sqm,
                       COALESCE(sum(amount) FILTER (WHERE is_ordered), 0) AS ordered_value
                  FROM analytics.stg_quote_line WHERE brand IS NOT NULL GROUP BY brand) l
          CROSS JOIN LATERAL (VALUES ('quoted_value', l.quoted_value), ('quoted_sqm', l.quoted_sqm),
                                     ('ordered_value', l.ordered_value)) AS m(k, v);

        INSERT INTO analytics.run_log (week_start, check_key, passed, detail)
        SELECT p_week_start, check_key, passed, detail FROM analytics.dq_check;
    END IF;

    SELECT count(*) INTO n FROM analytics.metric_weekly WHERE week_start = p_week_start;
    RETURN n;
END $$;
