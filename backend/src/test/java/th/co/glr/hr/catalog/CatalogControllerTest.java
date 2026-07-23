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

// Catalog browsing (search/searchPrices) is gated to canViewCatalog's exact role set —
// sales/import/ceo/account/sales_manager, mirroring routes.js (added 2026-07-24, Stage L
// follow-up; see CatalogViewerScopeIntegrationTest for the real-DB proof this role gate survives
// into the real repository query, not just this Mockito-mocked decision). The three write
// endpoints (add/update/delete product) stay ceo/import only, unchanged.
class CatalogControllerTest {
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PriceImportService priceImport = mock(PriceImportService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CatalogController(catalog, priceImport, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void searchIsAllowedForCatalogViewerRoles() throws Exception {
        when(catalog.search(any())).thenReturn(List.of());
        for (String role : List.of("sales", "import", "ceo", "account", "sales_manager")) {
            mvc.perform(get("/api/catalog").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void searchIsForbiddenForNonCatalogViewerRoles() throws Exception {
        for (String role : List.of("employee", "hr", "warehouse", "qc")) {
            mvc.perform(get("/api/catalog").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void searchPricesIsAllowedForCatalogViewerRoles() throws Exception {
        when(catalog.searchProductPrices(any(), any(), anyInt())).thenReturn(List.of());
        for (String role : List.of("sales", "import", "ceo", "account", "sales_manager")) {
            mvc.perform(get("/api/catalog/prices").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void searchPricesIsForbiddenForNonCatalogViewerRoles() throws Exception {
        for (String role : List.of("employee", "hr", "warehouse", "qc")) {
            mvc.perform(get("/api/catalog/prices").session(session(role)))
                .andExpect(status().isForbidden());
        }
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
