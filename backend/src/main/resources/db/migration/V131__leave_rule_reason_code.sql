SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Phase A0a (structured rejection outcome): LeaveService#autoRejectNote used to return a plain
-- English rejection String (systemNote) -- unusable by the frontend for anything structured, and
-- wrong for a Thai-primary HR product regardless. It now returns a LeaveRuleOutcome (see that
-- record and LeaveRuleCode/LeaveRuleMessages), carrying a machine-readable code, the params that
-- rendered it, and a Thai sentence. system_note keeps carrying that Thai sentence, unchanged in
-- role; these two columns carry the structured part alongside it.
--
-- PURE REPRESENTATION CHANGE: no rejection rule's condition, threshold, or evaluation order changes
-- in this migration or its paired Java change -- only how the reason is COMMUNICATED.
--
-- NO BACKFILL: every row created before this migration keeps its existing (English) system_note and
-- has system_note_code/system_note_params NULL. A consumer that wants the structured code for a
-- historical AUTO_REJECTED row does not have one -- fall back to system_note (still populated, just
-- not machine-parseable) the same way this codebase's other no-backfill boundaries are handled (see
-- e.g. V127's CARRY_FORWARD_BASELINE_YEAR comment in LeaveService for the same "a wrong/invented
-- historical value is worse than none" reasoning). Re-deriving system_note_code for old rows would
-- require re-running #autoRejectNote's decision against each row's PAST state (quota balances,
-- colleague schedules, etc. as they stood at submission time) -- not reliably reconstructable now,
-- so this migration does not attempt it.
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_request
    ADD COLUMN system_note_code varchar(60),
    ADD COLUMN system_note_params jsonb;

COMMENT ON COLUMN hr.leave_request.system_note_code IS
    'Phase A0a: the LeaveRuleCode enum name (e.g. ''SICK_CERTIFICATE_WINDOW'') behind an '
    'AUTO_REJECTED request''s system_note -- see LeaveRuleCode/LeaveRuleOutcome/LeaveService#'
    'autoRejectNote. NULL for an APPROVED request (system_note itself is also NULL in that case) '
    'and for every row created before this migration (NO BACKFILL -- see this migration''s own '
    'top-of-file comment). The stable, frontend-actionable contract; system_note''s Thai sentence is '
    'for DISPLAY only and may be reworded in a future change without code needing to change.';
COMMENT ON COLUMN hr.leave_request.system_note_params IS
    'Phase A0a: the params (a flat string-to-string object, e.g. {"tolerance":"3"}) that rendered '
    'system_note_code into system_note''s Thai sentence -- see LeaveRuleMessages for the param keys '
    'each code uses. NULL under the exact same conditions as system_note_code (APPROVED, or a '
    'pre-V131 row -- NO BACKFILL).';
