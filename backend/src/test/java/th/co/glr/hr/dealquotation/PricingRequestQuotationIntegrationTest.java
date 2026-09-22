package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
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
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
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
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for GLA-123 slice S1 (Phase 3 of the sales pricing redesign, 2026-09-19):
 * {@link DealQuotationService#createFromPricingRequest} — the direct-deal engine reused to create
 * a quotation from an APPROVED-FOR-QUOTATION pricing request's CEO-approved decision.
 *
 * <p>Setup mirrors {@code PricingDecisionCeoPriceModeIntegrationTest} (same fixtures, same helper
 * shapes) — duplicated rather than shared, to avoid coupling this new-feature suite's lifecycle to
 * an already-large existing file.
 */
class PricingRequestQuotationIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private PricingDecisionRepository decisionRepository;
    private th.co.glr.hr.customerquotation.CustomerQuotationRepository customerQuotationRepository;
    private th.co.glr.hr.customerquotation.CustomerQuotationService legacyQuotationService;
    private DealQuotationService quotationService;
    private DealQuotationRepository quotationRepository;
    private TicketRepository tickets;

    private long salesRepId;
    private long otherSalesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal accountActor;
    private long ticketId;
    private long contactId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pcr-quotation-s1-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage,
            factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc));

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
        quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        quotationService = new DealQuotationService(quotationRepository, tickets, customers,
            new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc),
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        customerQuotationRepository = new th.co.glr.hr.customerquotation.CustomerQuotationRepository(jdbc);
        quotationService.wirePricingRequestDependencies(pricingRequests, decisionRepository, customerQuotationRepository);
        // M2 (Opus review, 2026-09-20) fixture — the OLD create path, wired the same way
        // PricingDecisionCeoPriceModeIntegrationTest's own fixture does, purely so this file's M2
        // tests can put a live legacy quotation on a PR without hand-seeding its schema.
        legacyQuotationService = new th.co.glr.hr.customerquotation.CustomerQuotationService(
            customerQuotationRepository, pricingRequests, decisionRepository, tickets, ticketService,
            customers, new QuotationRenderer(), notifications,
            new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));
        legacyQuotationService.wireDealQuotationRepository(quotationRepository);

        salesRepId = createEmployee(employees, "พนักงานขาย S1", "sales-s1@glr.co.th", "SALES", "แผนกขาย");
        otherSalesRepId = createEmployee(employees, "พนักงานขายอื่น S1", "sales-s1-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า S1", "import-s1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร S1", "ceo-s1@glr.co.th", "MD", "ผู้บริหาร");
        long salesManagerId = createEmployee(employees, "ผจก.ขาย S1", "sm-s1@glr.co.th", "SALES", "ฝ่ายขาย");
        long accountId = createEmployee(employees, "บัญชี S1", "acct-s1@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        accountActor = actor(accountId, "account");

        catalogProductIdFactoryA = insertCatalogProduct("Factory A-S1", "IT", "TEST-A-S1-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B-S1", "IT", "TEST-B-S1-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท PCR Quotation S1 จำกัด", "0100000000199", "999 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0199");
        ProjectDto project = projects.create(customer.id(), "โครงการ PCR Quotation S1");
        contactId = new ContactRepository(jdbc).create(customer.id(), "สมชาย", "ทดสอบ",
            "ผู้จัดการฝ่ายจัดซื้อ", "contact-s1@example.com", "081-000-0000").id();
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล PCR Quotation S1", "NORMAL", customer.name(), customer.id(), project.id(),
                contactId, null, null, List.of(ticketItem("SCG", "Tile A-S1", "Factory A-S1"),
                    ticketItem("Cotto", "Tile B-S1", "Factory B-S1"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create + prefill fidelity
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void net_prefillsQuotation_netEqualsDecisionNet() {
        PricingDecisionDto decision = approvedDecision("NET",
            items -> List.of(discountItem(items.get(0).id(), new BigDecimal("10"))));
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        assertThat(quotation.origin()).isEqualTo("PRICING_REQUEST");
        assertThat(quotation.pricingRequestId()).isEqualTo(decision.pricingRequestId());
        assertThat(quotation.docStatus()).isEqualTo("DRAFT");
        assertThat(quotation.priceMode()).isEqualTo("NET");
        assertThat(quotation.items()).hasSize(decision.items().size());
        // The computed net on EVERY line equals ITS OWN decision item's net — ceoNetUnitPrice is
        // the exact value the repository joined in from THIS item's own pricing_decision_item.
        for (DealQuotationItemDto item : quotation.items()) {
            assertThat(item.ceoNetUnitPrice()).isNotNull();
            assertThat(item.netUnitPrice()).isEqualByComparingTo(item.ceoNetUnitPrice());
            assertThat(item.priceChangedFromCeo()).isFalse();
        }
        assertThat(quotation.priceModeChangedFromCeo()).isFalse();
    }

    /**
     * B1 (Opus review BLOCKER, 2026-09-20): a real-DB probe found that a NET line with an active
     * "ปรับราคาเอง" override (150, 10% discount) read {@code ceoListUnitPrice = 10137.01} (the
     * bare auto-formula {@code list_unit_price}) instead of 150 — so it showed as
     * {@code priceChangedFromCeo = true} the instant it was created, since the quotation's own
     * {@code unitPrice} (correctly prefilled as 150, per {@code buildItemInputFromDecisionItem})
     * never matched. Fixed by joining {@code COALESCE(manual_selling_price_per_requested_unit,
     * list_unit_price)} as {@code ceo_list_unit_price} — mirrors
     * {@code PricingDecisionService#effectiveListPrice} exactly.
     */
    @Test
    void net_withManualOverride_effectiveListPriceIsTheOverride_notTheAutoFormulaPrice() {
        PricingDecisionDto decision = approvedDecision("NET",
            items -> List.of(overrideItem(items.get(0).id(), new BigDecimal("150"), "ปรับราคาพิเศษ", new BigDecimal("10"))));
        // Sanity: the auto-formula price is a real, DIFFERENT number from the override (otherwise
        // this test could pass by coincidence) — see WastageCalculator's landed-cost-based
        // proposedSellingPricePerRequestedUnit, which this fixture's margin/cost inputs put well
        // above 150.
        assertThat(decision.items().get(0).listUnitPrice()).isNotEqualByComparingTo(new BigDecimal("150"));

        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto item = quotation.items().get(0);
        assertThat(item.ceoListUnitPrice()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(item.unitPrice()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(item.priceChangedFromCeo()).isFalse();

        DealQuotationDto changed = quotationService.update(quotation.id(),
            upsertWithChangedDiscount(quotation, item.id(), new BigDecimal("20")), salesActor);
        DealQuotationItemDto changedItem = itemById(changed, item.id());
        assertThat(changedItem.priceChangedFromCeo()).isTrue();
        assertThat(changedItem.ceoListUnitPrice()).isEqualByComparingTo(new BigDecimal("150"));

        DealQuotationDto reverted = quotationService.update(quotation.id(),
            upsertWithChangedDiscount(quotation, item.id(), new BigDecimal("10")), salesActor);
        assertThat(itemById(reverted, item.id()).priceChangedFromCeo()).isFalse();
    }

    @Test
    void specialSqm_prefillsQuotation_netEqualsDecisionNet() {
        PricingDecisionDto decision = approvedDecision("SPECIAL_SQM",
            items -> items.stream().map(i -> specialSqmItem(i.id(), new BigDecimal("1350"))).toList());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        assertThat(quotation.priceMode()).isEqualTo("SPECIAL_SQM");
        for (DealQuotationItemDto item : quotation.items()) {
            assertThat(item.netUnitPrice()).isEqualByComparingTo(item.ceoNetUnitPrice());
            assertThat(item.specialPriceSqm()).isNotNull();
            assertThat(item.priceChangedFromCeo()).isFalse();
        }
    }

    @Test
    void directNet_prefillsQuotation_netEqualsDecisionNet() {
        PricingDecisionDto decision = approvedDecision("DIRECT_NET",
            items -> items.stream().map(i -> directNetItem(i.id(), new BigDecimal("777.00"))).toList());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        assertThat(quotation.priceMode()).isEqualTo("DIRECT_NET");
        for (DealQuotationItemDto item : quotation.items()) {
            assertThat(item.netUnitPrice()).isEqualByComparingTo(item.ceoNetUnitPrice());
            assertThat(item.priceChangedFromCeo()).isFalse();
        }
    }

    @Test
    void headerTermsCarriedOverFromPricingRequest() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        // Fill header terms on the still-open decision's pricing request before it is approved.
        long prId = decision.pricingRequestId();
        jdbc.update("""
            UPDATE sales.pricing_request
               SET payment_term_mode = 'CREDIT', credit_days = 30, validity_days = 45,
                   dept_code = 'D1', unit_code = 'U1', note = 'หมายเหตุทดสอบ S1'
             WHERE pricing_request_id = :id
            """, Map.of("id", prId));

        DealQuotationDto quotation = quotationService.createFromPricingRequest(prId, salesActor);

        assertThat(quotation.creditDays()).isEqualTo(30);
        assertThat(quotation.validityDays()).isEqualTo(45);
        assertThat(quotation.deptCode()).isEqualTo("D1");
        assertThat(quotation.unitCode()).isEqualTo("U1");
        assertThat(quotation.customerNotes()).isEqualTo("หมายเหตุทดสอบ S1");
    }

    @Test
    void legacyDecision_refused409() {
        // A decision whose price_mode was never set (Phase 2 not exercised) — old-flow requests.
        long pricingRequestId = twoItemSubmittedCosting();
        decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        // Never calls decisionService.update(...) to set a price_mode — approve it as-is via the
        // legacy margin path (approve() requires positive selling prices, already auto-populated).
        PricingDecisionDto decision = decisionRepository.findByPricingRequest(pricingRequestId).get(0);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติแบบเดิม", UUID.randomUUID().toString()), ceoActor);

        assertThatThrownBy(() -> quotationService.createFromPricingRequest(pricingRequestId, salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    @Test
    void notApprovedForQuotation_refused409() {
        long pricingRequestId = twoItemSubmittedCosting();
        decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        // Decision started but never approved — pricing_request stays CEO_REVIEWING.
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(pricingRequestId, salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    @Test
    void duplicateCreate_returnsTheSameOpenDraft_doesNotInsertASecondRow() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto first = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationDto second = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        assertThat(second.id()).isEqualTo(first.id());
        // findByTicket is the DIRECT-deal-only list surface (deliberately origin = 'DEAL_DIRECT',
        // see directDealSearch_doesNotIncludePricingRequestOriginRows) — count the PRICING_REQUEST
        // row directly instead.
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id AND origin = 'PRICING_REQUEST'
            """, Map.of("id", ticketId), Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // AUTHZ, wrong-way-round, real DB. Mutation-checked below (see the *_mutationCheck test).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anotherSalesRep_cannotCreate_403_noRowInserted() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(decision.pricingRequestId(), otherSalesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    @Test
    void importRole_cannotCreate_403_noRowInserted() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(decision.pricingRequestId(), importActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    @Test
    void accountRole_cannotCreate_403_noRowInserted() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(decision.pricingRequestId(), accountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    /** Mirrors {@code CustomerQuotationService.create}'s own gate exactly — sales_manager is NOT
     * admitted (unlike {@code DealQuotationService#EDIT_ROLES}' broader allowance for editing an
     * EXISTING DEAL_DIRECT quotation). */
    @Test
    void salesManager_cannotCreate_403_noRowInserted() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(decision.pricingRequestId(), salesManagerActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    /**
     * Isolates the ROLE check from the OWNERSHIP check (coordinator follow-up, 2026-09-20): the
     * four wrong-way-round tests above are all "not the owner", so the ownership check alone
     * already explains every 403 there — mutation-checking the role gate by itself showed exactly
     * that (disabling ONLY the role check left every existing test green). This test makes the
     * actor GENUINELY the ticket's owner (created_by, updated directly — TicketService#create
     * itself is sales-only, so a non-sales owner cannot arise through the normal API) while
     * holding a non-sales role, so ONLY the role check can be what stops them.
     */
    @Test
    void ticketOwnerWithNonSalesRole_cannotCreate_403_noRowInserted() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        jdbc.update("UPDATE sales.ticket SET created_by = :ceoId WHERE ticket_id = :ticketId",
            Map.of("ceoId", ceoUserId, "ticketId", ticketId));
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(decision.pricingRequestId(), ceoActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    @Test
    void owningSalesRep_canCreate_mutationCheckControl() {
        // The positive control for the four wrong-way-round tests above: same fixture, the
        // OWNING rep succeeds. Mutation-checked by temporarily removing the ownership check in
        // DealQuotationService#createFromPricingRequest and confirming exactly the four
        // wrong-way-round tests above go red while this one and every other test in the class
        // stay green — see the PR body for the before/after run.
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(quotation.id()).isPositive();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Submit blocked (S2's job)
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** M6 fix: asserts the SPECIFIC guard message from {@code
     * DealQuotationService#requireStatusMachineEnabled}, not just the 409 status — a bare 409 could
     * also come from an unrelated guard (e.g. the missing-contact check right below it in {@code
     * submit}), so the message is what proves THIS guard is the one that actually fired.
     * Mutation-checked: with {@code requireStatusMachineEnabled}'s call removed from {@code submit},
     * this test alone goes red (the quotation instead advances past DRAFT, or a different guard's
     * message shows up) while the rest of the suite stays green. */
    @Test
    void submit_refusedForPricingRequestOrigin() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.submit(quotation.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> quotationService.submit(quotation.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasMessage("ยังไม่เปิดใช้งานการอนุมัติใบเสนอราคาจากคำขอราคา — จะเปิดใช้งานในระยะถัดไป");
        assertThat(quotationService.get(quotation.id(), salesActor).docStatus()).isEqualTo("DRAFT");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // findById-widening audit (coordinator follow-up, 2026-09-20): createRevision/createReorder
    // require APPROVED, which is itself unreachable for this origin in S1 (submit refuses first),
    // so both are ALREADY refused transitively — these tests pin that (and the explicit
    // #requireStatusMachineEnabled guard added as defence in depth) rather than leaving it as an
    // untested inference. cancel is the one status-machine method S1 deliberately WIDENS (owner
    // ruling: a DRAFT is safe to cancel, mirrors DEAL_DIRECT exactly).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createRevision_refusedForPricingRequestOrigin() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.createRevision(quotation.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createReorder_refusedForPricingRequestOrigin() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.createReorder(quotation.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    /** Owner ruling: cancelling a DRAFT is allowed for this origin, mirroring DEAL_DIRECT exactly
     * — see DealQuotationRepository#cancel's own comment. */
    @Test
    void cancel_allowedForPricingRequestOriginDraft() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationDto cancelled = quotationService.cancel(quotation.id(),
            new th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest("ทดสอบยกเลิก"), salesActor);
        assertThat(cancelled.docStatus()).isEqualTo("CANCELLED");
    }

    /** Coordinator follow-up: recipient_label was passed into InsertDraftParams by
     * #createFromPricingRequest but silently dropped (never bound in insertDraft's SQL) —
     * mirrors CustomerQuotationRepository#insertDraft, which has always carried it. */
    @Test
    void recipientLabel_carriedOverFromPricingRequestAtCreate() {
        long pricingRequestId = twoItemSubmittedCosting();
        jdbc.update("UPDATE sales.pricing_request SET recipient_label = :label WHERE pricing_request_id = :id",
            Map.of("label", "คุณสมชาย (ผู้ออกแบบ)", "id", pricingRequestId));
        PricingDecisionDto started = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        PricingDecisionDto afterMode = decisionService.update(started.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);
        decisionService.approve(afterMode.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        quotationService.createFromPricingRequest(pricingRequestId, salesActor);
        String recipientLabel = jdbc.queryForObject(
            "SELECT recipient_label FROM sales.quotation WHERE pricing_request_id = :id AND origin = 'PRICING_REQUEST'",
            Map.of("id", pricingRequestId), String.class);
        assertThat(recipientLabel).isEqualTo("คุณสมชาย (ผู้ออกแบบ)");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Sales MAY edit price (owner ruling revised 2026-09-19) — accepted + flagged, not refused.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void editingALinkedLinesPrice_isAccepted_andFlagged_andRevertingClearsIt() {
        PricingDecisionDto decision = approvedDecision("NET",
            items -> List.of(discountItem(items.get(0).id(), new BigDecimal("10"))));
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto original = quotation.items().get(0);
        BigDecimal ceoUnitPrice = original.unitPrice();
        BigDecimal ceoDiscount = original.discountPct();

        // Sales changes the discount to 20%.
        DealQuotationDto changed = quotationService.update(quotation.id(),
            upsertWithChangedDiscount(quotation, original.id(), new BigDecimal("20")), salesActor);
        DealQuotationItemDto changedItem = itemById(changed, original.id());
        assertThat(changedItem.priceChangedFromCeo()).isTrue();
        assertThat(changedItem.ceoDiscountPct()).isEqualByComparingTo(ceoDiscount);
        assertThat(changedItem.discountPct()).isEqualByComparingTo(new BigDecimal("20"));
        // The link survives the save — a second edit still compares against the SAME CEO original.
        assertThat(changedItem.ceoNetUnitPrice()).isNotNull();

        // Reverting to the CEO's exact original value clears the flag.
        DealQuotationDto reverted = quotationService.update(quotation.id(),
            upsertWithChangedDiscount(quotation, original.id(), ceoDiscount), salesActor);
        DealQuotationItemDto revertedItem = itemById(reverted, original.id());
        assertThat(revertedItem.priceChangedFromCeo()).isFalse();
        assertThat(revertedItem.unitPrice()).isEqualByComparingTo(ceoUnitPrice);
    }

    @Test
    void changingPriceMode_setsHeaderFlag() {
        PricingDecisionDto decision = approvedDecision("NET",
            items -> List.of(discountItem(items.get(0).id(), BigDecimal.ZERO)));
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(quotation.priceModeChangedFromCeo()).isFalse();

        // Switch the whole document to DIRECT_NET.
        List<ItemInput> directNetItems = quotation.items().stream()
            // DIRECT_NET still requires a positive "reference" unitPrice, same as every mode
            // (DealQuotationService#requirePriceValidForType has no mode exemption for a TH
            // document) — reuse the line's own current unitPrice rather than null.
            .map(item -> tileItemInput(item, item.unitPrice(), null, null, new BigDecimal("999.00")))
            .toList();
        DealQuotationDto switched = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, "DIRECT_NET", directNetItems), salesActor);
        assertThat(switched.priceModeChangedFromCeo()).isTrue();
        assertThat(switched.ceoPriceMode()).isEqualTo("NET");
        for (DealQuotationItemDto item : switched.items()) {
            assertThat(item.priceChangedFromCeo()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Extra rows refused (owner ruling 2026-09-19) — pin with a mutation-checkable test.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void addingAnExtraPlainRow_refused400() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        List<ItemInput> withExtra = new java.util.ArrayList<>();
        for (DealQuotationItemDto item : quotation.items()) {
            withExtra.add(existingTileInput(item));
        }
        withExtra.add(plainItemInput());
        assertThatThrownBy(() -> quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), withExtra), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        // Nothing was persisted — the quotation still has only its original TILE lines.
        assertThat(quotationService.get(quotation.id(), salesActor).items()).hasSize(quotation.items().size());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // M3 (Opus review, 2026-09-20) — origin-aware view gate. A real-DB probe found that
    // import AND account could both read a PRICING_REQUEST quotation's CEO discount/list via
    // GET /deal-quotations/{id} through DEAL_DIRECT's broader VIEW_ROLES (which includes
    // account) plus its quotation-grant bypass. Fixed in DealQuotationService#requireViewAccess
    // by branching on origin FIRST and using the narrower PRICING_REQUEST_VIEW_ROLES before
    // falling through to the DEAL_DIRECT rules. Covers every read path that funnels through
    // requireViewAccess: get, renderPdf/renderXlsx, getItemPicture, findForPricingRequest.
    //
    // MAJOR-4/MINOR-1 fix (owner ruling, confirmed 2026-09-20, second re-review): import was
    // ORIGINALLY admitted here (this comment used to say "mirrors CustomerQuotationService
    // .VIEW_ROLES exactly: sales/sales_manager/ceo/import"), on the theory that import already
    // sees the legacy customer quotation's own CEO price via that class's VIEW_ROLES. The owner's
    // standing rule is narrower than that theory: import must see NOTHING price- or
    // discount-shaped anywhere in this chain, on EITHER engine — the legacy side's inclusion of
    // import is its own, separate, unchanged fact this PR does not touch. PRICING_REQUEST_VIEW_ROLES
    // is now sales/sales_manager/ceo ONLY. See importRole_cannotGet_403 and its siblings below
    // (replacing the old importRole_canGet_mutationCheckControl, which asserted the opposite).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void accountRole_cannotGet_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.get(quotation.id(), accountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountRole_cannotRenderPdf_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.renderPdf(quotation.id(), accountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** account's quotation-grant does NOT bypass the origin-aware gate — the whole point of the
     * M3 fix, since DEAL_DIRECT's own {@code hasQuotationGrant} check would have let this same
     * actor straight through. Proves the fix branches on origin BEFORE the grant check, not just
     * that account lacks the grant by default. */
    @Test
    void accountRoleWithQuotationGrant_stillCannotGet_403() {
        UserPrincipal grantedAccountActor = grantedAccountActor("get");

        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.get(quotation.id(), grantedAccountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherSalesRep_cannotGet_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.get(quotation.id(), otherSalesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MAJOR-4/MINOR-1 (owner ruling, confirmed 2026-09-20) — import must not be able to open a
    // PRICING_REQUEST quotation at all: on this origin the document's OWN price fields ARE the
    // CEO's approved list/discount (createFromPricingRequest prefills them verbatim), so there is
    // no narrower cut the way account's exclusion has one. Covers get, render, the
    // findForPricingRequest list endpoint, and update — every path a probe could reach.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void importRole_cannotGet_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.get(quotation.id(), importActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void importRole_cannotRenderPdf_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.renderPdf(quotation.id(), importActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void importRole_cannotFindForPricingRequest_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.findForPricingRequest(decision.pricingRequestId(), importActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void importRole_cannotUpdate_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        List<ItemInput> payload = quotation.items().stream().map(this::existingTileInput).toList();
        assertThatThrownBy(() -> quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), importActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Positive control for the wrong-way-round tests above (account, granted-account, import):
     * ceo (unlike any of them) IS admitted by PRICING_REQUEST_VIEW_ROLES. Mutation-checked by
     * temporarily reverting requireViewAccess's origin branch to the plain DEAL_DIRECT rules and
     * confirming every wrong-way-round test in this section goes red while this one and the rest
     * of the suite stay green — see the PR body for the before/after run. */
    @Test
    void ceoRole_canGet_mutationCheckControl() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationDto viewed = quotationService.get(quotation.id(), ceoActor);
        assertThat(viewed.id()).isEqualTo(quotation.id());
    }

    /** MINOR-1 (owner ruling, confirmed 2026-09-20) — import's OWN, UNRELATED, legitimate access
     * to {@code PricingDecisionService#salesView} (its SALES_VIEW_ROLES already includes import,
     * for import's own factory-costing purposes — untouched by this PR) must keep working, and
     * must never expose which pricing method the CEO chose. Proven two ways: behaviourally (the
     * call still succeeds for import and returns the correct new-form fact) AND structurally
     * (PricingDecisionSalesViewDto#newFormPricing is a {@code boolean} — there is no price_mode
     * STRING field anywhere on this DTO for any caller, import included, to ever read; the type
     * system itself is the proof for the "no such field" half of this requirement). */
    @Test
    void importRole_seesNewFormPricingBooleanOnSalesView_neverAPriceModeString() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        PricingDecisionSalesViewDto salesView = decisionService.salesView(decision.pricingRequestId(), importActor);
        assertThat(salesView.newFormPricing()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // M1 fix (Opus review, 2026-09-20) — read-only lookup by pricing request id, backing
    // PricingRequestDetailPage's "ใบเสนอราคาลูกค้า" panel. No create side effect (unlike
    // createFromPricingRequest itself), reuses the SAME requireViewAccess gate M3 pinned above.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void findForPricingRequest_returnsEmptyWhenNoneExistsYet() {
        long pricingRequestId = twoItemSubmittedCosting();
        assertThat(quotationService.findForPricingRequest(pricingRequestId, salesActor)).isEmpty();
    }

    @Test
    void findForPricingRequest_returnsTheDraftOnceCreated() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto created = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        java.util.Optional<DealQuotationDto> found =
            quotationService.findForPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(created.id());
        assertThat(found.get().docStatus()).isEqualTo("DRAFT");
    }

    @Test
    void findForPricingRequest_accountRole_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        assertThatThrownBy(() -> quotationService.findForPricingRequest(decision.pricingRequestId(), accountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // M2 (Opus review, 2026-09-20) — mutual exclusivity, both directions. A real-DB probe found
    // one pricing request could get BOTH an old-flow (CustomerQuotationService) and a new-flow
    // (DealQuotationService#createFromPricingRequest) quotation. Fixed with a live-quotation
    // check in each create path, using the mirror-image repository methods
    // DealQuotationRepository#hasLivePricingRequestQuotation / CustomerQuotationRepository#hasLiveQuotation.
    // New-form PRs cannot be ISSUED until S2 (submit is refused — see
    // submit_refusedForPricingRequestOrigin above), which is an accepted gap: Phase 2 (CEO price
    // mode) and this Phase 3 slice deploy together, so nothing in production can reach a
    // NEW-only, un-issuable dead end mid-flow.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void oldQuotationAlreadyExists_newCreateRefused409() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        legacyQuotationService.create(decision.pricingRequestId(),
            new th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest(
                null, null, null, null, null, UUID.randomUUID().toString()),
            salesActor);

        assertThatThrownBy(() -> quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        assertThat(countPricingRequestQuotationsForTicket()).isZero();
    }

    @Test
    void newQuotationAlreadyExists_oldCreateRefused409() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        assertThatThrownBy(() -> legacyQuotationService.create(decision.pricingRequestId(),
            new th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest(
                null, null, null, null, null, UUID.randomUUID().toString()),
            salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        Integer legacyCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id AND origin IS NULL
            """, Map.of("id", ticketId), Integer.class);
        assertThat(legacyCount).isZero();
    }

    /** Positive control for the pair above: with no quotation on either chain yet, BOTH create
     * paths succeed independently. Mutation-checked by temporarily disabling the live-quotation
     * check in {@code DealQuotationService#createFromPricingRequest} (the new-chain guard) and
     * confirming exactly {@code oldQuotationAlreadyExists_newCreateRefused409} goes red — a
     * second quotation gets inserted instead of a 409 — while the rest of the suite, including
     * the mirror-image {@code newQuotationAlreadyExists_oldCreateRefused409}, stays green; then
     * the same for {@code CustomerQuotationService#create}'s own check and the other test. See
     * the PR body for the before/after run. */
    @Test
    void neitherChainStarted_bothCreatePathsIndependentlySucceed_mutationCheckControl() {
        PricingDecisionDto decisionA = approvedDecision("NET", id -> List.of());
        DealQuotationDto newQuotation = quotationService.createFromPricingRequest(decisionA.pricingRequestId(), salesActor);
        assertThat(newQuotation.id()).isPositive();

        PricingDecisionDto decisionB = approvedDecision("NET", id -> List.of());
        th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto oldQuotation =
            legacyQuotationService.create(decisionB.pricingRequestId(),
                new th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest(
                    null, null, null, null, null, UUID.randomUUID().toString()),
                salesActor);
        assertThat(oldQuotation.id()).isPositive();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MINOR-4 (owner ruling, confirmed 2026-09-20, second re-review) — a REJECTED quotation on
    // either engine has no live offer, so it must not block the OTHER engine's create path. Both
    // hasLiveQuotation/hasLivePricingRequestQuotation forced their target row's doc_status
    // directly via SQL rather than driving the full create->issue->recordOutcome workflow: these
    // two guards are pure doc_status predicates, and isolating them from an unrelated multi-step
    // flow's own setup cost is the same technique the M2 tests above already use.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void oldQuotationRejected_newCreateStillWorks() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto oldQuotation =
            legacyQuotationService.create(decision.pricingRequestId(),
                new th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest(
                    null, null, null, null, null, UUID.randomUUID().toString()),
                salesActor);
        jdbc.update("UPDATE sales.quotation SET doc_status = 'REJECTED' WHERE quotation_id = :id",
            Map.of("id", oldQuotation.id()));

        DealQuotationDto newQuotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(newQuotation.id()).isPositive();
    }

    /** Mirror direction (owner ruling: "make them consistent") — REJECTED is unreachable for a
     * PRICING_REQUEST-origin row in S1 (requireStatusMachineEnabled refuses submit/approve/
     * reject/revise/reorder for this origin), so this forces it via SQL to prove
     * hasLivePricingRequestQuotation's own predicate is ALREADY correct for the day S2 makes it
     * reachable, rather than leaving an untested guess. */
    @Test
    void newQuotationRejected_oldCreateStillWorks() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        jdbc.update("UPDATE sales.quotation SET doc_status = 'REJECTED' WHERE quotation_id = :id",
            Map.of("id", quotation.id()));

        th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto oldQuotation =
            legacyQuotationService.create(decision.pricingRequestId(),
                new th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest(
                    null, null, null, null, null, UUID.randomUUID().toString()),
                salesActor);
        assertThat(oldQuotation.id()).isPositive();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // M4 (Opus review, 2026-09-20) — changes that alter the OFFER aren't flagged. (a) refuses a
    // linked line's TYPE changing away from TILE; (b) refuses a linked line's PRODUCT IDENTITY
    // changing; (c) dropping a linked line is still allowed but flags the header count; (d)
    // "คืนรายการ" restores a dropped linked line. Quantity/เผื่อ stay freely editable and
    // unflagged throughout — see upsertWithChangedDiscount's own sibling tests above for that.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void linkedLine_typeChangedAwayFromTile_refused400() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto item = quotation.items().get(0);
        List<ItemInput> payload = new java.util.ArrayList<>();
        payload.add(linkedItemAsPlain(item));
        for (DealQuotationItemDto other : quotation.items().subList(1, quotation.items().size())) {
            payload.add(existingTileInput(other));
        }
        assertThatThrownBy(() -> quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        // Nothing persisted — still TILE.
        assertThat(itemById(quotationService.get(quotation.id(), salesActor), item.id()).lineType())
            .isEqualTo("TILE");
    }

    @Test
    void linkedLine_brandChanged_refused400() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto item = quotation.items().get(0);
        List<ItemInput> payload = new java.util.ArrayList<>();
        payload.add(linkedItemWithChangedBrand(item));
        for (DealQuotationItemDto other : quotation.items().subList(1, quotation.items().size())) {
            payload.add(existingTileInput(other));
        }
        assertThatThrownBy(() -> quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), salesActor))
            .hasMessageContaining("คำขอราคาต้นทาง");
        assertThat(itemById(quotationService.get(quotation.id(), salesActor), item.id()).brand())
            .isEqualTo(item.brand());
    }

    /** Positive control for the two refusals above: quantity IS freely editable on a linked line
     * (the ruling this whole slice is built on) — only lineType/product-identity are protected.
     * Mutation-checked by temporarily disabling requireLinkedLineIdentityUnchanged's call inside
     * #update and confirming exactly the two tests above go red while this one and the rest of
     * the suite stay green — see the PR body for the before/after run. */
    @Test
    void linkedLine_quantityChanged_allowed() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto item = quotation.items().get(0);
        List<ItemInput> payload = new java.util.ArrayList<>();
        payload.add(withPiecesInput(item, new BigDecimal("9")));
        for (DealQuotationItemDto other : quotation.items().subList(1, quotation.items().size())) {
            payload.add(existingTileInput(other));
        }
        DealQuotationDto updated = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), salesActor);
        assertThat(itemById(updated, item.id()).piecesInput()).isEqualTo(9);
    }

    @Test
    void droppingLinkedLine_allowed_flagsHeaderCount() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(quotation.items()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(quotation.itemsRemovedFromCeoCount()).isZero();
        DealQuotationItemDto kept = quotation.items().get(0);
        // Drop every OTHER item — only the first row's ItemInput is sent.
        List<ItemInput> payload = List.of(existingTileInput(kept));

        DealQuotationDto updated = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), salesActor);

        assertThat(updated.items()).hasSize(1);
        assertThat(updated.itemsRemovedFromCeoCount()).isEqualTo(quotation.items().size() - 1);
    }

    @Test
    void restoreRemovedItem_bringsTheLineBack_clearsTheFlag() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto kept = quotation.items().get(0);
        DealQuotationItemDto dropped = quotation.items().get(1);
        long droppedDecisionItemId = quotationRepository.findItemLinks(quotation.id()).stream()
            .filter(link -> link.itemId() == dropped.id())
            .findFirst().orElseThrow().pricingDecisionItemId();

        DealQuotationDto afterDrop = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), List.of(existingTileInput(kept))), salesActor);
        assertThat(afterDrop.items()).hasSize(1);
        assertThat(afterDrop.itemsRemovedFromCeoCount()).isEqualTo(1);
        // M4(d) fix's own field — exactly the dropped line, ready for a "คืนรายการ" button.
        assertThat(afterDrop.removedCeoItems()).hasSize(1);
        assertThat(afterDrop.removedCeoItems().get(0).pricingDecisionItemId()).isEqualTo(droppedDecisionItemId);
        assertThat(afterDrop.removedCeoItems().get(0).brand()).isEqualTo(dropped.brand());

        DealQuotationDto restored = quotationService.restoreRemovedItem(quotation.id(), droppedDecisionItemId, salesActor);

        assertThat(restored.items()).hasSize(2);
        assertThat(restored.itemsRemovedFromCeoCount()).isZero();
        assertThat(restored.removedCeoItems()).isEmpty();
        // The restored row is a NEW quotation_item_id (the old one was hard-deleted when dropped),
        // still carrying the SAME product identity as before.
        DealQuotationItemDto restoredItem = restored.items().stream()
            .filter(i -> i.id() != kept.id())
            .findFirst().orElseThrow();
        assertThat(restoredItem.id()).isNotEqualTo(dropped.id());
        assertThat(restoredItem.brand()).isEqualTo(dropped.brand());
        assertThat(restoredItem.model()).isEqualTo(dropped.model());
    }

    @Test
    void restoreRemovedItem_alreadyPresent_refused409() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto item = quotation.items().get(0);
        long decisionItemId = quotationRepository.findItemLinks(quotation.id()).stream()
            .filter(link -> link.itemId() == item.id())
            .findFirst().orElseThrow().pricingDecisionItemId();

        assertThatThrownBy(() -> quotationService.restoreRemovedItem(quotation.id(), decisionItemId, salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MAJOR-1 (Opus re-review, 2026-09-20) — restoreRemovedItem never recomputed total_amount,
    // the column #mapQuotation reads as the subtotal and from which VAT/grand total derive. A
    // restore left the total understated by exactly the restored line's amount until the next
    // full save. Fixed with DealQuotationRepository#updateSubtotal, reusing #update's own
    // WastageCalculator.subtotal arithmetic.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void restoreRemovedItem_recomputesSubtotalVatAndGrandTotal() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto kept = quotation.items().get(0);
        DealQuotationItemDto dropped = quotation.items().get(1);
        long droppedDecisionItemId = quotationRepository.findItemLinks(quotation.id()).stream()
            .filter(link -> link.itemId() == dropped.id())
            .findFirst().orElseThrow().pricingDecisionItemId();
        BigDecimal originalSubtotal = quotation.subtotalAmount();
        // Sanity: the two-item fixture's lines are not all zero, so a silent "leave the old total"
        // bug could not pass this test by coincidence.
        assertThat(originalSubtotal).isGreaterThan(BigDecimal.ZERO);

        DealQuotationDto afterDrop = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), List.of(existingTileInput(kept))), salesActor);
        assertThat(afterDrop.subtotalAmount()).isLessThan(originalSubtotal);

        DealQuotationDto restored = quotationService.restoreRemovedItem(quotation.id(), droppedDecisionItemId, salesActor);

        // The load-bearing assertion (MAJOR-1's own probe): the PERSISTED total must equal what
        // the CURRENT lines actually sum to — NOT necessarily the pre-drop snapshot, since a
        // rebuilt line is not guaranteed byte-identical to the one that was dropped (the
        // coordinator's own probe numbers show exactly this: original 201,842.25, but the lines
        // after a drop+restore summed to a DIFFERENT 222,116.27 — the bug was the STORED total
        // staying at the stale post-drop 121,644.12 instead of catching up to THAT number, not
        // failing to reproduce the original). Recomputed independently here via the SAME
        // WastageCalculator arithmetic #update/#restoreRemovedItem themselves use, so this proves
        // the persisted column agrees with the lines, not merely with itself.
        BigDecimal expectedSubtotal = WastageCalculator.subtotal(
            restored.items().stream().map(DealQuotationItemDto::lineAmount).toList());
        BigDecimal expectedVat = WastageCalculator.vat(expectedSubtotal, restored.documentLanguage());
        BigDecimal expectedGrand = WastageCalculator.grandTotal(expectedSubtotal, expectedVat);
        assertThat(restored.subtotalAmount()).isEqualByComparingTo(expectedSubtotal);
        assertThat(restored.vatAmount()).isEqualByComparingTo(expectedVat);
        assertThat(restored.grandTotal()).isEqualByComparingTo(expectedGrand);
        // It must NOT still be the stale post-drop total (the bug this whole test pins).
        assertThat(restored.subtotalAmount()).isNotEqualByComparingTo(afterDrop.subtotalAmount());

        // A render right after the restore reflects the SAME persisted total -- get()/renderPdf
        // share the same #mapQuotation read path, so this proves it is not merely the in-memory
        // return value of #restoreRemovedItem that is correct while the DATABASE row is stale.
        DealQuotationDto reread = quotationService.get(quotation.id(), salesActor);
        assertThat(reread.subtotalAmount()).isEqualByComparingTo(expectedSubtotal);
        assertThat(reread.vatAmount()).isEqualByComparingTo(expectedVat);
        assertThat(reread.grandTotal()).isEqualByComparingTo(expectedGrand);
        byte[] pdf = quotationService.renderPdf(quotation.id(), salesActor);
        assertThat(pdf).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MAJOR-3 (Opus re-review, 2026-09-20) — write-without-read: requireEditAccess honoured
    // can_create_quotation/EDIT_ROLES broadly while requireViewAccess (M3) already excluded them
    // for this origin, so a grant holder of ANY role got UPDATE/CANCEL/RESTORE ok, GET 403 — and
    // read every ceo* field back out of the write response anyway (update/restoreRemovedItem both
    // return requireQuotation(id) with no separate view check). Fixed by making
    // requireEditAccessForQuotation origin-aware too (every write path funnels through it,
    // including #update, which used to call the raw ticket-only check directly).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void grantedAccountRole_cannotUpdate_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        UserPrincipal grantedAccountActor = grantedAccountActor("update");

        List<ItemInput> payload = quotation.items().stream().map(this::existingTileInput).toList();
        assertThatThrownBy(() -> quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), payload), grantedAccountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void grantedAccountRole_cannotCancel_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        UserPrincipal grantedAccountActor = grantedAccountActor("cancel");

        assertThatThrownBy(() -> quotationService.cancel(quotation.id(),
            new th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest("ทดสอบ"), grantedAccountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void grantedAccountRole_cannotRestore_403() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto kept = quotation.items().get(0);
        DealQuotationItemDto dropped = quotation.items().get(1);
        long droppedDecisionItemId = quotationRepository.findItemLinks(quotation.id()).stream()
            .filter(link -> link.itemId() == dropped.id())
            .findFirst().orElseThrow().pricingDecisionItemId();
        quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), List.of(existingTileInput(kept))), salesActor);
        UserPrincipal grantedAccountActor = grantedAccountActor("restore");

        assertThatThrownBy(() -> quotationService.restoreRemovedItem(quotation.id(), droppedDecisionItemId, grantedAccountActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MINOR-2/MINOR-3 (Opus re-review, 2026-09-20) — restore refuses when the document has since
    // switched price mode or language away from what the CEO's decision was priced in, rather
    // than silently building an inconsistent (or wrong-currency) row.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void restoreRemovedItem_refusedWhenDocumentPriceModeDiffersFromCeo() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto kept = quotation.items().get(0);
        DealQuotationItemDto dropped = quotation.items().get(1);
        long droppedDecisionItemId = quotationRepository.findItemLinks(quotation.id()).stream()
            .filter(link -> link.itemId() == dropped.id())
            .findFirst().orElseThrow().pricingDecisionItemId();

        DealQuotationDto afterDrop = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), List.of(existingTileInput(kept))), salesActor);

        ItemInput directNetKept = tileItemInput(kept, kept.unitPrice(), null, null, new BigDecimal("500.00"));
        DealQuotationDto switched = quotationService.update(afterDrop.id(),
            upsertRequestWithMode(afterDrop, "DIRECT_NET", afterDrop.documentLanguage(), List.of(directNetKept)),
            salesActor);
        assertThat(switched.priceMode()).isEqualTo("DIRECT_NET");

        assertThatThrownBy(() -> quotationService.restoreRemovedItem(switched.id(), droppedDecisionItemId, salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> quotationService.restoreRemovedItem(switched.id(), droppedDecisionItemId, salesActor))
            .hasMessageContaining("เปลี่ยนวิธีกรอกราคา");
        // Refused, not silently mis-built -- the line count stays at 1.
        assertThat(quotationService.get(switched.id(), salesActor).items()).hasSize(1);
    }

    @Test
    void restoreRemovedItem_refusedWhenDocumentLanguageIsNotThai() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationItemDto kept = quotation.items().get(0);
        DealQuotationItemDto dropped = quotation.items().get(1);
        long droppedDecisionItemId = quotationRepository.findItemLinks(quotation.id()).stream()
            .filter(link -> link.itemId() == dropped.id())
            .findFirst().orElseThrow().pricingDecisionItemId();

        DealQuotationDto afterDrop = quotationService.update(quotation.id(),
            upsertRequestWithItems(quotation, quotation.priceMode(), List.of(existingTileInput(kept))), salesActor);

        DealQuotationDto switched = quotationService.update(afterDrop.id(),
            upsertRequestWithMode(afterDrop, "NET", "EN", List.of(existingTileInput(kept))), salesActor);
        assertThat(switched.documentLanguage()).isEqualTo("EN");

        assertThatThrownBy(() -> quotationService.restoreRemovedItem(switched.id(), droppedDecisionItemId, salesActor))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> quotationService.restoreRemovedItem(switched.id(), droppedDecisionItemId, salesActor))
            .hasMessageContaining("ภาษาไทย");
        assertThat(quotationService.get(switched.id(), salesActor).items()).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // NIT fix (Opus review, 2026-09-20) — refreshDraftContactSnapshot used to filter
    // `origin = 'DEAL_DIRECT'` only, so a live PRICING_REQUEST DRAFT's frozen ผู้สั่งซื้อ snapshot
    // never refreshed when the underlying customers.contact row changed. Widened to match
    // findById/updateHeader/cancel's own IN ('DEAL_DIRECT', 'PRICING_REQUEST').
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void refreshDraftContactSnapshot_updatesAPricingRequestOriginDraftToo() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(quotation.contactPhone()).isEqualTo("081-000-0000");

        jdbc.update("UPDATE customers.contact SET phone = :phone, email = :email WHERE contact_id = :id",
            Map.of("phone", "089-999-9999", "email", "updated-contact@example.com", "id", contactId));
        int rows = quotationRepository.refreshDraftContactSnapshot(contactId, false);
        assertThat(rows).isEqualTo(1);

        DealQuotationDto refreshed = quotationService.get(quotation.id(), salesActor);
        assertThat(refreshed.contactPhone()).isEqualTo("089-999-9999");
        assertThat(refreshed.contactEmail()).isEqualTo("updated-contact@example.com");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MINOR fix (Opus review, 2026-09-20) — a customer-change revision must supersede the OLD
    // pricing request's own PRICING_REQUEST-origin DRAFT quotation (mirrors PR cancel's own
    // DRAFT-supersede behaviour), or S1 would leave it orphaned forever (there is no S2 issue-time
    // supersede for this origin yet). Never touches a LEGACY quotation, which the owner's
    // reissue-through-CEO-chain ruling deliberately keeps live until the replacement is issued —
    // see supersedeOpenPricingRequestOriginDraft's own Javadoc for the full reasoning.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void customerChangeRevision_supersedesTheOldPricingRequestOriginDraft() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        DealQuotationDto quotation = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        assertThat(quotation.docStatus()).isEqualTo("DRAFT");

        pricingRequestService.createCustomerChangeRevision(decision.pricingRequestId(),
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "ลูกค้าขอเปลี่ยนแปลงรายละเอียด", UUID.randomUUID().toString(), "DESIGNER", null, "Designer Co.",
                LocalDate.now().plusDays(14), new BigDecimal("1000.00"), "THB", "revision note",
                null, null, null, null, null, null, null, null,
                List.of(pricingItem("SCG", "Tile A-S1", "Factory A-S1", new BigDecimal("10")),
                    pricingItem("Cotto", "Tile B-S1", "Factory B-S1", new BigDecimal("5")))),
            salesActor);

        DealQuotationDto afterRevision = quotationService.get(quotation.id(), salesActor);
        assertThat(afterRevision.docStatus()).isEqualTo("SUPERSEDED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Direct-deal list queries do not include PRICING_REQUEST rows
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void directDealSearch_doesNotIncludePricingRequestOriginRows() {
        PricingDecisionDto decision = approvedDecision("NET", id -> List.of());
        quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        // The DEAL_DIRECT-only list surface (`origin = 'DEAL_DIRECT'` throughout the repository)
        // must stay empty — this ticket has ONLY a PRICING_REQUEST-origin quotation.
        assertThat(quotationService.listForTicket(ticketId, salesActor)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private DealQuotationItemDto itemById(DealQuotationDto quotation, long itemId) {
        return quotation.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    private ItemInput existingTileInput(DealQuotationItemDto item) {
        return tileItemInput(item, item.unitPrice(), item.discountPct(), item.specialPriceSqm(), null);
    }

    /** One TILE {@link ItemInput}, carrying {@code item}'s own physical fields (location, catalog
     * link, brand/model/color/texture/size, thickness, sqm/piece, quantity mode, wastage, box
     * data) UNCHANGED, with the price fields set explicitly by the caller — the ONE place this
     * test class builds a TILE {@code ItemInput}, so the 34-positional-argument record can never
     * be miscounted at more than one call site. {@code directNetPrice} is threaded through even
     * though the current priceMode might not be DIRECT_NET — harmless (the server only reads the
     * field relevant to whichever priceMode the request's header carries). */
    private ItemInput tileItemInput(DealQuotationItemDto item, BigDecimal unitPrice, BigDecimal discountPct,
                                    BigDecimal specialPriceSqm, BigDecimal directNetPrice) {
        return new ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(), item.brand(),
            item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(), item.wastageValue(),
            item.piecesPerBox(), unitPrice, discountPct, item.originCountry(), item.leadTimeMinDays(),
            item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, specialPriceSqm, directNetPrice, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }

    /** M4(a) fix's own test fixture — the SAME linked row, resubmitted as PLAIN instead of TILE.
     * Fully-formed PLAIN fields (unitPrice/quantity/unit), same reasoning as
     * {@link #plainItemInput}'s own comment: an invalid PLAIN row would 400 on an UNRELATED
     * validation before {@code requireLinkedLineIdentityUnchanged} is ever reached, which would
     * mask the guard under test (confirmed by mutation check). */
    private ItemInput linkedItemAsPlain(DealQuotationItemDto item) {
        return new ItemInput(null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, new BigDecimal("500.00"), null, null, null, null, null,
            "PLAIN", "เปลี่ยนชนิดรายการ", new BigDecimal("1"), "JOB", null, null, null, null, null,
            item.id(), null, true);
    }

    /** M4(b) fix's own test fixture — the SAME linked row, unchanged except {@code brand}. */
    private ItemInput linkedItemWithChangedBrand(DealQuotationItemDto item) {
        return new ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(), "ยี่ห้ออื่น",
            item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(), item.wastageValue(),
            item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(), item.leadTimeMinDays(),
            item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, item.specialPriceSqm(), null, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }

    /** Positive-control fixture — the SAME linked row, unchanged except {@code piecesInput}
     * (quantity IS freely editable on a linked line; see {@code
     * linkedLine_quantityChanged_allowed}). */
    private ItemInput withPiecesInput(DealQuotationItemDto item, BigDecimal newPiecesInput) {
        return new ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(), item.brand(),
            item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), newPiecesInput.intValueExact(), item.wastageMode(), item.wastageValue(),
            item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(), item.leadTimeMinDays(),
            item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, item.specialPriceSqm(), null, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }

    /** A brand-new PLAIN row — used only by {@link #addingAnExtraPlainRow_refused400} to prove the
     * server refuses it for a PRICING_REQUEST-origin quotation (owner ruling 2026-09-19). */
    private ItemInput plainItemInput() {
        // unitPrice (position 17) MUST be positive — requirePriceValidForType requires it for
        // EVERY TILE/PLAIN row regardless of origin, so a null here would 400 for an unrelated
        // reason and mask whatever the origin-specific extra-row guard does (confirmed by mutation
        // check: with the guard disabled and unitPrice left null, the test still passed).
        return new ItemInput(null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, new BigDecimal("500.00"), null, null, null, null, null,
            "PLAIN", "ค่าติดตั้ง", new BigDecimal("1"), "JOB", null, null, null, null, null,
            null, null, true);
    }

    private UpsertDealQuotationRequest upsertWithChangedDiscount(DealQuotationDto quotation, long itemId,
                                                                  BigDecimal newDiscountPct) {
        List<ItemInput> items = quotation.items().stream()
            .map(item -> item.id() == itemId
                ? tileItemInput(item, item.unitPrice(), newDiscountPct, item.specialPriceSqm(), null)
                : existingTileInput(item))
            .toList();
        return upsertRequestWithItems(quotation, quotation.priceMode(), items);
    }

    /** MAJOR-3 fix's own test fixture (Opus re-review, 2026-09-20) — an `account`-role employee
     * holding the {@code can_create_quotation} grant, which lets them view/edit ANY DEAL_DIRECT
     * deal but must NOT bypass the narrower PRICING_REQUEST-origin gate (M3's own view fix, now
     * mirrored on every write path). {@code suffix} keeps each call site's employee/email unique
     * within one test class run. */
    private UserPrincipal grantedAccountActor(String suffix) {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long grantedAccountId = createEmployee(employees, "บัญชี MAJOR3 (สิทธิ์พิเศษ) " + suffix,
            "acct-major3-" + suffix + "@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            Map.of("id", grantedAccountId));
        return actor(grantedAccountId, "account");
    }

    private UpsertDealQuotationRequest upsertRequestWithItems(DealQuotationDto quotation, String priceMode,
                                                               List<ItemInput> items) {
        return new UpsertDealQuotationRequest(quotation.contactId(), quotation.deptCode(), quotation.unitCode(),
            quotation.offerDate(), quotation.depositPercent(), quotation.remainderMode(), quotation.creditDays(),
            quotation.validityDays(), quotation.validityMode(), quotation.validityUntil(),
            quotation.customerNotes(), priceMode, quotation.documentLanguage(), quotation.currency(),
            quotation.printedByDisplayId(), quotation.salesRepDisplayId(), quotation.projectName(),
            quotation.omitContactHonorific(), quotation.fullPaymentTerm(), items);
    }

    /** MINOR-2/MINOR-3 fix's own test fixture (Opus re-review, 2026-09-20) — same as {@link
     * #upsertRequestWithItems} but also switches {@code priceMode}/{@code documentLanguage}
     * (currency resolved server-side from whichever language is passed — {@code
     * DealQuotationService#resolveCurrency} refuses any other combination). */
    private UpsertDealQuotationRequest upsertRequestWithMode(DealQuotationDto quotation, String priceMode,
                                                              String documentLanguage, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(quotation.contactId(), quotation.deptCode(), quotation.unitCode(),
            quotation.offerDate(), quotation.depositPercent(), quotation.remainderMode(), quotation.creditDays(),
            quotation.validityDays(), quotation.validityMode(), quotation.validityUntil(),
            quotation.customerNotes(), priceMode, documentLanguage, null,
            quotation.printedByDisplayId(), quotation.salesRepDisplayId(), quotation.projectName(),
            quotation.omitContactHonorific(), quotation.fullPaymentTerm(), items);
    }

    /** M6 fix (coordinator follow-up, 2026-09-20): the wrong-way-round tests above all assert "no
     * row was inserted". They used to query {@link DealQuotationRepository#findByTicket}, which is
     * deliberately DEAL_DIRECT-only (see {@code directDealSearch_doesNotIncludePricingRequestOriginRows})
     * — a PRICING_REQUEST-origin row would never show up there regardless of whether the guard
     * under test actually fired, so that assertion could never fail. Count the row directly with
     * raw SQL instead, exactly like {@code duplicateCreate_returnsTheSameOpenDraft_doesNotInsertASecondRow}
     * already does. */
    private int countPricingRequestQuotationsForTicket() {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id AND origin = 'PRICING_REQUEST'
            """, Map.of("id", ticketId), Integer.class);
        return count;
    }

    /** Drives the golden path to an APPROVED, price_mode-carrying decision (V187), returning the
     * FULL (CEO-shaped) {@link PricingDecisionDto} — mirrors {@code
     * PricingDecisionCeoPriceModeIntegrationTest#startNewFormReview} plus the mode-set/approve
     * steps. {@code itemUpdates} builds the per-item {@link UpdatePricingDecisionItemRequest} list
     * from the STARTED decision's own two items (ids assigned by the DB, not predictable up
     * front) — SPECIAL_SQM/DIRECT_NET need EVERY item explicitly priced before approve() accepts
     * it (unlike NET, which auto-populates a formula list price + 0% discount for any item the
     * caller does not mention), so the callback receives the full item list, not just the first. */
    private PricingDecisionDto approvedDecision(String priceMode,
            java.util.function.Function<List<PricingDecisionItemDto>, List<UpdatePricingDecisionItemRequest>> itemUpdates) {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto started = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = new java.util.ArrayList<>(itemUpdates.apply(started.items()));
        PricingDecisionDto afterMode = decisionService.update(started.id(),
            new UpdatePricingDecisionRequest(null, priceMode, updates), ceoActor);
        return decisionService.approve(afterMode.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
    }

    private UpdatePricingDecisionItemRequest discountItem(long itemId, BigDecimal discountPct) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            discountPct, false, null, false, null, false);
    }

    private UpdatePricingDecisionItemRequest specialSqmItem(long itemId, BigDecimal specialPriceSqm) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            null, false, specialPriceSqm, false, null, false);
    }

    private UpdatePricingDecisionItemRequest directNetItem(long itemId, BigDecimal directNetPrice) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            null, false, null, false, directNetPrice, false);
    }

    /** "ปรับราคาเอง" (manual override) — B1 fix's own test needs this: NET mode's EFFECTIVE list
     * price is COALESCE(this, list_unit_price), never the bare auto-formula price once an
     * override is active. */
    private UpdatePricingDecisionItemRequest overrideItem(long itemId, BigDecimal overridePrice, String reason,
                                                           BigDecimal discountPct) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, reason, overridePrice, false,
            discountPct, false, null, false, null, false);
    }

    private long twoItemSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, twoItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "pcr quotation s1 request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A-S1", "Factory A-S1", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B-S1", "Factory B-S1", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A-S1".equals(factory) ? catalogProductIdFactoryA
            : "Factory B-S1".equals(factory) ? catalogProductIdFactoryB : null;
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
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
