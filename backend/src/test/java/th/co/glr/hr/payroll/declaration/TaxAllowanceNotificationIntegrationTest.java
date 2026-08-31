package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceOnBehalfRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceReviewRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * The ล.ย.01 workflow emitted no notifications at all: HR approving, HR rejecting and the yearly
 * expiry sweep were each invisible to the employee, who could only discover them by opening a page
 * nothing pointed at. This class covers the rows those three transitions now write.
 *
 * <p>Written wrong-way-round, like {@code TaxAllowanceDeclarationScopeIntegrationTest} beside it:
 * the assertion that matters is that a notification about one employee's tax affairs lands ONLY in
 * that employee's inbox — never the reviewing HR user's, and never a bystander's. A notification
 * body carries the rejection reason and the tax year, so mis-targeting one leaks the same personal
 * material the register's evidence column is careful to withhold.
 *
 * <p>Goes through the real service and a real {@link NotificationRepository} against real Postgres
 * — a mocked repository would "pass" while the INSERT wrote a different employee_id.
 *
 * <h2>2026-08-31 extension: mail dispatch, HR's submit-side gap, and two more decision points</h2>
 *
 * The class above pre-dates this change and only ever proved the IN-APP row. This class now also
 * covers three more things, and each is scoped to a different subset of the tests below:
 *
 * <ol>
 *   <li>{@code notifyOwner} switched from a bare {@code NotificationRepository#insert} to {@link
 *       NotificationService#notify}, which also queues mail. {@code employeeRepository} is now a
 *       REAL {@link EmployeeRepository} (not mocked, unlike before) — needed for the HR-submission
 *       "who filed" text below, and proven harmless for the pre-existing approve/reject/expire tests
 *       by inspection: {@code promoteHeaderToEmployeeMaster} (called inside {@code #approve}) only
 *       reaches {@code EmployeeRepository} when {@code existing.lorYor01().address() != null}, and
 *       every declaration this class submits carries a null {@code lorYor01}, so that branch is
 *       never entered here. {@code emailService} is a Mockito mock — this class wires NO real Mailer/SMTP
 *       — so {@code approvalAlsoDispatchesEmailWithTheSameSubjectAndBody} below is genuine evidence
 *       that {@code NotificationService#notify} was invoked with the right recipient/subject/body/
 *       link and {@code sendEmail=true}; it is NOT evidence that mail was actually delivered.</li>
 *   <li>{@code submitOwn} now notifies HR via {@link NotificationRepository#notifyHrAt}, the same
 *       {@code "hr"} division fan-out {@code ProfileRequestNotificationIntegrationTest} (#860)
 *       exercises for profile-change requests. The {@code submitting*} tests below seed a REAL
 *       {@code hr.division}/{@code hr.employee} pair the same way that class does, for the same
 *       reason: a mocked repository would "pass" a predicate that matched the wrong division, or
 *       every division.</li>
 *   <li>Two more decision points that plainly should notify the employee, found while auditing this
 *       class's full state machine, are covered too: {@code createOnBehalf} (HR creates AND
 *       approves in one action — the employee was never told either half happened) and {@code
 *       reverify} (EXPIRED -> APPROVED — the mirror image of the expiry sweep's notification, which
 *       had no reverse). Both reuse the {@code TAX_ALLOWANCE_APPROVED} type rather than inventing a
 *       new one: the fact that matters to the employee is identical to a regular {@code #approve},
 *       so the frontend's existing type-to-icon mapping already renders both correctly untouched.</li>
 * </ol>
 */
class TaxAllowanceNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * Mocked, not a real Mailer/SMTP — see the class Javadoc's point 1. Verifying calls against this
     * is genuine wiring evidence (it proves {@code NotificationService#notify} reached the mail
     * layer with the right arguments); it is not evidence of delivery.
     */
    private final NotificationEmailService emailService = mock(NotificationEmailService.class);

    private TaxAllowanceDeclarationRepository repository;
    private TaxAllowanceDeclarationService service;

    private long employeeA;
    private long employeeB;
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new TaxAllowanceDeclarationRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        service = new TaxAllowanceDeclarationService(
            repository,
            new PayrollRepository(jdbc),
            // REAL, unlike before this class's 2026-08-31 extension — see the class Javadoc's point 1
            // for why this is safe for the pre-existing approve/reject/expire tests below, and why it
            // is now required for the HR-submission "who filed" text.
            new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            mock(PayrollService.class),
            // Real, not mocked: the whole point is which employee_id reaches the INSERT, and (now)
            // which division the HR fan-out actually selects.
            notifications,
            new NotificationService(notifications, emailService),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("TAN-A");
        employeeB = seedEmployee("TAN-B");
        hrEmployeeId = seedEmployee("TAN-HR");
    }

    @Test
    void approvalNotifiesTheOwnerAndNobodyElse() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026);

        approveSigned(declaration.declarationId());

        assertThat(notificationTypesFor(employeeA)).containsExactly("TAX_ALLOWANCE_APPROVED");
        // The reviewing HR user must not be notified about someone else's tax affairs.
        assertThat(notificationTypesFor(hrEmployeeId)).isEmpty();
        assertThat(notificationTypesFor(employeeB)).isEmpty();
    }

    @Test
    void rejectionNotifiesTheOwnerAndNobodyElse() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026);

        service.reject(declaration.declarationId(), new TaxAllowanceReviewRequest("หลักฐานไม่ครบ"), hrActor());

        assertThat(notificationTypesFor(employeeA)).containsExactly("TAX_ALLOWANCE_REJECTED");
        assertThat(notificationTypesFor(hrEmployeeId)).isEmpty();
        assertThat(notificationTypesFor(employeeB)).isEmpty();
    }

    @Test
    void theRejectionReasonTravelsInTheNotificationBody() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026);

        service.reject(declaration.declarationId(), new TaxAllowanceReviewRequest("ขาดใบเสร็จเบี้ยประกัน"), hrActor());

        Map<String, Object> row = latestNotification(employeeA);
        assertThat((String) row.get("message")).contains("ขาดใบเสร็จเบี้ยประกัน");
        // Deep-links to the employee's own declaration page, not a ticket route.
        assertThat(row.get("link")).isEqualTo("/tax-allowance");
    }

    @Test
    void everyNotificationLandsOnTheOwnerEvenWhenAnotherEmployeeHasTheirOwnDeclaration() {
        TaxAllowanceDeclarationDto declarationA = submit(employeeA, 2026);
        TaxAllowanceDeclarationDto declarationB = submit(employeeB, 2026);

        approveSigned(declarationA.declarationId());
        service.reject(declarationB.declarationId(), new TaxAllowanceReviewRequest("ไม่ผ่าน"), hrActor());

        // Each employee sees exactly their own outcome — never the other's.
        assertThat(notificationTypesFor(employeeA)).containsExactly("TAX_ALLOWANCE_APPROVED");
        assertThat(notificationTypesFor(employeeB)).containsExactly("TAX_ALLOWANCE_REJECTED");
    }

    @Test
    void theExpirySweepNotifiesEachOwnerExactlyOnce() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026);
        approveSigned(declaration.declarationId());
        service.apply(declaration.declarationId(), new TaxAllowanceApplyRequest(1), hrActor());
        // Backdate the deadline so the sweep considers this row overdue.
        jdbc.update("UPDATE hr.tax_allowance_declaration SET expires_on = :past WHERE declaration_id = :id",
            Map.of("past", LocalDate.now().minusDays(1), "id", declaration.declarationId()));

        assertThat(service.expireOverdueVerifications()).isEqualTo(1);
        assertThat(notificationTypesFor(employeeA))
            .containsExactly("TAX_ALLOWANCE_APPROVED", "TAX_ALLOWANCE_EXPIRED");

        // A second sweep matches zero rows (the conditional UPDATE already fired), so it must not
        // notify again — otherwise a stuck scheduler would refill the employee's inbox hourly.
        assertThat(service.expireOverdueVerifications()).isEqualTo(0);
        assertThat(notificationTypesFor(employeeA))
            .containsExactly("TAX_ALLOWANCE_APPROVED", "TAX_ALLOWANCE_EXPIRED");
    }

    // --- 2026-08-31: mail dispatch -----------------------------------------------------------

    @Test
    void approvalAlsoDispatchesEmailWithTheSameSubjectAndBody() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026);

        approveSigned(declaration.declarationId());

        // emailService is a mock (no real Mailer/SMTP in this class) — this verifies the WIRING
        // (NotificationService#notify was reached with sendEmail=true and the right arguments), not
        // delivery. `to`/`recipientName` are left loose: employeeA (seeded by the bare seedEmployee
        // helper below) has no email/first_name_th on file, so both arrive null, and that is not
        // what this test is about.
        verify(emailService).send(eq(employeeA), any(), any(),
            eq("แบบแจ้ง ล.ย.01 ได้รับการอนุมัติ"),
            eq("ฝ่ายบุคคลอนุมัติแบบแจ้งค่าลดหย่อนภาษีปี 2026 แล้ว"),
            eq("/tax-allowance"));
    }

    // --- 2026-08-31: submitOwn notifies HR ----------------------------------------------------

    @Test
    void submittingNotifiesTheHrDivisionWithTheReviewLinkAndTheFilersName() {
        long hrDivision = insertDivision("HR", "HR-บุคคล");
        long hrRecipient = insertEmployeeInDivision("SUBHR", hrDivision);
        long submitter = insertEmployeeWithTitleAndName("GLR-1009", "นาย", "ภาคภูมิ", "ศรีสุข");

        submit(submitter, 2026);

        Map<String, Object> row = latestNotification(hrRecipient);
        assertThat(row.get("type")).isEqualTo("TAX_ALLOWANCE_SUBMITTED");
        assertThat(row.get("title")).isEqualTo("มีแบบ ล.ย.01 รอ HR ตรวจสอบ");
        assertThat(row.get("link")).isEqualTo("/tax-allowance-review");
        assertThat(row.get("message")).isEqualTo("นายภาคภูมิ ศรีสุข (GLR-1009) ยื่นแบบ ล.ย.01 ปีภาษี 2026");
    }

    /**
     * Wrong-way-round, per CLAUDE.md's "ask the question the wrong way round" discipline: this is
     * the case that actually proves the {@code "hr"} division predicate FILTERS, rather than merely
     * that HR happens to receive a row. A predicate mutated to match every division would still pass
     * the test above — only this case catches it. (Mutation-checked: see the session report.)
     */
    @Test
    void submittingDoesNotNotifyANonHrEmployee() {
        long salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        long salesEmployee = insertEmployeeInDivision("SUBSLS", salesDivision);

        submit(salesEmployee, 2026);

        assertThat(notificationTypesFor(salesEmployee)).doesNotContain("TAX_ALLOWANCE_SUBMITTED");
    }

    @Test
    void createOnBehalfDoesNotAlsoNotifyHrOfASubmission() {
        // createOnBehalf never leaves a PENDING row for HR to see — it inserts and auto-approves
        // within the same transaction, so notifyHrOfSubmission (submitOwn-only) must not fire here.
        long hrDivision = insertDivision("HR", "HR-บุคคล");
        long hrRecipient = insertEmployeeInDivision("OBHR", hrDivision);
        long employeeId = seedEmployee("TAN-OB1");

        service.createOnBehalf(onBehalfRequest(employeeId, 2026), hrActor());

        assertThat(notificationTypesFor(hrRecipient)).isEmpty();
    }

    // --- 2026-08-31: audit additions — createOnBehalf and reverify notify the employee too ---

    @Test
    void creatingOnBehalfNotifiesTheEmployeeItWasApprovedForThem() {
        long employeeId = seedEmployee("TAN-OB2");

        service.createOnBehalf(onBehalfRequest(employeeId, 2026), hrActor());

        assertThat(notificationTypesFor(employeeId)).containsExactly("TAX_ALLOWANCE_APPROVED");
        Map<String, Object> row = latestNotification(employeeId);
        assertThat(row.get("message")).isEqualTo("ฝ่ายบุคคลสร้างและอนุมัติแบบแจ้งค่าลดหย่อนภาษีปี 2026 ให้คุณแล้ว");
        assertThat(row.get("link")).isEqualTo("/tax-allowance");
    }

    @Test
    void reverifyingNotifiesTheEmployeeTheirAllowanceIsActiveAgain() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026);
        approveSigned(declaration.declarationId());
        service.apply(declaration.declarationId(), new TaxAllowanceApplyRequest(1), hrActor());
        jdbc.update("UPDATE hr.tax_allowance_declaration SET expires_on = :past WHERE declaration_id = :id",
            Map.of("past", LocalDate.now().minusDays(1), "id", declaration.declarationId()));
        assertThat(service.expireOverdueVerifications()).isEqualTo(1);

        service.reverify(declaration.declarationId(), hrActor());

        assertThat(notificationTypesFor(employeeA))
            .containsExactly("TAX_ALLOWANCE_APPROVED", "TAX_ALLOWANCE_EXPIRED", "TAX_ALLOWANCE_APPROVED");
        Map<String, Object> row = latestNotification(employeeA);
        assertThat(row.get("message")).isEqualTo(
            "แบบแจ้งค่าลดหย่อนภาษีปี 2026 ที่เคยหมดอายุ ได้รับการยืนยันใหม่จากฝ่ายบุคคลแล้ว "
                + "สิทธิลดหย่อนของคุณกลับมาใช้งานได้ตามปกติ");
        assertThat(row.get("link")).isEqualTo("/tax-allowance");
    }

    // --- 2026-08-31: copy guard ----------------------------------------------------------------

    /**
     * Regression guard for the TRAVEL_PER_DIEM class of defect that CLAUDE.md records: a raw machine
     * code reaching a human-facing message. Drives every transition this class touches — submit,
     * approve, reject, expire, reverify, createOnBehalf — in one pass and scans every title/message
     * {@code hr.notification} now holds. (Mutation-checked: see the session report.)
     */
    @Test
    void noNotificationMessageOrTitleEverContainsARawTypeCode() {
        TaxAllowanceDeclarationDto toApprove = submit(employeeA, 2026);
        approveSigned(toApprove.declarationId());
        service.apply(toApprove.declarationId(), new TaxAllowanceApplyRequest(1), hrActor());
        jdbc.update("UPDATE hr.tax_allowance_declaration SET expires_on = :past WHERE declaration_id = :id",
            Map.of("past", LocalDate.now().minusDays(1), "id", toApprove.declarationId()));
        service.expireOverdueVerifications();
        service.reverify(toApprove.declarationId(), hrActor());

        TaxAllowanceDeclarationDto toReject = submit(employeeB, 2027);
        service.reject(toReject.declarationId(), new TaxAllowanceReviewRequest("เอกสารไม่ครบ"), hrActor());

        long onBehalfEmployee = seedEmployee("TAN-COPY-OB");
        service.createOnBehalf(onBehalfRequest(onBehalfEmployee, 2026), hrActor());

        List<String> copy = jdbc.query(
            "SELECT title FROM hr.notification UNION ALL SELECT message FROM hr.notification",
            Map.of(), (rs, rowNum) -> rs.getString(1));

        assertThat(copy).isNotEmpty();
        assertThat(copy).allSatisfy(text -> {
            assertThat(text).doesNotContain("TAX_ALLOWANCE_");
            assertThat(text).doesNotContain("PENDING");
            assertThat(text).doesNotContain("SUPERSEDED");
            assertThat(text).doesNotContain("WITHDRAWN");
        });
    }

    // --- helpers ---------------------------------------------------------------------------------

    private List<String> notificationTypesFor(long employeeId) {
        return jdbc.queryForList(
            "SELECT type FROM hr.notification WHERE employee_id = :employeeId ORDER BY notification_id",
            Map.of("employeeId", employeeId), String.class);
    }

    private Map<String, Object> latestNotification(long employeeId) {
        return jdbc.queryForMap(
            """
            SELECT type, title, message, link FROM hr.notification
             WHERE employee_id = :employeeId
             ORDER BY notification_id DESC LIMIT 1
            """,
            Map.of("employeeId", employeeId));
    }

    private TaxAllowanceDeclarationDto submit(long employeeId, int taxYear) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
            taxYear,                 // taxYear
            null,                    // effectiveMonth -> defaults to January
            new BigDecimal("60000"), // spouseAllowance
            null, null, null, null,  // child, parentCare, disabledCare, maternity
            null, null, null,        // life, health, parentHealth
            null, null, null, null,  // rmf, ssf, pension, thaiEsg
            null, null, null, null,  // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,        // childCount, childCountDouble, disabledCareCount
            null,                    // disabilityCardHolder
            null,                    // parentCareCount
            null,                   // documentReference
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
        return service.submitOwn(request, employeeActor(employeeId));
    }

    private TaxAllowanceOnBehalfRequest onBehalfRequest(long employeeId, int taxYear) {
        return new TaxAllowanceOnBehalfRequest(
            employeeId, taxYear,
            null,                    // effectiveMonth
            new BigDecimal("60000"), // spouseAllowance
            null, null, null, null,  // child, parentCare, disabledCare, maternity
            null, null, null,        // life, health, parentHealth
            null, null, null, null,  // rmf, ssf, pension, thaiEsg
            null, null, null, null,  // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,        // childCount, childCountDouble, disabledCareCount
            null,                    // disabilityCardHolder
            null,                    // parentCareCount
            null,                    // documentReference
            null);                   // lorYor01
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

    /** {@code ProfileRequestNotificationIntegrationTest}'s own helper, same shape. */
    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    /**
     * A division-linked employee with no name — enough for the HR fan-out's SQL predicate (which
     * reads {@code hr.employee}/{@code hr.division}/{@code hr.position} directly, never {@link
     * EmployeeRepository}), and this class never asserts on a recipient's own name.
     */
    private long insertEmployeeInDivision(String code, long divisionId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, division_id, is_active)
            VALUES (:code, :divisionId, TRUE) RETURNING employee_id
            """, Map.of("code", code, "divisionId", divisionId), Long.class);
    }

    /**
     * A named employee, for the "who filed" text — {@code title_id} via {@link
     * EmployeeReferenceRepository#ensureTitle}, the same find-or-create helper {@code
     * EmployeeRepository#create} itself uses, so this does not depend on {@code hr.title} already
     * holding the row.
     */
    private long insertEmployeeWithTitleAndName(String code, String titleTh, String firstNameTh, String lastNameTh) {
        long titleId = new EmployeeReferenceRepository(jdbc).ensureTitle(titleTh);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, title_id, first_name_th, last_name_th, is_active)
            VALUES (:code, :titleId, :firstNameTh, :lastNameTh, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "titleId", titleId, "firstNameTh", firstNameTh, "lastNameTh", lastNameTh),
            Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    /**
     * Approves the way HR now has to: the signed ล.ย.01 must be attached first (owner decision #3).
     * The failure cases below deliberately do NOT use this — they assert on the role and status
     * checks, which both run before the signed-form check and so are unaffected by it.
     */
    private void approveSigned(long declarationId) {
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);
        service.approve(declarationId, null, hrActor());
    }
}
