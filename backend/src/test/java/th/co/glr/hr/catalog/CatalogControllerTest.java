package th.co.glr.hr.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.importer.PriceImportService;
import th.co.glr.hr.common.ApiExceptionHandler;

// Both catalog READS are open to any logged-in user, and both are open BY DECISION rather than by oversight.
// GET /api/catalog/prices -> product-owner ruling of 2026-08-01 closing #388: #205's "browsable by any
// logged-in user" extends to the supplier PURCHASE price this endpoint returns.
class CatalogControllerTest {
    // Every role DivisionAccessPolicy can assign. Both reads must accept all of them.
    private static final List<String> ALL_ROLES = List.of(
        "employee", "warehouse", "qc", "hr", "sales", "sales_manager", "import", "ceo", "account");

    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PriceImportService priceImport = mock(PriceImportService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CatalogController(catalog, priceImport, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    /** PINS ISSUE #205 (product owner, 2026-07-16): "The catalog stays browsable by any logged-in user." Every role
     * reaches GET /api/catalog, ordinary staff included. If this test is failing, someone added a role gate to
     * CatalogController.search. */
    @Test
    void searchStaysOpenToAnyAuthenticatedRole_perIssue205() throws Exception {
        when(catalog.search(any())).thenReturn(List.of());
        for (String role : ALL_ROLES) {
            mvc.perform(get("/api/catalog").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    /** PINS THE PRODUCT-OWNER RULING OF 2026-08-01 that closed issue #388: #205's "browsable by any logged-in user"
     * extends to the supplier PURCHASE price returned here. If this test is failing, someone gated
     * CatalogController.searchPrices. */
    @Test
    void searchPricesStaysOpenToAnyAuthenticatedRole_perIssue388Ruling() throws Exception {
        when(catalog.searchProductPrices(any(), any(), anyInt())).thenReturn(List.of());
        for (String role : ALL_ROLES) {
            mvc.perform(get("/api/catalog/prices").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    /** Open to any *authenticated* user is not open to the public — both reads still need a session. */
    @Test
    void anonymousCallerIsUnauthorizedOnBothReads() throws Exception {
        mvc.perform(get("/api/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalog/prices")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCannotAddProduct() throws Exception {
        mvc.perform(post("/api/catalog/prices")
                .session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCannotAddProduct() throws Exception {
        mvc.perform(post("/api/catalog/prices")
                .session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanAddProduct() throws Exception {
        when(priceImport.addProductManual(anyLong(), any())).thenReturn(1L);
        mvc.perform(post("/api/catalog/prices")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void importCanAddProduct() throws Exception {
        when(priceImport.addProductManual(anyLong(), any())).thenReturn(1L);
        mvc.perform(post("/api/catalog/prices")
                .session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void employeeCannotUpdateProduct() throws Exception {
        mvc.perform(put("/api/catalog/prices/1")
                .session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanUpdateProduct() throws Exception {
        mvc.perform(put("/api/catalog/prices/1")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void employeeCannotDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCannotDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void importCanDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("import")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void ceoCanDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("ceo")))
            .andExpect(status().is2xxSuccessful());
    }

    private String productJson() {
        return """
            {"factoryId": 1, "price": 10.50}
            """;
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
