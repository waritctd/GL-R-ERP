package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * V192.3 — ผู้ติดต่อ gains a Phone and an Email ("for sales record only … doesn't need to be render
 * in the actual pdf"), through the REAL {@link DealQuotationService} against real Postgres (the
 * production classes, hand-wired by {@link AbstractDealDirectQuotationIntegrationTest} — no Mockito
 * anywhere).
 *
 * <p>What only a real database can show:
 * <ul>
 *   <li>what a rep types is WRITTEN to {@code sales.quotation.ordered_by_phone / ordered_by_email}
 *       and READ back by the query every other read path uses;</li>
 *   <li>a value the service refuses leaves the row exactly as it was;</li>
 *   <li>a revision / a reorder clone carries both forward;</li>
 *   <li>the document the service renders from that stored row carries neither.</li>
 * </ul>
 *
 * <p>Written wrong-way-round where it matters: what must NOT be stored (a malformed address, an
 * over-long value), what must NOT change (an approved document), and what must NOT print. Every
 * "is absent" assertion below follows an assertion that the value was really there to begin with,
 * so none of them can pass on a row that simply never carried it.
 */
class DealQuotationOrderedByContactIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    private static final String NAME = "คุณวิชัย มั่นคง";
    /** Free text on purpose — an extension is normal. {@link #PHONE_NUMBER} is the part a search of
     * a rendered document looks for, so a renderer that reformatted the rest would still be caught. */
    private static final String PHONE = "089-765-4321 ต่อ 77";
    private static final String PHONE_NUMBER = "089-765-4321";
    private static final String EMAIL = "bid.contact@contractor-b.example";
    private static final String EMAIL_DOMAIN = "contractor-b.example";

    // ── create ───────────────────────────────────────────────────────────────────────────────

    @Test
    void create_storesPhoneAndEmailBesideTheName_andEveryReadReturnsThem() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);

        assertThat(created.orderedByPhone()).isEqualTo(PHONE);
        assertThat(created.orderedByEmail()).isEqualTo(EMAIL);
        assertThat(created.orderedByName()).isEqualTo(NAME);
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL)
            .containsEntry("ordered_by_name", NAME);
        // A fresh read, not the object create() handed back.
        DealQuotationDto read = quotationService.get(created.id(), salesActor);
        assertThat(read.orderedByPhone()).isEqualTo(PHONE);
        assertThat(read.orderedByEmail()).isEqualTo(EMAIL);

        // …and a quotation whose rep typed neither has neither.
        DealQuotationDto bare = quotationService.create(deal(salesActor), draft(), salesActor);
        assertThat(bare.orderedByPhone()).isNull();
        assertThat(bare.orderedByEmail()).isNull();
        assertThat(stored(bare.id())).containsEntry("ordered_by_phone", null).containsEntry("ordered_by_email", null);
    }

    @Test
    void create_trimsBoth_andABlankOneIsStoredAsNull() {
        DealQuotationDto padded = quotationService.create(deal(salesActor),
            contact(NAME, "  " + PHONE + "  ", "  " + EMAIL + " "), salesActor);

        assertThat(stored(padded.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL);

        DealQuotationDto blank = quotationService.create(deal(salesActor), contact(NAME, "   ", ""), salesActor);

        assertThat(blank.orderedByPhone()).isNull();
        assertThat(blank.orderedByEmail()).isNull();
        assertThat(stored(blank.id())).containsEntry("ordered_by_phone", null).containsEntry("ordered_by_email", null);
    }

    // ── update ───────────────────────────────────────────────────────────────────────────────

    @Test
    void update_replacesBoth() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);

        DealQuotationDto updated = quotationService.update(created.id(),
            contact(NAME, "02-123-4567", "purchasing@contractor-b.example"), salesActor);

        assertThat(updated.orderedByPhone()).isEqualTo("02-123-4567");
        assertThat(updated.orderedByEmail()).isEqualTo("purchasing@contractor-b.example");
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", "02-123-4567")
            .containsEntry("ordered_by_email", "purchasing@contractor-b.example");
    }

    /**
     * The same rule as the name beside them ({@code UpsertDealQuotationRequest#orderedByName}): the
     * editor states every header field on every save, so a blank — or a value it does not send at
     * all — is a real request to clear it. Clearing the contact leaves the name alone.
     */
    @Test
    void update_withABlankOrMissingValue_clearsIt_andLeavesTheNameAlone() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);
        assertThat(stored(created.id()))
            .as("there is something to clear")
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL);

        DealQuotationDto updated = quotationService.update(created.id(), contact(NAME, null, "  "), salesActor);

        assertThat(updated.orderedByPhone()).isNull();
        assertThat(updated.orderedByEmail()).isNull();
        assertThat(updated.orderedByName()).isEqualTo(NAME);
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", null)
            .containsEntry("ordered_by_email", null)
            .containsEntry("ordered_by_name", NAME);
    }

    /** The same lock as every other header field: an APPROVED document is frozen, the sales record
     * on it included. The edit is refused and the row keeps what it was approved with. */
    @Test
    void anApprovedDocument_keepsItsContact_whenAnEditIsRefused() {
        DealQuotationDto approved = approvedWithContact(deal(salesActor));
        assertThat(approved.orderedByPhone()).as("approval itself never touches the contact").isEqualTo(PHONE);
        assertThat(approved.orderedByEmail()).isEqualTo(EMAIL);

        refusal(() -> quotationService.update(approved.id(),
            contact(NAME, "02-999-9999", "someone.else@contractor-b.example"), salesActor), HttpStatus.CONFLICT);

        assertThat(stored(approved.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL);
    }

    // ── what is refused ──────────────────────────────────────────────────────────────────────

    /** The email must look like one. Each of these is refused BEFORE anything is written: the deal
     * still has no quotation at all afterwards. */
    @Test
    void aMalformedEmail_isRefusedOnCreate_andNothingIsWritten() {
        long ticket = deal(salesActor);

        for (String malformed : MALFORMED_EMAILS) {
            ApiException refused = refusal(
                () -> quotationService.create(ticket, contact(NAME, PHONE, malformed), salesActor),
                HttpStatus.BAD_REQUEST);
            assertThat(refused.getMessage()).as("the refusal of '%s' names the field, in Thai", malformed)
                .contains("อีเมล");
        }
        assertThat(quotationCount(ticket)).isZero();

        // The control: the very same request with a well-formed address is accepted, so it was the
        // address — and nothing else about this request — that each refusal above was about.
        DealQuotationDto accepted = quotationService.create(ticket, contact(NAME, PHONE, EMAIL), salesActor);
        assertThat(accepted.orderedByEmail()).isEqualTo(EMAIL);
    }

    @Test
    void aMalformedEmail_isRefusedOnUpdate_andTheStoredContactStands() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);

        for (String malformed : MALFORMED_EMAILS) {
            refusal(() -> quotationService.update(created.id(), contact(NAME, "02-123-4567", malformed), salesActor),
                HttpStatus.BAD_REQUEST);
        }

        // Not the email, and not the phone that travelled in the same refused request either.
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL);
    }

    /** An address is stored as typed — not lower-cased, not re-written — once it is well formed. */
    @Test
    void aWellFormedEmail_isStoredExactlyAsTyped() {
        DealQuotationDto created = quotationService.create(deal(salesActor),
            contact(NAME, null, "Somchai+QT@Example.CO.TH"), salesActor);

        assertThat(stored(created.id())).containsEntry("ordered_by_email", "Somchai+QT@Example.CO.TH");
    }

    @Test
    void anEmailOver254Characters_isRefused_andOneOfExactly254IsStored() {
        long ticket = deal(salesActor);
        String longest = "a".repeat(254 - "@example.com".length()) + "@example.com";
        assertThat(longest).hasSize(254);

        ApiException refused = refusal(
            () -> quotationService.create(ticket, contact(NAME, null, "a" + longest), salesActor),
            HttpStatus.BAD_REQUEST);
        assertThat(refused.getMessage()).contains("254");
        assertThat(quotationCount(ticket)).isZero();

        DealQuotationDto accepted = quotationService.create(ticket, contact(NAME, null, longest), salesActor);
        assertThat(stored(accepted.id())).containsEntry("ordered_by_email", longest);
    }

    /** Phone is FREE TEXT — an extension, several numbers — so nothing about its shape is refused;
     * only its length is, at the column's own width. */
    @Test
    void aPhoneOver50Characters_isRefused_andFreeTextUpTo50IsStoredAsTyped() {
        long ticket = deal(salesActor);
        String fifty = "0".repeat(50);

        ApiException refused = refusal(
            () -> quotationService.create(ticket, contact(NAME, fifty + "1", null), salesActor),
            HttpStatus.BAD_REQUEST);
        assertThat(refused.getMessage()).contains("50");
        assertThat(quotationCount(ticket)).isZero();

        DealQuotationDto atTheLimit = quotationService.create(ticket, contact(NAME, fifty, null), salesActor);
        assertThat(stored(atTheLimit.id())).containsEntry("ordered_by_phone", fifty);

        String severalNumbers = "02-123-4567 ต่อ 12, 081-234-5678";
        DealQuotationDto freeText = quotationService.create(deal(salesActor),
            contact(NAME, severalNumbers, null), salesActor);
        assertThat(stored(freeText.id())).containsEntry("ordered_by_phone", severalNumbers);
    }

    // ── copies ───────────────────────────────────────────────────────────────────────────────

    /** A re-issue is the same job and the same person to call: the revision and the reorder clone
     * both inherit the contact, exactly as they inherit the name beside it. */
    @Test
    void revisionAndReorder_bothCarryTheContact_andNeitherInventsOne() {
        DealQuotationDto approved = approvedWithContact(deal(salesActor));

        DealQuotationDto reorder = quotationService.createReorder(approved.id(), salesActor);
        assertThat(reorder.orderedByPhone()).isEqualTo(PHONE);
        assertThat(reorder.orderedByEmail()).isEqualTo(EMAIL);
        assertThat(stored(reorder.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL)
            .containsEntry("ordered_by_name", NAME);

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.orderedByPhone()).isEqualTo(PHONE);
        assertThat(revision.orderedByEmail()).isEqualTo(EMAIL);
        assertThat(stored(revision.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL);

        // …and the reverse: copying a document that has no contact never invents one.
        DealQuotationDto bare = approved(deal(salesActor), salesActor);
        DealQuotationDto bareRevision = quotationService.createRevision(bare.id(), salesActor);
        assertThat(stored(bareRevision.id()))
            .containsEntry("ordered_by_phone", null)
            .containsEntry("ordered_by_email", null);
    }

    // ── who reads it ─────────────────────────────────────────────────────────────────────────

    /** No authorization change: whoever could already open the quotation reads the contact on it —
     * the read-only finance and import viewers included. Nothing is blanked per role. */
    @Test
    void everyRoleThatMayViewTheQuotation_readsTheContactOnIt() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);

        for (String role : List.of("sales_manager", "ceo", "import", "account")) {
            DealQuotationDto seen = quotationService.get(created.id(), viewer(role));
            assertThat(seen.orderedByPhone()).as("phone as read by %s", role).isEqualTo(PHONE);
            assertThat(seen.orderedByEmail()).as("email as read by %s", role).isEqualTo(EMAIL);
        }
    }

    // ── the document the service renders from the stored row ─────────────────────────────────

    /**
     * "Doesn't need to be render in the actual pdf." The workbook the service itself renders for
     * the stored quotation, and the HTML the PDF is printed from, carry the NAME (that is what the
     * ผู้สั่งซื้อ slot is for) and neither the phone nor the email — not as typed, and not as the bare
     * number or the address's domain a reformatting renderer might leave behind.
     */
    @Test
    void theDocumentTheServiceRenders_printsTheName_butNeverThePhoneOrEmail() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);
        DealQuotationDto stored = quotationService.get(created.id(), salesActor);
        // Without these two, everything below would hold for a document that never carried them.
        assertThat(stored.orderedByPhone()).isEqualTo(PHONE);
        assertThat(stored.orderedByEmail()).isEqualTo(EMAIL);

        List<String> cells = sheetTexts(created.id());
        assertThat(cells).as("control: the name beside them prints").anyMatch(text -> text.contains(NAME));
        assertThat(cells).noneMatch(text -> text.contains(PHONE) || text.contains(PHONE_NUMBER));
        assertThat(cells).noneMatch(text -> text.contains(EMAIL) || text.contains(EMAIL_DOMAIN));

        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(stored, null, null);
        String html = new QuotationRenderer().toHtml(model);
        assertThat(html).as("control: the name beside them prints").contains(NAME);
        assertThat(html).doesNotContain(PHONE_NUMBER).doesNotContain(EMAIL).doesNotContain(EMAIL_DOMAIN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Refused on both sides of the API (the editor checks the same shapes before it saves). */
    private static final List<String> MALFORMED_EMAILS = List.of(
        "somchai.example.com",        // no @
        "somchai@",                   // nothing after it
        "@example.com",               // nothing before it
        "somchai@example",            // no dot in the domain — the ".com" that was never typed
        "som chai@example.com",       // a space inside
        "somchai@example@gmail.com"); // two @

    /**
     * A request as the editor sends one, carrying this ผู้ติดต่อ: it re-states the project name on
     * every save ({@code draft()} names none, which on UPDATE is a real request to clear it — see
     * {@code DealQuotationHideProjectNameIntegrationTest#edit}).
     */
    private static UpsertDealQuotationRequest contact(String name, String phone, String email) {
        UpsertDealQuotationRequest base = draft();
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(), base.offerDate(),
            base.depositPercent(), base.remainderMode(), base.creditDays(), base.validityDays(),
            base.validityMode(), base.validityUntil(), base.customerNotes(), base.priceMode(),
            base.documentLanguage(), base.currency(), base.printedByDisplayId(), base.salesRepDisplayId(),
            PROJECT_NAME, base.omitContactHonorific(), base.fullPaymentTerm(), name,
            base.hideProjectName(), phone, email, base.items());
    }

    private DealQuotationDto approvedWithContact(long ticketId) {
        DealQuotationDto created = quotationService.create(ticketId, contact(NAME, PHONE, EMAIL), salesActor);
        quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    /** Someone of {@code role} who is not the deal's owner and holds no quotation grant (the id
     * names no employee) — the plain role gate is all that lets them in. */
    private static UserPrincipal viewer(String role) {
        long id = 987_654L;
        return new UserPrincipal(id, role + "@glr.co.th", "Viewer " + role, role, id, true, LocalDate.now(), false,
            null, false);
    }

    private Map<String, Object> stored(long quotationId) {
        return jdbc.queryForMap(
            "SELECT ordered_by_name, ordered_by_phone, ordered_by_email FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId));
    }

    /** Every text cell of the workbook the SERVICE renders for the stored quotation — the same
     * render model the PDF is printed from. */
    private List<String> sheetTexts(long quotationId) {
        byte[] xls = quotationService.renderXlsx(quotationId, salesActor);
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
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return texts;
    }
}
