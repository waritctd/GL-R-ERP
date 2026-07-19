package th.co.glr.hr.pricingrequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    // ── authentication ─────────────────────────────────────────────────────

    @Test
    void createDraft_rejectsWithoutSession() throws Exception {
        mvc.perform(post("/api/tickets/10/pricing-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_rejectsWithoutSession() throws Exception {
        mvc.perform(get("/api/pricing-requests/20"))
            .andExpect(status().isUnauthorized());
    }

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
    void createDraft_rejectsInvalidBody() throws Exception {
        // recipientType is @NotBlank and items is @NotEmpty — an empty body fails
        // bean validation before the controller ever calls the service.
        mvc.perform(post("/api/tickets/10/pricing-requests").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
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

    // ── get ──────────────────────────────────────────────────────────────

    @Test
    void get_returnsNotFoundForUnknownId() throws Exception {
        when(service.get(eq(999L), any())).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
        mvc.perform(get("/api/pricing-requests/999").session(session("sales")))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_returnsOkOnSuccess() throws Exception {
        when(service.get(eq(20L), any())).thenReturn(sampleDetail());
        mvc.perform(get("/api/pricing-requests/20").session(session("sales")))
            .andExpect(status().isOk());
    }

    // ── pickup ───────────────────────────────────────────────────────────

    @Test
    void pickup_returnsOkOnSuccess() throws Exception {
        when(service.pickup(eq(20L), any())).thenReturn(sampleDetail());
        mvc.perform(post("/api/pricing-requests/20/pickup").session(session("import")))
            .andExpect(status().isOk());
    }

    @Test
    void pickup_returnsForbiddenForWrongRole() throws Exception {
        when(service.pickup(eq(20L), any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"));
        mvc.perform(post("/api/pricing-requests/20/pickup").session(session("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void pickup_returnsConflictWhenAlreadyPickedUp() throws Exception {
        when(service.pickup(eq(20L), any()))
            .thenThrow(new ApiException(HttpStatus.CONFLICT, "Pricing request was already picked up"));
        mvc.perform(post("/api/pricing-requests/20/pickup").session(session("import")))
            .andExpect(status().isConflict());
    }

    // ── requestInformation ──────────────────────────────────────────────────

    @Test
    void requestInformation_returnsOkOnSuccess() throws Exception {
        when(service.requestInformation(eq(20L), any(), any())).thenReturn(sampleDetail());
        mvc.perform(post("/api/pricing-requests/20/request-information").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"กรุณาระบุขนาดสินค้าเพิ่มเติม\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void requestInformation_rejectsBlankMessage() throws Exception {
        mvc.perform(post("/api/pricing-requests/20/request-information").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requestInformation_returnsForbiddenWhenNotTheAssignedImport() throws Exception {
        when(service.requestInformation(eq(20L), any(), any()))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"));
        mvc.perform(post("/api/pricing-requests/20/request-information").session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"ขอข้อมูลเพิ่ม\"}"))
            .andExpect(status().isForbidden());
    }

    // ── respondInformation ──────────────────────────────────────────────────

    @Test
    void respondInformation_returnsOkOnSuccess() throws Exception {
        when(service.respondInformation(eq(20L), any(), any())).thenReturn(sampleDetail());
        mvc.perform(post("/api/pricing-requests/20/respond-information").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"response\":\"ขนาด 60x60\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void respondInformation_rejectsBlankResponse() throws Exception {
        mvc.perform(post("/api/pricing-requests/20/respond-information").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"response\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void respondInformation_returnsConflictOnWrongStatus() throws Exception {
        when(service.respondInformation(eq(20L), any(), any()))
            .thenThrow(new ApiException(HttpStatus.CONFLICT,
                "Expected status 'MORE_INFO_REQUIRED' but pricing request is 'IMPORT_REVIEWING'"));
        mvc.perform(post("/api/pricing-requests/20/respond-information").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"response\":\"ขนาด 60x60\"}"))
            .andExpect(status().isConflict());
    }

    // ── submit / cancel: illegal transition ─────────────────────────────────

    @Test
    void submit_returnsConflictOnIllegalTransition() throws Exception {
        when(service.submit(eq(20L), any()))
            .thenThrow(new ApiException(HttpStatus.CONFLICT, "Expected status 'DRAFT' but pricing request is 'SUBMITTED'"));
        mvc.perform(post("/api/pricing-requests/20/submit").session(session("sales")))
            .andExpect(status().isConflict());
    }

    @Test
    void cancel_returnsConflictOnIllegalTransition() throws Exception {
        when(service.cancel(eq(20L), any(), any()))
            .thenThrow(new ApiException(HttpStatus.CONFLICT, "Cannot cancel pricing request in status 'CANCELLED'"));
        mvc.perform(post("/api/pricing-requests/20/cancel").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"ลูกค้ายกเลิก\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void cancel_rejectsBlankReason() throws Exception {
        mvc.perform(post("/api/pricing-requests/20/cancel").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── updateDraft ──────────────────────────────────────────────────────

    @Test
    void updateDraft_returnsOkOnSuccess() throws Exception {
        when(service.updateDraft(eq(20L), any(), any())).thenReturn(sampleDetail());
        mvc.perform(put("/api/pricing-requests/20").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"updated note\"}"))
            .andExpect(status().isOk());
    }

    // ── queue endpoint passes params through ────────────────────────────────

    @Test
    void list_passesQueryParamsThrough() throws Exception {
        when(service.list(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        mvc.perform(get("/api/pricing-requests")
                .param("status", "SUBMITTED")
                .param("assignedImportId", "42")
                .param("activeOnly", "false")
                .session(session("import")))
            .andExpect(status().isOk());
        verify(service).list(eq("SUBMITTED"), eq(42L), eq(false), any());
    }

    @Test
    void list_defaultsActiveOnlyToTrue() throws Exception {
        when(service.list(isNull(), isNull(), anyBoolean(), any())).thenReturn(List.of());
        mvc.perform(get("/api/pricing-requests").session(session("import")))
            .andExpect(status().isOk());
        verify(service).list(isNull(), isNull(), eq(true), any());
    }

    @Test
    void listForTicket_passesTicketIdThrough() throws Exception {
        when(service.listForTicket(eq(10L), any())).thenReturn(List.of());
        mvc.perform(get("/api/tickets/10/pricing-requests").session(session("sales")))
            .andExpect(status().isOk());
        verify(service).listForTicket(eq(10L), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String validCreateBody() {
        return """
            {
              "recipientType": "BUYER",
              "recipientContactId": 1,
              "items": [
                {
                  "requestedQty": 1,
                  "requestedUnit": "PIECE",
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
            1, 1, null, null, null, null, Instant.now(), Instant.now());
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
