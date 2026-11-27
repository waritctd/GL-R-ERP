package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportJobDtos.CreateImportJobRequest;
import th.co.glr.hr.importrequest.ImportJobDtos.ImportJobDto;
import th.co.glr.hr.importrequest.ImportJobDtos.ItemRequest;
import th.co.glr.hr.importrequest.ImportJobDtos.JobLine;
import th.co.glr.hr.importrequest.ImportJobDtos.RoundChoice;
import th.co.glr.hr.importrequest.ImportJobDtos.UpdateImportJobRequest;
import th.co.glr.hr.importrequest.MyIncomingDtos.MyIncomingRow;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** งานนำเข้า through the real services and SQL: who may write, and what never widens. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ImportJobIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Bangkok"));
    /** A manual number in this year's counter: the two digit Buddhist year, so it stays valid after 1 January. */
    private static final String LATE = String.format(java.util.Locale.ROOT, "IR%02d987", (TODAY.getYear() + 543) % 100);

    private ImportJobService jobs;
    private NoProjectOrderService orders;
    private ImportRequestService irService;
    private ShipmentRoundService roundService;
    private UserPrincipal importUser;
    private UserPrincipal rep;
    private UserPrincipal otherRep;
    private long factory;

    @BeforeEach
    void wire() {
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, new RecordingSalesMailer(jdbc));
        ObjectMapper plain = new ObjectMapper();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        FileStorageService files = new FileStorageService("/tmp/glr-import-job-test-uploads");
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
        roundService = transactional(new ShipmentRoundService(roundRepo, stored, irService, tickets, files, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc)));
        jobs = transactional(new ImportJobService(new ImportJobRepository(jdbc), stored, irService, roundRepo, roundService,
            new ImportSheetRepository(jdbc), factories, new EmployeeAuthRepository(jdbc), notifications,
            new AuditService(new AuditLogRepository(jdbc), json), json));
        orders = transactional(new NoProjectOrderService(stored, factories, notifications, new EmployeeAuthRepository(jdbc)));

        importUser = user("JOB-IMP", "import");
        rep = user("JOB-REP", "sales");
        otherRep = user("JOB-OTH", "sales");
        factory = jdbc.queryForObject("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('Round Factory', 'IT', 'EUR') RETURNING factory_id",
            Map.of(), Long.class);
    }

    @Test
    void importCreatesAJobThreeWays_withANumberThatCanNeverClash() {
        ImportJobDto waiting = jobs.create(request(null, new RoundChoice("NONE", null), rep.id()), importUser);
        assertThat(waiting.source()).isEqualTo("IMPORT");
        assertThat(waiting.importRequest().ticketId()).isNull();
        assertThat(waiting.rounds()).isEmpty();
        assertThat(waiting.history()).extracting(h -> h.action()).containsExactly("IMPORT_JOB_CREATED");
        assertThat(count("SELECT count(*) FROM hr.notification WHERE type = 'IMPORT_JOB_CREATED' AND employee_id = " + rep.id())).isEqualTo(1);

        long sailing = jobs.create(request(null, new RoundChoice("NEW", null), null), importUser).rounds().get(0).id();
        jdbc.update("UPDATE sales.shipment_round SET import_step = 'IN_TRANSIT', in_transit_on = current_date WHERE shipment_round_id = " + sailing, Map.of());
        ImportJobDto late = jobs.create(request(LATE, new RoundChoice("EXISTING", sailing), null), importUser);
        assertThat(late.importRequest().docNumber()).isEqualTo(LATE);
        assertThat(late.importRequest().importStep()).isEqualTo(ImportRequestStep.IN_TRANSIT);
        assertThat(late.items().get(0).lots()).hasSize(1);

        ImportJobDto fresh = jobs.create(request(null, new RoundChoice("NEW", null), null), importUser);
        assertThat(fresh.rounds()).hasSize(1);
        assertThat(fresh.rounds().get(0).source()).isEqualTo("PORTAL");
        // the counter moved past the typed number, so the portal never mints it again
        assertThat(Integer.parseInt(fresh.importRequest().docNumber().substring(4))).isGreaterThan(987);

        refused(() -> jobs.create(request(LATE, new RoundChoice("NONE", null), null), importUser), HttpStatus.CONFLICT);
        refused(() -> jobs.create(request("6947A", new RoundChoice("NONE", null), null), importUser), HttpStatus.BAD_REQUEST);
        refused(() -> jobs.create(request(null, new RoundChoice("EXISTING", round("RECEIVED")), null), importUser), HttpStatus.CONFLICT);
    }

    @Test
    void importEditsTheJob_everyChangeIsInTheHistory_andTheFactoryLocksOnceItRides() {
        ImportJobDto job = jobs.create(request(null, new RoundChoice("NEW", null), null), importUser);
        long id = job.importRequest().id();

        ImportJobDto edited = jobs.update(id, new UpdateImportJobRequest("STOCK", "สต็อกแก้แล้ว", rep.id(), TODAY.minusDays(3),
            TODAY.minusDays(4), TODAY.plusDays(9), "ir69988", null, null), importUser);
        assertThat(edited.purpose()).isEqualTo("STOCK");
        assertThat(edited.importRequest().docNumber()).isEqualTo("IR69988");
        assertThat(edited.importRequest().requestedByName()).isNotBlank();
        assertThat(edited.history().get(0).action()).isEqualTo("IMPORT_JOB_UPDATED");
        assertThat(edited.history().get(0).before()).contains("PROJECT");
        assertThat(edited.history().get(0).after()).contains("STOCK");

        ImportJobDto cleared = jobs.update(id, new UpdateImportJobRequest(null, null, null, null, null, null, null, null,
            List.of("requesterId", "wantedBy")), importUser);
        assertThat(cleared.requesterId()).isNull();
        assertThat(cleared.wantedBy()).isNull();

        long other = jdbc.queryForObject("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('Other', 'IT', 'EUR') RETURNING factory_id", Map.of(), Long.class);
        refused(() -> jobs.update(id, new UpdateImportJobRequest(null, null, null, null, null, null, null, other, null), importUser), HttpStatus.CONFLICT);
        refused(() -> jobs.update(id, new UpdateImportJobRequest(null, null, null, TODAY.plusDays(1), null, null, null, null, null), importUser), HttpStatus.BAD_REQUEST);

        long waiting = jobs.create(request(null, new RoundChoice("NONE", null), null), importUser).importRequest().id();
        assertThat(jobs.update(waiting, new UpdateImportJobRequest(null, null, null, null, null, null, null, other, null), importUser)
            .importRequest().factoryName()).isEqualTo("Other");
    }

    @Test
    void itemsFollowTheLotRules() {
        ImportJobDto job = jobs.create(requestWithLines(new RoundChoice("NEW", null)), importUser);
        long id = job.importRequest().id();
        long roundId = job.rounds().get(0).id();
        long first = job.items().get(0).id();
        long second = job.items().get(1).id();

        // one lot holding the whole line: the lot follows the new quantity
        assertThat(jobs.updateItem(id, first, new ItemRequest(null, null, new BigDecimal("15"), null), importUser)
            .items().get(0).lots().get(0).qty()).isEqualByComparingTo("15");

        // a new line waits to be planned; the IR stays where it was until it is
        ImportJobDto added = jobs.addItem(id, new ItemRequest("Tile C", null, new BigDecimal("4"), "แผ่น"), importUser);
        assertThat(added.items()).hasSize(3);
        assertThat(added.items().get(2).lots()).isEmpty();
        long third = added.items().get(2).id();

        // split a line: it can grow, but not below what is already placed
        jdbc.update("UPDATE sales.shipment_lot SET qty = 5 WHERE import_request_item_id = " + second, Map.of());
        long otherRound = round("CONTACTED");
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty) VALUES (:r, :ir, :i, 15)",
            Map.of("r", otherRound, "ir", id, "i", second));
        assertThat(jobs.updateItem(id, second, new ItemRequest(null, null, new BigDecimal("30"), null), importUser)
            .items().get(1).qty()).isEqualByComparingTo("30");
        refused(() -> jobs.updateItem(id, second, new ItemRequest(null, null, new BigDecimal("10"), null), importUser), HttpStatus.CONFLICT);

        assertThat(jobs.removeItem(id, third, importUser).items()).hasSize(2);

        // goods in the warehouse: the text can be fixed, the quantity cannot, and the line stays
        jdbc.update("UPDATE sales.shipment_round SET import_step = 'RECEIVED', received_on = current_date WHERE shipment_round_id = " + roundId, Map.of());
        assertThat(jobs.updateItem(id, first, new ItemRequest("Tile A fixed", null, null, null), importUser).items().get(0).code()).isEqualTo("Tile A fixed");
        refused(() -> jobs.updateItem(id, first, new ItemRequest(null, null, new BigDecimal("16"), null), importUser), HttpStatus.CONFLICT);
        refused(() -> jobs.removeItem(id, first, importUser), HttpStatus.CONFLICT);

        long single = jobs.create(request(null, new RoundChoice("NONE", null), null), importUser).importRequest().id();
        long only = jobs.get(single, importUser).items().get(0).id();
        refused(() -> jobs.removeItem(single, only, importUser), HttpStatus.CONFLICT);
    }

    @Test
    void onlyImportAndTheCeoWrite_aNamedRepCannotEditOrWithdraw_andAJobIrIsNeverTouched() {
        ImportJobDto job = jobs.create(request(null, new RoundChoice("NONE", null), rep.id()), importUser);
        long id = job.importRequest().id();
        long item = job.items().get(0).id();
        UserPrincipal holder = user("JOB-HLD", "qc");
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = true WHERE employee_id = " + holder.id(), Map.of());

        for (UserPrincipal who : List.of(rep, otherRep, user("JOB-SM", "sales_manager"), user("JOB-ACC", "account"), user("JOB-HR", "hr"),
            user("JOB-WH", "warehouse"), user("JOB-QC", "qc"), user("JOB-EMP", "employee"), holder)) {
            refused(() -> jobs.create(request(null, new RoundChoice("NONE", null), null), who), HttpStatus.FORBIDDEN);
            refused(() -> jobs.update(id, new UpdateImportJobRequest("STOCK", null, null, null, null, null, null, null, null), who), HttpStatus.FORBIDDEN);
            refused(() -> jobs.addItem(id, new ItemRequest("x", null, BigDecimal.ONE, "แผ่น"), who), HttpStatus.FORBIDDEN);
            refused(() -> jobs.updateItem(id, item, new ItemRequest("x", null, null, null), who), HttpStatus.FORBIDDEN);
            refused(() -> jobs.removeItem(id, item, who), HttpStatus.FORBIDDEN);
            refused(() -> jobs.latestRun(who), HttpStatus.FORBIDDEN);
        }
        refused(() -> orders.withdraw(id, rep), HttpStatus.FORBIDDEN);
        assertThat(orders.withdraw(id, importUser).status()).isEqualTo(ImportRequestStatus.SUPERSEDED);

        long dealIr = issuedIr(deal("JOB-1", rep.id()), rep);
        long dealItem = jdbc.queryForObject("SELECT import_request_item_id FROM sales.import_request_item WHERE import_request_id = " + dealIr + " LIMIT 1", Map.of(), Long.class);
        refused(() -> jobs.update(dealIr, new UpdateImportJobRequest("STOCK", null, null, null, null, null, null, null, null), importUser), HttpStatus.CONFLICT);
        refused(() -> jobs.updateItem(dealIr, dealItem, new ItemRequest("x", null, null, null), importUser), HttpStatus.CONFLICT);
        refused(() -> jobs.removeItem(dealIr, dealItem, importUser), HttpStatus.CONFLICT);
        refused(() -> jobs.addItem(dealIr, new ItemRequest("x", null, BigDecimal.ONE, "แผ่น"), importUser), HttpStatus.CONFLICT);
    }

    @Test
    void aJobImportMadeShowsOnTheTeamListWithoutAWithdraw_andNothingElseOpensUp() {
        long importJob = jobs.create(request(null, new RoundChoice("NONE", null), rep.id()), importUser).importRequest().id();
        MyIncomingService mine = new MyIncomingService(new ShipmentRoundRepository(jdbc), new EmployeeAuthRepository(jdbc));

        MyIncomingRow seenByAnother = mine.myIncoming(otherRep).rows().stream().filter(r -> r.importRequestId() == importJob)
            .findFirst().orElseThrow();
        assertThat(seenByAnother.kind()).isEqualTo("PROJECT");
        assertThat(seenByAnother.repName()).isNotBlank();
        assertThat(seenByAnother.canWithdraw()).isFalse();
        // the rep named on it cannot take it back either: it is import's job
        assertThat(mine.myIncoming(rep).rows().stream().filter(r -> r.importRequestId() == importJob).findFirst().orElseThrow().canWithdraw()).isFalse();
        // reading the list opened nothing: the other rep is still refused the job by id
        refused(() -> jobs.get(importJob, otherRep), HttpStatus.FORBIDDEN);
    }

    private CreateImportJobRequest request(String doc, RoundChoice round, Long requester) {
        return new CreateImportJobRequest("PROJECT", "บ้านทดสอบ", factory, TODAY.minusDays(5), requester, null, null, doc,
            List.of(new JobLine("Tile A", "60x60", new BigDecimal("10"), "แผ่น")), round);
    }

    private CreateImportJobRequest requestWithLines(RoundChoice round) {
        return new CreateImportJobRequest("PROJECT", "บ้านทดสอบ", factory, TODAY.minusDays(5), null, null, null, null,
            List.of(new JobLine("Tile A", "60x60", new BigDecimal("10"), "แผ่น"), new JobLine("Tile B", null, new BigDecimal("20"), "แผ่น")), round);
    }

    private static void refused(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Integer.class);
    }

    private long round(String step) {
        return jdbc.queryForObject("""
            INSERT INTO sales.shipment_round (factory_id, factory_name, source, import_step)
            VALUES (:f, 'Round Factory', 'MIGRATED', :s) RETURNING shipment_round_id""", Map.of("f", factory, "s", step), Long.class);
    }

    private void lotAll(long round, long ir) {
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            SELECT :r, :ir, import_request_item_id, qty FROM sales.import_request_item WHERE import_request_id = :ir""",
            Map.of("r", round, "ir", ir));
    }

    private long deal(String code, long ownerId) {
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

    private long issuedIr(long ticketId, UserPrincipal owner) {
        long id = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();
        irService.issue(id, null, owner);
        jdbc.update("DELETE FROM sales.shipment_lot WHERE import_request_id = :id", Map.of("id", id));
        return id;
    }

    /** Division codes are stored upper case (SA), so a raw lower case compare left this picker empty on a real database. */
    @Test
    void the_rep_picker_lists_sales_and_never_another_division() {
        long salesRep = employeeIn("JOB-SA", "SA");
        long importStaff = employeeIn("JOB-PC", "PCIM");

        List<Long> ids = jobs.repOptions(importUser).stream().map(ImportJobDtos.RepOption::id).toList();

        assertThat(ids).contains(salesRep).doesNotContain(importStaff);
        assertThatThrownBy(() -> jobs.repOptions(rep)).isInstanceOf(ApiException.class);
    }

    private long employeeIn(String code, String divisionCode) {
        List<Long> found = jdbc.queryForList("SELECT division_id FROM hr.division WHERE source_code = :c", Map.of("c", divisionCode), Long.class);
        long division = found.isEmpty()
            ? jdbc.queryForObject("INSERT INTO hr.division (source_code, name_th) VALUES (:c, :c) RETURNING division_id", Map.of("c", divisionCode), Long.class)
            : found.get(0);
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, division_id) VALUES (:c, 'ทดสอบ', 'งานนำเข้า', :d) RETURNING employee_id",
            Map.of("c", code, "d", division), Long.class);
    }

    private UserPrincipal user(String code, String role) {
        long id = jdbc.queryForObject("INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) VALUES (:c, 'ทดสอบ', 'งานนำเข้า') RETURNING employee_id",
            Map.of("c", code), Long.class);
        return new UserPrincipal(id, code.toLowerCase() + "@glr.co.th", code, role, id, true, LocalDate.of(2020, 1, 1), false, null, false);
    }
}
