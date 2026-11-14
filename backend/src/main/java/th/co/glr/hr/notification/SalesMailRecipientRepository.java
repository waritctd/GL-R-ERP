package th.co.glr.hr.notification;

import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves where an individually-addressed sales-pipeline notification should be emailed. Sales routing
 * therefore never reads a position, and CeoApproverRule is untouched for every other surface. Two things it
 * deliberately does not do: No position test, so no CEO branch. */
@Repository
public class SalesMailRecipientRepository {

    /** Everything the router needs about one individually-addressed recipient. */
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

    /** @return empty only when there is no employee row with that id. An employee with no address and no manager
     * still comes back present, carrying nulls — deciding what to do about that (the owner's manager fallback,
     * then log-and-drop) is the router's job, not the query's. */
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
