package th.co.glr.hr.notification;

import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves where an individually-addressed sales-pipeline notification should be emailed.
 *
 * <p>Separate from {@link NotificationRepository} on purpose: that class calls {@link
 * SalesNotificationMailer}, and the router that implements it needs this lookup — routing them
 * through one repository would be a dependency cycle.
 *
 * <p><b>The classification mirrors {@code NotificationRepository#notifyByRoleInternal} exactly</b>
 * ({@code source_code ILIKE 'PCIM%'} for import, and the {@code AC} accounting division for
 * account), so "who counts as import" is the same question for the in-app fan-out and for the mail
 * routing. Two things it deliberately does <b>not</b> do:
 *
 * <ul>
 *   <li><b>No position test, so no CEO branch.</b> {@link CeoApproverRule} narrows CEO notification
 *       to กรรมการผู้จัดการ with no fallback, and the owner's ruling for the sales pipeline is that
 *       the CEO address is <b>hardcoded</b> rather than resolved from any employee record — exactly
 *       so it is immune to CEO approver rows that carry no email. Sales routing therefore never
 *       reads a position, and {@link CeoApproverRule} is untouched for every other surface.</li>
 *   <li><b>No {@code is_active} filter.</b> The employee id arrives from a notification that was
 *       already written for that person (the ticket's creator, the pricing request's requester, the
 *       assigned import staffer); re-deciding here whether they deserve it would let the two
 *       channels disagree. The role fan-out's own {@code is_active = TRUE} filter still applies
 *       upstream, where it belongs.</li>
 * </ul>
 */
@Repository
public class SalesMailRecipientRepository {

    /**
     * Everything the router needs about one individually-addressed recipient.
     *
     * @param routingRole   {@code import}, {@code account}, or {@code sales} for everyone else.
     *                      Never {@code ceo} — see this class's Javadoc
     * @param email         the employee's own address, or {@code null} when there is none on file
     *                      (empty strings normalise to {@code null}, same as {@code
     *                      NotificationRepository#findEmployeeRecipient})
     * @param name          first name only, for the "เรียน คุณ&lt;name&gt;," greeting, or {@code null}
     * @param managerEmail  the address of {@code reports_to_employee_id}, or {@code null} when there
     *                      is no manager or the manager has no address. The fallback, one hop only
     * @param managerName   the manager's first name, for the greeting when the fallback is taken
     */
    public record SalesMailRecipient(String routingRole, String email, String name,
                                     String managerEmail, String managerName) {

        public boolean hasOwnEmail() {
            return email != null && !email.isBlank();
        }

        public boolean hasManagerEmail() {
            return managerEmail != null && !managerEmail.isBlank();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SalesMailRecipientRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return empty only when there is no employee row with that id. An employee with no address
     *     and no manager still comes back present, carrying nulls — deciding what to do about that
     *     (the owner's manager fallback, then log-and-drop) is the router's job, not the query's.
     */
    public Optional<SalesMailRecipient> findRecipient(long employeeId) {
        try {
            SalesMailRecipient recipient = jdbc.queryForObject("""
                SELECT CASE
                         WHEN d.source_code ILIKE 'PCIM%' THEN 'import'
                         WHEN d.source_code ILIKE 'AC%'   THEN 'account'
                         ELSE 'sales'
                       END                                  AS routing_role,
                       NULLIF(BTRIM(e.email), '')           AS email,
                       NULLIF(BTRIM(e.first_name_th), '')   AS name,
                       NULLIF(BTRIM(m.email), '')           AS manager_email,
                       NULLIF(BTRIM(m.first_name_th), '')   AS manager_name
                  FROM hr.employee e
                  LEFT JOIN hr.division d ON d.division_id = e.division_id
                  LEFT JOIN hr.employee m ON m.employee_id = e.reports_to_employee_id
                 WHERE e.employee_id = :employeeId
                """,
                Map.of("employeeId", employeeId),
                (rs, rowNum) -> new SalesMailRecipient(
                    rs.getString("routing_role"),
                    rs.getString("email"),
                    rs.getString("name"),
                    rs.getString("manager_email"),
                    rs.getString("manager_name")));
            return Optional.ofNullable(recipient);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }
}
