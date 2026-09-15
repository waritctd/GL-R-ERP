package th.co.glr.hr.customer;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.*;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.location.*;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

class CustomerThaiAddressIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test
    void datasetAndHierarchyValidationAndLegacyRoundTrip() throws Exception {
        var repo = new CustomerRepository(jdbc);
        var locations = new ThaiLocationRepository(jdbc);
        assertThat(locations.provinces()).hasSize(77);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer WHERE province_code IS NULL AND address IS DISTINCT FROM legacy_address", java.util.Map.of(), Long.class)).isZero();
        assertThat(locations.districts("10")).allMatch(d -> d.provinceCode().equals("10"));
        assertThat(locations.subdistricts("1039")).hasSize(3).allMatch(s -> s.districtCode().equals("1039"));
        var contactRepo = new ContactRepository(jdbc);
        var projectRepo = new ProjectRepository(jdbc);
        var auth = new EmployeeAuthRepository(jdbc);
        var mvc = MockMvcBuilders.standaloneSetup(new CustomerController(repo,contactRepo,projectRepo,
            new CustomerService(repo,contactRepo,projectRepo,auth,new DealQuotationRepository(jdbc,new CatalogRepository(jdbc))),new SessionContext(),auth),
            new ThaiLocationController(locations,new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        var session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,new UserPrincipal(1L,"test@example.com","Test","sales",1L,
            true,LocalDate.of(2026,1,1),false,1L,false));
        mvc.perform(get("/api/locations/provinces")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/locations/districts/1039/subdistricts").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.code == '103901')].postalCodes[0]").value("10110"));
        for (String bad : new String[] {
            "\"provinceCode\":\"10\",\"districtCode\":\"5001\",\"subdistrictCode\":\"500108\"",
            "\"provinceCode\":\"10\",\"districtCode\":\"1039\",\"subdistrictCode\":\"100101\"",
            "\"provinceCode\":\"99\",\"districtCode\":\"1039\",\"subdistrictCode\":\"103901\"",
            "\"provinceCode\":\"10\",\"districtCode\":\"1039\",\"subdistrictCode\":\"103901\",\"postalCode\":\"99999\"",
            "\"provinceCode\":\"10\""
        }) {
            mvc.perform(post("/api/customers").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Invalid address\","+bad+"}")).andExpect(status().isBadRequest());
        }
        assertThat(repo.search("Invalid address")).isEmpty();
        String fields = "\"addressLine\":\"88/8 ถนนสุขุมวิท 21\",\"provinceCode\":\"10\",\"districtCode\":\"1039\",\"subdistrictCode\":\"103901\"";
        mvc.perform(post("/api/customers").session(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Structured test\","+fields+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customer.postalCode").value("10110"))
            .andExpect(jsonPath("$.customer.address").value("88/8 ถนนสุขุมวิท 21 แขวงคลองเตยเหนือ เขตวัฒนา กรุงเทพมหานคร 10110"));
        var structured = repo.search("Structured test").getFirst();
        repo.update(structured.id(),null,null,null,null,"02-123");
        assertThat(repo.findById(structured.id()).orElseThrow().subdistrictCode()).isEqualTo("103901");
        String old = "ที่อยู่เก่า ไม่มีโครงสร้าง\nบรรทัดสอง";
        var legacy = repo.create("Legacy test",null,old,"สำนักงานใหญ่",null);
        assertThat(repo.findById(legacy.id()).orElseThrow().address()).isEqualTo(old);
        mvc.perform(put("/api/customers/"+legacy.id()).session(session).contentType(MediaType.APPLICATION_JSON)
            .content("{"+fields+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customer.legacyAddress").value(old));
        mvc.perform(put("/api/customers/"+legacy.id()).session(session).contentType(MediaType.APPLICATION_JSON)
            .content("{\"provinceCode\":\"50\",\"districtCode\":\"1039\",\"subdistrictCode\":\"103901\"}"))
            .andExpect(status().isBadRequest());
        assertThat(repo.findById(legacy.id()).orElseThrow().provinceCode()).isEqualTo("10");
        repo.update(legacy.id(),null,null,"new free text",null,null);
        assertThat(repo.findById(legacy.id()).orElseThrow().provinceCode()).isNull();
        assertThat(repo.findById(legacy.id()).orElseThrow().legacyAddress()).isEqualTo(old);
        // Idempotent upsert seed, including postcode relations.
        try (var connection = java.util.Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource()).getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                new org.springframework.core.io.ClassPathResource("db/migration/R__thai_locations.sql"));
        }
        assertThat(locations.provinces()).hasSize(77);
        assertThat(locations.subdistricts("1039").getFirst().postalCodes()).containsExactly("10110");
        jdbc.update("INSERT INTO customers.thai_subdistrict_postal_codes VALUES ('103901','10111')", java.util.Map.of());
        assertThatThrownBy(() -> locations.resolve("", "10", "1039", "103901", null)).isInstanceOf(th.co.glr.hr.common.ApiException.class);
        assertThat(locations.resolve("", "10", "1039", "103901", "10111").postalCode()).isEqualTo("10111");
        assertThatThrownBy(() -> jdbc.update("UPDATE customers.customer SET province_code='50' WHERE customer_id=:id", java.util.Map.of("id", structured.id())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
