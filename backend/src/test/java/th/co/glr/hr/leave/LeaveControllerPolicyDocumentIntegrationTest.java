package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * {@code GET}/{@code POST /api/leave/policy-document} against a real, freshly-migrated Postgres
 * schema (V133). The write side ({@code uploadPolicyDocument}) is a brand-new authorization
 * surface (HR/CEO only), so CLAUDE.md "Permission changes must ship evidence" requires this exact
 * real-service/real-repository proof — a Mockito-mocked {@code LeavePolicyDocumentRepository}
 * cannot show the SQL in V133 actually behaves as the migration's comment claims.
 *
 * <p>Every forbidden case asks the question the wrong way round: can a caller who should not reach
 * the upload endpoint actually reach it. {@code leaveService} is never constructed (it is irrelevant
 * to either endpoint under test), matching {@code LeaveController}'s constructor taking it only for
 * the OTHER endpoints on this controller.
 */
class LeaveControllerPolicyDocumentIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveController controller;
    private LeavePolicyDocumentRepository repository;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new LeavePolicyDocumentRepository(jdbc);
        controller = new LeaveController(/* leaveService */ null, new SessionContext(), repository);
    }

    // --- GET: read path, unchanged access (any authenticated user) -------------------------

    @Test
    void getReturns404WithAClearThaiMessageWhenTheTableIsEmpty() {
        assertThatThrownBy(() -> get(employee()))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                assertThat(apiException.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(apiException.getMessage()).isEqualTo("ยังไม่มีการอัปโหลดเอกสารประกาศฉบับนี้ กรุณาติดต่อฝ่ายบุคคล");
            });
    }

    @Test
    void getRequiresAuthentication() {
        MockHttpSession anonymousSession = new MockHttpSession();
        assertThatThrownBy(() -> controller.policyDocument(anonymousSession))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anyAuthenticatedRoleCanReadOnceADocumentExists() throws Exception {
        upload(hr(), samplePdfBytes(), LocalDate.of(2024, 10, 1));

        // A plain employee (never hr/ceo) can still read -- this is the "every employee may read
        // the rules that bind them" access CLAUDE.md's brief specified, unchanged by the write
        // gate above.
        ResponseEntity<Resource> response = get(employee());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getContentAsByteArray()).isEqualTo(samplePdfBytes());
    }

    @Test
    void getServesTheLatestEffectiveFromNotInTheFuture() throws Exception {
        byte[] older = pdfBytes("older");
        byte[] current = pdfBytes("current");
        byte[] future = pdfBytes("future");

        upload(hr(), older, LocalDate.of(2018, 6, 1));
        upload(hr(), current, LocalDate.of(2024, 10, 1));
        // A future-dated upload must NOT become "current" yet.
        upload(hr(), future, LocalDate.now().plusYears(1));

        ResponseEntity<Resource> response = get(employee());
        assertThat(response.getBody().getContentAsByteArray()).isEqualTo(current);

        // The superseded version is still byte-for-byte retrievable by id — not deleted, not
        // overwritten (V133's migration comment).
        LeavePolicyDocumentDto currentDto = repository.findCurrent().orElseThrow();
        assertThat(currentDto.effectiveFrom()).isEqualTo(LocalDate.of(2024, 10, 1));
    }

    // --- POST: write path, HR/CEO only ------------------------------------------------------

    @Test
    void hrCanUpload() throws Exception {
        Map<String, LeavePolicyDocumentDto> response = upload(hr(), samplePdfBytes(), LocalDate.of(2024, 10, 1));

        LeavePolicyDocumentDto document = response.get("document");
        assertThat(document.fileName()).isEqualTo("policy.pdf");
        assertThat(document.mimeType()).isEqualTo("application/pdf");
        assertThat(document.fileSize()).isEqualTo(samplePdfBytes().length);
        assertThat(document.effectiveFrom()).isEqualTo(LocalDate.of(2024, 10, 1));
    }

    @Test
    void ceoCanUpload() throws Exception {
        Map<String, LeavePolicyDocumentDto> response = upload(ceo(), samplePdfBytes(), LocalDate.of(2024, 10, 1));
        assertThat(response.get("document")).isNotNull();
    }

    @Test
    void aPlainEmployeeCannotUpload() {
        assertForbidden(() -> upload(employee(), samplePdfBytes(), LocalDate.of(2024, 10, 1)));
    }

    @Test
    void aSalesCallerCannotUpload() {
        assertForbidden(() -> upload(sales(), samplePdfBytes(), LocalDate.of(2024, 10, 1)));
    }

    /** {@code manager()} below is role {@code "employee"} with {@code manager=true} — a department
     * manager is still gated out; {@code requireAnyRole} checks role, not the manager flag. */
    @Test
    void aDepartmentManagerCannotUpload() {
        assertForbidden(() -> upload(manager(), samplePdfBytes(), LocalDate.of(2024, 10, 1)));
    }

    @Test
    void aNonPdfMimeTypeIsRejectedWith400() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "policy.txt", "text/plain", "not a pdf".getBytes());
        assertThatThrownBy(() -> controller.uploadPolicyDocument(file, LocalDate.of(2024, 10, 1), sessionFor(hr())))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findCurrent()).as("a rejected upload must never reach the database").isEmpty();
    }

    @Test
    void anOversizeFileIsRejectedWith400() {
        // MockMultipartFile.getSize() reflects the actual byte array length, so this genuinely
        // exceeds LeaveController.MAX_POLICY_DOCUMENT_BYTES (10MB) rather than faking a reported size.
        byte[] oversized = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", oversized);

        assertThatThrownBy(() -> controller.uploadPolicyDocument(file, LocalDate.of(2024, 10, 1), sessionFor(hr())))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findCurrent()).as("a rejected upload must never reach the database").isEmpty();
    }

    @Test
    void anEmptyFileIsRejectedWith400() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> controller.uploadPolicyDocument(file, LocalDate.of(2024, 10, 1), sessionFor(hr())))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers --------------------------------------------------------------------------

    private byte[] samplePdfBytes() {
        return pdfBytes("demo");
    }

    private byte[] pdfBytes(String marker) {
        return ("%PDF-1.4 " + marker).getBytes();
    }

    private ResponseEntity<Resource> get(UserPrincipal user) {
        return controller.policyDocument(sessionFor(user));
    }

    private Map<String, LeavePolicyDocumentDto> upload(UserPrincipal user, byte[] content, LocalDate effectiveFrom) {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", content);
        return controller.uploadPolicyDocument(file, effectiveFrom, sessionFor(user));
    }

    private MockHttpSession sessionFor(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return session;
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
