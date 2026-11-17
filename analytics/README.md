# analytics

Read-only reporting layer (schema `analytics`, migration V223): views over `sales.*` / `hr.*` / `customers.*` plus `metric_weekly` and `run_log`. `metrics.yml` defines every metric `compute_week` writes.

- The views depend on columns of the sales / hr tables. A later migration that alters or drops such a column must drop and recreate the affected analytics views in that same migration, or it fails (or silently breaks the views).
- The weekly job (`AnalyticsWeeklyJob`, Monday 06:00 Bangkok) computes the week that just ended.
- By hand: `SELECT analytics.compute_week('2026-11-09');` (any Bangkok Monday). Activity metrics are recomputed for any week; snapshot metrics and `run_log` only for the current or last ended week.
- Data-quality results: `SELECT * FROM analytics.dq_check;`. Mark a real deal whose name looks like a test with `INSERT INTO analytics.test_deal (ticket_id, is_test) VALUES (<id>, false)`.
