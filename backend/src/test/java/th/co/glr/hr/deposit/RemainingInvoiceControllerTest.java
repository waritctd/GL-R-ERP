package th.co.glr.hr.deposit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

/** GET /api/remaining-invoices/{id}/file: PDF on request, the legacy .xls stays the default (back-compat). */
class RemainingInvoiceControllerTest {
    private static final byte[] OLE2_MAGIC_BYTES =
        {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final RemainingInvoiceService service = mock(RemainingInvoiceService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new RemainingInvoiceController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void fileDefaultsToTheXlsWithTheHonestContentType() throws Exception {
        when(service.get(eq(7L), any(UserPrincipal.class))).thenReturn(document());
        when(service.file(eq(7L), any(UserPrincipal.class))).thenReturn(OLE2_MAGIC_BYTES);

        mvc.perform(get("/api/remaining-invoices/7/file").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"GLR6900001-1.xls\""))
            .andExpect(content().contentType("application/vnd.ms-excel"))
            .andExpect(content().bytes(OLE2_MAGIC_BYTES));
    }

    @Test
    void fileWithFormatPdfReturnsApplicationPdfNamedDotPdf() throws Exception {
        when(service.get(eq(7L), any(UserPrincipal.class))).thenReturn(document());
        when(service.getPdf(eq(7L), any(UserPrincipal.class))).thenReturn("%PDF-1.4\n".getBytes());

        mvc.perform(get("/api/remaining-invoices/7/file?format=pdf").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"GLR6900001-1.pdf\""))
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes("%PDF-1.4\n".getBytes()));
    }

    @Test
    void fileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/remaining-invoices/7/file?format=pdf")).andExpect(status().isUnauthorized());
    }

    private RemainingInvoiceDocumentDto document() {
        return new RemainingInvoiceDocumentDto(7L, 10L, null, null, "GLR6900001", 1, "GLR6900001-1", "ISSUED", null,
            "QT-TEST-0002", "AI2600001", LocalDate.of(2026, 10, 28), List.of(),
            "บริษัท ทดสอบ จำกัด", "0105599999999", "สำนักงานใหญ่", "99 ถนนทดสอบ", "ตึกตัวอย่าง",
            BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
            1L, "Sales", null, null, null, null, null, List.of());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(6L, "sales@glr.co.th", "Sales", "sales", 6L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
