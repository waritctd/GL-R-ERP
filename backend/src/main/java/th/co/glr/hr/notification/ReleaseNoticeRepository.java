package th.co.glr.hr.notification;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The idempotency reads for the release notice, all over EXISTING tables (no migration):
 *
 * <ul>
 *   <li>Published = the employee already has a {@code RELEASE_NOTE} row in {@code hr.notification} for this
 *       release's link. Read or unread, it is never re-inserted.</li>
 *   <li>Emailed = append-only {@code hr.audit_log} rows (DB-level immutability trigger, V18). Per employee and
 *       release there is an ATTEMPT row ({@code RELEASE_NOTE_EMAILED}, written in the email transaction before the
 *       mail is handed to the sender) and, once the provider has answered, an OUTCOME row
 *       ({@code RELEASE_NOTE_EMAIL_SENT} or {@code RELEASE_NOTE_EMAIL_FAILED}) carrying the same {@code attempt}
 *       token. See {@link #emailStates} for how they combine.</li>
 * </ul>
 */
@Repository
public class ReleaseNoticeRepository {
    /** The ATTEMPT row. The name predates outcome rows (2026-11-16) and is kept so production's existing rows and
     * the operator reset SQL keep their meaning; it records "handed to the sender", NOT "delivered". */
    static final String EMAILED_ACTION = "RELEASE_NOTE_EMAILED";
    static final String EMAILED_ENTITY = "employee";
    /** OUTCOME rows, appended by ReleaseNoticeDelivery after the provider call. */
    static final String SENT_ACTION = "RELEASE_NOTE_EMAIL_SENT";
    static final String FAILED_ACTION = "RELEASE_NOTE_EMAIL_FAILED";
    /**
     * Operator-inserted marker that voids every earlier attempt AND outcome row of one release, so a run can be
     * sent again from scratch. hr.audit_log is append-only (trigger audit_log_no_mutate), so those rows cannot be
     * deleted; a later row that supersedes them is the only honest undo. Ordered by id, not {@code at}: {@code at}
     * is the transaction start, so a reset and a send in the same instant would otherwise tie.
     *
     * <p>Since outcome rows exist (2026-11-17) a FAILED send is retried by the app itself, and a send with no
     * outcome can be re-sent from the app with an explicit opt-in, so this marker is only needed to re-send to
     * people the app records as SENT. It is the tool for the 16 Nov run (28 attempt rows from before outcomes were
     * recorded, none delivered), which the app now reports as "ไม่ทราบผล" rather than sent.
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
     * <p><b>All-or-nothing.</b> A reset voids EVERY earlier row of that release, sent ones included: after it, the
     * people whose mail did arrive are mailed again. Use it only for a run where nothing was delivered.
     *
     * <p><b>Wait for the run to finish first.</b> The lock above only covers the transaction that writes the
     * attempt rows. The mails themselves are sent AFTER that commit, when the lock is already released. Before
     * inserting, confirm in the app that nobody is still "กำลังส่ง" for the release.
     */
    static final String EMAIL_RESET_ACTION = "RELEASE_NOTE_EMAIL_RESET";
    static final String EMAIL_RESET_ENTITY = "release_note";

    /** "RELN". Two-int advisory-lock keyspace, separate from every other locker in the app. */
    static final int LOCK_CLASS_ID = 0x52454C4E;

    /** Where one employee's email stands for one release. No entry = never attempted (or reset since). */
    public enum EmailState { SENT, FAILED, SENDING, UNCONFIRMED }

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

    /**
     * Each employee's LATEST attempt for the release (after the latest reset marker), and the outcome recorded for
     * THAT attempt (matched by token, so a late outcome of an older attempt cannot answer for a newer one):
     * <ul>
     *   <li>outcome SENT → {@code SENT}; outcome FAILED → {@code FAILED};</li>
     *   <li>no outcome, attempt younger than {@code inFlightWindow} → {@code SENDING} (the sender may still be
     *       working on it);</li>
     *   <li>no outcome, older → {@code UNCONFIRMED}: the process died, the outcome could not be written, or the row
     *       predates outcome rows (no token at all). Deliberately not SENT and not FAILED: it may or may not have
     *       arrived.</li>
     * </ul>
     * Age is measured on the database clock ({@code now()} against {@code at}), never mixed with the JVM's.
     */
    public Map<Long, EmailState> emailStates(String releaseId, Duration inFlightWindow) {
        Map<Long, EmailState> states = new HashMap<>();
        jdbc.query("""
            WITH reset AS (
                SELECT COALESCE(MAX(r.id), 0) AS id
                  FROM hr.audit_log r
                 WHERE r.action = :resetAction
                   AND r.entity = :resetEntity
                   AND r.after_json ->> 'releaseId' = :releaseId
            ),
            latest AS (
                SELECT DISTINCT ON (a.entity_id) a.entity_id, a.at, a.after_json ->> 'attempt' AS attempt
                  FROM hr.audit_log a, reset
                 WHERE a.action = :attemptAction
                   AND a.entity = :entity
                   AND a.entity_id IS NOT NULL
                   AND a.after_json ->> 'releaseId' = :releaseId
                   AND a.id > reset.id
                 ORDER BY a.entity_id, a.id DESC
            )
            SELECT latest.entity_id,
                   (SELECT o.action
                      FROM hr.audit_log o
                     WHERE o.action IN (:sentAction, :failedAction)
                       AND o.entity = :entity
                       AND o.entity_id = latest.entity_id
                       AND o.after_json ->> 'releaseId' = :releaseId
                       AND o.after_json ->> 'attempt' = latest.attempt
                     ORDER BY o.id DESC
                     LIMIT 1) AS outcome,
                   latest.at > now() - make_interval(secs => :windowSeconds) AS in_flight
              FROM latest
            """, new MapSqlParameterSource()
                .addValue("resetAction", EMAIL_RESET_ACTION)
                .addValue("resetEntity", EMAIL_RESET_ENTITY)
                .addValue("attemptAction", EMAILED_ACTION)
                .addValue("sentAction", SENT_ACTION)
                .addValue("failedAction", FAILED_ACTION)
                .addValue("entity", EMAILED_ENTITY)
                .addValue("releaseId", releaseId)
                .addValue("windowSeconds", (double) inFlightWindow.toSeconds()),
            rs -> {
                String outcome = rs.getString("outcome");
                EmailState state;
                if (SENT_ACTION.equals(outcome)) {
                    state = EmailState.SENT;
                } else if (FAILED_ACTION.equals(outcome)) {
                    state = EmailState.FAILED;
                } else if (rs.getBoolean("in_flight")) {
                    state = EmailState.SENDING;
                } else {
                    state = EmailState.UNCONFIRMED;
                }
                states.put(rs.getLong("entity_id"), state);
            });
        return states;
    }
}
