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
    /** Appended by ReleaseNoticeDelivery, under the email lock, just before the provider call for one person. The
     * in-flight window runs from this row, not from the batch's attempt rows (fix round 1, review MEDIUM-3). */
    static final String STARTED_ACTION = "RELEASE_NOTE_EMAIL_STARTED";
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
     * <p><b>Never insert a reset marker while a send is in progress.</b> Check first, in the app (the release's
     * "ผลการส่งอีเมล" panel) or in SQL, that no attempt of the release is in the sending state ("กำลังส่ง" = 0). The
     * lock above only covers the transaction that writes attempt rows; the mails are sent after it, one at a time.
     * A person whose send has already STARTED when the marker lands is not protected: their outcome row is voided
     * by the marker, they read as not emailed, and the next press mails them again (the adversarial review showed
     * 12 mails to 6 people this way). People still waiting in the queue are protected: the sender re-checks under
     * the same lock that their attempt is still the latest one after any reset, and skips them otherwise.
     *
     * <p>The reset lookup voids exactly the rows of THAT release with a smaller id than the latest marker (attempt,
     * start and outcome rows alike, since start and outcome rows are only read through their attempt's token). It
     * ignores nothing else: other releases, other actions and other entities are untouched.
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

    /** The token of the person's latest attempt for the release after the latest reset; null when there is none (or
     * the latest is a pre-token row). The sender reads it under the email lock to skip a superseded attempt. */
    public String latestAttempt(String releaseId, long employeeId) {
        java.util.List<String> tokens = jdbc.queryForList("""
            SELECT a.after_json ->> 'attempt'
              FROM hr.audit_log a
             WHERE a.action = :attemptAction
               AND a.entity = :entity
               AND a.entity_id = :employeeId
               AND a.after_json ->> 'releaseId' = :releaseId
               AND a.id > COALESCE((
                       SELECT MAX(r.id)
                         FROM hr.audit_log r
                        WHERE r.action = :resetAction
                          AND r.entity = :resetEntity
                          AND r.after_json ->> 'releaseId' = :releaseId), 0)
             ORDER BY a.id DESC
             LIMIT 1
            """, new MapSqlParameterSource()
                .addValue("attemptAction", EMAILED_ACTION)
                .addValue("entity", EMAILED_ENTITY)
                .addValue("employeeId", employeeId)
                .addValue("releaseId", releaseId)
                .addValue("resetAction", EMAIL_RESET_ACTION)
                .addValue("resetEntity", EMAIL_RESET_ENTITY), String.class);
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    /**
     * Each employee's LATEST attempt for the release (after the latest reset marker), with the start and outcome
     * recorded for THAT attempt (matched by token, so a late row of an older attempt cannot answer for a newer one):
     * <ul>
     *   <li>outcome SENT → {@code SENT}; outcome FAILED → {@code FAILED};</li>
     *   <li>started, no outcome: started less than {@code inFlightWindow} ago → {@code SENDING}; older →
     *       {@code UNCONFIRMED} (the provider may or may not have accepted it);</li>
     *   <li>not started: attempted less than {@code inFlightWindow} ago → {@code SENDING} (still in the queue);
     *       older → {@code FAILED}, because no provider call was ever made — the start row is committed BEFORE the
     *       call, so its absence proves nothing left. Should that queued attempt still reach the sender later, the
     *       sender skips it if a newer attempt was made meanwhile (ReleaseNoticeDelivery);</li>
     *   <li>a row from before attempt tokens (no token, so no start or outcome can match it): younger than the window
     *       {@code SENDING}, older {@code UNCONFIRMED} — it may have been delivered (production's 16 Nov rows).</li>
     * </ul>
     * Every age is measured on the database clock ({@code now()} against {@code at}), never mixed with the JVM's.
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
            ),
            tied AS (
                SELECT latest.entity_id,
                       latest.attempt,
                       latest.at,
                       (SELECT o.action
                          FROM hr.audit_log o
                         WHERE o.action IN (:sentAction, :failedAction)
                           AND o.entity = :entity
                           AND o.entity_id = latest.entity_id
                           AND o.after_json ->> 'releaseId' = :releaseId
                           AND o.after_json ->> 'attempt' = latest.attempt
                         ORDER BY o.id DESC
                         LIMIT 1) AS outcome,
                       (SELECT MAX(s.at)
                          FROM hr.audit_log s
                         WHERE s.action = :startedAction
                           AND s.entity = :entity
                           AND s.entity_id = latest.entity_id
                           AND s.after_json ->> 'releaseId' = :releaseId
                           AND s.after_json ->> 'attempt' = latest.attempt) AS started_at
                  FROM latest
            )
            SELECT entity_id,
                   outcome,
                   attempt IS NULL AS legacy,
                   started_at IS NOT NULL AS started,
                   COALESCE(started_at, at) > now() - make_interval(secs => :windowSeconds) AS in_flight
              FROM tied
            """, new MapSqlParameterSource()
                .addValue("resetAction", EMAIL_RESET_ACTION)
                .addValue("resetEntity", EMAIL_RESET_ENTITY)
                .addValue("attemptAction", EMAILED_ACTION)
                .addValue("sentAction", SENT_ACTION)
                .addValue("failedAction", FAILED_ACTION)
                .addValue("startedAction", STARTED_ACTION)
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
                } else if (rs.getBoolean("started") || rs.getBoolean("legacy")) {
                    state = EmailState.UNCONFIRMED;
                } else {
                    state = EmailState.FAILED;
                }
                states.put(rs.getLong("entity_id"), state);
            });
        return states;
    }
}
