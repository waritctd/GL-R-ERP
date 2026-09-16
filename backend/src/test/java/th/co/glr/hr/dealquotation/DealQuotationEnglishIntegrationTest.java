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
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
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

        quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        NotificationEmailService approvalMailer = new NotificationEmailService(
            new NoOpMailer(), new BrandAssets(), "", "", "https://portal.test");
        // The default (LibreOffice) PDF engine deliberately: this test never renders a PDF, only
        // the XLS the PDF is drawn from, so no soffice/Chromium precondition is introduced.
        quotationService = new DealQuotationService(quotationRepository, tickets, customers, contacts,
            notifications, approvalMailer, new QuotationRenderer(), employeeAuth,
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test",
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

    // ── owner decision 2026-09-13: English per-sqm pricing (Option A) ───────────────────────────

    /**
     * The owner's QN6900933 reproduced exactly through the real service and real Postgres:
     * 120 boxes × 0.6 = 72.00 SQM × 64 = 4,608.00; 30 × 0.6 = 18.00 × 36 = 648.00;
     * 114 × 0.495 = 56.43 × 64 = 3,611.52; Grand Total 8,867.52 with no VAT. The figures are the
     * PRINTED sample's, typed in — not computed by the code under test.
     */
    @Test
    void englishPerSqm_reproducesTheOwnersQN6900933() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(
                perSqmItem(3360, 28, "0.6", "64"),
                perSqmItem(1800, 60, "0.6", "36"),
                perSqmItem(7524, 66, "0.495", "64"))), salesActor);

        assertThat(created.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        String[][] expected = {
            {"72.00", "64.00", "4608.00", "(1 box = 28 pcs = 0.6 sqm)", "120"},
            {"18.00", "36.00", "648.00", "(1 box = 60 pcs = 0.6 sqm)", "30"},
            {"56.43", "64.00", "3611.52", "(1 box = 66 pcs = 0.495 sqm)", "114"},
        };
        for (int i = 0; i < 3; i++) {
            var item = created.items().get(i);
            assertThat(item.quantity()).as("row %d qty", i + 1).isEqualByComparingTo(expected[i][0]);
            assertThat(item.unit()).isEqualTo("SQM");
            assertThat(item.unitPrice()).isEqualByComparingTo(expected[i][1]);
            assertThat(item.netUnitPrice()).isEqualByComparingTo(expected[i][1]);
            assertThat(item.discountPct()).isNull();
            assertThat(item.lineAmount()).as("row %d amount", i + 1).isEqualByComparingTo(expected[i][2]);
            assertThat(item.specialPriceLine()).isEqualTo(expected[i][3]);
            assertThat(item.boxes()).isEqualTo(Integer.valueOf(expected[i][4]));
        }
        assertThat(created.subtotalAmount()).isEqualByComparingTo("8867.52");
        assertThat(created.vatAmount()).isEqualByComparingTo("0.00");
        assertThat(created.grandTotal()).isEqualByComparingTo("8867.52");

        // Stored: qty stays the PIECE count, sqm_per_box is persisted (V176), raw_unit SQM.
        assertThat(jdbc.getJdbcOperations().queryForList(
            "SELECT qty::int || '|' || sqm_per_box::text || '|' || raw_unit FROM sales.quotation_item"
                + " WHERE quotation_id = " + created.id() + " ORDER BY seq", String.class))
            .containsExactly("3360|0.600000|SQM", "1800|0.600000|SQM", "7524|0.495000|SQM");

        // The rendered English sheet: Qty in sqm, Unit SQM, Unit price and Net price the USD/sqm,
        // Disc. "Net", the box sub-line, Grand Total 8,867.52.
        Sheet sheet = renderSheet(created.id());
        int row = rowWithText(sheet, 1, "Tile Model Model A");
        assertThat(sheet.getRow(row).getCell(2).getNumericCellValue()).isEqualTo(72.00);
        // Printed to 2dp: the template's whole-number format showed 56.43 as "56" (caught on the
        // rendered PDF). DataFormatter applies the cell's own format, as Excel/LibreOffice do.
        org.apache.poi.ss.usermodel.DataFormatter shown = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.US);
        assertThat(shown.formatCellValue(sheet.getRow(row).getCell(2))).isEqualTo("72.00");
        int fango = rowWithText(sheet, 1, "Tile Model Model A", row + 1);
        fango = rowWithText(sheet, 1, "Tile Model Model A", fango + 1);
        assertThat(shown.formatCellValue(sheet.getRow(fango).getCell(2))).isEqualTo("56.43");
        assertThat(str(sheet, row, 3)).isEqualTo("SQM");
        assertThat(sheet.getRow(row).getCell(4).getNumericCellValue()).isEqualTo(64.00);
        assertThat(str(sheet, row, 6)).isEqualTo("Net");
        assertThat(sheet.getRow(row).getCell(7).getNumericCellValue()).isEqualTo(64.00);
        assertThat(sheet.getRow(row).getCell(8).getNumericCellValue()).isEqualTo(4608.00);
        assertThat(allText(sheet)).contains("(1 box = 28 pcs = 0.6 sqm)", "(1 box = 66 pcs = 0.495 sqm)");
        int total = rowWithText(sheet, 4, "Grand Total (USD)");
        assertThat(sheet.getRow(total).getCell(8).getNumericCellValue()).isEqualTo(8867.52);

        // Submit's stored-row gate accepts it (no per-piece list price is required).
        assertThat(quotationService.submit(created.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    /**
     * Review fix (Opus, 2026-09-13): a supplier sqm/box to 5 decimals, as the prod catalogue
     * carries them, whose box quantity is NOT a whole cent. 120 boxes × 0.59696 = 71.6352 → Qty
     * 71.64, and Amount = the printed Qty × 64 = 4,584.96 — not 71.6352 × 64 = 4,584.65. Checked on
     * the DTO, the stored document totals, and the rendered sheet's own cells, so the printed row
     * multiplies out (Qty × Unit price = Amount) for anyone checking it with a calculator.
     */
    @Test
    void englishPerSqm_aFiveDecimalBoxArea_pricesTheRoundedQtyThatIsPrinted() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(perSqmItem(3360, 28, "0.59696", "64"))), salesActor);
        var item = created.items().get(0);
        assertThat(item.boxes()).isEqualTo(120);
        assertThat(item.quantity()).isEqualByComparingTo("71.64");
        assertThat(item.lineAmount()).isEqualByComparingTo("4584.96");
        assertThat(item.specialPriceLine()).isEqualTo("(1 box = 28 pcs = 0.59696 sqm)");
        assertThat(item.sqmPerBox()).isEqualByComparingTo("0.59696");
        assertThat(created.subtotalAmount()).isEqualByComparingTo("4584.96");
        assertThat(created.vatAmount()).isEqualByComparingTo("0.00");
        assertThat(created.grandTotal()).isEqualByComparingTo("4584.96");
        // Re-read through the repository mapping, not just the create response.
        var reread = quotationService.get(created.id(), salesActor).items().get(0);
        assertThat(reread.quantity()).isEqualByComparingTo("71.64");
        assertThat(reread.lineAmount()).isEqualByComparingTo("4584.96");

        Sheet sheet = renderSheet(created.id());
        int row = rowWithText(sheet, 1, "Tile Model Model A");
        org.apache.poi.ss.usermodel.DataFormatter shown = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.US);
        assertThat(shown.formatCellValue(sheet.getRow(row).getCell(2))).isEqualTo("71.64");
        assertThat(sheet.getRow(row).getCell(8).getNumericCellValue()).isEqualTo(4584.96);
        assertThat(allText(sheet)).contains("(1 box = 28 pcs = 0.59696 sqm)");
        int total = rowWithText(sheet, 4, "Grand Total (USD)");
        assertThat(sheet.getRow(total).getCell(8).getNumericCellValue()).isEqualTo(4584.96);
    }

    /** AREA mode with wastage: the pieces derivation is the ordinary one; only the qty is sqm. */
    @Test
    void englishPerSqm_areaModeWithWastage_usesTheBoxesWastageCalculatorRoundedTo() {
        ItemInput area = withQuantity(perSqmItem(0, 20, "1.22", "10"), WastageCalculator.QUANTITY_MODE_AREA,
            new BigDecimal("300"), null, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"),
            new BigDecimal("0.061013"));
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(area)), salesActor);
        var item = created.items().get(0);
        // 300 × 16.39 = 4,917 → +5% = 5,163 → 20/box = 5,180 pcs = 259 boxes; 259 × 1.22 = 315.98.
        assertThat(item.boxes()).isEqualTo(259);
        assertThat(item.quantity()).isEqualByComparingTo("315.98");
        assertThat(item.lineAmount()).isEqualByComparingTo("3159.80");
        assertThat(item.calculationLine()).isEqualTo(
            "(Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs = 259 boxes)");
        assertThat(item.specialPriceLine()).isEqualTo("(1 box = 20 pcs = 1.22 sqm)");
    }

    // ── Option B (owner decision, 2026-09-16): ตร.ม./กล่อง OPTIONAL for English per-sqm ─────────

    /** Blank box area, แผ่น/กล่อง filled: quantity derives from the box-rounded piecesFinal ×
     * sqmPerPiece instead of boxes × sqmPerBox — accepted on create, update AND the preview, where
     * this exact combination used to 400 before Option B. */
    @Test
    void englishPerSqm_noBoxArea_piecesPerBoxFilled_isNowAccepted_onCreateUpdateAndPreview() throws Exception {
        // 3,360 pcs / 28 per box = 120 exactly (no box rounding needed) — sqmPerPiece 0.36 (tileItem's
        // fixed value): qty = 3,360 × 0.36 = 1,209.60; amount = 1,209.60 × 64 = 77,414.40.
        ItemInput row = perSqmItem(3360, 28, null, "64");
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(row)), salesActor);
        var item = created.items().get(0);
        assertThat(item.unit()).isEqualTo("SQM");
        assertThat(item.quantity()).isEqualByComparingTo("1209.60");
        assertThat(item.lineAmount()).isEqualByComparingTo("77414.40");
        assertThat(item.boxes()).isEqualTo(120);
        assertThat(item.sqmPerBox()).isNull();
        assertThat(item.specialPriceLine()).isNull(); // no box sub-line without a box area
        assertThat(item.calculationLine())
            .isEqualTo("(Quantity 3,360 pcs, rounded up to full boxes = 3,360 pcs) (28 pcs/box)");

        // Stored: qty stays the PIECE count, sqm_per_box stays NULL.
        assertThat(jdbc.getJdbcOperations().queryForObject(
            "SELECT qty::int || '|' || (sqm_per_box IS NULL)::text FROM sales.quotation_item"
                + " WHERE quotation_id = " + created.id(), String.class))
            .isEqualTo("3360|true");

        // RELOAD through the repository mapping (DealQuotationRepository#mapItemColumns), not just
        // the create response — proves the sqmPerPiece thread reaches the READ path too.
        var reread = quotationService.get(created.id(), salesActor).items().get(0);
        assertThat(reread.quantity()).isEqualByComparingTo("1209.60");
        assertThat(reread.lineAmount()).isEqualByComparingTo("77414.40");
        assertThat(reread.unit()).isEqualTo("SQM");

        // RENDER: the sheet's own Qty/Unit/Amount cells, and no box sub-line text anywhere on it.
        Sheet sheet = renderSheet(created.id());
        int row2 = rowWithText(sheet, 1, "Tile Model Model A");
        assertThat(sheet.getRow(row2).getCell(2).getNumericCellValue()).isEqualTo(1209.60);
        assertThat(str(sheet, row2, 3)).isEqualTo("SQM");
        assertThat(sheet.getRow(row2).getCell(8).getNumericCellValue()).isEqualTo(77414.40);
        assertThat(allText(sheet)).noneMatch(t -> t.contains("1 box ="));

        // Submit's stored-row gate accepts it too — no per-piece list price, no box area required.
        assertThat(quotationService.submit(created.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.PENDING_APPROVAL);

        // update: the same combination onto an existing (NET) document.
        DealQuotationDto net = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        DealQuotationDto updated = quotationService.update(net.id(),
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(row)), salesActor);
        assertThat(updated.items().get(0).quantity()).isEqualByComparingTo("1209.60");

        // preview: the lenient calculate-line path accepts it too.
        DealQuotationItemDto preview = quotationService.calculateLine(row, "EN", salesActor);
        assertThat(preview.quantity()).isEqualByComparingTo("1209.60");
        assertThat(preview.unit()).isEqualTo("SQM");
    }

    /** Blank box area AND no แผ่น/กล่อง either — a TILE row with no box multiple at all, exactly
     * like any other tile row without a pieces-per-box. No box rounding, no box wording. */
    @Test
    void englishPerSqm_noBoxAreaAndNoPiecesPerBox_isAccepted_noBoxRounding() {
        ItemInput row = withBoxes(perSqmItem(3360, 28, null, "64"), null);
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(row)), salesActor);
        var item = created.items().get(0);
        assertThat(item.boxes()).isNull();
        assertThat(item.quantity()).isEqualByComparingTo("1209.60"); // 3,360 × 0.36, no box rounding to apply anyway
        assertThat(item.lineAmount()).isEqualByComparingTo("77414.40");
        assertThat(item.specialPriceLine()).isNull();
        assertThat(item.calculationLine()).isEqualTo("(Quantity 3,360 pcs = 3,360 pcs)");
    }

    /** Blank box area, แผ่น/กล่อง filled, "sell loose pieces" ticked: quantity derives from the
     * EXACT wastage-adjusted piece count (unrounded), never the box-rounded one — the combination
     * {@code englishPerSqm_withRoundToFullBoxFalse_isRefusedWithABoxArea} below still refuses when
     * a box area IS present. */
    @Test
    void englishPerSqm_noBoxArea_loosePieces_isNowAccepted_derivesSqmFromExactPieces() {
        // 3,357 pcs / 10 per box = 335 boxes + 7 loose pcs; qty = 3,357 × 0.36 = 1,208.52 (NOT the
        // box-rounded 3,360 × 0.36 = 1,209.60).
        ItemInput row = withRoundToFullBox(perSqmItem(3357, 10, null, "64"), false);
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(row)), salesActor);
        var item = created.items().get(0);
        assertThat(item.quantity()).isEqualByComparingTo("1208.52");
        assertThat(item.lineAmount()).isEqualByComparingTo("77345.28");
        assertThat(item.calculationLine()).isEqualTo("(Quantity 3,357 pcs = 335 boxes + 7 pcs) (10 pcs/box)");
        assertThat(item.specialPriceLine()).isNull();

        // preview accepts it too — no completeness gate to bypass, but this used to be the exact
        // refused combination before Option B.
        assertThat(quotationService.calculateLine(row, "EN", salesActor).quantity())
            .isEqualByComparingTo("1208.52");
    }

    /** Wrong-way-round, unchanged from before Option B: a box area PRESENT with แผ่น/กล่อง blank is
     * a partially-filled pair — you cannot count boxes without pieces per box, so it is still
     * refused, on create, update and the preview. This is the ONE combination Option B leaves
     * refused. */
    @Test
    void englishPerSqm_boxAreaFilledButPiecesPerBoxBlank_isStillRefused_onCreateUpdateAndPreview() {
        ItemInput partial = withBoxes(perSqmItem(3360, 28, "0.6", "64"), null);
        assertThatThrownBy(() -> quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(partial)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("แผ่นต่อกล่อง");

        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        assertThatThrownBy(() -> quotationService.update(created.id(),
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(partial)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("แผ่นต่อกล่อง");
        assertThat(quotationService.get(created.id(), salesActor).priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_NET);

        // The lenient preview skips #requireItemComplete (rowNumber is null there), so it is the
        // OTHER per-sqm-specific check (#requireBoxDataForPerSqm) that catches this combination —
        // a different message ("แผ่น/กล่อง"), same refusal.
        assertThatThrownBy(() -> quotationService.calculateLine(partial, "EN", salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("แผ่น/กล่อง");
        // ...while the SAME row previewed as a THAI ราคาพิเศษ needs no box data at all.
        assertThat(quotationService.calculateLine(withSqmPerPiece(partial, "0.36"), null, salesActor).unit())
            .isEqualTo("แผ่น");
    }

    /** Owner-approved "sell loose pieces" (V182), narrowed by Option B: a box AREA present is
     * still boxes × sqmPerBox, which has no "loose pieces" term at all, so
     * {@code roundToFullBox = false} is STILL refused whenever ตร.ม./กล่อง is filled — on the same
     * three surfaces (create, update, the lenient preview) as before. */
    @Test
    void englishPerSqm_withRoundToFullBoxFalse_isRefusedInThai_whenABoxAreaIsPresent() {
        ItemInput loose = withRoundToFullBox(perSqmItem(3360, 28, "0.6", "64"), false);
        assertThatThrownBy(() -> quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(loose)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("ปัดขึ้นเต็มกล่อง");

        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10))), salesActor);
        assertThatThrownBy(() -> quotationService.update(created.id(),
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(loose)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ปัดขึ้นเต็มกล่อง");
        assertThat(quotationService.get(created.id(), salesActor).priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_NET);

        // The lenient preview has no completeness gate — this refusal still applies there.
        assertThatThrownBy(() -> quotationService.calculateLine(loose, "EN", salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ปัดขึ้นเต็มกล่อง");
        // ...while the same box/pricing data previewed as a THAI ราคาพิเศษ is perfectly legal —
        // the refusal is specific to the English PER-SQM combination, not to a TILE row in
        // general (withSqmPerPiece's `copy` helper does not thread roundToFullBox through, so
        // this leg does not itself prove roundToFullBox=false is accepted in Thai mode — see
        // WastageCalculatorTest/DealQuotationLinesTest for that).
        assertThat(quotationService.calculateLine(withSqmPerPiece(loose, "0.36"), null, salesActor).unit())
            .isEqualTo("แผ่น");
    }

    /** Owner decision (B): the preview in English returns the English lines and the sqm quantity. */
    @Test
    void calculateLine_inEnglish_returnsTheEnglishLinesAndThePerSqmQuantity() {
        DealQuotationItemDto preview = quotationService.calculateLine(perSqmItem(3360, 28, "0.6", "64"), "EN", salesActor);
        assertThat(preview.descriptionLine()).isEqualTo("Tile Model Model A Color White Finish Matte");
        assertThat(preview.calculationLine()).isEqualTo("(Quantity 3,360 pcs, rounded up to full boxes = 3,360 pcs = 120 boxes)");
        assertThat(preview.specialPriceLine()).isEqualTo("(1 box = 28 pcs = 0.6 sqm)");
        assertThat(preview.quantity()).isEqualByComparingTo("72.00");
        assertThat(preview.unit()).isEqualTo("SQM");
        assertThat(preview.lineAmount()).isEqualByComparingTo("4608.00");
        assertThat(preview.sqmPerBox()).isEqualByComparingTo("0.6");

        // An English NET preview: English lines, PCS, pieces.
        DealQuotationItemDto net = quotationService.calculateLine(tileItem("100.00", 10), "EN", salesActor);
        assertThat(net.calculationLine()).isEqualTo("(Quantity 10 pcs, rounded up to full boxes = 10 pcs) (1 pcs/box)");
        assertThat(net.unit()).isEqualTo("PCS");

        assertThatThrownBy(() -> quotationService.calculateLine(tileItem("100.00", 10), "FR", salesActor))
            .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    /** A revision of an English per-sqm quotation copies the PIECE count into qty (not the sqm
     * figure the DTO prints) and keeps sqm_per_box, so its printed qty and amount are unchanged. */
    @Test
    void revisingAnEnglishPerSqmQuotation_keepsQtyAmountAndSqmPerBox() {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(perSqmItem(7524, 66, "0.495", "64"))), salesActor);
        quotationService.submit(created.id(), salesActor);
        quotationService.approve(created.id(), new ApproveRequest("ok"), salesManagerActor);
        DealQuotationDto revision = quotationService.createRevision(created.id(), salesActor);
        var item = revision.items().get(0);
        assertThat(item.quantity()).isEqualByComparingTo("56.43");
        assertThat(item.lineAmount()).isEqualByComparingTo("3611.52");
        assertThat(item.unit()).isEqualTo("SQM");
        assertThat(item.sqmPerBox()).isEqualByComparingTo("0.495");
        assertThat(jdbc.getJdbcOperations().queryForObject(
            "SELECT qty::int FROM sales.quotation_item WHERE quotation_id = " + revision.id(), Integer.class))
            .isEqualTo(7524);
    }

    /** Wrong-way-round: the THAI SPECIAL_SQM is unchanged — a VAT-inclusive baht/ตร.ม. turned into a
     * per-piece net, quantity in pieces — even when the row carries a sqm/box. */
    @Test
    void specialSqmOnAThaiDocument_isStillAccepted() {
        DealQuotationDto created = quotationService.create(ticketId,
            thaiRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(withSqmPerBox(specialSqmItem("2000.00", 10, "1350"), "0.72"))), salesActor);
        assertThat(created.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        // 1350 / 1.07 / 2.78 = 453.84 per piece (the owner's own QN6900704-2 figure).
        assertThat(created.items().get(0).netUnitPrice()).isEqualByComparingTo("453.84");
        assertThat(created.items().get(0).unitPrice()).isEqualByComparingTo("2000.00");
        assertThat(created.items().get(0).quantity()).isEqualByComparingTo("10");
        assertThat(created.items().get(0).unit()).isEqualTo("แผ่น");
        assertThat(created.items().get(0).lineAmount()).isEqualByComparingTo("4538.40");
        assertThat(created.items().get(0).specialPriceLine()).isEqualTo("(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)");
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

    // ── owner ruling 2026-09-13 (1)(4)(5): the item lines follow the language, at READ time ──

    private static final java.util.regex.Pattern THAI = java.util.regex.Pattern.compile("[\\u0E00-\\u0E7F]");

    /**
     * Through the real repository mapping: an English quotation's tile lines, tile unit and
     * ส่วนลดพิเศษ text come back in English, while the STORED description/raw_unit columns keep the
     * Thai write-time values — proving the translation is read-time, not a data rewrite.
     */
    @Test
    void englishQuotation_itemLinesUnitAndDiscountTextAreEnglish_storedColumnsUntouched() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            englishRequest(List.of(tileItem("100.00", 10), adjustmentItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);
        var tile = created.items().get(0);
        assertThat(tile.descriptionLine()).isEqualTo("Tile Model Model A Color White Finish Matte");
        assertThat(tile.sizeLine()).isEqualTo("Size 60x60 x 10 mm (approx.)");
        assertThat(tile.calculationLine()).isEqualTo("(Quantity 10 pcs, rounded up to full boxes = 10 pcs) (1 pcs/box)");
        assertThat(tile.unit()).isEqualTo("PCS");
        var adjustment = created.items().get(1);
        assertThat(adjustment.descriptionLine()).isEqualTo("Special discount 3% for orders placed by July 31, 2026");

        assertThat(jdbc.getJdbcOperations().queryForList(
            "SELECT COALESCE(raw_unit, '') || '|' || COALESCE(description, '') FROM sales.quotation_item"
                + " WHERE quotation_id = " + created.id() + " ORDER BY seq", String.class))
            // The write path stores the THAI tile description and unit, and the Thai discount text,
            // whatever the document language — the English above is produced on read.
            .containsExactly("แผ่น|กระเบื้อง รุ่น Model A สี White ผิว Matte",
                "|ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");

        // And the rendered item + totals zone carries no Thai script.
        Sheet sheet = renderSheet(created.id());
        List<String> offending = new java.util.ArrayList<>();
        for (int r = 9; r <= 33; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c <= 8; c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.STRING
                    && THAI.matcher(cell.getStringCellValue()).find()) {
                    offending.add("r" + r + "c" + c + ": " + cell.getStringCellValue());
                }
            }
        }
        assertThat(offending).isEmpty();
        assertThat(str(sheet, 33, 4)).isEqualTo("Grand Total (USD)");
        assertThat(sheet.getRow(31).getZeroHeight()).isTrue();
        assertThat(sheet.getRow(32).getZeroHeight()).isTrue();
    }

    /**
     * Ruling 5 — EXISTING documents, no migration: a quotation written as Thai and then flipped to
     * English directly in the table (standing in for any English row already in the database) reads
     * back in English with nothing re-saved. And the Thai read of the very same rows, before the
     * flip, is the Thai text.
     */
    @Test
    void anExistingRowReadsInTheDocumentsCurrentLanguage_withNoRewrite() {
        DealQuotationDto thai = quotationService.create(ticketId,
            thaiRequest(List.of(tileItem("100.00", 10), adjustmentItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);
        assertThat(thai.items().get(0).descriptionLine()).isEqualTo("กระเบื้อง รุ่น Model A สี White ผิว Matte");
        assertThat(thai.items().get(0).sizeLine()).isEqualTo("ขนาด 60x60 x 10 mm (ขนาดโดยประมาณ)");
        assertThat(thai.items().get(0).calculationLine()).isEqualTo("(จำนวน 10 แผ่น และปัดขึ้นเต็มกล่อง = 10 แผ่น) (บรรจุ 1 แผ่น/กล่อง)");
        assertThat(thai.items().get(0).unit()).isEqualTo("แผ่น");
        assertThat(thai.items().get(1).descriptionLine()).isEqualTo("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");

        jdbc.getJdbcOperations().update(
            "UPDATE sales.quotation SET document_language = 'EN', currency = 'USD' WHERE quotation_id = " + thai.id());
        DealQuotationDto reread = quotationService.get(thai.id(), salesActor);
        assertThat(reread.items().get(0).descriptionLine()).isEqualTo("Tile Model Model A Color White Finish Matte");
        assertThat(reread.items().get(0).unit()).isEqualTo("PCS");
        assertThat(reread.items().get(1).descriptionLine()).isEqualTo("Special discount 3% for orders placed by July 31, 2026");
        assertThat(reread.items()).allSatisfy(item -> {
            for (String line : new String[] {item.descriptionLine(), item.sizeLine(), item.calculationLine(), item.unit()}) {
                if (line != null) assertThat(THAI.matcher(line).find()).as(line).isFalse();
            }
        });
    }

    /** A NULL document_language (every pre-V169 row) still reads as Thai. */
    @Test
    void aNullDocumentLanguageRow_readsItsLinesInThai() {
        DealQuotationDto created = quotationService.create(ticketId,
            thaiRequest(List.of(tileItem("100.00", 10))), salesActor);
        jdbc.getJdbcOperations().update(
            "UPDATE sales.quotation SET document_language = NULL WHERE quotation_id = " + created.id());
        var item = quotationService.get(created.id(), salesActor).items().get(0);
        assertThat(item.descriptionLine()).isEqualTo("กระเบื้อง รุ่น Model A สี White ผิว Matte");
        assertThat(item.unit()).isEqualTo("แผ่น");
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
            new CatalogRepository(jdbc),
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

    /** 60x60 -> 0.36 ตร.ม./แผ่น (explicit -- ตร.ม./แผ่น is never derived from sizeText any more;
     * see DealQuotationService#resolveSqmPerPiece's Javadoc), piecesPerBox 1 (box rounding a
     * no-op), 30-45 day lead time. */
    private ItemInput tileItem(String unitPrice, int pieces) {
        return new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
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

    /** An English per-sqm tile in PIECES mode, no wastage — {@code sqmPerBox} null to omit it. */
    private ItemInput perSqmItem(int pieces, int piecesPerBox, String sqmPerBox, String usdPerSqm) {
        ItemInput base = tileItem("1.00", pieces);
        return new ItemInput(base.locationLabel(), base.catalogPriceId(), base.productCode(), base.brand(),
            base.model(), base.color(), base.texture(), base.sizeText(), base.thicknessMm(), base.sqmPerPiece(),
            base.quantityMode(), base.areaSqm(), base.piecesInput(), base.wastageMode(), base.wastageValue(),
            piecesPerBox, null, null, base.originCountry(),
            base.leadTimeMinDays(), base.leadTimeMaxDays(), base.itemNotes(),
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            new BigDecimal(usdPerSqm), null, null, null, null, null,
            sqmPerBox == null ? null : new BigDecimal(sqmPerBox));
    }

    private ItemInput copy(ItemInput b, String quantityMode, BigDecimal areaSqm, Integer piecesInput,
                           String wastageMode, BigDecimal wastageValue, BigDecimal sqmPerPiece, Integer piecesPerBox,
                           BigDecimal sqmPerBox) {
        return new ItemInput(b.locationLabel(), b.catalogPriceId(), b.productCode(), b.brand(), b.model(), b.color(),
            b.texture(), b.sizeText(), b.thicknessMm(), sqmPerPiece, quantityMode, areaSqm, piecesInput,
            wastageMode, wastageValue, piecesPerBox, b.unitPrice(), b.discountPct(), b.originCountry(),
            b.leadTimeMinDays(), b.leadTimeMaxDays(), b.itemNotes(), b.lineType(), b.description(), b.quantity(),
            b.unit(), b.specialPriceSqm(), b.directNetPrice(), b.adjustmentPct(), b.adjustmentDeadline(),
            b.adjustmentAmount(), b.id(), sqmPerBox);
    }

    private ItemInput withQuantity(ItemInput b, String quantityMode, BigDecimal areaSqm, Integer piecesInput,
                                   String wastageMode, BigDecimal wastageValue, BigDecimal sqmPerPiece) {
        return copy(b, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue, sqmPerPiece, b.piecesPerBox(),
            b.sqmPerBox());
    }

    private ItemInput withBoxes(ItemInput b, Integer piecesPerBox) {
        return copy(b, b.quantityMode(), b.areaSqm(), b.piecesInput(), b.wastageMode(), b.wastageValue(),
            b.sqmPerPiece(), piecesPerBox, b.sqmPerBox());
    }

    private ItemInput withSqmPerBox(ItemInput b, String sqmPerBox) {
        return copy(b, b.quantityMode(), b.areaSqm(), b.piecesInput(), b.wastageMode(), b.wastageValue(),
            b.sqmPerPiece(), b.piecesPerBox(), new BigDecimal(sqmPerBox));
    }

    /** V182 "sell loose pieces" — {@code copy} has no roundToFullBox parameter (it predates this
     * feature), so this goes through the full canonical constructor directly instead. */
    private ItemInput withRoundToFullBox(ItemInput b, Boolean roundToFullBox) {
        return new ItemInput(b.locationLabel(), b.catalogPriceId(), b.productCode(), b.brand(), b.model(),
            b.color(), b.texture(), b.sizeText(), b.thicknessMm(), b.sqmPerPiece(), b.quantityMode(),
            b.areaSqm(), b.piecesInput(), b.wastageMode(), b.wastageValue(), b.piecesPerBox(), b.unitPrice(),
            b.discountPct(), b.originCountry(), b.leadTimeMinDays(), b.leadTimeMaxDays(), b.itemNotes(),
            b.lineType(), b.description(), b.quantity(), b.unit(), b.specialPriceSqm(), b.directNetPrice(),
            b.adjustmentPct(), b.adjustmentDeadline(), b.adjustmentAmount(), b.id(), b.sqmPerBox(),
            roundToFullBox);
    }

    /** The row as a THAI ราคาพิเศษ would send it: a list price per piece and a ตร.ม./แผ่น. */
    private ItemInput withSqmPerPiece(ItemInput b, String sqmPerPiece) {
        ItemInput c = copy(b, b.quantityMode(), b.areaSqm(), b.piecesInput(), b.wastageMode(), b.wastageValue(),
            new BigDecimal(sqmPerPiece), b.piecesPerBox(), b.sqmPerBox());
        return new ItemInput(c.locationLabel(), c.catalogPriceId(), c.productCode(), c.brand(), c.model(), c.color(),
            c.texture(), c.sizeText(), c.thicknessMm(), c.sqmPerPiece(), c.quantityMode(), c.areaSqm(), c.piecesInput(),
            c.wastageMode(), c.wastageValue(), c.piecesPerBox(), new BigDecimal("2000.00"), c.discountPct(),
            c.originCountry(), c.leadTimeMinDays(), c.leadTimeMaxDays(), c.itemNotes(), c.lineType(), c.description(),
            c.quantity(), c.unit(), c.specialPriceSqm(), c.directNetPrice(), c.adjustmentPct(), c.adjustmentDeadline(),
            c.adjustmentAmount(), c.id(), c.sqmPerBox());
    }

    private int rowWithText(Sheet sheet, int col, String prefix) {
        return rowWithText(sheet, col, prefix, 0);
    }

    private int rowWithText(Sheet sheet, int col, String prefix, int fromRow) {
        for (int r = fromRow; r <= sheet.getLastRowNum(); r++) {
            if (str(sheet, r, col).startsWith(prefix)) return r;
        }
        throw new AssertionError("no row starting with \"" + prefix + "\" in column " + col);
    }

    /** A 3% ส่วนลดพิเศษ with a deadline — the row the English "Special discount" line is derived for. */
    private ItemInput adjustmentItem(String pct, LocalDate deadline) {
        return new ItemInput(null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, null, null, null, null, null,
            new BigDecimal(pct), deadline, null);
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
