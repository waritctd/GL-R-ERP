package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.salesboard.SalesBoardDtos.MyWork;
import th.co.glr.hr.salesboard.SalesBoardDtos.RepRow;
import th.co.glr.hr.salesboard.SalesBoardDtos.TeamOverview;
import th.co.glr.hr.ticket.QuietRule;

/** The role DECISION and the ranking, with the repository mocked. Whether the scope reaches the WHERE clause is
 * SalesBoardScopeIntegrationTest's job — a mocked repository cannot prove it. */
class SalesBoardServiceTest {

    private SalesBoardRepository repository;
    private th.co.glr.hr.auth.EmployeeAuthRepository employees;
    private SalesBoardService service;

    @BeforeEach
    void wire() {
        repository = mock(SalesBoardRepository.class);
        employees = mock(th.co.glr.hr.auth.EmployeeAuthRepository.class);
        service = new SalesBoardService(repository, new QuietRule(null, 14), employees);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "sales_manager", "ceo"})
    void myWork_isScopedToTheCallersOwnDeals(String role) {
        when(repository.activeDealsOwnedBy(42L)).thenReturn(List.of());

        MyWork work = service.myWork(actor(42L, role));

        verify(repository).activeDealsOwnedBy(42L);
        verify(repository, never()).activeDealsAllReps();
        assertThat(work.quietDays()).isEqualTo(14);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "import", "hr", "employee", "warehouse", "qc", "division_manager"})
    void myWork_refusesEveryNonSalesRole(String role) {
        assertForbidden(() -> service.myWork(actor(7L, role)));
        verify(repository, never()).activeDealsOwnedBy(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales_manager", "ceo"})
    void teamOverview_allowsManagerAndCeo(String role) {
        when(repository.activeDealsAllReps()).thenReturn(List.of());

        assertThat(service.teamOverview(actor(1L, role)).quietDays()).isEqualTo(14);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "account", "import", "hr", "employee", "warehouse", "qc", "division_manager"})
    void teamOverview_refusesEveryOtherRole(String role) {
        assertForbidden(() -> service.teamOverview(actor(7L, role)));
        verify(repository, never()).activeDealsAllReps();
    }

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

    @Test
    void teamOverview_oneRowPerRep_withCountsValueAndLatestTouch() {
        when(repository.activeDealsAllReps()).thenReturn(List.of(
            deal(1, 10L, 20, "100", true),
            deal(2, 10L, 2, "50", false),
            deal(3, 20L, 1, null, false)));

        TeamOverview team = service.teamOverview(actor(1L, "sales_manager"));

        assertThat(team.reps()).hasSize(2);
        RepRow a = team.reps().stream().filter(r -> r.employeeId() == 10L).findFirst().orElseThrow();
        assertThat(a.openDeals()).isEqualTo(2);
        assertThat(a.quietDeals()).isEqualTo(1);
        assertThat(a.openValue()).isEqualByComparingTo("150");
        assertThat(a.lastUpdateAt()).isEqualTo(daysAgo(2));
        RepRow b = team.reps().stream().filter(r -> r.employeeId() == 20L).findFirst().orElseThrow();
        assertThat(b.openDeals()).isEqualTo(1);
        assertThat(b.quietDeals()).isZero();
        assertThat(b.openValue()).isEqualByComparingTo("0");
        assertThat(team.stale()).extracting(BoardDeal::ticketId).containsExactly(1L);
    }

    /** Final review: values in different currencies are not comparable, so only THB is weighted into the ranking;
     * a USD 1,000 deal does not outrank a THB 900,000 deal of equal days, and a foreign deal ranks by days alone. */
    @Test
    void quietRanking_weightsOnlyThbValues() {
        when(repository.activeDealsOwnedBy(1L)).thenReturn(List.of(
            deal(1, 1L, 20, "1000", true, "USD"),
            deal(2, 1L, 20, "900000", true, "THB"),
            deal(3, 1L, 25, "5000000", true, "EUR"),
            deal(4, 1L, 20, "10", true, null)));

        MyWork work = service.myWork(actor(1L, "sales"));

        // THB-weighted first (a null currency is THB, value 10 x 20 days < 900000 x 20), then days, then id.
        assertThat(work.followUp()).extracting(BoardDeal::ticketId).containsExactly(2L, 4L, 3L, 1L);
    }

    /** Final review: one batched employee lookup for the reps, never one query per rep. */
    @Test
    void teamOverview_doesNotLookUpEmployeesOneByOne() {
        when(repository.activeDealsAllReps()).thenReturn(List.of(
            deal(1, 10L, 1, "100", false), deal(2, 20L, 1, "100", false), deal(3, 30L, 1, "100", false)));

        service.teamOverview(actor(1L, "sales_manager"));

        verify(employees, never()).findByEmployeeId(anyLong());
    }

    @Test
    void teamOverview_staleIsCappedAtTwenty() {
        when(repository.activeDealsAllReps()).thenReturn(IntStream.rangeClosed(1, 25)
            .mapToObj(i -> deal(i, 10L, 14 + i, null, true)).toList());

        TeamOverview team = service.teamOverview(actor(1L, "ceo"));

        assertThat(team.stale()).hasSize(20);
        assertThat(team.stale().get(0).ticketId()).isEqualTo(25L);
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

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
