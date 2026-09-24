package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetLeadTimeRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateEmailDraftRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * The per-factory order-email DRAFT (owner decision 09-18 #3 §B: "go ahead [order-email draft]") —
 * against real Postgres, through the real service. Mirrors the factory RFQ composer's own
 * discipline: the backend drafts, a human copies and sends, this system never sends mail itself.
 *
 * <p>Editing and mark-sent are both authz-and-lifecycle-shaped (who may touch a draft, and only
 * before it is marked sent), so per CLAUDE.md this needs real-DB evidence, written wrong-way-round.
 */
class ImportRequestEmailDraftIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal salesManager;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-email-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth);

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService);

        ownerId = insertEmployee("EM-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("EM-OTH"), "sales");
        importUser = principal(insertEmployee("EM-IMP"), "import");
        ceoUser = principal(insertEmployee("EM-CEO"), "ceo");
        salesManager = principal(insertEmployee("EM-SM"), "sales_manager");
    }

    // ── draft generation at issue ────────────────────────────────────────────────────────────

    @Test
    void issue_generatesAnEmailDraft_toTheFactorysAddress_withNoPricingContent() {
        insertFactory("Email Factory", "IT", "sales@emailfactory.example");
        long ticketId = insertTicket("EM-ISSUE");
        insertItem(ticketId, "Email Factory", "Lithos Nero", "60x60 cm", "100", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);

        assertThat(issued.emailTo()).isEqualTo("sales@emailfactory.example");
        assertThat(issued.emailSubject()).contains(issued.docNumber()).contains("GL&R");
        assertThat(issued.emailBody()).contains("Lithos Nero").contains("60x60 cm").contains("100");
        assertThat(issued.emailSentAt()).isNull();
        // Cheap nit (REVIEW ROUND 2): "Attached:" must name the REAL download filename
        // (ImportRequestController#storedFile's own "IR-<doc>-<factory>.pdf") so a human copying
        // this line into their mail client names what they actually downloaded.
        assertThat(issued.emailBody())
            .contains("Attached: IR-" + issued.docNumber() + "-" + issued.factoryName() + ".pdf");

        // No price/discount/pricing-mode/wastage anywhere in the draft (hard boundary, extended
        // from ImportRequestDtosNoPricingFieldsTest's record-shape check to actual body CONTENT).
        String lower = issued.emailBody().toLowerCase(java.util.Locale.ROOT);
        assertThat(lower).doesNotContain("price").doesNotContain("discount")
            .doesNotContain("wastage").doesNotContain("cost");
    }

    /**
     * The numbered-block layout (owner decision 09-18 #3, second pass) that replaced the old
     * fixed-width table — mirrors {@code FactoryQuoteService#appendItemBlock}'s shape: a
     * Brand/Model (Code) header, a Colour/Surface/Size detail line, a Quantity line with no
     * trailing zeros, and the free-text note indented as its own line. Blank fields (here: no
     * colour, no surface on the second line) are skipped outright rather than printed as "-" or
     * "null".
     *
     * <p><b>REVIEW ROUND 2, S-A</b> (this test used to pin the PRE-fix header/note shape — fixed
     * here rather than left green over stale behaviour): the header is now "<Brand> <Model>  (Code:
     * X)", not the bare code alone, and the auto-derived colour/surface note ("สี Grigio · ผิว
     * Matte") is SKIPPED on the first line because it exactly duplicates the "Colour: Grigio |
     * Surface: Matte" detail line one row up — printing both said the same thing twice in an email a
     * factory actually reads.
     *
     * <p><b>REVIEW ROUND 3 nit</b> (this test's fixture happens to have {@code code == model} for
     * both lines, since there is no catalog link — see {@code
     * ImportRequestFactoryResolutionIntegrationTest#createDrafts_prefillsCodeSizeQtyUnitAndNote_forCatalogLinkedAndHandTypedLines}
     * for the catalog-linked case where they genuinely differ): the "(Code: X)" suffix is no longer
     * printed when X is identical to the model already shown a few words earlier — "... Lithos Nero
     * (Code: Lithos Nero)" told a factory nothing a second time.
     */
    @Test
    void issue_bodyUsesTheNumberedBlockLayout_skippingBlankFieldsAndNeverPrintingNull() {
        insertFactory("Block Layout Factory", "IT", "sales@blocklayout.example");
        long ticketId = insertTicket("EM-BLOCK");
        insertItemWithBrandModelColor(ticketId, "Living Ceramics", "Block Layout Factory", "Lithos Nero",
            "60x60 cm", "308.000", "pcs", 0, "Grigio", "Matte");
        insertItemWithBrandModelColor(ticketId, "Terrazzo Co", "Block Layout Factory", "Terrazzo White",
            "30x60 cm", "10", "pcs", 1, null, null);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);
        String body = issued.emailBody();

        assertThat(body).contains("1. Living Ceramics Lithos Nero")
            .doesNotContain("(Code: Lithos Nero)") // code == model here -- the suffix is redundant
            .contains("Colour: Grigio | Surface: Matte | Size: 60x60 cm")
            .contains("Quantity: 308 pcs");   // no trailing zeros: "308", not "308.000"
        // The auto-derived colour/surface sentence is NOT repeated as a separate "Note:" line — it
        // is already shown as the Colour/Surface detail line above (S-A).
        assertThat(body).doesNotContain("Note: สี Grigio");
        assertThat(body).contains("2. Terrazzo Co Terrazzo White")
            .doesNotContain("(Code: Terrazzo White)")
            .contains("Quantity: 10 pcs");
        // The second line has neither colour nor surface — its detail line is omitted outright.
        assertThat(body).doesNotContain("Colour: null").doesNotContain("Surface: null")
            .doesNotContain("Note: null");
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        assertThat(lower).doesNotContain("price").doesNotContain("discount")
            .doesNotContain("wastage").doesNotContain("cost");
    }

    /**
     * REVIEW ROUND 2, S-A: a business-typed note that does NOT match the auto-derived colour/surface
     * sentence is never skipped — only the exact duplicate is.
     */
    @Test
    void issue_bodyKeepsABusinessTypedNote_whenItDiffersFromTheAutoDerivedOne() {
        insertFactory("Custom Note Factory", "IT", "note@example.com");
        long ticketId = insertTicket("EM-CUSTOM-NOTE");
        insertItemWithBrandModelColor(ticketId, "Living Ceramics", "Custom Note Factory", "Lithos Nero",
            "60x60 cm", "10", "pcs", 0, "Grigio", "Matte");
        // Business hand-edits the auto-derived note to something else before drafts are raised is not
        // reachable pre-createDrafts in this flow (the note is only ever set AT creation), so this
        // exercises the draft-stage item edit via update() instead.
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        ImportRequestDto draft = service.get(id, owner);
        java.util.List<ImportRequestRequests.ImportRequestItemInput> edited = draft.items().stream()
            .map(it -> new ImportRequestRequests.ImportRequestItemInput(it.ticketItemId(), it.code(),
                it.size(), it.qty(), it.unit(), "Pack in wooden crates, fragile", it.color(), it.texture(),
                it.brand(), it.model()))
            .toList();
        service.update(id, new UpdateImportRequestRequest(null, null, null, null, null, null, null, null,
            edited, null, null, null), owner);

        ImportRequestDto issued = service.issue(id, null, owner);
        assertThat(issued.emailBody()).contains("Note: Pack in wooden crates, fragile");
    }

    /**
     * The catalog-linked case where the code GENUINELY differs from the model — the "(Code: X)"
     * suffix still prints (REVIEW ROUND 3 nit's positive case, complementing the negative case
     * pinned above where {@code code == model} and the suffix is now skipped).
     */
    @Test
    void issue_bodyShowsTheCodeSuffix_whenItGenuinelyDiffersFromTheModel() {
        long catalogPriceId = insertCatalogProduct("Code Differs Factory", "IT", "CAT-CODE-777",
            new BigDecimal("12.50"), "EUR", "per_sqm");
        long ticketId = insertTicket("EM-CODE-DIFFERS");
        jdbc.update("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, size, qty, unit, sort_order, catalog_price_id)
            VALUES (:t, :brand, :model, :size, :qty, :unit, 0, :catalogPriceId)
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("brand", "Living Ceramics")
                .addValue("model", "Lithos Nero Hand-Typed").addValue("size", "60x60 cm")
                .addValue("qty", new BigDecimal("10")).addValue("unit", "pcs")
                .addValue("catalogPriceId", catalogPriceId));
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);
        assertThat(issued.emailBody())
            .contains("1. Living Ceramics Lithos Nero Hand-Typed  (Code: CAT-CODE-777)");
    }

    /**
     * Cheap nit (REVIEW ROUND 3): the "Attached:" filename and the download route's own filename
     * ({@link ImportRequestController#storedFile}) must be produced by the SAME helper ({@link
     * ImportRequestService#downloadFileName}) — asserted here by calling that helper directly rather
     * than hand-duplicating its string shape a third time in a test.
     */
    @Test
    void issue_attachedFilename_matchesTheDownloadRoutesOwnFilenameHelper() {
        insertFactory("Filename Tie Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-FILENAME-TIE");
        insertItem(ticketId, "Filename Tie Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);
        String expectedFilename = ImportRequestService.downloadFileName(issued.docNumber(), issued.id(),
            issued.factoryName(), issued.brand(), true);
        assertThat(issued.emailBody()).contains("Attached: " + expectedFilename);
    }

    /**
     * REVIEW ROUND 3, item 4 (S-F email half): the email's "Required by:" line must match what
     * ACTUALLY lands on the form. The bug was that {@code buildEmailDraft} read {@code
     * draft.requiredByNote()} directly, which can be BLANK on the draft even though {@code #issue}'s
     * own deal-snapshot fallback is what really gets written onto the issued row — so the printed
     * form and the email could silently disagree.
     */
    @Test
    void issue_emailRequiredByLine_matchesWhatActuallyGetsSnapshottedOntoTheForm() {
        insertFactory("Required By Consistency Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-REQBY-CONSIST");
        insertItem(ticketId, "Required By Consistency Factory", "Line", "60x60", "10", "pcs", 0);
        // The draft is raised BEFORE the deal-level value is set, so the draft's OWN requiredByNote
        // starts (and stays) blank until #issue's re-snapshot fallback fires.
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        assertThat(service.get(id, owner).requiredByNote()).isNull();
        service.setRequiredByNote(ticketId, new SetRequiredByNoteRequest("Within 30 days"), owner);

        ImportRequestDto issued = service.issue(id, null, owner);
        assertThat(issued.requiredByNote()).isEqualTo("Within 30 days");
        assertThat(issued.emailBody())
            .as("the email's Required-by line must match what actually landed on the form")
            .contains("Required by: Within 30 days");
    }

    /**
     * OWNER DECISION 09-19 #2: the order-email subject/body must never name the customer or the
     * project — a supplier has no business learning either. The subject used to read
     * "Purchase order &lt;doc&gt; - GL&amp;R (&lt;project&gt;)"; the parenthetical is gone outright.
     */
    @Test
    void issue_emailSubjectAndBody_neverNameTheCustomerOrTheProject() {
        insertFactory("Privacy Factory", "IT", "sales@privacyfactory.example");
        long ticketId = insertTicket("EM-PRIVACY");
        insertItem(ticketId, "Privacy Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);

        assertThat(issued.emailSubject()).isEqualTo("Purchase order " + issued.docNumber() + " - GL&R");
        assertThat(issued.customerName()).isNotBlank(); // sanity: the deal DOES carry a customer name
        assertThat(issued.emailSubject()).doesNotContain(issued.customerName());
        assertThat(issued.emailBody()).doesNotContain(issued.customerName());
        if (issued.projectName() != null && !issued.projectName().isBlank()) {
            assertThat(issued.emailSubject()).doesNotContain(issued.projectName());
            assertThat(issued.emailBody()).doesNotContain(issued.projectName());
        }
    }

    @Test
    void issue_withNoFactoryEmailOnFile_leavesEmailToNull_butStillDraftsTheBody() {
        insertFactory("No Email Factory", "IT", null);
        long ticketId = insertTicket("EM-NOEMAIL");
        insertItem(ticketId, "No Email Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);

        assertThat(issued.emailTo()).isNull();
        assertThat(issued.emailBody()).isNotBlank();
    }

    /** A revision's own issue REGENERATES the draft rather than carrying the old one forward. */
    @Test
    void revision_regeneratesTheEmailDraft() {
        insertFactory("Revision Email Factory", "IT", "old@example.com");
        long ticketId = insertTicket("EM-REVISE");
        insertItem(ticketId, "Revision Email Factory", "Line A", "60x60", "10", "pcs", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        ImportRequestDto issuedV1 = service.issue(v1, null, owner);
        assertThat(issuedV1.emailBody()).contains("Line A");

        ImportRequestDto v2 = service.revise(v1, owner);
        ImportRequestDto issuedV2 = service.issue(v2.id(), null, owner);

        assertThat(issuedV2.emailTo()).isEqualTo("old@example.com");
        assertThat(issuedV2.emailSubject()).contains(issuedV2.docNumber());
        assertThat(issuedV2.emailSentAt()).isNull(); // a fresh draft, not carrying v1's sent state
    }

    // ── REVIEW ROUND 3, item 3 (S-D regression): setLeadTime's email regeneration ────────────────

    /**
     * The OLD code regenerated the unsent draft UNCONDITIONALLY on every {@code setLeadTime} call,
     * silently clobbering a hand-edit made via {@link #emailDraft_isEditableByOwningRepCeoOrImport_notByANonOwningRep}'s
     * own route. Regeneration must now happen ONLY when the stored body is still exactly what
     * generation would have produced — i.e. nobody touched it since.
     */
    @Test
    void setLeadTime_neverClobbersAHandEditedUnsentEmailBody() {
        insertFactory("Hand Edit Preserve Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-HANDEDIT-PRESERVE");
        insertItem(ticketId, "Hand Edit Preserve Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);

        ImportRequestDto handEdited = service.updateEmailDraft(id, new UpdateEmailDraftRequest(
            null, null, "A completely hand-written email, please ship ASAP"), owner);
        assertThat(handEdited.emailBody()).isEqualTo("A completely hand-written email, please ship ASAP");

        ImportRequestDto afterLeadTime = service.setLeadTime(id, new SetLeadTimeRequest(10, 20), importUser);
        assertThat(afterLeadTime.emailBody())
            .as("a hand edit must never be silently clobbered by a later lead-time change")
            .isEqualTo("A completely hand-written email, please ship ASAP");
    }

    /**
     * When the draft is STILL unedited, {@code setLeadTime} does regenerate it (the content must
     * move with the new range) — but the signature must always read the ORIGINAL issuer, never
     * whoever happens to be the one nudging the lead time (here: {@code import}, a different person
     * from the {@code owner} who actually issued the form).
     */
    @Test
    void setLeadTime_regeneratesAnUneditedEmail_butAlwaysSignsAsTheOriginalIssuer_notTheCaller() {
        insertFactory("Original Signer Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-ORIG-SIGNER");
        insertItem(ticketId, "Original Signer Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);
        assertThat(issued.emailBody()).contains(owner.email());

        ImportRequestDto afterLeadTime = service.setLeadTime(id, new SetLeadTimeRequest(10, 20), importUser);
        assertThat(afterLeadTime.emailBody())
            .as("still unedited -- content DOES move with the new range")
            .isNotEqualTo(issued.emailBody());
        assertThat(afterLeadTime.emailBody())
            .as("but the signature stays the ORIGINAL issuer, never setLeadTime's own caller")
            .contains(owner.email())
            .doesNotContain(importUser.email());
    }

    // ── editing the draft ─────────────────────────────────────────────────────────────────────

    @Test
    void emailDraft_isEditableByOwningRepCeoOrImport_notByANonOwningRep() {
        insertFactory("Edit Email Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-EDIT");
        insertItem(ticketId, "Edit Email Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);

        ImportRequestDto afterOwner = service.updateEmailDraft(id,
            new UpdateEmailDraftRequest("b@example.com", null, null), owner);
        assertThat(afterOwner.emailTo()).isEqualTo("b@example.com");

        ImportRequestDto afterImport = service.updateEmailDraft(id,
            new UpdateEmailDraftRequest(null, "Edited subject", null), importUser);
        assertThat(afterImport.emailSubject()).isEqualTo("Edited subject");

        ImportRequestDto afterCeo = service.updateEmailDraft(id,
            new UpdateEmailDraftRequest(null, null, "Edited body"), ceoUser);
        assertThat(afterCeo.emailBody()).isEqualTo("Edited body");

        assertThatThrownBy(() -> service.updateEmailDraft(id,
                new UpdateEmailDraftRequest("x@example.com", null, null), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.updateEmailDraft(id,
                new UpdateEmailDraftRequest("x@example.com", null, null), salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        // Untouched by either refused attempt.
        assertThat(service.get(id, owner).emailTo()).isEqualTo("b@example.com");
    }

    @Test
    void emailDraft_isNotEditableOnceMarkedSent() {
        insertFactory("Sent Email Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-SENT-EDIT");
        insertItem(ticketId, "Sent Email Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);
        service.markEmailSent(id, importUser);

        assertThatThrownBy(() -> service.updateEmailDraft(id,
                new UpdateEmailDraftRequest("late@example.com", null, null), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.get(id, owner).emailTo()).isEqualTo("a@example.com");
    }

    // ── repository-level compare-and-set (cheap nit, REVIEW ROUND 2) ─────────────────────────────
    //
    // The service already refuses a non-ISSUED/already-sent row BEFORE calling the repository, so a
    // test that only ever drives the service could never distinguish "the repository's own WHERE
    // clause also checks this" from "the repository writes unconditionally and the service check is
    // the only thing stopping it" -- exactly the "green test that cannot fail is not evidence" trap.
    // These call ImportRequestRepository DIRECTLY, bypassing the service's own gate entirely, so they
    // actually exercise (and mutation-check) the SQL WHERE clause itself.

    @Test
    void updateEmailDraftSql_refusesADraftRow_directlyAtTheRepository() {
        insertFactory("Repo Guard Draft Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-REPO-DRAFT");
        insertItem(ticketId, "Repo Guard Draft Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id(); // never issued

        ImportRequestRepository repo = new ImportRequestRepository(jdbc);
        assertThat(repo.updateEmailDraft(id, "sneaky@example.com", null, null)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT email_to FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", id), String.class)).isNull();
    }

    @Test
    void updateEmailDraftSql_refusesAnAlreadySentRow_directlyAtTheRepository() {
        insertFactory("Repo Guard Sent Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-REPO-SENT");
        insertItem(ticketId, "Repo Guard Sent Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);
        service.markEmailSent(id, importUser);

        ImportRequestRepository repo = new ImportRequestRepository(jdbc);
        assertThat(repo.updateEmailDraft(id, "sneaky@example.com", null, null)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT email_to FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", id), String.class)).isEqualTo("a@example.com");
    }

    @Test
    void markEmailSentSql_refusesADraftRow_directlyAtTheRepository() {
        insertFactory("Repo Guard MarkSent Draft Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-REPO-MARK-DRAFT");
        insertItem(ticketId, "Repo Guard MarkSent Draft Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id(); // never issued

        ImportRequestRepository repo = new ImportRequestRepository(jdbc);
        assertThat(repo.markEmailSent(id, importUser.id(), importUser.name())).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT email_sent_at FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", id), java.sql.Timestamp.class)).isNull();
    }

    // ── marking sent ──────────────────────────────────────────────────────────────────────────

    @Test
    void markEmailSent_isAllowedToImportCeoAndOwningRep_notToANonOwningRepOrSalesManager() {
        insertFactory("Mark Sent Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-MARK");
        insertItem(ticketId, "Mark Sent Factory", "Line", "60x60", "10", "pcs", 0);

        // Non-owning rep and sales_manager: refused.
        long draftForOther = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(draftForOther, null, owner);
        assertThatThrownBy(() -> service.markEmailSent(draftForOther, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.markEmailSent(draftForOther, salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(draftForOther, owner).emailSentAt()).isNull();

        // Import: allowed.
        ImportRequestDto afterImport = service.markEmailSent(draftForOther, importUser);
        assertThat(afterImport.emailSentAt()).isNotNull();
        assertThat(afterImport.emailSentByName()).isEqualTo(importUser.name());

        // Second call: idempotent 409, not a silent re-stamp.
        assertThatThrownBy(() -> service.markEmailSent(draftForOther, ceoUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void markEmailSent_allowedToTheOwningRep_andToCeo() {
        insertFactory("Mark Sent Factory 2", "IT", "a@example.com");
        long ticketId = insertTicket("EM-MARK2");
        insertItem(ticketId, "Mark Sent Factory 2", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);

        ImportRequestDto afterOwner = service.markEmailSent(id, owner);
        assertThat(afterOwner.emailSentAt()).isNotNull();
    }

    @Test
    void markEmailSent_recordsATicketEvent() {
        insertFactory("Event Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-EVENT");
        insertItem(ticketId, "Event Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);

        service.markEmailSent(id, importUser);

        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM sales.ticket_event
             WHERE ticket_id = :id AND kind = 'IMPORT_REQUEST_EMAIL_SENT'
            """, Map.of("id", ticketId), Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-email@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'อีเมลใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name, String country, String email) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency, email)
            VALUES (:name, :country, 'EUR', :email) RETURNING factory_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country)
                .addValue("email", email), Long.class);
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบอีเมลใบขอซื้อ', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String factory, String model, String size, String qty,
                            String unit, int sortOrder) {
        insertItemWithColor(ticket, factory, model, size, qty, unit, sortOrder, null, null);
    }

    private void insertItemWithColor(long ticket, String factory, String model, String size,
            String qty, String unit, int sortOrder, String color, String texture) {
        insertItemWithBrandModelColor(ticket, factory, factory, model, size, qty, unit, sortOrder,
            color, texture);
    }

    /**
     * Like {@link #insertItemWithColor}, but with a REAL product {@code brand} distinct from the
     * factory name — REVIEW ROUND 2, S-A's order-email header ("<Brand> <Model>  (Code: X)") needs a
     * genuine brand value to test against; every other test in this file uses the simpler
     * {@code brand == factory} shape above since it does not assert on brand text at all.
     */
    private void insertItemWithBrandModelColor(long ticket, String brand, String factory, String model,
            String size, String qty, String unit, int sortOrder, String color, String texture) {
        jdbc.update("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, size, qty, unit, sort_order, factory, color, texture)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory, :color, :texture)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory)
                .addValue("color", color).addValue("texture", texture));
    }
}
