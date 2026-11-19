package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
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
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Production services hand-wired against real Postgres (no Mockito), plus the deal / quotation fixtures that a
 * test of the order-confirmation lifecycle needs: a rep, a sales manager, a CEO, import, account and a
 * quotation-grant holder; a customer; deals with an APPROVED direct quotation. Extracted from
 * {@code DealQuotationConfirmOrderIntegrationTest}'s setup so the undo test does not copy it.
 * {@link #confirmer} is the quotation service behind a REAL {@code @Transactional} proxy.
 */
abstract class DirectQuotationDealTestBase extends AbstractPostgresIntegrationTest {
    protected TicketRepository tickets;
    protected RecordingSalesMailer mailer;
    protected TicketService ticketService;
    protected DealQuotationService quotationService;
    protected DealQuotationService confirmer; // quotationService behind a real @Transactional proxy
    protected PricingRequestService pricingRequestService;
    protected DepositNoticeService depositNoticeService;
    protected CustomerRepository customers;
    protected EmployeeRepository employees;

    protected UserPrincipal salesActor;
    protected UserPrincipal otherSalesActor;
    protected UserPrincipal salesManagerActor;
    protected UserPrincipal ceoActor;
    protected UserPrincipal importActor;
    protected UserPrincipal accountActor;
    protected UserPrincipal grantedQcActor;

    protected CustomerDto customer;
    protected long projectId;

    @BeforeEach
    void wireDirectQuotationServices() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications,
            objectMapper, new ContactRepository(jdbc), new FileStorageService("/tmp/glr-undo-confirm-test-uploads"),
            factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, customers, new QuotationRenderer(),
            pricingRequestService, new EmployeeAuthRepository(jdbc), new ContactRepository(jdbc),
            new ProjectRepository(jdbc), new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)));

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
        CustomerQuotationRepository legacyQuotations = new CustomerQuotationRepository(jdbc);
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)),
            tickets, customers, new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, new PricingDecisionRepository(jdbc),
            legacyQuotations);
        quotationService.wireTicketService(ticketService);
        confirmer = transactional(quotationService);
        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticePdfRenderer(), customers, legacyQuotations,
            new th.co.glr.hr.deposit.DocumentBundle(new th.co.glr.hr.attachment.AttachmentRepository(jdbc),
                quotationService, new DepositNoticeRepository(jdbc)));

        salesActor = actor(createEmployee("พนักงานขาย 222", "sales-222@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        otherSalesActor = actor(createEmployee("พนักงานขาย 222 อื่น", "sales-222-other@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
        salesManagerActor = actor(createEmployee("ผจก.ขาย 222", "sm-222@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");
        ceoActor = actor(createEmployee("ผู้บริหาร 222", "ceo-222@glr.co.th", "MD", "ผู้บริหาร",
            "กรรมการผู้จัดการ"), "ceo");
        importActor = actor(createEmployee("นำเข้า 222", "import-222@glr.co.th", "PCIM", "ฝ่ายนำเข้า", null), "import");
        accountActor = actor(createEmployee("บัญชี 222", "account-222@glr.co.th", "ACCT", "ฝ่ายบัญชี", null), "account");
        long qcId = createEmployee("QC 222", "qc-222@glr.co.th", "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qcId));
        grantedQcActor = actor(qcId, "qc");

        customer = customers.create("บริษัท ทดสอบยกเลิกยืนยัน จำกัด", "0100000000222", "222 ถนนทดสอบ", "สนญ.", "02-000-0222");
        projectId = projects.create(customer.id(), "โครงการทดสอบยกเลิกยืนยัน 222").id();
    }

    // Fixtures

    protected long quotationOnlyTicket(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest(customer.name(), "NORMAL", customer.name(), customer.id(),
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7), true), creator).summary().id();
    }

    protected long pipelineTicket(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest("ดีล pipeline", "NORMAL", customer.name(), customer.id(),
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7)), creator).summary().id();
    }

    protected DealQuotationDto approvedDirectQuotation(long ticketId, UserPrincipal creator) {
        DealQuotationDto created = quotationService.create(ticketId, directDraft(), creator);
        DealQuotationDto submitted = quotationService.submit(created.id(), creator);
        DealQuotationDto approved = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    protected DealQuotationDto approvedQuotation(long ticketId, UpsertDealQuotationRequest draft) {
        DealQuotationDto created = quotationService.create(ticketId, draft, salesActor);
        quotationService.submit(created.id(), salesActor);
        return quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
    }

    protected DealQuotationDto approvedZeroDepositQuotation(long deal) {
        DealQuotationDto approved = approvedQuotation(deal, zeroDepositDirectDraft());
        assertThat(approved.depositPercent()).isZero();
        return approved;
    }

    /** Revise {@code source} to {@code draft}'s terms, submit, and approve. */
    protected DealQuotationDto approvedRevision(DealQuotationDto source, UpsertDealQuotationRequest draft) {
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        quotationService.update(revision.id(), draft, salesActor);
        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    /** One TILE line (10 pcs, loose), one PLAIN line (2 JOB), one ADJUSTMENT (flat -100); 50% deposit. */
    protected UpsertDealQuotationRequest directDraft() {
        ItemInput tile = new ItemInput(
            null, null, null, "SCG", "Direct Tile", "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false);
        ItemInput plain = new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, new BigDecimal("500.00"), BigDecimal.ZERO, null,
            null, null, null, WastageCalculator.LINE_TYPE_PLAIN, "ค่าขนส่ง", new BigDecimal("2"), "JOB", null, null,
            null, null, null, null, null, null);
        ItemInput adjustment = new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, WastageCalculator.LINE_TYPE_ADJUSTMENT, "ส่วนลดพิเศษ", null, null, null, null,
            null, null, new BigDecimal("100.00"), null, null, null);
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(tile, plain, adjustment)).withRecipientType("OWNER");
    }

    /** {@link #directDraft} as a ไม่รับมัดจำ (0%) quotation. */
    protected UpsertDealQuotationRequest zeroDepositDirectDraft() {
        return withDeposit(0, "BEFORE_DELIVERY", directDraft().items());
    }

    /** {@code stock} lines carry the จากสต็อก tick; a deal of only such lines routes to delivery on a 0% confirm. */
    protected UpsertDealQuotationRequest stockDraft(Integer depositPercent, String fullPaymentTerm, ItemInput... lines) {
        return withDeposit(depositPercent, fullPaymentTerm, List.of(lines));
    }

    private UpsertDealQuotationRequest withDeposit(Integer depositPercent, String fullPaymentTerm, List<ItemInput> items) {
        UpsertDealQuotationRequest d = directDraft();
        return new UpsertDealQuotationRequest(d.contactId(), d.deptCode(), d.unitCode(), d.offerDate(), depositPercent,
            d.remainderMode(), d.creditDays(), d.validityDays(), d.validityMode(), d.validityUntil(),
            d.customerNotes(), d.priceMode(), d.documentLanguage(), d.currency(), null, null, null, null,
            fullPaymentTerm, null, null, null, null, d.recipientType(), items);
    }

    protected ItemInput stockTile(String model, boolean stock) {
        return stockTile(model, stock, null);
    }

    /** {@code id} non-null is a row the editor already saved, which update rewrites in place. */
    protected ItemInput stockTile(String model, boolean stock, Long id) {
        return new ItemInput(
            null, null, null, "SCG", model, "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            id, null, false).withStockSource(stock ? "IN_THAILAND" : null);
    }

    // Reads and assertions

    protected TicketSummaryDto summary(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary();
    }

    protected int itemCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    protected int eventCount(long ticketId, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            Map.of("id", ticketId, "kind", kind), Integer.class);
    }

    protected static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    protected static void assertStatus(HttpStatus expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(expected));
    }

    protected long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh,
                                  String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    protected UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
