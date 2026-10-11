package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * V192.3 — ผู้ติดต่อ's Phone and Email are a sales record and are NEVER printed ("doesn't need to be
 * render in the actual pdf"). Pure render-layer coverage — no Spring, no DB.
 *
 * <p><b>The first four tests are REGRESSION GUARDS, and are green before the feature exists</b> —
 * nothing prints what nothing reads. They are here for the day someone "helpfully" fills the
 * header's empty phone or email line from the one the rep typed: the fixture with NO
 * customer-master contact is exactly the document where that fallback would fire. Each carries a
 * control (the name beside the two fields DOES print; the fourth test is the other control — a
 * customer-master contact's own phone/email DO print), so none of them can pass merely because the
 * fixture printed nothing. They have been seen to fail: with
 * {@code DealQuotationRenderAdapter#effectiveCustomerPhone} falling back to
 * {@code quotation.orderedByPhone()}, the first three go red and the fourth stays green. The
 * real-DB round trip (the value the service stored is the value the service's own renderer
 * ignores) is {@code DealQuotationOrderedByContactIntegrationTest}.
 *
 * <p>The last two are not guards: a copy of the quotation that silently went through an older
 * constructor shape would DROP the contact, and they fail until every copy passes it on.
 */
class DealQuotationOrderedByContactRenderTest {

    private static final String NAME = "คุณวิชัย มั่นคง";
    private static final String PHONE = "089-765-4321 ต่อ 77";
    private static final String PHONE_NUMBER = "089-765-4321";
    private static final String EMAIL = "bid.contact@contractor-b.example";
    private static final String EMAIL_DOMAIN = "contractor-b.example";

    /** The customer-master contact snapshot (V167) — what the document's header DOES print. */
    private static final String PRINTED_PHONE = "02-555-0100";
    private static final String PRINTED_EMAIL = "purchasing@customer-a.example";

    // ── regression guards: never printed ─────────────────────────────────────────────────────

    @Test
    void theRenderModel_isHandedTheName_andNeitherThePhoneNorTheEmail() {
        for (DealQuotationDto quotation : everyDocument()) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation, null, null);

            assertThat(model.signatories().orderedBy()).as("control: the name is handed over").isEqualTo(NAME);
            // A record's toString lists every component, nested ones included — so this is "no
            // field of the render model holds either value", whatever the field is called.
            assertThat(model.toString())
                .doesNotContain(PHONE_NUMBER).doesNotContain(EMAIL).doesNotContain(EMAIL_DOMAIN);
        }
    }

    @Test
    void noCellOfTheSheet_carriesThePhoneOrTheEmail() throws Exception {
        for (DealQuotationDto quotation : everyDocument()) {
            List<String> cells = sheetTexts(quotation);

            assertThat(cells).as("control: the name beside them prints").anyMatch(text -> text.contains(NAME));
            assertThat(cells).noneMatch(text -> text.contains(PHONE) || text.contains(PHONE_NUMBER));
            assertThat(cells).noneMatch(text -> text.contains(EMAIL) || text.contains(EMAIL_DOMAIN));
        }
    }

    @Test
    void theHtmlThePdfIsPrintedFrom_carriesNeither() {
        for (DealQuotationDto quotation : everyDocument()) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation, null, null);

            for (String html : List.of(new QuotationRenderer().toHtml(model), QuotationHtmlDocument.render(model))) {
                assertThat(html).as("control: the name beside them prints").contains(NAME);
                assertThat(html).doesNotContain(PHONE_NUMBER).doesNotContain(EMAIL).doesNotContain(EMAIL_DOMAIN);
            }
        }
    }

    /** The other half of the controls above: the header still prints the customer-master contact's
     * own phone and email, so "no phone, no email anywhere" is not what the guards are passing on. */
    @Test
    void theCustomerMasterContact_stillPrints_besideASalesContactThatDoesNot() throws Exception {
        DealQuotationDto quotation = withSalesContact(quotation(null, true), PHONE, EMAIL);

        List<String> cells = sheetTexts(quotation);
        assertThat(cells).anyMatch(text -> text.contains(PRINTED_PHONE));
        assertThat(cells).anyMatch(text -> text.contains(PRINTED_EMAIL));
        assertThat(cells).noneMatch(text -> text.contains(PHONE_NUMBER) || text.contains(EMAIL));
    }

    // ── the contact survives every copy of the DTO and of the request ────────────────────────

    /** {@link DealQuotationDto} is rebuilt field-by-field by its copy methods, and its older
     * constructor shapes default any field they do not know. {@code withRemovedCeoItems} runs on
     * EVERY read of a quotation that came from a pricing request — a copy that went through an
     * older shape there would lose the rep's phone and email on the way to the screen. */
    @Test
    void everyCopyOfTheQuotation_keepsTheSalesContact() {
        DealQuotationDto quotation = withSalesContact(quotation(null, true), PHONE, EMAIL);

        for (DealQuotationDto copy : List.of(
            quotation.withRemovedCeoItems(List.of()),
            quotation.withOrderedByName("คุณสมศรี ใจดี"),
            quotation.withHideProjectName(true))) {
            assertThat(copy.orderedByPhone()).isEqualTo(PHONE);
            assertThat(copy.orderedByEmail()).isEqualTo(EMAIL);
        }
    }

    @Test
    void theRequest_keepsThePhoneAndEmail_whenOnlyTheProjectTickIsReplaced() {
        UpsertDealQuotationRequest request = new UpsertDealQuotationRequest(null, null, null, null, 50, "ON_DELIVERY",
            null, 30, null, null, null, "NET", "TH", "THB", null, null, "โครงการ ทดสอบ", null, null, NAME,
            null, PHONE, EMAIL, List.of());

        UpsertDealQuotationRequest ticked = request.withHideProjectName(true);

        assertThat(ticked.hideProjectName()).isTrue();
        assertThat(ticked.orderedByPhone()).isEqualTo(PHONE);
        assertThat(ticked.orderedByEmail()).isEqualTo(EMAIL);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** Thai and English, each with and without a customer-master contact on the document. The
     * "without" pair is where a fallback to the rep's typed phone/email would show up. */
    private static List<DealQuotationDto> everyDocument() {
        List<DealQuotationDto> documents = new ArrayList<>();
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            for (boolean customerMasterContact : List.of(true, false)) {
                documents.add(withSalesContact(quotation(language, customerMasterContact), PHONE, EMAIL));
            }
        }
        return documents;
    }

    /** A DRAFT with no item lines — the header and signature blocks are all these tests read —
     * whose ผู้สั่งซื้อ slot names {@link #NAME}. Built through one of the record's older constructor
     * shapes, as {@code DealQuotationHideProjectNameRenderTest} builds its own. */
    private static DealQuotationDto quotation(String documentLanguage, boolean customerMasterContact) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 10, 11), "บริษัท ทดสอบ จำกัด", "99 ถนนทดสอบ กรุงเทพมหานคร 10110", null,
            customerMasterContact ? PRINTED_PHONE : null,
            customerMasterContact ? 9L : null, customerMasterContact ? "สมชาย ใจดี" : null,
            customerMasterContact ? PRINTED_PHONE : null, customerMasterContact ? PRINTED_EMAIL : null,
            "โครงการ ทดสอบ",
            "P003", "D002", LocalDate.of(2026, 10, 11), 30, "CREDIT", 30, 30, null, null,
            WastageCalculator.PRICE_MODE_NET,
            documentLanguage != null ? documentLanguage : WastageCalculator.DOCUMENT_LANGUAGE_TH,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            WastageCalculator.defaultCurrencyFor(documentLanguage),
            false, List.of(), Instant.parse("2026-10-11T00:00:00Z"), null)
            .withOrderedByName(NAME);
    }

    /** {@code quotation} carrying this phone and email, through the record's canonical constructor
     * — every other field passed straight through. */
    private static DealQuotationDto withSalesContact(DealQuotationDto q, String phone, String email) {
        return new DealQuotationDto(q.id(), q.number(), q.ticketId(), q.docStatus(), q.revisionNo(),
            q.parentQuotationId(), q.createdById(), q.createdByName(), q.createdByNameEn(), q.salesRepId(),
            q.salesRepName(), q.salesRepNameEn(), q.salesRepPhone(), q.submittedAt(), q.approvedById(),
            q.approvedByName(), q.approvedByNameEn(), q.approvedAt(), q.approvalNote(), q.quotationDate(),
            q.customerName(), q.customerAddress(), q.customerTaxId(), q.customerPhone(), q.contactId(),
            q.contactName(), q.contactPhone(), q.contactEmail(), q.projectName(), q.deptCode(), q.unitCode(),
            q.offerDate(), q.depositPercent(), q.remainderMode(), q.creditDays(), q.validityDays(),
            q.validityDate(), q.validityMode(), q.validityUntil(), q.customerNotes(), q.priceMode(),
            q.documentLanguage(), q.subtotalAmount(), q.vatAmount(), q.grandTotal(), q.currency(),
            q.approverHasSignature(), q.printedByDisplayId(), q.printedByDisplayName(),
            q.printedByDisplayNameEn(), q.salesRepDisplayId(), q.salesRepDisplayName(),
            q.salesRepDisplayNameEn(), q.salesRepDisplayPhone(), q.omitContactHonorific(), q.fullPaymentTerm(),
            q.derivedFromQuotationId(), q.derivedFromQuotationNumber(), q.derivedFromQuotationStatus(),
            q.items(), q.createdAt(), q.updatedAt(), q.origin(), q.pricingRequestId(),
            q.priceModeChangedFromCeo(), q.ceoPriceMode(), q.pricingRequestCode(), q.itemsRemovedFromCeoCount(),
            q.removedCeoItems(), q.orderedByName(), q.hideProjectName(), phone, email);
    }

    /** Every text cell of every sheet the XLS engine wrote for this quotation. */
    private static List<String> sheetTexts(DealQuotationDto quotation) throws Exception {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation, null, null);
        byte[] xls = new QuotationRenderer().toXls(model);
        List<String> texts = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()) {
                            texts.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        return texts;
    }
}
