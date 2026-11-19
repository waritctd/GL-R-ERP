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

/** GET/POST /api/leave/policy-document against a real, freshly-migrated Postgres schema (V133). leaveService is
 * never constructed (it is irrelevant to either endpoint under test), matching LeaveController's constructor
 * taking it only for the OTHER endpoints on this controller. */
class LeaveControllerPolicyDocumentIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveController controller;
    private LeavePolicyDocumentRepository repository;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new LeavePolicyDocumentRepository(jdbc);
        controller = new LeaveController(
            /* leaveService */ null, new SessionContext(), repository, /* calendarContextService */ null,
            /* attachmentBlobs */ null, /* leaveReportRenderer */ null);
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
    void aPlainEmployeeCannotUpload() {
        assertForbidden(() -> upload(employee(), samplePdfBytes(), LocalDate.of(2024, 10, 1)));
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

}
