-- V218: sales-insights snapshot. One analytics JSON document is produced outside the portal each month and uploaded
-- by the CEO (PUT /api/sales-analytics/snapshot); ceo + sales_manager read the latest (GET). Every upload is kept as
-- history; the newest row wins. Additive: a new table, nothing existing changes.
CREATE TABLE IF NOT EXISTS sales.sales_analytics_snapshot (
    snapshot_id BIGSERIAL PRIMARY KEY,
    payload     JSONB NOT NULL,
    as_of       TEXT,
    uploaded_by BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sales_analytics_snapshot_latest ON sales.sales_analytics_snapshot (uploaded_at DESC, snapshot_id DESC);
