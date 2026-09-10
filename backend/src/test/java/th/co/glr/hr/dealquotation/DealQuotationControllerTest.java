package th.co.glr.hr.dealquotation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * Pins the {@code GET /api/deal-quotations/{id}/file} response contract — same MockMvc
 * standalone pattern {@code CustomerQuotationControllerTest} uses for the sibling pricing-chain
 * quotation endpoint. {@code QuotationRenderer} (reused unmodified by
 * {@link DealQuotationRenderAdapter}) fills a real BIFF8 (.xls) company template, so the response
 * must advertise {@code application/vnd.ms-excel} and a {@code .xls} filename even when the
 * caller asked for {@code ?format=xlsx}.
 *
 * <p>The download filename is the quotation NUMBER (sanitised), not the internal id — see
 * {@link DealQuotationController#file}'s own Javadoc.
 */
class DealQuotationControllerTest {
    private static final byte[] OLE2_MAGIC_BYTES =
        {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final DealQuotationService service = mock(DealQuotationService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DealQuotationController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

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
        // A revision's number ("QT-2026-0042-2") must survive sanitisation intact -- hyphens are
        // in the allowed set -- and any character outside [A-Za-z0-9._-] must become "_" rather
        // than being dropped silently or breaking the Content-Disposition header.
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
            6L, "Sales", 6L, "Sales", "081-234-5678",
            null, null, null, null, null,
            LocalDate.now(), "Customer", null, null, null, null, null, null, null, "Project",
            null, null, null, null, null, null, null, null, null,
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "THB",
            false, List.of(), null, null);
    }
}
