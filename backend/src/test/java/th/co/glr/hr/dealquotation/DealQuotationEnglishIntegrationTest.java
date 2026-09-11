package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.mail.Mailer.Attachment;
import th.co.glr.hr.mail.Mailer.InlineImage;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Quotation v3b (owner, 2026-09-11 overnight) — the ENGLISH quotation (F-SM-008) against REAL
 * Postgres, through the REAL {@link DealQuotationService} and {@link DealQuotationRepository}.
 *
 * <p>Why this exists alongside {@link DealQuotationEnglishFormTest}: that file drives the adapter
 * and the renderer as pure functions off a hand-built DTO, which proves the DOCUMENT is right but
 * says nothing about whether {@code document_language} and {@code currency} survive a round trip
 * through V169's columns, whether an EN document's VAT actually comes back as zero from the
 * mapping SQL, or whether the SPECIAL_SQM refusal is reachable on the real write path. This one
 * creates, submits, approves and renders an EN/USD quotation end to end.
 *
 * <p>⚠️ There is no F-SM-008 template FILE — the English document is the Thai workbook driven with
 * English labels, modelled on the owner's PDF samples. See {@link DealQuotationEnglishFormTest}'s
 * header.
 */
class DealQuotationEnglishIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private CustomerRepository customers;
    private DealQuotationRepository quotationRepository;
    private DealQuotationService quotationService;
    // Fields rather than setUp locals so a test can build a SECOND service with the bank block
    // configured — see #theConfiguredBankBlock_reachesTheEnglishDocumentThroughTheService.
    private NotificationRepository notifications;
    private ContactRepository contacts;
    private EmployeeAuthRepository employeeAuth;

    private long salesRepId;
    private long salesManagerId;
    private UserPrincipal salesActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    private CustomerDto customer;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        notifications =
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        employeeAuth = new EmployeeAuthRepository(jdbc);
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), null, employeeAuth);

        quotationRepository = new DealQuotationRepository(jdbc);
        NotificationEmailService approvalMailer = new NotificationEmailService(
            new NoOpMailer(), new BrandAssets(), "", "", "https://portal.test");
        // The default (LibreOffice) PDF engine deliberately: this test never renders a PDF, only
        // the XLS the PDF is drawn from, so no soffice/Chromium precondition is introduced.
        quotationService = new DealQuotationService(quotationRepository, tickets, customers, contacts,
            notifications, approvalMailer, new QuotationRenderer(), employeeAuth,
            new EmployeeSignatureRepository(jdbc), "https://portal.test",
            // app.quotation.bank-block-line1..3 — empty here, so the English document prints the
            // proforma-invoice line. DealQuotationEnglishFormTest covers the configured block.
            "", "", "");

        // ⚠️ Both employees carry REAL first_name_en/last_name_en — that is the point of the
        // signature assertions below. #createEmployeeWithoutEnglishName covers the fallback.
        salesRepId = createEmployee(employees, "เจนเนตร หลงสกุล", "Jennet Longsakul",
            "sales-en@glr.co.th", "SALES", "ฝ่ายขาย", null);
        salesManagerId = createEmployee(employees, "ราม อิฐรัตน์", "Rarm Itarat",
            "sales-manager-en@glr.co.th", "SALES", "ฝ่ายขาย", "ผู้จัดการฝ่ายขาย");

        salesActor = actor(salesRepId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");

        customer = customers.create("Blue Lagoon Resort Pvt Ltd", "0100000000099",
            "12 Boduthakurufaanu Magu, Male, Maldives", "HQ", "+960 330 1234");
        ProjectDto project = projects.create(customer.id(), "Blue Lagoon Villas");
        ContactDto contact = contacts.create(customer.id(), "Aisha", "Rahman", "Purchasing Manager",
            "aisha@bluelagoon.mv", "+960 777 1234");
        TicketDto created = ticketService.create(new CreateTicketRequest(
            "Maldives deal", "NORMAL", customer.name(), customer.id(), project.id(),
            contact.id(), null, null, null), salesActor);
        ticketId = created.summary().id();
    }

    // ── the end-to-end acceptance run ──────────────────────────────────────────────────────

    /**
     * Create → submit → approve → render an EN/USD quotation, asserting at every step that the
     * language and currency survived and that the VAT is ZERO throughout.
     */
    @Test
    void englishQuotation_createsSubmitsApprovesAndRendersWithNoVat() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);

        assertThat(created.documentLanguage()).isEqualTo("EN");
        assertThat(created.currency()).isEqualTo("USD");
        assertThat(created.subtotalAmount()).isEqualByComparingTo("1000.00");
        // ⚠️ The whole point of the language flag on the money side: a Thai document of the same
        // items reads 70.00 / 1070.00 here (see #thaiQuotationOnTheSameDeal_stillCarriesItsVat).
        assertThat(created.vatAmount()).isEqualByComparingTo("0.00");
        assertThat(created.grandTotal()).isEqualByComparingTo("1000.00");

        // V169's columns actually hold it — read straight out of the table, not off the DTO that
        // just came back from the same mapping code that wrote it.
        Map<String, Object> stored = jdbc.getJdbcOperations().queryForMap(
            "SELECT document_language, currency FROM sales.quotation WHERE quotation_id = "
                + created.id());
        assertThat(stored.get("document_language")).isEqualTo("EN");
        assertThat(stored.get("currency")).isEqualTo("USD");
        // And the per-item vat column follows the document, rather than stamping 7% on the rows of
        // a document that charges none.
        assertThat(jdbc.getJdbcOperations().queryForObject(
            "SELECT SUM(vat) FROM sales.quotation_item WHERE quotation_id = " + created.id(),
            BigDecimal.class)).isEqualByComparingTo("0.00");

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(submitted.documentLanguage()).isEqualTo("EN");

        DealQuotationDto approved = quotationService.approve(submitted.id(),
            new ApproveRequest("approved"), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(approved.documentLanguage()).isEqualTo("EN");
        assertThat(approved.vatAmount()).isEqualByComparingTo("0.00");

        // The rendered document, through the real service's own render path.
        Sheet sheet = renderSheet(approved.id());
        assertThat(str(sheet, 0, 7).strip()).isEqualTo("QUOTATION");
        assertThat(str(sheet, 3, 1)).matches("[A-Z][a-z]+ \\d{1,2}, 20\\d\\d");   // English month, CE year
        assertThat(str(sheet, 3, 1)).doesNotContain("25");                        // no BE year leaked
        assertThat(str(sheet, 6, 8)).isEqualTo("Amount (USD)");
        assertThat(str(sheet, 6, 1)).isEqualTo("Description & Conditions");
        assertThat(str(sheet, 33, 4)).isEqualTo("Grand Total (USD)");             // TOTAL_ROW 40-7
        assertThat(str(sheet, 40, 8)).isEqualTo("F-SM-008 (01)");                 // FORM_TAG 47-7
        // English signature labels AND the employees' real English names, from hr.employee.
        assertThat(str(sheet, 37, 0))
            .contains("Printed by").contains("Quoted by").contains("Approved by").contains("Ordered by");
        assertThat(str(sheet, 38, 0))
            .contains("(Jennet Longsakul)").contains("(Rarm Itarat)").contains("(Aisha Rahman)");
        // No VAT row survived onto the sheet.
        assertThat(allText(sheet)).noneMatch(t -> t.contains("ภาษีมูลค่าเพิ่ม"));
    }

    /** The regression that matters, on the REAL write path: a Thai document on the same deal, with
     * the same items, is completely unaffected — 7% VAT, Thai labels, blank form tag. */
    @Test
    void thaiQuotationOnTheSameDeal_stillCarriesItsVat() throws Exception {
        DealQuotationDto thai = quotationService.create(ticketId,
            thaiRequest(List.of(tileItem("100.00", 10))), salesActor);

        assertThat(thai.documentLanguage()).isEqualTo("TH");
        assertThat(thai.currency()).isEqualTo("THB");
        assertThat(thai.vatAmount()).isEqualByComparingTo("70.00");
        assertThat(thai.grandTotal()).isEqualByComparingTo("1070.00");
        assertThat(jdbc.getJdbcOperations().queryForObject(
            "SELECT SUM(vat) FROM sales.quotation_item WHERE quotation_id = " + thai.id(),
            BigDecimal.class)).isEqualByComparingTo("70.00");

        Sheet sheet = renderSheet(thai.id());
        assertThat(str(sheet, 0, 7)).contains("ใบเสนอราคา");
        assertThat(str(sheet, 6, 8)).contains("เป็นเงิน");
        assertThat(str(sheet, 31, 7)).isEqualTo("รวมเป็นเงิน");                  // SUBTOTAL 38-7
        assertThat(str(sheet, 32, 4)).isEqualTo("ภาษีมูลค่าเพิ่ม");                  // VAT 39-7
        assertThat(str(sheet, 33, 7)).isEqualTo("รวมเป็นเงินทั้งสิ้น");             // TOTAL 40-7
        assertThat(str(sheet, 40, 8)).isEmpty();                                  // tag stays blank
        assertThat(str(sheet, 37, 0)).contains("ผู้พิมพ์").contains("ผู้สั่งซื้อ");
    }

    /** A request with NEITHER field is a Thai/THB document — every pre-v3b client keeps working. */
    @Test
    void requestWithoutALanguage_defaultsToThaiAndBaht() {
        DealQuotationDto created = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
                null, List.of(tileItem("100.00", 10))),
            salesActor);
        assertThat(created.documentLanguage()).isEqualTo("TH");
        assertThat(created.currency()).isEqualTo("THB");
        assertThat(created.vatAmount()).isEqualByComparingTo("70.00");
    }

    // ── SPECIAL_SQM is unavailable in English ──────────────────────────────────────────────

    /**
     * ⚠️ ราคาพิเศษ divides a บาท/ตร.ม. price by 1.07 to strip Thai VAT. On a USD document there is
     * no VAT to strip, so the same arithmetic silently produces a number with no meaning. Refused
     * at the SERVICE — on create AND on update, because a rep can reach the same state either way.
     */
    @Test
    void specialSqmOnAnEnglishDocument_isRefusedOnCreate() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ราคาพิเศษ");
    }

    @Test
    void specialSqmOnAnEnglishDocument_isRefusedOnUpdateToo() {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        assertThatThrownBy(() -> quotationService.update(created.id(),
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        // And the stored document is untouched by the refused update.
        assertThat(quotationService.get(created.id(), salesActor).priceMode())
            .isEqualTo(WastageCalculator.PRICE_MODE_NET);
    }

    /** Wrong-way-round: SPECIAL_SQM is still perfectly available on a THAI document — the refusal
     * must be about the LANGUAGE, not about the mode having been broken. */
    @Test
    void specialSqmOnAThaiDocument_isStillAccepted() {
        DealQuotationDto created = quotationService.create(ticketId,
            thaiRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"))), salesActor);
        assertThat(created.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        // 1350 / 1.07 / 2.78 = 453.84 per piece (the owner's own QN6900704-2 figure).
        assertThat(created.items().get(0).netUnitPrice()).isEqualByComparingTo("453.84");
    }

    /** DIRECT_NET touches no VAT, so it stays available in English. */
    @Test
    void directNetOnAnEnglishDocument_isAccepted() {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("2000.00", 10, "777.50"))), salesActor);
        assertThat(created.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_DIRECT_NET);
        assertThat(created.subtotalAmount()).isEqualByComparingTo("7775.00");
        assertThat(created.vatAmount()).isEqualByComparingTo("0.00");
    }

    // ── currency must agree with the language ──────────────────────────────────────────────

    @Test
    void aThaiDocumentInUsd_isRefused() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            request("TH", "USD", WastageCalculator.PRICE_MODE_NET, List.of(tileItem("100.00", 10))),
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void anEnglishDocumentInBaht_isRefused() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            request("EN", "THB", WastageCalculator.PRICE_MODE_NET, List.of(tileItem("100.00", 10))),
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    // ── revisions inherit the language ─────────────────────────────────────────────────────

    /** Revising an English quotation must not silently reissue it in Thai — with VAT. */
    @Test
    void revisingAnEnglishQuotation_keepsItEnglishAndVatFree() {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        DealQuotationDto approved = quotationService.approve(
            quotationService.submit(created.id(), salesActor).id(),
            new ApproveRequest("ok"), salesManagerActor);

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.documentLanguage()).isEqualTo("EN");
        assertThat(revision.currency()).isEqualTo("USD");
        assertThat(revision.vatAmount()).isEqualByComparingTo("0.00");
        assertThat(jdbc.getJdbcOperations().queryForObject(
            "SELECT SUM(vat) FROM sales.quotation_item WHERE quotation_id = " + revision.id(),
            BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    // ── the English-name fallback, against real hr.employee rows ───────────────────────────

    /**
     * An employee with no {@code first_name_en} prints their THAI name on the English document,
     * not a blank slot. Asserted against a REAL employee row rather than a hand-built DTO, because
     * the null this depends on is produced by {@code baseSelect}'s
     * {@code NULLIF(TRIM(CONCAT_WS(...)))} — a blank-string employee would come back as "" from a
     * naive CONCAT and defeat the fallback entirely.
     */
    @Test
    void anEmployeeWithNoEnglishName_printsTheThaiNameNotABlankSlot() throws Exception {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long noEnglishId = createEmployee(employees, "สมชาย ไม่มีชื่ออังกฤษ", null,
            "no-en@glr.co.th", "SALES", "ฝ่ายขาย", "ผู้จัดการฝ่ายขาย");

        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        DealQuotationDto approved = quotationService.approve(
            quotationService.submit(created.id(), salesActor).id(),
            new ApproveRequest("ok"), actor(noEnglishId, "sales_manager"));
        assertThat(approved.approvedByNameEn()).isNull();

        String names = str(renderSheet(approved.id()), 38, 0);
        assertThat(names).contains("(สมชาย ไม่มีชื่ออังกฤษ)");
        assertThat(names).doesNotContain("(..........................)");
    }

    /**
     * Review F3 (PR #929): nothing proved the CONFIGURED bank block reaches a document through the
     * service. Making {@code DealQuotationService} pass {@code List.of()} instead of its own field
     * left all 171 DealQuotation tests green, because every service in the suite is built with empty
     * lines. This one is built with the owner's block and renders through {@code renderXlsx}, exactly
     * as a download does.
     */
    @Test
    void theConfiguredBankBlock_reachesTheEnglishDocumentThroughTheService() throws Exception {
        List<String> block = List.of(
            "Please arrange payment to the following bank account. Bank Name : Kasikorn Bank Public Company Limited",
            "Beneficiary name : G.L.& R. Taps and Tiles Co., Ltd. Beneficiary account number : 003-92-1222-6 Saving Account",
            "SWIFT code : KASITHBK");
        DealQuotationService withBank = new DealQuotationService(quotationRepository, tickets, customers,
            contacts, notifications,
            new NotificationEmailService(new NoOpMailer(), new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), employeeAuth, new EmployeeSignatureRepository(jdbc),
            "https://portal.test", block.get(0), block.get(1), block.get(2));

        DealQuotationDto created = withBank.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        byte[] xls = withBank.renderXlsx(created.id(), salesActor);
        var wb = WorkbookFactory.create(new ByteArrayInputStream(xls));
        Sheet sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
        List<String> remarks = new java.util.ArrayList<>();
        for (int r = 23; r <= 30; r++) {
            remarks.add(str(sheet, r, 1));
        }
        assertThat(remarks).containsSequence(block);
        assertThat(remarks).noneMatch(line -> line.contains("proforma invoice"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private Sheet renderSheet(long quotationId) throws Exception {
        byte[] xls = quotationService.renderXlsx(quotationId, salesActor);
        var wb = WorkbookFactory.create(new ByteArrayInputStream(xls));
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    private String str(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return "";
        Cell cell = r.getCell(col);
        if (cell == null || cell.getCellType() != CellType.STRING) return "";
        return cell.getStringCellValue();
    }

    private List<String> allText(Sheet sheet) {
        List<String> out = new java.util.ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c <= 9; c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.STRING) {
                    out.add(cell.getStringCellValue());
                }
            }
        }
        return out;
    }

    private UpsertDealQuotationRequest englishRequest(List<ItemInput> items) {
        return request("EN", null, null, items);
    }

    private UpsertDealQuotationRequest englishRequestWithMode(String priceMode, List<ItemInput> items) {
        return request("EN", null, priceMode, items);
    }

    private UpsertDealQuotationRequest thaiRequest(List<ItemInput> items) {
        return request("TH", null, null, items);
    }

    private UpsertDealQuotationRequest thaiRequestWithMode(String priceMode, List<ItemInput> items) {
        return request("TH", null, priceMode, items);
    }

    private UpsertDealQuotationRequest request(String language, String currency, String priceMode,
                                               List<ItemInput> items) {
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            null, priceMode, language, currency, items);
    }

    /** 60x60 -> 0.36 ตร.ม./แผ่น, piecesPerBox 1 (box rounding a no-op), 30-45 day lead time. */
    private ItemInput tileItem(String unitPrice, int pieces) {
        return new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), null,
            WastageCalculator.QUANTITY_MODE_PIECES, null, pieces, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal(unitPrice), BigDecimal.ZERO, "Italy", 30, 45, null);
    }

    private ItemInput specialSqmItem(String listPrice, int pieces, String specialPerSqm) {
        ItemInput base = tileItem(listPrice, pieces);
        return new ItemInput(base.locationLabel(), base.catalogPriceId(), base.productCode(), base.brand(),
            base.model(), base.color(), base.texture(), base.sizeText(), base.thicknessMm(), base.sqmPerPiece(),
            base.quantityMode(), base.areaSqm(), base.piecesInput(), base.wastageMode(), base.wastageValue(),
            base.piecesPerBox(), base.unitPrice(), base.discountPct(), base.originCountry(),
            base.leadTimeMinDays(), base.leadTimeMaxDays(), base.itemNotes(),
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            new BigDecimal(specialPerSqm), null, null, null, null);
    }

    private ItemInput directNetItem(String listPrice, int pieces, String directNet) {
        ItemInput base = tileItem(listPrice, pieces);
        return new ItemInput(base.locationLabel(), base.catalogPriceId(), base.productCode(), base.brand(),
            base.model(), base.color(), base.texture(), base.sizeText(), base.thicknessMm(), base.sqmPerPiece(),
            base.quantityMode(), base.areaSqm(), base.piecesInput(), base.wastageMode(), base.wastageValue(),
            base.piecesPerBox(), base.unitPrice(), base.discountPct(), base.originCountry(),
            base.leadTimeMinDays(), base.leadTimeMaxDays(), base.itemNotes(),
            WastageCalculator.LINE_TYPE_TILE, null, null, null, null,
            new BigDecimal(directNet), null, null, null);
    }

    /** {@code nameEn} is split into {@code first_name_en}/{@code last_name_en} by
     * {@code EmployeeRepository#splitName}; passing null leaves BOTH null, which is exactly the
     * state {@link #anEmployeeWithNoEnglishName_printsTheThaiNameNotABlankSlot} needs. */
    private long createEmployee(EmployeeRepository employees, String nameTh, String nameEn,
                                String email, String divisionSourceCode,
                                String divisionNameTh, String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, nameEn, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionTh, null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    private static final class NoOpMailer implements Mailer {
        @Override
        public void send(String to, String subject, String body) {}

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody,
                             List<InlineImage> inlineImages) {}

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename,
                                       byte[] bytes) {}

        @Override
        public void sendWithAttachments(String to, String subject, String body,
                                        List<Attachment> attachments) {}
    }
}
