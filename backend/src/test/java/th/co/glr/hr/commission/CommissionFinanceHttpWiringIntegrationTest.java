package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.finance.FinanceDealController;
import th.co.glr.hr.finance.FinanceDealRepository;
import th.co.glr.hr.finance.FinanceDealService;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * The NEW commission / finance API proven wired at the HTTP layer: the REAL {@link CommissionController}
 * and REAL {@link FinanceDealController} (with {@link ApiExceptionHandler}) behind MockMvc standalone
 * setup, hand-wired to the real services and repositories over real Postgres. Every JSON key asserted
 * here is one the frontend actually reads (CommissionPage.jsx PendingCommissionCard / PendingApprovalView
 * / adjustWeight / loadPending, FinanceDealPage.jsx, FinanceMilestoneSections.jsx, and hrApi.js
 * createFromDeal), so a renamed / dropped record component fails here instead of in the browser.
 *
 * <p>Authz note: {@code @PreAuthorize} is NOT active in standalone MockMvc (no method-security proxy),
 * so the 403 cases below are produced by the SERVICE gate ({@link CommissionService}), surfaced
 * through {@link ApiExceptionHandler}. They prove the service refuses, not that the annotation does.
 */
class CommissionFinanceHttpWiringIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate JUNE_INVOICE = LocalDate.of(2026, 6, 15);

    /** FinanceDealDto.CommissionInvoice: the frontend reads all of these (INVOICE_FIELDS + block header). */
    private static final Set<String> COMMISSION_INVOICE_KEYS = Set.of(
        "invoiceNumber", "invoiceDate", "grossAmount", "bankFees", "suspenseVat", "transportFee", "cutFee",
        "shortfall", "withholdingTax", "overpayment", "fileName", "downloadPath", "approvalStatus",
        "rejectionReason", "recordedAt");

    private final ObjectMapper json = new ObjectMapper();

    private TicketRepository tickets;
    private CommissionRepository commissions;
    private MockMvc commissionMvc;
    private MockMvc financeMvc;

    private long repId;
    private UserPrincipal repActor;
    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;
    private UserPrincipal hrActor;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        AttachmentRepository attachments = new AttachmentRepository(jdbc);
        NotificationRepository notificationRepository = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CommissionService commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            new FileStorageService("/tmp/glr-http-wiring-test-uploads"),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            attachments,
            new CeoApproverRepository(jdbc));
        TicketService ticketService = new TicketService(tickets, notificationRepository, new ObjectMapper(),
            new CustomerRepository(jdbc), new QuotationRenderer(),
            (th.co.glr.hr.pricingrequest.PricingRequestService) null, new EmployeeAuthRepository(jdbc));
        FinanceDealService financeService = new FinanceDealService(tickets, new FinanceDealRepository(jdbc),
            new DepositNoticeRepository(jdbc), new RemainingInvoiceRepository(jdbc), attachments, ticketService);

        commissionMvc = MockMvcBuilders
            .standaloneSetup(new CommissionController(commissionService, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        financeMvc = MockMvcBuilders
            .standaloneSetup(new FinanceDealController(financeService, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        repId = createEmployee(employees, "พนักงานขาย เอชทีทีพี", "hw-rep@glr.co.th", "SL", "เจ้าหน้าที่");
        repActor = principal(repId, "sales");
        managerActor = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย เอชทีทีพี", "hw-mgr@glr.co.th", "SA", "ผู้จัดการฝ่ายขาย"),
            "sales_manager");
        ceoActor = principal(createEmployee(employees, "ซีอีโอ เอชทีทีพี", "hw-ceo@glr.co.th", "MD", "กรรมการผู้จัดการ"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี เอชทีทีพี", "hw-acct@glr.co.th", "ACCT", "เจ้าหน้าที่"), "account");
        importActor = principal(createEmployee(employees, "นำเข้า เอชทีทีพี", "hw-imp@glr.co.th", "PCIM", "เจ้าหน้าที่"), "import");
        hrActor = principal(createEmployee(employees, "บุคคล เอชทีทีพี", "hw-hr@glr.co.th", "HR", "เจ้าหน้าที่"), "hr");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void fromDeal_multipart_partNames_matchHrApi_andCreateSubmitted() throws Exception {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2));

        // EXACTLY the names hrApi.js createFromDeal appends to its FormData.
        MvcResult result = commissionMvc.perform(multipart("/api/commissions/from-deal")
                .file(new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes()))
                .param("ticketId", String.valueOf(ticketId))
                .param("invoiceNumber", "INV-HW-0001")
                .param("invoiceDate", "2026-06-15")
                .param("grossAmount", "300000")
                .param("bankFees", "0")
                .param("suspenseVat", "0")
                .param("transportFee", "0")
                .param("cutFee", "0")
                .param("shortfall", "0")
                .param("withholdingTax", "0")
                .param("overpayment", "0")
                .session(session(accountActor)))
            .andReturn();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("commission").path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(body.path("commission").path("invoiceDetails").path("invoiceNumber").asText())
            .isEqualTo("INV-HW-0001");
        assertThat(jdbc.queryForObject("SELECT status FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", body.path("commission").path("id").asLong()), String.class)).isEqualTo("SUBMITTED");
    }

    @Test
    void pendingApproval_http_shape_matchesFrontendReads() throws Exception {
        createRecord(dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 0, 2000, 1)), "300000.00");
        createRecord(dealWithItems(itemSpec(8, 8, 1500, 3)), "250000.00");

        MvcResult result = commissionMvc.perform(get("/api/commissions/pending-approval")
            .session(session(managerActor))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode root = json.readTree(result.getResponse().getContentAsString());

        assertThat(root.has("commissions")).as("top-level key `commissions`").isTrue();
        assertThat(root.get("commissions").isArray()).isTrue();
        assertThat(root.get("commissions")).hasSize(2);
        for (JsonNode entry : root.get("commissions")) {
            assertPendingEntryShape(entry);
        }
    }

    @Test
    void itemWeights_http_roundTrip() throws Exception {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long commissionId = createRecord(ticketId, "300000.00");
        long itemB = tickets.findById(ticketId).orElseThrow().items().get(1).id();

        MvcResult result = commissionMvc.perform(post("/api/commissions/{id}/item-weights", commissionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lines\":[{\"itemId\":" + itemB + ",\"weightMultiplier\":3}]}")
            .session(session(managerActor))).andReturn();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        assertThat(root.has("pending")).as("top-level key `pending`").isTrue();
        JsonNode pending = root.get("pending");
        assertPendingEntryShape(pending);

        assertThat(pending.get("commission").get("id").asLong()).isEqualTo(commissionId);
        BigDecimal stored = jdbc.queryForObject(
            "SELECT effective_weight_multiplier FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", commissionId), BigDecimal.class);
        assertThat(stored).isNotNull();
        assertThat(pending.get("effectiveWeight").decimalValue()).isEqualByComparingTo(stored);
        List<Integer> weights = new ArrayList<>();
        pending.get("items").forEach(i -> weights.add(i.get("weightMultiplier").asInt()));
        assertThat(weights).containsExactly(2, 3);
    }

    @Test
    void itemWeights_http_validation() throws Exception {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long commissionId = createRecord(ticketId, "300000.00");
        long itemB = tickets.findById(ticketId).orElseThrow().items().get(1).id();

        // @Max(3): 4 is refused by the controller's @Valid.
        assertThat(commissionMvc.perform(post("/api/commissions/{id}/item-weights", commissionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemB + ",\"weightMultiplier\":4}]}")
                .session(session(managerActor))).andReturn().getResponse().getStatus()).isEqualTo(400);
        // @NotEmpty: an empty lines list is refused.
        assertThat(commissionMvc.perform(post("/api/commissions/{id}/item-weights", commissionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[]}")
                .session(session(managerActor))).andReturn().getResponse().getStatus()).isEqualTo(400);

        // Nothing moved.
        assertThat(tickets.findById(ticketId).orElseThrow().items()).extracting(TicketItemDto::weightMultiplier)
            .containsExactly(2, 1);
    }

    @Test
    void financeDeal_http_shape_matchesFrontendReads() throws Exception {
        long ticketId = closedPaidDealWithQuotation();

        // BEFORE recording: RECORD_INVOICE offered to account, amountPayableExVat present, no commissionInvoice.
        JsonNode before = getDealJson(ticketId, accountActor);
        assertThat(before.path("money").path("amountPayableExVat").isNumber()).isTrue();
        assertThat(before.get("money").get("amountPayableExVat").decimalValue()).isEqualByComparingTo("100000.00");
        JsonNode recordAction = findAction(before, "RECORD_INVOICE");
        assertThat(recordAction).as("RECORD_INVOICE action offered to account").isNotNull();
        assertThat(recordAction.get("action").asText()).isEqualTo("RECORD_INVOICE");
        assertThat(recordAction.get("label").asText()).isEqualTo("บันทึกใบกำกับ");
        assertThat(before.has("commissionInvoice")).isTrue();
        assertThat(before.get("commissionInvoice").isNull()).as("null until recorded").isTrue();

        // Record via the REAL from-deal endpoint, all eight amounts populated.
        MvcResult recorded = commissionMvc.perform(multipart("/api/commissions/from-deal")
                .file(new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes()))
                .param("ticketId", String.valueOf(ticketId))
                .param("invoiceNumber", "INV-HW-0002")
                .param("invoiceDate", "2026-06-15")
                .param("grossAmount", "100000.00")
                .param("bankFees", "50.00")
                .param("suspenseVat", "70.00")
                .param("transportFee", "300.00")
                .param("cutFee", "20.00")
                .param("shortfall", "10.00")
                .param("withholdingTax", "1000.00")
                .param("overpayment", "5.00")
                .session(session(accountActor))).andReturn();
        assertThat(recorded.getResponse().getStatus()).as(recorded.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode after = getDealJson(ticketId, accountActor);
        assertThat(findAction(after, "RECORD_INVOICE")).as("action disappears once recorded").isNull();
        assertThat(after.get("money").get("amountPayableExVat").isNumber()).isTrue();
        JsonNode invoice = after.get("commissionInvoice");
        assertThat(invoice.isObject()).isTrue();

        List<String> keys = new ArrayList<>();
        invoice.fieldNames().forEachRemaining(keys::add);
        // Frontend-read keys must all be served, AND nothing outside the 15-key allowlist may leak.
        assertThat(new TreeSet<>(keys)).isEqualTo(new TreeSet<>(COMMISSION_INVOICE_KEYS));
        assertThat(keys).hasSize(15);
        for (String k : List.of("invoiceNumber", "invoiceDate", "fileName", "downloadPath", "approvalStatus", "recordedAt")) {
            assertThat(invoice.get(k).isTextual()).as(k + " is a string").isTrue();
        }
        for (String k : List.of("grossAmount", "bankFees", "suspenseVat", "transportFee", "cutFee", "shortfall",
            "withholdingTax", "overpayment")) {
            assertThat(invoice.get(k).isNumber()).as(k + " is a number").isTrue();
        }
        assertThat(invoice.get("invoiceNumber").asText()).isEqualTo("INV-HW-0002");
        assertThat(invoice.get("approvalStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(invoice.get("grossAmount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(invoice.get("downloadPath").asText()).startsWith("/api/attachments/").endsWith("/file");
    }

    @Test
    void wrongWayRound_http() throws Exception {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long commissionId = createRecord(ticketId, "300000.00");
        long itemB = tickets.findById(ticketId).orElseThrow().items().get(1).id();
        List<Integer> weightsBefore = tickets.findById(ticketId).orElseThrow().items().stream()
            .map(TicketItemDto::weightMultiplier).toList();

        // NB: standalone MockMvc has no method-security proxy, so @PreAuthorize is inert here; these 403s
        // come from the service-level role gate via ApiExceptionHandler.
        for (UserPrincipal caller : List.of(repActor, accountActor, importActor, hrActor)) {
            assertThat(commissionMvc.perform(get("/api/commissions/pending-approval").session(session(caller)))
                .andReturn().getResponse().getStatus()).as("GET pending-approval as %s", caller.role()).isEqualTo(403);
        }
        for (UserPrincipal caller : List.of(ceoActor, repActor, accountActor)) {
            assertThat(commissionMvc.perform(post("/api/commissions/{id}/item-weights", commissionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lines\":[{\"itemId\":" + itemB + ",\"weightMultiplier\":3}]}")
                    .session(session(caller))).andReturn().getResponse().getStatus())
                .as("POST item-weights as %s", caller.role()).isEqualTo(403);
        }
        assertThat(tickets.findById(ticketId).orElseThrow().items().stream().map(TicketItemDto::weightMultiplier).toList())
            .isEqualTo(weightsBefore);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // shape assertions
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Every field CommissionPage.jsx reads off a pending entry, with its JSON type. */
    private static void assertPendingEntryShape(JsonNode entry) {
        assertThat(entry.path("ticketCode").isTextual()).as("entry.ticketCode string").isTrue();
        assertThat(entry.path("customerName").isTextual()).as("entry.customerName string").isTrue();
        assertThat(entry.path("effectiveWeight").isNumber()).as("entry.effectiveWeight number").isTrue();
        assertThat(entry.path("weightedCommissionableBase").isNumber()).as("entry.weightedCommissionableBase number").isTrue();
        assertThat(entry.path("estimatedCommission").isNumber()).as("entry.estimatedCommission number").isTrue();

        JsonNode commission = entry.path("commission");
        assertThat(commission.isObject()).as("entry.commission object").isTrue();
        assertThat(commission.path("id").isNumber()).as("commission.id number").isTrue();
        assertThat(commission.path("status").isTextual()).as("commission.status string").isTrue();
        assertThat(commission.path("kind").isTextual()).as("commission.kind string").isTrue();
        assertThat(commission.path("salesRepId").isNumber()).as("commission.salesRepId number").isTrue();
        assertThat(commission.path("salesRepName").isTextual()).as("commission.salesRepName string").isTrue();
        assertThat(commission.path("actualReceived").isNumber()).as("commission.actualReceived number").isTrue();
        assertThat(commission.path("commissionableBase").isNumber()).as("commission.commissionableBase number").isTrue();
        assertThat(commission.has("dealAmountMismatch")).as("commission.dealAmountMismatch").isTrue();
        assertThat(commission.path("dealAmountMismatch").isBoolean()).isTrue();
        assertThat(commission.path("invoiceDetails").path("invoiceNumber").isTextual())
            .as("commission.invoiceDetails.invoiceNumber string").isTrue();

        JsonNode items = entry.path("items");
        assertThat(items.isArray()).as("entry.items array").isTrue();
        assertThat(items.size()).isGreaterThan(0);
        for (JsonNode item : items) {
            assertThat(item.path("itemId").isNumber()).as("item.itemId number").isTrue();
            assertThat(item.path("description").isTextual()).as("item.description string").isTrue();
            assertThat(item.path("qty").isNumber()).as("item.qty number").isTrue();
            assertThat(item.path("qtyFromStock").isNumber()).as("item.qtyFromStock number").isTrue();
            assertThat(item.path("weightMultiplier").isNumber()).as("item.weightMultiplier number").isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record ItemSpec(int qty, int qtyFromStock, int price, int weight) {}

    private static ItemSpec itemSpec(int qty, int qtyFromStock, int price, int weight) {
        return new ItemSpec(qty, qtyFromStock, price, weight);
    }

    /** A CLOSED_PAID deal owned by the rep, with priced, stock-declared, weighted items. */
    private long dealWithItems(ItemSpec... specs) {
        List<TicketItemRequest> requests = new ArrayList<>();
        int n = 0;
        for (ItemSpec spec : specs) {
            n++;
            requests.add(new TicketItemRequest("Brand" + n, "Model" + n, "สีขาว", null, "60x60", "Factory A",
                BigDecimal.valueOf(spec.qty()), null, "PIECE", null, null, null,
                BigDecimal.valueOf(spec.price()), "THB"));
        }
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีลเอชทีทีพี", "NORMAL", "ลูกค้าเอชทีทีพี", null, null, null, null, null, requests),
            tickets.nextTicketCode(), repId, "พนักงานขาย เอชทีทีพี");
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        for (int i = 0; i < specs.length; i++) {
            jdbc.update("""
                UPDATE sales.ticket_item
                   SET approved_price = :price, qty_from_stock = :stock, weight_multiplier = :weight
                 WHERE item_id = :id
                """, new MapSqlParameterSource()
                    .addValue("price", BigDecimal.valueOf(specs[i].price()))
                    .addValue("stock", BigDecimal.valueOf(specs[i].qtyFromStock()))
                    .addValue("weight", specs[i].weight())
                    .addValue("id", items.get(i).id()));
        }
        return ticketId;
    }

    /** CLOSED_PAID deal with an accepted 100,000.00 pre-VAT quotation (finance money block needs it). */
    private long closedPaidDealWithQuotation() {
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีลการเงิน", "NORMAL", "ลูกค้าการเงิน", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), repId, "พนักงานขาย เอชทีทีพี");
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);
        long quotationId = tickets.createQuotation(ticketId, "QT-HW-" + ticketId, repId, new BigDecimal("100000.00")).id();
        tickets.markQuotationStatus(ticketId, quotationId, "ACCEPTED");
        return ticketId;
    }

    /** The production path, via the service (the HTTP path is covered by the dedicated multipart tests). */
    private long createRecord(long ticketId, String gross) {
        // Invoked through the same controller so the record is created exactly as the browser does.
        try {
            MvcResult r = commissionMvc.perform(multipart("/api/commissions/from-deal")
                .file(new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes()))
                .param("ticketId", String.valueOf(ticketId))
                .param("invoiceNumber", "INV-HW-" + UUID.randomUUID().toString().substring(0, 8))
                .param("invoiceDate", JUNE_INVOICE.toString())
                .param("grossAmount", gross)
                .session(session(accountActor))).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json.readTree(r.getResponse().getContentAsString()).get("commission").get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode getDealJson(long ticketId, UserPrincipal actor) throws Exception {
        String body = financeMvc.perform(get("/api/finance/deals/{id}", ticketId).session(session(actor)))
            .andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(body);
        assertThat(root.has("deal")).as("response body: %s", body).isTrue();
        return root.get("deal");
    }

    private static JsonNode findAction(JsonNode deal, String action) {
        for (JsonNode a : deal.get("availableActions")) {
            if (action.equals(a.path("action").asText())) return a;
        }
        return null;
    }

    private static MockHttpSession session(UserPrincipal actor) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return session;
    }

    private long createEmployee(EmployeeRepository employees, String name, String email, String divisionCode,
                                String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode, divisionCode,
            positionTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-hw@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
