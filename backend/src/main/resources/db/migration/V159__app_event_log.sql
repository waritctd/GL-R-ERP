-- Application events: the part of the Render log that hr.activity_log cannot see.
--
-- hr.activity_log records one row per /api/ request, so it answers "who was in the portal". It is
-- blind to two things a Render log shows and people actually open a log to find:
--
--   1. Something went wrong. A handler that caught an exception and still returned 200 leaves a
--      WARN in the log and a perfectly healthy-looking 200 in activity_log.
--   2. A background job ran, or did not. Seven @Scheduled workers (BOT holiday fetch, BOT FX fetch,
--      attendance recalc, factory-quote email dispatch, quotation expiry, tax-allowance expiry)
--      make no HTTP request at all, so no request filter can ever see them.
--
-- DELIBERATELY NOT A FULL LOG MIRROR. INFO-level chatter is excluded and stack traces are stored
-- as type + message + first frame only, never whole. Owner decision, and the reason is the reader:
-- this table is surfaced on a web page, and a complete trace is the most likely place for a
-- connection string, a token or an employee's personal data to end up somewhere it should not be.
-- If you ever widen `level` to INFO, revisit that trade first.

CREATE TABLE hr.app_event (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 'LOG'  a WARN/ERROR emitted by application code
    -- 'JOB'  one execution of a @Scheduled worker, success or failure
    kind              TEXT        NOT NULL CHECK (kind IN ('LOG', 'JOB')),
    level             TEXT        NOT NULL CHECK (level IN ('WARN', 'ERROR', 'INFO')),
    logger            TEXT,                 -- logger name, or the job's method for kind='JOB'
    message           TEXT        NOT NULL,
    exception_type    TEXT,
    exception_message TEXT,
    -- One frame, not the trace. Enough to find the line; not enough to leak the request.
    first_frame       TEXT,
    -- Ties a LOG row to the hr.activity_log row for the request that produced it. NULL for
    -- background jobs and boot-time events, which have no request. Set by CorrelationIdFilter.
    correlation_id    TEXT,
    thread            TEXT,
    duration_ms       INTEGER               -- kind='JOB' only
);

-- "What went wrong today", newest first — the query this exists to serve.
CREATE INDEX idx_app_event_at            ON hr.app_event (at DESC);
-- "Everything that happened during that one request", joining to hr.activity_log.
CREATE INDEX idx_app_event_correlation   ON hr.app_event (correlation_id) WHERE correlation_id IS NOT NULL;
-- "Did the FX fetch run?" — job history for one worker.
CREATE INDEX idx_app_event_kind_at       ON hr.app_event (kind, at DESC);

COMMENT ON TABLE hr.app_event IS
    'WARN/ERROR application events and @Scheduled job runs — the half of the Render log that '
    'hr.activity_log (one row per HTTP request) structurally cannot see. Written best-effort and '
    'asynchronously; a failure here must never fail the work it describes, and must never be '
    'logged through slf4j or it would feed itself.';

COMMENT ON COLUMN hr.app_event.first_frame IS
    'A single stack frame, never the whole trace. Widening this is a data-exposure decision, not a '
    'formatting one: this table is rendered on a web page.';
