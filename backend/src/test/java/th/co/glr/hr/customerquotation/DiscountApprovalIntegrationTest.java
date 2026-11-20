package th.co.glr.hr.customerquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.DiscountApprovalDtos.DiscountApprovalDto;
import th.co.glr.hr.customerquotation.DiscountApprovalRequests.RejectDiscountApprovalRequest;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155) — real Postgres, real services, no
 * Mockito on the path under test. */
class DiscountApprovalIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private DiscountApprovalRepository discountApprovals;
    private DiscountApprovalService discountApprovalService;

    private long salesRepId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;
    private long ticketId;
    private long catalogProductId1;
    private long catalogProductId2;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-discount-approval-test-uploads");
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper,
            customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        discountApprovals = new DiscountApprovalRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, discountApprovals);
        discountApprovalService = new DiscountApprovalService(discountApprovals, quotationService, pricingRequests,
            notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย ส่วนลด", "sales-discount-approval@glr.co.th",
            "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า ส่วนลด", "import-discount-approval@glr.co.th",
            "PCIM", "ฝ่ายนำเข้า");
        // createManagingDirector-equivalent: a real position (กรรมการผู้จัดการ) is required for
        // CeoApproverRule.SQL_PREDICATE (notification routing) to match — plain division "MD" alone is not enough,
        // exactly as CustomerQuotationIntegrationTest's own comment explains.
        ceoUserId = employees.create(new UpsertEmployeeRequest(
            null, null, "ผู้บริหาร ส่วนลด", null, null, null, null, null, null, null,
            "ceo-discount-approval@glr.co.th", null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
        long accountUserId = createEmployee(employees, "บัญชี ส่วนลด", "account-discount-approval@glr.co.th",
            "ACCT", "ฝ่ายบัญชี");
        long salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย ส่วนลด",
            "sales-manager-discount-approval@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        catalogProductId1 = insertCatalogProduct("Factory Discount1", "IT", "TEST-DA-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductId2 = insertCatalogProduct("Factory Discount2", "IT", "TEST-DA-002",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Discount Approval จำกัด", "0100000000031", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0031");
        ProjectDto project = projects.create(customer.id(), "โครงการ Discount Approval");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Discount Approval", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, List.of(ticketItem("SCG", "Tile DA1", "Factory Discount1"),
                    ticketItem("Cotto", "Tile DA2", "Factory Discount2"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // Authorization — wrong-way-round (real DB, real service — not mocked)

    @Test
    void onlyCeoMayApproveOrReject_everyOtherRoleIsRefused() {
        long pricingRequestId = approvedOneItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        CustomerQuotationDto discounted = discountItem(draft.id(), item.id(), new BigDecimal("1.00"));
        DiscountApprovalDto pending = currentApprovalFor(discounted.id(), item.id());

        // sales (even the OWNING rep), sales_manager, account, import: every one of them REFUSED.
        for (UserPrincipal refused : List.of(salesActor, salesManagerActor, accountActor, importActor)) {
            assertThatThrownBy(() -> discountApprovalService.approve(pending.id(), refused))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> discountApprovalService.reject(pending.id(),
                new RejectDiscountApprovalRequest("เหตุผล"), refused))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        // The row is untouched by every refused attempt — still PENDING, no decider recorded.
        DiscountApprovalDto stillPending = discountApprovals.findById(pending.id()).orElseThrow();
        assertThat(stillPending.status()).isEqualTo("PENDING");
        assertThat(stillPending.decidedBy()).isNull();

        // The CEO still can — proves the guard is scoped to the role, not a blanket lockout.
        DiscountApprovalDto approved = discountApprovalService.approve(pending.id(), ceoActor);
        assertThat(approved.status()).isEqualTo("APPROVED");
    }

    // Helpers

    private CustomerQuotationDto discountItem(long quotationId, long itemId, BigDecimal discount) {
        return quotationService.update(quotationId, new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(itemId, null, null, discount))), salesActor);
    }

    /** The "current" per-line status a UI would show — see DiscountApprovalRepository#findCurrentByQuotationId's
     * own doc for why this is price-matched, not merely "the latest row". */
    private DiscountApprovalDto currentApprovalFor(long quotationId, long itemId) {
        return discountApprovalService.listForQuotation(quotationId, salesActor).stream()
            .filter(a -> a.quotationItemId() == itemId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no current discount approval for item " + itemId));
    }

    /** One-item deal driven to an APPROVED pricing decision at 20% margin — no explicit minimum set anywhere, so
     * Phase 1's auto-population (minimum = approved price) means ANY positive discount is below minimum, exactly
     * like approvedPricingRequest in CustomerQuotationIntegrationTest. */
    private long approvedOneItemPricingRequest() {
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId1, null, "SCG", "Tile DA1", "SCG Tile DA1", "White", "Matte", "60x60",
            "Factory Discount1", null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "discount approval test request", UUID.randomUUID().toString(),
            List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).get(0);
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            response("REF-DA1", draft.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        return pricingRequestId;
    }

    private ReceiveFactoryQuoteRequest response(String ref, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, "THB", "30 days", "45 days", "revision", "note",
            List.of(new ReceiveFactoryQuoteItemRequest(pricingRequestItemId, null, null,
                new BigDecimal("1.00"), "piece", "piece", new BigDecimal("100.00"), "THB", null,
                new BigDecimal("1.00"), null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
