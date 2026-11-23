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
import th.co.glr.hr.ticket.CompleteDeliveryRequest;
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
    private LineNotDeliveredService lineNotDelivered;
    private IrLineLinkService links;
    private TicketService ticketService;
    private TicketRepository ticketRepo;
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
        ticketRepo = tickets;
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
        // through the transactional proxy, as in production: a failure after a write rolls the whole call back
        irService = transactional(new ImportRequestService(new ImportRequestQueryRepository(jdbc),
            new ImportRequestRenderer(), stored, new FactoryConfigRepository(jdbc), tickets, ticketService,
            notifications, roundRepo));
        rounds = transactional(new ShipmentRoundService(roundRepo, stored, irService, tickets, files));
        lineNotDelivered = transactional(new LineNotDeliveredService(ticketService, rounds));
        links = transactional(new IrLineLinkService(stored, rounds, irService, ticketService, tickets));
        incoming = new IncomingService(ticketService, stored, roundRepo, new ImportRequestQueryRepository(jdbc));
        noProject = transactional(new NoProjectOrderService(stored, new FactoryConfigRepository(jdbc), notifications, new EmployeeAuthRepository(jdbc)));
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

        // an undo quoting another advance (a delayed retry of an earlier one) hits nothing
        assertRefused(() -> rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.ORDERED, "00000000-0000-0000-0000-000000000000"), importUser),
            HttpStatus.CONFLICT);
        assertThat(rounds.get(round, importUser).importStep()).isEqualTo(ImportRequestStep.ORDERED);
        ShipmentRoundDto undone = rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.ORDERED, after.advanceId()), importUser);

        assertThat(undone.importStep()).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(undone.facts().vessel()).isNull();
        assertThat(undone.documents()).isEmpty(); // the advance's own documents go with it
        assertThat(undone.orderedOn()).isNull();
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat(irStep(irB)).isEqualTo(ImportRequestStep.CONTACTED);
        assertRefused(() -> rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.ORDERED, after.advanceId()), importUser), HttpStatus.CONFLICT);
    }

    @Test
    void aSplitIrFollowsItsSlowestLot_andQuantityOnNoRoundHoldsItAtContacted() {
        long r1 = round("CONTACTED");
        long r2 = round("CONTACTED");
        lot(r1, irA, 25);
        lot(r2, irA, 15);

        rounds.advance(r1, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.IN_TRANSIT), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.IN_TRANSIT); // a CONTACTED round is only planned: the round past it governs
        rounds.advance(r2, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED);

        jdbc.update("DELETE FROM sales.shipment_lot WHERE shipment_round_id = :r", Map.of("r", r2));
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)"
            + " SELECT :r, :ir, import_request_item_id, 10 FROM sales.import_request_item WHERE import_request_id = :ir",
            Map.of("r", r2, "ir", irA));
        rounds.advance(r1, advanceTo(ImportRequestStep.IN_TRANSIT, ImportRequestStep.RECEIVED), importUser);
        rounds.advance(r2, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.RECEIVED), importUser);
        // 25 + 10 of 40 are received; 5 are on no round, so the IR is not received: it stops short of it
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.AWAITING_CUSTOMS);
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
        ShipmentRoundDto picked = rounds.advance(round, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.PICKED_UP), ceoUser);
        rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.PICKED_UP, picked.advanceId()), ceoUser);
        Map<String, Object> ir = jdbc.queryForMap(
            "SELECT import_step, import_step_by_name, import_step_note FROM sales.import_request WHERE import_request_id = :id", Map.of("id", irA));
        assertThat(ir).containsEntry("import_step", ImportRequestStep.ORDERED).containsEntry("import_step_note", "first note");
        assertThat(ir.get("import_step_by_name")).isEqualTo(rounds.get(round, importUser).stepByName());

        ShipmentRoundDto left = rounds.advance(round, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.IN_TRANSIT), ceoUser);
        rounds.updateFacts(round, new UpdateShipmentRoundRequest(new ShipmentFacts("รอบ ทดสอบ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), Map.of()), ceoUser);
        assertRefused(() -> rounds.undo(round, new UndoShipmentRoundRequest(ImportRequestStep.IN_TRANSIT, left.advanceId()), ceoUser), HttpStatus.CONFLICT);
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
        // the gate lives in advanceStep, under the locks: no round, or a portal round, cannot be received from the IR page
        assertRefused(() -> irStepTo(irA, ImportRequestStep.RECEIVED), HttpStatus.CONFLICT); // no round
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_round", Map.of(), Integer.class)).isZero(); // and nothing was created
        long migrated = round("CONTACTED");
        lot(migrated, irB, 40);
        irStepTo(irB, ImportRequestStep.RECEIVED); // an exempt round is fine

        long portal = round("CONTACTED", "PORTAL");
        lot(portal, irA, 40);
        assertRefused(() -> irStepTo(irA, ImportRequestStep.RECEIVED), HttpStatus.CONFLICT);
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
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED); // 15 still on no round: ordered all the same
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

        // C: the IR orders 100 for a line of 40. Of a 50 lot only 30 arrived and 50 are still coming: what arrived fills the line.
        long ticketC = deal(owner.id(), "RND-OVER");
        long irC = issuedIr(ticketC, irService, "over");
        jdbc.update("UPDATE sales.import_request_item SET qty = 100, unit = 'pcs' WHERE import_request_id = :ir", Map.of("ir", irC));
        long in = round("RECEIVED");
        lot(in, irC, 50);
        jdbc.update("UPDATE sales.shipment_lot SET received_qty = 30 WHERE shipment_round_id = :r", Map.of("r", in));
        lot(round("ORDERED"), irC, 50);
        long itemC = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticketC), Long.class);
        assertThat(ticketService.warehouseRemaining(ticketC, owner).get(itemC)).isEqualByComparingTo("30");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL, raw_unit = NULL WHERE ticket_id = :t", Map.of("t", ticketC));
        jdbc.update("UPDATE sales.import_request_item SET unit = 'แผ่น' WHERE import_request_id = :ir", Map.of("ir", irC));
        assertThat(ticketService.warehouseRemaining(ticketC, owner).get(itemC)).isEqualByComparingTo("30"); // a piece line with no unit text
        jdbc.update("UPDATE sales.import_request_item SET unit = 'กล่อง' WHERE import_request_id = :ir", Map.of("ir", irC));
        assertThat(ticketService.warehouseRemaining(ticketC, owner).get(itemC)).isEqualByComparingTo("20"); // a box is never a piece
        jdbc.update("UPDATE sales.import_request_item SET unit = 'ตร.ม.' WHERE import_request_id = :ir", Map.of("ir", irC));
        assertThat(ticketService.warehouseRemaining(ticketC, owner).get(itemC)).isEqualByComparingTo("20"); // another unit: 40 * 50 / 100
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

    @Test
    void aDealAlreadyMarkedReceivedDeliversWhateverStepItsIrIsOn() {
        long r = round("ORDERED");
        lot(r, irA, 40); // the IR is still ordered, but the deal was marked received (the legacy button / an earlier rollup)
        ticketRepo.addEvent(ticketA, owner.id(), owner.name(), th.co.glr.hr.ticket.TicketEventKind.GOODS_RECEIVED, null, null, null);
        deliverFromWarehouse(ticketA, "40"); // no lot on a RECEIVED round, yet nothing is capped
    }

    @Test
    void roundsOfFinishedDealsAreHiddenAndRefused_aMixedRoundStillAdvances() {
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED' WHERE ticket_id = :t", Map.of("t", ticketB));
        long dead = round("CONTACTED");
        lot(dead, irB, 40);
        long mixed = round("CONTACTED");
        lot(mixed, irA, 40);
        lot(mixed, irB, 40);

        assertThat(rounds.list(importUser).rounds()).extracting(r -> r.id()).contains(mixed).doesNotContain(dead);
        assertRefused(() -> rounds.advance(dead, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser), HttpStatus.CONFLICT);
        assertThat(rounds.advance(mixed, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser).importStep())
            .isEqualTo(ImportRequestStep.ORDERED);
    }

    @Test
    void anIrOnAFinishedDealIsMigratedToo_hiddenWhileTheDealIsOver_andBackWithItsStepOnReopen() throws Exception {
        jdbc.update("UPDATE sales.import_request SET import_step = 'ORDERED' WHERE import_request_id = :id", Map.of("id", irB));
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST', lost_reason = 'PRICE', lost_at = now() WHERE ticket_id = :t", Map.of("t", ticketB));
        // V239's own data statements, run here: every ISSUED IR with lines (these two have no round yet) gets one.
        String sql = new String(getClass().getResourceAsStream("/db/migration/V239__shipment_rounds.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        jdbc.getJdbcTemplate().execute(sql.substring(sql.indexOf("INSERT INTO sales.shipment_round ("), sql.indexOf("-- Orders with no project")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_round WHERE migrated_from_import_request_id = :id AND import_step = 'ORDERED'",
            Map.of("id", irB), Integer.class)).isEqualTo(1);
        assertThat(rounds.list(importUser).rounds()).extracting(r -> r.lots().get(0).importRequestId()).doesNotContain(irB);

        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ACTIVE', lost_reason = NULL, lost_at = NULL WHERE ticket_id = :t", Map.of("t", ticketB));
        assertThat(rounds.list(importUser).rounds()).anySatisfy(r -> {
            assertThat(r.lots().get(0).importRequestId()).isEqualTo(irB);
            assertThat(r.importStep()).isEqualTo(ImportRequestStep.ORDERED);
        });
    }

    @Test
    void takingTheLiveLotOffAMixedRoundLeavesItHiddenAndCommits_forRemoveAndMove() {
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED' WHERE ticket_id = :t", Map.of("t", ticketB));
        long mixed = round("CONTACTED");
        lot(mixed, irA, 40);
        lot(mixed, irB, 40);
        long lotA = lotOf(mixed, irA);

        assertThat(rounds.removeLot(mixed, lotA, importUser)).isNull(); // hidden now, but committed: no 404 and no rollback
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_lot WHERE shipment_round_id = :r", Map.of("r", mixed), Integer.class)).isEqualTo(1);
        assertThat(rounds.list(importUser).waiting()).extracting(w -> w.importRequestId()).contains(irA);

        long mixed2 = round("CONTACTED");
        lot(mixed2, irA, 25);
        lot(mixed2, irB, 10);
        long target = round("CONTACTED");
        lot(target, irA, 5); // another round of this factory that has not left (the moved lot merges into it)
        assertThat(rounds.moveLot(mixed2, lotOf(mixed2, irA), new MoveLotRequest(target), importUser)).isNull();
        assertThat(rounds.get(target, importUser).lots()).extracting(l -> l.importRequestId()).containsExactly(irA);
    }

    @Test
    void theLastLotOfAPartiallyDeliveredDealMovesDepositPaidToAwaitingFinalPayment_andLeavesFullyPaidAlone() {
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'PARTIALLY_DELIVERED', payment_status = 'DEPOSIT_PAID' WHERE ticket_id = :t", Map.of("t", ticketB));
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'PARTIALLY_DELIVERED', payment_status = 'FULLY_PAID' WHERE ticket_id = :t", Map.of("t", ticketA));
        long r = round("ORDERED");
        lot(r, irA, 40);
        lot(r, irB, 40);

        rounds.advance(r, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.RECEIVED), importUser);

        Map<String, Object> b = jdbc.queryForMap("SELECT payment_status, fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketB));
        assertThat(b).containsEntry("payment_status", "AWAITING_FINAL_PAYMENT").containsEntry("fulfillment_status", "PARTIALLY_DELIVERED");
        Map<String, Object> a = jdbc.queryForMap("SELECT payment_status, fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketA));
        assertThat(a).containsEntry("payment_status", "FULLY_PAID").containsEntry("fulfillment_status", "PARTIALLY_DELIVERED");
    }

    @Test
    void theWarehouseCapCountsStockOnlyOnceTheDepositIsSettled_andALineWithNoReceiptRowHasNothingImportedYet() {
        // A: 20 of the line come from stock, 10 of the IR's 40 arrived. Deposit not settled: stock does not count.
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = 20 WHERE ticket_id = :t", Map.of("t", ticketA));
        jdbc.update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_NOTICE_ISSUED' WHERE ticket_id = :t", Map.of("t", ticketA));
        long r1 = round("RECEIVED");
        lot(r1, irA, 10);
        assertThat(ticketService.warehouseRemaining(ticketA, owner).values()).containsExactly(new BigDecimal("10.00")); // what the dialog defaults to
        assertRefused(() -> deliverFromWarehouse(ticketA, "11"), HttpStatus.CONFLICT); // the IR over-orders the imported 20: the 10 that arrived fill it first
        deliverFromWarehouse(ticketA, "10");
        jdbc.update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_PAID' WHERE ticket_id = :t", Map.of("t", ticketA));
        deliverFromWarehouse(ticketA, "20"); // settled: 20 stock + 10 imported = 30 in all
        assertRefused(() -> deliverFromWarehouse(ticketA, "1"), HttpStatus.CONFLICT);

        // B: a second line no IR line points at, while the first line's lot is in: nothing of it arrived
        jdbc.update("INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)"
            + " VALUES (:t, 'Other Factory', 'Kaze', '30x30', 10, 'pcs', 1, 'Other Factory')", Map.of("t", ticketB));
        long second = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t AND sort_order = 1", Map.of("t", ticketB), Long.class);
        long r2 = round("RECEIVED");
        lot(r2, irB, 40);
        assertRefused(() -> deliverItemFromWarehouse(ticketB, second, "5"), HttpStatus.CONFLICT);
        ticketRepo.addEvent(ticketB, owner.id(), owner.name(), th.co.glr.hr.ticket.TicketEventKind.GOODS_RECEIVED, null, null, null);
        deliverItemFromWarehouse(ticketB, second, "5"); // the goods-received event: a line with no received lot is not capped
    }

    @Test
    void aRevisionAfterDepartureKeepsWhatShippedAndLeavesTheIncreaseWaiting_aDecreaseBelowTheLotIsRefused() {
        long left = round("IN_TRANSIT");
        lot(left, irA, 40);

        long tooLow = irService.revise(irA, owner).id();
        jdbc.update("UPDATE sales.import_request_item SET qty = 30 WHERE import_request_id = :id", Map.of("id", tooLow));
        assertRefused(() -> irService.issue(tooLow, null, owner), HttpStatus.CONFLICT);
        jdbc.update("DELETE FROM sales.import_request WHERE import_request_id = :id", Map.of("id", tooLow));

        long revision = irService.revise(irA, owner).id();
        jdbc.update("UPDATE sales.import_request_item SET qty = 50 WHERE import_request_id = :id", Map.of("id", revision));
        irService.issue(revision, null, owner);

        assertThat(jdbc.queryForObject("SELECT qty FROM sales.shipment_lot WHERE import_request_id = :id", Map.of("id", revision), BigDecimal.class))
            .isEqualByComparingTo("40"); // what shipped stays; the 10 more are unplanned
        assertThat(jdbc.queryForObject("SELECT received_qty FROM sales.shipment_lot WHERE import_request_id = :id", Map.of("id", revision), BigDecimal.class))
            .isNull(); // in transit: nothing received, and none is invented
        assertThat(irStep(revision)).isEqualTo(ImportRequestStep.IN_TRANSIT); // the 10 more wait, the rest is on its way
        assertThat(incoming.incoming(ticketA, owner).irs().get(0).waiting().get(0).remainingQty()).isEqualByComparingTo("10");
    }

    @Test
    void anIrCanJoinARoundOrderedBeforeItWasIssued_itsDateIsClampedToFirstIssue_theRoundKeepsItsOwn() {
        LocalDate before = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).minusDays(2);
        long r = round("ORDERED");
        lot(r, irB, 40); // a round with no lot is not a round yet
        jdbc.update("UPDATE sales.shipment_round SET ordered_on = :d WHERE shipment_round_id = :r", Map.of("d", before, "r", r));

        rounds.addLots(r, new AddLotsRequest(List.of(new LotInput(irA, itemOf(irA), new BigDecimal("40")))), importUser);

        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED);
        assertThat(jdbc.queryForObject("SELECT import_step_at FROM sales.import_request WHERE import_request_id = :id", Map.of("id", irA), LocalDate.class))
            .isEqualTo(jdbc.queryForObject("SELECT first_issued_date FROM sales.import_request WHERE import_request_id = :id", Map.of("id", irA), LocalDate.class));
        assertThat(rounds.get(r, importUser).orderedOn()).isEqualTo(before);
    }

    @Test
    void aRevisionAfterDepartureCannotDropALineThatShipped() {
        jdbc.update("INSERT INTO sales.import_request_item (import_request_id, seq, code, size, qty, unit, brand, model)"
            + " VALUES (:ir, 2, 'LINE-B', '10x10', 20, 'pcs', 'Round Factory', 'Kaze')", Map.of("ir", irA));
        long left = round("IN_TRANSIT");
        lot(left, irA, 40); // lot() puts every line at 40: put B back to its own 20
        jdbc.update("UPDATE sales.shipment_lot SET qty = 20 WHERE import_request_item_id IN"
            + " (SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = :ir AND seq = 2)", Map.of("ir", irA));
        long revision = irService.revise(irA, owner).id();
        jdbc.update("DELETE FROM sales.import_request_item WHERE import_request_id = :id AND seq = 2", Map.of("id", revision));

        assertRefused(() -> irService.issue(revision, null, owner), HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.shipment_lot WHERE import_request_id = :ir", Map.of("ir", irA), Integer.class)).isEqualTo(2);
    }

    @Test
    void aDepartedRevisionPairsEachLineWithItsOwnLot_evenWhenTwoLinesShareADealLine() {
        long item1 = itemOf(irA);
        jdbc.update("INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)"
            + " SELECT import_request_id, ticket_item_id, 2, code, '30x30', 10, unit, brand, model FROM sales.import_request_item"
            + " WHERE import_request_item_id = :i", Map.of("i", item1));
        long item2 = jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = :ir AND seq = 2", Map.of("ir", irA), Long.class);
        long left = round("IN_TRANSIT");
        for (long[] l : new long[][] {{item2, 10}, {item1, 40}}) { // the small lot first: first-match pairing would swap them
            jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty) VALUES (:r, :ir, :i, :q)",
                Map.of("r", left, "ir", irA, "i", l[0], "q", l[1]));
        }
        long revision = irService.revise(irA, owner).id();
        irService.issue(revision, null, owner);

        assertThat(jdbc.queryForList("SELECT i.size || ':' || l.qty FROM sales.shipment_lot l JOIN sales.import_request_item i USING (import_request_item_id)"
            + " WHERE l.import_request_id = :id ORDER BY i.seq", Map.of("id", revision), String.class)).containsExactly("20x20:40.00", "30x30:10.00");
    }

    @Test
    void aStaleFactsEditorIsRefused_andTheReceiveNeedsTheStoredIncompleteNote() {
        long r = round("ORDERED");
        lot(r, irA, 40);
        ShipmentRoundDto opened = rounds.get(r, importUser);
        rounds.updateFacts(r, new UpdateShipmentRoundRequest(new ShipmentFacts(null, null, "VESSEL-1", null, null, null, null, null,
            null, null, null, null, null, null, false, null, null), Map.of()), importUser); // saved incomplete, no note
        assertRefused(() -> rounds.updateFacts(r, new UpdateShipmentRoundRequest(new ShipmentFacts(null, null, "VESSEL-2", null, null, null, null,
            null, null, null, null, null, null, null, null, null, null), Map.of(), null, opened.updatedAt()), importUser), HttpStatus.CONFLICT);
        // receive without facts: the stored flag (false) and missing note are what counts
        assertRefused(() -> rounds.advance(r, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.ORDERED, null,
            null, List.of(new DocumentInput("PROFORMA", "PI-X"), new DocumentInput("INVOICE", "INV-X"))), importUser), HttpStatus.BAD_REQUEST);
    }

    @Test
    void aReceiptOnlySaveMovesTheVersion_soASecondSaveFromTheSameOpenIsRefused() {
        long r = round("RECEIVED");
        lot(r, irA, 40);
        ShipmentRoundDto opened = rounds.get(r, importUser);
        long lotId = opened.lots().get(0).lotId();
        rounds.updateFacts(r, new UpdateShipmentRoundRequest(null, Map.of(),
            List.of(new LotReceipt(lotId, new BigDecimal("30"))), opened.updatedAt()), importUser);
        assertRefused(() -> rounds.updateFacts(r, new UpdateShipmentRoundRequest(null, Map.of(),
            List.of(new LotReceipt(lotId, new BigDecimal("20"))), opened.updatedAt()), importUser), HttpStatus.CONFLICT);
        assertThat(rounds.get(r, importUser).lots().get(0).receivedQty()).isEqualByComparingTo("30");
    }

    @Test
    void aDepositRecordedAfterTheFinalLotMovesAPartiallyDeliveredDealToAwaitingFinalPayment() {
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'PARTIALLY_DELIVERED', payment_status = 'DEPOSIT_NOTICE_ISSUED' WHERE ticket_id = :t", Map.of("t", ticketA));
        ticketRepo.addEvent(ticketA, owner.id(), owner.name(), th.co.glr.hr.ticket.TicketEventKind.GOODS_RECEIVED, null, null, null);

        ticketService.recordPayment(ticketA, new th.co.glr.hr.ticket.RecordPaymentRequest("DEPOSIT", new BigDecimal("100"), null, "ทดสอบ มัดจำเกินยอดเพราะไม่มีใบเสนอราคา", null, null, true), accountUser);

        assertThat(jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketA), String.class))
            .isEqualTo("AWAITING_FINAL_PAYMENT");
    }

    @Test
    void aSplitIrKeepsItsStepWhileTheRestWaits_andWhenTheRestJoinsAnotherRound() {
        long r1 = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irA, itemOf(irA), new BigDecimal("25")))), importUser).id();
        rounds.advance(r1, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED); // the remaining 15 were ordered too: not back to S12

        long r2 = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irA, itemOf(irA), new BigDecimal("15")))), importUser).id();
        rounds.advance(r2, advanceTo(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED);
        rounds.advance(r1, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.IN_TRANSIT), importUser);
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED); // the slowest round governs
    }

    @Test
    void anIrThatReceivedNothingIsNotGoodsReceived_noRolloutNoBell_andACorrectionAboveZeroRollsUpOnce_withNullUnits() {
        jdbc.update("UPDATE sales.import_request_item SET unit = NULL WHERE import_request_id = :ir", Map.of("ir", irA));
        long r = round("ORDERED");
        lot(r, irA, 40);
        long lotId = rounds.get(r, importUser).lots().get(0).lotId();

        rounds.advance(r, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.ORDERED, null,
            new ShipmentFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, "ไม่มีของถึง", null),
            null, List.of(new LotReceipt(lotId, BigDecimal.ZERO))), importUser);

        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.RECEIVED);
        assertThat(ticketRepo.hasReceivedGoods(ticketA)).isFalse();
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketA), String.class))
            .isNotEqualTo("GOODS_RECEIVED");
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL") + bells(ticketA, "IMPORT_LOT_RECEIVED")).isZero();

        rounds.updateFacts(r, new UpdateShipmentRoundRequest(null, Map.of(), List.of(new LotReceipt(lotId, new BigDecimal("30"))), null), importUser);

        assertThat(ticketRepo.hasReceivedGoods(ticketA)).isTrue(); // short but above 0: rolled up, once
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM hr.notification WHERE type = 'IMPORT_GOODS_RECEIVED_ALL' AND link LIKE :l",
            Map.of("l", "/tickets/" + ticketA + "%"), String.class)).contains("30 จาก 40").contains("ขาด 10").doesNotContain("ครบทุก");
        // no unit on the lines: figures still show
        var seen = incoming.incoming(ticketA, owner);
        assertThat(seen.totalOrdered()).isEqualByComparingTo("40");
        assertThat(seen.irs().get(0).received()).isEqualByComparingTo("30");
        assertThat(seen.irs().get(0).unit()).isNull();
        rounds.updateFacts(r, new UpdateShipmentRoundRequest(null, Map.of(), List.of(new LotReceipt(lotId, new BigDecimal("35"))), null), importUser);
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL")).isEqualTo(1); // not told twice
    }

    @Test
    void aReceivedLotThatGotZeroStaysCappedAfterTheGoodsReceivedEvent_whileALineWithNoReceivedLotIsStillFree() {
        // IR line 2 (deal line 2, 100) received 0 on a RECEIVED round; line 1 (40) received in full; the deal rolled up
        jdbc.update("INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)"
            + " VALUES (:t, 'Round Factory', 'Kaze', '30x30', 100, 'pcs', 1, 'Round Factory')", Map.of("t", ticketA));
        long deal2 = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t AND sort_order = 1", Map.of("t", ticketA), Long.class);
        jdbc.update("INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)"
            + " VALUES (:ir, :ti, 2, 'LINE-2', '30x30', 100, 'pcs', 'Round Factory', 'Kaze')", Map.of("ir", irA, "ti", deal2));
        long item2 = jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = :ir AND seq = 2", Map.of("ir", irA), Long.class);
        long r = round("RECEIVED");
        lot(r, irA, 40);
        jdbc.update("UPDATE sales.shipment_lot SET qty = 100, received_qty = 0 WHERE import_request_item_id = :i", Map.of("i", item2));
        ticketRepo.addEvent(ticketA, owner.id(), owner.name(), th.co.glr.hr.ticket.TicketEventKind.GOODS_RECEIVED, null, null, null);

        assertRefused(() -> deliverItemFromWarehouse(ticketA, deal2, "1"), HttpStatus.CONFLICT); // nothing of line 2 arrived
        deliverFromWarehouse(ticketA, "40"); // line 1 arrived in full
    }

    @Test
    void planningTheRestOnANewRoundNeverRewindsAnOrderedIr_noBackwardEventAndItsDateStays() {
        long r1 = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irA, itemOf(irA), new BigDecimal("25")))), importUser).id();
        rounds.advance(r1, new AdvanceShipmentRoundRequest(ImportRequestStep.ORDERED, LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")),
            ImportRequestStep.CONTACTED, null, null, null), importUser);
        LocalDate orderedAt = jdbc.queryForObject("SELECT import_step_at FROM sales.import_request WHERE import_request_id = :i", Map.of("i", irA), LocalDate.class);
        int events = stepEvents(ticketA);

        rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irA, itemOf(irA), new BigDecimal("15")))), importUser); // the rest, planned on a CONTACTED round

        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.ORDERED);
        assertThat(jdbc.queryForObject("SELECT import_step_at FROM sales.import_request WHERE import_request_id = :i", Map.of("i", irA), LocalDate.class)).isEqualTo(orderedAt);
        assertThat(stepEvents(ticketA)).isEqualTo(events);
    }

    @Test
    void aSaveOnAFullyDeliveredDealNeverRepeatsTheAllReceivedNotice() {
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :t", Map.of("t", ticketA));
        long r = round("ORDERED");
        lot(r, irA, 40);
        long lotId = rounds.get(r, importUser).lots().get(0).lotId();
        rounds.advance(r, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.ORDERED, null,
            new ShipmentFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, "ไม่มีของถึง", null),
            null, List.of(new LotReceipt(lotId, BigDecimal.ZERO))), importUser);
        for (String qty : List.of("30", "35")) {
            rounds.updateFacts(r, new UpdateShipmentRoundRequest(null, Map.of(), List.of(new LotReceipt(lotId, new BigDecimal(qty))), null), importUser);
        }
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL")).isZero(); // the rollup writes no event on this deal: nothing to announce
    }

    @Test
    void aLotThatGotExactlyZeroGoesBackToWaiting_otherLotsStay_importAndCeoOnly_aMoveBackWritesNoEvent() {
        long r = round("ORDERED");
        lot(r, irA, 40);
        lot(r, irB, 40);
        var lots = rounds.get(r, importUser).lots();
        long lotA = lots.stream().filter(l -> l.importRequestId() == irA).findFirst().orElseThrow().lotId();
        long lotB = lots.stream().filter(l -> l.importRequestId() == irB).findFirst().orElseThrow().lotId();
        rounds.advance(r, new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.ORDERED, null,
            new ShipmentFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, "ขาดของ", null),
            null, List.of(new LotReceipt(lotA, BigDecimal.ZERO), new LotReceipt(lotB, new BigDecimal("40")))), importUser);
        int events = stepEvents(ticketA);

        assertRefused(() -> rounds.returnLotToWaiting(r, lotA, accountUser), HttpStatus.FORBIDDEN);
        assertRefused(() -> rounds.returnLotToWaiting(r, lotA, salesManager), HttpStatus.FORBIDDEN);
        assertRefused(() -> rounds.returnLotToWaiting(r, lotB, importUser), HttpStatus.CONFLICT); // anything received stays

        assertThat(rounds.returnLotToWaiting(r, lotA, ceoUser).lots()).extracting(l -> l.importRequestId()).containsExactly(irB);
        assertThat(rounds.list(importUser).waiting()).extracting(w -> w.importRequestId()).contains(irA);
        assertThat(irStep(irA)).isIn(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED); // never shipped: not "at customs"
        assertThat(stepEvents(ticketA)).isEqualTo(events); // it moved back, silently
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL")).isZero();
    }

    @Test
    void aLineMarkedNotDeliveredIsNoLongerWanted_theIrIsDone_theDealRollsUpOnce_andClosesWithNoAmountChanged() {
        // line 1 (40) arrived in full; line 2 (100) is on a RECEIVED round with 0 received
        jdbc.update("INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)"
            + " VALUES (:t, 'Round Factory', 'Kaze', '30x30', 100, 'pcs', 1, 'Round Factory')", Map.of("t", ticketA));
        long deal2 = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t AND sort_order = 1", Map.of("t", ticketA), Long.class);
        jdbc.update("INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)"
            + " VALUES (:ir, :ti, 2, 'LINE-2', '30x30', 100, 'pcs', 'Round Factory', 'Kaze')", Map.of("ir", irA, "ti", deal2));
        long item2 = jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = :ir AND seq = 2", Map.of("ir", irA), Long.class);
        long r = round("RECEIVED");
        lot(r, irA, 40);
        jdbc.update("UPDATE sales.shipment_lot SET qty = 100, received_qty = 0 WHERE import_request_item_id = :i", Map.of("i", item2));
        long lot2 = jdbc.queryForObject("SELECT shipment_lot_id FROM sales.shipment_lot WHERE import_request_item_id = :i", Map.of("i", item2), Long.class);
        var payableBefore = ticketRepo.payableAmount(ticketA);
        var paidBefore = ticketRepo.sumPaid(ticketA);
        var mark = new th.co.glr.hr.ticket.MarkNotDeliveredRequest(new BigDecimal("100"), "โรงงานไม่ส่ง");

        assertRefused(() -> lineNotDelivered.mark(ticketA, deal2, mark, owner), HttpStatus.FORBIDDEN); // sales manager and CEO only
        assertRefused(() -> lineNotDelivered.mark(ticketA, deal2, mark, salesManager), HttpStatus.CONFLICT); // a 0-received lot goes back to waiting first
        rounds.returnLotToWaiting(r, lot2, importUser);
        // a lot on a round in progress blocks the mark
        long inProgress = rounds.createRound(new CreateShipmentRoundRequest(factoryId(), null, null,
            List.of(new LotInput(irA, item2, new BigDecimal("100")))), importUser).id();
        assertRefused(() -> lineNotDelivered.mark(ticketA, deal2, mark, salesManager), HttpStatus.CONFLICT);
        rounds.removeLot(inProgress, rounds.get(inProgress, importUser).lots().get(0).lotId(), importUser);
        assertRefused(() -> lineNotDelivered.mark(ticketA, deal2, new th.co.glr.hr.ticket.MarkNotDeliveredRequest(new BigDecimal("101"), "x"), salesManager), HttpStatus.CONFLICT);

        lineNotDelivered.mark(ticketA, deal2, mark, salesManager);

        assertThat(jdbc.queryForObject("SELECT qty_not_delivered FROM sales.ticket_item WHERE item_id = :i", Map.of("i", deal2), BigDecimal.class)).isEqualByComparingTo("100");
        assertThat(rounds.list(importUser).waiting()).extracting(w -> w.importRequestId()).doesNotContain(irA); // not wanted, not waiting
        assertThat(irStep(irA)).isEqualTo(ImportRequestStep.RECEIVED); // nothing left to receive: done
        assertThat(ticketRepo.hasReceivedGoods(ticketA)).isTrue(); // the normal rollup ran
        assertThat(bells(ticketA, "IMPORT_GOODS_RECEIVED_ALL")).isEqualTo(1);
        assertThat(incoming.incoming(ticketA, owner).irs().get(0).waiting()).isEmpty();

        deliverFromWarehouse(ticketA, "40");
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketA), String.class))
            .isEqualTo("FULLY_DELIVERED"); // line 2's 100 is not deliverable
        assertThat(ticketRepo.payableAmount(ticketA)).isEqualByComparingTo(payableBefore);
        assertThat(ticketRepo.sumPaid(ticketA)).isEqualByComparingTo(paidBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'LINE_NOT_DELIVERED'", Map.of("t", ticketA), Integer.class)).isEqualTo(1);
    }

    @Test
    void aDealResumedAfterItsLastLotArrivedWhilePausedCatchesUpOnce() {
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :t", Map.of("t", ticketA));
        long r = round("ORDERED");
        lot(r, irA, 40);
        rounds.advance(r, advanceTo(ImportRequestStep.ORDERED, ImportRequestStep.RECEIVED), importUser);
        assertThat(ticketRepo.hasReceivedGoods(ticketA)).isFalse(); // paused: no rollup

        ticketService.resume(ticketA, null, owner);
        assertThat(ticketRepo.hasReceivedGoods(ticketA)).isTrue();
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketA), String.class))
            .isEqualTo("GOODS_RECEIVED");
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :t", Map.of("t", ticketA));
        ticketService.resume(ticketA, null, owner); // already rolled up: nothing replayed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'GOODS_RECEIVED'",
            Map.of("t", ticketA), Integer.class)).isEqualTo(1);
    }

    @Test
    void factoryDocumentsReachImportAndTheCeoOnly() {
        long r = round("CONTACTED");
        lot(r, irA, 40);
        rounds.addDocument(r, "PROFORMA", "PI-SECRET-1", null, null, null, importUser);

        assertThat(rounds.get(r, importUser).documents()).hasSize(1);
        assertThat(rounds.get(r, ceoUser).documents()).hasSize(1);
        assertThat(rounds.get(r, salesManager).documents()).isEmpty();
        assertThat(rounds.list(salesManager).rounds().get(0).documents()).isEmpty();
    }

    @Test
    void notDeliveredMatrix_everyPathReachesTheRightAllowanceAndCompletes() {
        // full
        Line a = line("MX-1");
        receive(a, 100, 100);
        row(a, "100", true);
        deliverItemFromWarehouse(a.ticket, a.item, "100");
        finish(a);

        // 60 arrived, the other 40 marked: all 60 in the warehouse can go, and the notice never says ครบ
        Line b = line("MX-2");
        receive(b, 60, 60);
        mark(b, "40");
        row(b, "60", true);
        assertThat(jdbc.queryForObject("SELECT string_agg(COALESCE(message, ''), ' ') FROM hr.notification WHERE type = 'IMPORT_GOODS_RECEIVED_ALL' AND link LIKE :l",
            Map.of("l", "/tickets/" + b.ticket + "%"), String.class)).doesNotContain("และครบ").contains("ขาด 40");
        assertRefused(() -> deliverItemFromWarehouse(b.ticket, b.item, "61"), HttpStatus.CONFLICT);
        deliverItemFromWarehouse(b.ticket, b.item, "60");
        finish(b);

        // 0 received, returned, marked (alone)
        Line c = line("MX-3");
        long cr = receive(c, 100, 0);
        rounds.returnLotToWaiting(cr, lotOf(cr, c.ir), importUser);
        row(c, "0", false);
        mark(c, "100");
        finish(c);

        // received, rolled up, corrected to 0, returned: the deal has no lot left and must stay capped
        Line d = line("MX-4");
        long dr = receive(d, 100, 100);
        rounds.updateFacts(dr, new UpdateShipmentRoundRequest(null, Map.of(), List.of(new LotReceipt(lotOf(dr, d.ir), BigDecimal.ZERO)), null), importUser);
        rounds.addDocument(dr, "PROFORMA", "PI-MX-4", null, null, null, importUser);
        assertThat(rounds.returnLotToWaiting(dr, lotOf(dr, d.ir), importUser).documents()).hasSize(1); // the round stays reachable
        assertThat(rounds.get(dr, importUser).lots()).isEmpty();
        rounds.updateFacts(dr, new UpdateShipmentRoundRequest(null, Map.of(ImportRequestStep.RECEIVED, LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"))), List.of(), null), importUser);
        row(d, "0", true);
        assertRefused(() -> deliverItemFromWarehouse(d.ticket, d.item, "1"), HttpStatus.CONFLICT);
        mark(d, "100");
        finish(d);

        // X got 0 beside Y on one IR, after the rollup; re-mark up, refused down
        Line y = line("MX-5");
        long x = secondLine(y, 100);
        long r = round("ORDERED");
        insertLot(r, y.ir, y.irItem, 100);
        long xIrItem = irItemOf(x);
        insertLot(r, y.ir, xIrItem, 100);
        long lotY = lotIdOf(r, y.irItem);
        long lotX = lotIdOf(r, xIrItem);
        rounds.advance(r, receiveRequest(List.of(new LotReceipt(lotY, new BigDecimal("100")), new LotReceipt(lotX, BigDecimal.ZERO))), importUser);
        assertThat(ticketRepo.hasReceivedGoods(y.ticket)).isFalse(); // a lot recorded as 0: the deal still waits for it
        assertThat(bells(y.ticket, "IMPORT_GOODS_RECEIVED_ALL")).isZero();
        assertRefused(() -> lineNotDelivered.mark(y.ticket, x, markOf("100"), salesManager), HttpStatus.CONFLICT); // returned first
        rounds.returnLotToWaiting(r, lotX, importUser);
        assertThat(ticketService.warehouseRemaining(y.ticket, owner).get(x)).isEqualByComparingTo("0");
        assertRefused(() -> deliverItemFromWarehouse(y.ticket, x, "1"), HttpStatus.CONFLICT);
        lineNotDelivered.mark(y.ticket, x, markOf("50"), salesManager);
        lineNotDelivered.mark(y.ticket, x, markOf("100"), salesManager); // up after the rollup
        if (ticketRepo.hasReceivedGoods(y.ticket)) {
            assertRefused(() -> lineNotDelivered.mark(y.ticket, x, markOf("1"), salesManager), HttpStatus.CONFLICT); // down after it
        }
        row(y, "100", true);
        deliverItemFromWarehouse(y.ticket, y.item, "100");
        finish(y);

        // re-mark up and down before the rollup, then refused after it
        Line e = line("MX-6");
        receive(e, 60, 60);
        mark(e, "10");
        mark(e, "30");
        mark(e, "20"); // down, before the rollup
        assertThat(ticketRepo.hasReceivedGoods(e.ticket)).isFalse();
        mark(e, "40");
        assertRefused(() -> lineNotDelivered.mark(e.ticket, e.item, markOf("1"), salesManager), HttpStatus.CONFLICT);
        assertRefused(() -> lineNotDelivered.clear(e.ticket, e.item, salesManager), HttpStatus.CONFLICT);
        row(e, "60", true);
        deliverItemFromWarehouse(e.ticket, e.item, "60");
        finish(e);
        assertRefused(() -> lineNotDelivered.mark(e.ticket, e.item, markOf("1"), salesManager), HttpStatus.CONFLICT);

        // legacy: an IR line with no lot on a deal that has a lot elsewhere and the goods event stays uncapped
        Line l = line("MX-7");
        long legacy = secondLine(l, 100);
        long lr = round("RECEIVED");
        insertLot(lr, l.ir, l.irItem, 100);
        ticketRepo.addEvent(l.ticket, owner.id(), owner.name(), th.co.glr.hr.ticket.TicketEventKind.GOODS_RECEIVED, null, null, null);
        assertThat(ticketService.warehouseRemaining(l.ticket, owner).get(legacy)).isEqualByComparingTo("100");
        row(l, "100", true);
        deliverItemFromWarehouse(l.ticket, l.item, "100");
        deliverItemFromWarehouse(l.ticket, legacy, "100");
        finish(l);
    }

    @Test
    void arrivedGoodsCountTowardTheDealFirst_acrossUnitsTheShareStays() {
        Line split = sized("LM-1", 14, 32, "pcs");
        receive(split, 14, 14);
        allowance(split, "14");
        deliverItemFromWarehouse(split.ticket, split.item, "14");
        assertThat(fulfilment(split.ticket)).isEqualTo("FULLY_DELIVERED");

        Line both = sized("LM-2", 14, 32, "pcs");
        receive(both, 14, 14);
        receive(both, 18, 18);
        allowance(both, "14");

        Line single = sized("LM-3", 18, 66, "pcs");
        receive(single, 66, 66);
        allowance(single, "18");

        Line sqm = sized("LM-4", 600, new BigDecimal("60.12"), "ตร.ม.");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL WHERE item_id = :i", Map.of("i", sqm.item));
        insertAndReceive(sqm, "30.06");
        allowance(sqm, "300");
        insertAndReceive(sqm, "30.06");
        allowance(sqm, "600");

        Line sqmBasis = sized("LM-10", 600, new BigDecimal("60.12"), "ตร.ม.");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL, unit_basis = 'SQM', qty_sqm = 60.12 WHERE item_id = :i", Map.of("i", sqmBasis.item));
        insertAndReceive(sqmBasis, "30.06");
        allowance(sqmBasis, "300"); // SQM basis: qty is not in ตร.ม., so the share applies

        Line zero = sized("LM-5", 100, 100, "pcs");
        long zr = receive(zero, 100, 0);
        allowance(zero, "0");
        rounds.returnLotToWaiting(zr, lotIdOf(zr, zero.irItem), importUser);
        allowance(zero, "0");
        mark(zero, "100");
        assertThat(fulfilment(zero.ticket)).isEqualTo("FULLY_DELIVERED");

        Line marked = sized("LM-6", 100, 100, "pcs");
        receive(marked, 60, 60);
        mark(marked, "40");
        allowance(marked, "60");
        deliverItemFromWarehouse(marked.ticket, marked.item, "60");
        assertThat(fulfilment(marked.ticket)).isEqualTo("FULLY_DELIVERED");

        Line stock = sized("LM-7", 100, 80, "pcs");
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = 20 WHERE item_id = :i", Map.of("i", stock.item));
        receive(stock, 40, 40);
        allowance(stock, "60");

        Line shortLot = sized("LM-8", 100, 100, "pcs");
        receive(shortLot, 100, 60);
        allowance(shortLot, "100");

        Line mixed = sized("LM-9", 100, 50, "pcs");
        long other = jdbc.queryForObject("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
            VALUES (:ir, :ti, 2, 'MIX', '20x20', 50, 'กล่อง', 'Round Factory', 'Raku') RETURNING import_request_item_id""",
            Map.of("ir", mixed.ir, "ti", mixed.item), Long.class);
        long mr = round("ORDERED");
        insertLot(mr, mixed.ir, mixed.irItem, 50);
        insertLot(mr, mixed.ir, other, 25);
        rounds.advance(mr, receiveRequest(List.of(new LotReceipt(lotIdOf(mr, mixed.irItem), new BigDecimal("50")),
            new LotReceipt(lotIdOf(mr, other), new BigDecimal("25")))), importUser);
        allowance(mixed, "75");

        Line exact = sized("LM-11", 100, 100, "pcs");
        receive(exact, 14, 12);
        allowance(exact, "12"); // only what is in the warehouse while 86 are still to come

        Line under = sized("LM-12", 100, 50, "pcs");
        receive(under, 25, 25);
        allowance(under, "25");

        Line wantedIn = sized("LM-13", 100, 100, "pcs");
        receive(wantedIn, 60, 50);
        mark(wantedIn, "40");
        allowance(wantedIn, "60"); // all that is wanted is in: the short lot does not cap
        deliverItemFromWarehouse(wantedIn.ticket, wantedIn.item, "60");
        assertThat(fulfilment(wantedIn.ticket)).isEqualTo("FULLY_DELIVERED");

        Line priceBasis = sized("LM-14", 600, 60, "sqm");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL, raw_unit = 'sqm' WHERE item_id = :i", Map.of("i", priceBasis.item));
        receive(priceBasis, 30, 30);
        allowance(priceBasis, "300"); // raw_unit is a price basis: ตร.ม. is never counted as pieces

        Line rd56 = sized("LM-15", 14, 32, "แผ่น");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL, raw_unit = 'แผ่น' WHERE item_id = :i", Map.of("i", rd56.item));
        receive(rd56, 14, 12);
        allowance(rd56, "12");
        Line rd55 = sized("LM-16", 10, 24, "แผ่น");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL, raw_unit = 'แผ่น' WHERE item_id = :i", Map.of("i", rd55.item));
        receive(rd55, 10, 10);
        allowance(rd55, "10");
        Line third = sized("LM-17", 90, 90, "แผ่น");
        jdbc.update("UPDATE sales.ticket_item SET unit = NULL, raw_unit = 'แผ่น' WHERE item_id = :i", Map.of("i", third.item));
        receive(third, 90, 90);
        allowance(third, "90");
    }

    private Line sized(String code, int dealQty, Number irQty, String irUnit) {
        Line l = line(code);
        jdbc.update("UPDATE sales.ticket_item SET qty = :q WHERE item_id = :i", Map.of("q", dealQty, "i", l.item));
        jdbc.update("UPDATE sales.import_request_item SET qty = :q, unit = :u WHERE import_request_item_id = :i",
            Map.of("q", irQty, "u", irUnit, "i", l.irItem));
        return l;
    }

    private void insertAndReceive(Line l, String qty) {
        long r = round("ORDERED");
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty) VALUES (:r, :ir, :i, :q)",
            Map.of("r", r, "ir", l.ir, "i", l.irItem, "q", new BigDecimal(qty)));
        rounds.advance(r, receiveRequest(List.of(new LotReceipt(lotIdOf(r, l.irItem), new BigDecimal(qty)))), importUser);
    }

    private void allowance(Line l, String expected) {
        BigDecimal remaining = ticketService.warehouseRemaining(l.ticket, owner).get(l.item);
        assertThat(remaining).isEqualByComparingTo(expected);
    }

    private record Line(long ticket, long ir, long item, long irItem) {}

    private Line line(String code) {
        long t = deal(owner.id(), code);
        long ir = issuedIr(t, irService, code);
        jdbc.update("UPDATE sales.ticket_item SET qty = 100 WHERE ticket_id = :t", Map.of("t", t));
        jdbc.update("UPDATE sales.import_request_item SET qty = 100 WHERE import_request_id = :ir", Map.of("ir", ir));
        long item = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", t), Long.class);
        return new Line(t, ir, item, itemOf(ir));
    }

    private long secondLine(Line l, int qty) {
        long item = jdbc.queryForObject("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, 'Round Factory', 'Kaze', '30x30', :q, 'pcs', 1, 'Round Factory') RETURNING item_id""",
            Map.of("t", l.ticket, "q", qty), Long.class);
        jdbc.update("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
            VALUES (:ir, :ti, 2, 'LINE-2', '30x30', :q, 'pcs', 'Round Factory', 'Kaze')""", Map.of("ir", l.ir, "ti", item, "q", qty));
        return item;
    }

    private long irItemOf(long ticketItem) {
        return jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE ticket_item_id = :i", Map.of("i", ticketItem), Long.class);
    }

    private void insertLot(long round, long ir, long irItem, int qty) {
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty) VALUES (:r, :ir, :i, :q)",
            Map.of("r", round, "ir", ir, "i", irItem, "q", qty));
    }

    private long lotIdOf(long round, long irItem) {
        return jdbc.queryForObject("SELECT shipment_lot_id FROM sales.shipment_lot WHERE shipment_round_id = :r AND import_request_item_id = :i",
            Map.of("r", round, "i", irItem), Long.class);
    }

    private static AdvanceShipmentRoundRequest receiveRequest(List<LotReceipt> receipts) {
        return new AdvanceShipmentRoundRequest(ImportRequestStep.RECEIVED, null, ImportRequestStep.ORDERED, null,
            new ShipmentFacts(null, null, null, null, null, null, null, null, null, null, null, null, null, null, false, "ทดสอบ", null), null, receipts);
    }

    private long receive(Line l, int planned, int received) {
        long r = round("ORDERED");
        insertLot(r, l.ir, l.irItem, planned);
        rounds.advance(r, receiveRequest(List.of(new LotReceipt(lotIdOf(r, l.irItem), new BigDecimal(received)))), importUser);
        return r;
    }

    private static th.co.glr.hr.ticket.MarkNotDeliveredRequest markOf(String qty) {
        return new th.co.glr.hr.ticket.MarkNotDeliveredRequest(new BigDecimal(qty), "โรงงานไม่ส่ง");
    }

    private void mark(Line l, String qty) {
        lineNotDelivered.mark(l.ticket, l.item, markOf(qty), salesManager);
    }

    private int events(long ticket, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = :k", Map.of("t", ticket, "k", kind), Integer.class);
    }

    private String fulfilment(long ticket) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticket), String.class);
    }

    private void row(Line l, String allowance, boolean goodsIn) {
        BigDecimal remaining = ticketService.warehouseRemaining(l.ticket, owner).get(l.item);
        assertThat(remaining).isEqualByComparingTo(allowance);
        assertThat(ticketRepo.hasReceivedGoods(l.ticket)).isEqualTo(goodsIn);
    }

    private void finish(Line l) {
        assertThat(fulfilment(l.ticket)).isEqualTo("FULLY_DELIVERED");
        assertThat(events(l.ticket, "GOODS_RECEIVED")).isEqualTo(1);
        assertThat(bells(l.ticket, "IMPORT_GOODS_RECEIVED_ALL")).isLessThanOrEqualTo(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────

    private void irStepTo(long ir, String step) {
        irService.advanceStep(ir, new ImportRequestRequests.AdvanceImportStepRequest(step, null, null), importUser);
    }

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

    @Test
    void irGoodsOnNoDealLine_holdDeliveryOpen() {
        // transport line + all IR goods unlinked, after they arrived
        Line transport = line("UL-1");
        receive(transport, 100, 100);
        unlink(transport.irItem);
        jdbc.update("UPDATE sales.ticket_item SET model = NULL, size = NULL, catalog_price_id = NULL, qty_sqm = NULL WHERE item_id = :i", Map.of("i", transport.item));
        allowance(transport, "100"); // not goods: stays recordable
        assertThatThrownBy(() -> ticketService.completeDelivery(transport.ticket,
            new CompleteDeliveryRequest(null, null, null, null, "ทดสอบส่งก่อนมีใบกำกับ"), owner))
            .isInstanceOf(ApiException.class).hasMessageContaining("ยังส่งมอบครบไม่ได้");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.delivery_record WHERE ticket_id = :t", Map.of("t", transport.ticket), Integer.class)).isZero();
        deliverItemFromWarehouse(transport.ticket, transport.item, "100");
        assertThat(fulfilment(transport.ticket)).isEqualTo("PARTIALLY_DELIVERED");

        // one line linked, one goods line whose IR line lost its link
        Line mixed = line("UL-2");
        long loose = secondLine(mixed, 40);
        receive(mixed, 100, 100);
        unlink(irItemOf(loose));
        assertThat(ticketService.warehouseRemaining(mixed.ticket, owner).get(loose)).isEqualByComparingTo("0");
        deliverItemFromWarehouse(mixed.ticket, mixed.item, "100");
        assertThat(fulfilment(mixed.ticket)).isEqualTo("PARTIALLY_DELIVERED");

        // an unrelated stock line keeps its stock quantity
        Line stock = line("UL-3");
        long stocked = secondLine(stock, 40);
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = 30 WHERE item_id = :i", Map.of("i", stocked));
        receive(stock, 100, 100);
        unlink(irItemOf(stocked));
        assertThat(ticketService.warehouseRemaining(stock.ticket, owner).get(stocked)).isEqualByComparingTo("30");

        // no lots at all: nothing is capped
        Line legacy = line("UL-4");
        unlink(legacy.irItem);
        allowance(legacy, "100");

        // the exit: a revision drops the goods that never come, and the delivered deal completes
        Line dropped = line("UL-5");
        long gone = secondLine(dropped, 40);
        unlink(irItemOf(gone));
        jdbc.update("DELETE FROM sales.ticket_item WHERE item_id = :i", Map.of("i", gone));
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = 100 WHERE item_id = :i", Map.of("i", dropped.item));
        ticketService.recordPartialDelivery(dropped.ticket, new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(dropped.item, new BigDecimal("100"))), null, null, null, "ทดสอบส่งก่อนมีใบกำกับ"), owner);
        assertThat(fulfilment(dropped.ticket)).isEqualTo("PARTIALLY_DELIVERED");
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = 0 WHERE item_id = :i", Map.of("i", dropped.item)); // issue refuses a stock-covered line
        long revision = irService.revise(dropped.ir, owner).id();
        jdbc.update("DELETE FROM sales.import_request_item WHERE import_request_id = :r AND ticket_item_id IS NULL", Map.of("r", revision));
        irService.issue(revision, null, owner);
        assertThat(fulfilment(dropped.ticket)).isEqualTo("FULLY_DELIVERED");
    }

    @Test
    void linkingAnUnlinkedIrLine_releasesTheHeldDealOnce() {
        Line l = line("LK-1");
        receive(l, 100, 100);
        long loose = jdbc.queryForObject("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
            VALUES (:ir, NULL, 2, 'LOOSE', '20x20', 10, 'pcs', 'Round Factory', 'Raku') RETURNING import_request_item_id""",
            Map.of("ir", l.ir), Long.class);
        deliverItemFromWarehouse(l.ticket, l.item, "100");
        assertThat(fulfilment(l.ticket)).isEqualTo("PARTIALLY_DELIVERED"); // held by the unlinked line
        assertThat(ticketService.completeDeliveryIfDone(l.ticket, importUser).summary().fulfillmentStatus()).isEqualTo("PARTIALLY_DELIVERED");

        assertRefused(() -> links.link(loose, l.item, owner), HttpStatus.FORBIDDEN); // the rep cannot
        assertRefused(() -> links.link(loose, l.item, salesManager), HttpStatus.FORBIDDEN);
        Line other = line("LK-2"); // another deal's line
        assertRefused(() -> links.link(loose, other.item, importUser), HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE sales.ticket_item SET size = '60x60' WHERE item_id = :i", Map.of("i", l.item));
        assertRefused(() -> links.link(loose, l.item, importUser), HttpStatus.CONFLICT); // another product
        jdbc.update("UPDATE sales.ticket_item SET size = '20x20' WHERE item_id = :i", Map.of("i", l.item));

        links.link(loose, l.item, importUser);
        links.link(loose, l.item, importUser); // double submit: nothing more happens
        assertThat(fulfilment(l.ticket)).isEqualTo("FULLY_DELIVERED");
        assertThat(events(l.ticket, "DELIVERY_COMPLETED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.ticket_event WHERE ticket_id = :t AND message LIKE 'ผูกรายการ%'", Map.of("t", l.ticket), Integer.class)).isEqualTo(1);
        assertRefused(() -> links.link(loose, other.item, importUser), HttpStatus.CONFLICT); // already linked elsewhere

        Line closed = line("LK-3");
        long stray = jdbc.queryForObject("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
            VALUES (:ir, NULL, 2, 'LOOSE', '20x20', 10, 'pcs', 'Round Factory', 'Raku') RETURNING import_request_item_id""", Map.of("ir", closed.ir), Long.class);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :t", Map.of("t", closed.ticket));
        assertRefused(() -> links.link(stray, closed.item, importUser), HttpStatus.CONFLICT);

        // the legacy close path respects the hold too
        Line legacy = line("LK-4");
        jdbc.update("INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit) VALUES (:ir, NULL, 2, 'LOOSE', '20x20', 10, 'pcs')", Map.of("ir", legacy.ir));
        jdbc.update("UPDATE sales.ticket SET status = 'document_issued', payment_status = NULL WHERE ticket_id = :t", Map.of("t", legacy.ticket));
        jdbc.update("UPDATE sales.ticket SET close_confirmed_at = now() WHERE ticket_id = :t", Map.of("t", legacy.ticket));
        assertThatThrownBy(() -> ticketService.verifyClose(legacy.ticket, ceoUser))
            .isInstanceOf(ApiException.class).hasMessageContaining("ปิดงานไม่ได้: ยังส่งมอบครบไม่ได้");
    }

    @Test
    void linkingOntoAMarkedDealLine_reDerivesTheIrStep() {
        Line k = line("LK-5"); // deal line 100, IR line A linked
        jdbc.update("UPDATE sales.import_request_item SET qty = 90 WHERE import_request_item_id = :i", Map.of("i", k.irItem));
        long u = jdbc.queryForObject("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
            VALUES (:ir, NULL, 2, 'LOOSE', '20x20', 10, 'pcs', 'Round Factory', 'Raku') RETURNING import_request_item_id""", Map.of("ir", k.ir), Long.class);
        lineNotDelivered.mark(k.ticket, k.item, markOf("100"), salesManager); // the whole deal line was never sent
        assertThat(irStep(k.ir)).isNotEqualTo(ImportRequestStep.RECEIVED); // U still wants its 10: it points at no deal line
        links.link(u, k.item, importUser); // U now shares the marked line, whose mark covers it too
        assertThat(irStep(k.ir)).isEqualTo(ImportRequestStep.RECEIVED);
    }

    @Test
    void linking_leavesAnotherDealsIrOnTheSameRoundAlone() {
        Line a = line("LK-6");
        Line b = line("LK-7");
        jdbc.update("INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model) VALUES (:ir, NULL, 2, 'LOOSE', '20x20', 10, 'pcs', 'Round Factory', 'Raku')", Map.of("ir", a.ir));
        long r = round("RECEIVED");
        insertLot(r, a.ir, a.irItem, 100);
        insertLot(r, b.ir, b.irItem, 100);
        jdbc.update("UPDATE sales.import_request SET import_step = 'ORDERED' WHERE import_request_id = :i", Map.of("i", b.ir)); // out of step on purpose
        long loose = jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = :i AND code = 'LOOSE'", Map.of("i", a.ir), Long.class);
        links.link(loose, a.item, importUser);
        assertThat(irStep(b.ir)).isEqualTo(ImportRequestStep.ORDERED); // a neighbour on the shared round is not re-derived
    }

    @Test
    void closeOutIsNotSwitchedOn() {
        // the endpoints answer 409; no service reaches the closed_out columns
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.import_request_item WHERE closed_out_at IS NOT NULL", Map.of(), Integer.class)).isZero();
    }

    private void unlink(long irItem) {
        jdbc.update("UPDATE sales.import_request_item SET ticket_item_id = NULL WHERE import_request_item_id = :i", Map.of("i", irItem));
    }

    private void lot(long round, long ir, int qty) {
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            SELECT :r, :ir, import_request_item_id, :q FROM sales.import_request_item WHERE import_request_id = :ir""",
            new MapSqlParameterSource("r", round).addValue("ir", ir).addValue("q", qty));
    }

    private void deliverFromWarehouse(long ticket, String qty) {
        long item = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t ORDER BY sort_order LIMIT 1", Map.of("t", ticket), Long.class);
        deliverItemFromWarehouse(ticket, item, qty);
    }

    private void deliverItemFromWarehouse(long ticket, long item, String qty) {
        ticketService.recordPartialDelivery(ticket, new RecordDeliveryRequest("WAREHOUSE", null,
            List.of(new RecordDeliveryRequest.Line(item, new BigDecimal(qty))), null, null, null, "ทดสอบส่งก่อนมีใบกำกับ"), owner);
    }

    private int bells(long ticket, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM hr.notification WHERE type = :ty AND link LIKE :l",
            Map.of("ty", type, "l", "/tickets/" + ticket + "%"), Integer.class);
    }

    private long lotOf(long round, long ir) {
        return jdbc.queryForObject("SELECT shipment_lot_id FROM sales.shipment_lot WHERE shipment_round_id = :r AND import_request_id = :ir",
            Map.of("r", round, "ir", ir), Long.class);
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
