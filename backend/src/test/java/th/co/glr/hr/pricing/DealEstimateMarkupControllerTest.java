package th.co.glr.hr.pricing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Proves the DECISION for {@code /api/deal-estimate-markup} (V112): read is authentication-only,
 * write is CEO-only. {@code DealEstimateMarkupAuthzIntegrationTest} proves the ENFORCEMENT on real
 * Postgres through the real repository — Mockito cannot reach that.
 */
class DealEstimateMarkupControllerTest {
    private static final List<String> ALL_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account", "ceo", "import");

    private final DealEstimateMarkupRepository markup = mock(DealEstimateMarkupRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DealEstimateMarkupController(markup, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void getIsAllowedForAnyAuthenticatedRole() throws Exception {
        when(markup.findCurrent())
            .thenReturn(Optional.of(new DealEstimateMarkupDto(new BigDecimal("2.000"), Instant.now(), null)));
        for (String role : ALL_ROLES) {
            mvc.perform(get("/api/deal-estimate-markup").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void getIsUnauthorizedWithoutASession() throws Exception {
        mvc.perform(get("/api/deal-estimate-markup")).andExpect(status().isUnauthorized());
    }

    // D8: queryForObject raises EmptyResultDataAccessException if the seeded singleton row is
    // ever absent, which used to propagate straight out of the repository as a 500 on a read
    // every deal-create modal now performs. findCurrent() now returns Optional.empty() for that
    // case and the controller must degrade to a 2xx with a null payload — the frontend's
    // estimateReady already treats a null multiplier as "not ready" and hides the section, so
    // there is nothing left for a rep to trip over.
    @Test
    void getDegradesGracefullyWhenTheSeededRowIsMissing() throws Exception {
        when(markup.findCurrent()).thenReturn(Optional.empty());
        mvc.perform(get("/api/deal-estimate-markup").session(session("ceo")))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.dealEstimateMarkup").value(nullValue()));
    }

    @Test
    void nonCeoCannotWriteTheMultiplier() throws Exception {
        for (String role : List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account", "import")) {
            mvc.perform(put("/api/deal-estimate-markup")
                    .session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"multiplier": 3.5}
                        """))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void ceoCanWriteTheMultiplier() throws Exception {
        when(markup.update(eq(new BigDecimal("3.5")), any()))
            .thenReturn(new DealEstimateMarkupDto(new BigDecimal("3.500"), Instant.now(), 1L));
        mvc.perform(put("/api/deal-estimate-markup")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 3.5}
                    """))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void rejectsAZeroOrNegativeMultiplier() throws Exception {
        mvc.perform(put("/api/deal-estimate-markup")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 0}
                    """))
            .andExpect(status().isBadRequest());
    }

    // D6: V112's column is `NUMERIC(6,3) CHECK (multiplier > 0)` — 6 total digits, 3 after the
    // decimal point. 1000 has 4 integer digits and does not fit, so before
    // UpdateDealEstimateMarkupRequest gained a @DecimalMax this sailed past request validation
    // and reached Postgres, which threw a DataAccessException surfaced to the CEO as a raw 500.
    @Test
    void rejectsAMultiplierThatWouldOverflowNumeric6_3() throws Exception {
        mvc.perform(put("/api/deal-estimate-markup")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 1000}
                    """))
            .andExpect(status().isBadRequest());
    }

    // D6 converse: a value below NUMERIC(6,3)'s smallest representable step rounds to 0.000 on
    // the way into Postgres, which then trips the same `CHECK (multiplier > 0)` as an explicit
    // zero — but as a 500 from the database rather than a clean 400 from request validation.
    @Test
    void rejectsAMultiplierThatWouldRoundToZeroUnderNumeric6_3() throws Exception {
        mvc.perform(put("/api/deal-estimate-markup")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 0.0001}
                    """))
            .andExpect(status().isBadRequest());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
