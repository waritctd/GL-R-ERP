package th.co.glr.hr.deposit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

class DepositNoticeControllerTest {
    // OLE2/Compound File Binary magic (BIFF8 .xls, per [MS-CFB]) — real HSSFWorkbook output
    // starts with these bytes, never the ZIP "PK\x03\x04" of an OOXML .xlsx.
    private static final byte[] OLE2_MAGIC_BYTES =
        {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final DepositNoticeService service = mock(DepositNoticeService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DepositNoticeController(service, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void fileDefaultsToPdfForIssuedDocument() throws Exception {
        when(service.getById(eq(99L), any(UserPrincipal.class))).thenReturn(document());
        when(service.getPdf(eq(99L), any(UserPrincipal.class))).thenReturn("%PDF-1.4\n".getBytes());

        mvc.perform(get("/api/deposit-notices/99/file").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"GLRD69001.pdf\""))
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().bytes("%PDF-1.4\n".getBytes()));
    }

    @Test
    void fileWithFormatXlsxParamReturnsHonestXlsContentTypeAndFilename() throws Exception {
        // DepositNoticeRenderer fills a real BIFF8 (.xls) company template via
        // WorkbookFactory/HSSFWorkbook (see its TEMPLATE constant) — wb.write(out) emits OLE2
        // .xls bytes, never OOXML .xlsx. The ?format=xlsx REQUEST param is the existing wire
        // contract and stays as-is (frontend + mockApi both send it); only the RESPONSE
        // filename/content-type must advertise what the bytes actually are, or Excel warns
        // "the file format and extension don't match". Regression test for that mismatch.
        when(service.getById(eq(99L), any(UserPrincipal.class))).thenReturn(document());
        when(service.getXlsx(eq(99L), any(UserPrincipal.class))).thenReturn(OLE2_MAGIC_BYTES);

        mvc.perform(get("/api/deposit-notices/99/file?format=xlsx").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"GLRD69001.xls\""))
            .andExpect(content().contentType("application/vnd.ms-excel"))
            .andExpect(content().bytes(OLE2_MAGIC_BYTES));
    }

    @Test
    void fileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/deposit-notices/99/file"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void remainingInvoiceFileReturnsHonestXlsContentTypeAndFilename() throws Exception {
        // Same BIFF8-template mismatch as above, but for RemainingInvoiceRenderer
        // (templates/remaining_invoice_template.xls) via
        // GET /api/tickets/{ticketId}/remaining-invoice/file.
        when(service.getRemainingInvoiceXlsx(eq(10L), any(UserPrincipal.class))).thenReturn(OLE2_MAGIC_BYTES);

        mvc.perform(get("/api/tickets/10/remaining-invoice/file").session(session()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"remaining-invoice-10.xls\""))
            .andExpect(content().contentType("application/vnd.ms-excel"))
            .andExpect(content().bytes(OLE2_MAGIC_BYTES));
    }

    @Test
    void remainingInvoiceFileRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/tickets/10/remaining-invoice/file"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void listByTicketRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/tickets/10/deposit-notices"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getDocRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/deposit-notices/99"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createDraftRejectsInvalidDepositPercent() throws Exception {
        String body = """
            {"depositPercent": -1}
            """;

        mvc.perform(post("/api/tickets/10/deposit-notice/draft")
                .session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createDraftRejectsNegativeItemUnitPrice() throws Exception {
        String body = """
            {
              "items": [
                {"seq": 1, "description": "Widget", "qty": 1, "unitPrice": -100, "netUnitPrice": -100}
              ]
            }
            """;

        mvc.perform(post("/api/tickets/10/deposit-notice/draft")
                .session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    private DepositNoticeDto document() {
        return new DepositNoticeDto(
            99L,
            10L,
            "DEPOSIT_NOTICE",
            1,
            "GLRD69001",
            LocalDate.of(2026, 7, 5),
            "ISSUED",
            "ACME",
            "0100000000000",
            "Bangkok",
            "Showroom",
            "REF-1",
            "THB",
            new BigDecimal("0.50"),
            new BigDecimal("1000.00"),
            new BigDecimal("500.00"),
            new BigDecimal("0.07"),
            new BigDecimal("35.00"),
            new BigDecimal("535.00"),
            List.of(),
            true,
            true,
            "Sales",
            "Preparer",
            null,
            null,
            List.of()
        );
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(6L, "sales@glr.co.th", "Sales", "sales", 6L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
