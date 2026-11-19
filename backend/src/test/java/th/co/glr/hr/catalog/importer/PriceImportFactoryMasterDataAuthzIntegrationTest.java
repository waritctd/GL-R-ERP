package th.co.glr.hr.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Factory master-data write endpoints — POST/PUT /api/price-import/factories and GET
 * /api/price-import/countries — added/fixed alongside the price_catalog.factories / sales.factory_config merge
 * (V163). Country is now REQUIRED and validated against price_catalog.country up front. */
class PriceImportFactoryMasterDataAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Everyone outside PriceImportController.requireImporter's ceo/import gate. */
    private static final List<String> DENIED_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private MockMvc mvc;

    @BeforeEach
    void wireRealCollaborators() {
        SessionContext sessions = new SessionContext();

        // ImportEngine (file-parsing) is mocked -- irrelevant to every test in this class, which never touches /upload
        // or /upload-commit. Everything else here is real: real jdbc (from AbstractPostgresIntegrationTest), real
        // FactoryConfigRepository, real PriceImportService.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        PriceImportService service =
            new PriceImportService(mock(ImportEngine.class), jdbc, objectMapper, new FactoryConfigRepository(jdbc));

        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();
        mvc = MockMvcBuilders.standaloneSetup(new PriceImportController(service, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── create: authz, wrong-way-round ───────────────────────────────────────────────────────

    @Test
    void nonImportCeoRolesCannotCreateAFactory() throws Exception {
        for (String role : DENIED_ROLES) {
            mvc.perform(post("/api/price-import/factories").session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name": "Should Not Be Created", "country": "TH", "defaultCurrency": "THB"}
                        """))
                .andExpect(status().isForbidden());
        }
        assertThat(countFactoriesNamed("Should Not Be Created"))
            .as("a denied role's request must not have created the row despite the 403")
            .isZero();
    }

    // ── create: the permitted roles reach a REAL row, and validation actually fires ──────────

    @Test
    void importAndCeoCanCreateAFactoryWithARealCountryIncludingTheNewEmailAndUnitFields() throws Exception {
        mvc.perform(post("/api/price-import/factories").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "New Factory Import", "country": "th", "defaultCurrency": "thb",
                     "email": "buy@newfactory.example", "unit": "sqm"}
                    """))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.country").value("TH"))
            .andExpect(jsonPath("$.defaultCurrency").value("THB"))
            .andExpect(jsonPath("$.email").value("buy@newfactory.example"))
            .andExpect(jsonPath("$.unit").value("sqm"));
        assertThat(countFactoriesNamed("New Factory Import")).isEqualTo(1L);

        mvc.perform(post("/api/price-import/factories").session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "New Factory Ceo", "country": "VN", "defaultCurrency": "USD"}
                    """))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.unit").value("piece"));
        assertThat(countFactoriesNamed("New Factory Ceo")).isEqualTo(1L);
    }

    // ── create: 'ZZ' (อื่นๆ) / countryOther pairing (V184, owner decision 09-18) ───────────────

    @Test
    void createFactoryRejectsADuplicateNameWith409NotA500() throws Exception {
        seedFactory("Duplicate Name Factory", "TH", "THB");
        mvc.perform(post("/api/price-import/factories").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Duplicate Name Factory", "country": "VN", "defaultCurrency": "USD"}
                    """))
            .andExpect(status().isConflict());
        assertThat(countFactoriesNamed("Duplicate Name Factory")).isEqualTo(1L);
    }

    // create: currency/email/unit length validation ───────── The country fix above closed ONE way this endpoint
    // 500'd on bad input, but normalizeCurrency only uppercased/trimmed — it never checked length — and email/unit
    // had no length check at all.

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private long seedFactory(String name, String countryCode, String currency) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, :country, :currency)
            RETURNING factory_id
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("country", countryCode)
                .addValue("currency", currency),
            Long.class);
    }

    private long countFactoriesNamed(String name) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM price_catalog.factories WHERE name = :name",
            Map.of("name", name), Long.class);
    }

    private static MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test " + role, role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
