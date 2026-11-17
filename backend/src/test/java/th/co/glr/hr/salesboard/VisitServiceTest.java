package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.VisitDtos.CreateVisitRequest;
import th.co.glr.hr.ticket.TicketService;

/** The role and range DECISIONS, with the repository mocked. Whether the owner scope reaches the WHERE clause is
 * VisitScopeIntegrationTest's job — a mocked repository cannot prove it. */
class VisitServiceTest {

    // 2026-11-12 17:30 UTC = Friday 13 Nov 00:30 in Bangkok: "today" must be the Bangkok date.
    private static final Clock LATE_UTC_THURSDAY = Clock.fixed(Instant.parse("2026-11-12T17:30:00Z"),
        VisitService.BANGKOK);

    private VisitRepository repository;
    private TicketService tickets;
    private VisitService service;

    @BeforeEach
    void wire() {
        repository = mock(VisitRepository.class);
        tickets = mock(TicketService.class);
        service = new VisitService(repository, tickets, LATE_UTC_THURSDAY);
    }

    @Test
    void weekStartIsTheIsoMonday() {
        assertThat(VisitService.weekStart(LocalDate.of(2026, 11, 9))).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(VisitService.weekStart(LocalDate.of(2026, 11, 13))).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(VisitService.weekStart(LocalDate.of(2026, 11, 15))).as("Sunday belongs to the week before")
            .isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void todayIsTheBangkokDate() {
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "import", "hr", "employee", "warehouse"})
    void nonSalesRolesAreRefusedBeforeAnyRead(String role) {
        UserPrincipal actor = actor(role);
        LocalDate d = LocalDate.of(2026, 11, 9);

        assertStatus(() -> service.listOwn(d, d, actor), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.listTeam(d, d, actor), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.create(new CreateVisitRequest(null, "ลูกค้า", d, null, null, false), actor),
            HttpStatus.FORBIDDEN);
        assertStatus(() -> service.markDone(1L, null, actor), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.cancel(1L, actor), HttpStatus.FORBIDDEN);
        verifyNoInteractions(repository, tickets);
    }

    @Test
    void salesIsRefusedTheTeamViewBeforeAnyRead() {
        LocalDate d = LocalDate.of(2026, 11, 9);
        assertStatus(() -> service.listTeam(d, d, actor("sales")), HttpStatus.FORBIDDEN);
        verifyNoInteractions(repository);
    }

    @Test
    void rangeIsAtMost31DaysInclusive() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        service.listOwn(from, from.plusDays(30), actor("sales"));
        verify(repository).findOwn(anyLong(), any(), any());

        assertStatus(() -> service.listOwn(from, from.plusDays(31), actor("sales")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.listOwn(from, from.minusDays(1), actor("sales")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.listTeam(from, from.plusDays(31), actor("ceo")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.listOwn(null, from, actor("sales")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void doneNowNeedsTodaysBangkokDate() {
        UserPrincipal rep = actor("sales");
        assertStatus(() -> service.create(new CreateVisitRequest(null, "ลูกค้า", LocalDate.of(2026, 11, 12), null,
            null, true), rep), HttpStatus.BAD_REQUEST);
    }

    @Test
    void contactNameIsCappedAt200() {
        UserPrincipal rep = actor("sales");
        assertStatus(() -> service.create(new CreateVisitRequest(null, "ก".repeat(201), LocalDate.of(2026, 11, 13),
            null, null, false), rep), HttpStatus.BAD_REQUEST);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private static UserPrincipal actor(String role) {
        return new UserPrincipal(7L, "u@glr.co.th", "ผู้ใช้", role, 7L, true, LocalDate.now(), false, null, false);
    }
}
