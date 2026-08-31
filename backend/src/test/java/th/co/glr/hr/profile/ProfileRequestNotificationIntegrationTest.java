package th.co.glr.hr.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesMailRecipientRepository;
import th.co.glr.hr.notification.SalesNotificationMailRouter;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms {@link ProfileRequestService}'s notification wiring against the real service and the
 * real SQL -- {@link NotificationRepository#notifyByRoleInternal}'s {@code "hr"} division predicate
 * for {@link ProfileRequestService#create}, and, since the 2026-08-31 regression fix, WHICH ADDRESS
 * {@link ProfileRequestService#update}'s employee-facing mail is addressed to.
 *
 * <p>Modeled on the sibling {@code ProfileRequestScopeIntegrationTest} (this package) and on
 * {@code AttendanceScopeIntegrationTest} for the {@code insertDivision}/{@code insertEmployee}
 * helper shape (CLAUDE.md, "Permission changes must ship evidence" -- this is not a role/scope gate,
 * but it IS a new division predicate against real SQL, which a mocked repository cannot prove: it
 * would happily "pass" a predicate that matched the wrong division, or every division).
 *
 * <p>{@code employees} is mocked here for the same reason {@code ProfileRequestScopeIntegrationTest}
 * mocks it: it plays no part in the behaviour under test.
 * {@link ProfileRequestService#create} falls back to {@link ProfileRequestRecord#requestedBy()} for
 * the notification's "who" text when the {@code EmployeeDto} lookup is empty, and the
 * {@code fieldLabel}/{@code fieldKey} copy guard reads {@link ProfileRequestRecord} directly either
 * way -- so every assertion below (division targeting, link, message copy) holds regardless of what
 * {@code EmployeeRepository} returns.
 *
 * <p><b>Wired with the REAL {@link SalesNotificationMailRouter}/{@link SalesMailRecipientRepository}
 * chain, mocked only at the {@link NotificationEmailService} boundary -- not {@code
 * SalesNotificationMailer.NO_OP}.</b> This is deliberate, not incidental: the regression this class
 * now guards against ({@code update}'s employee notification silently rerouting to a shared
 * departmental mailbox) is a ROUTING defect, and {@code NO_OP} cannot exercise routing at all -- its
 * own Javadoc says an assertion against it would be vacuous, since it can never send regardless of
 * what routing decided. A real router wired to a mocked {@code NotificationEmailService} is what
 * lets the mutation-check recorded on {@link
 * #approvingAnAcDivisionEmployeesRequestEmailsThemPersonallyNotTheSharedAccountMailbox} actually
 * observe mail landing on {@code account@glr.co.th} instead of merely failing to send at all. The
 * {@code create()} tests below are unaffected by this wiring change: the HR division they fan out to
 * has no email on file in these fixtures, so that path's real (deferred) recipient lookup resolves
 * to "no address, no manager address" and logs-and-drops, exactly as {@code NO_OP} would have looked
 * from the outside -- see {@link SalesNotificationMailRouter#routeResolved}.
 */
class ProfileRequestNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Literal, not a reference to {@code SalesNotificationMailRouter.ACCOUNT_MAILBOX}: that
     *  constant is package-private to {@code th.co.glr.hr.notification}, and this test lives in
     *  {@code th.co.glr.hr.profile}. */
    private static final String ACCOUNT_MAILBOX = "account@glr.co.th";
    private static final String IMPORT_MAILBOX = "import@glr.co.th";

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    /** The one double in the chain -- see the class Javadoc for why NO_OP would not do here. */
    private final NotificationEmailService emailService = mock(NotificationEmailService.class);

    private ProfileRequestService service;

    private long hrDivision;
    private long salesDivision;
    private long hrEmployee;
    private long salesEmployee;
    private long accountDivision;
    private long accountEmployee;
    private long importDivision;
    private long importEmployee;

    @BeforeEach
    void wireRealCollaborators() {
        when(employees.findEmployeeSummariesByIds(any())).thenReturn(Map.of());
        when(employees.findEmployeeSummaryById(anyLong())).thenReturn(Optional.empty());

        ProfileRequestRepository profileRequests = new ProfileRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(
            jdbc, new SalesNotificationMailRouter(emailService, new SalesMailRecipientRepository(jdbc)));
        NotificationService notificationService = new NotificationService(notifications, emailService);
        // @Transactional on ProfileRequestService#create/#update is inert without a real AOP proxy
        // (no Spring context here) -- see AbstractPostgresIntegrationTest#transactional's Javadoc.
        // The proxy matters doubly here: AfterCommit.run (inside both NotificationService and
        // SalesNotificationMailRouter) only defers when TransactionSynchronizationManager sees an
        // active transaction -- without this wrapper every email send below would run inline
        // instead of after commit, which is real, already-covered behaviour elsewhere
        // (SalesNotificationMailRoutingIntegrationTest) and not what this class means to re-prove.
        service = transactional(
            new ProfileRequestService(profileRequests, employees, auditService, notifications, notificationService));

        // "HR" (not a prefix match) is this company's actual HR division coding -- confirmed by
        // PendingApproverSql#SINGLE_ACTIVE_HR_NAME_SQL's Javadoc, which cites
        // V115__work_schedule_and_holiday_calendar.sql's seed. Deliberately no position_id on either
        // inserted employee, so the "hr" predicate's NOT LIKE '%กรรมการ%' executive guard reads
        // COALESCE(p.name_th, '') = '' and is a no-op for these fixtures -- exactly like a real
        // position-less HR staffer, who must still be notified.
        hrDivision = insertDivision("HR", "HR-บุคคล");
        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        hrEmployee = insertEmployee("HR001", hrDivision, null);
        salesEmployee = insertEmployee("S001", salesDivision, "rep.sales@glr.co.th");

        // The two shared-mailbox divisions SalesMailRecipientRepository#findRecipient classifies by
        // source_code prefix (ILIKE 'AC%' / ILIKE 'PCIM%'). Real production division codes, not
        // placeholders -- ฝ่ายบัญชี, source_code 'AC', is the confirmed production case (employee 72)
        // this regression was found against; see ProfileRequestService's constructor Javadoc.
        accountDivision = insertDivision("AC", "ฝ่ายบัญชี");
        accountEmployee = insertEmployee("AC001", accountDivision, "sunee.account@glr.co.th");
        importDivision = insertDivision("PCIM", "จัดซื้อต่างประเทศ");
        importEmployee = insertEmployee("PCIM001", importDivision, "buyer.import@glr.co.th");
    }

    // --- create(): notifies HR -----------------------------------------------

    @Test
    void createNotifiesTheHrDivisionWithTheRequestsLink() {
        service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        List<NotificationRow> hrRows = notificationsFor(hrEmployee, "PROFILE_REQUEST_SUBMITTED");
        assertThat(hrRows).hasSize(1);
        assertThat(hrRows.get(0).link()).isEqualTo("/requests");
    }

    /**
     * Wrong-way-round, per CLAUDE.md's "ask the question the wrong way round" discipline: this is
     * the case that actually proves the new {@code "hr"} division predicate FILTERS, rather than
     * merely that HR happens to receive a row. A predicate mutated to match every division would
     * still pass {@link #createNotifiesTheHrDivisionWithTheRequestsLink} above -- only this case
     * catches it. (Mutation-checked: see the PR body / session report.)
     */
    @Test
    void createDoesNotNotifyANonHrEmployee() {
        service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        assertThat(notificationsFor(salesEmployee, "PROFILE_REQUEST_SUBMITTED")).isEmpty();
    }

    // --- update(): notifies the requesting employee, at THEIR OWN address ----------------------

    /**
     * THE regression test, wrong-way-round: this proves the mail does NOT land on the shared
     * account@glr.co.th box, not merely that it lands somewhere. Confirmed against production
     * (employee 72, ฝ่ายบัญชี, {@code source_code = 'AC'}) -- see ProfileRequestService's
     * constructor Javadoc.
     *
     * <p><b>MUTATION-CHECKED</b> (see this session's report for the full record): reverting {@code
     * update()}'s {@code notificationService.notify(...)} call back to {@code
     * notifications.notifyEmployeeOfProfileRequest(...)} (the pre-fix code, restored temporarily
     * with the Edit tool and then reverted the same way) turns this test red -- the mocked {@code
     * emailService} observes the mail addressed to {@code account@glr.co.th} instead of to {@code
     * sunee.account@glr.co.th}, which is exactly the production defect this class exists to catch.
     */
    @Test
    void approvingAnAcDivisionEmployeesRequestEmailsThemPersonallyNotTheSharedAccountMailbox() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), acUser());

        service.update(created.id(), new UpdateProfileRequestRequest("approved", null), hrUser());

        verify(emailService).send(eq(accountEmployee), eq("sunee.account@glr.co.th"), any(),
            eq("อนุมัติคำขอแก้ไขข้อมูลของคุณแล้ว"), any(), eq("/profile"));
        verify(emailService, never()).send(anyLong(), eq(ACCOUNT_MAILBOX), any(), any(), any(), any());
    }

    /**
     * Same regression, the PCIM/import division -- and the rejection path, not just approval, so
     * both {@code update()} branches are proven to carry the fix, not just the one the AC case
     * happens to exercise.
     */
    @Test
    void rejectingAPcimDivisionEmployeesRequestEmailsThemPersonallyNotTheSharedImportMailbox() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), importUser());

        service.update(created.id(), new UpdateProfileRequestRequest("rejected", "ข้อมูลไม่ตรงกับเอกสาร"), hrUser());

        verify(emailService).send(eq(importEmployee), eq("buyer.import@glr.co.th"), any(),
            eq("คำขอแก้ไขข้อมูลของคุณไม่ได้รับอนุมัติ"), any(), eq("/profile"));
        verify(emailService, never()).send(anyLong(), eq(IMPORT_MAILBOX), any(), any(), any(), any());
    }

    /**
     * No-regression companion to the two cases above: a plain (non-AC/PCIM) employee already got
     * personal delivery before this fix (their division resolves to {@code SalesMailRecipientRepository
     * #findRecipient}'s {@code ELSE 'sales'} branch either way) and must still get it after.
     */
    @Test
    void approvingAPlainDivisionEmployeesRequestStillEmailsThemPersonally() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("approved", null), hrUser());

        verify(emailService).send(eq(salesEmployee), eq("rep.sales@glr.co.th"), any(),
            eq("อนุมัติคำขอแก้ไขข้อมูลของคุณแล้ว"), any(), eq("/profile"));
    }

    @Test
    void approvingNotifiesTheRequestingEmployeeWithTheProfileLink() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("approved", null), hrUser());

        List<NotificationRow> rows = notificationsFor(salesEmployee, "PROFILE_REQUEST_APPROVED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).link()).isEqualTo("/profile");
        // Byte-identical to what #860 shipped (now sourced from ProfileRequestService's own
        // APPROVED_TITLE constant rather than NotificationRepository#TICKET_EVENT_TITLES) -- the
        // in-app row must read exactly the same to a user regardless of which transport wrote it.
        assertThat(rows.get(0).title()).isEqualTo("อนุมัติคำขอแก้ไขข้อมูลของคุณแล้ว");
    }

    @Test
    void rejectingCarriesTheReviewerNoteToTheRequestingEmployee() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("rejected", "ข้อมูลไม่ตรงกับเอกสาร"), hrUser());

        List<NotificationRow> rows = notificationsFor(salesEmployee, "PROFILE_REQUEST_REJECTED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).message()).contains("เหตุผล: ข้อมูลไม่ตรงกับเอกสาร");
        assertThat(rows.get(0).title()).isEqualTo("คำขอแก้ไขข้อมูลของคุณไม่ได้รับอนุมัติ");
    }

    @Test
    void rejectingWithABlankReviewerNoteLeavesNoDanglingReasonLabel() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("rejected", ""), hrUser());

        List<NotificationRow> rows = notificationsFor(salesEmployee, "PROFILE_REQUEST_REJECTED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).message()).doesNotContain("เหตุผล");
    }

    // --- copy guard: fieldLabel only, never the raw fieldKey -------------------

    /**
     * Regression guard for the TRAVEL_PER_DIEM class of defect that CLAUDE.md records: a raw
     * machine field code reaching a human-facing message instead of its Thai label. Covers all
     * three message builders (submitted/approved/rejected) in one pass, rather than three separate
     * assertions that could each individually go stale without the others noticing.
     *
     * <p>Deliberately asserts nothing about ROW COUNT -- only message content. An earlier version
     * of this test also asserted {@code hasSize(4)}, and the case-2/case-5 mutation check below
     * caught that as a real defect in the test itself: loosening the "hr" division predicate (case
     * 2's own mutation) changes how many employees get fanned out to, which changed the count and
     * made this test go red FOR THE WRONG REASON alongside case 2, violating "case 2 must go red,
     * alone". Content-only keeps this test isolated to the one thing it claims to guard.
     */
    @Test
    void everyNotificationMessageCarriesTheThaiLabelNeverTheRawFieldKey() {
        ProfileRequestDto toApprove = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());
        ProfileRequestDto toReject = service.create(emailRequest("old2@glr.co.th", "new2@glr.co.th"), salesUser());
        service.update(toApprove.id(), new UpdateProfileRequestRequest("approved", null), hrUser());
        service.update(toReject.id(), new UpdateProfileRequestRequest("rejected", "ข้อมูลไม่ตรงกับเอกสาร"), hrUser());

        List<String> messages = profileRequestNotificationMessages();

        assertThat(messages).isNotEmpty();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message).contains("อีเมล");
            assertThat(message).doesNotContain("email");
        });
    }

    // --- helpers ----------------------------------------------------------------

    private record NotificationRow(String type, String title, String message, String link) {
    }

    private List<NotificationRow> notificationsFor(long employeeId, String type) {
        return jdbc.query("""
            SELECT type, title, message, link
              FROM hr.notification
             WHERE employee_id = :employeeId AND type = :type
             ORDER BY notification_id
            """,
            Map.of("employeeId", employeeId, "type", type),
            (rs, rowNum) -> new NotificationRow(
                rs.getString("type"), rs.getString("title"), rs.getString("message"), rs.getString("link")));
    }

    private List<String> profileRequestNotificationMessages() {
        return jdbc.query("""
            SELECT message FROM hr.notification
             WHERE type LIKE 'PROFILE_REQUEST%'
             ORDER BY notification_id
            """, Map.of(), (rs, rowNum) -> rs.getString("message"));
    }

    private CreateProfileRequestRequest emailRequest(String oldValue, String newValue) {
        return new CreateProfileRequestRequest("email", "อีเมล", oldValue, newValue);
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(500L, "sales@glr.co.th", "sales", "sales", salesEmployee, true,
            LocalDate.now(), false, salesDivision, false);
    }

    private UserPrincipal hrUser() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", hrEmployee, true,
            LocalDate.now(), false, hrDivision, false);
    }

    private UserPrincipal acUser() {
        return new UserPrincipal(502L, "account.rep@glr.co.th", "employee", "employee", accountEmployee, true,
            LocalDate.now(), false, accountDivision, false);
    }

    private UserPrincipal importUser() {
        return new UserPrincipal(503L, "import.rep@glr.co.th", "employee", "employee", importEmployee, true,
            LocalDate.now(), false, importDivision, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    /**
     * {@code email} is genuinely nullable here (an HR employee with no address on file, matching
     * real rows) -- {@link HashMap}, not {@link Map#of}, because the latter rejects a null value.
     */
    private long insertEmployee(String code, Long divisionId, String email) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        params.put("email", email);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active, email)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE, :email)
            RETURNING employee_id
            """, params, Long.class);
    }
}
