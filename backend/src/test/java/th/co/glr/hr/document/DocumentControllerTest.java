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

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(6L, "sales@glr.co.th", "Sales", "sales", 6L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
