package th.co.glr.hr.dealquotation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;

/** Pins the GET /api/deal-quotations/{id/file} response contract — same MockMvc standalone pattern
 * CustomerQuotationControllerTest uses for the sibling pricing-chain quotation endpoint. */
class DealQuotationControllerTest {
    private static final byte[] OLE2_MAGIC_BYTES =
        {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final DealQuotationService service = mock(DealQuotationService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DealQuotationController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    /** Owner decision 2026-09-13 (B): calculate-line forwards ?documentLanguage to the service, and an absent one
     * reaches it as null (which the service reads as TH). */
    @Test
    void calculateLineForwardsTheDocumentLanguageQueryParam() throws Exception {
        DealQuotationDtos.DealQuotationItemDto preview = new DealQuotationDtos.DealQuotationItemDto(0L, 0,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, 0, 0, 0, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
        when(service.calculateLine(any(), eq("EN"), any(UserPrincipal.class))).thenReturn(preview);
        when(service.calculateLine(any(), isNull(), any(UserPrincipal.class))).thenReturn(preview);

        mvc.perform(post("/api/deal-quotations/calculate-line?documentLanguage=EN").session(session())
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        verify(service).calculateLine(any(), eq("EN"), any(UserPrincipal.class));

        mvc.perform(post("/api/deal-quotations/calculate-line").session(session())
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        verify(service).calculateLine(any(), isNull(), any(UserPrincipal.class));
    }

    @Test
    void fileDefaultsToPdf() throws Exception {
        when(service.get(eq(42L), any(UserPrincipal.class))).thenReturn(quotation(42L, "QT-2026-0042"));
        when(service.renderPdf(eq(42L), any(UserPrincipal.class))).thenReturn("%PDF-1.4\n".getBytes());

        mvc.perform(get("/api/deal-quotations/42/file").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"QT-2026-0042.pdf\""))
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes("%PDF-1.4\n".getBytes()));
    }

    @Test
    void fileWithFormatXlsxParamReturnsHonestXlsContentTypeAndFilename() throws Exception {
        when(service.get(eq(42L), any(UserPrincipal.class))).thenReturn(quotation(42L, "QT-2026-0042"));
        when(service.renderXlsx(eq(42L), any(UserPrincipal.class))).thenReturn(OLE2_MAGIC_BYTES);

        mvc.perform(get("/api/deal-quotations/42/file?format=xlsx").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"QT-2026-0042.xls\""))
            .andExpect(content().contentType("application/vnd.ms-excel"))
            .andExpect(content().bytes(OLE2_MAGIC_BYTES));
    }

    @Test
    void fileNameOfARevisionIsSanitisedButKeepsTheHyphenSuffix() throws Exception {
        // A revision's number ("QT-2026-0042-2") must survive sanitisation intact -- hyphens are in the allowed set --
        // and any character outside [A-Za-z0-9._-] must become "_" rather than being dropped silently or breaking the
        // Content-Disposition header.
        when(service.get(eq(43L), any(UserPrincipal.class))).thenReturn(quotation(43L, "QT/2026\\0042-2"));
        when(service.renderPdf(eq(43L), any(UserPrincipal.class))).thenReturn("%PDF-1.4\n".getBytes());

        mvc.perform(get("/api/deal-quotations/43/file").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"QT_2026_0042-2.pdf\""));
    }

    @Test
    void fileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/deal-quotations/42/file"))
            .andExpect(status().isUnauthorized());
    }

