package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Evidence upload/download authorization, driven through the real TaxAllowanceDeclarationService + real
 * Postgres — the gap Mockito-based unit tests cannot cover (issue #28), same reasoning as
 * AttendanceScopeIntegrationTest, which every scope test in this package is modelled on. */
import th.co.glr.hr.config.AppProperties;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceAttachmentScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository repository;
    private TaxAllowanceDeclarationService service;

    private long employeeA;
    private long employeeB;
    private long hrEmployeeId;
    private long ceoEmployeeId;

    @BeforeEach
    void wireRealCollaborators() throws Exception {
        repository = new TaxAllowanceDeclarationRepository(jdbc);
        PayrollRepository payrollRepository = new PayrollRepository(jdbc);
        FileStorageService fileStorage =
            new FileStorageService(Files.createTempDirectory("tax-allowance-evidence-test").toString());
        service = new TaxAllowanceDeclarationService(
            repository,
            payrollRepository,
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            fileStorage,
            // The estimate endpoint is not exercised by this class — a mock is enough.
            mock(PayrollService.class),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            // Not under test here — this class is about the attachment access gate.
            mock(NotificationService.class),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("TAA-A");
        employeeB = seedEmployee("TAA-B");
        hrEmployeeId = seedEmployee("TAA-HR");
        ceoEmployeeId = seedEmployee("TAA-CEO");
    }

    @Test
    void anotherEmployeeCannotDownloadTheOwnersEvidenceAndGetsNoBytes() {
        long declarationId = submit(employeeA);
        TaxAllowanceAttachmentDto uploaded = uploadPdf(declarationId, employeeActor(employeeA));

        assertThatThrownBy(() -> service.getAttachmentForDownload(uploaded.attachmentId(), employeeActor(employeeB)))
            .as("a foreign employee must 404, never receive the file")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // The owner (and HR) can still reach it -- proves the 404 above is scoping, not a broken feature.
        assertThat(service.getAttachmentForDownload(uploaded.attachmentId(), employeeActor(employeeA)).filePath())
            .isNotBlank();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private long submit(long employeeId) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
            2026,                     // taxYear
            new BigDecimal("60000"),  // spouseAllowance
            null, null, null, null,   // child, parentCare, disabledCare, maternity
            null, null, null,         // life, health, parentHealth
            null, null, null, null,   // rmf, ssf, pension, thaiEsg
            null, null, null, null,   // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,         // childCount, childCountDouble, disabledCareCount
            null,                     // disabilityCardHolder
            null,                     // parentCareCount
            null,                    // documentReference
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
        return service.submitOwn(request, employeeActor(employeeId)).declarationId();
    }

    private TaxAllowanceAttachmentDto uploadPdf(long declarationId, UserPrincipal actor) {
        return uploadPdf(declarationId, null, actor);
    }

    private TaxAllowanceAttachmentDto uploadPdf(long declarationId, String sectionKey, UserPrincipal actor) {
        MockMultipartFile file = new MockMultipartFile(
            "file", "evidence.pdf", "application/pdf", "หลักฐานประกอบการยื่นแบบ".getBytes());
        return service.uploadAttachment(declarationId, file, sectionKey, actor);
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

}
