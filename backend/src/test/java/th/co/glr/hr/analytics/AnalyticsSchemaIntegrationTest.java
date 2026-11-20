package th.co.glr.hr.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The analytics staging view's deal-value rule (V223). Every row is a synthetic fixture this test inserts itself. */
class AnalyticsSchemaIntegrationTest extends AbstractPostgresIntegrationTest {
    private long repA;

    @BeforeEach
    void fixtures() {
        repA = employee("A001", "Alpha");
    }

    // ---- 3. stg_deal --------------------------------------------------------------------------------------------

    @Test
    void stgDeal_dealValueFollowsTheBoardRule() {
        long t = ticket("D-VAL", "Value rule", repA, "NEGOTIATION", "ACTIVE", null);
        assertThat(deal(t, "deal_value")).isNull();
        jdbc.update("UPDATE sales.ticket SET estimated_value = 500 WHERE ticket_id = :t", Map.of("t", t));
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("500");
        jdbc.update("UPDATE sales.ticket SET imported_value = 700 WHERE ticket_id = :t", Map.of("t", t));
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("700");
        quotation(t, "Q-OLD", "DRAFT", "9999.00");
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("700");
        quotation(t, "Q-APP", "APPROVED", "1000.00");
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("1000");
    }

    private static BigDecimal num(Object o) {
        return (BigDecimal) o;
    }

    private Object deal(long ticketId, String column) {
        return jdbc.queryForMap("SELECT " + column + " AS v FROM analytics.stg_deal WHERE ticket_id = :t",
            Map.of("t", ticketId)).get("v");
    }

    private long employee(String code, String nick) {
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, nickname) VALUES (:c, :n) RETURNING employee_id",
            Map.of("c", code, "n", nick), Long.class);
    }

    private long ticket(String code, String title, long owner, String stage, String lifecycle, Long projectId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, sales_stage, lifecycle, project_id)
            VALUES (:c, :t, :o, :s, :l, :p) RETURNING ticket_id
            """, new MapSqlParameterSource("c", code).addValue("t", title).addValue("o", owner)
            .addValue("s", stage).addValue("l", lifecycle).addValue("p", projectId), Long.class);
    }

    private long quotation(long ticketId, String number, String status, String total) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, total_amount, document_language,
                                         quotation_version)
            VALUES (:t, :n, :i, :s, :a, 'TH',
                    (SELECT count(*) + 1 FROM sales.quotation WHERE ticket_id = :t)) RETURNING quotation_id
            """, new MapSqlParameterSource("t", ticketId).addValue("n", number).addValue("i", repA)
            .addValue("s", status).addValue("a", new BigDecimal(total)), Long.class);
    }
}
