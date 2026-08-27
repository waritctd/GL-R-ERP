package th.co.glr.hr.customerquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.DiscountApprovalDtos.DiscountApprovalDto;
import th.co.glr.hr.customerquotation.DiscountApprovalRequests.RejectDiscountApprovalRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
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
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155) — real Postgres, real
 * services, no Mockito on the path under test. Builds on {@code CustomerQuotationIntegrationTest}'s
 * own fixtures/style (this repo's convention is one self-contained fixture per file, e.g. {@code
 * PricingDecisionMinimumPriceAutoPopulationIntegrationTest} does not share {@code
 * CustomerQuotationIntegrationTest}'s either).
 *
 * <p><b>The single most important test in this file</b> is {@link
 * #approvalIsBoundToTheExactPrice_priceChangeAfterApprovalInvalidatesIt} — written wrong-way-round
 * per the task brief: approve a line, change its price, assert {@code issue()} is STILL refused.
 *
 * <p><b>Authorization</b> ({@link #onlyCeoMayApproveOrReject_everyOtherRoleIsRefused}) is also
 * written wrong-way-round: it asserts {@code sales}, {@code sales_manager}, {@code account} and
 * {@code import} all CANNOT approve/reject, not merely that the CEO can. {@code sales_manager} is
 * deliberately included in the refused set — the owner's words were "must be approved by CEO", and
 * nothing in the task brief grants sales_manager write access here (see {@code
 * DiscountApprovalService}'s own class Javadoc for the same statement).
 */
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
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        AppProperties dispatchProperties = new AppProperties();
        dispatchProperties.getFactoryQuoteDispatch().setReclaimTimeoutSeconds(2);
        dispatchProperties.getFactoryQuoteDispatch().setMaxAttempts(3);
        dispatchProperties.getFactoryQuoteDispatch().setBackoffBaseSeconds(1);
        dispatchProperties.getFactoryQuoteDispatch().setBatchSize(20);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper,
            customers, new QuotationRenderer(), pricingRequestService);
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
        // CeoApproverRule.SQL_PREDICATE (notification routing) to match — plain division "MD"
        // alone is not enough, exactly as CustomerQuotationIntegrationTest's own comment explains.
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

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Factory Discount1', 'factory-discount1@example.com', 'THB', 'piece', 'Italy'),
                   ('Factory Discount2', 'factory-discount2@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // THE critical invariant — wrong-way-round
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approvalIsBoundToTheExactPrice_priceChangeAfterApprovalInvalidatesIt() {
        long pricingRequestId = approvedOneItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);

        // Sales discounts to ฿1 off (below minimum, since minimum == approved price here) and the
        // CEO approves it AT THIS EXACT PRICE.
        CustomerQuotationDto atPriceA = discountItem(draft.id(), item.id(), new BigDecimal("1.00"));
        BigDecimal priceA = itemById(atPriceA, item.id()).finalUnitPrice();
        DiscountApprovalDto pendingA = currentApprovalFor(atPriceA.id(), item.id());
        assertThat(pendingA.status()).isEqualTo("PENDING");
        assertThat(pendingA.requestedFinalUnitPrice()).isEqualByComparingTo(priceA);

        DiscountApprovalDto approvedA = discountApprovalService.approve(pendingA.id(), ceoActor);
        assertThat(approvedA.status()).isEqualTo("APPROVED");
        assertThat(approvedA.approvedFinalUnitPrice()).isEqualByComparingTo(priceA);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'DISCOUNT_APPROVED'
            """, Map.of(), Long.class)).isEqualTo(1L);

        // Sales now edits the SAME line to a DIFFERENT below-minimum price B.
        CustomerQuotationDto atPriceB = discountItem(draft.id(), item.id(), new BigDecimal("2.00"));
        BigDecimal priceB = itemById(atPriceB, item.id()).finalUnitPrice();
        assertThat(priceB).isNotEqualByComparingTo(priceA);

        // ─── THE assertion this task is about ───────────────────────────────────────────
        // The approval granted at price A must NOT carry over to price B — issue() must
        // STILL be refused, even though this exact line WAS approved a moment ago.
        assertThatThrownBy(() -> quotationService.issue(atPriceB.id(), new IssueCustomerQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        // ─────────────────────────────────────────────────────────────────────────────────

        // The line correctly reads as needing approval again — a FRESH request exists for price
        // B, distinct from the one already decided for price A.
        DiscountApprovalDto pendingB = currentApprovalFor(atPriceB.id(), item.id());
        assertThat(pendingB.status()).isEqualTo("PENDING");
        assertThat(pendingB.requestedFinalUnitPrice()).isEqualByComparingTo(priceB);
        assertThat(pendingB.id()).isNotEqualTo(pendingA.id());

        // Approving price B (and ONLY approving price B) lets issue() succeed.
        discountApprovalService.approve(pendingB.id(), ceoActor);
        CustomerQuotationDto issued = quotationService.issue(atPriceB.id(), new IssueCustomerQuotationRequest(null), salesActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    /** If sales bounces back to a price the CEO already approved earlier (not the most recent
     * decision on this line), issue() succeeds without asking again — the gate is "was THIS
     * price approved", not "is the most recent decision an approval". */
    @Test
    void revertingToAPreviouslyApprovedPrice_issuesWithoutANewApproval() {
        long pricingRequestId = approvedOneItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);

        CustomerQuotationDto atPriceA = discountItem(draft.id(), item.id(), new BigDecimal("1.00"));
        BigDecimal priceA = itemById(atPriceA, item.id()).finalUnitPrice();
        discountApprovalService.approve(currentApprovalFor(atPriceA.id(), item.id()).id(), ceoActor);

        // Move to price B, get REJECTED.
        discountItem(draft.id(), item.id(), new BigDecimal("2.00"));
        DiscountApprovalDto pendingB = currentApprovalFor(draft.id(), item.id());
        discountApprovalService.reject(pendingB.id(), new RejectDiscountApprovalRequest("ไม่อนุมัติ"), ceoActor);

        // Move BACK to price A exactly.
        CustomerQuotationDto backToA = discountItem(draft.id(), item.id(), new BigDecimal("1.00"));
        assertThat(itemById(backToA, item.id()).finalUnitPrice()).isEqualByComparingTo(priceA);

        DiscountApprovalDto currentStatus = currentApprovalFor(backToA.id(), item.id());
        assertThat(currentStatus.status()).isEqualTo("APPROVED");
        assertThat(currentStatus.requestedFinalUnitPrice()).isEqualByComparingTo(priceA);

        CustomerQuotationDto issued = quotationService.issue(backToA.id(), new IssueCustomerQuotationRequest(null), salesActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authorization — wrong-way-round (real DB, real service — not mocked)
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    @Test
    void listPending_isCeoOnly() {
        long pricingRequestId = approvedOneItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        discountItem(draft.id(), draft.items().get(0).id(), new BigDecimal("1.00"));

        for (UserPrincipal refused : List.of(salesActor, salesManagerActor, accountActor, importActor)) {
            assertThatThrownBy(() -> discountApprovalService.listPending(refused))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(discountApprovalService.listPending(ceoActor)).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Rejection: mandatory reason, recorded, shown to Sales, quotation stays blocked
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void rejection_requiresAReason_recordsIt_leavesTheQuotationBlocked_andReRequestOpensAFreshRow() {
        long pricingRequestId = approvedOneItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        CustomerQuotationDto discounted = discountItem(draft.id(), item.id(), new BigDecimal("1.00"));
        DiscountApprovalDto pending = currentApprovalFor(discounted.id(), item.id());

        // Reason is mandatory — null and blank both refused, nothing decided.
        assertThatThrownBy(() -> discountApprovalService.reject(pending.id(),
            new RejectDiscountApprovalRequest(null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> discountApprovalService.reject(pending.id(),
            new RejectDiscountApprovalRequest("   "), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(discountApprovals.findById(pending.id()).orElseThrow().status()).isEqualTo("PENDING");

        // A real reason succeeds and is recorded verbatim (trimmed).
        String reason = "ส่วนลดสูงเกินไปสำหรับลูกค้ารายนี้ ให้กลับไปคุยราคาใหม่";
        DiscountApprovalDto rejected = discountApprovalService.reject(pending.id(),
            new RejectDiscountApprovalRequest("  " + reason + "  "), ceoActor);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectionReason()).isEqualTo(reason);
        assertThat(rejected.approvedFinalUnitPrice()).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'DISCOUNT_REJECTED'
            """, Map.of(), Long.class)).isEqualTo(1L);
        // The requesting sales rep is told too, not only logged in the event trail.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE employee_id = :salesRepId AND type = 'DISCOUNT_REJECTED'
            """, Map.of("salesRepId", salesRepId), Long.class)).isEqualTo(1L);

        // Shown to Sales via the same read Sales already uses for this quotation.
        List<DiscountApprovalDto> salesView = discountApprovalService.listForQuotation(discounted.id(), salesActor);
        assertThat(salesView).anySatisfy(a -> {
            assertThat(a.quotationItemId()).isEqualTo(item.id());
            assertThat(a.status()).isEqualTo("REJECTED");
            assertThat(a.rejectionReason()).isEqualTo(reason);
        });

        // The quotation stays blocked: issue() still refuses at the rejected price.
        assertThatThrownBy(() -> quotationService.issue(discounted.id(), new IssueCustomerQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        // Sales revises the price and re-requests — a FRESH pending row opens (not the rejected
        // one silently reopened) at the new price.
        CustomerQuotationDto revised = discountItem(discounted.id(), item.id(), new BigDecimal("0.50"));
        DiscountApprovalDto reopened = currentApprovalFor(revised.id(), item.id());
        assertThat(reopened.id()).isNotEqualTo(pending.id());
        assertThat(reopened.status()).isEqualTo("PENDING");
        assertThat(reopened.requestedFinalUnitPrice()).isEqualByComparingTo(itemById(revised, item.id()).finalUnitPrice());
    }

    @Test
    void rejectingAlreadyDecidedRequest_conflicts() {
        long pricingRequestId = approvedOneItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto discounted = discountItem(draft.id(), draft.items().get(0).id(), new BigDecimal("1.00"));
        DiscountApprovalDto pending = currentApprovalFor(discounted.id(), draft.items().get(0).id());

        discountApprovalService.approve(pending.id(), ceoActor);

        assertThatThrownBy(() -> discountApprovalService.reject(pending.id(),
            new RejectDiscountApprovalRequest("สายไปแล้ว"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> discountApprovalService.approve(pending.id(), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Per-line granularity — owner's explicit choice over per-quotation
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approvalIsPerLine_approvingOneLineDoesNotApproveAnother() {
        long pricingRequestId = approvedTwoItemPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item1 = draft.items().get(0);
        CustomerQuotationItemDto item2 = draft.items().get(1);

        CustomerQuotationDto discounted = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item1.id(), null, null, new BigDecimal("1.00")),
                    new UpdateCustomerQuotationItemRequest(item2.id(), null, null, new BigDecimal("1.00")))),
            salesActor);

        DiscountApprovalDto pending1 = currentApprovalFor(discounted.id(), item1.id());
        DiscountApprovalDto pending2 = currentApprovalFor(discounted.id(), item2.id());
        assertThat(pending1.id()).isNotEqualTo(pending2.id());

        discountApprovalService.approve(pending1.id(), ceoActor);

        // Still refused — item2 is unapproved. The message names item2 specifically ("a clear
        // message naming the offending line", per the task brief).
        assertThatThrownBy(() -> quotationService.issue(discounted.id(), new IssueCustomerQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains(String.valueOf(item2.id()));
            });

        discountApprovalService.approve(pending2.id(), ceoActor);
        CustomerQuotationDto issued = quotationService.issue(discounted.id(), new IssueCustomerQuotationRequest(null), salesActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private CustomerQuotationDto discountItem(long quotationId, long itemId, BigDecimal discount) {
        return quotationService.update(quotationId, new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(itemId, null, null, discount))), salesActor);
    }

    /** The "current" per-line status a UI would show — see {@link
     * DiscountApprovalRepository#findCurrentByQuotationId}'s own doc for why this is
     * price-matched, not merely "the latest row". */
    private DiscountApprovalDto currentApprovalFor(long quotationId, long itemId) {
        return discountApprovalService.listForQuotation(quotationId, salesActor).stream()
            .filter(a -> a.quotationItemId() == itemId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no current discount approval for item " + itemId));
    }

    private CustomerQuotationItemDto itemById(CustomerQuotationDto quotation, long itemId) {
        return quotation.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    /** One-item deal driven to an APPROVED pricing decision at 20% margin — no explicit minimum
     * set anywhere, so Phase 1's auto-population (minimum = approved price) means ANY positive
     * discount is below minimum, exactly like {@code approvedPricingRequest} in
     * {@code CustomerQuotationIntegrationTest}. */
    private long approvedOneItemPricingRequest() {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId1, null, "SCG", "Tile DA1", "SCG Tile DA1", null, null, "60x60",
            "Factory Discount1", new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "discount approval test request", UUID.randomUUID().toString(),
            List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).get(0);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            response("REF-DA1", draft.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        return pricingRequestId;
    }

    /** Two-item variant of {@link #approvedOneItemPricingRequest} — for the per-line granularity
     * test, which needs two independently-approvable lines on the SAME quotation. */
    private long approvedTwoItemPricingRequest() {
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "discount approval two-item test request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile DA1", "Factory Discount1", catalogProductId1),
                pricingItem("Cotto", "Tile DA2", "Factory Discount2", catalogProductId2)));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : factoryQuoteService.generateDrafts(pricingRequestId, importActor)) {
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), draft.items().get(0).pricingRequestItemId()), importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        return pricingRequestId;
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, long productId
    ) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, new BigDecimal("10"), new BigDecimal("10"),
            "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
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