    /** Slice 1 (IA §7): …/confirm-order is the real route and …/promote-to-deal is kept as an alias for one release
     * — BOTH must dispatch to the same service method. Against the pre-slice controller the confirm-order POST is
     * a 404 (no such mapping). */
    @Test
    void confirmOrder_andThePromoteToDealAlias_bothReachConfirmOrderFromDirectQuotation() throws Exception {
        DealQuotationDtos.PromoteToDealResultDto result =
            new DealQuotationDtos.PromoteToDealResultDto(9L, null, quotation(42L, "QT-2026-0042-1"));
        when(service.confirmOrderFromDirectQuotation(eq(42L), any(UserPrincipal.class))).thenReturn(result);

        mvc.perform(post("/api/deal-quotations/42/confirm-order").session(session()))
            .andExpect(status().isOk());
        mvc.perform(post("/api/deal-quotations/42/promote-to-deal").session(session()))
            .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(2)).confirmOrderFromDirectQuotation(eq(42L), any(UserPrincipal.class));
    }

    /** {@code POST …/undo-confirm-order}: the body is optional (none = no reason), a reason reaches the service
     * verbatim (trimming and blank handling live there), and the answer is {@code {"result": …}} like confirm-order. */
    @Test
    void undoConfirmOrder_bodyIsOptional_andTheReasonReachesTheService() throws Exception {
        DealQuotationDtos.PromoteToDealResultDto result =
            new DealQuotationDtos.PromoteToDealResultDto(9L, null, quotation(42L, "QT-2026-0042-1"));
        when(service.undoOrderConfirmation(eq(42L), isNull(), any(UserPrincipal.class))).thenReturn(result);
        when(service.undoOrderConfirmation(eq(42L), eq("กดผิด"), any(UserPrincipal.class))).thenReturn(result);

        mvc.perform(post("/api/deal-quotations/42/undo-confirm-order").session(session()))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result.ticketId").value(9));
        mvc.perform(post("/api/deal-quotations/42/undo-confirm-order").session(session())
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/deal-quotations/42/undo-confirm-order").session(session())
                .contentType("application/json").content("{\"reason\":\"กดผิด\"}"))
            .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(2)).undoOrderConfirmation(eq(42L), isNull(), any(UserPrincipal.class));
        verify(service).undoOrderConfirmation(eq(42L), eq("กดผิด"), any(UserPrincipal.class));
    }

    @Test
    void undoConfirmOrder_reasonOver500Chars_is400_andNeverReachesTheService() throws Exception {
        when(service.undoOrderConfirmation(eq(42L), any(), any(UserPrincipal.class))).thenReturn(
            new DealQuotationDtos.PromoteToDealResultDto(9L, null, quotation(42L, "QT-2026-0042-1")));
        mvc.perform(post("/api/deal-quotations/42/undo-confirm-order").session(session())
                .contentType("application/json").content("{\"reason\":\"" + "ก".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/deal-quotations/42/undo-confirm-order").session(session())
                .contentType("application/json").content("{\"reason\":\"" + "ก".repeat(500) + "\"}"))
            .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(1)).undoOrderConfirmation(eq(42L), any(), any(UserPrincipal.class));
    }

    @Test
    void undoConfirmOrder_requiresAuthentication() throws Exception {
        mvc.perform(post("/api/deal-quotations/42/undo-confirm-order"))
            .andExpect(status().isUnauthorized());
    }

    /** {@code GET /deal-quotations?origin=…} reaches the service verbatim (validation lives there). */
    @Test
    void searchForwardsTheOriginQueryParam() throws Exception {
        when(service.search(any(), eq(false), eq("LEGACY"), any(UserPrincipal.class))).thenReturn(List.of());
        mvc.perform(get("/api/deal-quotations?origin=LEGACY").session(session()))
            .andExpect(status().isOk());
        verify(service).search(any(), eq(false), eq("LEGACY"), any(UserPrincipal.class));
    }

    /** Document chain (2026-11-17): each list row is the quotation's own fields, unwrapped, plus its
     * {@code chainStage} — the per-row field the list's chain mark reads, so the page makes no per-row request. */
    @Test
    void searchRowsCarryTheirChainStageBesideTheQuotationsOwnFields() throws Exception {
        th.co.glr.hr.documentchain.DocumentChainService chain = mock(th.co.glr.hr.documentchain.DocumentChainService.class);
        MockMvc withChain = MockMvcBuilders
            .standaloneSetup(new DealQuotationController(service, new SessionContext(), chain))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        List<DealQuotationDto> rows = List.of(quotation(42L, "QT-2026-0042-1"), quotation(43L, "QT-2026-0043-1"));
        when(service.search(any(), eq(false), isNull(), any(UserPrincipal.class))).thenReturn(rows);
        when(chain.listStages(eq(rows), any(UserPrincipal.class))).thenReturn(java.util.Map.of(42L, "AWAITING_ORDER"));

        withChain.perform(get("/api/deal-quotations").session(session()))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].id").value(42))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].number")
                .value("QT-2026-0042-1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].chainStage")
                .value("AWAITING_ORDER"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].quotation")
                .doesNotExist())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[1].number")
                .value("QT-2026-0043-1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[1].chainStage")
                .isEmpty());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(6L, "sales@glr.co.th", "Sales", "sales", 6L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }

    /** Minimal {@link DealQuotationDto} — only {@code id}/{@code number} matter to this test. */
    private DealQuotationDto quotation(long id, String number) {
        return new DealQuotationDto(
            id, number, 1L, "DRAFT", 1, null,
            6L, "Sales", null, 6L, "Sales", null, "081-234-5678",
            null, null, null, null, null, null,
            LocalDate.now(), "Customer", null, null, null, null, null, null, null, "Project",
            null, null, null, null, null, null, null, null, null,
            "NET", "TH",
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "THB",
            false, List.of(), null, null);
    }
}
