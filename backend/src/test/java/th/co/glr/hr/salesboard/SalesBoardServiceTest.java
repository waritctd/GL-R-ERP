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
        org.mockito.Mockito.verifyNoInteractions(visits);
    }

    @Test
    void myWork_takesTheCallersOwnVisitsThisWeek_teamTakesTheTeams() {
        UserPrincipal rep = actor(42L, "sales");
        UserPrincipal manager = actor(43L, "sales_manager");
        when(repository.activeDealsOwnedBy(42L)).thenReturn(List.of());
        when(repository.activeDealsAllReps()).thenReturn(List.of());

        service.myWork(rep);
        service.teamOverview(manager);

        verify(visits).ownThisWeek(rep);
        verify(visits).teamThisWeek(manager);
        verify(visits, never()).teamThisWeek(rep);
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

    // ── team board (งานทั้งหมด for sales_manager / ceo) ──────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"sales_manager", "ceo"})
    void teamBoard_givesManagerAndCeoEveryRepsOpenDeals(String role) {
        List<BoardDeal> rows = List.of(deal(1, 10L, 3, "100", false), deal(2, 20L, 30, null, true));
        when(repository.activeDealsAllReps()).thenReturn(rows);

        SalesBoardDtos.TeamBoard board = service.teamBoard(actor(1L, role));

        assertThat(board.board()).containsExactlyElementsOf(rows);
        assertThat(board.quietDays()).isEqualTo(14);
        verify(repository, never()).activeDealsOwnedBy(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "account", "import", "hr", "employee", "warehouse", "qc", "division_manager"})
    void teamBoard_refusesEveryOtherRole_beforeReadingAnyRow(String role) {
        assertForbidden(() -> service.teamBoard(actor(7L, role)));
        org.mockito.Mockito.verifyNoInteractions(repository);
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

    /** Review: a cash-only owner (no open deal, no visit) takes role AND name from the one batched lookup, like every
     * other row — no per-owner findByEmployeeId. */
    @Test
    void teamOverview_cashOnlyOwner_usesTheBatchedLookupForNameAndNeverTheSingleLookup() {
        when(repository.activeDealsAllReps()).thenReturn(List.of());
        when(cash.receivedByDay(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(new CashRepository.ReceivedDayRow(77L, LocalDate.now(), new BigDecimal("500"))));
        when(employees.findByEmployeeIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.Map.of(
            77L, new th.co.glr.hr.auth.EmployeeLoginRecord(77L, "E77", "e77@glr.co.th", "Cash Owner", true, null, null,
                null, null, LocalDate.now(), "x", false, false)));

        TeamOverview team = service.teamOverview(actor(1L, "sales_manager"));

        RepRow row = team.reps().stream().filter(r -> r.employeeId() == 77L).findFirst().orElseThrow();
        assertThat(row.name()).isEqualTo("Cash Owner");
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

    // ── paused deals coming back (owner ruling: a pause may carry a วันที่จะกลับมาดู) ─────────────

    /** The rep's ควรตามต่อ takes their OWN returning paused deals, ranked with the quiet ones; the board stays the
     * ACTIVE deals only, and the team-wide read is never touched. */
    @Test
    void myWork_followUpTakesTheCallersOwnReturningPausedDeals_boardDoesNot() {
        when(repository.activeDealsOwnedBy(1L)).thenReturn(List.of(deal(1, 1L, 20, "1000", true)));
        when(repository.returningPausedDealsOwnedBy(1L)).thenReturn(List.of(returning(9, 1L, 40, "1000")));

        MyWork work = service.myWork(actor(1L, "sales"));

        assertThat(work.followUp()).extracting(BoardDeal::ticketId).containsExactly(9L, 1L);
        assertThat(work.followUp().get(0).returning()).isTrue();
        assertThat(work.board()).extracting(BoardDeal::ticketId).containsExactly(1L);
        verify(repository).returningPausedDealsOwnedBy(1L);
        verify(repository, never()).returningPausedDealsAllReps();
    }

    /** A returning paused deal counts in its owner's quiet figure and the team's stale list exactly as a quiet deal
     * does — and only there: it is not an open deal and adds nothing to the open value. */
    @Test
    void teamOverview_returningPausedDealCountsAsQuiet_notAsOpen() {
        when(repository.activeDealsAllReps()).thenReturn(List.of(deal(1, 10L, 2, "100", false)));
        when(repository.returningPausedDealsAllReps()).thenReturn(List.of(
            returning(8, 10L, 30, "500"), returning(9, 20L, 30, "700")));

        TeamOverview team = service.teamOverview(actor(1L, "sales_manager"));

        RepRow a = team.reps().stream().filter(r -> r.employeeId() == 10L).findFirst().orElseThrow();
        assertThat(a.openDeals()).isEqualTo(1);
        assertThat(a.openValue()).isEqualByComparingTo("100");
        assertThat(a.quietDeals()).isEqualTo(1);
        // An owner whose only deal is a returning paused one still gets a row, with 0 open deals.
        RepRow b = team.reps().stream().filter(r -> r.employeeId() == 20L).findFirst().orElseThrow();
        assertThat(b.openDeals()).isZero();
        assertThat(b.quietDeals()).isEqualTo(1);
        assertThat(team.stale()).extracting(BoardDeal::ticketId).containsExactlyInAnyOrder(8L, 9L);
    }

    /** Fix round 1, ruling 4: returning deals are listed AHEAD of the ranked top five and never cut by the cap, even
     * when they score 0 (no value) against valued quiet deals; the stale list likewise. */
    @Test
    void returningDealsLeadFollowUpAndStale_andAreNeverCutByTheCap() {
        List<BoardDeal> valuedQuiet = IntStream.rangeClosed(1, 7).mapToObj(i -> deal(i, 1L, 20 + i, "100000", true))
            .toList();
        when(repository.activeDealsOwnedBy(1L)).thenReturn(valuedQuiet);
        when(repository.returningPausedDealsOwnedBy(1L)).thenReturn(List.of(returning(90, 1L, 0, null),
            returning(91, 1L, 3, null)));

        MyWork work = service.myWork(actor(1L, "sales"));

        assertThat(work.followUp()).extracting(BoardDeal::ticketId).containsExactly(91L, 90L, 7L, 6L, 5L, 4L, 3L);

        when(repository.activeDealsAllReps()).thenReturn(IntStream.rangeClosed(1, 25)
            .mapToObj(i -> deal(i, 10L, 14 + i, "100000", true)).toList());
        when(repository.returningPausedDealsAllReps()).thenReturn(List.of(returning(99, 10L, 0, null)));
        TeamOverview team = service.teamOverview(actor(2L, "sales_manager"));
        assertThat(team.stale()).hasSize(21);
        assertThat(team.stale().get(0).ticketId()).isEqualTo(99L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");

    /** A paused deal whose วันที่จะกลับมาดู has arrived: not quiet (that rule is ACTIVE-only), returning instead. */
    private static BoardDeal returning(long id, long owner, int daysQuiet, String value) {
        return new BoardDeal(id, "deal " + id, "cust", null, owner, "rep " + owner, "QUOTE_OWNER", "S3", 3,
            value == null ? null : new BigDecimal(value), "THB", false, null, null, null, daysAgo(daysQuiet),
            daysQuiet, false, null, null, true, LocalDate.of(2026, 11, 1));
    }

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
