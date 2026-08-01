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
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDownload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationRegisterResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Evidence upload/download authorization, driven through the real {@link
 * TaxAllowanceDeclarationService} + real Postgres — the gap Mockito-based unit tests cannot cover
 * (issue #28), same reasoning as {@code AttendanceScopeIntegrationTest}, which every scope test in
 * this package is modelled on. Written wrong-way-round: every case asks "can a caller reach a file
 * they should not", never "can they reach their own".
 *
 * <p><b>The download gate is the security-critical part of the whole evidence feature.</b> Every
 * case below pins {@code TaxAllowanceDeclarationService#requireOwnerOrHr}'s two properties at
 * once: (1) it is re-resolved from the PARENT declaration on EVERY call, never cached from upload
 * time, and (2) it deliberately does NOT copy {@code AttachmentController
 * #requireAttachmentAccess}'s uploader short-circuit — see {@link
 * #accessIsRecheckedOnEveryDownloadNeverCachedFromUploadTime} for the case that specifically pins
 * the second property, and this class's own javadoc mutation-check note in the PR body for how
 * that pin was verified to actually fail if the short-circuit is reintroduced.
 */
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
            mock(PayrollService.class));

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

    @Test
    void anotherEmployeeCannotListTheOwnersAttachments() {
        long declarationId = submit(employeeA);
        uploadPdf(declarationId, employeeActor(employeeA));

        assertThatThrownBy(() -> service.listAttachments(declarationId, employeeActor(employeeB)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * The anti-{@code requireAttachmentAccess} case. HR uploads ON BEHALF of employeeA (HR is the
     * uploader; employeeA is the declaration's beneficiary and the ONLY employee this evidence is
     * about). If the download gate copied {@code AttachmentController#requireAttachmentAccess}'s
     * {@code actor.id() == dto.uploadedBy()} short-circuit, the very same HR principal — even
     * stripped of the "hr" role, e.g. after a demotion — would keep permanent access. It must not:
     * the SAME attachment is 200 while the caller still holds "hr", and 404 the instant the role
     * check alone would fail, regardless of who physically uploaded it.
     */
    @Test
    void accessIsRecheckedOnEveryDownloadNeverCachedFromUploadTime() {
        long declarationId = submit(employeeA);
        TaxAllowanceAttachmentDto uploaded = uploadPdf(declarationId, hrActor());

        TaxAllowanceAttachmentDownload asHr = service.getAttachmentForDownload(uploaded.attachmentId(), hrActor());
        assertThat(asHr.attachment().attachmentId()).isEqualTo(uploaded.attachmentId());

        UserPrincipal sameUploaderIdButNoLongerHr = new UserPrincipal(hrEmployeeId, "exhr@glr.co.th", "employee",
            "employee", hrEmployeeId, true, LocalDate.now(), false, null, false);
        assertThatThrownBy(() -> service.getAttachmentForDownload(uploaded.attachmentId(), sameUploaderIdButNoLongerHr))
            .as("the uploader's identity must never substitute for a live owner-or-hr check")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * Decision #5 / plan open question #2's deliberate asymmetry: CEO reads the declaration
     * REGISTER (payroll amounts) but never its evidence (personal medical/insurance/family
     * documents).
     */
    @Test
    void ceoCannotDownloadOrListEvidenceWhileTheRegisterStillReturns200() {
        long declarationId = submit(employeeA);
        TaxAllowanceAttachmentDto uploaded = uploadPdf(declarationId, employeeActor(employeeA));

        TaxAllowanceDeclarationRegisterResponse register = service.getRegister(2026, null, ceoActor());
        assertThat(register.items()).extracting(TaxAllowanceDeclarationDto::declarationId).contains(declarationId);

        assertThatThrownBy(() -> service.getAttachmentForDownload(uploaded.attachmentId(), ceoActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.listAttachments(declarationId, ceoActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aTombstonedFileIsNotDownloadableButTheRowSurvives() {
        long declarationId = submit(employeeA);
        TaxAllowanceAttachmentDto uploaded = uploadPdf(declarationId, employeeActor(employeeA));

        service.deleteAttachment(uploaded.attachmentId(), "อัปโหลดผิดไฟล์", employeeActor(employeeA));

        assertThatThrownBy(() -> service.getAttachmentForDownload(uploaded.attachmentId(), employeeActor(employeeA)))
            .as("a tombstoned file must not be downloadable even by its own owner")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        TaxAllowanceAttachmentDto stillThere = repository.findAttachment(uploaded.attachmentId()).orElseThrow();
        assertThat(stillThere.deletedAt()).as("the row must survive -- a tombstone, never a hard delete").isNotNull();
        assertThat(stillThere.fileName()).isEqualTo(uploaded.fileName());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private long submit(long employeeId) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
            2026,                     // taxYear
            null,                     // effectiveMonth -> defaults to January
            new BigDecimal("60000"),  // spouseAllowance
            null, null, null, null,   // child, parentCare, disabledCare, maternity
            null, null, null,         // life, health, parentHealth
            null, null, null, null,   // rmf, ssf, pension, thaiEsg
            null, null, null, null,   // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,         // childCount, childCountDouble, disabledCareCount
            null,                     // disabilityCardHolder
            null,                     // parentCareCount
            null);                    // documentReference
        return service.submitOwn(request, employeeActor(employeeId)).declarationId();
    }

    private TaxAllowanceAttachmentDto uploadPdf(long declarationId, UserPrincipal actor) {
        MockMultipartFile file = new MockMultipartFile(
            "file", "evidence.pdf", "application/pdf", "หลักฐานประกอบการยื่นแบบ".getBytes());
        return service.uploadAttachment(declarationId, file, actor);
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
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceoActor() {
        return new UserPrincipal(ceoEmployeeId, "ceo@glr.co.th", "CEO", "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
    }
}
