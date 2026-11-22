package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportSheetModels.ImportSheetException;
import th.co.glr.hr.importrequest.ImportSheetModels.Result;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** The sheet backfill through the real services on a real database, with a workbook of made up values. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ImportSheetBackfillIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String[] COUNTED = {"sales.import_request", "sales.import_request_item", "sales.shipment_round",
        "sales.shipment_lot", "sales.shipment_round_document", "sales.ticket_event", "sales.document_sequence",
        "sales.import_sheet_backfill_run", "hr.notification"};

    @TempDir
    Path dir;

    private ImportSheetBackfillService service;
    private ImportRequestService irService;
    private UserPrincipal owner;
    private long ownerId;
    private long actorId;
    private long factory;

    @BeforeEach
    void wire() {
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, new RecordingSalesMailer(jdbc));
        ObjectMapper plain = new ObjectMapper();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        FileStorageService files = new FileStorageService("/tmp/glr-import-sheet-test-uploads");
        PricingRequestService pricing = new PricingRequestService(new PricingRequestRepository(jdbc), tickets, notifications,
            plain, new ContactRepository(jdbc), files, factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, plain, new CustomerRepository(jdbc),
            new QuotationRenderer(), pricing, new EmployeeAuthRepository(jdbc), new ContactRepository(jdbc),
            new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        ShipmentRoundRepository roundRepo = new ShipmentRoundRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        irService = transactional(new ImportRequestService(new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(),
            stored, factories, tickets, ticketService, notifications, roundRepo));
        ShipmentRoundService rounds = transactional(new ShipmentRoundService(roundRepo, stored, irService, tickets, files));
        ImportSheetRepository sheet = new ImportSheetRepository(jdbc);
        service = new ImportSheetBackfillService(new ImportSheetPlanner(sheet, factories, roundRepo, json), sheet, stored, roundRepo,
            rounds, factories, new EmployeeAuthRepository(jdbc), transactionManager, json);

        ownerId = employee("SHT-OWN");
        actorId = employee("SHT-IMP");
        owner = new UserPrincipal(ownerId, "owner@glr.co.th", "owner", "sales", ownerId, true, LocalDate.of(2020, 1, 1), false, null, false);
        factory = jdbc.queryForObject("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('Round Factory', 'IT', 'EUR') RETURNING factory_id",
            Map.of(), Long.class);
        jdbc.update("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('Other Factory', 'IT', 'EUR')", Map.of());
    }

    @Test
    void aDryRunWritesNothing_aCommitLoadsEveryKind_andASecondCommitChangesNothing() throws IOException {
        long ticket = deal("SHT-1");
        long ir = issuedIr(ticket);
        long oldRound = migratedRound("CONTACTED");
        lot(oldRound, ir);
        String doc = docOf(ir).substring(2);
        Path file = file(new ImportSheetTestBook()
            .row("IR NO.", "69901", "ยี่ห้อ", "Round Factory", "Sale", "S99", "PROJECT", "ตัวอย่างทีมขาย", "รายชื่อสินค้าและจำนวน", "Tile A = 10 box",
                "Proforma invoice no", "PI-A", "วันที่ได้รับ IR", LocalDate.of(2026, 9, 1), "วันที่สั่งของ", LocalDate.of(2026, 9, 2),
                "ETA SINGAPORE/ เรือลำสอง", LocalDate.of(2026, 10, 5), "ทำประกัน", LocalDate.of(2026, 10, 6),
                "วันที่เรือลำแรกออกจริง", LocalDate.of(2026, 10, 1), ImportSheetTestBook.COST, 12345)
            .row("IR NO.", "69902", "PROJECT", "บ้านลูกค้าทดสอบ", "รายชื่อสินค้าและจำนวน", "Tile B = 20 pcs")
            .blank()
            .row("IR NO.", doc, "ยี่ห้อ", "Round Factory", "PROJECT", "งานเดิม", "รายชื่อสินค้าและจำนวน", "ignored = 1 pcs",
                "วันที่สั่งของ", LocalDate.of(2026, 9, 3), "ชื่อพาหนะ", "TEST VESSEL"));

        Map<String, Integer> before = counts();
        Result dry = service.dryRun(file, null, dir.resolve("r1"));
        assertThat(counts()).isEqualTo(before);
        assertThat(dry.totals().roundsCreated()).isEqualTo(2);
        assertThat(dry.totals().irsCreated()).isEqualTo(2);
        assertThat(dry.totals().irsAdopted()).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("r1/report.json"))).doesNotContain("12345");

        Result done = service.commit(file, null, dir.resolve("r2"), actorId, dry.planSha256());
        assertThat(done.failed()).isEmpty();

        Map<String, Object> created = row("SELECT source, order_purpose, ticket_id, import_step, issue_date FROM sales.import_request WHERE doc_number = 'IR69901'");
        assertThat(created.get("source")).isEqualTo("BACKFILL");
        assertThat(created.get("order_purpose")).isEqualTo("SAMPLE");
        assertThat(created.get("ticket_id")).isNull();
        assertThat(created.get("import_step")).isEqualTo(ImportRequestStep.IN_TRANSIT);
        assertThat(string("SELECT order_purpose FROM sales.import_request WHERE doc_number = 'IR69902'")).isEqualTo("CUSTOMER");
        Map<String, Object> round = row("SELECT source, backfill_key, import_step, transship_eta_on, insured_on FROM sales.shipment_round WHERE backfill_key = 'IR69901#1'");
        assertThat(round.get("source")).isEqualTo("BACKFILL");
        assertThat(round.get("import_step")).isEqualTo(ImportRequestStep.IN_TRANSIT);
        assertThat(round.get("transship_eta_on")).isNotNull();
        assertThat(count("SELECT count(*) FROM sales.shipment_round_document WHERE doc_no = 'PI-A' AND kind = 'PROFORMA'")).isEqualTo(1);
        // the job's IR moved off its untouched round, which is gone, and kept its own lines
        assertThat(count("SELECT count(*) FROM sales.shipment_round WHERE shipment_round_id = " + oldRound)).isZero();
        assertThat(string("SELECT r.backfill_key FROM sales.shipment_lot l JOIN sales.shipment_round r USING (shipment_round_id) WHERE l.import_request_id = " + ir))
            .isEqualTo("IR" + doc + "#1");
        assertThat(count("SELECT count(*) FROM sales.import_request_item WHERE import_request_id = " + ir + " AND code = 'ignored'")).isZero();
        assertThat(count("SELECT last_seq FROM sales.document_sequence WHERE doc_type = 'IMPORT_REQUEST' AND year_th = 2569")).isGreaterThanOrEqualTo(902);

        Map<String, Integer> afterFirst = counts();
        Result dryAgain = service.dryRun(file, null, dir.resolve("r3"));
        Result again = service.commit(file, null, dir.resolve("r4"), actorId, dryAgain.planSha256());
        Map<String, Integer> afterSecond = counts();
        afterSecond.put("sales.import_sheet_backfill_run", afterFirst.get("sales.import_sheet_backfill_run"));
        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(again.totals().irsCreated()).isZero();
        assertThat(again.totals().roundsCreated()).isZero();
    }

    @Test
    void aJobWhoseGoodsAreInMovesForward_withItsEvents_andNobodyIsNotified() throws IOException {
        long ticket = deal("SHT-2");
        long ir = issuedIr(ticket);
        lot(migratedRound("CONTACTED"), ir);
        int bells = count("SELECT count(*) FROM hr.notification");
        Path file = file(new ImportSheetTestBook().row("IR NO.", docOf(ir).substring(2), "ยี่ห้อ", "Round Factory", "PROJECT", "งาน",
            "รายชื่อสินค้าและจำนวน", "x = 1 pcs", "วันที่ออกของ", LocalDate.of(2026, 11, 1),
            "สินค้าเข้าตรงตามInvoice(เลขที่ใบตรวจรับ)", "GR-2601-0098"));

        Result dry = service.dryRun(file, null, dir.resolve("r1"));
        assertThat(dry.totals().dealsToAdvance()).isEqualTo(1);
        Result done = service.commit(file, null, dir.resolve("r2"), actorId, dry.planSha256());

        assertThat(done.totals().dealsToAdvance()).isEqualTo(1);
        Map<String, Object> t = row("SELECT sales_stage, fulfillment_status, payment_status FROM sales.ticket WHERE ticket_id = " + ticket);
        assertThat(t.get("sales_stage")).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(t.get("fulfillment_status")).isEqualTo("GOODS_RECEIVED");
        assertThat(t.get("payment_status")).isEqualTo("AWAITING_FINAL_PAYMENT");
        assertThat(count("SELECT count(*) FROM sales.ticket_event WHERE kind = 'IMPORT_STEP_ADVANCED' AND ticket_id = " + ticket)).isPositive();
        assertThat(count("SELECT count(*) FROM hr.notification")).isEqualTo(bells);
    }

    @Test
    void theRunNeverTouchesTheWrongIr_andKeepsWhatImportAlreadyTyped() throws IOException {
        long otherFactoryTicket = deal("SHT-3");
        long otherFactoryIr = issuedIr(otherFactoryTicket);
        long otherRepTicket = deal("SHT-4");
        long otherRepIr = issuedIr(otherRepTicket);
        long touchedTicket = deal("SHT-5");
        long touchedIr = issuedIr(touchedTicket);
        long touchedRound = migratedRound("ORDERED");
        lot(touchedRound, touchedIr);
        jdbc.update("UPDATE sales.shipment_round SET vessel = 'PORTAL VESSEL' WHERE shipment_round_id = " + touchedRound, Map.of());
        jdbc.update("INSERT INTO sales.shipment_round_document (shipment_round_id, kind, doc_no, uploaded_by) VALUES (:r, 'OTHER', 'KEEP-ME', :by)",
            Map.of("r", touchedRound, "by", actorId));
        long stranger = employee("SHT-STR");
        Path choices = dir.resolve("choices.json");
        Files.writeString(choices, "{\"repChoices\":{\"S50\":" + stranger + "}}");

        Path file = file(new ImportSheetTestBook()
            .row("IR NO.", docOf(otherFactoryIr).substring(2), "ยี่ห้อ", "Other Factory", "PROJECT", "งาน", "รายชื่อสินค้าและจำนวน", "x = 1 pcs",
                "วันที่ออกของ", LocalDate.of(2026, 11, 1))
            .blank()
            .row("IR NO.", docOf(otherRepIr).substring(2), "ยี่ห้อ", "Round Factory", "Sale", "S50", "PROJECT", "งาน", "รายชื่อสินค้าและจำนวน", "x = 1 pcs",
                "วันที่ออกของ", LocalDate.of(2026, 11, 1))
            .blank()
            .row("IR NO.", docOf(touchedIr).substring(2), "ยี่ห้อ", "Round Factory", "PROJECT", "งาน", "รายชื่อสินค้าและจำนวน", "x = 1 pcs",
                "ชื่อพาหนะ", "SHEET VESSEL", "วันที่คาดว่าจะถึง ETA bkk", LocalDate.of(2026, 12, 1), "วันที่เรือลำแรกออกจริง", LocalDate.of(2026, 11, 2)));

        Result dry = service.dryRun(file, choices, dir.resolve("r1"));
        assertThat(dry.totals().irsSkipped()).isEqualTo(2);
        assertThat(dry.totals().irsFilled()).isEqualTo(1);
        service.commit(file, choices, dir.resolve("r2"), actorId, dry.planSha256());

        for (long t : List.of(otherFactoryTicket, otherRepTicket)) {
            assertThat(string("SELECT sales_stage FROM sales.ticket WHERE ticket_id = " + t)).isNotEqualTo(DealStage.DELIVERY_SCHEDULING);
        }
        assertThat(string("SELECT import_step FROM sales.import_request WHERE import_request_id = " + otherRepIr)).isEqualTo(ImportRequestStep.CONTACTED);
        Map<String, Object> kept = row("SELECT vessel, eta_on, import_step FROM sales.shipment_round WHERE shipment_round_id = " + touchedRound);
        assertThat(kept.get("vessel")).isEqualTo("PORTAL VESSEL");
        assertThat(kept.get("eta_on")).isNotNull();
        assertThat(kept.get("import_step")).isEqualTo(ImportRequestStep.IN_TRANSIT);
        assertThat(count("SELECT count(*) FROM sales.shipment_round_document WHERE doc_no = 'KEEP-ME'")).isEqualTo(1);
    }

    @Test
    void aProblemExcludesOnlyItsBlock_andAPlanThatWentStaleWritesNothing() throws IOException {
        Path file = file(new ImportSheetTestBook()
            .row("IR NO.", "69911", "ยี่ห้อ", "Round Factory", "PROJECT", "งานดี", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .blank()
            .row("IR NO.", "6947A", "ยี่ห้อ", "Round Factory", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .blank()
            .row("IR NO.", "69912", "ยี่ห้อ", "Round Factory", "Sale", "S1", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .blank()
            .row("IR NO.", "69912", "ยี่ห้อ", "Round Factory", "Sale", "S2", "รายชื่อสินค้าและจำนวน", "y = 1 pcs")
            .blank()
            .row("IR NO.", "69913", "ยี่ห้อ", "Round Factory", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .row("IR NO.", "69914", "ยี่ห้อ", "Other Factory", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .blank()
            .row("IR NO.", "69915", "ยี่ห้อ", "Unknown Brand", "รายชื่อสินค้าและจำนวน", "x = 1 pcs"));

        Result dry = service.dryRun(file, null, dir.resolve("r1"));
        assertThat(dry.totals().blocksExcluded()).isEqualTo(2); // the block with no usable IR number and the block naming two brands

        jdbc.update("INSERT INTO sales.import_request (factory_id, factory_name, brand, status, doc_number, issue_date, first_issued_date, import_step, import_step_at, order_purpose, purpose_note, source) "
            + "VALUES (:f, 'Round Factory', 'Round Factory', 'ISSUED', 'IR69911', current_date, current_date, 'CONTACTED', current_date, 'STOCK', 'มาก่อน', 'IMPORT')",
            Map.of("f", factory));
        Map<String, Integer> before = counts();
        assertThatThrownBy(() -> service.commit(file, null, dir.resolve("r2"), actorId, dry.planSha256()))
            .isInstanceOf(ImportSheetException.class);
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void everythingLoadsWithoutAnswers_anUnknownBrandBecomesAFactory_andAClashingNumberIsKeptUnderItsOwn() throws IOException {
        Path file = file(new ImportSheetTestBook()
            .row("IR NO.", "69931", "ยี่ห้อ", "Brandnew Group", "Sale", "S1", "PROJECT", "งานหนึ่ง", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .blank()
            .row("IR NO.", "69932", "ยี่ห้อ", "Brandnew", "Sale", "S1", "PROJECT", "งานสอง", "รายชื่อสินค้าและจำนวน", "x = 2 pcs")
            .blank()
            .row("IR NO.", "69933", "ยี่ห้อ", "Round Factory", "Sale", "S1", "รายชื่อสินค้าและจำนวน", "x = 3 pcs")
            .blank()
            .row("IR NO.", "69933", "ยี่ห้อ", "Round Factory", "Sale", "S2", "รายชื่อสินค้าและจำนวน", "y = 4 pcs"));

        Result dry = service.dryRun(file, null, dir.resolve("r1"));
        assertThat(dry.totals().blocksExcluded()).isZero();
        Result done = service.commit(file, null, dir.resolve("r2"), actorId, dry.planSha256());

        assertThat(done.failed()).isEmpty();
        assertThat(count("SELECT count(*) FROM price_catalog.factories WHERE lower(name) LIKE 'brandnew%'")).isEqualTo(1);
        assertThat(string("SELECT country FROM price_catalog.factories WHERE lower(name) LIKE 'brandnew%'")).isEqualTo("ZZ");
        assertThat(count("SELECT count(*) FROM sales.import_request WHERE doc_number IN ('IR69931', 'IR69932')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM sales.import_request WHERE doc_number = 'IR69933'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sales.import_request WHERE doc_number = 'IR69933-90'")).isEqualTo(1);

        Map<String, Integer> first = counts();
        Result repeat = service.dryRun(file, null, dir.resolve("r3"));
        service.commit(file, null, dir.resolve("r4"), actorId, repeat.planSha256());
        Map<String, Integer> second = counts();
        second.put("sales.import_sheet_backfill_run", first.get("sales.import_sheet_backfill_run"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anIrThisRunCreatedIsNotExtendedByABlockOfAnotherFactory() throws IOException {
        Path file = file(new ImportSheetTestBook()
            .row("IR NO.", "69941", "ยี่ห้อ", "Round Factory", "Sale", "S1", "PROJECT", "งานเดียว", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .blank()
            .row("IR NO.", "69941", "ยี่ห้อ", "Other Maker", "Sale", "S1", "PROJECT", "งานเดียว", "รายชื่อสินค้าและจำนวน", "y = 2 pcs"));

        Result dry = service.dryRun(file, null, dir.resolve("r1"));
        assertThat(dry.totals().irsSkipped()).isEqualTo(1);
        Result done = service.commit(file, null, dir.resolve("r2"), actorId, dry.planSha256());

        assertThat(done.failed()).isEmpty();
        assertThat(count("SELECT count(*) FROM sales.import_request_item i JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69941'")).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT shipment_round_id) FROM sales.shipment_lot l JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69941'")).isEqualTo(1);
    }

    @Test
    void aBlockOfASplitIrThatDidNotCommitIsAppendedByTheNextRun() throws IOException {
        ImportSheetTestBook first = new ImportSheetTestBook()
            .row("IR NO.", "69951", "ยี่ห้อ", "Round Factory", "Sale", "S7", "PROJECT", "งานสองรอบ", "รายชื่อสินค้าและจำนวน", "A = 1 pcs");
        Path onlyFirst = file(first);
        Result dry = service.dryRun(onlyFirst, null, dir.resolve("r1"));
        service.commit(onlyFirst, null, dir.resolve("r2"), actorId, dry.planSha256());
        assertThat(count("SELECT count(*) FROM sales.import_request_item i JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69951'")).isEqualTo(1);

        Path both = file(new ImportSheetTestBook()
            .row("IR NO.", "69951", "ยี่ห้อ", "Round Factory", "Sale", "S7", "PROJECT", "งานสองรอบ", "รายชื่อสินค้าและจำนวน", "A = 1 pcs")
            .blank()
            .row("IR NO.", "69951", "ยี่ห้อ", "Round Factory", "Sale", "S7", "PROJECT", "งานสองรอบ", "รายชื่อสินค้าและจำนวน", "B = 2 pcs"));
        Result again = service.dryRun(both, null, dir.resolve("r3"));
        Result done = service.commit(both, null, dir.resolve("r4"), actorId, again.planSha256());

        assertThat(done.failed()).isEmpty();
        assertThat(count("SELECT count(*) FROM sales.import_request WHERE doc_number = 'IR69951'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sales.import_request_item i JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69951'")).isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT shipment_round_id) FROM sales.shipment_lot l JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69951'")).isEqualTo(2);
    }

    @Test
    void anIrInTwoBlocksIsOneIrWithLotsOnBothRounds() throws IOException {
        Path file = file(new ImportSheetTestBook()
            .row("IR NO.", "69921", "ยี่ห้อ", "Round Factory", "Sale", "S7", "PROJECT", "งานสองรอบ", "รายชื่อสินค้าและจำนวน", "A = 1 pcs")
            .blank()
            .row("IR NO.", "69921", "ยี่ห้อ", "Round Factory", "Sale", "S7", "PROJECT", "งานสองรอบ", "รายชื่อสินค้าและจำนวน", "B = 2 pcs"));
        Result dry = service.dryRun(file, null, dir.resolve("r1"));
        Result done = service.commit(file, null, dir.resolve("r2"), actorId, dry.planSha256());
        assertThat(done.failed()).isEmpty();
        assertThat(count("SELECT count(*) FROM sales.import_request WHERE doc_number = 'IR69921'")).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT shipment_round_id) FROM sales.shipment_lot l JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69921'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM sales.import_request_item i JOIN sales.import_request r USING (import_request_id) WHERE r.doc_number = 'IR69921'")).isEqualTo(2);

        Map<String, Integer> first = counts();
        Result repeat = service.dryRun(file, null, dir.resolve("r3"));
        service.commit(file, null, dir.resolve("r4"), actorId, repeat.planSha256());
        Map<String, Integer> second = counts();
        second.put("sales.import_sheet_backfill_run", first.get("sales.import_sheet_backfill_run"));
        assertThat(second).isEqualTo(first);
    }

    private Path file(ImportSheetTestBook book) throws IOException {
        Path p = dir.resolve("sheet.xlsx");
        Files.write(p, book.bytes());
        return p;
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String t : COUNTED) {
            out.put(t, count("SELECT count(*) FROM " + t));
        }
        return out;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Integer.class);
    }

    private String string(String sql) {
        return jdbc.queryForObject(sql, Map.of(), String.class);
    }

    private Map<String, Object> row(String sql) {
        return jdbc.queryForMap(sql, Map.of());
    }

    private String docOf(long ir) {
        return string("SELECT doc_number FROM sales.import_request WHERE import_request_id = " + ir);
    }

    private long deal(String code) {
        long t = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, sales_stage)
            VALUES (:c, 'ทดสอบ', :by, 'ลูกค้า', 'quotation_issued', 'DEPOSIT_PAID', :stage) RETURNING ticket_id""",
            new MapSqlParameterSource("c", code).addValue("by", ownerId).addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, 'Round Factory', 'Raku', '20x20', :q, 'pcs', 0, 'Round Factory')""",
            new MapSqlParameterSource("t", t).addValue("q", new BigDecimal("40")));
        return t;
    }

    private long issuedIr(long ticketId) {
        long id = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();
        irService.issue(id, null, owner);
        jdbc.update("DELETE FROM sales.shipment_lot WHERE import_request_id = :id", Map.of("id", id));
        return id;
    }

    private long migratedRound(String step) {
        return jdbc.queryForObject("""
            INSERT INTO sales.shipment_round (factory_id, factory_name, source, import_step)
            VALUES (:f, 'Round Factory', 'MIGRATED', :s) RETURNING shipment_round_id""", Map.of("f", factory, "s", step), Long.class);
    }

    private void lot(long round, long ir) {
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            SELECT :r, :ir, import_request_item_id, qty FROM sales.import_request_item WHERE import_request_id = :ir""",
            Map.of("r", round, "ir", ir));
    }

    private long employee(String code) {
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) VALUES (:c, 'ทดสอบ', 'ตาราง') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }
}
