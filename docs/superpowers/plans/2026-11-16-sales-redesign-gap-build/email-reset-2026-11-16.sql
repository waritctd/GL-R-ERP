-- Reopen the 2026-11-16 release-notice email run on production.
-- PRECONDITIONS (all three, or this does nothing useful / does harm):
--   1. Backend image containing PR #1313 is deployed (older code ignores the marker).
--   2. A real notification email has been seen to SEND with the new Resend key.
--   3. The owner has said the email may go out.
-- Append-only: inserts one audit row, deletes nothing. Takes the same advisory lock the email action holds,
-- so it cannot interleave with a send in progress (class id 1380273230 = 0x52454C4E = LOCK_CLASS_ID).
-- All-or-nothing: voids EVERY earlier emailed row for this release. Safe here because none of the 28 arrived.
BEGIN;
SELECT pg_advisory_xact_lock(1380273230, hashtext('email:2026-11-16'));

SELECT count(*) AS emailed_rows_before
  FROM hr.audit_log
 WHERE action = 'RELEASE_NOTE_EMAILED' AND after_json ->> 'releaseId' = '2026-11-16';   -- expect 28

INSERT INTO hr.audit_log (actor_email, action, entity, after_json)
VALUES ('ops-reset', 'RELEASE_NOTE_EMAIL_RESET', 'release_note',
        jsonb_build_object('releaseId', '2026-11-16'));

SELECT count(*) AS reset_markers
  FROM hr.audit_log
 WHERE action = 'RELEASE_NOTE_EMAIL_RESET' AND after_json ->> 'releaseId' = '2026-11-16'; -- expect 1
COMMIT;
-- Afterwards, in the app: open มีอะไรใหม่ as CEO/HR, press ส่งอีเมล, and check the confirm says 28 pending
-- BEFORE confirming. A marker with a wrong entity or JSON is silently ignored, so that count is the check.
