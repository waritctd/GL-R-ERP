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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** The STORED ใบขอซื้อ lifecycle, against real Postgres through the real service and repository — rewritten for
 * V184's per-FACTORY grain (was per-brand) and owner decision 1's new authorisation model. */
class StoredImportRequestIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;
    private ImportRequestRepository stored;
    private RecordingSalesMailer mailer;

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

        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-per-factory-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth, new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService, notifications, new th.co.glr.hr.importrequest.ShipmentRoundRepository(jdbc));

        ownerId = insertEmployee("STORED-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("STORED-OTH"), "sales");
        importUser = principal(insertEmployee("STORED-IMP"), "import");
        ceoUser = principal(insertEmployee("STORED-CEO"), "ceo");
        accountUser = principal(insertEmployee("STORED-ACC"), "account");
        salesManager = principal(insertEmployee("STORED-SM"), "sales_manager");

        // Pre-seeded factory master rows — the deal's lines are hand-typed against these names via
        // ticket_item.factory, which is the simplest of the three resolution-cascade inputs and exercises the everyday
        // case, not the auto-create path.
        padanaFactoryId = insertFactory("Padana Stored Test");
        leaFactoryId = insertFactory("LEA Stored Test");

        ticketId = insertTicket("IRSTORE-1", "บริษัท ยู่ฮุย อินทีเรีย จำกัด");
        insertItem(ticketId, "Padana Stored Test", "Lithos Nero Nat", "60x60 cm", "308", "pcs", 0);
        insertItem(ticketId, "Padana Stored Test", "Terrazzo White Nat", "30x60 cm", "2370", "pcs", 1);
        insertItem(ticketId, "LEA Stored Test", "Ceppo Grigio", "60x120 cm", "44", "pcs", 2);
    }

    // ── lifecycle (per-FACTORY as of V184) ──────────────────────────────────────────────────────

    @Test
    void createDrafts_isOnePerFactory_andSkipsFactoriesAlreadyCovered() {
        List<ImportRequestDto> first = service.createDrafts(ticketId, null, owner);
        assertThat(first).hasSize(2);
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
    void createDrafts_isAllOrNothing_whenOneNewFactoryHasNoLeadTime() {
        // country PL has no LeadTimeDefaults entry -> null lead time -> cannot be issued.
        jdbc.update("INSERT INTO price_catalog.factories (name, country, default_currency) "
            + "VALUES ('No Lead Time Factory', 'PL', 'EUR')", Map.of());
        insertItem(ticketId, "No Lead Time Factory", "Line", "60x60 cm", "5", "pcs", 3);

        assertThatThrownBy(() -> service.createDrafts(ticketId, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("No Lead Time Factory");
            });

        assertThat(service.list(ticketId, owner)).isEmpty();
        assertThat(new TicketRepository(jdbc).findById(ticketId).orElseThrow().summary()
            .fulfillmentStatus()).isNull();
    }

    @Test
    void issue_mintsTheNumberAndFreezesTheDate_andSetsTheFirstStepToContacted() {
        long id = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();

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

    /**
     * Walk 4 (owner ruling 2026-10-29: a stock line is never ordered from a factory). A DRAFT form
     * left over from before the order was confirmed can name a line that is now covered from stock
     * ({@code qty_from_stock >= qty}, written by order confirmation): issuing it would order stock
     * the customer already has. ANY covered line refuses the issue (all covered, or one among several -
     * a legacy mixed draft); nothing is numbered and the form stays a DRAFT.
     */
    @Test
    void issue_ofADraftWithAnyLineNowCoveredFromStock_isRefused409_andBurnsNoNumber() {
        List<ImportRequestDto> drafts = DraftSeed.seed(jdbc, ticketId, owner);
        long leaDraft = byFactory(drafts, leaFactoryId).id();
        long padanaDraft = byFactory(drafts, padanaFactoryId).id();
        // LEA's only line fully covered; Padana's FIRST of two lines covered (a mixed draft).
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = qty WHERE ticket_id = :t AND factory = 'LEA Stored Test'",
            Map.of("t", ticketId));
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = qty WHERE ticket_id = :t AND model = 'Lithos Nero Nat'",
            Map.of("t", ticketId));
        int seqBefore = sequenceValue();

        for (long draft : new long[] {leaDraft, padanaDraft}) {
            assertThatThrownBy(() -> service.issue(draft, null, owner))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("สต็อก");
                });
            assertThat(service.get(draft, owner).status()).isEqualTo(ImportRequestStatus.DRAFT);
        }
        assertThat(sequenceValue()).isEqualTo(seqBefore);
    }

    @Test
    void revisionDroppingALineThatIsSplitOverRounds_isRefused_andNoLotMoves() {
        long v1 = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();
        service.issue(v1, null, owner);
        service.advanceStep(v1, new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), importUser);
        // Split the first line's lot over a second round.
        jdbc.update("""
            UPDATE sales.shipment_lot SET qty = qty / 2 WHERE shipment_lot_id = (
              SELECT min(shipment_lot_id) FROM sales.shipment_lot WHERE import_request_id = :ir)""", Map.of("ir", v1));
        long roundB = jdbc.queryForObject("""
            INSERT INTO sales.shipment_round (source, import_step) VALUES ('PORTAL', 'CONTACTED')
            RETURNING shipment_round_id""", Map.of(), Long.class);
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            SELECT :b, import_request_id, import_request_item_id, qty FROM sales.shipment_lot
             WHERE shipment_lot_id = (SELECT min(shipment_lot_id) FROM sales.shipment_lot WHERE import_request_id = :ir)""",
            Map.of("b", roundB, "ir", v1));
        ImportRequestDto v2 = service.revise(v1, owner);
        jdbc.update("""
            DELETE FROM sales.import_request_item WHERE import_request_id = :v2 AND code =
              (SELECT i.code FROM sales.import_request_item i JOIN sales.shipment_lot l USING (import_request_item_id)
                WHERE l.shipment_lot_id = (SELECT min(shipment_lot_id) FROM sales.shipment_lot WHERE import_request_id = :v1))""",
            Map.of("v2", v2.id(), "v1", v1));

        assertThatThrownBy(() -> service.issue(v2.id(), null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_lot WHERE import_request_id = :id",
            Map.of("id", v1), Integer.class)).isEqualTo(3);
    }

    /** Two partial unique indexes on (ticket_id, factory_id): a correction is prepared while the previous version
     * is still live, and the revision's issue carries the predecessor's progress step FORWARD rather than
     * resetting it to CONTACTED (import-request-per-factory-PLAN.md §3). */
    @Test
    void revise_leavesThePreviousVersionIssuedUntilTheReplacementIsIssued_andCarriesTheStepForward() {
        long v1 = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();
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

        // The lots follow the revision's new item rows onto the same round; the superseded row keeps none.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_lot WHERE import_request_id = :id",
            Map.of("id", v1), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(DISTINCT l.shipment_round_id) FROM sales.shipment_lot l
              JOIN sales.import_request_item i USING (import_request_item_id)
             WHERE l.import_request_id = :id""",
            Map.of("id", v2.id()), Integer.class)).isEqualTo(1);
        // every replacement lot carries its whole item quantity
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM sales.shipment_lot l
              JOIN sales.import_request_item i USING (import_request_item_id)
             WHERE l.import_request_id = :id AND l.qty <> i.qty""",
            Map.of("id", v2.id()), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_lot WHERE import_request_id = :id",
            Map.of("id", v2.id()), Integer.class)).isEqualTo(2);

        ImportRequestDto superseded = service.get(v1, owner);
        assertThat(superseded.status()).isEqualTo(ImportRequestStatus.SUPERSEDED);
        assertThat(superseded.supersededById()).isEqualTo(v2.id());
    }

    /** A revision cannot even be prepared once the predecessor has reached RECEIVED. */
    @Test
    void revise_isRefusedOnceThePredecessorHasReceivedTheGoods() {
        long v1 = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();
        service.issue(v1, null, owner);
        DraftSeed.migratedRound(jdbc, v1);
        for (String step : List.of(ImportRequestStep.ORDERED, ImportRequestStep.PICKED_UP,
                ImportRequestStep.IN_TRANSIT, ImportRequestStep.AWAITING_CUSTOMS, ImportRequestStep.RECEIVED)) {
            service.advanceStep(v1, new AdvanceImportStepRequest(step, null, null), importUser);
        }

        assertThatThrownBy(() -> service.revise(v1, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── authorisation (owner decision 1), asked the wrong way round ─────────────────────────────

    @Test
    void nonOwningSalesRepCannotCreateIssueOrRevise() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, null, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.findByTicket(ticketId)).isEmpty();

        long id = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();
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

        long id = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();
        assertThatThrownBy(() -> service.issue(id, null, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        service.issue(id, null, owner);
        assertThatThrownBy(() -> service.revise(id, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** v2.3.0 read-all grant: a flagged hr / non-owning rep reads another rep's stored import request and the list,
     * and still cannot create, issue or revise one. Without the flag the read stays refused. */
    @Test
    void readAllHolderReadsButWritesNothing() {
        long id = byFactory(DraftSeed.seed(jdbc, ticketId, owner), padanaFactoryId).id();
        UserPrincipal hr = principal(insertEmployee("STORED-HR"), "hr");
        for (UserPrincipal who : List.of(hr, otherSalesRep)) {
            assertThatThrownBy(() -> service.get(id, who)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id IN (:a, :b)",
            Map.of("a", hr.id(), "b", otherSalesRep.id()));
        for (UserPrincipal who : List.of(hr, otherSalesRep)) {
            assertThat(service.get(id, who).status()).isEqualTo(ImportRequestStatus.DRAFT);
            assertThat(service.list(ticketId, who)).isNotEmpty();
            assertThatThrownBy(() -> service.createDrafts(ticketId, null, who)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> service.issue(id, null, who)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        // ...and no write verb opens: an empty PATCH is refused (and leaves updated_at alone), as are a body update,
        // a revise and a delete.
        var empty = new th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, null, null, null);
        var body = new th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest(
            "x", null, null, null, null, null, null, null, null, null, null, null);
        Object before = jdbc.queryForObject("SELECT updated_at FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", id), java.sql.Timestamp.class);
        for (UserPrincipal who : List.of(hr, otherSalesRep)) {
            for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                    () -> service.update(id, empty, who), () -> service.update(id, body, who),
                    () -> service.revise(id, who), () -> service.deleteDraft(id, who))) {
                assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            }
        }
        assertThat(jdbc.queryForObject("SELECT updated_at FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", id), java.sql.Timestamp.class)).isEqualTo(before);
        assertThat(service.get(id, owner).status()).isEqualTo(ImportRequestStatus.DRAFT);
    }

    @Test
    void ceoCanRaiseAndIssue() {
        long id = byFactory(DraftSeed.seed(jdbc, ticketId, ceoUser), padanaFactoryId).id();
        assertThat(service.issue(id, null, ceoUser).status()).isEqualTo(ImportRequestStatus.ISSUED);
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

    /** Already at quotation_issued + DEPOSIT_PAID + sales_stage=ORDER_RECEIVED — the readiness floor
     * TicketService#requireImportRequestIssuable/stageAtLeastOrderReceived both need — so every test can go
     * straight to exercising the IR lifecycle without re-deriving that setup per test. */
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
}
