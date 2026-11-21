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
import th.co.glr.hr.importrequest.ShipmentRoundDtos.LotReceipt;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.MoveLotRequest;
import th.co.glr.hr.ticket.RecordDeliveryRequest;
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
    private IncomingService incoming;
    private NoProjectOrderService noProject;
    private MyIncomingService myIncoming;
    private TicketService ticketService;
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
        ticketService = new TicketService(tickets, notifications, objectMapper, new CustomerRepository(jdbc),
            new QuotationRenderer(), pricing, new EmployeeAuthRepository(jdbc),
            new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        ShipmentRoundRepository roundRepo = new ShipmentRoundRepository(jdbc);
        irService = new ImportRequestService(new ImportRequestQueryRepository(jdbc),
            new ImportRequestRenderer(), stored, new FactoryConfigRepository(jdbc), tickets, ticketService,
            notifications, roundRepo);
        rounds = new ShipmentRoundService(roundRepo, stored, irService, tickets, files);
        incoming = new IncomingService(ticketService, stored, roundRepo);
        noProject = new NoProjectOrderService(stored, new FactoryConfigRepository(jdbc), notifications, new EmployeeAuthRepository(jdbc));
        myIncoming = new MyIncomingService(roundRepo, new EmployeeAuthRepository(jdbc));

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

    @Test
    void aPartialReceiptAllowsWarehouseDeliveryUpToWhatArrived_aShortArrivalNeverBlocksFullDelivery() {
        // Deal A: 25 of 40 sit on a received round, 15 have not been planned. Deal B: its line (60) is larger than the IR
        // line (40), and the one lot arrives short (30 of 40).
        long r1 = round("RECEIVED");
        lot(r1, irA, 25);
        long r2 = round("ORDERED");
        lot(r2, irB, 40);
        jdbc.update("UPDATE sales.ticket_item SET qty = 60 WHERE ticket_id = :t", Map.of("t", ticketB));
        long lotB = rounds.get(r2, importUser).lots().get(0).lotId();
        rounds.advance(r2, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.ORDERED, null,
            new ShipmentFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, "ขาด 10 แผ่น", null),
            null, List.of(new LotReceipt(lotB, new BigDecimal("30")))), importUser);
        assertThat(irStep(irB)).isEqualTo(ImportRequestStep.RECEIVED); // short arrival: the IR is still received

        // A: allowed = 40 * min(1, 25 / 40) = 25, whatever the IR says.
        assertRefused(() -> deliverFromWarehouse(ticketA, "26"), HttpStatus.CONFLICT);
        deliverFromWarehouse(ticketA, "25"); // allowed although the IR is not fully in
        assertRefused(() -> deliverFromWarehouse(ticketA, "1"), HttpStatus.CONFLICT);
        assertThat(ticketService.deliveryBlockedReason(ticketA, false)).isEqualTo("ของที่ถึงโกดังส่งครบแล้ว รอล็อตถัดไป");

        // B: the whole IR is on a received round, so the whole deal line (60) goes, short arrival included.
        deliverFromWarehouse(ticketB, "60");
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketB), String.class))
            .isEqualTo("FULLY_DELIVERED");
    }

    @Test
    void aLotReachingTheWarehouseRingsTheRepOnce_neverTogetherWithTheAllReceivedNotice_andTheDealReadGateHolds() {
        long round = round("ORDERED");
        lot(round, irA, 25); // 15 of deal A are still unplanned: the deal is not complete
        lot(round, irB, 40); // deal B is complete with this advance
        rounds.advance(round, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.RECEIVED), importUser);

        assertThat(bells(ticketA, "IMPORT_LOT_RECEIVED")).isEqualTo(1);
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL")).isZero();
        assertThat(bells(ticketB, "IMPORT_LOT_RECEIVED")).isZero(); // never both
        assertThat(bells(ticketB, "IMPORT_GOODS_RECEIVED_ALL")).isEqualTo(1);

        var seen = incoming.incoming(ticketA, owner);
        assertThat(seen.irs()).hasSize(1);
        assertThat(seen.irs().get(0).lots()).hasSize(1);
        assertThat(seen.irs().get(0).lots().get(0).receivedQty()).isEqualByComparingTo("25");
        assertThat(seen.irs().get(0).waiting().get(0).remainingQty()).isEqualByComparingTo("15");
        assertRefused(() -> incoming.incoming(ticketA, principal(employee("RND-OTHER-REP"), "sales")), HttpStatus.FORBIDDEN);
    }

    @Test
    void anOrderWithNoProjectRidesARoundToReceivedWithNoDealSideEffects_andIsReadOnlyByItsPeople() {
        int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event", Map.of(), Integer.class);
        long order = noProject.create(orderRequest("SHOWROOM", 12), owner).id();
        assertThat(irStep(order)).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(rounds.list(importUser).waiting()).anySatisfy(w -> {
            assertThat(w.importRequestId()).isEqualTo(order);
            assertThat(w.ticketId()).isNull();
            assertThat(w.orderPurpose()).isEqualTo("SHOWROOM");
        });

        long round = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(order, itemOf(order), new BigDecimal("12")))), importUser).id();
        rounds.advance(round, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.CONTACTED, null, null,
            List.of(new DocumentInput("PROFORMA", "PI-X"), new DocumentInput("INVOICE", "INV-X"))), importUser);

        assertThat(irStep(order)).isEqualTo(ImportRequestStep.RECEIVED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event", Map.of(), Integer.class)).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr.notification WHERE type = 'IMPORT_LOT_RECEIVED' AND link = '/my-orders'",
            Map.of(), Integer.class)).isEqualTo(1);
        assertThat(rounds.get(round, importUser).lots().get(0).orderPurpose()).isEqualTo("SHOWROOM");

        // read: the requester, the sales manager, import; a showroom order also by the showroom grant, nobody else
        UserPrincipal otherRep = principal(employee("RND-OTH-REP"), "sales");
        assertThat(irService.get(order, owner).id()).isEqualTo(order);
        irService.get(order, salesManager);
        irService.get(order, importUser);
        assertRefused(() -> irService.get(order, otherRep), HttpStatus.FORBIDDEN);
        assertRefused(() -> irService.renderStored(order, otherRep), HttpStatus.FORBIDDEN);
        assertThat(irService.renderStored(order, owner)).isNotEmpty();
        jdbc.update("UPDATE hr.employee SET can_see_showroom_orders = TRUE WHERE employee_id = :id", Map.of("id", otherRep.id()));
        irService.get(order, otherRep);
        long sample = noProject.create(orderRequest("SAMPLE", 3), owner).id();
        assertRefused(() -> irService.get(sample, otherRep), HttpStatus.FORBIDDEN); // the flag reads SHOWROOM only

        // deal-scoped actions on it answer 409, never a 500
        assertRefused(() -> irService.revise(order, owner), HttpStatus.CONFLICT);
        assertRefused(() -> irService.advanceStep(order, new ImportRequestRequests.AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), importUser),
            HttpStatus.CONFLICT);

        // ของที่ฉันสั่ง: the requester sees both orders and the project IRs of their deals; the flag holder only the showroom one
        assertThat(myIncoming.myIncoming(owner).rows()).extracting(r -> r.kind()).contains("SHOWROOM", "SAMPLE", "PROJECT");
        assertThat(myIncoming.myIncoming(otherRep).rows()).extracting(r -> r.kind()).containsOnly("SHOWROOM");
        assertThat(myIncoming.myIncoming(salesManager).rows()).extracting(r -> r.importRequestId()).contains(order, sample);
        assertRefused(() -> myIncoming.myIncoming(importUser), HttpStatus.FORBIDDEN);
        assertThat(myIncoming.myIncoming(principal(employee("RND-OTH-REP2"), "sales")).rows()).isEmpty();
    }

    @Test
    void aNoProjectOrderNeedsEveryField_andTheDealEntryGate() {
        for (var bad : List.of(
            new NoProjectOrderService.CreateRequest(null, "ให้ผู้ออกแบบ", factoryId(), tomorrow(), lines(5)),
            new NoProjectOrderService.CreateRequest("SAMPLE", " ", factoryId(), tomorrow(), lines(5)),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", null, tomorrow(), lines(5)),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", factoryId(), tomorrow(), List.of()),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", factoryId(), null, lines(5)),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", factoryId(), tomorrow().minusDays(2), lines(5)),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", factoryId(), tomorrow(),
                List.of(new NoProjectOrderService.Line("Slate", "60x60", BigDecimal.ZERO, "แผ่น"))),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", factoryId(), tomorrow(),
                List.of(new NoProjectOrderService.Line("Slate", "60x60", new BigDecimal("1.234"), "แผ่น"))),
            new NoProjectOrderService.CreateRequest("SAMPLE", "ให้ผู้ออกแบบ", factoryId(), tomorrow(),
                List.of(new NoProjectOrderService.Line("Slate", "60x60", new BigDecimal("10000000000"), "แผ่น"))))) {
            assertRefused(() -> noProject.create(bad, owner), HttpStatus.BAD_REQUEST);
        }
        assertRefused(() -> noProject.create(orderRequest("SAMPLE", 1), accountUser), HttpStatus.FORBIDDEN);
    }

    @Test
    void aNoProjectOrderCanBeWithdrawnOnlyWhileOnNoRound_andAQuotationGrantHolderReadsTheirOrder() {
        long order = noProject.create(orderRequest("SAMPLE", 5), owner).id();
        UserPrincipal otherRep = principal(employee("RND-OTH-WD"), "sales");
        assertRefused(() -> noProject.withdraw(order, otherRep), HttpStatus.FORBIDDEN);
        noProject.withdraw(order, owner);
        assertThat(jdbc.queryForObject("SELECT status FROM sales.import_request WHERE import_request_id = :id", Map.of("id", order), String.class))
            .isEqualTo("SUPERSEDED"); // the IR number stays on record, with no successor
        assertThat(jdbc.queryForObject("SELECT superseded_by_id FROM sales.import_request WHERE import_request_id = :id", Map.of("id", order), Long.class)).isNull();
        assertThat(rounds.list(importUser).waiting()).extracting(w -> w.importRequestId()).doesNotContain(order);
        assertThat(myIncoming.myIncoming(owner).rows()).extracting(r -> r.importRequestId()).doesNotContain(order);
        assertRefused(() -> noProject.withdraw(order, owner), HttpStatus.CONFLICT);

        long planned = noProject.create(orderRequest("STOCK", 5), owner).id();
        rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(planned, itemOf(planned), new BigDecimal("5")))), importUser);
        assertRefused(() -> noProject.withdraw(planned, owner), HttpStatus.CONFLICT); // import takes the lot off first

        // a quotation-grant holder (any role) may raise and read their own order; delivered long ago it drops off the page
        UserPrincipal qc = principal(employee("RND-QC"), "qc");
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qc.id()));
        long mine = noProject.create(orderRequest("SAMPLE", 2), qc).id();
        assertThat(irService.get(mine, qc).id()).isEqualTo(mine);
        assertThat(myIncoming.myIncoming(qc).rows()).extracting(r -> r.importRequestId()).containsExactly(mine);
        long done = round("RECEIVED");
        jdbc.update("UPDATE sales.shipment_round SET received_on = current_date - 40 WHERE shipment_round_id = :r", Map.of("r", done));
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)"
            + " SELECT :r, :ir, import_request_item_id, 2 FROM sales.import_request_item WHERE import_request_id = :ir", Map.of("r", done, "ir", mine));
        assertThat(myIncoming.myIncoming(qc).rows()).isEmpty();
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

    private void deliverFromWarehouse(long ticket, String qty) {
        long item = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticket), Long.class);
        ticketService.recordPartialDelivery(ticket, new RecordDeliveryRequest("WAREHOUSE", null,
            List.of(new RecordDeliveryRequest.Line(item, new BigDecimal(qty))), null, null, null, "ทดสอบส่งก่อนมีใบกำกับ"), owner);
    }

    private int bells(long ticket, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM hr.notification WHERE type = :ty AND link LIKE :l",
            Map.of("ty", type, "l", "/tickets/" + ticket + "%"), Integer.class);
    }

    private int stepEvents(long ticket) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'IMPORT_STEP_ADVANCED'",
            Map.of("t", ticket), Integer.class);
    }

    private static LocalDate tomorrow() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(1);
    }

    private static List<NoProjectOrderService.Line> lines(int qty) {
        return List.of(new NoProjectOrderService.Line("Slate Grey", "60x60", new BigDecimal(qty), "แผ่น"));
    }

    private NoProjectOrderService.CreateRequest orderRequest(String purpose, int qty) {
        return new NoProjectOrderService.CreateRequest(purpose, "ให้ผู้ออกแบบ (ทดสอบ)", factoryId(), tomorrow(), lines(qty));
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
