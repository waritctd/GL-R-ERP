package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Quotation ↔ deal linking, slice 1 (IA §5/§8/§10, owner decision D4 "show legacy, read-only") —
 * {@code GET /api/deal-quotations} ({@link DealQuotationService#search}) and its tab-count sibling
 * ({@link DealQuotationService#counts}) return ALL origins, against real Postgres:
 *
 * <ul>
 *   <li>{@code DEAL_DIRECT} and {@code PRICING_REQUEST} rows as before, {@code readOnly = false};</li>
 *   <li>legacy v1 rows ({@code sales.quotation.origin IS NULL}) tagged {@code origin = "LEGACY"},
 *       {@code readOnly = true} — never presented as DEAL_DIRECT;</li>
 *   <li>every row carries its deal's {@code ticketCode} and live {@code dealStage};</li>
 *   <li>the optional {@code origin} filter narrows to exactly one origin; anything else is 400.</li>
 * </ul>
 *
 * <p>Scope is unchanged and applies to every origin (owner ruling 2026-09-24,
 * {@code listOwnerScope}). The price-bearing origins (PRICING_REQUEST, LEGACY) keep their existing
 * narrower READ rule on the list too — sales-owner / sales_manager / ceo only, no import/account/
 * grant bypass — so widening the list cannot leak a CEO price the by-id path would refuse.
 *
 * <p>Authz cases are wrong-way-round: they assert what a caller does NOT get.
 */
class DealQuotationSearchOriginsIntegrationTest extends AbstractPostgresIntegrationTest {
    private DealQuotationService quotationService;
    private TicketService ticketService;
    private TicketRepository tickets;
    private EmployeeRepository employees;
    private CustomerDto customer;
    private long projectId;

    private UserPrincipal salesA;
    private UserPrincipal salesB;
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal importActor;
    private UserPrincipal grantedQc;

    // Rep A's deals and rows
    private long dealA;
    private long dealA2;
    private long directA;   // DEAL_DIRECT on dealA (PENDING_APPROVAL)
    private long legacyA;   // origin NULL on dealA
    private long prA2;      // PRICING_REQUEST on dealA2 (APPROVED)
    // Rep B's deal and rows
    private long dealB;
    private long directB;   // DEAL_DIRECT on dealB (DRAFT)
    private long prB;       // PRICING_REQUEST on dealB (CANCELLED)

    @BeforeEach
    void wireAndSeed() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        PricingRequestService pricingRequestService = new PricingRequestService(pricingRequests, tickets,
            notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-qdl-s1-search-test-uploads"), factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, customers, new QuotationRenderer(),
            pricingRequestService, new EmployeeAuthRepository(jdbc));
        Mailer noMail = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {}

            @Override
            public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                 List<Mailer.InlineImage> inlineImages) {}

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Mailer.Attachment> attachments) {}
        };
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)),
            tickets, customers, new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, new PricingDecisionRepository(jdbc),
            new CustomerQuotationRepository(jdbc));
        quotationService.wireTicketService(ticketService);

        salesA = actor(createEmployee("ขาย A", "qdl-sales-a@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        salesB = actor(createEmployee("ขาย B", "qdl-sales-b@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        salesManager = actor(createEmployee("ผจก.ขาย", "qdl-sm@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");
        ceo = actor(createEmployee("ผู้บริหาร", "qdl-ceo@glr.co.th", "MD", "ผู้บริหาร", "กรรมการผู้จัดการ"), "ceo");
        importActor = actor(createEmployee("นำเข้า", "qdl-import@glr.co.th", "PCIM", "ฝ่ายนำเข้า", null), "import");
        long qcId = createEmployee("QC", "qdl-qc@glr.co.th", "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qcId));
        grantedQc = actor(qcId, "qc");

        customer = customers.create("บริษัท ลิงก์ใบเสนอราคา จำกัด", "0100000000777", "777 ถนนทดสอบ", "สนญ.",
            "02-000-0777");
        projectId = projects.create(customer.id(), "โครงการลิงก์ใบเสนอราคา").id();

        dealA = deal(salesA);
        dealA2 = deal(salesA);
        dealB = deal(salesB);

        directA = quotationService.submit(quotationService.create(dealA, directDraft(), salesA).id(), salesA).id();
        legacyA = insertLegacyQuotation(dealA, salesA.id(), "QN-LEGACY-A", "ลูกค้าเดิม A", new BigDecimal("1000.00"));
        prA2 = pricingRequestOrigin(dealA2, salesA, "APPROVED");
        directB = quotationService.create(dealB, directDraft(), salesB).id();
        prB = pricingRequestOrigin(dealB, salesB, "CANCELLED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // All origins, legacy tagged read-only, ticketCode on every row.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void salesManagerList_returnsOneRowOfEachOrigin_legacyTaggedReadOnly_withTicketCode() {
        List<DealQuotationDto> rows = quotationService.search(null, false, null, salesManager);

        assertThat(ids(rows)).containsExactlyInAnyOrder(directA, legacyA, prA2, directB, prB);
        assertThat(rows).extracting(DealQuotationDto::origin)
            .contains("DEAL_DIRECT", "PRICING_REQUEST", "LEGACY");

        DealQuotationDto legacy = byId(rows, legacyA);
        assertThat(legacy.origin()).isEqualTo("LEGACY");
        assertThat(legacy.readOnly()).isTrue();
        assertThat(legacy.number()).isEqualTo("QN-LEGACY-A");
        assertThat(legacy.customerName()).isEqualTo("ลูกค้าเดิม A");
        assertThat(legacy.ticketId()).isEqualTo(dealA);
        assertThat(legacy.ticketCode()).isEqualTo(code(dealA)).isNotBlank();
        assertThat(legacy.subtotalAmount()).isEqualByComparingTo("1000.00");
        assertThat(legacy.items()).as("legacy lines are never mapped through this engine").isEmpty();
        assertThat(legacy.pricingRequestId()).isNull();

        // A legacy row is NEVER presented as DEAL_DIRECT.
        assertThat(rows.stream().filter(r -> "DEAL_DIRECT".equals(r.origin())).map(DealQuotationDto::id))
            .containsExactlyInAnyOrder(directA, directB);

        for (long id : List.of(directA, prA2, directB, prB)) {
            DealQuotationDto row = byId(rows, id);
            assertThat(row.readOnly()).as("row %d readOnly", id).isFalse();
            assertThat(row.ticketCode()).as("row %d ticketCode", id).isEqualTo(code(row.ticketId()));
        }
        assertThat(byId(rows, prA2).origin()).isEqualTo("PRICING_REQUEST");

        // Every row carries its deal's LIVE stage. This asserted LEAD_APPROACH for all of them, because a
        // quotation did not move its deal. By the owner rule of 2026-10-05 (M2) it does: the fixture put an
        // OWNER-recipient direct quotation on each of the three deals through the real service (setup calls
        // create / createAlongsideLive for directA, directB and the two re-tagged PRICING_REQUEST rows), so
        // every deal is on ขั้น 5 — the legacy row's deal (deal A) included. The legacy row itself was
        // inserted by SQL and moved nothing; it only reads the stage its deal is on.
        List<Executable> stageOfEveryRow = new ArrayList<>();
        stageOfEveryRow.add(() -> assertThat(legacy.dealStage()).as("legacy row (deal A) dealStage")
            .isEqualTo(DealStage.QUOTE_OWNER));
        for (long id : List.of(directA, prA2, directB, prB)) {
            stageOfEveryRow.add(() -> assertThat(byId(rows, id).dealStage()).as("row %d dealStage", id)
                .isEqualTo(DealStage.QUOTE_OWNER));
        }
        assertAll("every row carries its deal's live stage", stageOfEveryRow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEAL_DIRECT", "PRICING_REQUEST", "LEGACY"})
    void originFilter_narrowsToExactlyThatOrigin(String origin) {
        List<DealQuotationDto> rows = quotationService.search(null, false, origin, salesManager);

        Set<Long> expected = switch (origin) {
            case "DEAL_DIRECT" -> Set.of(directA, directB);
            case "PRICING_REQUEST" -> Set.of(prA2, prB);
            default -> Set.of(legacyA);
        };
        assertThat(ids(rows)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(rows).allSatisfy(r -> assertThat(r.origin()).isEqualTo(origin));
        // The filter composes with the tab counts: counts under the same filter agree with it.
        assertThat(quotationService.counts(origin, salesManager).all()).isEqualTo(rows.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "FOO", "deal_direct", "NULL"})
    void originFilter_unknownValue_is400(String origin) {
        assertThatThrownBy(() -> quotationService.search(null, false, origin, salesManager))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ไม่รองรับ");
            });
        assertThatThrownBy(() -> quotationService.counts(origin, salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Scope — wrong-way-round.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void salesRep_seesOnlyOwnDealRows_acrossAllOrigins_anotherRepsPricingRequestQuotationIsAbsent() {
        List<DealQuotationDto> rowsA = quotationService.search(null, false, null, salesA);

        assertThat(ids(rowsA)).containsExactlyInAnyOrder(directA, legacyA, prA2);
        assertThat(ids(rowsA)).doesNotContain(directB, prB);
        assertThat(ids(quotationService.search(null, false, "PRICING_REQUEST", salesA)))
            .containsExactly(prA2).doesNotContain(prB);
        assertThat(ids(quotationService.search(null, false, "LEGACY", salesB))).as("B has no legacy row").isEmpty();
        assertThat(ids(quotationService.search(null, false, null, salesB)))
            .containsExactlyInAnyOrder(directB, prB).doesNotContain(directA, legacyA, prA2);
    }

    /**
     * The price-bearing origins keep their narrower READ rule on the widened list: import and a
     * {@code can_create_quotation} grant-holder see only DEAL_DIRECT rows, even on a deal they
     * created themselves — exactly what {@code requireViewAccess} lets them open by id. Without this
     * the widened list would print a CEO-approved price/discount the detail endpoint refuses (403).
     */
    @Test
    void importAndGrantHolder_neverSeePricingRequestOrLegacyRows_evenOnTheirOwnDeal() {
        for (UserPrincipal reader : List.of(importActor, grantedQc)) {
            jdbc.update("UPDATE sales.ticket SET created_by = :by WHERE ticket_id IN (:ids)",
                new MapSqlParameterSource().addValue("by", reader.id()).addValue("ids", List.of(dealA, dealA2)));

            assertThat(ids(quotationService.search(null, false, null, reader)))
                .as("%s list", reader.role()).containsExactly(directA);
            assertThat(quotationService.search(null, false, "PRICING_REQUEST", reader)).isEmpty();
            assertThat(quotationService.search(null, false, "LEGACY", reader)).isEmpty();
            DealQuotationCountsDto counts = quotationService.counts(reader);
            assertThat(counts.all()).as("%s counts agree", reader.role()).isEqualTo(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Counts — widened identically, so a tab's count is the size of the list it shows.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void counts_matchTheWidenedList_perTab() {
        for (UserPrincipal actor : List.of(salesA, salesB, salesManager, ceo)) {
            DealQuotationCountsDto counts = quotationService.counts(actor);
            assertThat(counts.all()).as("%s all", actor.role())
                .isEqualTo(quotationService.search(null, false, null, actor).size());
            assertThat(counts.pendingApproval())
                .isEqualTo(quotationService.search(List.of("PENDING_APPROVAL"), false, null, actor).size());
            assertThat(counts.needsRework()).isEqualTo(quotationService.search(null, true, null, actor).size());
            assertThat(counts.cancelled())
                .isEqualTo(quotationService.search(List.of("CANCELLED"), false, null, actor).size());
            assertThat(counts.approved())
                .isEqualTo(quotationService.search(List.of("APPROVED"), false, null, actor).size());
        }
        // …and they really are widened (not agreeing only because both stayed DEAL_DIRECT-only).
        DealQuotationCountsDto own = quotationService.counts(salesA);
        assertThat(own.all()).isEqualTo(3);
        assertThat(own.approved()).as("the PRICING_REQUEST row").isEqualTo(1);
        DealQuotationCountsDto queue = quotationService.counts(salesManager);
        assertThat(queue.all()).isEqualTo(5);
        assertThat(queue.cancelled()).as("rep B's cancelled PRICING_REQUEST row").isEqualTo(1);
        assertThat(queue.pendingApproval()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Detail carries the deal link.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void findById_carriesTicketCodeAndDealStage() {
        DealQuotationDto detail = quotationService.get(directA, salesA);
        String stageOnTheFirstRead = detail.dealStage();
        assertThat(detail.ticketCode()).isEqualTo(code(dealA)).isNotBlank();
        assertThat(detail.readOnly()).isFalse();

        // Read live from the deal, never frozen on the quotation.
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'PRESENTATION' WHERE ticket_id = :id", Map.of("id", dealA));
        assertThat(quotationService.get(directA, salesA).dealStage()).isEqualTo(DealStage.PRESENTATION);
        assertThat(quotationService.listForTicket(dealA, salesA))
            .allSatisfy(q -> assertThat(q.ticketCode()).isEqualTo(code(dealA)));

        // And what that live read said BEFORE the stage was forced above: this asserted LEAD_APPROACH, because
        // a quotation did not move its deal. Setup created directA's OWNER-recipient quotation through the real
        // service, so by the owner rule of 2026-10-05 (M2) deal A is on ขั้น 5 by then. Last, so the live-read
        // half above is checked whatever the stage was.
        assertThat(stageOnTheFirstRead).as("dealStage on the first read of the detail")
            .isEqualTo(DealStage.QUOTE_OWNER);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Read-only is enforced: a LEGACY id never reaches a read-by-id or mutation path.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void legacyId_throughRequireQuotation_isStill404() {
        assertNotFound(() -> quotationService.get(legacyA, salesA));
        assertNotFound(() -> quotationService.get(legacyA, salesManager));
        assertNotFound(() -> quotationService.update(legacyA, directDraft(), salesA));
        assertNotFound(() -> quotationService.submit(legacyA, salesA));
        assertNotFound(() -> quotationService.approve(legacyA, new ApproveRequest(null), salesManager));
        assertNotFound(() -> quotationService.cancel(legacyA, new CancelRequest(null), salesA));
        assertNotFound(() -> quotationService.createRevision(legacyA, salesA));
        assertNotFound(() -> quotationService.createReorder(legacyA, salesA));
        assertNotFound(() -> quotationService.confirmOrderFromDirectQuotation(legacyA, salesA));
        assertNotFound(() -> quotationService.renderPdf(legacyA, salesA));
        // Nothing about the row changed.
        assertThat(jdbc.queryForObject("SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", legacyA), String.class)).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject("SELECT origin FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", legacyA), String.class)).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private long deal(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest("ดีลลิงก์", "NORMAL", customer.name(), customer.id(),
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7)), creator).summary().id();
    }

    /** A PRICING_REQUEST-origin row in {@code status}: an ordinary direct draft re-tagged and linked
     * to a real pricing_request row (the origin/pricing_request_id columns are what every read keys
     * on) — the same device {@code DealQuotationConfirmOrderIntegrationTest} uses. */
    private long pricingRequestOrigin(long ticketId, UserPrincipal creator, String status) {
        // Slice 2's N6: dealB already holds a live direct draft — see LegacyDirectQuotationFixtures.
        long id = LegacyDirectQuotationFixtures.createAlongsideLive(jdbc, quotationService, ticketId, directDraft(),
            creator).id();
        long pricingRequestId = jdbc.queryForObject("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, requested_by)
            VALUES (:code, :ticketId, 'DESIGNER', :by)
            RETURNING pricing_request_id
            """, new MapSqlParameterSource()
                .addValue("code", "PCR-QDL-" + ticketId)
                .addValue("ticketId", ticketId)
                .addValue("by", creator.id()), Long.class);
        jdbc.update("""
            UPDATE sales.quotation SET origin = 'PRICING_REQUEST', pricing_request_id = :pr, doc_status = :status
             WHERE quotation_id = :id
            """, Map.of("pr", pricingRequestId, "status", status, "id", id));
        return id;
    }

    /** A pre-v2 row as {@code customerquotation/} wrote it: {@code origin IS NULL}, issued_by, no
     * created_by, a stored total_amount. */
    private long insertLegacyQuotation(long ticketId, long issuedBy, String number, String customerName,
                                       BigDecimal totalAmount) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, quotation_version,
                                         customer_name, total_amount, currency)
            VALUES (:ticketId, :number, :by, NULL, 'ISSUED', 99, :customerName, :total, 'THB')
            RETURNING quotation_id
            """, new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("by", issuedBy)
                .addValue("customerName", customerName)
                .addValue("total", totalAmount), Long.class);
    }

    private UpsertDealQuotationRequest directDraft() {
        ItemInput tile = new ItemInput(
            null, null, null, "SCG", "Link Tile", "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false);
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(tile)).withRecipientType("OWNER");
    }

    private String code(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().code();
    }

    private static List<Long> ids(List<DealQuotationDto> rows) {
        return rows.stream().map(DealQuotationDto::id).toList();
    }

    private static DealQuotationDto byId(List<DealQuotationDto> rows, long id) {
        return rows.stream().filter(r -> r.id() == id).findFirst()
            .orElseThrow(() -> new AssertionError("row " + id + " missing from "
                + rows.stream().map(r -> r.id() + "/" + r.origin()).collect(Collectors.joining(","))));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh,
                                String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
