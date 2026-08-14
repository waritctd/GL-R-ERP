package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Mail cannot be un-sent, so a sales notification raised inside a transaction that later rolls back
 * must reach nobody. This drives the real {@link PricingRequestService#submit} — which raises two
 * sales notifications, one to Import and one to the CEO — and then fails the transaction.
 *
 * <p><b>Why the harness is what it is.</b> Every integration test in this suite hand-wires its
 * services with {@code new}: no Spring context, no AOP proxy, so the {@code @Transactional} on
 * {@code submit} is inert and a plain call would commit each statement on its own. {@link
 * AbstractPostgresIntegrationTest#transactional} wraps the service in a real transactional proxy so
 * the production annotation is honoured exactly as it is in production.
 *
 * <p><b>What this test does and does not pin.</b> The rollback is forced from an enclosing {@link
 * AbstractPostgresIntegrationTest#transactionTemplate} because {@code submit} has no reachable
 * failure after the notification — it notifies and returns. So this pins the <b>deferral</b>
 * ({@code SalesNotificationMailRouter} → {@link AfterCommit}), which is the thing being added here,
 * and not {@code submit}'s own {@code @Transactional}. The zero-rows assertion is load-bearing: it
 * proves the transaction really did roll back, rather than the test never reaching the notification.
 *
 * <p><b>MUTATION-CHECK RECORD (actually run, not simulated).</b> Removing {@code AfterCommit.run(...)}
 * from both {@code SalesNotificationMailRouter} entry points — so mail sends inline at notify time —
 * turned {@link #aRolledBackPricingRequestSubmissionEmailsNobody} red and nothing else in the suite;
 * the source was then restored and verified byte-identical by SHA-256. Mutation 2 in {@code
 * SalesNotificationMailRoutingIntegrationTest}'s record (resolving the CEO address from the employee
 * row) turned {@link #aCommittedPricingRequestSubmissionEmailsImportAndTheCeo} red, which is what
 * stops the pair of tests here from passing vacuously on wiring that could never send.
 */
class SalesNotificationEmailRollbackIntegrationTest extends AbstractPostgresIntegrationTest {

    private PricingRequestService pricingRequestService;
    private CapturingMailer mailer;
    private UserPrincipal salesActor;
    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireTheRealSalesChain() {
        mailer = new CapturingMailer();
        NotificationEmailService emailService = new NotificationEmailService(
            mailer, new BrandAssets(), "", "", "https://portal.test.glr");
        NotificationRepository notifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(emailService, new SalesMailRecipientRepository(jdbc)));

        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-sales-mail-rollback-uploads"), factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        long salesRepId = createEmployee(employees, "ณภา ขายดี", "napa.sales@glr.co.th", "SALES", "ฝ่ายขาย");
        // Real recipients for the two notifications submit() raises, so the "no mail" assertion is
        // about the deferral rather than about there being nobody to mail in the first place.
        createEmployee(employees, "สมศักดิ์ นำเข้า", "somsak.import@glr.co.th", "PCIM", "จัดซื้อต่างประเทศ");
        createManagingDirector(employees, "ราม อิฐรัตน์", "someone.else@example.com");
        salesActor = actor(salesRepId, "sales");

        catalogProductId = insertCatalogProduct("Rollback Test Factory", "TH", "ROLLBACK-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท ทดสอบ จำกัด", "0100000000000", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบ");
        ticketId = ticketService.create(
            new CreateTicketRequest("ใบเสนอราคา", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem())),
            salesActor).summary().id();

        // The deal creation above must not leave anything behind for the assertions below to
        // mistake for a rolled-back send: TicketService.create raises no notification at all.
        assertThat(mailer.recipients()).isEmpty();
    }

    @Test
    void aRolledBackPricingRequestSubmissionEmailsNobody() {
        long pricingRequestId = pricingRequestService
            .createDraft(ticketId, draftRequest(), salesActor).summary().id();
        PricingRequestService transactionalService = transactional(pricingRequestService);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            transactionalService.submit(pricingRequestId, salesActor);
            throw new IllegalStateException("forced rollback after the notifications were raised");
        })).isInstanceOf(IllegalStateException.class);

        // Load-bearing: zero rows proves the transaction actually rolled back, so the "no mail"
        // assertion below is about the deferral and not about a submit() that never ran.
        assertThat(countNotificationRows()).isZero();
        assertThat(mailer.sent())
            .as("a rolled-back submission must not email Import or the CEO about a pricing request "
                + "that no longer exists")
            .isEmpty();
    }

    /**
     * The other half of the same contract, and the reason this file cannot consist of the rollback
     * case alone: a test that only ever asserts "no mail" stays green if the wiring can never send.
     * Committing the same submission must mail both recipients.
     */
    @Test
    void aCommittedPricingRequestSubmissionEmailsImportAndTheCeo() {
        long pricingRequestId = pricingRequestService
            .createDraft(ticketId, draftRequest(), salesActor).summary().id();

        transactionTemplate.execute(status -> transactional(pricingRequestService)
            .submit(pricingRequestId, salesActor));

        assertThat(mailer.recipients())
            .containsExactlyInAnyOrder("import@glr.co.th", "rarm@glr.co.th");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private Long countNotificationRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification", Map.of(), Long.class);
    }

    private CreatePricingRequestRequest draftRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "note", "33333333-3333-3333-3333-333333333333",
            List.of(pricingItem()));
    }

    private PricingRequestItemRequest pricingItem() {
        return new PricingRequestItemRequest(null, catalogProductId, null, "Toyota", "Hilux", "Toyota Hilux",
            null, null, null, null, new BigDecimal("1"), null, "PIECE", UnitBasis.PER_PIECE,
            QuantityType.REFERENCE, null, null, null);
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("Toyota", "Hilux", "White", "Matte", "L", null,
            new BigDecimal("1"), null, null, null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private long createManagingDirector(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    /** Same double as {@code SalesNotificationMailRoutingIntegrationTest} — recipients only here. */
    private static final class CapturingMailer implements Mailer {
        private final List<String> sent = new ArrayList<>();

        List<String> sent() {
            return sent;
        }

        List<String> recipients() {
            return sent;
        }

        @Override
        public void send(String to, String subject, String body) {
            sent.add(to);
        }

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody,
                             List<InlineImage> inlineImages) {
            sent.add(to);
        }

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            sent.add(to);
        }

        @Override
        public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
            sent.add(to);
        }
    }
}
