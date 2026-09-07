package th.co.glr.hr.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * Factory master-data write endpoints — {@code POST}/{@code PUT /api/price-import/factories} and
 * {@code GET /api/price-import/countries} — added/fixed alongside the price_catalog.factories /
 * sales.factory_config merge (V163). {@code createFactory} used to pass {@code country = null} on
 * a blank input; V151 made {@code price_catalog.factories.country} NOT NULL + FK, so that raised a
 * bare 500 via {@code ApiExceptionHandler.handleDataAccess} ("cannot add a factory") instead of a
 * clean 400. Country is now REQUIRED and validated against {@code price_catalog.country} up front.
 *
 * <p>Runs the REAL {@link PriceImportController} over the REAL {@link PriceImportService} /
 * {@link FactoryConfigRepository} on REAL Postgres, per CLAUDE.md's "Permission changes must ship
 * evidence": a Mockito-mocked repository passes happily while the SQL does something else, and
 * this repo has been bitten by exactly that. {@code AttendanceScopeIntegrationTest} is the
 * reference shape; {@code CatalogPricingReadAuthzIntegrationTest} is the closest sibling (same
 * real-controller-over-real-Postgres MockMvc technique, one package over).
 *
 * <p>Every denial case is written <strong>wrong way round</strong>: it asks whether a caller who
 * should NOT reach the write can reach it — and additionally confirms no row was created/changed
 * as a side effect — rather than only confirming the permitted roles can reach their own writes.
 */
class PriceImportFactoryMasterDataAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Everyone outside PriceImportController.requireImporter's ceo/import gate. */
    private static final List<String> DENIED_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private MockMvc mvc;

    @BeforeEach
    void wireRealCollaborators() {
        SessionContext sessions = new SessionContext();

        // ImportEngine (file-parsing) is mocked -- irrelevant to every test in this class, which
        // never touches /upload or /upload-commit. Everything else here is real: real jdbc (from
        // AbstractPostgresIntegrationTest), real FactoryConfigRepository, real PriceImportService.
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

    @Test
    void anonymousCallerIsRejectedOnEveryWriteEndpointAndTheCountryPicker() throws Exception {
        mvc.perform(post("/api/price-import/factories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Anon\", \"country\": \"TH\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/price-import/factories/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Anon\", \"country\": \"TH\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/price-import/countries"))
            .andExpect(status().isUnauthorized());
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

    /**
     * THE FIX: {@code country = null} used to reach the NOT NULL + FK column and 500. Now a
     * clean 400, and — the part a status-code-only assertion would miss — no half-written row.
     */
    @Test
    void createFactoryRejectsABlankCountryWith400NotA500() throws Exception {
        mvc.perform(post("/api/price-import/factories").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "No Country Factory", "defaultCurrency": "THB"}
                    """))
            .andExpect(status().isBadRequest());
        assertThat(countFactoriesNamed("No Country Factory")).isZero();
    }

    @Test
    void createFactoryRejectsAnUnknownCountryCodeWith400NotA500() throws Exception {
        mvc.perform(post("/api/price-import/factories").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Bad Country Factory", "country": "ZZ", "defaultCurrency": "THB"}
                    """))
            .andExpect(status().isBadRequest());
        assertThat(countFactoriesNamed("Bad Country Factory")).isZero();
    }

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

    // ── create: currency/email/unit length validation (MEDIUM 5, review remediation) ─────────
    //
    // The country fix above (createFactoryRejectsABlankCountryWith400NotA500 /
    // ...UnknownCountryCodeWith400NotA500) closed ONE way this endpoint 500'd on bad input, but
    // normalizeCurrency only uppercased/trimmed — it never checked length — and email/unit had no
    // length check at all. default_currency is CHAR(3), email is VARCHAR(200), unit is VARCHAR(30)
    // (V163): an out-of-range value for any of the three still reached Postgres, raised a 22001
    // ("value too long"/mismatched fixed-length), and came back out through
    // ApiExceptionHandler.handleDataAccess as the exact bare 500 this whole change exists to
    // remove. These pin the same "clean 400, no half-written row" shape for all three.

    @Test
    void createFactoryRejectsAnInvalidLengthCurrencyWith400NotA500() throws Exception {
        // "EURO" (4 chars) would 22001 outright; "TH" (2 chars) would not error at all — CHAR(3)
        // BLANK-PADS a too-short value instead of rejecting it, silently storing "TH " with a
        // trailing space. Both must be refused up front.
        for (String badCurrency : List.of("EURO", "TH")) {
            mvc.perform(post("/api/price-import/factories").session(session("import"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name": "Bad Currency Factory", "country": "TH", "defaultCurrency": "%s"}
                        """.formatted(badCurrency)))
                .andExpect(status().isBadRequest());
        }
        assertThat(countFactoriesNamed("Bad Currency Factory")).isZero();
    }

    @Test
    void createFactoryRejectsAnOverLongEmailWith400NotA500() throws Exception {
        String overLongEmail = "a".repeat(195) + "@x.com"; // 201 chars, one over VARCHAR(200)
        mvc.perform(post("/api/price-import/factories").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Bad Email Factory", "country": "TH", "defaultCurrency": "THB", "email": "%s"}
                    """.formatted(overLongEmail)))
            .andExpect(status().isBadRequest());
        assertThat(countFactoriesNamed("Bad Email Factory")).isZero();
    }

    @Test
    void createFactoryRejectsAnOverLongUnitWith400NotA500() throws Exception {
        String overLongUnit = "u".repeat(31); // one over VARCHAR(30)
        mvc.perform(post("/api/price-import/factories").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Bad Unit Factory", "country": "TH", "defaultCurrency": "THB", "unit": "%s"}
                    """.formatted(overLongUnit)))
            .andExpect(status().isBadRequest());
        assertThat(countFactoriesNamed("Bad Unit Factory")).isZero();
    }

    // ── update: authz, wrong-way-round ───────────────────────────────────────────────────────

    @Test
    void nonImportCeoRolesCannotUpdateAFactory() throws Exception {
        long factoryId = seedFactory("Update Target Factory", "IT", "EUR");
        for (String role : DENIED_ROLES) {
            mvc.perform(put("/api/price-import/factories/" + factoryId).session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name": "Should Not Update", "country": "IT", "defaultCurrency": "EUR"}
                        """))
                .andExpect(status().isForbidden());
        }
        assertThat(factoryName(factoryId))
            .as("a denied role's request must not have renamed the row despite the 403")
            .isEqualTo("Update Target Factory");
    }

    // ── update: the permitted roles reach a REAL row, and validation actually fires ──────────

    @Test
    void importAndCeoCanUpdateAFactory() throws Exception {
        long factoryId = seedFactory("Update Target Factory Two", "IT", "EUR");

        mvc.perform(put("/api/price-import/factories/" + factoryId).session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Renamed Factory", "country": "vn", "defaultCurrency": "usd",
                     "email": "new@renamed.example", "unit": "box"}
                    """))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.name").value("Renamed Factory"))
            .andExpect(jsonPath("$.country").value("VN"))
            .andExpect(jsonPath("$.email").value("new@renamed.example"));
        assertThat(jdbc.queryForObject(
            "SELECT unit FROM price_catalog.factories WHERE factory_id = :id",
            Map.of("id", factoryId), String.class)).isEqualTo("box");

        mvc.perform(put("/api/price-import/factories/" + factoryId).session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Renamed Factory Again", "country": "TH", "defaultCurrency": "THB"}
                    """))
            .andExpect(status().is2xxSuccessful());
        assertThat(factoryName(factoryId)).isEqualTo("Renamed Factory Again");
    }

    @Test
    void updateFactoryReturns404ForAnUnknownId() throws Exception {
        mvc.perform(put("/api/price-import/factories/999999999").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Whatever", "country": "TH", "defaultCurrency": "THB"}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateFactoryReturns400ForAnInvalidCountryNotA500() throws Exception {
        long factoryId = seedFactory("Country Validation Factory", "IT", "EUR");
        mvc.perform(put("/api/price-import/factories/" + factoryId).session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Country Validation Factory", "country": "ZZ", "defaultCurrency": "EUR"}
                    """))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
            "SELECT country FROM price_catalog.factories WHERE factory_id = :id",
            Map.of("id", factoryId), String.class)).isEqualTo("IT");
    }

    @Test
    void updateFactoryReturns409ForADuplicateNameNotA500() throws Exception {
        seedFactory("Existing Name Holder", "IT", "EUR");
        long secondFactoryId = seedFactory("Second Factory To Rename", "IT", "EUR");

        mvc.perform(put("/api/price-import/factories/" + secondFactoryId).session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Existing Name Holder", "country": "IT", "defaultCurrency": "EUR"}
                    """))
            .andExpect(status().isConflict());
        assertThat(factoryName(secondFactoryId)).isEqualTo("Second Factory To Rename");
    }

    // ── update: currency/email/unit length validation (MEDIUM 5, review remediation) ─────────

    @Test
    void updateFactoryReturns400ForAnInvalidLengthCurrencyNotA500() throws Exception {
        long factoryId = seedFactory("Currency Validation Factory", "IT", "EUR");
        for (String badCurrency : List.of("EURO", "TH")) {
            mvc.perform(put("/api/price-import/factories/" + factoryId).session(session("import"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name": "Currency Validation Factory", "country": "IT", "defaultCurrency": "%s"}
                        """.formatted(badCurrency)))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject(
            "SELECT default_currency FROM price_catalog.factories WHERE factory_id = :id",
            Map.of("id", factoryId), String.class)).isEqualTo("EUR");
    }

    @Test
    void updateFactoryReturns400ForAnOverLongEmailNotA500() throws Exception {
        long factoryId = seedFactory("Email Validation Factory", "IT", "EUR");
        String overLongEmail = "a".repeat(195) + "@x.com";
        mvc.perform(put("/api/price-import/factories/" + factoryId).session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Email Validation Factory", "country": "IT", "defaultCurrency": "EUR", "email": "%s"}
                    """.formatted(overLongEmail)))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
            "SELECT email FROM price_catalog.factories WHERE factory_id = :id",
            Map.of("id", factoryId), String.class)).isNull();
    }

    @Test
    void updateFactoryReturns400ForAnOverLongUnitNotA500() throws Exception {
        long factoryId = seedFactory("Unit Validation Factory", "IT", "EUR");
        String overLongUnit = "u".repeat(31);
        mvc.perform(put("/api/price-import/factories/" + factoryId).session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Unit Validation Factory", "country": "IT", "defaultCurrency": "EUR", "unit": "%s"}
                    """.formatted(overLongUnit)))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
            "SELECT unit FROM price_catalog.factories WHERE factory_id = :id",
            Map.of("id", factoryId), String.class)).isEqualTo("piece");
    }

    // ── countries picker: authz ───────────────────────────────────────────────────────────────

    @Test
    void nonImportCeoRolesCannotListCountries() throws Exception {
        for (String role : DENIED_ROLES) {
            mvc.perform(get("/api/price-import/countries").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void importAndCeoCanListRealSeededCountries() throws Exception {
        for (String role : List.of("import", "ceo")) {
            mvc.perform(get("/api/price-import/countries").session(session(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.countryCode == 'TH')].nameEn").exists())
                .andExpect(jsonPath("$[?(@.countryCode == 'IT')].nameTh").exists());
        }
    }

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

    private String factoryName(long factoryId) {
        return jdbc.queryForObject(
            "SELECT name FROM price_catalog.factories WHERE factory_id = :id",
            Map.of("id", factoryId), String.class);
    }

    private static MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test " + role, role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
