package th.co.glr.hr.designer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Read-only by construction: this controller has no write mapping at all (no
 * {@code @PostMapping}/{@code @PutMapping}/{@code @DeleteMapping}), which the
 * {@link #thereIsNoWriteEndpointOnThisController} test below pins by asserting POST/PUT/DELETE
 * against its base path all 405 rather than reaching any handler -- the owner's "อ่านอย่างเดียว
 * อัปเดตจาก Excel" ruling should fail loudly if anyone ever adds one back.
 *
 * <p>Open to any authenticated role, same product decision as {@code CatalogController.search}
 * (#205) -- see {@link DesignerController}'s own Javadoc.
 */
class DesignerControllerTest {
    private static final List<String> ALL_ROLES = List.of(
        "employee", "warehouse", "qc", "hr", "sales", "sales_manager", "import", "ceo", "account");

    private final DesignerRepository designers = mock(DesignerRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DesignerController(designers, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void searchIsOpenToAnyAuthenticatedRole() throws Exception {
        when(designers.search(any())).thenReturn(List.of());
        for (String role : ALL_ROLES) {
            mvc.perform(get("/api/designers").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mvc.perform(get("/api/designers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/designers/A001")).andExpect(status().isUnauthorized());
    }

    @Test
    void getByCodeResolvesEvenAnInactiveDesigner() throws Exception {
        when(designers.findByCode("A060")).thenReturn(Optional.of(new DesignerDto("A060", "ABACUS DESIGN CO.,LTD", false)));
        mvc.perform(get("/api/designers/A060").session(session("sales")))
            .andExpect(status().is2xxSuccessful())
            // The response DOES carry the name -- the picker needs it to show who is selected.
            // The confidentiality guarantee is that this name never reaches a RENDERED document,
            // pinned separately by DesignerNameNeverReachesRenderedQuotationTest, not that the
            // read-only lookup API withholds it from the app that is building the picker UI.
            .andExpect(content().json("{\"code\":\"A060\",\"name\":\"ABACUS DESIGN CO.,LTD\",\"active\":false}"));
    }

    @Test
    void getByCodeIsNotFoundForAnUnknownCode() throws Exception {
        when(designers.findByCode("ZZZZ")).thenReturn(Optional.empty());
        mvc.perform(get("/api/designers/ZZZZ").session(session("sales")))
            .andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoWriteEndpointOnThisController() throws Exception {
        mvc.perform(post("/api/designers").session(session("ceo"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/designers/A001").session(session("ceo"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/designers/A001").session(session("ceo"))).andExpect(status().isMethodNotAllowed());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
