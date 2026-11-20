package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.BotHolidayFetchService.FetchOutcome;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms the manual holiday-refresh trigger's role gate against the real controller and the real service —
 * CLAUDE.md "Permission changes must ship evidence", because POST /api/holidays/fetch is a brand-new
 * authorization rule. */
class HolidayControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private HolidayController controller;
    private HolidayRepository holidayRepository;
    private th.co.glr.hr.audit.AuditLogRepository auditLogRepository;

    @BeforeEach
    void wireRealCollaborators() {
        holidayRepository = new HolidayRepository(jdbc);
        auditLogRepository = new th.co.glr.hr.audit.AuditLogRepository(jdbc);
        BotHolidayFetchService fetchService = new BotHolidayFetchService(
            holidayRepository,
            new AppProperties(), // BOT_HOLIDAY_API_TOKEN unset -> blank -> no network call, see class javadoc
            RestClient.builder(),
            new ObjectMapper().registerModule(new JavaTimeModule()));
        HolidayAdminService adminService = new HolidayAdminService(
            holidayRepository,
            new th.co.glr.hr.audit.AuditService(auditLogRepository, new ObjectMapper().registerModule(new JavaTimeModule())));
        controller = new HolidayController(fetchService, adminService, new SessionContext());
    }

    @Test
    void hrCanTriggerAManualFetch() {
        Map<String, List<FetchOutcome>> response = fetchNow(hr());

        assertThat(response).containsKey("outcomes");
        // No token configured -> the service returns immediately with nothing fetched; the point of this assertion is
        // that the call was let through at all, not what it fetched.
        assertThat(response.get("outcomes")).isEmpty();
    }

    @Test
    void aDivisionManagerCannotTriggerAManualFetch() {
        assertForbidden(() -> fetchNow(manager()));
    }

    // --- admin CRUD: happy path ------------------------------------------------

    /** The other half of the same requirement: a subsequent BOT reconcile must not delete the COMPANY row a manual
     * write created, even when that reconcile's fetch does not include that date (which is exactly what would
     * happen to a stray BANK row). */
    @Test
    void aSubsequentBotReconcileDoesNotDeleteTheManuallyCreatedCompanyRow() {
        LocalDate manualDate = LocalDate.of(2027, 4, 1);
        createHoliday(hr(), manualDate, "วันหยุดบริษัท");

        // The reconcile's fetch for 2027 does not include manualDate at all -- exactly the shape that deletes a stale
        // BANK row.
        holidayRepository.reconcileBankHolidaysForYear(2027, List.of(
            new HolidayRepository.BankHoliday(LocalDate.of(2027, 4, 15), "วันหยุดธนาคารอื่น")));

        assertThat(holidayRepository.findByDate(manualDate))
            .as("a COMPANY row must survive a bank reconcile that does not mention its date")
            .isPresent();
        assertThat(holidayRepository.findByDate(manualDate).orElseThrow().source()).isEqualTo("COMPANY");
    }

    // --- helpers --------------------------------------------------------------

    private Map<String, List<FetchOutcome>> fetchNow(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.fetchNow(session);
    }

    private HolidayDto createHoliday(UserPrincipal user, LocalDate date, String nameTh) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.create(new HolidayCreateRequest(date, nameTh), session);
    }

    private static void assertForbidden(ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private interface ThrowingCallable {
        void call();
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(2L, "hr@glr.co.th", "hr", "hr", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee() {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal sales() {
        return new UserPrincipal(4L, "sales@glr.co.th", "sales", "sales", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager() {
        return new UserPrincipal(5L, "mgr@glr.co.th", "mgr", "employee", null, true, LocalDate.now(), false, null, true);
    }
}
