package th.co.glr.hr.pricingchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateRevisionRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
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
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
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
 * Evidence for the reissue-through-CEO-chain ruling (owner, 2026-08-13): <b>any change to an issued
 * customer quotation's prices or quantities goes through the full chain and ends in a fresh CEO
 * decision. Sales may not move a price on their own authority.</b>
 *
 * <p>Every test here is written the way the rule is stated — <b>wrong-way-round</b>, asserting the
 * paths that are now CLOSED, because "the happy path still works" is what a green suite proves
 * regardless of whether any guard exists. The four closed paths, one per work item:
 *
 * <ol>
 *   <li>{@code CustomerQuotationService.createRevision} + {@code update} cannot move a line price
 *       (the per-line discount is gone, and the follow-up {@code update} that would put it back
 *       409s). A price concession cannot survive a "typo fix".</li>
 *   <li>A customer-change revision from {@code QUOTATION_ACCEPTED} is refused — at the service with
 *       a 409, and at the repository with an {@code IllegalStateException} that the raw negative
 *       -guard {@code UPDATE} this change replaced could not produce.</li>
 *   <li>A revision that changes a QUANTITY never carries the parent's factory quote forward. Freight
 *       and clearance are quantity-banded and the quote carries a {@code minimumOrderQuantity}, so a
 *       carried-forward price across a quantity change is wrong, not merely stale.</li>
 *   <li>The parent's already-ISSUED quotation is NOT retired when the revision is created — the
 *       customer keeps a live offer while the replacement chain runs — and IS retired the moment the
 *       replacement is issued.</li>
 * </ol>
 *
 * <p>Driven end to end through the real services against real Postgres, never hand-rolled SQL:
 * a mocked repository "passes" while the SQL does something else, and three of the four rules here
 * live in SQL (the compare-and-set supersede, the quote copy, the chain-scoped quotation retire).
 * The only mocks are the two collaborators every other test in this package already mocks —
 * {@link FactoryEmailService} (sends real email) and {@link PriceCalcService} (unrelated legacy
 * quotation math).
 *
 * <p><b>No assertion here depends on a rollback.</b> {@link AbstractPostgresIntegrationTest} builds
 * no Spring context, so services are hand-wired with {@code new}, {@code @Transactional} is inert,
 * and a "the failed call undid its writes" assertion would be asserting a rollback that never
 * happens. Where this file asserts nothing changed after a rejected call, the call is rejected
 * BEFORE it writes anything — which is the property actually worth pinning.
 */
class ReissueThroughCeoChainIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FACTORY = "Factory Reissue-Chain";

    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;

    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireChainAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-reissue-chain-test-uploads");

        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        AppProperties dispatchProperties = new AppProperties();
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCosts = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCosts);

        PricingDecisionRepository decisions = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisions, pricingRequests,
            new th.co.glr.hr.pricingcosting.PricingCostingRepository(jdbc), tickets, fxRates, notifications,
            landedCosts, formulaEngine);

        TicketService ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);
        quotationService = new CustomerQuotationService(new CustomerQuotationRepository(jdbc), pricingRequests,
            decisions, tickets, ticketService, customers, new QuotationRenderer(), notifications);

        salesActor = actor(createEmployee(employees, "พนักงานขาย รีอิชชู", "sales-reissue@glr.co.th", "SALES", "แผนกขาย"), "sales");
        importActor = actor(createEmployee(employees, "ฝ่ายนำเข้า รีอิชชู", "import-reissue@glr.co.th", "PCIM", "ฝ่ายนำเข้า"), "import");
        ceoActor = actor(createEmployee(employees, "ผู้บริหาร รีอิชชู", "ceo-reissue@glr.co.th", "MD", "ผู้บริหาร"), "ceo");

        // Country 'Thailand' has a real, non-all-zero price_calc_config row (seeded by V26), so the
        // landed cost this chain computes is real arithmetic — which matters here, because the
        // carry-forward's own gate is LandedCostCalculator.isFullyResolvable.
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, 'factory-reissue@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", FACTORY));
        catalogProductId = insertCatalogProduct(FACTORY, "TH", "TEST-REISSUE-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Reissue Test จำกัด", "0100000000021", "21 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0021");
        ProjectDto project = projects.create(customer.id(), "โครงการ Reissue Test");
        TicketDto created = ticketService.create(new CreateTicketRequest(
            "ดีล Reissue Test", "NORMAL", customer.name(), customer.id(), project.id(), null, null, null,
            List.of(ticketItem())), salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Work item 1 — createRevision can no longer move a price
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The loophole itself, closed. Sales discounts the FIRST quotation (legitimate: Discount Policy
     * B, the CEO's own decision set the floor), issues it, then creates a correction revision. The
     * revision must come back at the CEO-APPROVED price with discount zero — the prior discount
     * does NOT ride along.
     *
     * <p>The assertion is deliberately the uncomfortable direction: the corrected quotation is
     * priced HIGHER than what the customer was last shown. That is the ruling, not a regression —
     * a price concession cannot be kept alive through a "fix the typo" revision.
     */
    @Test
    void createRevision_rebuildsEveryLineAtTheCeoApprovedPrice_soAPriorDiscountDoesNotSurvive() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, UUID.randomUUID().toString()), salesActor);
        CustomerQuotationItemDto line = draft.items().get(0);
        BigDecimal approvedPrice = line.approvedUnitPrice();

        CustomerQuotationDto discounted = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(line.id(), null, null, new BigDecimal("5.0000")))),
            salesActor);
        assertThat(discounted.items().get(0).finalUnitPrice())
            .isEqualByComparingTo(approvedPrice.subtract(new BigDecimal("5.0000")));

        CustomerQuotationDto issued = quotationService.issue(discounted.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);

        CustomerQuotationDto revision = quotationService.createRevision(issued.id(),
            new CreateRevisionRequest("แก้ไขที่อยู่ลูกค้าพิมพ์ผิด", UUID.randomUUID().toString()), salesActor);

        assertThat(revision.items()).hasSize(1);
        CustomerQuotationItemDto revisedLine = revision.items().get(0);
        assertThat(revisedLine.salesDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(revisedLine.finalUnitPrice()).isEqualByComparingTo(approvedPrice);
        // The visible, intended consequence, pinned rather than left implicit.
        assertThat(revisedLine.finalUnitPrice()).isGreaterThan(issued.items().get(0).finalUnitPrice());
    }

    /**
     * The other half of the same loophole. Removing {@code createRevision}'s carry-forward alone
     * would have closed nothing: the revision it produces is a DRAFT, and {@code update} is what
     * Sales would call next to put the discount straight back. Any non-zero discount on a REVISION
     * draft is now a 409 naming the path Sales must take instead.
     */
    @Test
    void updateOnARevisionDraft_withADiscount_is409_soSalesCannotMoveAPriceAfterIssue() {
        CustomerQuotationDto revision = issuedThenRevisedQuotation();
        long revisionItemId = revision.items().get(0).id();
        BigDecimal approvedPrice = revision.items().get(0).approvedUnitPrice();

        assertThatThrownBy(() -> quotationService.update(revision.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(revisionItemId, null, null, new BigDecimal("5.0000")))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("customer-change revision");
            });

        // Rejected before it wrote: the line is still at the CEO-approved price. Not a rollback
        // assertion — @Transactional is inert here (see this class's Javadoc) — the guard simply
        // throws before quotations.updateItems is reached.
        CustomerQuotationDto unchanged = quotationService.get(revision.id(), salesActor);
        assertThat(unchanged.items().get(0).salesDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(unchanged.items().get(0).finalUnitPrice()).isEqualByComparingTo(approvedPrice);
    }

    /**
     * The guard reads the RESOLVED discount, not {@code req.salesDiscount()} — so it cannot be
     * slipped past by omitting the field and letting a non-zero value already on the row survive
     * {@code update}'s "null = leave unchanged" merge. Seeded directly with SQL because
     * {@code createRevision} can no longer produce such a row; a pre-change row can still exist in
     * a real database, which is exactly why the guard is written this way.
     */
    @Test
    void updateOnARevisionDraft_omittingTheDiscountField_stillRejectsADiscountAlreadyOnTheRow() {
        CustomerQuotationDto revision = issuedThenRevisedQuotation();
        long revisionItemId = revision.items().get(0).id();
        jdbc.update("""
            UPDATE sales.quotation_item SET sales_discount = 5.0000 WHERE quotation_item_id = :id
            """, Map.of("id", revisionItemId));

        assertThatThrownBy(() -> quotationService.update(revision.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(revisionItemId, "แก้คำอธิบาย", null, null))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The boundary, asserted so the guard cannot quietly grow into "no quotation may ever be
     * discounted". Discount Policy B on a FIRST quotation is untouched: there the CEO's own
     * decision supplied the floor, so discounting down to it is authority the CEO granted. The
     * ruling is about moving a price AFTER the customer has been given one.
     */
    @Test
    void updateOnAFirstQuotationDraft_stillAllowsADiscount_soPolicyBIsUntouched() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, UUID.randomUUID().toString()), salesActor);
        CustomerQuotationItemDto line = draft.items().get(0);
        assertThat(draft.parentQuotationId()).isNull();

        CustomerQuotationDto updated = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(line.id(), null, null, new BigDecimal("5.0000")))),
            salesActor);

        assertThat(updated.items().get(0).salesDiscount()).isEqualByComparingTo("5.0000");
        assertThat(updated.items().get(0).finalUnitPrice())
            .isEqualByComparingTo(line.approvedUnitPrice().subtract(new BigDecimal("5.0000")));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Work item 2 — QUOTATION_ACCEPTED is terminal, and the repository now enforces it
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The capability this change REMOVES. Until now {@code supersedeForCustomerRevision} reached the
     * row with a raw {@code status <> 'SUPERSEDED' AND status <> 'CANCELLED'} UPDATE that never
     * consulted {@code PricingRequestStatus.ALLOWED}, so Sales could rewrite the pricing behind a
     * quotation the customer had already ACCEPTED — silently, with no CEO involvement.
     *
     * <p>Asserted at both levels, because they fail differently and a reviewer should see both: the
     * service 409s, and the row is untouched (no child pricing request was created either).
     */
    @Test
    void customerChangeRevision_fromQuotationAccepted_is409_andLeavesTheAcceptedDealAlone() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto issued = issueQuotation(pricingRequestId);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()),
            salesActor);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);

        assertThatThrownBy(() -> pricingRequestService.createCustomerChangeRevision(
            pricingRequestId, revisionRequest(new BigDecimal("10")), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ลูกค้ายอมรับใบเสนอราคาแล้ว");
            });

        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request WHERE parent_pricing_request_id = :id
            """, Map.of("id", pricingRequestId), Long.class)).isZero();
    }

    /**
     * The repository half of the same rule, reached directly so the state machine is proved to be
     * what enforces it rather than the service's own 409 sitting in front. The old raw UPDATE could
     * not produce this outcome at all: it would have returned rowcount 1 and superseded the row.
     */
    @Test
    void supersedeForCustomerRevision_fromQuotationAccepted_throwsAndWritesNothing() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto issued = issueQuotation(pricingRequestId);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor);

        assertThatThrownBy(() -> pricingRequests.supersedeForCustomerRevision(
            pricingRequestId, PricingRequestStatus.QUOTATION_ACCEPTED, pricingRequestId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(PricingRequestStatus.QUOTATION_ACCEPTED);

        assertThat(jdbc.queryForObject("""
            SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class)).isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
        assertThat(jdbc.queryForObject("""
            SELECT superseded_by_pricing_request_id FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), Long.class)).isNull();
    }

    /**
     * The compare-and-set the caller depends on for the lost-update race, tightened from a negative
     * guard to {@code WHERE status = :expected}. A concurrent writer that moved the row between the
     * service's read and this update must now yield rowcount 0 (which the service turns into a 409),
     * not silently supersede from a status the caller never saw.
     */
    @Test
    void supersedeForCustomerRevision_whenTheRowMovedUnderneath_returnsZeroInsteadOfSupersedingAnyway() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(pricingRequestId);
        // The caller read APPROVED_FOR_QUOTATION; by the time it writes, issue() has moved the row
        // on to QUOTATION_ISSUED. Both are legal SUPERSEDED predecessors, so this proves the
        // compare-and-set — not the transition assertion — is what refuses.
        int rows = pricingRequests.supersedeForCustomerRevision(
            pricingRequestId, PricingRequestStatus.APPROVED_FOR_QUOTATION, pricingRequestId);

        assertThat(rows).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class)).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Work item 3 — factory quotes carry forward ONLY when nothing cost-relevant changed
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The test that matters most in this file.</b> A quantity change must NEVER reuse the
     * parent's factory quote: freight and clearance rates are quantity-banded
     * ({@code qtyMinSqm}/{@code qtyMaxSqm}) and the quote carries a {@code minimumOrderQuantity} a
     * reduced order can fall below — so the carried price would not be merely stale, it would be
     * void. The revision must fall through to the ordinary SUBMITTED -> Import path.
     */
    @Test
    void revisionThatChangesQuantity_doesNotCarryTheFactoryQuoteForward_andWaitsForImport() {
        long parentId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(parentId);

        PricingRequestDetailDto child = pricingRequestService.createCustomerChangeRevision(
            parentId, revisionRequest(new BigDecimal("25")), salesActor);
        pricingRequestService.submit(child.summary().id(), salesActor);

        assertThat(pricingRequestService.get(child.summary().id(), salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(factoryQuoteCount(child.summary().id())).isZero();
        // And the parent's quotes were not disturbed on the way past.
        assertThat(readyForCostingQuoteCount(parentId)).isEqualTo(1L);
    }

    /**
     * The same refusal for a DIFFERENT reason: the item list itself changed shape. An added item has
     * no factory price at all, so a mapping derived from a fuzzy match is how a real price silently
     * lands on the wrong line. Item-count equality is a precondition of the mapping, not a nicety.
     */
    @Test
    void revisionThatAddsAnItem_doesNotCarryTheFactoryQuoteForward_andWaitsForImport() {
        long parentId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(parentId);

        PricingRequestRequests.CustomerChangeRevisionRequest twoItems = new PricingRequestRequests.CustomerChangeRevisionRequest(
            "ลูกค้าขอเพิ่มรายการ", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), null, "THB", "added item",
            List.of(pricingItem(new BigDecimal("10")), pricingItem(new BigDecimal("3"))));
        PricingRequestDetailDto child = pricingRequestService.createCustomerChangeRevision(parentId, twoItems, salesActor);
        pricingRequestService.submit(child.summary().id(), salesActor);

        assertThat(pricingRequestService.get(child.summary().id(), salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(factoryQuoteCount(child.summary().id())).isZero();
    }

    /**
     * The path the shortcut exists for: the customer haggles over price, products and quantities
     * untouched. Import is not made to re-ask the factory for prices nobody disputed, so the
     * parent's READY_FOR_COSTING quotes are copied and the child skips straight to the CEO.
     *
     * <p>Also pins the two things that make the copy safe to cost: the copied quote is a fresh root
     * ({@code revision_no = 1}, no parent/root pointer — the chain key
     * {@code uq_factory_quote_chain_revision} would otherwise collide with the parent's own
     * numbering), and its items point at the CHILD's request items, not the parent's.
     */
    @Test
    void commercialOnlyRevision_carriesTheFactoryQuoteForward_andLandsAtReadyForCeoReview() {
        long parentId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(parentId);

        PricingRequestDetailDto child = pricingRequestService.createCustomerChangeRevision(
            parentId, revisionRequest(new BigDecimal("10")), salesActor);
        long childId = child.summary().id();
        pricingRequestService.submit(childId, salesActor);

        assertThat(pricingRequestService.get(childId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(readyForCostingQuoteCount(childId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote
             WHERE pricing_request_id = :id AND revision_no = 1
               AND root_factory_quote_id IS NULL AND parent_factory_quote_id IS NULL
            """, Map.of("id", childId), Long.class)).isEqualTo(1L);
        // Every copied quote item points at one of the CHILD's request items — a dangling or
        // parent-pointing reference is exactly what would make the CEO cost the wrong line.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote_item fqi
              JOIN sales.factory_quote fq ON fq.factory_quote_id = fqi.factory_quote_id
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = fqi.pricing_request_item_id
             WHERE fq.pricing_request_id = :id AND pri.pricing_request_id <> :id
            """, Map.of("id", childId), Long.class)).isZero();

        // The point of landing at READY_FOR_CEO_REVIEW: the CEO can actually cost it. Anything less
        // and the shortcut has parked the request somewhere nobody can move it from.
        PricingDecisionDto decision = decisionService.startReview(childId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decision.items()).hasSize(1);
        assertThat(decision.items().get(0).frozenLandedCostPerPieceThb()).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * The audit trail the shortcut writes. Import never touched this request, so the events must say
     * so rather than implying a factory round-trip that did not happen — and the transition recorded
     * must be the real one (SUBMITTED -> READY_FOR_CEO_REVIEW), not a fabricated Import hop.
     */
    @Test
    void carryForward_recordsTheAdvanceAsSubmittedToReadyForCeoReview_withNoImportPickup() {
        long parentId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(parentId);
        PricingRequestDetailDto child = pricingRequestService.createCustomerChangeRevision(
            parentId, revisionRequest(new BigDecimal("10")), salesActor);
        long childId = child.summary().id();

        pricingRequestService.submit(childId, salesActor);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND from_status = 'SUBMITTED' AND to_status = 'READY_FOR_CEO_REVIEW'
            """, Map.of("id", childId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND to_status = 'IMPORT_REVIEWING'
            """, Map.of("id", childId), Long.class)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Work item 4 — the parent's offer stays live until the replacement is issued
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Owner ruling: the already-issued quotation stays ISSUED and valid while the replacement chain
     * runs. It used to be flipped to SUPERSEDED the instant a revision was created, which left the
     * customer holding no live offer for the whole time — and nothing to fall back to if the CEO
     * refused the new price.
     *
     * <p>Walks the full replacement chain so the retirement is observed at the moment it is supposed
     * to happen, not merely absent at the start: ISSUED through revision creation, through Import,
     * through the CEO's fresh decision, and SUPERSEDED only when the replacement quotation issues.
     * The parent's internal pricing DECISION is superseded eagerly by contrast — it is not the
     * customer-facing offer, and the issued quotation carries its own frozen price snapshot.
     */
    @Test
    void parentQuotationStaysIssuedWhileTheChainRuns_andRetiresWhenTheReplacementIssues() {
        long parentId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto parentQuotation = issueQuotation(parentId);

        PricingRequestDetailDto child = pricingRequestService.createCustomerChangeRevision(
            parentId, revisionRequest(new BigDecimal("25")), salesActor);
        long childId = child.summary().id();

        // Immediately after the revision is created: parent request SUPERSEDED, parent decision
        // SUPERSEDED, parent QUOTATION still live.
        assertThat(pricingRequestService.get(parentId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUPERSEDED);
        assertThat(jdbc.queryForObject("""
            SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id
            """, Map.of("id", parentId), String.class)).isEqualTo("SUPERSEDED");
        assertThat(quotationService.get(parentQuotation.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ISSUED);

        // Still live all the way through Import and the CEO's fresh decision. The quantity changed
        // (10 -> 25), so this revision takes the ordinary Import path rather than the
        // factory-quote carry-forward shortcut — see the carry-forward tests above.
        pricingRequestService.submit(childId, salesActor);
        assertThat(pricingRequestService.get(childId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        driveSubmittedRequestToApprovedForQuotation(childId, new BigDecimal("25"));
        assertThat(quotationService.get(parentQuotation.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ISSUED);

        // A DRAFT replacement is not a replacement — only a real issue retires the old offer.
        CustomerQuotationDto replacementDraft = quotationService.create(childId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, UUID.randomUUID().toString()), salesActor);
        assertThat(quotationService.get(parentQuotation.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ISSUED);

        CustomerQuotationDto replacement = quotationService.issue(replacementDraft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);

        assertThat(quotationService.get(parentQuotation.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.SUPERSEDED);
        assertThat(quotationService.get(replacement.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ISSUED);
    }

    /**
     * Scoping, wrong-way-round: issuing a first quotation must not reach across to some OTHER deal's
     * superseded chain. The chain-scoped UPDATE is keyed on
     * {@code COALESCE(root_pricing_request_id, pricing_request_id)}, and a missing or wrong key
     * there would show up as an unrelated customer's live offer silently disappearing — the kind of
     * damage nobody notices until the customer calls.
     */
    @Test
    void issuing_doesNotRetireAQuotationBelongingToAnUnrelatedDeal() {
        long unrelatedId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto unrelatedQuotation = issueQuotation(unrelatedId);
        // Make the unrelated deal's request SUPERSEDED, so ONLY the chain key stands between it and
        // the sweep: without that clause it matches every condition the UPDATE tests.
        pricingRequestService.createCustomerChangeRevision(
            unrelatedId, revisionRequest(new BigDecimal("25")), salesActor);
        assertThat(pricingRequestService.get(unrelatedId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUPERSEDED);

        long otherId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(otherId);

        assertThat(quotationService.get(unrelatedQuotation.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixtures — every precondition is driven through the real services, never SQL
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Drives a fresh single-item pricing request from DRAFT to APPROVED_FOR_QUOTATION. */
    private long approvedPricingRequest(BigDecimal quantity) {
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "reissue chain walk", UUID.randomUUID().toString(),
            List.of(pricingItem(quantity)));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        driveSubmittedRequestToApprovedForQuotation(pricingRequestId, quantity);
        return pricingRequestId;
    }

    /** Import pickup -> factory response -> ready for costing -> CEO decision approved. */
    private void driveSubmittedRequestToApprovedForQuotation(long pricingRequestId, BigDecimal quantity) {
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).get(0);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            factoryResponse(draft.items().get(0).pricingRequestItemId(), quantity), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        approveCeoDecision(pricingRequestId);
    }

    private void approveCeoDecision(long pricingRequestId) {
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            // A deliberately low floor: several tests here discount a FIRST quotation by 5.0000 to
            // prove Policy B still works, and a realistic floor would 422 that instead.
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

    /** An issued quotation plus the DRAFT correction revision made from it. */
    private CustomerQuotationDto issuedThenRevisedQuotation() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto issued = issueQuotation(pricingRequestId);
        CustomerQuotationDto revision = quotationService.createRevision(issued.id(),
            new CreateRevisionRequest("แก้ไขรายละเอียดที่ไม่เกี่ยวกับราคา", UUID.randomUUID().toString()), salesActor);
        assertThat(revision.parentQuotationId()).isEqualTo(issued.id());
        assertThat(revision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        return revision;
    }

    private PricingRequestRequests.CustomerChangeRevisionRequest revisionRequest(BigDecimal quantity) {
        return new PricingRequestRequests.CustomerChangeRevisionRequest(
            "ลูกค้าขอเปลี่ยนเงื่อนไข", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), null, "THB", "customer change revision",
            List.of(pricingItem(quantity)));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(BigDecimal quantity) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null, "SCG",
            "Tile Reissue", "SCG Tile Reissue", null, null, "60x60", FACTORY, quantity, quantity, "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
    }

    private ReceiveFactoryQuoteRequest factoryResponse(long pricingRequestItemId, BigDecimal quantity) {
        return new ReceiveFactoryQuoteRequest("REF-REISSUE-" + UUID.randomUUID(), "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private long factoryQuoteCount(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), Long.class);
    }

    private long readyForCostingQuoteCount(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote
             WHERE pricing_request_id = :id AND is_current = TRUE AND status = :status
            """, Map.of("id", pricingRequestId, "status", FactoryQuoteStatus.READY_FOR_COSTING), Long.class);
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile Reissue", "White", "Matte", "60x60", FACTORY,
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
