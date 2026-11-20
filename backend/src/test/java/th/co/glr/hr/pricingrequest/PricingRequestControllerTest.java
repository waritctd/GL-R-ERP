package th.co.glr.hr.pricingrequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;

class PricingRequestControllerTest {
    private final PricingRequestService service = mock(PricingRequestService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new PricingRequestController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    // ── create ───────────────────────────────────────────────────────────

    @Test
    void createDraft_returns201OnSuccess() throws Exception {
        when(service.createDraft(eq(10L), any(), any())).thenReturn(sampleDetail());
        mvc.perform(post("/api/tickets/10/pricing-requests").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isCreated());
    }

    @Test
    void createDraft_rejectsWrongRole() throws Exception {
        when(service.createDraft(eq(10L), any(), any()))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"));
        mvc.perform(post("/api/tickets/10/pricing-requests").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isForbidden());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String validCreateBody() {
        return """
            {
              "recipientType": "BUYER",
              "recipientContactId": 1,
              "clientRequestId": "55555555-5555-5555-5555-555555555555",
              "items": [
                {
                  "requestedQty": 1,
                  "requestedUnit": "PIECE",
                  "requestedUnitBasis": "PER_PIECE",
                  "quantityType": "REFERENCE"
                }
              ]
            }
            """;
    }

    private PricingRequestDetailDto sampleDetail() {
        PricingRequestSummaryDto summary = new PricingRequestSummaryDto(
            20L, "PCR-2026-0001", 10L, "PR-2026-0001", "Test Project", "Test Customer",
            1L, PricingRequestRecipient.BUYER, 1L, null,
            PricingRequestStatus.DRAFT, 1L, "Sales User", null, null, null, null, null, null,
            1, 1, null, null, null, null, Instant.now(), Instant.now(), null);
        return new PricingRequestDetailDto(summary, List.of(), List.of());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
