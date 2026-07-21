-- Extends the factory quote email dispatch audit table (V64) into a real transactional
-- outbox: a background worker claims rows atomically and retries them, instead of
-- FactoryQuoteService.send() sending synchronously in the HTTP request.
--
-- NUMBERING — why this is V67 and not V66.
--
-- Checked live on 2026-07-20, the same day this migration was authored, against every place a
-- version number could collide:
--   - This branch's own commit 1 already claimed V65 (factory_quote_response_idempotency), so the
--     repo tip on this branch is V65, not the V64 in the last merged handoff.
--   - V66 is claimed by an UNTRACKED file (V66__special_money_request_schema.sql) sitting in the
--     parallel worktree .claude/worktrees/special-money on feat/special-money-requests. It has not
--     merged, so it will not show up in `git log` or `git diff` from this branch, but it is real
--     work-in-progress on disk and taking V66 here would collide the moment that branch merges.
--   - origin/main is at V54. The hosted production database is one version AHEAD of main, at V55
--     ("quotation doc terms"), applied from a branch that had not merged at the time (the same kind
--     of drift documented in V60's header on chore/attendance-daily-migration-hold). UAT is at V54.
--   - Flyway on this repo runs with out-of-order unset (default false) and validate-on-migrate off
--     only on the prod profile, so a version taken here that is later claimed by another in-flight
--     branch would deploy successfully and silently skip that branch's migration on merge — the
--     exact failure mode V60's header describes.
--
-- V67 is the first version free against all of: current main, the hosted prod DB, the hosted UAT
-- DB, this branch's own V65, and every other open branch/worktree's migration files (tracked or
-- not) as of 2026-07-20. Re-check the same way (repo tip, `git worktree list` + each worktree's
-- migration directory, and each deployed environment's hr.flyway_schema_history) before adding the
-- next migration on top of this one.

ALTER TABLE sales.factory_quote_email_dispatch
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN provider_message_id TEXT,
    ADD COLUMN finalized_at TIMESTAMPTZ;

-- Rows created under the old (pre-outbox) synchronous send path went straight from PENDING to
-- SENT with no intermediate claim/finalize bookkeeping. Backfill finalized_at for those so the
-- worker's "already finalized" fast path (finalized_at IS NULL) reads them consistently with rows
-- the worker itself completes.
UPDATE sales.factory_quote_email_dispatch
   SET finalized_at = sent_at
 WHERE status = 'SENT'
   AND sent_at IS NOT NULL
   AND finalized_at IS NULL;

-- Supports the worker's claim query/scan: candidates are rows not yet exhausted
-- (attempt_count < the configured cap, enforced in the application query) whose status is
-- PENDING (never attempted), FAILED (backed off, due for retry), or SENDING-but-stale (claimed by
-- a worker that crashed before finishing, past the reclaim timeout) AND whose next_attempt_at has
-- elapsed or was never set. SENT rows are terminal and excluded by the status list itself.
CREATE INDEX idx_factory_quote_email_dispatch_claimable
    ON sales.factory_quote_email_dispatch (status, next_attempt_at, claimed_at, attempt_count)
    WHERE status IN ('PENDING', 'SENDING', 'FAILED');
