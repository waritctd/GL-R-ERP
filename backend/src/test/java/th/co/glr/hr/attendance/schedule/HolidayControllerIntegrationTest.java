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

    // --- admin CRUD: happy path ------------------------------------------------

    @Test
    void hrCanCreateAndItLandsAsCompanySourced() {
        LocalDate date = LocalDate.of(2027, 1, 4);
        HolidayDto created = createHoliday(hr(), date, "วันหยุดพิเศษทดสอบ");

        assertThat(created.holidayDate()).isEqualTo(date);
        assertThat(created.nameTh()).isEqualTo("วันหยุดพิเศษทดสอบ");
        assertThat(created.source()).isEqualTo("COMPANY");
    }

    @Test
    void ceoCanUpdateAndDelete() {
        LocalDate date = LocalDate.of(2027, 1, 5);
        createHoliday(ceo(), date, "เดิม");

        HolidayDto updated = updateHoliday(ceo(), date, "แก้ไขแล้ว");
        assertThat(updated.nameTh()).isEqualTo("แก้ไขแล้ว");
        assertThat(updated.source()).isEqualTo("COMPANY");

        deleteHoliday(ceo(), date);
        assertThat(holidayRepository.findByDate(date)).isEmpty();
    }

    @Test
    void listReturnsWhatWasCreated() {
        LocalDate date = LocalDate.of(2027, 2, 1);
        createHoliday(hr(), date, "ทดสอบรายการ");

        List<HolidayDto> found = listHolidays(hr(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        assertThat(found).extracting(HolidayDto::holidayDate).contains(date);
    }

    /**
     * The core requirement CLAUDE.md calls out: a manual write can never produce a {@code BANK} row.
     * Asserted on ONE fixture together with the opposite (a BANK row inserted directly by the
     * reconcile path) so the assertion is not vacuous — see the class javadoc's "wrong way round"
     * discipline and the repo's "assert both sides" testing rule.
     */
    @Test
    void aManualCreateIsAlwaysCompanySourcedNeverBank() {
        LocalDate manualDate = LocalDate.of(2027, 3, 1);
        LocalDate bankDate = LocalDate.of(2027, 3, 2);

        createHoliday(hr(), manualDate, "สร้างโดยแอดมิน");
        holidayRepository.reconcileBankHolidaysForYear(2027, List.of(
            new HolidayRepository.BankHoliday(bankDate, "จากธนาคาร")));

        assertThat(holidayRepository.findByDate(manualDate).orElseThrow().source())
            .as("a manual create must never be able to produce a BANK row")
            .isEqualTo("COMPANY");
        assertThat(holidayRepository.findByDate(bankDate).orElseThrow().source())
            .as("the opposite case on the SAME fixture: a real bank-sourced row is actually BANK")
            .isEqualTo("BANK");
    }

    /**
     * The other half of the same requirement: a subsequent BOT reconcile must not delete the
     * COMPANY row a manual write created, even when that reconcile's fetch does not include that
     * date (which is exactly what would happen to a stray BANK row).
     */
    @Test
    void aSubsequentBotReconcileDoesNotDeleteTheManuallyCreatedCompanyRow() {
        LocalDate manualDate = LocalDate.of(2027, 4, 1);
        createHoliday(hr(), manualDate, "วันหยุดบริษัท");

        // The reconcile's fetch for 2027 does not include manualDate at all -- exactly the shape
        // that deletes a stale BANK row.
        holidayRepository.reconcileBankHolidaysForYear(2027, List.of(
            new HolidayRepository.BankHoliday(LocalDate.of(2027, 4, 15), "วันหยุดธนาคารอื่น")));

        assertThat(holidayRepository.findByDate(manualDate))
            .as("a COMPANY row must survive a bank reconcile that does not mention its date")
            .isPresent();
        assertThat(holidayRepository.findByDate(manualDate).orElseThrow().source()).isEqualTo("COMPANY");
    }

    @Test
    void editingABankSourcedRowFlipsItToCompanyAndItThenSurvivesReconcile() {
        LocalDate date = LocalDate.of(2027, 5, 1);
        jdbc.update("INSERT INTO hr.holiday (holiday_date, name_th, source) VALUES (:date, :name, 'BANK')",
            Map.of("date", date, "name", "จากธนาคาร (เดิม)"));

        HolidayDto edited = updateHoliday(hr(), date, "แก้ไขโดย HR");
        assertThat(edited.source()).isEqualTo("COMPANY");

        // Next reconcile does not mention this date -> would delete it if it were still BANK.
        holidayRepository.reconcileBankHolidaysForYear(2027, List.of());
        assertThat(holidayRepository.findByDate(date).orElseThrow().source()).isEqualTo("COMPANY");
    }

    @Test
    void createRejectsADuplicateDate() {
        LocalDate date = LocalDate.of(2027, 6, 1);
        createHoliday(hr(), date, "รายการแรก");

        assertThatThrownBy(() -> createHoliday(hr(), date, "รายการซ้ำ"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createRejectsABlankName() {
        assertThatThrownBy(() -> createHoliday(hr(), LocalDate.of(2027, 6, 5), "   "))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * V129 widened {@code hr.holiday.name_th} to {@code TEXT}, removing the database's own length
     * guard (production BOT names have measured 137 characters for a real substitution-day + full
     * royal title entry). {@link HolidayAdminService#MAX_NAME_LENGTH} (500) replaces that guard at
     * the application layer.
     *
     * <p>Both sides asserted on ONE fixture, per CLAUDE.md's testing rule: a name at the real
     * measured length (137 chars, well under the 500 bound) must be accepted, while a name past the
     * bound must be rejected with 400 and never reach the database. A one-sided test (only the
     * rejection) would also pass if the bound were set absurdly low and rejected legitimate BOT
     * names too — which is exactly the regression this bound must not reintroduce.
     */
    @Test
    void aRealisticallyLongNameIsAcceptedAndAnAbsurdlyLongOneIsRejected() {
        // Mirrors the measured longest real BOT holiday name (137 chars: substitution-day prefix +
        // King Rama X's full royal title + special-holiday designation) -- built programmatically to
        // hit exactly that length rather than hand-counting Thai characters.
        String realisticName = ("วันหยุดชดเชยวันเฉลิมพระชนมพรรษาพระบาทสมเด็จพระปรเมนทรรามาธิบดี"
            + "ศรีสินทรมหาวชิราลงกรณ พระวชิรเกล้าเจ้าอยู่หัว (วันหยุดพิเศษเพิ่มเติม)"
            + "ก".repeat(200)).substring(0, 137);
        assertThat(realisticName).hasSize(137);
        LocalDate acceptedDate = LocalDate.of(2027, 10, 1);

        HolidayDto accepted = createHoliday(hr(), acceptedDate, realisticName);
        assertThat(accepted.nameTh()).isEqualTo(realisticName);

        String absurdlyLongName = "ก".repeat(HolidayAdminService.MAX_NAME_LENGTH + 1);
        LocalDate rejectedDate = LocalDate.of(2027, 10, 2);

        assertThatThrownBy(() -> createHoliday(hr(), rejectedDate, absurdlyLongName))
            .as("a name past MAX_NAME_LENGTH must be rejected with 400, on the SAME fixture that "
                + "just proved a realistic 137-char name is accepted")
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(holidayRepository.findByDate(rejectedDate))
            .as("the rejected write must never reach the database")
            .isEmpty();
    }

    /** Same bound, update path -- {@code requireValidName} is shared between create and update. */
    @Test
    void updateAlsoEnforcesTheNameLengthBound() {
        LocalDate date = LocalDate.of(2027, 10, 3);
        createHoliday(hr(), date, "เดิม");
        String absurdlyLongName = "ข".repeat(HolidayAdminService.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> updateHoliday(hr(), date, absurdlyLongName))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(holidayRepository.findByDate(date).orElseThrow().nameTh())
            .as("a rejected update must not have overwritten the existing name")
            .isEqualTo("เดิม");
    }

    @Test
    void updateOnAMissingDateIs404() {
        assertThatThrownBy(() -> updateHoliday(hr(), LocalDate.of(2027, 9, 9), "ไม่มีอยู่จริง"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createWritesAnAuditRow() {
        LocalDate date = LocalDate.of(2027, 7, 1);
        createHoliday(hr(), date, "ตรวจสอบ audit");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT action, entity FROM hr.audit_log WHERE action = 'CREATE_HOLIDAY' ORDER BY id DESC LIMIT 1",
            Map.of());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("entity", "holiday");
    }

    // --- admin CRUD: wrong-way-round authz --------------------------------------

    @Test
    void anEmployeeCannotListHolidays() {
        assertForbidden(() -> listHolidays(employee(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
    }

    @Test
    void aSalesCallerCannotListHolidays() {
        assertForbidden(() -> listHolidays(sales(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
    }

    @Test
    void aDivisionManagerCannotListHolidays() {
        assertForbidden(() -> listHolidays(manager(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
    }

    @Test
    void anEmployeeCannotCreateAHoliday() {
        assertForbidden(() -> createHoliday(employee(), LocalDate.of(2027, 8, 1), "ทดสอบ"));
    }

    @Test
    void aSalesCallerCannotCreateAHoliday() {
        assertForbidden(() -> createHoliday(sales(), LocalDate.of(2027, 8, 2), "ทดสอบ"));
    }

    @Test
    void aDivisionManagerCannotCreateAHoliday() {
        assertForbidden(() -> createHoliday(manager(), LocalDate.of(2027, 8, 3), "ทดสอบ"));
    }

    @Test
    void anEmployeeCannotUpdateAHoliday() {
        LocalDate date = LocalDate.of(2027, 8, 10);
        createHoliday(hr(), date, "เดิม");
        assertForbidden(() -> updateHoliday(employee(), date, "แก้ไข"));
    }

    @Test
    void aDivisionManagerCannotUpdateAHoliday() {
        LocalDate date = LocalDate.of(2027, 8, 11);
        createHoliday(hr(), date, "เดิม");
        assertForbidden(() -> updateHoliday(manager(), date, "แก้ไข"));
    }

    @Test
    void anEmployeeCannotDeleteAHoliday() {
        LocalDate date = LocalDate.of(2027, 8, 20);
        createHoliday(hr(), date, "เดิม");
        assertForbidden(() -> deleteHoliday(employee(), date));
    }

    @Test
    void aSalesCallerCannotDeleteAHoliday() {
        LocalDate date = LocalDate.of(2027, 8, 21);
        createHoliday(hr(), date, "เดิม");
        assertForbidden(() -> deleteHoliday(sales(), date));
    }

    @Test
    void aDivisionManagerCannotDeleteAHoliday() {
        LocalDate date = LocalDate.of(2027, 8, 22);
        createHoliday(hr(), date, "เดิม");
        assertForbidden(() -> deleteHoliday(manager(), date));
    }

    // --- helpers --------------------------------------------------------------

    private Map<String, List<FetchOutcome>> fetchNow(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.fetchNow(session);
    }

    private List<HolidayDto> listHolidays(UserPrincipal user, LocalDate from, LocalDate to) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.list(from, to, session).holidays();
    }

    private HolidayDto createHoliday(UserPrincipal user, LocalDate date, String nameTh) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.create(new HolidayCreateRequest(date, nameTh), session);
    }

    private HolidayDto updateHoliday(UserPrincipal user, LocalDate date, String nameTh) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.update(date, new HolidayUpdateRequest(nameTh), session);
    }

    private void deleteHoliday(UserPrincipal user, LocalDate date) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        controller.delete(date, session);
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
