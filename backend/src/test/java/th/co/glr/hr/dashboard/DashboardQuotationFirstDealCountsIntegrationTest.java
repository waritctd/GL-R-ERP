package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Quotation ↔ deal linking, slice 1 (IA §7, owner decision 2026-09-30): the GLA-136 "hide a
 * quotation-first deal" rule is REVERSED. A deal created from {@code /quotations/new}
 * ({@code sales.ticket.quotation_only = TRUE}, V193 — now provenance only) is a real pipeline deal,
 * so every dashboard ticket figure COUNTS it, in every scope. Real SQL against real Postgres: the
 * figures come from {@code DashboardRepository#whereTicketScope}'s WHERE root, which only the
 * database can evaluate.
 *
 * <p>Each scope holds one ordinary draft and one quotation-first draft by the same rep in the same
 * division; the assertion is that BOTH are counted (the GLA-136 version asserted 1).
 */
class DashboardQuotationFirstDealCountsIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);
    private static final OffsetDateTime OVERDUE_BEFORE = OffsetDateTime.parse("2026-09-27T09:00:00+07:00");

    private DashboardRepository repository;
    private long divisionId;
    private long repId;

    @BeforeEach
    void seed() {
        repository = new DashboardRepository(jdbc);
        divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES ('SA136', 'Sales 136') RETURNING division_id
            """, Map.of(), Number.class).longValue();
        repId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES ('EMP-136', 'EMP-136', :divisionId, TRUE) RETURNING employee_id
            """, Map.of("divisionId", divisionId), Number.class).longValue();
        insertDraftTicket("PR-136-PIPE", false);
        insertDraftTicket("PR-136-QFIRST", true);
    }

    @Test
    void allScope_countsTheQuotationFirstDeal() {
        TicketSummaryDto all = repository.tickets(DashboardQueryScope.all(), MONTH_START, OVERDUE_BEFORE);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.draft()).isEqualTo(2);
        assertThat(all.totalOpen()).isEqualTo(2);
    }

    @Test
    void divisionScope_countsTheQuotationFirstDeal() {
        TicketSummaryDto division = repository.tickets(DashboardQueryScope.division(divisionId), MONTH_START,
            OVERDUE_BEFORE);
        assertThat(division.total()).isEqualTo(2);
    }

    @Test
    void selfScope_countsTheQuotationFirstDeal() {
        TicketSummaryDto self = repository.tickets(DashboardQueryScope.self(repId), MONTH_START, OVERDUE_BEFORE);
        assertThat(self.total()).isEqualTo(2);
    }

    /** The provenance flag has no bearing on the figure: clearing it changes nothing. */
    @Test
    void theProvenanceFlag_doesNotChangeTheFigure() {
        jdbc.update("UPDATE sales.ticket SET quotation_only = FALSE", Map.of());
        assertThat(repository.tickets(DashboardQueryScope.all(), MONTH_START, OVERDUE_BEFORE).total()).isEqualTo(2);
    }

    private void insertDraftTicket(String code, boolean quotationOnly) {
        jdbc.update("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, quotation_only)
            VALUES (:code, :code, :by, 'ลูกค้า 136', 'draft', :quotationOnly)
            """, Map.of("code", code, "by", repId, "quotationOnly", quotationOnly));
    }
}
