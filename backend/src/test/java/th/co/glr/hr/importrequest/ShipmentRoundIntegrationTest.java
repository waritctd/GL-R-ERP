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
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.AdvanceShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.AddLotsRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ChangeLotRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.CreateShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.DocumentInput;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.LotInput;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.MoveLotRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.UpdateShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentFacts;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentRoundDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.UndoShipmentRoundRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Slice 2: import advances a shipment round; every IR on it follows, split IRs take the slowest lot's step, the undo
 * restores the round and the IRs, and only import/ceo write (read-all holders and the sales manager read). */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ShipmentRoundIntegrationTest extends AbstractPostgresIntegrationTest {

    private ShipmentRoundService rounds;
    private ImportRequestService irService;
    private UserPrincipal owner;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal salesManager;
    private UserPrincipal accountUser;
    private long irA;
    private long irB;
    private long ticketA;
    private long ticketB;

    @BeforeEach
    void wire() {
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, new RecordingSalesMailer(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService files = new FileStorageService("/tmp/glr-shipment-round-test-uploads");
        PricingRequestService pricing = new PricingRequestService(new PricingRequestRepository(jdbc), tickets,
            notifications, objectMapper, new ContactRepository(jdbc), files, factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, new CustomerRepository(jdbc),
            new QuotationRenderer(), pricing, new EmployeeAuthRepository(jdbc),
            new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        ShipmentRoundRepository roundRepo = new ShipmentRoundRepository(jdbc);
        irService = new ImportRequestService(new ImportRequestQueryRepository(jdbc),
            new ImportRequestRenderer(), stored, new FactoryConfigRepository(jdbc), tickets, ticketService,
            notifications, roundRepo);
        rounds = new ShipmentRoundService(roundRepo, stored, irService, tickets, files);

        long ownerId = employee("RND-OWN");
        owner = principal(ownerId, "sales");
        importUser = principal(employee("RND-IMP"), "import");
        ceoUser = principal(employee("RND-CEO"), "ceo");
        salesManager = principal(employee("RND-SM"), "sales_manager");
        accountUser = principal(employee("RND-ACC"), "account");

        jdbc.update("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('Round Factory', 'IT', 'EUR')",
            Map.of());
        ticketA = deal(ownerId, "RND-1");
        ticketB = deal(ownerId, "RND-2");
        irA = issuedIr(ticketA, irService, "RND-1");
        irB = issuedIr(ticketB, irService, "RND-2");
    }

    @Test
    void aRoundCarryingTwoIrsAdvancesBoth_andTheUndoRestoresRoundAndIrs() {
        long round = round("CONTACTED");
        lot(round, irA, 40);
        lot(round, irB, 40);

        ShipmentRoundDto after = rounds.advance(round, new AdvanceShipmentRoundRequest(ImportRequestStep.ORDERED, null,
            ImportRequestStep.CONTACTED, null, new ShipmentFacts(null, null, "TEST VESSEL", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null),
            List.of(new DocumentInput("PROFORMA", "PI-TEST-1"))), importUser);

        assertThat(after.importStep()).isEqualTo(ImportRequestStep.ORDERED);
        assertThat(after.facts().vessel()).isEqualTo("TEST VESSEL");
        assertThat(after.documents()).extracting(d -> d.docNo()).containsExactly("PI-TEST-1");
        assertThat(after.orderedOn()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")));
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED);
        assertThat(irStep(irB)).isEqualTo(ImportRequestStep.ORDERED);
        assertThat(after.undoUntil()).isNotNull();

        ShipmentRoundDto undone = rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.ORDERED), importUser);

        assertThat(undone.importStep()).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(undone.facts().vessel()).isNull();
        assertThat(undone.documents()).isEmpty(); // the advance's own documents go with it
        assertThat(undone.orderedOn()).isNull();
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(irStep(irB)).isEqualTo(ImportRequestStep.CONTACTED);
        assertRefused(() -> rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.ORDERED), importUser), HttpStatus.CONFLICT);
    }

    @Test
    void aSplitIrFollowsItsSlowestLot_andQuantityOnNoRoundHoldsItAtContacted() {
        long r1 = round("CONTACTED");
        long r2 = round("CONTACTED");
        lot(r1, irA, 25);
        lot(r2, irA, 15);

        rounds.advance(r1, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.IN_TRANSIT), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.CONTACTED);
        rounds.advance(r2, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED);

        jdbc.update("DELETE FROM sales.shipment_lot WHERE shipment_round_id = :r", Map.of("r", r2));
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)"
            + " SELECT :r, :ir, import_request_item_id, 10 FROM sales.import_request_item WHERE import_request_id = :ir",
            Map.of("r", r2, "ir", irA));
        rounds.advance(r1, advanceTo(ImportRequestStep.IN_TRANSIT, ImportRequestStep.RECEIVED), importUser);
        rounds.advance(r2, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.RECEIVED), importUser);
        // 25 + 10 of 40 are received; 5 are on no round, so the IR is not received.
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(rounds.list(importUser).waiting()).extracting(w -> w.importRequestId()).contains(irA);
    }

    @Test
    void incompleteArrivalNeedsANote_andWritesAreImportAndCeoOnly() {
        long round = round("CONTACTED");
        lot(round, irA, 40);
        AdvanceShipmentRoundRequest noNote = new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.CONTACTED, null,
            new ShipmentFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, "GR-TEST", false, null, null), null);
        assertRefused(() -> rounds.advance(round, noNote, importUser), HttpStatus.BAD_REQUEST);

        for (UserPrincipal reader : List.of(salesManager, owner, accountUser)) {
            assertRefused(() -> rounds.advance(round, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), reader), HttpStatus.FORBIDDEN);
        }
        assertThat(rounds.list(salesManager).rounds()).hasSize(1);
        assertRefused(() -> rounds.list(owner), HttpStatus.FORBIDDEN);
        assertThat(rounds.advance(round, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), ceoUser).importStep())
            .isEqualTo(ImportRequestStep.ORDERED);
        assertThat(rounds.list(importUser).rounds().get(0).lots()).extracting(l -> l.importRequestId()).containsExactly(irA);
    }

    @Test
    void aDeadDealKeepsItsDerivedStepButGetsNoEventOrRollup_andTheOtherDealIsNotBlocked() {
        long round = round("CONTACTED");
        lot(round, irA, 40);
        lot(round, irB, 40);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED' WHERE ticket_id = :t", Map.of("t", ticketB));
        int eventsB = stepEvents(ticketB);

        rounds.advance(round, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);

        assertThat(irStep(irB)).isEqualTo(ImportRequestStep.ORDERED);
        assertThat(stepEvents(ticketB)).isEqualTo(eventsB);
        assertThat(stepEvents(ticketA)).isPositive();
        assertThat(rounds.list(importUser).waiting()).extracting(w -> w.importRequestId()).doesNotContain(irB);
    }

    @Test
    void aFactsEditEndsTheUndoWindow_andAfterAnUndoTheIrKeepsTheRoundsEarlierStamp() {
        long round = round("CONTACTED");
        lot(round, irA, 40);
        rounds.advance(round, new AdvanceShipmentRoundRequest(ImportRequestStep.ORDERED, null,
            ImportRequestStep.CONTACTED, "first note", null, null), importUser);
        rounds.advance(round, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.PICKED_UP), ceoUser);
        rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.PICKED_UP), ceoUser);
        Map<String, Object> ir = jdbc.queryForMap(
            "SELECT import_step, import_step_by_name, import_step_note FROM sales.import_request WHERE import_request_id = :id", Map.of("id", irA));
        assertThat(ir).containsEntry("import_step", ImportRequestStep.ORDERED).containsEntry("import_step_note", "first note");
        assertThat(ir.get("import_step_by_name")).isEqualTo(rounds.get(round, importUser).stepByName());

        rounds.advance(round, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.IN_TRANSIT), ceoUser);
        rounds.updateFacts(round, new UpdateShipmentRoundRequest(new ShipmentFacts("รอบ ทดสอบ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), Map.of()), ceoUser);
        assertRefused(() -> rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.IN_TRANSIT), ceoUser), HttpStatus.CONFLICT);
        assertThat(rounds.get(round, ceoUser).importStep()).isEqualTo(ImportRequestStep.IN_TRANSIT);
        assertRefused(() -> rounds.advance(round, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, null, null, null, null), ceoUser),
            HttpStatus.BAD_REQUEST);
    }

    @Test
    void aPortalRoundNeedsAProformaAndAnInvoiceNumberToBeReceived_aMigratedOneDoesNot_andARoundTakesSeveralInvoices() {
        long portal = round("CONTACTED", "PORTAL");
        lot(portal, irA, 40);
        ShipmentRoundDto base = rounds.advance(portal, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.IN_TRANSIT), importUser);
        assertThat(base.importStep()).isEqualTo(ImportRequestStep.IN_TRANSIT); // only RECEIVED is blocked

        assertRefused(() -> rounds.advance(portal, advanceTo(ImportRequestStep.IN_TRANSIT, ImportRequestStep.RECEIVED), importUser),
            HttpStatus.BAD_REQUEST);
        rounds.addDocument(portal, "INVOICE", "INV-TEST-1", null, null, null, importUser);
        ShipmentRoundDto two = rounds.addDocument(portal, "INVOICE", "INV-TEST-2", null, "second PO", null, importUser);
        assertThat(two.documents()).extracting(d -> d.docNo()).containsExactly("INV-TEST-1", "INV-TEST-2");
        assertRefused(() -> rounds.advance(portal, advanceTo(ImportRequestStep.IN_TRANSIT, ImportRequestStep.RECEIVED), importUser),
            HttpStatus.BAD_REQUEST); // still no proforma
        // A proforma typed on the advance itself counts.
        ShipmentRoundDto done = rounds.advance(portal, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null,
            ImportRequestStep.IN_TRANSIT, null, null, List.of(new DocumentInput("PROFORMA", "PI-TEST-9"))), importUser);
        assertThat(done.importStep()).isEqualTo(ImportRequestStep.RECEIVED);

        long migrated = round("CONTACTED");
        lot(migrated, irB, 40);
        assertThat(rounds.advance(migrated, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.RECEIVED), importUser).importStep())
            .isEqualTo(ImportRequestStep.RECEIVED);
        assertRefused(() -> rounds.addDocument(migrated, "OTHER", " ", null, null, null, importUser), HttpStatus.BAD_REQUEST);
    }

    @Test
    void correctingTheDateOfTheIrsCurrentStepMovesItsStepDate_whateverTheRoundsStepIsAfterwards() {
        long round = round("CONTACTED");
        lot(round, irA, 40);
        rounds.advance(round, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);
        LocalDate fixed = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
        jdbc.update("UPDATE sales.import_request SET first_issued_date = :d WHERE import_request_id = :id", Map.of("d", fixed.minusDays(10), "id", irA));

        ShipmentRoundDto after = rounds.updateFacts(round, new UpdateShipmentRoundRequest(null,
            Map.of(ImportRequestStep.ORDERED, fixed.minusDays(3))), importUser);

        assertThat(after.orderedOn()).isEqualTo(fixed.minusDays(3));
        assertThat(jdbc.queryForObject("SELECT import_step_at FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", irA), LocalDate.class)).isEqualTo(fixed.minusDays(3));
        assertRefused(() -> rounds.updateFacts(round, new UpdateShipmentRoundRequest(null,
            Map.of(ImportRequestStep.RECEIVED, fixed)), importUser), HttpStatus.BAD_REQUEST); // a step not yet passed
    }

    @Test
    void theIrLevelAdvanceCannotReceiveOnAPortalOrMissingRound_andAReceivedPortalRoundKeepsItsNumbers() {
        assertRefused(() -> irService.requireReceivableThroughIr(irA, ImportRequestStep.RECEIVED), HttpStatus.CONFLICT); // no round
        long migrated = round("CONTACTED");
        lot(migrated, irB, 40);
        irService.requireReceivableThroughIr(irB, ImportRequestStep.RECEIVED); // an exempt round is fine
        irService.requireReceivableThroughIr(irA, ImportRequestStep.ORDERED); // only RECEIVED is gated

        long portal = round("CONTACTED", "PORTAL");
        lot(portal, irA, 40);
        assertRefused(() -> irService.requireReceivableThroughIr(irA, ImportRequestStep.RECEIVED), HttpStatus.CONFLICT);
        rounds.advance(portal, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.CONTACTED, null,
            null, List.of(new DocumentInput("PROFORMA", "PI-TEST-1"), new DocumentInput("INVOICE", "INV-TEST-1"))), importUser);
        long invoice = rounds.get(portal, importUser).documents().stream().filter(d -> d.kind().equals("INVOICE")).findFirst().orElseThrow().id();

        assertRefused(() -> rounds.deleteDocument(portal, invoice, importUser), HttpStatus.BAD_REQUEST);
        assertRefused(() -> rounds.updateDocument(portal, invoice, new ShipmentRoundDtos.DocumentRequest("OTHER", "INV-TEST-1", null, null),
            importUser), HttpStatus.BAD_REQUEST);
        rounds.addDocument(portal, "INVOICE", "INV-TEST-2", null, null, null, importUser);
        assertThat(rounds.deleteDocument(portal, invoice, importUser).documents()).extracting(d -> d.docNo()).contains("INV-TEST-2");
    }

    @Test
    void aSplitLeavesTheRestWaitingAndTheIrAtContacted_overQuantityIsRefused_andAJoiningLotMovesTheIr() {
        long r1 = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), "รอบ ทดสอบ", "AIR",
            List.of(new LotInput(irA, itemOf(irA), new BigDecimal("25")))), importUser).id();
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.CONTACTED);
        var waiting = rounds.list(importUser).waiting().stream().filter(w -> w.importRequestId() == irA).findFirst().orElseThrow();
        assertThat(waiting.lines().get(0).remainingQty()).isEqualByComparingTo("15");
        assertRefused(() -> rounds.addLots(r1, new AddLotsRequest(List.of(new LotInput(irA, itemOf(irA), new BigDecimal("16")))), importUser),
            HttpStatus.CONFLICT);
        assertRefused(() -> rounds.addLots(r1, new AddLotsRequest(List.of(new LotInput(irA, itemOf(irA), BigDecimal.ZERO))), importUser),
            HttpStatus.BAD_REQUEST);

        rounds.advance(r1, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.CONTACTED); // 15 still on no round
        rounds.addLots(r1, new AddLotsRequest(List.of(new LotInput(irA, itemOf(irA), new BigDecimal("15")))), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED); // all placed; the round is at ORDERED
        assertThat(rounds.get(r1, importUser).lots()).hasSize(1).first().satisfies(l -> assertThat(l.qty()).isEqualByComparingTo("40"));
        assertThat(rounds.get(r1, importUser).facts().transportMode()).isEqualTo("AIR");
    }

    @Test
    void aLotMovesToAnotherRoundOfTheSameFactory_andLotsAreLockedOnceARoundHasLeft() {
        long r1 = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irA, itemOf(irA), new BigDecimal("40")))), importUser).id();
        long r2 = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irB, itemOf(irB), new BigDecimal("40")))), importUser).id();
        long lotA = rounds.get(r1, importUser).lots().get(0).lotId();

        assertThat(rounds.moveLot(r1, lotA, new MoveLotRequest(r2), importUser)).isNull(); // r1 held only that lot: deleted
        assertThat(rounds.get(r2, importUser).lots()).extracting(l -> l.importRequestId()).containsExactlyInAnyOrder(irA, irB);

        rounds.advance(r2, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.IN_TRANSIT), importUser);
        long lotB = rounds.get(r2, importUser).lots().stream().filter(l -> l.importRequestId() == irB).findFirst().orElseThrow().lotId();
        assertRefused(() -> rounds.changeLot(r2, lotB, new ChangeLotRequest(new BigDecimal("10")), importUser), HttpStatus.CONFLICT);
        assertRefused(() -> rounds.removeLot(r2, lotB, importUser), HttpStatus.CONFLICT);
        assertThat(rounds.updateFacts(r2, new UpdateShipmentRoundRequest(new ShipmentFacts("ยังแก้ข้อมูลได้", null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null), Map.of()), importUser).facts().label())
            .isEqualTo("ยังแก้ข้อมูลได้");
    }

    @Test
    void anEmptiedMigratedRoundIsDeletedUnlessItHoldsItsOwnData_soMigratedIrsCanRegroup() {
        long r1 = round("CONTACTED"); // MIGRATED
        long r2 = round("CONTACTED");
        lot(r1, irA, 40);
        lot(r2, irB, 40);
        long lotA = rounds.get(r1, importUser).lots().get(0).lotId();
        jdbc.update("UPDATE sales.shipment_round SET vessel = 'TEST VESSEL' WHERE shipment_round_id = :r", Map.of("r", r1));
        assertRefused(() -> rounds.moveLot(r1, lotA, new MoveLotRequest(r2), importUser), HttpStatus.CONFLICT);
        jdbc.update("UPDATE sales.shipment_round SET vessel = NULL, label = 'ชื่ออย่างเดียวไม่นับ' WHERE shipment_round_id = :r", Map.of("r", r1));

        assertThat(rounds.moveLot(r1, lotA, new MoveLotRequest(r2), importUser)).isNull();
        assertThat(rounds.get(r2, importUser).lots()).extracting(l -> l.importRequestId()).containsExactlyInAnyOrder(irA, irB);
    }

    @Test
    void aRevisionRederivesItsStepFromItsLots_soCuttingTheWaitingQuantityReceivesIt() {
        long received = round("RECEIVED");
        jdbc.update("UPDATE sales.shipment_round SET received_on = current_date WHERE shipment_round_id = :r", Map.of("r", received));
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)"
            + " SELECT :r, :ir, import_request_item_id, 25 FROM sales.import_request_item WHERE import_request_id = :ir",
            Map.of("r", received, "ir", irA)); // 25 of 40 received, 15 still waiting

        long revision = irService.revise(irA, owner).id();
        jdbc.update("UPDATE sales.import_request_item SET qty = 25 WHERE import_request_id = :id", Map.of("id", revision));
        irService.issue(revision, null, owner);

        assertThat(irStep(revision)).isEqualTo(ImportRequestStep.RECEIVED);
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketA), String.class))
            .isEqualTo("GOODS_RECEIVED"); // the deal rolls up: every required factory is received
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────

    private static AdvanceShipmentRoundRequest advanceTo(String from, String to) {
        return new AdvanceShipmentRoundRequest(to, null, from, null, null, null);
    }

    private long deal(long ownerId, String code) {
        long t = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, sales_stage)
            VALUES (:c, 'ทดสอบรอบ', :by, 'ลูกค้า', 'quotation_issued', 'DEPOSIT_PAID', :stage) RETURNING ticket_id""",
            new MapSqlParameterSource("c", code).addValue("by", ownerId).addValue("stage", DealStage.ORDER_RECEIVED),
            Long.class);
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, 'Round Factory', 'Raku', '20x20', :q, 'pcs', 0, 'Round Factory')""",
            new MapSqlParameterSource("t", t).addValue("q", new BigDecimal("40")));
        return t;
    }

    private long issuedIr(long ticketId, ImportRequestService svc, String who) {
        long id = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();
        svc.issue(id, null, owner);
        // A fresh IR has no round yet; the tests build the rounds they need.
        jdbc.update("DELETE FROM sales.shipment_lot WHERE import_request_id = :id", Map.of("id", id));
        return id;
    }

    /** A MIGRATED round (exempt from the number rule). */
    private long round(String step) {
        return round(step, "MIGRATED");
    }

    private long round(String step, String source) {
        return jdbc.queryForObject("""
            INSERT INTO sales.shipment_round (factory_id, factory_name, source, import_step)
            VALUES ((SELECT factory_id FROM price_catalog.factories WHERE name = 'Round Factory'), 'Round Factory', :src, :s)
            RETURNING shipment_round_id""", Map.of("s", step, "src", source), Long.class);
    }

    private void lot(long round, long ir, int qty) {
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            SELECT :r, :ir, import_request_item_id, :q FROM sales.import_request_item WHERE import_request_id = :ir""",
            new MapSqlParameterSource("r", round).addValue("ir", ir).addValue("q", qty));
    }

    private int stepEvents(long ticket) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'IMPORT_STEP_ADVANCED'",
            Map.of("t", ticket), Integer.class);
    }

    private long factoryId() {
        return jdbc.queryForObject("SELECT factory_id FROM price_catalog.factories WHERE name = 'Round Factory'", Map.of(), Long.class);
    }

    private long itemOf(long ir) {
        return jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = :id ORDER BY seq LIMIT 1",
            Map.of("id", ir), Long.class);
    }

    private String irStep(long ir) {
        return jdbc.queryForObject("SELECT import_step FROM sales.import_request WHERE import_request_id = :id",
            Map.of("id", ir), String.class);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-round@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long employee(String code) {
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, first_name_th, last_name_th)"
            + " VALUES (:c, 'ทดสอบ', 'รอบ') RETURNING employee_id", Map.of("c", code), Long.class);
    }
}
