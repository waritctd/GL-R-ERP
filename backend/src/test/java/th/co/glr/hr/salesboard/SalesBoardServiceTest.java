package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.salesboard.SalesBoardDtos.MyWork;
import th.co.glr.hr.ticket.QuietRule;

/** The role DECISION and the ranking, with the repository mocked. Whether the scope reaches the WHERE clause is
 * SalesBoardScopeIntegrationTest's job — a mocked repository cannot prove it. */
class SalesBoardServiceTest {

    private SalesBoardRepository repository;
    private th.co.glr.hr.auth.EmployeeAuthRepository employees;
    private SalesBoardService service;
    private VisitService visits;
    private CashRepository cash;

    @BeforeEach
    void wire() {
        repository = mock(SalesBoardRepository.class);
        visits = mock(VisitService.class);
        employees = mock(th.co.glr.hr.auth.EmployeeAuthRepository.class);
        cash = mock(CashRepository.class);
        service = new SalesBoardService(repository, new QuietRule(null, 14), employees, visits, cash);
        // Fix round 1: the due state is added by the repository; here it hands the rows back unchanged.
        when(repository.withPaymentDue(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── team board (งานทั้งหมด for sales_manager / ceo) ──────────────────────────

    @Test
    void followUp_isQuietOnly_rankedByDaysTimesValueThenDays_cappedAtFive() {
        List<BoardDeal> rows = List.of(
            deal(1, 1L, 40, null, true),
            deal(2, 1L, 20, "1000", true),
            deal(3, 1L, 30, "1000", true),
            deal(4, 1L, 3, "999999", false),
            deal(5, 1L, 19, null, true),
            deal(6, 1L, 18, null, true),
            deal(7, 1L, 17, null, true));
        when(repository.activeDealsOwnedBy(1L)).thenReturn(rows);

        MyWork work = service.myWork(actor(1L, "sales"));

        assertThat(work.followUp()).extracting(BoardDeal::ticketId).containsExactly(3L, 2L, 1L, 5L, 6L);
        assertThat(work.board()).hasSize(7);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");

    private static Instant daysAgo(int days) {
        return NOW.minusSeconds(days * 86_400L);
    }

    private static BoardDeal deal(long id, long owner, int daysQuiet, String value, boolean quiet) {
        return deal(id, owner, daysQuiet, value, quiet, "THB");
    }

    private static BoardDeal deal(long id, long owner, int daysQuiet, String value, boolean quiet, String currency) {
        return new BoardDeal(id, "deal " + id, "cust", null, owner, "rep " + owner, "QUOTE_OWNER", "S3", 3,
            value == null ? null : new BigDecimal(value), currency, false, null, null, null, daysAgo(daysQuiet),
            daysQuiet, quiet, null, null);
    }

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, id + "@glr.co.th", "Actor " + id, role, id, true, LocalDate.now(), false, null,
            false);
    }

}
