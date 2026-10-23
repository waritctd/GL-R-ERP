package th.co.glr.hr.deposit;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
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
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;

/**
 * Change 1 (guard hardening plan): {@code DepositNoticeRepository.issue} must refuse a
 * concurrent/duplicate issue against a row that is no longer DRAFT, rather than silently re-minting
 * {@code doc_number} in place. Drives a deal through the REAL Steps 1-6 services (no shortcuts) —
 * same wiring and fixture-building approach as {@code
 * InventoryDeliveryFulfilmentIntegrationTest#fullChain_reserveStockAndCompleteDelivery_...} — up to
 * a successfully issued deposit notice, then exercises both the pre-existing service-level guard
 * ({@code DepositNoticeService.requireDraft}) and the new repository-level SQL guard directly.
 */
class DepositNoticeIssueGuardIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private DepositNoticeRepository depositNoticeRepository;
    private OrderConfirmationService orderConfirmation;

    private long salesRepId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    private static final String FACTORY = "Factory Guard A";

    @BeforeEach
    void wireStepsServicesAndCreateFactory() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-deposit-issue-guard-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        // Costing moved from Import to CEO: the landed-cost calculation is now a shared
        // LandedCostCalculator that FactoryQuoteService and PricingDecisionService both take,
        // and PricingCostingService no longer owns the repositories it used to compute from.
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes,
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc),
            new CatalogRepository(jdbc), formulaEngine);

        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);

        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย การ์ด", "sales-guard1@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า การ์ด", "import-guard1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร การ์ด", "ceo-guard1@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        insertFactory(FACTORY);
    }

    private void insertFactory(String name) {
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Change 1 tests
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_secondServiceCallOnAlreadyIssuedNoticeIsRefused_rowUnchanged() {
        // NEGATIVE (the one that matters): this currently passes via requireDraft — pins the
        // pre-existing rule, which is unaffected by the new repository predicate.
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();

        assertThatThrownBy(() -> depositNoticeService.issue(fixture.docId(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        DepositNoticeDto reread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(reread.docNumber()).isEqualTo(fixture.docNumber());
        assertThat(reread.status()).isEqualTo("ISSUED");
    }

    @Test
    void issue_repositoryLevelCallOnAlreadyIssuedRowRefusesWithoutRemintingDocNumber() {
        // NEGATIVE, repository-level: this is what proves the NEW guard. Calling the repository
        // directly bypasses requireDraft entirely — without "AND status = 'DRAFT'" in the UPDATE's
        // WHERE, this returns a fresh doc number and rewrites the row.
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();

        Optional<String> result = depositNoticeRepository.issue(fixture.docId(), salesRepId, "Someone Else");

        assertThat(result).isEmpty();
        DepositNoticeDto reread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(reread.docNumber()).isEqualTo(fixture.docNumber());
        assertThat(reread.status()).isEqualTo("ISSUED");
    }

    @Test
    void issue_secondDraftOnSameTicket_refusedUntilItsPercentAndAmountAreBillable_thenSupersedesTheFirst() {
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();

        // A stored percent outside whole 1-100 (a 0% ticket-route draft on a ไม่รับมัดจำ quotation,
        // a pre-09-29 free-typed 0.3330, an out-of-range 1.5) or a 0-baht amount takes no number.
        long secondDocId = depositNoticeRepository.createDraft(
            fixture.ticketId(), draftHeader(BigDecimal.ZERO), secondDraftItems());
        assertIssueRefusedAndNothingChanged(fixture, secondDocId,
            "ใบแจ้งรับมัดจำนี้มีเปอร์เซ็นต์มัดจำ 0% จึงออกเอกสารไม่ได้ (ต้องเป็นจำนวนเต็ม 1–100)");
        assertIssueRefusedAndNothingChanged(fixture,
            depositNoticeRepository.createDraft(fixture.ticketId(), draftHeader(new BigDecimal("0.3330")), secondDraftItems()),
            "ใบแจ้งรับมัดจำนี้มีเปอร์เซ็นต์มัดจำ 33.3% จึงออกเอกสารไม่ได้ (ต้องเป็นจำนวนเต็ม 1–100)");
        assertIssueRefusedAndNothingChanged(fixture,
            depositNoticeRepository.createDraft(fixture.ticketId(), draftHeader(new BigDecimal("1.5")), secondDraftItems()),
            "ใบแจ้งรับมัดจำนี้มีเปอร์เซ็นต์มัดจำ 150% จึงออกเอกสารไม่ได้ (ต้องเป็นจำนวนเต็ม 1–100)");
        assertIssueRefusedAndNothingChanged(fixture,
            depositNoticeRepository.createDraft(fixture.ticketId(), secondDraftHeader(), List.of(new DepositNoticeItemRequest(
                1, "Free item", new BigDecimal("2"), "แผ่น", BigDecimal.ZERO, null, BigDecimal.ZERO))),
            "ใบแจ้งรับมัดจำนี้มียอดมัดจำ 0 บาท จึงออกเอกสารไม่ได้");

        // POSITIVE control: once the percent is billable, the DRAFT -> ISSUED transition and the
        // "supersede older ISSUED versions" behaviour work with the repository predicate in place.
        depositNoticeService.update(secondDocId, secondDraftHeader(), salesActor);
        Optional<String> secondDocNumber = depositNoticeRepository.issue(secondDocId, salesRepId, "Sales");

        assertThat(secondDocNumber).isPresent();
        assertThat(secondDocNumber.get()).isNotEqualTo(fixture.docNumber());

        DepositNoticeDto firstReread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(firstReread.status()).isEqualTo("SUPERSEDED");
        assertThat(firstReread.docNumber()).isEqualTo(fixture.docNumber());

        DepositNoticeDto secondReread = depositNoticeRepository.findById(secondDocId).orElseThrow();
        assertThat(secondReread.status()).isEqualTo("ISSUED");
        assertThat(secondReread.docNumber()).isEqualTo(secondDocNumber.get());
    }

    // Owner ruling 2026-10-23: one deposit notice per deal. While one is ISSUED, neither create route
    // drafts another (re-creating re-seeded the % from the quotation and a later issue silently
    // superseded the customer's document). Re-versioning an issued notice is a separate change.
    @Test
    void createDraft_isRefusedOnBothRoutesWhileAnIssuedNoticeExists_noNewRow() {
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();
        String message = "ดีลนี้ออกใบแจ้งรับมัดจำเลขที่ " + fixture.docNumber() + " แล้ว จึงสร้างฉบับร่างใหม่ไม่ได้";

        assertThatThrownBy(() -> depositNoticeService.createDraft(fixture.ticketId(),
                new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo(message);
            });
        assertThatThrownBy(() -> orderConfirmation.createDepositNoticeFromQuotation(fixture.pricingRequestId(),
                new CreateDepositNoticeFromQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo(message);
            });

        assertThat(depositNoticeRepository.findByTicket(fixture.ticketId()))
            .extracting(DepositNoticeDto::id).containsExactly(fixture.docId());
    }

    // Owner ruling 2026-10-23: sales may change the deposit % on a DRAFT; the amount follows.
    @Test
    void update_onADraft_changesThePercentAndTheAmountFollows() {
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();
        long draftId = depositNoticeRepository.createDraft(
            fixture.ticketId(), secondDraftHeader(), secondDraftItems()); // 0.50 of 100.00

        DepositNoticeDto updated = depositNoticeService.update(draftId, new DepositNoticeDraftRequest(
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
            new BigDecimal("0.40"), List.of(), secondDraftItems()), salesActor);

        // secondDraftItems: net x qty sums to 75.02 (unit x qty 90.00, stored amounts 75.03, nets alone 43.34).
        assertThat(updated.depositPercent()).isEqualByComparingTo("0.40");
        assertThat(updated.subtotal()).isEqualByComparingTo("75.02");
        assertThat(updated.depositAmount()).isEqualByComparingTo("30.01");
        assertThat(updated.vatAmount()).isEqualByComparingTo("2.10");
        assertThat(updated.totalPayable()).isEqualByComparingTo("32.11");
        assertThat(depositNoticeService.preview(draftId, salesActor))
            .contains("ขอรับเงินมัดจำ (40%)")
            .contains("30.01 บาท");

        // A request without items (null, or an empty list) keeps the stored lines; the amount is
        // recomputed from them at the new percent.
        DepositNoticeDto nullItems = depositNoticeService.update(draftId, new DepositNoticeDraftRequest(
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
            new BigDecimal("0.30"), List.of(), null), salesActor);
        assertThat(nullItems.items()).isEqualTo(updated.items());
        assertThat(nullItems.subtotal()).isEqualByComparingTo("75.02");
        assertThat(nullItems.depositAmount()).isEqualByComparingTo("22.51");

        DepositNoticeDto emptyItems = depositNoticeService.update(draftId, new DepositNoticeDraftRequest(
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
            new BigDecimal("0.20"), List.of(), List.of()), salesActor);
        assertThat(emptyItems.items()).isEqualTo(updated.items());
        assertThat(emptyItems.subtotal()).isEqualByComparingTo("75.02");
        assertThat(emptyItems.depositAmount()).isEqualByComparingTo("15.00");
    }

    // Two tabs on one draft: both loaded the same updatedAt. A saves; B's save with that now-stale
    // stamp is refused and changes nothing; reloading (the fresh stamp) saves.
    @Test
    void update_withAStaleUpdatedAt_isRefused409_andTheFirstSaveStands() {
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();
        long draftId = depositNoticeRepository.createDraft(fixture.ticketId(), secondDraftHeader(), secondDraftItems());
        OffsetDateTime loaded = depositNoticeRepository.findById(draftId).orElseThrow().updatedAt();

        DepositNoticeDto savedByA = depositNoticeService.update(draftId, stampedRequest("0.40", secondDraftItems(), loaded), salesActor);

        List<DepositNoticeItemRequest> tabBItems = List.of(new DepositNoticeItemRequest(
            1, "Tab B item", new BigDecimal("1"), "แผ่น", new BigDecimal("999.00"), null, new BigDecimal("999.00")));
        assertThatThrownBy(() -> depositNoticeService.update(draftId, stampedRequest("0.30", tabBItems, loaded), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("ใบแจ้งรับมัดจำนี้ถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
            });
        DepositNoticeDto reread = depositNoticeRepository.findById(draftId).orElseThrow();
        assertThat(reread.depositPercent()).isEqualByComparingTo("0.40");
        assertThat(reread.depositAmount()).isEqualByComparingTo(savedByA.depositAmount());
        assertThat(reread.items()).isEqualTo(savedByA.items());
        assertThat(reread.updatedAt()).isEqualTo(savedByA.updatedAt());

        DepositNoticeDto reloaded = depositNoticeService.update(
            draftId, stampedRequest("0.30", tabBItems, savedByA.updatedAt()), salesActor);
        assertThat(reloaded.depositPercent()).isEqualByComparingTo("0.30");
        assertThat(reloaded.subtotal()).isEqualByComparingTo("999.00");
    }

    @Test
    void update_onAnIssuedNotice_refusesThePercentEditWith409_rowUnchanged() {
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();
        DepositNoticeDto before = depositNoticeRepository.findById(fixture.docId()).orElseThrow();

        assertThatThrownBy(() -> depositNoticeService.update(fixture.docId(), new DepositNoticeDraftRequest(
                "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
                new BigDecimal("0.40"), List.of(), secondDraftItems()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("ใบแจ้งรับมัดจำนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)");
            });

        // Repository level: a save that passed requireDraft before issue() committed reaches the
        // UPDATE with the row already ISSUED. It must refuse before touching the lines, which the
        // PDF/XLSX print.
        assertThatThrownBy(() -> depositNoticeRepository.update(fixture.docId(), new DepositNoticeDraftRequest(
                "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
                new BigDecimal("0.40"), List.of(), secondDraftItems()), before.items()))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("ใบแจ้งรับมัดจำนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)");
            });

        DepositNoticeDto reread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(reread.status()).isEqualTo("ISSUED");
        assertThat(reread.depositPercent()).isEqualByComparingTo(before.depositPercent());
        assertThat(reread.depositAmount()).isEqualByComparingTo(before.depositAmount());
        assertThat(reread.items()).isEqualTo(before.items());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers — mirrors InventoryDeliveryFulfilmentIntegrationTest's approach for
    // driving a deal to an issued deposit notice through the real Steps 1-6 services.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record IssuedNoticeFixture(long ticketId, long pricingRequestId, long docId, String docNumber) {}

    private DepositNoticeDraftRequest secondDraftHeader() {
        return draftHeader(new BigDecimal("0.50"));
    }

    private DepositNoticeDraftRequest draftHeader(BigDecimal depositPercent) {
        return new DepositNoticeDraftRequest(
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
            depositPercent, List.of(), null);
    }

    private DepositNoticeDraftRequest stampedRequest(String depositPercent, List<DepositNoticeItemRequest> items,
                                                     OffsetDateTime expectedUpdatedAt) {
        return new DepositNoticeDraftRequest("ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
            new BigDecimal(depositPercent), List.of(), items, expectedUpdatedAt);
    }

    // Fractional qty, net below unit: net x qty (49.995 + 25.025 = 75.02), unit x qty (90.00), the
    // per-line rounded amounts (50.00 + 25.03 = 75.03) and the nets alone (43.34) all differ.
    private List<DepositNoticeItemRequest> secondDraftItems() {
        return List.of(
            new DepositNoticeItemRequest(1, "Second draft item A", new BigDecimal("1.5"), "แผ่น",
                new BigDecimal("40.00"), "ส่วนลด 6.67 ต่อหน่วย", new BigDecimal("33.33")),
            new DepositNoticeItemRequest(2, "Second draft item B", new BigDecimal("2.5"), "แผ่น",
                new BigDecimal("12.00"), "ส่วนลด 1.99 ต่อหน่วย", new BigDecimal("10.01")));
    }

    private void assertIssueRefusedAndNothingChanged(IssuedNoticeFixture fixture, long draftId, String message) {
        assertThatThrownBy(() -> depositNoticeService.issue(draftId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo(message);
            });
        DepositNoticeDto draft = depositNoticeRepository.findById(draftId).orElseThrow();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.docNumber()).isNull();
        DepositNoticeDto first = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(first.status()).isEqualTo("ISSUED");
        assertThat(first.docNumber()).isEqualTo(fixture.docNumber());
    }

    private IssuedNoticeFixture buildTicketWithIssuedDepositNotice() {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-GUARD1-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Guard1 " + UUID.randomUUID() + " จำกัด", "0100000000019", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0019");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Guard1");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Guard1", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Guard1", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        BigDecimal quantity = new BigDecimal("10");
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a
        // quantity are now required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead. roundToFullBox=false +
        // piecesInput=quantity keeps the derived requestedQty byte-identical to `quantity`.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile Guard1", "SCG Tile Guard1",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "deposit-notice guard walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, quantity);

        orderConfirmation.confirmOrder(pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draft = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issued = depositNoticeService.issue(draft.id(), salesActor);

        return new IssuedNoticeFixture(ticketId, pricingRequestId, issued.id(), issued.docNumber());
    }

    private void driveDraftPricingRequestToQuotationAccepted(long pricingRequestId, BigDecimal quantity) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        drainDispatches();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest(
            "REF-" + UUID.randomUUID(), "THB", "30 days", "45 days", "revision", "note",
            List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        // V141 ("CEO owns costing"): Import's last act is markReadyForCosting above. The three
        // costing write calls that used to sit here are severed (@Deprecated, 409) — startReview
        // now computes the landed cost itself when the CEO opens the review.
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = decision.items().stream()
            .map(decisionItem -> new UpdatePricingDecisionItemRequest(decisionItem.id(), null, new BigDecimal("1.00"), null, null, false))
            .toList();
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, updates), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        CustomerQuotationDto draftQuotation = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        CustomerQuotationDto issued = quotationService.issue(
            draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
    }

    private void drainDispatches() {
        // No-op now: FactoryQuoteService.send is synchronous (manual-RFQ redesign) --
        // there is no dispatch/worker queue left to drain. Kept (rather than removing
        // every call site) so this helper's callers do not all need to be revisited
        // individually.
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
