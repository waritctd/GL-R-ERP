package th.co.glr.hr.pricingchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
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
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Evidence for the cancel cutoff (owner ruling 2026-08-13): a pricing request is cancellable up to and
 * including APPROVED_FOR_QUOTATION, and refused from QUOTATION_ISSUED onward. The widening and the refusal are
 * one ruling and are tested as one. */
class PricingRequestCancelCutoffIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FACTORY = "Factory Cancel-Cutoff";

    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private RecordingSalesMailer mailer;

    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireChainAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-cancel-cutoff-test-uploads");

        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCosts = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCosts);

        PricingDecisionRepository decisions = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisions, pricingRequests,
            new PricingCostingRepository(jdbc), tickets, fxRates, notifications, landedCosts, formulaEngine);

        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        quotationService = new CustomerQuotationService(new CustomerQuotationRepository(jdbc), pricingRequests,
            decisions, tickets, ticketService, customers, new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        salesActor = actor(createEmployee(employees, "พนักงานขาย ยกเลิก", "sales-cancel@glr.co.th", "SALES", "แผนกขาย"), "sales");
        otherSalesActor = actor(createEmployee(employees, "พนักงานขาย อื่น", "sales-other-cancel@glr.co.th", "SALES", "แผนกขาย"), "sales");
        importActor = actor(createEmployee(employees, "ฝ่ายนำเข้า ยกเลิก", "import-cancel@glr.co.th", "PCIM", "ฝ่ายนำเข้า"), "import");
        ceoActor = actor(createEmployee(employees, "ผู้บริหาร ยกเลิก", "ceo-cancel@glr.co.th", "MD", "ผู้บริหาร"), "ceo");

        catalogProductId = insertCatalogProduct(FACTORY, "IT", "TEST-CANCEL-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Cancel Cutoff จำกัด", "0100000000031", "31 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0031");
        ProjectDto project = projects.create(customer.id(), "โครงการ Cancel Cutoff");
        TicketDto created = ticketService.create(new CreateTicketRequest(
            "ดีล Cancel Cutoff", "NORMAL", customer.name(), customer.id(), project.id(), null, null, null,
            List.of(ticketItem())), salesActor);
        ticketId = created.summary().id();
    }

    // Guard 1 — the cutoff itself, wrong-way-round. This is the point of the change.

    /** The line in the sand. It goes through the DEAL (markLost/cancel, whose cascade uses the cancelForDeadDeal
     * bypass) or through a customer-change revision — never through this endpoint. */
    @Test
    void cancel_fromQuotationIssued_isRefused_andTheRequestStaysExactlyWhereItWas() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(pricingRequestId);
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Re-read from the DB, not from a returned DTO: the refusal is only worth anything if the ROW did not move.
        // cancelled_at/cancelled_by are checked too — a guard that returned 409 after already stamping the row would
        // pass a status-only assertion.
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
    }

    // Guard 2 — the role gate, wrong-way-round.

    /** Ownership is the gate. Another sales rep — same role, same division, no relationship to this deal — must not
     * be able to cancel it. What this test does and does not pin, established by mutation-check. */
    @Test
    void cancel_byASalesRepWhoDoesNotOwnTheDeal_isRefused_andTheRequestIsUntouched() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
    }

    /** The recorded deliberate divergence, pinned so it cannot be "tidied away" into consistency with
     * TicketService.cancel (owner-only, no CEO override). A manager must be able to unwind an abandoned request
     * without the original rep's session. */
    @Test
    void cancel_byTheCeoWhoDoesNotOwnTheDeal_isAllowed_theDeliberateDivergenceFromTicketCancel() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), ceoActor);

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CANCELLED);
    }

    // The widening — the three statuses that could not be cancelled at all before.

    /** The status this gap hurt most: the CEO has the request open, the customer walks away, and before this change
     * there was nothing the deal owner could do about it short of killing the whole ticket. The CEO's open DRAFT
     * decision must go with it. */
    @Test
    void cancel_fromCeoReviewing_isNowAllowed_andRetiresTheCeosOpenDraftDecision() {
        long pricingRequestId = readyForCeoReviewPricingRequest(new BigDecimal("10"));
        decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(decisionStatusOf(pricingRequestId)).isEqualTo("DRAFT");

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CANCELLED);
        assertThat(decisionStatusOf(pricingRequestId)).isEqualTo("SUPERSEDED");
    }

    // Fixtures — every precondition is driven through the real services, never SQL

    /** Drives a fresh single-item pricing request from DRAFT to APPROVED_FOR_QUOTATION. */
    private long approvedPricingRequest(BigDecimal quantity) {
        long pricingRequestId = readyForCeoReviewPricingRequest(quantity);
        approveCeoDecision(pricingRequestId);
        return pricingRequestId;
    }

    /** Drives a fresh single-item pricing request from DRAFT to READY_FOR_CEO_REVIEW. */
    private long readyForCeoReviewPricingRequest(BigDecimal quantity) {
        PricingRequestRequests.CreatePricingRequestRequest request =
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                new BigDecimal("5000.00"), "THB", "cancel cutoff walk", UUID.randomUUID().toString(),
                List.of(pricingItem(quantity)));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).get(0);
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            factoryResponse(draft.items().get(0).pricingRequestItemId(), quantity), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    private void approveCeoDecision(long pricingRequestId) {
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        }
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
    }

    private CustomerQuotationDto issueQuotation(long pricingRequestId) {
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        return quotationService.issue(draft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
    }

    private PricingRequestRequests.CancelPricingRequestRequest cancelRequest() {
        return new PricingRequestRequests.CancelPricingRequestRequest("ลูกค้ายกเลิกโครงการ");
    }

    // ── Assertions read the DB directly: a refusal is only evidence if the ROW did not move ──

    private String statusOf(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class);
    }

    /** True when either cancel stamp is set — catches a guard that 409s only after writing. */
    private boolean isCancelStamped(long pricingRequestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT cancelled_at IS NOT NULL OR cancelled_by IS NOT NULL
              FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), Boolean.class));
    }

    private String decisionStatusOf(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class);
    }

    // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
    // required on every item PricingRequestService#createDraft persists —
    // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
    private PricingRequestRequests.PricingRequestItemRequest pricingItem(BigDecimal quantity) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null, "SCG",
            "Tile Cancel", "SCG Tile Cancel", "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private ReceiveFactoryQuoteRequest factoryResponse(long pricingRequestItemId, BigDecimal quantity) {
        return new ReceiveFactoryQuoteRequest("REF-CANCEL-" + UUID.randomUUID(), "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile Cancel", "White", "Matte", "60x60", FACTORY,
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
