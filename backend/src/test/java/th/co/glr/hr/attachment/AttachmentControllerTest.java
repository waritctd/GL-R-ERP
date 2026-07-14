package th.co.glr.hr.attachment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.ticket.TicketService;

class AttachmentControllerTest {
    private static final long TICKET_ID = 10L;
    private static final long ATTACHMENT_ID = 99L;
    private static final long UPLOADER_ID = 1L;
    private static final long CREATOR_ID = 2L;
    private static final long STRANGER_ID = 4L;
    private static final long HR_ID = 5L;

    private final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);

    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new AttachmentController(
            attachmentRepository, new SessionContext(), ticketService, auditService, fileStorageService))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void listRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/tickets/{ticketId}/attachments", TICKET_ID))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRequiresAuthentication() throws Exception {
        mvc.perform(delete("/api/attachments/{id}", ATTACHMENT_ID))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void uploaderCanDownloadOwnAttachment() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());

        // uploader is not the ticket creator/assignee and ticket lookup is never needed for them
        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(UPLOADER_ID, "sales")))
            .andExpect(status().isNotFound()); // file missing on disk, but access check passed (not 403)

        verify(ticketService, never()).requireTicketAccess(anyLong(), any(UserPrincipal.class));
    }

    @Test
    void ticketCreatorCanDownload() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(CREATOR_ID, "sales")))
            .andExpect(status().isNotFound()); // access check passed; 404 is from missing file on disk
    }

    @Test
    void importRoleCanDownloadWhenTicketPolicyAllows() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(3L, "import")))
            .andExpect(status().isNotFound());
    }

    @Test
    void managerRoleCanDownloadWhenTicketPolicyAllows() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(HR_ID, "hr")))
            .andExpect(status().isNotFound());
    }

    @Test
    void managerRoleCanListWhenTicketPolicyAllows() throws Exception {
        when(attachmentRepository.findByTicketId(TICKET_ID)).thenReturn(List.of(attachment()));

        mvc.perform(get("/api/tickets/{ticketId}/attachments", TICKET_ID).session(session(HR_ID, "ceo")))
            .andExpect(status().isOk());
    }

    @Test
    void ceoRoleCanDownload() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(HR_ID, "ceo")))
            .andExpect(status().isNotFound());
    }

    @Test
    void strangerWithSalesRoleForbiddenOnDownload() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        denyTicketAccess();

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(STRANGER_ID, "sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void strangerWithSalesRoleForbiddenOnDelete() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        denyTicketAccess();

        mvc.perform(delete("/api/attachments/{id}", ATTACHMENT_ID).session(session(STRANGER_ID, "sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void strangerWithSalesRoleForbiddenOnList() throws Exception {
        denyTicketAccess();

        mvc.perform(get("/api/tickets/{ticketId}/attachments", TICKET_ID).session(session(STRANGER_ID, "sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void strangerWithSalesRoleForbiddenOnUpload() throws Exception {
        denyTicketAccess();
        MockMultipartFile file = new MockMultipartFile(
            "file", "po.pdf", "application/pdf", "content".getBytes());

        mvc.perform(multipart("/api/tickets/{ticketId}/attachments", TICKET_ID)
                .file(file)
                .session(session(STRANGER_ID, "sales")))
            .andExpect(status().isForbidden());

        verify(fileStorageService, never()).store(any(), anyLong(), any(), any());
    }

    @Test
    void deleteByAuthorizedUploaderSucceedsAndAudits() throws Exception {
        AttachmentDto dto = attachment();
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(dto));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());

        UserPrincipal uploader = principal(UPLOADER_ID, "sales");

        mvc.perform(delete("/api/attachments/{id}", ATTACHMENT_ID).session(sessionFor(uploader)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .json("{\"ok\":true}"));

        verify(auditService).record(
            eq(uploader), eq("DELETE_ATTACHMENT"), eq("attachment"), eq(ATTACHMENT_ID), eq(dto), isNull());
    }

    @Test
    void listOnMissingTicketReturns404NotServerError() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"))
            .when(ticketService).requireTicketAccess(eq(TICKET_ID), any(UserPrincipal.class));

        mvc.perform(get("/api/tickets/{ticketId}/attachments", TICKET_ID).session(session(STRANGER_ID, "sales")))
            .andExpect(status().isNotFound());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private AttachmentDto attachment() {
        return new AttachmentDto(
            ATTACHMENT_ID, TICKET_ID, null, "file.pdf", "OTHER",
            "application/pdf", 123L, UPLOADER_ID, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private String missingFilePath() {
        return "/nonexistent/path/does-not-exist.pdf";
    }

    private void denyTicketAccess() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"))
            .when(ticketService).requireTicketAccess(eq(TICKET_ID), any(UserPrincipal.class));
    }

    private MockHttpSession session(long userId, String role) {
        return sessionFor(principal(userId, role));
    }

    private MockHttpSession sessionFor(UserPrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, principal);
        return session;
    }

    private UserPrincipal principal(long userId, String role) {
        return new UserPrincipal(userId, "user" + userId + "@glr.co.th", "User " + userId, role,
            userId, true, LocalDate.of(2026, 1, 1), false, 1L, false);
    }
}
