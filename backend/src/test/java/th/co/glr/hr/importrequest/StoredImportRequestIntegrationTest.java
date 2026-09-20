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
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.IssueImportRequestRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
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
 * The STORED ใบขอซื้อ lifecycle, against real Postgres through the real service and repository —
 * rewritten for V184's per-FACTORY grain (was per-brand) and owner decision 1's new authorisation
 * model. The pre-V184 version of this class tested {@code createDrafts}/{@code issue}/etc. grouped
 * by {@code brand} and gated on {@code import}/{@code ceo}; both are now wrong: the grouping key is
 * {@code factory_id} and the write roles are the deal's OWNING sales rep + CEO (full BODY write),
 * {@code import} (step advance + post-issue lead time + order-email edit/mark-sent only — NOT the
 * printed footer, owner decision 09-19), the CEO ALONE (the printed footer), {@code sales_manager}
 * (read only).
 *
 * <p>These are WRITES — unlike the preview endpoints, which are read-only — so per CLAUDE.md they
 * need real-DB authz evidence, written wrong-way-round. Mockito cannot reach any of this: a mocked
 * repository would pass happily while the {@code UPDATE} and the two partial unique indexes did
 * something else.
 */
class StoredImportRequestIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;
    private ImportRequestRepository stored;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal salesManager;

    private long ticketId;
    private long padanaFactoryId;
    private long leaFactoryId;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-per-factory-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth);

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService);

        ownerId = insertEmployee("STORED-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("STORED-OTH"), "sales");
        importUser = principal(insertEmployee("STORED-IMP"), "import");
        ceoUser = principal(insertEmployee("STORED-CEO"), "ceo");
        accountUser = principal(insertEmployee("STORED-ACC"), "account");
        salesManager = principal(insertEmployee("STORED-SM"), "sales_manager");

        // Pre-seeded factory master rows (V184 grouping key) — the deal's lines are hand-typed
        // against these names via ticket_item.factory, which is the simplest of the three
        // resolution-cascade inputs (ImportRequestQueryRepository#factoryResolutionCandidates) and
        // exercises the everyday case, not the auto-create path (covered separately).
        padanaFactoryId = insertFactory("Padana Stored Test");
        leaFactoryId = insertFactory("LEA Stored Test");

        ticketId = insertTicket("IRSTORE-1", "บริษัท ยู่ฮุย อินทีเรีย จำกัด");
        insertItem(ticketId, "Padana Stored Test", "Lithos Nero Nat", "60x60 cm", "308", "pcs", 0);
        insertItem(ticketId, "Padana Stored Test", "Terrazzo White Nat", "30x60 cm", "2370", "pcs", 1);
        insertItem(ticketId, "LEA Stored Test", "Ceppo Grigio", "60x120 cm", "44", "pcs", 2);
    }

    // ── lifecycle (per-FACTORY as of V184) ──────────────────────────────────────────────────────

    /**
     * PR-B REVIEW ROUND 1, S9: {@code ImportRequestRepository.findByTicket} used to {@code ORDER BY
     * r.brand, r.version} — stale since V184 made {@code brand} a nullable DISPLAY snapshot
     * (derived from {@code ticket_item.brand}, see {@code ImportRequestService.FactoryGroup
     * #brandLabel}) and {@code factory_id}/{@code factory_name} the real, NOT NULL per-row identity
     * (owner decision 2, GLA-105). Reproduces the bug directly: the two lines' brands are seeded in
     * the OPPOSITE alphabetical order from their resolved factories, so the old ordering would
     * return [zeta, alpha] while the fix (ORDER BY factory_name) must return [alpha, zeta].
     */
    @Test
    void list_ordersByFactoryName_notByTheNullableBrandSnapshot() {
        long alphaFactoryId = insertFactory("Alpha Factory Order Test");
        long zetaFactoryId = insertFactory("Zeta Factory Order Test");
        long orderTicketId = insertTicket("IRORDER-1", "บริษัท ทดสอบลำดับ จำกัด");
        // brand "Zzz Brand" (sorts LAST) is on the factory that sorts FIRST by name, and vice
        // versa — see this test's own Javadoc above for why that combination is deliberate.
        insertItemWithDivergentBrand(orderTicketId, "Zzz Brand", "Alpha Factory Order Test",
            "Model A", "10x10 cm", "5", "pcs", 0);
        insertItemWithDivergentBrand(orderTicketId, "Aaa Brand", "Zeta Factory Order Test",
            "Model Z", "10x10 cm", "5", "pcs", 1);

        List<ImportRequestDto> drafts = service.createDrafts(orderTicketId, null, owner);
        // Confirms the fixture actually diverges brand from factory name before trusting the
        // ordering assertion below.
        assertThat(byFactory(drafts, alphaFactoryId).brand()).isEqualTo("Zzz Brand");
        assertThat(byFactory(drafts, zetaFactoryId).brand()).isEqualTo("Aaa Brand");

        List<ImportRequestDto> listed = service.list(orderTicketId, owner);
        assertThat(listed).extracting(ImportRequestDto::factoryId)
            .containsExactly(alphaFactoryId, zetaFactoryId);
    }

    @Test
    void createDrafts_isOnePerFactory_andSkipsFactoriesAlreadyCovered() {
        List<ImportRequestDto> first = service.createDrafts(ticketId, null, owner);
        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(ImportRequestStatus.DRAFT);
            assertThat(r.docNumber()).isNull();
            assertThat(r.issueDate()).isNull();
            // DRAFT carries no progress step yet (chk_import_request_step_pairing).
            assertThat(r.importStep()).isNull();
        });
        assertThat(first).extracting(ImportRequestDto::factoryId)
            .containsExactlyInAnyOrder(padanaFactoryId, leaFactoryId);
        assertThat(byFactory(first, padanaFactoryId).items()).extracting(i -> i.code())
            .containsExactly("Lithos Nero Nat", "Terrazzo White Nat");

        // Calling again must not duplicate: both factories are already covered.
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.list(ticketId, owner)).hasSize(2);
    }

    @Test
    void issue_mintsTheNumberAndFreezesTheDate_andSetsTheFirstStepToContacted() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();

        ImportRequestDto issued = service.issue(id, null, owner);

        assertThat(issued.status()).isEqualTo(ImportRequestStatus.ISSUED);
        assertThat(issued.docNumber()).matches("IR\\d{2}\\d{3}");
        assertThat(issued.issueDate()).isNotNull();
        assertThat(issued.issuedByName()).isNotNull();
        // First issue for this (deal, factory): step defaults to CONTACTED.
        assertThat(issued.importStep()).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(issued.importStepAt()).isEqualTo(issued.issueDate());

        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void issue_withAnOverride_doesNotBurnASequenceValue() {
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        int seqBefore = sequenceValue();

        service.issue(byFactory(drafts, padanaFactoryId).id(),
            new IssueImportRequestRequest("IR69068"), owner);
        assertThat(sequenceValue()).isEqualTo(seqBefore);

        ImportRequestDto minted = service.issue(byFactory(drafts, leaFactoryId).id(), null, owner);
        assertThat(minted.docNumber()).isNotEqualTo("IR69068");
        assertThat(sequenceValue()).isEqualTo(seqBefore + 1);
    }

    @Test
    void issue_withAnAlreadyUsedOverride_isRefused() {
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        service.issue(byFactory(drafts, padanaFactoryId).id(),
            new IssueImportRequestRequest("IR69068"), owner);

        assertThatThrownBy(() -> service.issue(byFactory(drafts, leaFactoryId).id(),
                new IssueImportRequestRequest("IR69068"), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * Two partial unique indexes on (ticket_id, factory_id): a correction is prepared while the
     * previous version is still live, and the revision's issue carries the predecessor's progress
     * step FORWARD rather than resetting it to CONTACTED (import-request-per-factory-PLAN.md §3).
     */
    @Test
    void revise_leavesThePreviousVersionIssuedUntilTheReplacementIsIssued_andCarriesTheStepForward() {
        long v1 = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(v1, null, owner);
        service.advanceStep(v1, new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), importUser);

        ImportRequestDto v2 = service.revise(v1, owner);
        assertThat(v2.status()).isEqualTo(ImportRequestStatus.DRAFT);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.items()).extracting(i -> i.code())
            .containsExactly("Lithos Nero Nat", "Terrazzo White Nat");
        // v1 is STILL the live issued form at this point.
        assertThat(service.get(v1, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);

        ImportRequestDto v2Issued = service.issue(v2.id(), null, owner);
        // Carried forward, not reset — v1 had already advanced past CONTACTED.
        assertThat(v2Issued.importStep()).isEqualTo(ImportRequestStep.ORDERED);

        ImportRequestDto superseded = service.get(v1, owner);
        assertThat(superseded.status()).isEqualTo(ImportRequestStatus.SUPERSEDED);
        assertThat(superseded.supersededById()).isEqualTo(v2.id());
    }

    /** A revision cannot even be prepared once the predecessor has reached RECEIVED. */
    @Test
    void revise_isRefusedOnceThePredecessorHasReceivedTheGoods() {
        long v1 = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(v1, null, owner);
        for (String step : List.of(ImportRequestStep.ORDERED, ImportRequestStep.PICKED_UP,
                ImportRequestStep.IN_TRANSIT, ImportRequestStep.AWAITING_CUSTOMS, ImportRequestStep.RECEIVED)) {
            service.advanceStep(v1, new AdvanceImportStepRequest(step, null, null), importUser);
        }

        assertThatThrownBy(() -> service.revise(v1, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void issuedForm_bodyIsFrozenEvenToTheOwningRep() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, null, owner);

        // Body: refused to import outright — FORBIDDEN, not CONFLICT, because import is not even in
        // FULL_WRITE_ROLES for a body change; requireFullWrite rejects it before the DRAFT-only
        // status check is ever reached.
        assertThatThrownBy(() -> service.update(id,
                new UpdateImportRequestRequest("แก้โครงการ", null, null, null, null, null, null, null, null, null, null, null),
                importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        // Body via the deal owner IS authorised to attempt it, but is refused anyway — CONFLICT,
        // because the form is ISSUED (frozen), not DRAFT. This is the status-layer check the import
        // case above never reaches.
        assertThatThrownBy(() -> service.update(id,
                new UpdateImportRequestRequest("แก้โครงการ", null, null, null, null, null, null, null, null, null, null, null),
                owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The printed form's footer (Checked By/date, Approve By/date, vessel ETA) — owner decision
     * 09-19, CEO ONLY, superseding decision 1's original wording that had let the owning rep and
     * import touch it too. Written wrong-way-round per CLAUDE.md: the owning rep, import and
     * sales_manager must each be refused, not just "CEO can" on its own.
     */
    @Test
    void issuedFormFooter_isWritableByCeoOnly_ownerRepImportAndSalesManagerAllRefused() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        ImportRequestDto issued = service.issue(id, null, owner);
        // vesselEtaNote is auto-prefilled by the SYSTEM at issue (owner decision 09-18 #3 §A) — a
        // service-internal write, unaffected by 09-19's actor-role gate. Captured here so the
        // "untouched" assertion below compares against the real starting value, not an assumed null.
        String vesselEtaNoteAtIssue = issued.vesselEtaNote();
        assertThat(vesselEtaNoteAtIssue).isNotBlank();
        UpdateImportRequestRequest footerEdit = new UpdateImportRequestRequest(
            null, null, null, "ETA ปลายเดือน", "ธันย์ยพร", LocalDate.of(2026, 3, 7), null, null, null,
            null, null, null);

        assertThatThrownBy(() -> service.update(id, footerEdit, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.update(id, footerEdit, owner))
            .as("the deal's OWNING sales rep — full body/lifecycle write, but not the footer")
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.update(id, footerEdit, salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        // None of the three refused attempts touched anything: still the system's auto-prefilled
        // value, not the "ETA ปลายเดือน" any of them tried to write.
        ImportRequestDto untouched = service.get(id, ceoUser);
        assertThat(untouched.vesselEtaNote()).isEqualTo(vesselEtaNoteAtIssue);
        assertThat(untouched.checkedByName()).isNull();

        ImportRequestDto after = service.update(id, footerEdit, ceoUser);
        assertThat(after.vesselEtaNote()).isEqualTo("ETA ปลายเดือน");
        assertThat(after.checkedByName()).isEqualTo("ธันย์ยพร");
        assertThat(after.checkedDate()).isEqualTo(LocalDate.of(2026, 3, 7));
    }

    @Test
    void deleteDraft_removesADraftButNeverAnIssuedForm() {
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        long lea = byFactory(drafts, leaFactoryId).id();
        long padana = byFactory(drafts, padanaFactoryId).id();
        service.issue(padana, null, owner);

        service.deleteDraft(lea, owner);
        assertThat(service.list(ticketId, owner)).extracting(ImportRequestDto::id)
            .containsExactly(padana);

        assertThatThrownBy(() -> service.deleteDraft(padana, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aStoredFormRendersFromItsOwnSnapshot_notFromTheDeal() throws Exception {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, new IssueImportRequestRequest("IR69068"), owner);

        jdbc.update("UPDATE sales.ticket SET customer_name = 'ลูกค้าใหม่' WHERE ticket_id = :id",
            Map.of("id", ticketId));

        String text = textOf(service.renderStored(id, importUser));
        assertThat(text).contains("IR69068").contains("Lithos Nero Nat");
        assertThat(text).contains("ยู่ฮุย").doesNotContain("ลูกค้าใหม่");
    }

    /**
     * REVIEW ROUND 2, S-B: the printed header must name the FACTORY this particular form is for —
     * V184 made factory the grouping key, so a per-factory form that printed only a bare brand label
     * (or a brand shared with ANOTHER factory's own form) would no longer say which one it is.
     */
    @Test
    void aStoredFormsPrintedHeader_namesItsOwnFactory() throws Exception {
        long padanaId = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(padanaId, new IssueImportRequestRequest("IR-S-B-1"), owner);

        String text = textOf(service.renderStored(padanaId, importUser));
        assertThat(text).contains("Padana Stored Test");
    }

    /**
     * OWNER DECISION 09-19 #2: the copy that actually reaches a supplier must not show the customer
     * name or the deposit-received date. The INTERNAL copy (everything, the default — same call
     * every existing test above already makes) still shows both; only the explicit FACTORY copy
     * blanks them. Everything else — factory header, lines, required-by, doc number — is IDENTICAL
     * between the two.
     */
    @Test
    void factoryCopy_omitsCustomerNameAndDepositDate_internalCopyKeepsBoth() throws Exception {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, new IssueImportRequestRequest("IR-OWNER-0919-2"), owner);

        String internal = textOf(service.renderStored(id, importUser, false));
        assertThat(internal).contains("ยู่ฮุย").contains("Padana Stored Test").contains("IR-OWNER-0919-2");

        String factoryCopy = textOf(service.renderStored(id, importUser, true));
        assertThat(factoryCopy).doesNotContain("ยู่ฮุย");
        // Everything else is unchanged between the two copies.
        assertThat(factoryCopy).contains("Padana Stored Test").contains("IR-OWNER-0919-2")
            .contains("Lithos Nero Nat");
    }

    /**
     * V184's DB-layer backstop, tested independently of the service's own "skip an already-covered
     * factory" check ({@link #createDrafts_isOnePerFactory_andSkipsFactoriesAlreadyCovered}): even a
     * raw SQL insert that bypasses the service entirely cannot put a second ISSUED row on the same
     * (ticket, factory) — {@code ux_import_request_ticket_factory_issued}, {@code CREATE UNIQUE
     * INDEX ... WHERE status = 'ISSUED'}. "Enforced in the database, not by a service-side
     * check-then-insert, which races" (V154's own comment on the ancestor, brand-keyed index this
     * one replaced).
     */
    @Test
    void uniquePartialIndex_refusesASecondIssuedRowForTheSameTicketAndFactory_evenBypassingTheService() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, new IssueImportRequestRequest("IR-UNIQ-1"), owner);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, factory_id, factory_name, version, status, doc_number, issue_date,
                 import_step, import_step_at, first_issued_date)
            VALUES (:ticketId, :factoryId, :factoryName, 99, 'ISSUED', 'IR-UNIQ-2', CURRENT_DATE,
                    'CONTACTED', CURRENT_DATE, CURRENT_DATE)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("factoryId", padanaFactoryId).addValue("factoryName", "Padana Stored Test")))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ── advance-step (V184, import/ceo only, forward-only with skips allowed) ───────────────────

    @Test
    void advanceStep_forwardSkipsAllowed_butNeverBackwards() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, null, owner);

        // Skip ORDERED and PICKED_UP entirely — forward skips are allowed by owner decision.
        ImportRequestDto afterSkip = service.advanceStep(id,
            new AdvanceImportStepRequest(ImportRequestStep.IN_TRANSIT, null, "ข้ามขั้นตอน"), importUser);
        assertThat(afterSkip.importStep()).isEqualTo(ImportRequestStep.IN_TRANSIT);

        // Backwards is refused, not silently ignored.
        assertThatThrownBy(() -> service.advanceStep(id,
                new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // ...and to the SAME step (not strictly ahead) is refused too.
        assertThatThrownBy(() -> service.advanceStep(id,
                new AdvanceImportStepRequest(ImportRequestStep.IN_TRANSIT, null, null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void advanceStep_isRefusedToSalesAndSalesManager_butAllowedToCeo() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, null, owner);

        assertThatThrownBy(() -> service.advanceStep(id,
                new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.advanceStep(id,
                new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(id, owner).importStep()).isEqualTo(ImportRequestStep.CONTACTED);

        assertThat(service.advanceStep(id,
            new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), ceoUser).importStep())
            .isEqualTo(ImportRequestStep.ORDERED);
    }

    // ── authorisation (owner decision 1), asked the wrong way round ─────────────────────────────

    @Test
    void nonOwningSalesRepCannotCreateIssueOrRevise() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.findByTicket(ticketId)).isEmpty();

        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        assertThatThrownBy(() -> service.issue(id, null, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(id, owner).status()).isEqualTo(ImportRequestStatus.DRAFT);

        service.issue(id, null, owner);
        assertThatThrownBy(() -> service.revise(id, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void importCannotCreateIssueOrRevise() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.findByTicket(ticketId)).isEmpty();

        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        assertThatThrownBy(() -> service.issue(id, null, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        service.issue(id, null, owner);
        assertThatThrownBy(() -> service.revise(id, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void accountCannotRaiseAnImportRequest() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, accountUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.findByTicket(ticketId)).isEmpty();
    }

    /**
     * {@code sales_manager} reads (owner decision 1's third tier) but must be refused every write —
     * create, issue, footer edit and step advance alike.
     */
    @Test
    void salesManagerIsReadOnly() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, null, owner);

        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, "ETA", null, null, null, null, null, null, null, null), salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.advanceStep(id,
                new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // But CAN read.
        assertThat(service.get(id, salesManager).id()).isEqualTo(id);
        assertThat(service.list(ticketId, salesManager)).hasSize(2);
    }

    @Test
    void ceoCanRaiseAndIssue() {
        long id = byFactory(service.createDrafts(ticketId, null, ceoUser), padanaFactoryId).id();
        assertThat(service.issue(id, null, ceoUser).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    // ── กำหนดวันที่ต้องการของ — SALES's field, a DIFFERENT gate ────────────────────────────────

    /**
     * REVIEW ROUND 2, S-F cheap nit: renamed from {@code
     * requiredByNote_isSetByTheOwningRep_andSnapshottedOntoTheFormAtIssue} — that name was
     * misleading, since the assertion runs right after {@code createDrafts} and never calls {@code
     * issue} at all, so what it actually pins is the DRAFT-CREATION-time snapshot, not an at-issue
     * one. The genuine at-issue re-snapshot (new behaviour, S-F) is
     * {@link #requiredByNote_isReSnapshottedAtIssue_onlyWhenStillBlankOnTheDraft}.
     */
    @Test
    void requiredByNote_isSetByTheOwningRep_andSnapshottedOntoTheFormAtCreation() {
        service.setRequiredByNote(ticketId, new SetRequiredByNoteRequest("Within 21/5/26"), owner);

        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        assertThat(service.get(id, owner).requiredByNote()).isEqualTo("Within 21/5/26");
    }

    /**
     * REVIEW ROUND 2, S-F: {@code requiredByNote} is ALSO a body field on the FORM itself (not just
     * the deal-level snapshot source above) — editable by the owning rep/CEO while DRAFT, through
     * {@link #update}, distinct from {@link SetRequiredByNoteRequest} which sets the DEAL's value.
     */
    @Test
    void requiredByNote_isEditableOnTheDraftByOwningRepOrCeo_notByANonOwningRepOrImport() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();

        ImportRequestDto afterOwner = service.update(id, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, null, null, "Within 10 days"), owner);
        assertThat(afterOwner.requiredByNote()).isEqualTo("Within 10 days");

        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, null, null, "x"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, null, null, "x"), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(id, owner).requiredByNote()).isEqualTo("Within 10 days");

        ImportRequestDto afterCeo = service.update(id, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, null, null, "Within 20 days"), ceoUser);
        assertThat(afterCeo.requiredByNote()).isEqualTo("Within 20 days");
    }

    /**
     * REVIEW ROUND 2, S-F: a draft's OWN {@code requiredByNote}, if it is STILL blank at issue,
     * is re-snapshotted at that point from the deal's CURRENT value — not frozen forever at
     * whatever it read (nothing, in the common case) at draft creation. Once the owning rep/CEO has
     * typed one directly onto the form, issue never overwrites it.
     */
    @Test
    void requiredByNote_isReSnapshottedAtIssue_onlyWhenStillBlankOnTheDraft() {
        // ONE createDrafts call raises BOTH factories' drafts at once (setUp's ticket already has
        // items for both) — capture both ids from it rather than calling createDrafts a second
        // time, which would find every factory already covered and 409.
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        long blankId = byFactory(drafts, padanaFactoryId).id();
        long typedId = byFactory(drafts, leaFactoryId).id();
        assertThat(service.get(blankId, owner).requiredByNote()).isNull();
        service.setRequiredByNote(ticketId, new SetRequiredByNoteRequest("Within 21/5/26"), owner);

        ImportRequestDto issued = service.issue(blankId, null, owner);
        assertThat(issued.requiredByNote()).isEqualTo("Within 21/5/26");

        service.update(typedId, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, null, null, "Custom, do not overwrite"),
            owner);
        service.setRequiredByNote(ticketId, new SetRequiredByNoteRequest("A different deal-level note"), owner);

        ImportRequestDto issuedTyped = service.issue(typedId, null, owner);
        assertThat(issuedTyped.requiredByNote()).isEqualTo("Custom, do not overwrite");
    }

    /**
     * REVIEW ROUND 3, item 2 (S-F regression, confirmed against real Postgres): {@code revise} must
     * carry the ISSUED form's OWN {@code requiredByNote} forward, not fall back to whatever the DEAL
     * currently reads. The bug was that {@code revise}'s call to {@code insertDraft} left the new
     * override argument unset, so the row fell all the way back to {@code
     * TicketSnapshot#requiredByNote} — silently dropping anything the rep had typed directly onto
     * THIS form (rather than the deal-level field {@link SetRequiredByNoteRequest} sets) the moment
     * it was revised.
     */
    @Test
    void requiredByNote_typedOnTheFormBeforeItsFirstIssue_survivesARevision() {
        long v1 = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.update(v1, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, null, null, "Before Songkran"), owner);

        ImportRequestDto issuedV1 = service.issue(v1, null, owner);
        assertThat(issuedV1.requiredByNote()).isEqualTo("Before Songkran");

        ImportRequestDto v2 = service.revise(v1, owner);
        assertThat(v2.requiredByNote()).as("carried onto the fresh DRAFT immediately, before any issue")
            .isEqualTo("Before Songkran");

        ImportRequestDto issuedV2 = service.issue(v2.id(), null, owner);
        assertThat(issuedV2.requiredByNote()).isEqualTo("Before Songkran");
    }

    // ── REVIEW ROUND 1, S3: read access is also ownership-scoped, wrong-way-round ────────────────

    /**
     * {@code requireRead} lets CEO/import/sales_manager read ANY deal's IR, but a {@code sales} rep
     * must be the deal's OWN owner — {@code otherSalesRep} holds the identical role, so this is the
     * case that actually tests the ownership half rather than the role half.
     */
    @Test
    void nonOwningSalesRepCannotReadListOrDownloadAnotherRepsIr() throws Exception {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, new IssueImportRequestRequest("IR-S3-READ"), owner);

        assertThatThrownBy(() -> service.get(id, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.list(ticketId, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.renderStored(id, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // The owning rep, CEO, import and sales_manager — every role requireRead DOES admit — must
        // still succeed, so the case above is a real ownership scope, not a blanket outage.
        assertThat(service.get(id, owner).id()).isEqualTo(id);
        assertThat(service.get(id, ceoUser).id()).isEqualTo(id);
        assertThat(service.get(id, importUser).id()).isEqualTo(id);
        assertThat(service.get(id, salesManager).id()).isEqualTo(id);
    }

    /**
     * {@code deleteDraft} is a {@link #FULL_WRITE_ROLES}-only action per the class Javadoc — import
     * (footer-only) and sales_manager (read-only) must both be refused, not just a non-owning rep
     * (already covered by {@link #nonOwningSalesRepCannotCreateIssueOrRevise}/{@link
     * #importCannotCreateIssueOrRevise}, which do not touch delete).
     */
    @Test
    void importAndSalesManagerCannotDeleteDraft() {
        long lea = byFactory(service.createDrafts(ticketId, null, owner), leaFactoryId).id();

        assertThatThrownBy(() -> service.deleteDraft(lea, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.deleteDraft(lea, salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Still there — neither refused attempt deleted it.
        assertThat(stored.findById(lea)).isPresent();
    }

    // ── REVIEW ROUND 1, S4: a stored ใบขอซื้อ must refuse on an inactive deal ───────────────────

    /**
     * {@code issue}/{@code revise}/{@code update}/{@code setLeadTime}/{@code advanceStep} must all
     * refuse once the deal has gone LOST — matching every legacy ticket-mutating method's own
     * {@code TicketService#requireActive} guard, which this stored aggregate did not previously
     * share.
     */
    @Test
    void everyMutatingMethod_isRefusedOnceTheDealIsInactive() {
        long padana = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(padana, null, owner);
        long lea = byFactory(service.list(ticketId, owner), leaFactoryId).id();
        // A THIRD, not-yet-covered factory, added before markLost — so the post-lost createDrafts
        // call below fails for the INACTIVE-DEAL reason this test is about, not the unrelated
        // "every factory already covered" conflict createDrafts would otherwise throw first.
        long thirdFactoryId = insertFactory("Inactive Deal Third Factory");
        insertItem(ticketId, "Inactive Deal Third Factory", "Line C", "60x60 cm", "5", "pcs", 3);

        markLost(ticketId);

        // Cheap nit (REVIEW ROUND 2): extended to createDrafts, updateEmailDraft, markEmailSent,
        // deleteDraft — everyMutatingMethod now really does mean every one.
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.list(ticketId, owner)).extracting(ImportRequestDto::factoryId)
            .doesNotContain(thirdFactoryId);
        assertThatThrownBy(() -> service.issue(lea, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.revise(padana, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // Footer write is CEO-only as of owner decision 09-19 — importUser would 403 before ever
        // reaching the inactive-deal check this test is about, so ceoUser (the role actually
        // authorised to write the footer) is the one that exercises requireDealActive here.
        assertThatThrownBy(() -> service.update(padana, new UpdateImportRequestRequest(
                null, null, null, "ETA", null, null, null, null, null, null, null, null), ceoUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.setLeadTime(padana,
                new th.co.glr.hr.importrequest.ImportRequestRequests.SetLeadTimeRequest(1, 2), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.advanceStep(padana,
                new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.updateEmailDraft(padana,
                new th.co.glr.hr.importrequest.ImportRequestRequests.UpdateEmailDraftRequest(
                    "x@example.com", null, null), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.markEmailSent(padana, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.deleteDraft(lea, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Nothing moved: padana's step is still whatever issue() set it to (CONTACTED), its email
        // never got marked sent, and lea's draft is still there (deleteDraft refused).
        assertThat(stored.findById(padana).orElseThrow().importStep()).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(stored.findById(padana).orElseThrow().emailSentAt()).isNull();
        assertThat(stored.findById(lea)).isPresent();
    }

    // ── REVIEW ROUND 1, S7: the arrival anchor never moves on a revision ─────────────────────────

    /**
     * A same-day (or later) revision must NOT push the derived arrival window out — it stays
     * anchored on the FACTORY'S FIRST issue date (owner default decision, flagged for the owner).
     */
    @Test
    void revision_doesNotMoveTheArrivalAnchor() {
        long v1 = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.update(v1, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, 10, 20, null), owner);
        ImportRequestDto issuedV1 = service.issue(v1, null, owner);
        LocalDate firstIssued = issuedV1.firstIssuedDate();
        assertThat(firstIssued).isEqualTo(issuedV1.issueDate());
        assertThat(issuedV1.expectedArrivalFrom()).isEqualTo(firstIssued.plusDays(10));
        assertThat(issuedV1.expectedArrivalTo()).isEqualTo(firstIssued.plusDays(20));

        ImportRequestDto v2 = service.revise(v1, owner);
        ImportRequestDto issuedV2 = service.issue(v2.id(), null, owner);

        // Carried forward, unchanged, even though v2's OWN issueDate is a fresh "today" stamp.
        assertThat(issuedV2.firstIssuedDate()).isEqualTo(firstIssued);
        assertThat(issuedV2.expectedArrivalFrom()).isEqualTo(firstIssued.plusDays(10));
        assertThat(issuedV2.expectedArrivalTo()).isEqualTo(firstIssued.plusDays(20));
    }

    // ── REVIEW ROUND 1 nit: advanceStep eventDate bounds ─────────────────────────────────────────

    @Test
    void advanceStep_refusesAFutureEventDate_andADateBeforeTheFirstIssue() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        ImportRequestDto issued = service.issue(id, null, owner);

        assertThatThrownBy(() -> service.advanceStep(id, new AdvanceImportStepRequest(
                ImportRequestStep.ORDERED, LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(1), null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.advanceStep(id, new AdvanceImportStepRequest(
                ImportRequestStep.ORDERED, issued.firstIssuedDate().minusDays(1), null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Neither refused attempt moved the step.
        assertThat(service.get(id, owner).importStep()).isEqualTo(ImportRequestStep.CONTACTED);
    }

    /** REVIEW ROUND 1 nit: the ticket_event message names the step in the owner's own Thai labels,
     * not the raw uppercase code. */
    @Test
    void advanceStep_eventMessageUsesThaiStepLabels() {
        long id = byFactory(service.createDrafts(ticketId, null, owner), padanaFactoryId).id();
        service.issue(id, null, owner);

        service.advanceStep(id, new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, "ทดสอบ"), importUser);

        String message = jdbc.queryForObject("""
            SELECT message FROM sales.ticket_event
             WHERE ticket_id = :id AND kind = 'IMPORT_STEP_ADVANCED'
             ORDER BY event_id DESC LIMIT 1
            """, Map.of("id", ticketId), String.class);
        assertThat(message).contains("ติดต่อโรงงาน").contains("สั่งซื้อแล้ว")
            .doesNotContain("CONTACTED").doesNotContain("ORDERED");
    }

    private void markLost(long ticket) {
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :id",
            Map.of("id", ticket));
    }

    @Test
    void requiredByNote_isRefusedToImport_andToANonOwningRep() {
        assertThatThrownBy(() -> service.setRequiredByNote(
                ticketId, new SetRequiredByNoteRequest("x"), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.setRequiredByNote(
                ticketId, new SetRequiredByNoteRequest("x"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(requiredByNoteOnDeal()).isNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static ImportRequestDto byFactory(List<ImportRequestDto> rows, long factoryId) {
        return rows.stream().filter(r -> r.factoryId() == factoryId).findFirst().orElseThrow();
    }

    private int sequenceValue() {
        Integer v = jdbc.queryForObject("""
            SELECT COALESCE(MAX(last_seq), 0) FROM sales.document_sequence
             WHERE doc_type = 'IMPORT_REQUEST'
            """, Map.of(), Integer.class);
        return v == null ? 0 : v;
    }

    private String requiredByNoteOnDeal() {
        return jdbc.queryForObject("SELECT required_by_note FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-stored@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, 'IT', 'EUR') RETURNING factory_id
            """, Map.of("name", name), Long.class);
    }

    /**
     * Already at {@code quotation_issued} + {@code DEPOSIT_PAID} + {@code sales_stage=ORDER_RECEIVED}
     * — the readiness floor {@code TicketService#requireImportRequestIssuable}/{@code
     * stageAtLeastOrderReceived} both need — so every test can go straight to exercising the IR
     * lifecycle without re-deriving that setup per test.
     */
    private long insertTicket(String code, String customerName) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบใบขอซื้อ', :by, :customer, 'quotation_issued', 'DEPOSIT_PAID',
                    :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("customer", customerName).addValue("stage", DealStage.ORDER_RECEIVED),
            Long.class);
    }

    private void insertItem(long ticket, String factory, String model, String size, String qty,
                            String unit, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", factory)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory));
    }

    /**
     * Like {@link #insertItem} but lets {@code brand} and {@code factory} diverge — {@link
     * #insertItem} deliberately sets them equal, which is the everyday fixture shape but cannot
     * exercise {@link #list_ordersByFactoryName_notByTheNullableBrandSnapshot}'s bug.
     */
    private void insertItemWithDivergentBrand(long ticket, String brand, String factory, String model,
                            String size, String qty, String unit, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory));
    }
}
