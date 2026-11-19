package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The ล.ย.01 workflow emitted no notifications at all: HR approving, HR rejecting and the yearly expiry sweep
 * were each invisible to the employee, who could only discover them by opening a page nothing pointed at. */
class TaxAllowanceNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Mocked, not a real Mailer/SMTP — see the class Javadoc's point 1. Verifying calls against this is genuine
     * wiring evidence (it proves NotificationService#notify reached the mail layer with the right arguments); it
     * is not evidence of delivery. */
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
            // REAL, unlike before this class's 2026-08-31 extension — see the class Javadoc's point 1 for why this is safe
            // for the pre-existing approve/reject/expire tests below, and why it is now required for the HR-submission
            // "who filed" text.
            new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            mock(PayrollService.class),
            // Real, not mocked: the whole point is which employee_id reaches the INSERT, and (now) which division the HR
            // fan-out actually selects.
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

    /** A division-linked employee with no name — enough for the HR fan-out's SQL predicate (which reads
     * hr.employee/hr.division/hr.position directly, never EmployeeRepository), and this class never asserts on a
     * recipient's own name. */
    private long insertEmployeeInDivision(String code, long divisionId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, division_id, is_active)
            VALUES (:code, :divisionId, TRUE) RETURNING employee_id
            """, Map.of("code", code, "divisionId", divisionId), Long.class);
    }

    /** A named employee, for the "who filed" text — title_id via EmployeeReferenceRepository#ensureTitle, the same
     * find-or-create helper EmployeeRepository#create itself uses, so this does not depend on hr.title already
     * holding the row. */
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

    /** Approves the way HR now has to: the signed ล.ย.01 must be attached first (owner decision #3). The failure
     * cases below deliberately do NOT use this — they assert on the role and status checks, which both run before
     * the signed-form check and so are unaffected by it. */
    private void approveSigned(long declarationId) {
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);
        service.approve(declarationId, null, hrActor());
    }
}
