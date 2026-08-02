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

/**
 * Confirms the manual holiday-refresh trigger's role gate against the real controller and the
 * real service — CLAUDE.md "Permission changes must ship evidence", because {@code
 * POST /api/holidays/fetch} is a brand-new authorization rule.
 *
 * <p>{@code AppProperties} is constructed with no {@code BOT_HOLIDAY_API_TOKEN} (its default is
 * blank), exactly as the "no TEST_DB_URL/no Docker" note in this repo's CLAUDE.md requires tests to
 * avoid any real network call: with a blank token, {@link BotHolidayFetchService#fetchNow()}
 * returns immediately without ever constructing an HTTP request (mirrors {@code BotFxFetchService}'s
 * own guard, which reads the separate {@code BOT_FX_API_TOKEN} — see {@code AppProperties.Bot}).
 * That means this test exercises the real {@link HolidayController} → real {@link
 * BotHolidayFetchService} role-gate path with zero network traffic and zero writes to {@code
 * hr.holiday} — exactly what is needed to prove the gate itself, without needing a live BOT
 * payload this branch cannot obtain.
 *
 * <p>Every forbidden case asks the question the wrong way round: can a caller who should not reach
 * this endpoint actually reach it.
 */
class HolidayControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private HolidayController controller;

    @BeforeEach
    void wireRealCollaborators() {
        BotHolidayFetchService fetchService = new BotHolidayFetchService(
            new HolidayRepository(jdbc),
            new AppProperties(), // BOT_HOLIDAY_API_TOKEN unset -> blank -> no network call, see class javadoc
            RestClient.builder(),
            new ObjectMapper().registerModule(new JavaTimeModule()));
        controller = new HolidayController(fetchService, new SessionContext());
    }

    @Test
    void hrCanTriggerAManualFetch() {
        Map<String, List<FetchOutcome>> response = fetchNow(hr());

        assertThat(response).containsKey("outcomes");
        // No token configured -> the service returns immediately with nothing fetched; the point
        // of this assertion is that the call was let through at all, not what it fetched.
        assertThat(response.get("outcomes")).isEmpty();
    }

    @Test
    void ceoCanTriggerAManualFetch() {
        Map<String, List<FetchOutcome>> response = fetchNow(ceo());

        assertThat(response).containsKey("outcomes");
    }

    @Test
    void anEmployeeCannotTriggerAManualFetch() {
        assertForbidden(() -> fetchNow(employee()));
    }

    @Test
    void aSalesCallerCannotTriggerAManualFetch() {
        assertForbidden(() -> fetchNow(sales()));
    }

    @Test
    void aDivisionManagerCannotTriggerAManualFetch() {
        assertForbidden(() -> fetchNow(manager()));
    }

    // --- helpers --------------------------------------------------------------

    private Map<String, List<FetchOutcome>> fetchNow(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.fetchNow(session);
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
