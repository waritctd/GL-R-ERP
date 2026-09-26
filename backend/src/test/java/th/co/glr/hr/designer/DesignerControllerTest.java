package th.co.glr.hr.designer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * ⚠️ REVERSAL (owner ask relayed 2026-09-26, task "designer-add-from-ui"): this class used to be
 * titled "Read-only by construction" and its {@code thereIsNoWriteEndpointOnThisController} test
 * asserted POST/PUT/DELETE all 405. POST now exists (see {@link DesignerController#create}) --
 * {@link #thereIsNoUpdateOrDeleteEndpointOnThisController} below keeps pinning PUT/DELETE as 405
 * (still true -- only create was asked for), and the {@code create*} tests below cover the new
 * endpoint's authz gate and validation with a MOCKED repository (cheap, fast). Real-DB evidence for
 * the authz gate itself is {@code DesignerCreateAuthzIntegrationTest} -- Mockito cannot prove a
 * grant survives into the actual SQL/service call, per CLAUDE.md's "Permission changes must ship
 * evidence".
 *
 * <p>{@link #search}/{@link #getByCode} stay open to any authenticated role, same product decision
 * as {@code CatalogController.search} (#205) -- see {@link DesignerController}'s own Javadoc.
 */
class DesignerControllerTest {
    private static final List<String> ALL_ROLES = List.of(
        "employee", "warehouse", "qc", "hr", "sales", "sales_manager", "import", "ceo", "account");
    private static final List<String> DEAL_ENTRY_ROLES = List.of("sales", "sales_manager");
    private static final List<String> NON_DEAL_ENTRY_ROLES = List.of(
        "employee", "warehouse", "qc", "hr", "import", "ceo", "account");

    private final DesignerRepository designers = mock(DesignerRepository.class);
    // Unstubbed -> canCreateQuotation() defaults to false (Mockito), so a role reaches create()
    // here only via its OWN role (sales/sales_manager) or an explicit per-test stub -- never a
    // phantom grant, mirroring CustomerControllerTest's own employeeAuth wiring.
    private final EmployeeAuthRepository employeeAuth = mock(EmployeeAuthRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DesignerController(designers, new SessionContext(), employeeAuth))
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

    /** POST now exists (see class Javadoc) -- only PUT/DELETE still 405. */
    @Test
    void thereIsNoUpdateOrDeleteEndpointOnThisController() throws Exception {
        mvc.perform(put("/api/designers/A001").session(session("ceo"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/designers/A001").session(session("ceo"))).andExpect(status().isMethodNotAllowed());
    }

    // ── create (reversal, see class Javadoc) ──────────────────────────────────────────────

    @Test
    void createIsAllowedForSalesAndSalesManager() throws Exception {
        when(designers.create("A200", "NEW DESIGN STUDIO")).thenReturn(new DesignerDto("A200", "NEW DESIGN STUDIO", true));
        for (String role : DEAL_ENTRY_ROLES) {
            mvc.perform(post("/api/designers").session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"A200\",\"name\":\"NEW DESIGN STUDIO\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(content().json("{\"designer\":{\"code\":\"A200\",\"name\":\"NEW DESIGN STUDIO\",\"active\":true}}"));
        }
    }

    @Test
    void createIsAllowedForAGrantedQc() throws Exception {
        when(employeeAuth.canCreateQuotation(anyLong())).thenReturn(true);
        when(designers.create("A201", "GRANTED QC STUDIO")).thenReturn(new DesignerDto("A201", "GRANTED QC STUDIO", true));
        mvc.perform(post("/api/designers").session(session("qc"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"A201\",\"name\":\"GRANTED QC STUDIO\"}"))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void createIsForbiddenForNonDealEntryRoles() throws Exception {
        for (String role : NON_DEAL_ENTRY_ROLES) {
            mvc.perform(post("/api/designers").session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"A202\",\"name\":\"SHOULD NOT BE CREATED\"}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void createRejectsABlankCodeOrName() throws Exception {
        mvc.perform(post("/api/designers").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"NEW DESIGN STUDIO\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/designers").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"A203\",\"name\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createOnADuplicateCodeIs409() throws Exception {
        when(designers.create(eq("A001"), any())).thenThrow(new DuplicateKeyException("sales.designer_pkey"));
        mvc.perform(post("/api/designers").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"A001\",\"name\":\"ABACUS DESIGN CO.,LTD\"}"))
            .andExpect(status().isConflict());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
