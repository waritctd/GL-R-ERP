package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.support.WorkbookTexts;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * V192.3 — ผู้ติดต่อ's Phone and Email. Until 2026-10-13 they were a sales record the document never
 * printed; the owner reversed that on 2026-10-13 ("for the email and phone for customer it should be
 * rendered if they have it, if not just leave it"). Pure render-layer coverage — no Spring, no DB.
 *
 * <p>The rule the first five tests pin: the header's contact line prints the phone and the email the
 * rep typed on the quotation when they are there; the customer-master contact (V167) never prints
 * on this line; and a value nobody has leaves no bare label behind. Each
 * carries a control (the name beside the two fields prints either way) so none of them can pass
 * merely because the fixture printed nothing — or everything. The real-DB round trip (the value the
 * service stored is the value the service's own renderer prints) is
 * {@code DealQuotationOrderedByContactIntegrationTest}.
 *
 * <p>The last two are not about printing: a copy of the quotation that silently went through an
 * older constructor shape would DROP the contact, and they fail until every copy passes it on.
 */
class DealQuotationOrderedByContactRenderTest {

    private static final String NAME = "คุณวิชัย มั่นคง";
    private static final String PHONE = "089-765-4321 ต่อ 77";
    private static final String EMAIL = "bid.contact@contractor-b.example";

    /** The customer-master contact snapshot (V167) — present on the fixture precisely so the
     * tests can show it does NOT print. */
    private static final String PRINTED_PHONE = "02-555-0100";
    private static final String PRINTED_EMAIL = "purchasing@customer-a.example";

    // ── printed when typed ───────────────────────────────────────────────────────────────────

    @Test
    void theHeaderLine_carriesTheTypedPhoneAndEmail_onEveryDocument() {
        for (DealQuotationDto quotation : everyDocument()) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation, null, null);

            assertThat(model.signatories().orderedBy()).as("control: the name is handed over").isEqualTo(NAME);
            String language = quotation.documentLanguage();
            assertThat(model.phoneLine()).as(language)
                .contains(phoneLabel(language) + PHONE)
                .contains(emailLabel(language) + EMAIL);
        }
    }

    @Test
    void aCellOfTheSheet_carriesThePhoneAndTheEmail() throws Exception {
        for (DealQuotationDto quotation : everyDocument()) {
            List<String> cells = sheetTexts(quotation);

            assertThat(cells).as("control: the name beside them prints").anyMatch(text -> text.contains(NAME));
            assertThat(cells).as(quotation.documentLanguage()).anyMatch(text -> text.contains(PHONE));
            assertThat(cells).as(quotation.documentLanguage()).anyMatch(text -> text.contains(EMAIL));
        }
    }

    @Test
    void theHtmlThePdfIsPrintedFrom_carriesBoth() {
        for (DealQuotationDto quotation : everyDocument()) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation, null, null);

            for (String html : List.of(new QuotationRenderer().toHtml(model), QuotationHtmlDocument.render(model))) {
                assertThat(html).as("control: the name beside them prints").contains(NAME);
                assertThat(html).as(quotation.documentLanguage()).contains(PHONE).contains(EMAIL);
            }
        }
    }

    /** Both present: only the one the rep typed on THIS quotation reaches the customer. */
    @Test
    void theTypedValues_winOverTheCustomerMasterContact() {
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
                withSalesContact(quotation(language, true)), null, null);

            assertThat(model.phoneLine()).as(language)
                .contains(phoneLabel(language) + PHONE).contains(emailLabel(language) + EMAIL)
                .doesNotContain(PRINTED_PHONE).doesNotContain(PRINTED_EMAIL);
        }
    }

    /** Nothing typed: nothing prints — the customer-master contact is not a fallback. */
    @Test
    void withoutTypedValues_nothingPrints_evenWithACustomerMasterContact() {
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation(language, true), null, null);

            assertThat(model.signatories().orderedBy()).as("control").isEqualTo(NAME);
            assertThat(model.phoneLine()).as(language)
                .doesNotContain(PRINTED_PHONE).doesNotContain(PRINTED_EMAIL)
                .doesNotContain("Phone").doesNotContain("Email").doesNotContain("Tel.").doesNotContain("E :");
        }
    }

    /** "If not, just leave it": nothing anywhere prints no label — not "Phone" with nothing after it. */
    @Test
    void withNeither_theLineCarriesNoBareLabel() {
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation(language, false), null, null);

            assertThat(model.signatories().orderedBy()).as("control").isEqualTo(NAME);
            assertThat(model.phoneLine()).as(language)
                .doesNotContain("Phone").doesNotContain("Email").doesNotContain("Tel.").doesNotContain("E :");
        }
    }

    /** Each field prints on its own: a typed phone beside a blank email prints the phone and no
     * email at all (not the master's), and the other way round. */
    @Test
    void eachField_printsOnItsOwn() {
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            String phoneOnly = DealQuotationRenderAdapter.toRenderModel(
                withSalesContact(quotation(language, true), PHONE, null), null, null).phoneLine();
            assertThat(phoneOnly).as(language + " typed phone only")
                .contains(phoneLabel(language) + PHONE)
                .doesNotContain(PRINTED_PHONE).doesNotContain(PRINTED_EMAIL).doesNotContain(EMAIL)
                .doesNotContain("Email").doesNotContain("E :");

            String emailOnly = DealQuotationRenderAdapter.toRenderModel(
                withSalesContact(quotation(language, true), null, EMAIL), null, null).phoneLine();
            assertThat(emailOnly).as(language + " typed email only")
                .contains(emailLabel(language) + EMAIL)
                .doesNotContain(PRINTED_PHONE).doesNotContain(PRINTED_EMAIL).doesNotContain(PHONE)
                .doesNotContain("Phone").doesNotContain("Tel.");
        }
    }

    /** A typed value that is only whitespace, or only a phone label ("โทร." with no number after
     * it — the service stores it because it is not blank), is nothing to print: no label, and
     * no master value in its place. */
    @Test
    void aTypedValueWithNothingInIt_printsNothing() {
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            for (boolean master : List.of(true, false)) {
                for (String emptyPhone : List.of("   ", "โทร.", "Tel. ")) {
                    String line = DealQuotationRenderAdapter.toRenderModel(
                        withSalesContact(quotation(language, master), emptyPhone, "  "), null, null).phoneLine();
                    assertThat(line).as(language + " master=" + master + " typed phone '" + emptyPhone + "'")
                        .doesNotContain("Phone").doesNotContain("Tel.").doesNotContain("Email").doesNotContain("E :")
                        .doesNotContain(PRINTED_PHONE).doesNotContain(PRINTED_EMAIL);
                }
            }
        }
    }

    private static String phoneLabel(String language) {
        return WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(language) ? "Tel. " : "Phone ";
    }

    private static String emailLabel(String language) {
        return WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(language) ? "E : " : "Email ";
    }

    // ── the contact survives every copy of the DTO and of the request ────────────────────────

    /** {@link DealQuotationDto} is rebuilt field-by-field by its copy methods, and its older
     * constructor shapes default any field they do not know. {@code withRemovedCeoItems} runs on
     * EVERY read of a quotation that came from a pricing request — a copy that went through an
     * older shape there would lose the rep's phone and email on the way to the screen. */
    @Test
    void everyCopyOfTheQuotation_keepsTheSalesContact() {
        DealQuotationDto quotation = withSalesContact(quotation(null, true));

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

    /** Thai and English, each with and without a customer-master contact on the document, every
     * one carrying the typed phone and email. */
    private static List<DealQuotationDto> everyDocument() {
        List<DealQuotationDto> documents = new ArrayList<>();
        for (String language : List.of(WastageCalculator.DOCUMENT_LANGUAGE_TH, WastageCalculator.DOCUMENT_LANGUAGE_EN)) {
            for (boolean customerMasterContact : List.of(true, false)) {
                documents.add(withSalesContact(quotation(language, customerMasterContact)));
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

    /** {@code quotation} carrying {@link #PHONE} and {@link #EMAIL}, through the record's canonical
     * constructor — every other field passed straight through. */
    private static DealQuotationDto withSalesContact(DealQuotationDto q) {
        return withSalesContact(q, PHONE, EMAIL);
    }

    /** {@code q} carrying exactly {@code phone} and {@code email} as the typed ผู้ติดต่อ values. */
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
        return WorkbookTexts.of(new QuotationRenderer().toXls(model));
    }
}
