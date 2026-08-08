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
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
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
 */
import th.co.glr.hr.config.AppProperties;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceNotificationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository repository;
    private TaxAllowanceDeclarationService service;

    private long employeeA;
    private long employeeB;
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new TaxAllowanceDeclarationRepository(jdbc);
        service = new TaxAllowanceDeclarationService(
            repository,
            new PayrollRepository(jdbc),
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            mock(PayrollService.class),
            // Real, not mocked: the whole point is which employee_id reaches the INSERT.
            new NotificationRepository(jdbc),
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

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
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
