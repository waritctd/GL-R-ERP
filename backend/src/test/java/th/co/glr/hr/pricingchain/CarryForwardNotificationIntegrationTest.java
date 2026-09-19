package th.co.glr.hr.pricingchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
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
import th.co.glr.hr.customerquotation.DiscountApprovalRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesMailRecipientRepository;
import th.co.glr.hr.notification.SalesNotificationMailRouter;
import th.co.glr.hr.pricing.FxRateRepository;
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
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * GLA-32 (owner rulings, GLA-110 Q2 2026-09-18): what a factory-quote carry-forward submit does and
 * does NOT notify, observed through the REAL {@link SalesNotificationMailRouter} chain rather than
 * {@code SalesNotificationMailer.NO_OP}.
 *
 * <p><b>Why this file exists alongside two others that look similar.</b>
 * {@code SalesNotificationEmailRollbackIntegrationTest} already pins the ORDINARY submit path (a
 * request that goes SUBMITTED -> Import) end to end with the real router — see
 * {@code aCommittedPricingRequestSubmissionEmailsImportAndTheCeo}, which asserts the mail lands on
 * exactly {@code import@glr.co.th} and {@code rarm@glr.co.th}, and
 * {@code PricingRequestFlowIntegrationTest#submittingOneRequest_leavesTheOtherUntouchedAndDoesNotMoveTheDeal},
 * which asserts the in-app {@code hr.notification} rows for that same path. Neither test drives a
 * carry-forward submit. {@code ReissueThroughCeoChainIntegrationTest} DOES drive carry-forward
 * submits, repeatedly, but wires {@code NotificationRepository} with
 * {@code SalesNotificationMailer.NO_OP} — a harness that cannot see mail at all, and never asserts
 * an {@code hr.notification} row either; its own {@code
 * carryForward_recordsTheAdvanceAsSubmittedToReadyForCeoReview_withNoImportPickup} test only pins
 * the {@code sales.pricing_request_event} audit trail. This file is the missing intersection: a
 * real carry-forward submit, through the real mail router.
 *
 * <p><b>What the code actually does on a carry-forward submit</b> (read from
 * {@code PricingRequestService.submit} and {@code #carryFactoryQuotesForwardOnSubmit}): the ordinary
 * {@code notifyByRoleForPricingRequest("import", ...)} call sits AFTER the
 * {@code carryFactoryQuotesForwardOnSubmit(...)} early-return, so it is skipped entirely on this
 * path — Import gets neither an in-app row nor mail. The CEO is still told, but through a
 * DIFFERENT call than the ordinary path uses: {@code carryFactoryQuotesForwardOnSubmit} raises its
 * own {@code notifyCeo(..., PRICING_COSTING_SUBMITTED, ...)} rather than falling through to the
 * ordinary path's {@code notifyCeo(..., PRICING_REQUEST_SUBMITTED, ...)}. Both resolve to the same
 * {@link th.co.glr.hr.notification.CeoApproverRule} in-app fan-out and the same hardcoded
 * {@code rarm@glr.co.th} mailbox, so the CEO-side behaviour observed here is: one
 * {@code hr.notification} row, one email, to the hardcoded CEO mailbox.
 *
 * <p>Assertions are baseline-delta rather than absolute counts, because the fixture that reaches a
 * carry-forward-eligible child (create -> submit -> pickup -> factory quote -> CEO decision ->
 * issue -> customer-change revision) itself raises several notifications and emails of its own —
 * asserting a delta of exactly what the CHILD's submit call adds is what isolates the behaviour
 * under test from the fixture's own noise, without resorting to a raw {@code DELETE} against
 * {@code hr.notification}.
 */
class CarryForwardNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FACTORY = "Factory Carry-Forward Mail";

    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private CapturingMailer mailer;

    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long ceoEmployeeId;

    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireRealChainWithRealMailRouter() {
        TicketRepository tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        mailer = new CapturingMailer();
        NotificationEmailService emailService = new NotificationEmailService(
            mailer, new BrandAssets(), "", "", "https://portal.test.glr");
        // The real router, not SalesNotificationMailer.NO_OP -- a NO_OP harness cannot see mail at
        // all, which is exactly the gap this file exists to close relative to
        // ReissueThroughCeoChainIntegrationTest.
        NotificationRepository notifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(emailService, new SalesMailRecipientRepository(jdbc)));

        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-carry-forward-mail-test-uploads");

        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCosts = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCosts);

        PricingDecisionRepository decisions = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisions, pricingRequests,
            new th.co.glr.hr.pricingcosting.PricingCostingRepository(jdbc), tickets, fxRates, notifications,
            landedCosts, formulaEngine);

        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));
        quotationService = new CustomerQuotationService(new CustomerQuotationRepository(jdbc), pricingRequests,
            decisions, tickets, ticketService, customers, new QuotationRenderer(), notifications,
            new DiscountApprovalRepository(jdbc));

        salesActor = actor(createEmployee(employees, "พนักงานขาย เมล์", "sales-cf-mail@glr.co.th", "SALES", "แผนกขาย"), "sales");
        importActor = actor(createEmployee(employees, "ฝ่ายนำเข้า เมล์", "import-cf-mail@glr.co.th", "PCIM", "ฝ่ายนำเข้า"), "import");
        ceoEmployeeId = createManagingDirector(employees, "ผู้บริหาร เมล์", "ceo-cf-mail@glr.co.th");
        ceoActor = actor(ceoEmployeeId, "ceo");

        // A real, non-all-zero price_calc_config row (seeded by V26 for Thailand-equivalent
        // origins), same as ReissueThroughCeoChainIntegrationTest -- the carry-forward's own gate is
        // LandedCostCalculator.isFullyResolvable, so the landed cost here must be real arithmetic.
        catalogProductId = insertCatalogProduct(FACTORY, "IT", "TEST-CF-MAIL-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Carry-Forward Mail Test จำกัด", "0100000000031", "31 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0031");
        ProjectDto project = projects.create(customer.id(), "โครงการ Carry-Forward Mail Test");
        TicketDto created = ticketService.create(new CreateTicketRequest(
            "ดีล Carry-Forward Mail Test", "NORMAL", customer.name(), customer.id(), project.id(), null, null, null,
            List.of(ticketItem())), salesActor);
        ticketId = created.summary().id();
    }

    /**
     * <b>The behaviour GLA-32/GLA-110 asks to have pinned.</b> A commercial-only revision (same
     * items, same quantities) submitted from a parent that already has an ISSUED quotation carries
     * the parent's factory quote forward and jumps straight to READY_FOR_CEO_REVIEW -- see
     * {@code PricingRequestService#carryFactoryQuotesForwardOnSubmit}. On that path:
     *
     * <ul>
     *   <li>Import receives NOTHING -- no {@code hr.notification} row, no mail to
     *       {@code import@glr.co.th}. The ordinary {@code notifyByRoleForPricingRequest("import", ...)}
     *       call in {@code submit} sits after the carry-forward early-return, so it is never
     *       reached.</li>
     *   <li>The CEO IS still notified -- one {@code hr.notification} row and one email to the
     *       hardcoded {@code rarm@glr.co.th} mailbox, raised by
     *       {@code carryFactoryQuotesForwardOnSubmit}'s own {@code notifyCeo} call.</li>
     * </ul>
     *
     * <p>Driven through a real {@code @Transactional} proxy and committed via
     * {@link #transactionTemplate}, exactly like
     * {@code SalesNotificationEmailRollbackIntegrationTest#aCommittedPricingRequestSubmissionEmailsImportAndTheCeo}
     * -- {@code AfterCommit} itself runs the action immediately when no transaction is synchronising,
     * but committing for real is what actually exercises the after-commit deferral path production
     * relies on, rather than merely relying on the "no transaction" fallback.
     */
    @Test
    void carryForwardSubmit_notifiesNoImport_butStillNotifiesTheCeo() {
        long parentId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(parentId);

        PricingRequestDetailDto child = pricingRequestService.createCustomerChangeRevision(
            parentId, revisionRequest(new BigDecimal("10")), salesActor);
        long childId = child.summary().id();

        // Baseline AFTER the fixture (parent submit/pickup/factory-quote/CEO-decision/issue/revision
        // all raise their own notifications and mail), so the deltas below are about ONLY the
        // child's carry-forward submit.
        long importRowsBefore = importDivisionNotificationCount();
        long ceoRowsBefore = notificationCountFor(ceoEmployeeId);
        int mailSentBefore = mailer.sent().size();

        transactionTemplate.execute(status ->
            transactional(pricingRequestService).submit(childId, salesActor));

        assertThat(pricingRequestService.get(childId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // Wrong-way-round: Import gets NO new row and NO new mail from this submit.
        assertThat(importDivisionNotificationCount()).isEqualTo(importRowsBefore);
        assertThat(mailer.sent().subList(mailSentBefore, mailer.sent().size()))
            .as("a carry-forward submit must not mail the shared import box")
            .doesNotContain("import@glr.co.th");

        // The CEO still hears about it -- exactly one new row, exactly one new mail, to the
        // hardcoded CEO mailbox.
        assertThat(notificationCountFor(ceoEmployeeId)).isEqualTo(ceoRowsBefore + 1);
        assertThat(mailer.sent().subList(mailSentBefore, mailer.sent().size()))
            .as("a carry-forward submit must still mail the CEO, on the hardcoded rarm@glr.co.th mailbox")
            .containsExactly("rarm@glr.co.th");
    }

    // ── fixtures — every precondition driven through the real services, as ReissueThroughCeoChain does ──

    private long importDivisionNotificationCount() {
        return jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification n
              JOIN hr.employee e ON e.employee_id = n.employee_id
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE d.source_code ILIKE 'PCIM%'
            """, Map.of(), Long.class);
    }

    private long notificationCountFor(long employeeId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.notification WHERE employee_id = :id",
            Map.of("id", employeeId), Long.class);
    }

    /** Drives a fresh single-item pricing request from DRAFT to APPROVED_FOR_QUOTATION. */
    private long approvedPricingRequest(BigDecimal quantity) {
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "carry-forward mail walk", UUID.randomUUID().toString(),
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

    private PricingRequestRequests.CustomerChangeRevisionRequest revisionRequest(BigDecimal quantity) {
        return new PricingRequestRequests.CustomerChangeRevisionRequest(
            "ลูกค้าขอเปลี่ยนเงื่อนไข", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), null, "THB", "commercial-only revision",
            List.of(pricingItem(quantity)));
    }

    // Rebased onto develop @84f4123e (V184): V185/GLA-125 made color/texture/thicknessMm/
    // sqmPerPiece/piecesPerBox/a quantity/originCountry/leadTimeMin+MaxDays unconditionally
    // required on every item (PricingRequestService#requireItemFieldsComplete) -- the old
    // 18-arg compat shape this used to build (requestedQty/requestedQtySqm/requestedUnit/
    // requestedUnitBasis sent directly, no tile fields at all) now 400s with "ขาด สี, ผิว,
    // ความหนา, ...". Rebuilt as a complete new-form item: PIECES mode with wastageMode NONE and
    // roundToFullBox false so the derived requestedQty comes out EXACTLY equal to `quantity`
    // (piecesFinal == piecesInput, no wastage/box rounding) -- preserving this fixture's original
    // "requestedQty/quotedQuantity both equal `quantity`" arithmetic, which the parent/child
    // FactoryQuoteCarryForward equality check and the factory-response quoted amount both rely on.
    private PricingRequestRequests.PricingRequestItemRequest pricingItem(BigDecimal quantity) {
        return new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId, null, "SCG", "Tile CF Mail", "SCG Tile CF Mail", "ขาว", "ด้าน",
            "60x60", FACTORY, null, null, null, null, QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), "PIECES", null, quantity.intValueExact(),
            "NONE", null, 4, null, false, "ไทย-สต็อก", 3, 7, null, null, null, null);
    }

    private ReceiveFactoryQuoteRequest factoryResponse(long pricingRequestItemId, BigDecimal quantity) {
        return new ReceiveFactoryQuoteRequest("REF-CF-MAIL-" + UUID.randomUUID(), "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile CF Mail", "White", "Matte", "60x60", FACTORY,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** Matches CeoApproverRule.SQL_PREDICATE: กรรมการผู้จัดการ position, so the hr.notification
     *  fan-out for role "ceo" actually reaches this employee -- same fixture as
     *  SalesNotificationEmailRollbackIntegrationTest#createManagingDirector. */
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

    /** Same double as SalesNotificationEmailRollbackIntegrationTest -- recipients only here. */
    private static final class CapturingMailer implements Mailer {
        private final List<String> sent = new ArrayList<>();

        List<String> sent() {
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
