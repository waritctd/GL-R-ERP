package th.co.glr.hr.catalog.importer;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

// #205: every /api/price-import endpoint must be ceo/import only — reads included, since
// even listing factories or a factory's raw import profile shouldn't be visible to a plain
// employee or sales user. `commit` is the highest-stakes endpoint (flips a version ACTIVE and
// archives the prior one), so it gets its own explicit coverage alongside a representative read.
class PriceImportControllerTest {
    private final PriceImportService service = mock(PriceImportService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new PriceImportController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void employeeCannotListFactories() throws Exception {
        mvc.perform(get("/api/price-import/factories").session(session("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCannotListFactories() throws Exception {
        mvc.perform(get("/api/price-import/factories").session(session("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanListFactories() throws Exception {
        when(service.listFactories()).thenReturn(List.of());
        mvc.perform(get("/api/price-import/factories").session(session("ceo")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void importCanListFactories() throws Exception {
        when(service.listFactories()).thenReturn(List.of());
        mvc.perform(get("/api/price-import/factories").session(session("import")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void employeeCannotCommit() throws Exception {
        mvc.perform(post("/api/price-import/commit/1").session(session("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCannotCommit() throws Exception {
        mvc.perform(post("/api/price-import/commit/1").session(session("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanCommit() throws Exception {
        when(service.commit(anyLong(), anyLong()))
            .thenReturn(new PriceImportService.CommitResult(1L, 10, 2, 1));
        mvc.perform(post("/api/price-import/commit/1").session(session("ceo")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void importCanCommit() throws Exception {
        when(service.commit(anyLong(), anyLong()))
            .thenReturn(new PriceImportService.CommitResult(1L, 10, 2, 1));
        mvc.perform(post("/api/price-import/commit/1").session(session("import")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void factoriesRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/price-import/factories"))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
