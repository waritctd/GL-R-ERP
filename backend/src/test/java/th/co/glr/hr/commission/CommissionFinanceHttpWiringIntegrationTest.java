package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** The NEW commission / finance API proven wired at the HTTP layer: the REAL CommissionController and REAL
 * FinanceDealController (with ApiExceptionHandler) behind MockMvc standalone setup, hand-wired to the real
 * services and repositories over real Postgres. */
class CommissionFinanceHttpWiringIntegrationTest extends AbstractPostgresIntegrationTest {

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
            (th.co.glr.hr.pricingrequest.PricingRequestService) null, new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
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

    // fixtures

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
