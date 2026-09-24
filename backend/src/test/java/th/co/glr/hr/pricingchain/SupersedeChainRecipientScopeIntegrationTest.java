package th.co.glr.hr.pricingchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
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
import th.co.glr.hr.dealquotation.WastageCalculator;
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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
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
 * GLA-124: {@code CustomerQuotationRepository#supersedeSupersededChainQuotations} used to key its
 * chain-wide sweep on {@code root_pricing_request_id} alone, with no {@code recipient_type}
 * clause. A revision chain is only ever supposed to hold ONE recipient — PR #999 closed the two
 * write paths that could put a mismatched recipient into a chain ({@code
 * PricingRequestRepository#createCustomerChangeRevision}, {@code updateDraft}) — but the sweep
 * itself had no way to know that invariant held for every row it might touch. A chain that DOES
 * contain two recipients (legacy pre-guard production data, or any future write path that
 * reintroduces the gap) would have the OTHER recipient's still-live, customer-held ISSUED
 * quotation silently flipped to SUPERSEDED the moment this recipient's quotation issues.
 *
 * <p>Driven end to end through the real {@link PricingRequestService}/{@link
 * CustomerQuotationService} against real Postgres, exactly like {@code
 * ReissueThroughCeoChainIntegrationTest} (whose fixture wiring this class mirrors) — a mocked
 * repository would pass while the SQL does something else, and the bug lives entirely in SQL.
 *
 * <p>The mismatched-recipient chain member is seeded with raw SQL rather than through the
 * service, on purpose: PR #999's guards now refuse to create one through {@code
 * createCustomerChangeRevision}/{@code updateDraft}, so raw SQL is the only way left to reproduce
 * the legacy pre-guard shape this test defends against. This is not a gap in the guard being
 * tested around — it is the fixture standing in for rows that already exist in production from
 * before PR #999 merged.
 */
class SupersedeChainRecipientScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FACTORY = "Factory GLA-124";

    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationRepository quotationRepository;
    private CustomerQuotationService quotationService;

    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long salesRepId;

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
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-supersede-scope-test-uploads");

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
            new PricingCostingRepository(jdbc), tickets, fxRates, notifications, landedCosts, formulaEngine);

        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));
        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisions, tickets,
            ticketService, customers, new QuotationRenderer(), notifications, new DiscountApprovalRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย GLA124", "sales-gla124@glr.co.th", "SALES", "แผนกขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(createEmployee(employees, "ฝ่ายนำเข้า GLA124", "import-gla124@glr.co.th", "PCIM", "ฝ่ายนำเข้า"), "import");
        ceoActor = actor(createEmployee(employees, "ผู้บริหาร GLA124", "ceo-gla124@glr.co.th", "MD", "ผู้บริหาร"), "ceo");

        catalogProductId = insertCatalogProduct(FACTORY, "IT", "TEST-GLA124-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท GLA-124 จำกัด", "0100000000099", "99 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0099");
        ProjectDto project = projects.create(customer.id(), "โครงการ GLA-124");
        TicketDto created = ticketService.create(new CreateTicketRequest(
            "ดีล GLA-124", "NORMAL", customer.name(), customer.id(), project.id(), null, null, null,
            List.of(ticketItem())), salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The fix, both directions from ONE mixed-recipient chain and ONE issue() call
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The bug, wrong-way-round: a chain that contains a mismatched-recipient (OWNER) member
     * alongside the DESIGNER chain being revised must NOT have that other recipient's ISSUED
     * quotation swept up just because it shares a chain root and is itself SUPERSEDED. This is
     * the assertion that matters — see the class javadoc for why the mismatch can exist at all.
     */
    @Test
    void issuingASecondRecipientsQuotation_leavesAnotherRecipientsIssuedQuotationInTheSameChainAlone() {
        MixedRecipientChain chain = buildMixedRecipientChainAndIssueTheDesignerReplacement();

        assertThat(docStatus(chain.mismatchedRecipientQuotationId())).isEqualTo("ISSUED");
    }

    /**
     * The positive case, in the SAME fixture: the fix must not over-scope. The DESIGNER chain's
     * own earlier ISSUED quotation — same recipient as the request that just issued — is still
     * exactly the row {@code supersedeSupersededChainQuotations} exists to retire, and must still
     * go to SUPERSEDED. A fix that silently stopped ALL chain supersession (e.g. an always-false
     * predicate) would pass the test above and fail this one.
     */
    @Test
    void issuingASecondRecipientsQuotation_stillSupersedesAnEarlierSameRecipientRevisionInTheChain() {
        MixedRecipientChain chain = buildMixedRecipientChainAndIssueTheDesignerReplacement();

        assertThat(docStatus(chain.sameRecipientParentQuotationId())).isEqualTo("SUPERSEDED");
    }

    private record MixedRecipientChain(long sameRecipientParentQuotationId, long mismatchedRecipientQuotationId) {}

    /**
     * Builds one revision chain with two recipients and drives the second, DESIGNER-recipient
     * quotation all the way to ISSUED through the real service path — the same call
     * ({@code CustomerQuotationService#issue} -> {@code
     * CustomerQuotationRepository#supersedeSupersededChainQuotations}) production uses.
     *
     * <ol>
     *   <li>Root pricing request A, recipient DESIGNER, driven to APPROVED_FOR_QUOTATION and
     *       issued as quotation QA (ISSUED).</li>
     *   <li>A mismatched chain member C is inserted DIRECTLY (raw SQL, not through the service —
     *       see the class javadoc): recipient_type OWNER, same chain root as A, status
     *       SUPERSEDED — reproducing legacy pre-PR-#999 data where a chain acquired a second
     *       recipient. Its own quotation QC is ISSUED, exactly the live customer-held document
     *       the bug would retire.</li>
     *   <li>A LEGITIMATE same-recipient (DESIGNER) customer-change revision D is created from A
     *       through the real service — this is the path PR #999 still allows, and it is what
     *       puts A's own status at SUPERSEDED (a precondition for QA to be swept at all).</li>
     *   <li>D is driven to APPROVED_FOR_QUOTATION and its quotation is issued — the call under
     *       test.</li>
     * </ol>
     */
    private MixedRecipientChain buildMixedRecipientChainAndIssueTheDesignerReplacement() {
        long designerRootId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto designerRootQuotation = issueQuotation(designerRootId);

        long mismatchedRecipientId = insertLegacyMismatchedRecipientChainMember(designerRootId);
        long mismatchedRecipientQuotationId = insertIssuedQuotationFor(mismatchedRecipientId, PricingRequestRecipient.OWNER);

        PricingRequestRequests.CustomerChangeRevisionRequest sameRecipientRevision =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "ลูกค้าขอเปลี่ยนจำนวน (GLA-124)", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
                "Designer Co.", LocalDate.now().plusDays(14), null, "THB", "same-recipient revision",
                List.of(pricingItem(new BigDecimal("25"))));
        long designerChildId = pricingRequestService.createCustomerChangeRevision(
            designerRootId, sameRecipientRevision, salesActor).summary().id();
        pricingRequestService.submit(designerChildId, salesActor);
        driveSubmittedRequestToApprovedForQuotation(designerChildId, new BigDecimal("25"));

        // The call under test: CustomerQuotationService#issue -> ...#supersedeSupersededChainQuotations.
        issueQuotation(designerChildId);

        return new MixedRecipientChain(designerRootQuotation.id(), mismatchedRecipientQuotationId);
    }

    /**
     * Raw-SQL fixture only (see class javadoc): PR #999 removed every service-level path that
     * could produce this row, so this reproduces the legacy shape directly. {@code revision_no}
     * 50 is picked to sit well clear of the root (1) and the legitimate same-recipient revision
     * this test also creates (2), so it cannot collide with {@code
     * uq_pricing_request_chain_revision} (V71: unique on {@code (COALESCE(root_pricing_request_id,
     * pricing_request_id), revision_no)}).
     */
    private long insertLegacyMismatchedRecipientChainMember(long chainRootId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request
                (request_code, ticket_id, recipient_type, recipient_label, status, requested_by,
                 root_pricing_request_id, revision_no, revision_reason)
            VALUES
                (:requestCode, :ticketId, :recipientType, :recipientLabel, 'SUPERSEDED', :requestedBy,
                 :rootId, 50, 'GLA-124 fixture: legacy pre-PR-#999 mismatched-recipient chain member')
            RETURNING pricing_request_id
            """,
            new MapSqlParameterSource()
                .addValue("requestCode", pricingRequests.nextRequestCode())
                .addValue("ticketId", ticketId)
                .addValue("recipientType", PricingRequestRecipient.OWNER)
                .addValue("recipientLabel", "Owner Co. (GLA-124 fixture)")
                .addValue("requestedBy", salesRepId)
                .addValue("rootId", chainRootId),
            Long.class);
    }

    /** Raw-SQL fixture companion to {@link #insertLegacyMismatchedRecipientChainMember}: the
     * ISSUED, customer-held quotation the bug would incorrectly retire. */
    private long insertIssuedQuotationFor(long pricingRequestId, String recipientType) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, currency, quotation_version, doc_status, recipient_type,
                 pricing_request_id, quotation_revision_no)
            VALUES
                (:ticketId, :number, :issuedBy, 'THB', 1, 'ISSUED', :recipientType, :pricingRequestId, 1)
            RETURNING quotation_id
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", quotationRepository.nextQuotationCode())
                .addValue("issuedBy", salesRepId)
                .addValue("recipientType", recipientType)
                .addValue("pricingRequestId", pricingRequestId),
            Long.class);
    }

    private String docStatus(long quotationId) {
        return jdbc.queryForObject(
            "SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), String.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixtures — mirrors ReissueThroughCeoChainIntegrationTest's own helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Drives a fresh single-item pricing request from DRAFT to APPROVED_FOR_QUOTATION. */
    private long approvedPricingRequest(BigDecimal quantity) {
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "supersede scope walk", UUID.randomUUID().toString(),
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

    // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a
    // quantity are now required on every item PricingRequestService#createDraft persists —
    // requestedQty/requestedUnit/requestedUnitBasis are derived instead. roundToFullBox=false +
    // piecesInput=quantity keeps the derived requestedQty byte-identical to `quantity`.
    private PricingRequestRequests.PricingRequestItemRequest pricingItem(BigDecimal quantity) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null, "SCG",
            "Tile GLA-124", "SCG Tile GLA-124", "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private ReceiveFactoryQuoteRequest factoryResponse(long pricingRequestItemId, BigDecimal quantity) {
        return new ReceiveFactoryQuoteRequest("REF-GLA124-" + UUID.randomUUID(), "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile GLA-124", "White", "Matte", "60x60", FACTORY,
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
