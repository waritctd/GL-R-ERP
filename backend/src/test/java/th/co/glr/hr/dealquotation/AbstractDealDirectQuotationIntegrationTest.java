package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

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
import th.co.glr.hr.support.WorkbookTexts;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Shared real-Postgres wiring for tests of a DIRECT-deal quotation: every service is the production
 * class, hand-wired exactly as {@link DealQuotationOutcomeIntegrationTest} wires it — no Mockito
 * anywhere, so every refusal a subclass asserts is the real service's and every "nothing was
 * written" is read back from the real tables.
 */
abstract class AbstractDealDirectQuotationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The project every {@link #deal} hangs off — what a quotation created on one prints as its
     * "Project : …" line unless the request says otherwise. */
    protected static final String PROJECT_NAME = "โครงการ ทดสอบใบเสนอราคา";

    protected DealQuotationService quotationService;
    protected UserPrincipal salesActor;
    protected UserPrincipal salesManagerActor;

    private TicketService ticketService;
    private CustomerDto customer;
    private long projectId;

    @BeforeEach
    void wireDirectQuotationServices() {
        TicketRepository tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        PricingRequestService pricingRequestService = new PricingRequestService(pricingRequests, tickets,
            notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-direct-quotation-test-uploads"), factoryQuoteCarryForward());
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

        salesActor = actor(createEmployee(employees, "พนักงานขาย ใบเสนอราคา", "sales-dq@glr.co.th", "SALES", "แผนกขาย",
            null), "sales");
        salesManagerActor = actor(createEmployee(employees, "ผจก.ขาย ใบเสนอราคา", "sm-dq@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");

        customer = customers.create("บริษัท ทดสอบใบเสนอราคา จำกัด", "0100000000202", "202 ถนนทดสอบ", "สนญ.",
            "02-000-0202");
        projectId = new ProjectRepository(jdbc).create(customer.id(), PROJECT_NAME).id();
    }

    /** An ordinary pipeline deal owned by {@code creator}, under {@link #PROJECT_NAME}. */
    protected long deal(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest("ดีล ทดสอบใบเสนอราคา", "NORMAL", customer.name(),
            customer.id(), projectId, null, null, null, List.of()), creator).summary().id();
    }

    /** One TILE line and one PLAIN line, both priced per แผ่น. */
    protected static UpsertDealQuotationRequest draft() {
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
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(tile, plain));
    }

    // ── a priced tile row, for the classes that are about how a row is priced ───────────────
    // The owner's own 60×120 (0.72 ตร.ม./แผ่น → 1.39 แผ่น/ตร.ม., 2 แผ่น/กล่อง) unless a test says
    // otherwise, so the expected figures are hers: 1,800 → 1,210.25 a piece and 790 → 531.16.

    protected static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** A TILE row of 10 แผ่น with no wastage, so 10 is what every amount is multiplied by. Built
     * through the constructor shape that takes the two per-ตร.ม. prices (the older shapes default
     * them to null) and no price mode, so the row states none. */
    protected static ItemInput item(String sizeText, String sqmPerPiece, Integer piecesPerBox, String unitPrice,
                                    String discountPct, String specialPriceSqm, String listPriceSqm,
                                    String directNetPriceSqm) {
        return new ItemInput(
            null, null, null, "Panaria", "Trilogy", "Ivory", "Lappato", sizeText, bd("10.00"),
            bd(sqmPerPiece), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, piecesPerBox, bd(unitPrice), bd(discountPct), "อิตาลี",
            75, 90, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, bd(specialPriceSqm), null, null, null,
            null, null, null, false, bd(listPriceSqm), bd(directNetPriceSqm));
    }

    /** The 60×120 priced per แผ่น — the shape every request had before V192.2, and the one the
     * English form, the pricing-request path and the e2e suite still send. */
    protected static ItemInput perPiece(String unitPrice, String discountPct) {
        return item("60x120", "0.72", 2, unitPrice, discountPct, null, null, null);
    }

    /** A สินค้า/บริการอื่น line: ค่าขนส่ง, 2 × 500.00. */
    protected static ItemInput freight() {
        return new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, bd("500.00"), BigDecimal.ZERO, null,
            null, null, null, WastageCalculator.LINE_TYPE_PLAIN, "ค่าขนส่ง", bd("2"), "JOB", null, null,
            null, null, null, null, null, null, null, null);
    }

    /** {@code row} as the editor sends a row it has already saved: carrying its server id, which is
     * what makes {@code DealQuotationService#update} rewrite it in place. */
    protected static ItemInput withId(long id, ItemInput row) {
        return new ItemInput(row.locationLabel(), row.catalogPriceId(), row.productCode(), row.brand(), row.model(),
            row.color(), row.texture(), row.sizeText(), row.thicknessMm(), row.sqmPerPiece(), row.quantityMode(),
            row.areaSqm(), row.piecesInput(), row.wastageMode(), row.wastageValue(), row.piecesPerBox(),
            row.unitPrice(), row.discountPct(), row.originCountry(), row.leadTimeMinDays(), row.leadTimeMaxDays(),
            row.itemNotes(), row.lineType(), row.description(), row.quantity(), row.unit(), row.specialPriceSqm(),
            row.directNetPrice(), row.adjustmentPct(), row.adjustmentDeadline(), row.adjustmentAmount(), id,
            row.sqmPerBox(), row.roundToFullBox(), row.listPriceSqm(), row.directNetPriceSqm(), row.priceMode());
    }

    /** A Thai request in {@code priceMode} holding {@code items}, on the terms {@link #draft} uses. */
    protected static UpsertDealQuotationRequest request(String priceMode, ItemInput... items) {
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, priceMode, "TH", "THB", List.of(items));
    }

    protected DealQuotationDto approved(long ticketId, UserPrincipal creator) {
        return approved(ticketId, draft(), creator);
    }

    /** {@code request} created on the deal by {@code creator}, submitted, and approved by the sales
     * manager. */
    protected DealQuotationDto approved(long ticketId, UpsertDealQuotationRequest request, UserPrincipal creator) {
        DealQuotationDto created = quotationService.create(ticketId, request, creator);
        quotationService.submit(created.id(), creator);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    protected int quotationCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    /** Every text cell of the workbook the SERVICE renders for the stored quotation — the same
     * render model the PDF is printed from. */
    protected List<String> sheetTexts(long quotationId) {
        return WorkbookTexts.of(quotationService.renderXlsx(quotationId, salesActor));
    }

    protected static ApiException refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).as("expected a %s refusal", status).isInstanceOf(ApiException.class);
        ApiException e = (ApiException) thrown;
        assertThat(e.getStatus()).as("status of: %s", e.getMessage()).isEqualTo(status);
        return e;
    }

    protected static long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                         String divisionSourceCode, String divisionNameTh, String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    protected static UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
