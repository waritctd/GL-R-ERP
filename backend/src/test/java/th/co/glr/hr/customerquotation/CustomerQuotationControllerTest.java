package th.co.glr.hr.customerquotation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Pins the {@code GET /api/customer-quotations/{id}/file} response contract — the same MockMvc
 * standalone pattern {@code DepositNoticeControllerTest} uses for the sibling deposit-notice and
 * remaining-invoice endpoints. All three renderers (this one's {@code QuotationRenderer}
 * included) fill a real BIFF8 (.xls) company template via WorkbookFactory/HSSFWorkbook, so
 * {@code wb.write(out)} always emits OLE2 {@code .xls} bytes, never OOXML {@code .xlsx} — the
 * response must say so, or Excel warns "the file format and extension don't match".
 */
class CustomerQuotationControllerTest {
    // OLE2/Compound File Binary magic (BIFF8 .xls, per [MS-CFB]) — real HSSFWorkbook output
    // starts with these bytes, never the ZIP "PK\x03\x04" of an OOXML .xlsx.
    private static final byte[] OLE2_MAGIC_BYTES =
        {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final CustomerQuotationService service = mock(CustomerQuotationService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CustomerQuotationController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void fileDefaultsToPdfForQuotation() throws Exception {
        when(service.renderPdf(eq(42L), any(UserPrincipal.class))).thenReturn("%PDF-1.4\n".getBytes());

        mvc.perform(get("/api/customer-quotations/42/file").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-42.pdf\""))
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes("%PDF-1.4\n".getBytes()));
    }

    @Test
    void fileWithFormatXlsxParamReturnsHonestXlsContentTypeAndFilename() throws Exception {
        // The ?format=xlsx REQUEST param is the existing wire contract and stays as-is (frontend
        // + mockApi both send it); only the RESPONSE filename/content-type must advertise what
        // QuotationRenderer's bytes actually are. Regression test for that mismatch.
        when(service.renderXlsx(eq(42L), any(UserPrincipal.class))).thenReturn(OLE2_MAGIC_BYTES);

        mvc.perform(get("/api/customer-quotations/42/file?format=xlsx").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-42.xls\""))
            .andExpect(content().contentType("application/vnd.ms-excel"))
            .andExpect(content().bytes(OLE2_MAGIC_BYTES));
    }

    @Test
    void fileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/customer-quotations/42/file"))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(6L, "sales@glr.co.th", "Sales", "sales", 6L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
