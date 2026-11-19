package th.co.glr.hr.attachment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.ticket.QuotationDto;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventDto;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

class AttachmentControllerTest {
    private static final long TICKET_ID = 10L;
    private static final long ATTACHMENT_ID = 99L;
    private static final long UPLOADER_ID = 1L;
    private static final long CREATOR_ID = 2L;
    private static final long ASSIGNEE_ID = 3L;
    private static final long STRANGER_ID = 4L;

    private final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);

    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new AttachmentController(
            attachmentRepository, new SessionContext(), ticketRepository, auditService, fileStorageService))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void ticketCreatorCanDownload() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(CREATOR_ID, "sales")))
            .andExpect(status().isNotFound()); // access check passed; 404 is from missing file on disk
    }

    /** hr used to sit in this controller's own MANAGER_ROLES and so could read every deal's documents, despite
     * being refused the deal itself everywhere else. These two tests asserted that behaviour; they now assert its

    /** #389's other direction: account confirms the money these documents evidence, so it may now open them — but
     * it may NOT upload, because the tax invoice keeps exactly one entry point (CommissionService#createFromDeal). */
    @Test
    void accountCanListButCannotUpload() throws Exception {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));
        // H1 lockdown: account reads a deal's documents only when the deal is inside its list scope.
        when(ticketRepository.isInAccountScope(TICKET_ID)).thenReturn(true);
        when(attachmentRepository.findByTicketId(TICKET_ID)).thenReturn(List.of(attachment()));

        mvc.perform(get("/api/tickets/{ticketId}/attachments", TICKET_ID).session(session(STRANGER_ID, "account")))
            .andExpect(status().isOk());

        mvc.perform(multipart("/api/tickets/{ticketId}/attachments", TICKET_ID)
                .file(pdfFile())
                .session(session(STRANGER_ID, "account")))
            .andExpect(status().isForbidden());

        verify(attachmentRepository, never()).save(
            eq(TICKET_ID), any(), any(), any(), any(), any(), any(), eq(STRANGER_ID));
    }

    @Test
    void strangerWithSalesRoleForbiddenOnDownload() throws Exception {
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(attachment()));
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket()));

        mvc.perform(get("/api/attachments/{id}/file", ATTACHMENT_ID).session(session(STRANGER_ID, "sales")))
            .andExpect(status().isForbidden());
    }

    // the guard's DECISION (resolveScope-style) — every collaborator mocked, proving the branch
    // AttachmentController#delete takes for a given backsLiveCommission() answer.

    @Test
    void deleteRefusedWhenFileBacksALiveCommission() throws Exception {
        AttachmentDto dto = attachment(); // uploadedBy = UPLOADER_ID -- would otherwise pass access
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(dto));
        when(attachmentRepository.findFilePathById(ATTACHMENT_ID)).thenReturn(missingFilePath());
        when(attachmentRepository.backsLiveCommission(missingFilePath())).thenReturn(true);

        mvc.perform(delete("/api/attachments/{id}", ATTACHMENT_ID).session(session(UPLOADER_ID, "sales")))
            .andExpect(status().isConflict());

        verify(attachmentRepository, never()).delete(ATTACHMENT_ID);
        verify(auditService, never()).record(any(), any(), any(), anyLong(), any(), any());
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

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    private TicketDto ticket() {
        TicketSummaryDto summary = new TicketSummaryDto(
            TICKET_ID, "PR-2026-0001", "SALES", "Ticket title", "approved", "NORMAL",
            CREATOR_ID, "Creator Name",
            ASSIGNEE_ID, "Assignee Name",
            "Customer", null, null, null, null, null,
            null,
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-01T00:00:00Z"),
            null,
            0, false, null, null,
            "LEAD_APPROACH", null, null, Instant.parse("2026-07-01T00:00:00Z"),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        List<TicketItemDto> items = List.of();
        List<TicketEventDto> events = List.of();
        List<QuotationDto> quotations = List.of();
        return new TicketDto(summary, items, events, null, quotations);
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
