package th.co.glr.hr.notification;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The two idempotency reads for the release notice, both over EXISTING tables (no migration):
 *
 * <ul>
 *   <li>Published = the employee already has a {@code RELEASE_NOTE} row in {@code hr.notification} for this
 *       release's link. Read or unread, it is never re-inserted.</li>
 *   <li>Emailed = an append-only {@code hr.audit_log} row {@code RELEASE_NOTE_EMAILED} for that employee and
 *       release id. The audit log is the record of who broadcast what to whom, and its DB-level immutability
 *       trigger (V18) means a "sent" mark can never be cleared, which is the property that keeps a second call
 *       from double-sending.</li>
 * </ul>
 */
@Repository
public class ReleaseNoticeRepository {
    static final String EMAILED_ACTION = "RELEASE_NOTE_EMAILED";
    static final String EMAILED_ENTITY = "employee";
    /**
     * Operator-inserted marker that voids every earlier {@code RELEASE_NOTE_EMAILED} row of one release, so a run
     * whose sends all failed (2026-11-16: invalid Resend key, 28 rows recorded, nothing delivered) can be sent
     * again. hr.audit_log is append-only (trigger audit_log_no_mutate), so those rows cannot be deleted; a later
     * row that supersedes them is the only honest undo. Ordered by id, not {@code at}: {@code at} is the
     * transaction start, so a reset and a send in the same instant would otherwise tie.
     *
     * <p><b>Operator runbook (exact SQL).</b> {@code hr.audit_log.id} is assigned at INSERT, not at commit, so the
     * marker must be inserted while holding the SAME advisory lock the email path holds, in the same transaction;
     * otherwise a marker can land in the middle of an in-flight run and split its rows around it (double send or
     * a missed send). The class id below is the decimal value of {@code 0x52454C4E} and must match
     * {@link #LOCK_CLASS_ID} (a test pins it). Replace {@code <releaseId>} (e.g. {@code 2026-11-16}) in both places:
     * <pre>{@code
     * BEGIN;
     * SELECT pg_advisory_xact_lock(1380273230, hashtext('email:<releaseId>'));
     * INSERT INTO hr.audit_log (actor_email, action, entity, after_json)
     * VALUES ('<operator email>', 'RELEASE_NOTE_EMAIL_RESET', 'release_note',
     *         jsonb_build_object('releaseId', '<releaseId>'));
     * COMMIT;
     * }</pre>
     * A marker with a different entity, or an {@code after_json} that is null or lacks {@code releaseId}, is
     * silently ignored (fails closed, no error), so check the next audience count after inserting it.
     *
     * <p><b>All-or-nothing.</b> A reset voids EVERY earlier emailed row of that release. After a PARTIAL failure
     * the employees whose mail did arrive are mailed again. Use it only for a run where nothing was delivered.
     *
     * <p><b>Wait for the run to finish first.</b> The lock above only covers the transaction that writes the
     * emailed rows. The mails themselves are handed to the async sender AFTER that commit, when the lock is
     * already released. A marker inserted while those sends are still in flight voids rows whose mail then
     * arrives, and the next click sends a second copy. Before inserting, confirm in the backend log that every
     * send of the run has either succeeded or logged "Failed to send notification email".
     */
    static final String EMAIL_RESET_ACTION = "RELEASE_NOTE_EMAIL_RESET";
    static final String EMAIL_RESET_ENTITY = "release_note";

    /** "RELN". Two-int advisory-lock keyspace, separate from every other locker in the app. */
    static final int LOCK_CLASS_ID = 0x52454C4E;

    private final NamedParameterJdbcTemplate jdbc;

    public ReleaseNoticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes concurrent publish (or email) calls for one release, so two clicks cannot both see "not yet
     * done" and both insert/send. Held until the surrounding transaction ends. */
    public void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(:classId, hashtext(:key))",
            new MapSqlParameterSource().addValue("classId", LOCK_CLASS_ID).addValue("key", key),
            (rs, rowNum) -> 0);
    }

    public Set<Long> notifiedEmployeeIds(String link) {
        return new HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT employee_id
              FROM hr.notification
             WHERE type = :type AND link = :link
            """, Map.of("type", ReleaseNoticePolicy.NOTIFICATION_TYPE, "link", link), Long.class));
    }

    public Set<Long> emailedEmployeeIds(String releaseId) {
        return new HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT entity_id
              FROM hr.audit_log
             WHERE action = :action
               AND entity = :entity
               AND entity_id IS NOT NULL
               AND after_json ->> 'releaseId' = :releaseId
               AND id > COALESCE((
                       SELECT MAX(reset.id)
                         FROM hr.audit_log reset
                        WHERE reset.action = :resetAction
                          AND reset.entity = :resetEntity
                          AND reset.after_json ->> 'releaseId' = :releaseId), 0)
            """, Map.of("action", EMAILED_ACTION, "entity", EMAILED_ENTITY, "releaseId", releaseId,
                "resetAction", EMAIL_RESET_ACTION, "resetEntity", EMAIL_RESET_ENTITY), Long.class));
    }
}
