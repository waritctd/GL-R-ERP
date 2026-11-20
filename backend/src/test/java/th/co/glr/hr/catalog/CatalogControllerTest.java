package th.co.glr.hr.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PriceImportService priceImport = mock(PriceImportService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CatalogController(catalog, priceImport, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void employeeCannotAddProduct() throws Exception {
        mvc.perform(post("/api/catalog/prices")
                .session(session("employee"))
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
