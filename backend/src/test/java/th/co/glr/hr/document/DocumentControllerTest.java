package th.co.glr.hr.document;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

class DocumentControllerTest {
    private final DocumentService service = mock(DocumentService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DocumentController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void noteTemplatesRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/document-note-templates"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void noteTemplatesReturnsTemplatesForAuthenticatedUser() throws Exception {
        when(service.getNoteTemplates()).thenReturn(List.of(
            new DocumentNoteTemplateDto(1L, "ชำระมัดจำ", true, 1)));

        mvc.perform(get("/api/document-note-templates").session(session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templates[0].id").value(1))
            .andExpect(jsonPath("$.templates[0].text").value("ชำระมัดจำ"));
    }

    @Test
    void listByTicketRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/tickets/1/documents"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void listByTicketReturnsDocumentsForAuthenticatedUser() throws Exception {
        when(service.listByTicket(1L)).thenReturn(List.of(doc(10L, 1L)));

        mvc.perform(get("/api/tickets/1/documents").session(session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documents[0].id").value(10))
            .andExpect(jsonPath("$.documents[0].customerName").value("ลูกค้า"));
    }

    @Test
    void getDocRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/documents/10"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void getDocReturnsDocumentForAuthenticatedUser() throws Exception {
        when(service.getById(10L)).thenReturn(doc(10L, 1L));

        mvc.perform(get("/api/documents/10").session(session()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.document.id").value(10))
            .andExpect(jsonPath("$.document.customerTaxId").value("0105500000001"));
    }

    private DocumentDto doc(long id, long ticketId) {
        return new DocumentDto(
            id, ticketId, "QUOTATION", 1, "QT-2026-001", LocalDate.of(2026, 1, 1), "DRAFT",
            "ลูกค้า", "0105500000001", "กรุงเทพฯ", "Project", "REF-1", "THB",
            null, null, null, null, null, null,
            List.of(), false, false, null, "Sales", null, null, List.of());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(6L, "sales@glr.co.th", "Sales", "sales", 6L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
