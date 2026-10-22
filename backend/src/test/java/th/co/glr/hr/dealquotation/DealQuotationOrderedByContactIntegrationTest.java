package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * V192.3 — ผู้ติดต่อ gains a Phone and an Email (first "for sales record only", reversed by the
 * owner 2026-10-13: they print on the document's customer contact line when filled), through the REAL {@link DealQuotationService} against real Postgres (the
 * production classes, hand-wired by {@link AbstractDealDirectQuotationIntegrationTest} — no Mockito
 * anywhere).
 *
 * <p>What only a real database can show:
 * <ul>
 *   <li>what a rep types is WRITTEN to {@code sales.quotation.ordered_by_phone / ordered_by_email}
 *       and READ back by the query every other read path uses;</li>
 *   <li>a value the service refuses leaves the row exactly as it was;</li>
 *   <li>a revision / a reorder clone carries both forward;</li>
 *   <li>the document the service renders from that stored row prints both (the master
 *       contact's own are not printed), and prints nothing for one left blank.</li>
 * </ul>
 *
 * <p>Written wrong-way-round where it matters: what must NOT be stored (a malformed address, an
 * over-long value), what must NOT change (an approved document), and what must NOT print (a blank one, as a bare label). Every
 * "is absent" assertion below follows an assertion that the value was really there to begin with,
 * so none of them can pass on a row that simply never carried it.
 */
class DealQuotationOrderedByContactIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    /** A name typed into the request's {@code orderedByName}: ignored since the 2026-10-18 ruling. */
    private static final String NAME = "คุณวิชัย มั่นคง";
    /** The customer contact picked on the quotation (see {@link #pickedContact}). Its own phone and
     * email differ from {@link #PHONE}/{@link #EMAIL}, so a test can tell which pair was saved. */
    private static final String PICKED_NAME = "สมศรี รับซื้อ";
    /** Free text on purpose — an extension is normal. */
    private static final String PHONE = "089-765-4321 ต่อ 77";
    private static final String EMAIL = "bid.contact@contractor-b.example";

    // ── create ───────────────────────────────────────────────────────────────────────────────

    /** The phone and email are saved from the request. The name beside them is the picked
     * contact's (ruling 2026-10-18): a name typed into the request is ignored. */
    @Test
    void create_storesPhoneAndEmailBesideTheName_andEveryReadReturnsThem() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            contact(pickedContact(ticket), NAME, PHONE, EMAIL), salesActor);

        assertThat(created.orderedByPhone()).isEqualTo(PHONE);
        assertThat(created.orderedByEmail()).isEqualTo(EMAIL);
        assertThat(created.orderedByName()).as("the picked contact's name, not the typed one").isEqualTo(PICKED_NAME);
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL)
            .containsEntry("ordered_by_name", PICKED_NAME);
        // A fresh read, not the object create() handed back.
        DealQuotationDto read = quotationService.get(created.id(), salesActor);
        assertThat(read.orderedByPhone()).isEqualTo(PHONE);
        assertThat(read.orderedByEmail()).isEqualTo(EMAIL);
        assertThat(read.orderedByName()).isEqualTo(PICKED_NAME);

        // …and a quotation with no contact picked has no name even with one typed, and one whose
        // rep typed neither phone nor email has neither.
        DealQuotationDto bare = quotationService.create(deal(salesActor), contact(NAME, null, null), salesActor);
        assertThat(bare.orderedByName()).isNull();
        assertThat(bare.orderedByPhone()).isNull();
        assertThat(bare.orderedByEmail()).isNull();
        assertThat(stored(bare.id())).containsEntry("ordered_by_name", null)
            .containsEntry("ordered_by_phone", null).containsEntry("ordered_by_email", null);
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

    /** Also: a save with no contact picked writes no signature name — neither the name typed into
     * the request nor a legacy typed name already on the row (ruling 2026-10-18). */
    @Test
    void update_replacesBoth() {
        DealQuotationDto created = quotationService.create(deal(salesActor), contact(NAME, PHONE, EMAIL), salesActor);
        jdbc.update("UPDATE sales.quotation SET ordered_by_name = 'legacy typed' WHERE quotation_id = :id",
            Map.of("id", created.id()));
        assertThat(stored(created.id())).as("a legacy typed name to clear").containsEntry("ordered_by_name", "legacy typed");

        DealQuotationDto updated = quotationService.update(created.id(),
            contact(NAME, "02-123-4567", "purchasing@contractor-b.example"), salesActor);

        assertThat(updated.orderedByPhone()).isEqualTo("02-123-4567");
        assertThat(updated.orderedByEmail()).isEqualTo("purchasing@contractor-b.example");
        assertThat(updated.orderedByName()).isNull();
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", "02-123-4567")
            .containsEntry("ordered_by_email", "purchasing@contractor-b.example")
            .containsEntry("ordered_by_name", null);
    }

    /**
     * The editor states every header field on every save, so a blank phone or email — or one it
     * does not send at all — is a real request to clear it. Clearing them leaves the name alone:
     * it is the picked contact's, kept by a save that sends no contact id, and a name typed into
     * the request is ignored.
     */
    @Test
    void update_withABlankOrMissingValue_clearsIt_andLeavesTheNameAlone() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            contact(pickedContact(ticket), NAME, PHONE, EMAIL), salesActor);
        assertThat(stored(created.id()))
            .as("there is something to clear")
            .containsEntry("ordered_by_phone", PHONE)
            .containsEntry("ordered_by_email", EMAIL);

        DealQuotationDto updated = quotationService.update(created.id(), contact(NAME, null, "  "), salesActor);

        assertThat(updated.orderedByPhone()).isNull();
        assertThat(updated.orderedByEmail()).isNull();
        assertThat(updated.orderedByName()).isEqualTo(PICKED_NAME);
        assertThat(stored(created.id()))
            .containsEntry("ordered_by_phone", null)
            .containsEntry("ordered_by_email", null)
            .containsEntry("ordered_by_name", PICKED_NAME);
    }

    /** The same lock as every other header field: an APPROVED document is frozen, the typed contact
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
            // The sentence the editor shows under the field before the save (quotationMeta
            // #orderedByEmailError) — one sentence for one problem, whoever catches it. The box is
            // the CUSTOMER's (อีเมลลูกค้า), so the sentence names the customer, not the ผู้ติดต่อ.
            assertThat(refused.getMessage()).as("the refusal of '%s'", malformed)
                .isEqualTo("Email ของลูกค้า: รูปแบบอีเมลไม่ถูกต้อง");
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
        assertThat(refused.getMessage()).isEqualTo("Email ของลูกค้ายาวเกิน 254 ตัวอักษร");
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
        assertThat(refused.getMessage()).isEqualTo("Phone ของลูกค้ายาวเกิน 50 ตัวอักษร");
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
            .containsEntry("ordered_by_name", PICKED_NAME);

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
        openToAccount(created.id()); // account reads only an APPROVED quotation on a deal in its scope

        for (String role : List.of("sales_manager", "ceo", "import", "account")) {
            DealQuotationDto seen = quotationService.get(created.id(), viewer(role));
            assertThat(seen.orderedByPhone()).as("phone as read by %s", role).isEqualTo(PHONE);
            assertThat(seen.orderedByEmail()).as("email as read by %s", role).isEqualTo(EMAIL);
        }
    }

    // ── the document the service renders from the stored row ─────────────────────────────────

    /**
     * Owner, 2026-10-13: "rendered if they have it, if not just leave it". The workbook the service
     * itself renders for the stored quotation, and the HTML the PDF is printed from, carry the
     * picked contact's NAME in the ผู้สั่งซื้อ slot (ruling 2026-10-18; never the typed one) AND the
     * phone and the email the rep typed.
     */
    @Test
    void theDocumentTheServiceRenders_printsTheName_andThePhoneAndEmail() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket,
            contact(pickedContact(ticket), NAME, PHONE, EMAIL), salesActor);
        DealQuotationDto stored = quotationService.get(created.id(), salesActor);
        // Without these two, everything below could hold for a document that printed some OTHER contact.
        assertThat(stored.orderedByPhone()).isEqualTo(PHONE);
        assertThat(stored.orderedByEmail()).isEqualTo(EMAIL);

        List<String> cells = sheetTexts(created.id());
        assertThat(cells).as("the picked contact in the ผู้สั่งซื้อ slot")
            .anyMatch(text -> text.contains("(" + PICKED_NAME + ")"));
        assertThat(cells).as("the typed name prints nowhere").noneMatch(text -> text.contains(NAME));
        assertThat(cells).anyMatch(text -> text.contains(PHONE));
        assertThat(cells).anyMatch(text -> text.contains(EMAIL));

        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(stored, null, null);
        String html = new QuotationRenderer().toHtml(model);
        assertThat(html).as("the picked contact in the ผู้สั่งซื้อ slot").contains("(" + PICKED_NAME + ")")
            .doesNotContain(NAME);
        assertThat(html).contains(PHONE).contains(EMAIL);
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
        return contact(null, name, phone, email);
    }

    /** {@link #contact(String, String, String)} with a picked customer contact. */
    private static UpsertDealQuotationRequest contact(Long contactId, String name, String phone, String email) {
        UpsertDealQuotationRequest base = draft();
        return new UpsertDealQuotationRequest(contactId, base.deptCode(), base.unitCode(), base.offerDate(),
            base.depositPercent(), base.remainderMode(), base.creditDays(), base.validityDays(),
            base.validityMode(), base.validityUntil(), base.customerNotes(), base.priceMode(),
            base.documentLanguage(), base.currency(), base.printedByDisplayId(), base.salesRepDisplayId(),
            PROJECT_NAME, base.omitContactHonorific(), base.fullPaymentTerm(), name,
            base.hideProjectName(), phone, email, base.items()).withRecipientType(base.recipientType());
    }

    private DealQuotationDto approvedWithContact(long ticketId) {
        return approved(ticketId, contact(pickedContact(ticketId), NAME, PHONE, EMAIL), salesActor);
    }

    /** A contact named {@link #PICKED_NAME} of the deal's own customer, with a phone and an email
     * of its own; returns its id. */
    private long pickedContact(long ticketId) {
        long customerId = jdbc.queryForObject("SELECT customer_id FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);
        return new th.co.glr.hr.customer.ContactRepository(jdbc)
            .create(customerId, "สมศรี", "รับซื้อ", null, "purchasing@customer-a.example", "02-555-0100").id();
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
}
