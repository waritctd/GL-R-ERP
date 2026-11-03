package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import th.co.glr.hr.notification.SalesNotificationMailer;

/**
 * Test double for {@link SalesNotificationMailer} that RECORDS what {@code NotificationRepository}
 * handed it, instead of {@link SalesNotificationMailer#NO_OP}'s silence. Wiring this (not NO_OP) is
 * what lets a test say "the EMAIL path was invoked for the same recipient with the same
 * title/message/link as the bell row" — a regression to an {@code *InAppOnly} method, or a missing
 * {@code notify*} call, leaves {@link #mails} empty and the assertion fails.
 *
 * <p>The assertion helpers read {@code hr.notification} (the bell) and {@link #mails} (the email
 * path) together so every test pins both channels, for the same recipient, in one call.
 */
public final class RecordingSalesMailer implements SalesNotificationMailer {

    /** One call into the mailer. {@code role} is set for a role fan-out, {@code employeeId} for a single employee. */
    public record Mail(String role, List<Long> notifiedEmployeeIds, Long employeeId,
                       String title, String message, String link) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final List<Mail> mails = new ArrayList<>();
    private int seeded;

    public RecordingSalesMailer(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void emailForRole(String role, List<Long> notifiedEmployeeIds, String title, String message, String link) {
        mails.add(new Mail(role, List.copyOf(notifiedEmployeeIds), null, title, message, link));
    }

    @Override
    public void emailForEmployee(long employeeId, String title, String message, String link) {
        mails.add(new Mail(null, List.of(), employeeId, title, message, link));
    }

    public List<Mail> mails() {
        return mails;
    }

    // ── recipients ───────────────────────────────────────────────────────────────────────────

    /**
     * Inserts ONE active employee that {@code NotificationRepository#notifyByRoleAtLink(role, …)}
     * fans out to: {@code import} = division PCIM, {@code account} = division AC (exact match, as
     * the production predicate), {@code ceo} = a กรรมการผู้จัดการ position. Returns its employee id.
     */
    public long seedRoleEmployee(String role) {
        seeded++;
        Long divisionId = null;
        Long positionId = null;
        if ("ceo".equals(role)) {
            List<Long> existing = jdbc.queryForList(
                "SELECT position_id FROM hr.position WHERE name_th = 'กรรมการผู้จัดการ' LIMIT 1", Map.of(), Long.class);
            positionId = existing.isEmpty()
                ? jdbc.queryForObject("""
                    INSERT INTO hr.position (source_code, name_th, is_active) VALUES (:code, 'กรรมการผู้จัดการ', TRUE)
                    RETURNING position_id
                    """, Map.of("code", "RSM" + seeded), Long.class)
                : existing.get(0);
        } else {
            String code = "import".equals(role) ? "PCIM" : "AC";
            divisionId = jdbc.queryForObject("""
                INSERT INTO hr.division (source_code, name_th) VALUES (:code, :name)
                ON CONFLICT (source_code) DO UPDATE SET name_th = hr.division.name_th
                RETURNING division_id
                """, Map.of("code", code, "name", code + " division"), Long.class);
        }
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, position_id, is_active)
            VALUES (:code, :name, :email, :divisionId, :positionId, TRUE)
            RETURNING employee_id
            """, new MapSqlParameterSource()
                .addValue("code", "RSM-" + role + "-" + seeded)
                .addValue("name", "ผู้รับ " + role)
                .addValue("email", "rsm-" + role + "-" + seeded + "@test.glr")
                .addValue("divisionId", divisionId)
                .addValue("positionId", positionId),
            Long.class);
    }

    // ── assertions ───────────────────────────────────────────────────────────────────────────

    /**
     * A role fan-out reached EXACTLY {@code recipients} on the bell AND on the email path: one
     * {@code hr.notification} row per recipient and exactly one {@code emailForRole} call, all with
     * the same type/title/message/link.
     */
    public void assertRoleNotice(String role, String type, String title, String message, String link,
                                 long... recipients) {
        assertRoleNotices(role, type, title, link, List.of(message), recipients);
    }

    /** {@link #assertRoleNotice} for several notices of one type (e.g. one per IR): each message once per recipient. */
    public void assertRoleNotices(String role, String type, String title, String link, List<String> messages,
                                  long... recipients) {
        Set<Long> expected = LongStream.of(recipients).boxed().collect(Collectors.toSet());
        List<Map<String, Object>> rows = bellRows(type);
        assertThat(rows).as("bell rows of type %s", type).hasSize(expected.size() * messages.size());
        assertThat(rows.stream().map(r -> ((Number) r.get("employee_id")).longValue()).collect(Collectors.toSet()))
            .as("bell recipients of %s", type).isEqualTo(expected);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("title")).as("bell title").isEqualTo(title);
            assertThat(r.get("link")).as("bell link").isEqualTo(link);
        });
        assertThat(rows.stream().map(r -> (String) r.get("message")).distinct().toList())
            .as("bell messages").containsExactlyInAnyOrderElementsOf(messages);
        List<Mail> sent = mailsTitled(title);
        assertThat(sent).as("emails titled '%s' (role %s)", title, role).hasSize(messages.size());
        assertThat(sent).allSatisfy(m -> {
            assertThat(m.role()).as("email role").isEqualTo(role);
            assertThat(Set.copyOf(m.notifiedEmployeeIds())).as("email's notified ids").isEqualTo(expected);
            assertThat(m.link()).as("email link").isEqualTo(link);
        });
        assertThat(sent.stream().map(Mail::message).toList()).as("email messages")
            .containsExactlyInAnyOrderElementsOf(messages);
    }

    /** A single-employee notice reached {@code employeeId} on the bell AND the email path, exactly once each. */
    public void assertEmployeeNotice(long employeeId, String type, String title, String message, String link) {
        List<Map<String, Object>> rows = bellRows(type);
        assertThat(rows).as("bell rows of type %s", type).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("employee_id")).longValue()).as("bell recipient").isEqualTo(employeeId);
        assertThat(row.get("title")).as("bell title").isEqualTo(title);
        assertThat(row.get("message")).as("bell message").isEqualTo(message);
        assertThat(row.get("link")).as("bell link").isEqualTo(link);
        List<Mail> sent = mailsTitled(title);
        assertThat(sent).as("emails titled '%s'", title).hasSize(1);
        Mail mail = sent.get(0);
        assertThat(mail.employeeId()).as("email recipient").isEqualTo(employeeId);
        assertThat(mail.message()).as("email message").isEqualTo(message);
        assertThat(mail.link()).as("email link").isEqualTo(link);
    }

    /** Neither a bell row of {@code type} nor an email titled {@code title} exists. */
    public void assertNoNotice(String type, String title) {
        assertThat(bellRows(type)).as("bell rows of type %s", type).isEmpty();
        assertThat(mailsTitled(title)).as("emails titled '%s'", title).isEmpty();
    }

    private List<Map<String, Object>> bellRows(String type) {
        return jdbc.queryForList(
            "SELECT employee_id, title, message, link FROM hr.notification WHERE type = :type ORDER BY notification_id",
            Map.of("type", type));
    }

    private List<Mail> mailsTitled(String title) {
        return mails.stream().filter(m -> title.equals(m.title())).toList();
    }
}
