package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.StartNegotiationRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingCostingStatus;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;

class PricingFactoryQuoteCostingIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    private FactoryEmailService factoryEmail;
    private NotificationRepository notificationRepository;
    private AppProperties dispatchProperties;

    private long salesRepId;
    private long importUserId;
    private long secondImportUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal secondImportActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    // Financial-integrity review Finding A (commit 3): submit() now requires every item's
    // catalog snapshot to be fully resolved — see pricingItem() below.
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;
    // "Factory C" / "TestLand" carries an all-zero price_calc_config (freight/insurance/inland/
    // duty all 0), so landedCostPerUnitThb == goodsCostThb exactly for every line costed
    // against it — used by the unit-normalization test matrix below so those assertions are
    // not diluted by unrelated cost components.
    private long catalogProductIdFactoryC;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        notificationRepository = notifications;
        factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyList())).thenReturn(UUID.randomUUID().toString());
        // Small, deterministic values so the reclaim/backoff/attempt-cap tests below run fast and
        // without flakiness, instead of the production defaults (120s reclaim, 8 attempts).
        dispatchProperties = new AppProperties();
        dispatchProperties.getFactoryQuoteDispatch().setReclaimTimeoutSeconds(2);
        dispatchProperties.getFactoryQuoteDispatch().setMaxAttempts(3);
        dispatchProperties.getFactoryQuoteDispatch().setBackoffBaseSeconds(1);
        dispatchProperties.getFactoryQuoteDispatch().setBatchSize(20);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications,
            new FileStorageService("/tmp/glr-pricing-test-uploads"), dispatchProperties);
        costingService = new PricingCostingService(new PricingCostingRepository(jdbc), pricingRequests,
            factoryQuotes, tickets, new FxRateRepository(jdbc), new PriceCalcConfigRepository(jdbc),
            new FactoryConfigRepository(jdbc), notifications);
        TicketService ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-step2@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า เอ", "import-a@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        secondImportUserId = createEmployee(employees, "ฝ่ายนำเข้า บี", "import-b@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ");
        ceoUserId = createEmployee(employees, "ผู้บริหาร ทดสอบ", "ceo-step2@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี ทดสอบ", "account-step2@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-step2@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        secondImportActor = actor(secondImportUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES
                ('Factory A', 'factory-a@example.com', 'THB', 'piece', 'Thailand'),
                ('Factory B', 'factory-b@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email,
                currency = EXCLUDED.currency,
                unit = EXCLUDED.unit,
                country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryA = insertCatalogProduct("Factory A", "TH", "TEST-A-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B", "TH", "TEST-B-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Factory C', 'factory-c@example.com', 'THB', 'piece', 'TestLand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email,
                currency = EXCLUDED.currency,
                unit = EXCLUDED.unit,
                country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryC = insertCatalogProduct("Factory C", "XX", "TEST-C-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        // All-zero config: freight/insurance/inland/duty all 0, so goodsCostThb ==
        // landedCostPerUnitThb exactly for every line costed against "Factory C" — see the
        // catalogProductIdFactoryC field javadoc above.
        jdbc.update("""
            INSERT INTO sales.price_calc_config
                (version, country, freight_per_sqm, insurance_per_sqm, inland_factory_to_port_per_sqm,
                 inland_port_to_warehouse_per_sqm, import_duty_pct, margin_pct, is_current)
            VALUES (1, 'TestLand', 0, 0, 0, 0, 0, 0, TRUE)
            """, Map.of());

        CustomerDto customer = customers.create(
            "บริษัท Step 2 จำกัด", "0100000000001", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0001");
        ProjectDto project = projects.create(customer.id(), "โครงการ Step 2");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Step 2", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A", "Factory A"), ticketItem("Cotto", "Tile B", "Factory B"))),
            salesActor);
        ticketId = created.summary().id();
    }

    @Test
    void revisedScenario_importControlsReadinessRecalculationAndSubmitWithoutMovingTheDeal() {
        String ticketStatusBefore = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        String salesStageBefore = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);

        long pricingRequestId = pricingRequestService.createDraft(ticketId, pricingRequest(), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(drafts).hasSize(2);
        FactoryQuoteDto factoryA = quoteFor(drafts, "Factory A");
        FactoryQuoteDto factoryB = quoteFor(drafts, "Factory B");

        factoryQuoteService.send(factoryA.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", null, null,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), secondImportActor);
        factoryQuoteService.send(factoryB.id(),
            new SendFactoryQuoteRequest("factory-b@example.com", null, null,
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), secondImportActor);
        // send() only enqueues; the outbox worker (simulated here by draining the queue directly)
        // is what actually calls the mail provider and finalizes quote/pricing-request state.
        drainDispatches();
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);

        FactoryQuoteDto factoryARevision1 = factoryQuoteService.receive(factoryA.id(),
            response("REF-A-1", "THB", "120.00", factoryA.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryARevision1.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> "Factory A".equals(q.factoryName()))
            .hasSize(1);
        // Factory B has not answered yet: the request must still read as awaiting factory
        // response, not costing-in-progress, even though Factory A already responded.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        FactoryQuoteDto negotiating = factoryQuoteService.startNegotiation(factoryARevision1.id(),
            new StartNegotiationRequest("ขอต่อรองราคา"), importActor);
        assertThat(negotiating.status()).isEqualTo(FactoryQuoteStatus.NEGOTIATING);

        FactoryQuoteDto factoryARevision2 = factoryQuoteService.receive(negotiating.id(),
            response("REF-A-2", "THB", "110.00", negotiating.items().get(0).pricingRequestItemId()), importActor);
        List<FactoryQuoteDto> factoryARevisions = factoryQuoteService.list(pricingRequestId, importActor).stream()
            .filter(q -> "Factory A".equals(q.factoryName()))
            .toList();
        assertThat(factoryARevisions).extracting(FactoryQuoteDto::revisionNo).containsExactly(1, 2);
        assertThat(factoryARevisions).filteredOn(q -> q.revisionNo() == 1).singleElement()
            .extracting(FactoryQuoteDto::status).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        assertThat(factoryARevision2.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        factoryQuoteService.markReadyForCosting(factoryARevision2.id(), importActor);
        FactoryQuoteDto factoryBResponse = factoryQuoteService.receive(factoryB.id(),
            response("REF-B-1", "THB", "200.00", factoryB.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(factoryBResponse.id(), importActor);

        PricingCostingDto draft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);
        assertThat(draft.status()).isEqualTo(PricingCostingStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_IN_PROGRESS);

        PricingCostingDto calculated1 = costingService.recalculate(draft.id(),
            new RecalculateCostingRequest("first pass"), importActor);
        PricingCostingDto calculated2 = costingService.recalculate(draft.id(),
            new RecalculateCostingRequest("second pass"), importActor);
        assertThat(calculated2.status()).isEqualTo(PricingCostingStatus.CALCULATED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_IN_PROGRESS);
        assertThat(calculated1.submittedAt()).isNull();
        assertThat(calculated2.submittedAt()).isNull();

        FactoryQuoteDto factoryARevision3 = factoryQuoteService.receive(factoryARevision2.id(),
            response("REF-A-3", "THB", "100.00", factoryARevision2.items().get(0).pricingRequestItemId()), importActor);
        assertThat(costingService.get(draft.id(), importActor).stale()).isTrue();
        assertThatThrownBy(() -> costingService.submit(draft.id(), new SubmitCostingRequest("too soon"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        factoryQuoteService.markReadyForCosting(factoryARevision3.id(), importActor);
        PricingCostingDto recalculated = costingService.recalculate(draft.id(),
            new RecalculateCostingRequest("uses latest revision"), importActor);
        assertThat(recalculated.items()).extracting(item -> item.factoryQuoteRevisionNo()).contains(3);

        PricingCostingDto submitted = costingService.submit(draft.id(),
            new SubmitCostingRequest("submit to CEO"), importActor);
        assertThat(submitted.status()).isEqualTo(PricingCostingStatus.SUBMITTED);
        assertThat(submitted.submittedAt()).isNotNull();
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        assertThatThrownBy(() -> costingService.recalculate(submitted.id(),
            new RecalculateCostingRequest("cannot edit"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(costingService.get(submitted.id(), ceoActor).items())
            .extracting(item -> item.rawUnitPrice())
            .contains(new BigDecimal("100.0000"), new BigDecimal("200.0000"));
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> q.items().stream().anyMatch(item -> item.rawUnitPrice() != null))
            .isNotEmpty();

        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.get(submitted.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(ticketStatusBefore);
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(salesStageBefore);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
               AND (raw_price IS NOT NULL OR calced_cost IS NOT NULL OR proposed_price IS NOT NULL OR approved_price IS NOT NULL)
            """, Map.of("ticketId", ticketId), Long.class))
            .isZero();
    }

    @Test
    void costingCreateRejectsClientRequestReplayAcrossPricingRequests() {
        long firstPricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("11111111-1111-4111-8111-111111111111"), salesActor).summary().id();
        pricingRequestService.submit(firstPricingRequestId, salesActor);
        pricingRequestService.pickup(firstPricingRequestId, importActor);
        markAllFactoriesReady(firstPricingRequestId);

        String costingClientRequestId = "99999999-9999-4999-8999-999999999999";
        PricingCostingDto firstDraft = costingService.createDraft(firstPricingRequestId,
            new CreateCostingRequest("first", costingClientRequestId), importActor);
        assertThat(firstDraft.pricingRequestId()).isEqualTo(firstPricingRequestId);

        long secondPricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("22222222-2222-4222-8222-222222222222"), salesActor).summary().id();
        pricingRequestService.submit(secondPricingRequestId, salesActor);
        pricingRequestService.pickup(secondPricingRequestId, importActor);
        markAllFactoriesReady(secondPricingRequestId);

        assertThatThrownBy(() -> costingService.createDraft(secondPricingRequestId,
            new CreateCostingRequest("second", costingClientRequestId), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void factoryQuoteSendIsIdempotentForClientRequestIdAndDoesNotSendTwice() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("33333333-3333-4333-8333-333333333333"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        SendFactoryQuoteRequest send = new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body",
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        factoryQuoteService.send(draft.id(), send, importActor);
        factoryQuoteService.send(draft.id(), send, importActor);

        // Enqueue-only: two calls with the same clientRequestId must still resolve to exactly one
        // dispatch row, and send() itself must never have touched the mail provider.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote_email_dispatch WHERE factory_quote_id = :quoteId
            """, Map.of("quoteId", draft.id()), Long.class)).isEqualTo(1L);
        verify(factoryEmail, times(0)).send(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any());
        assertThat(factoryQuoteService.get(draft.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.DRAFT);

        drainDispatches();

        verify(factoryEmail, times(1)).send(ticketId, "Factory A", "factory-a@example.com", "Subject", "Body", java.util.List.of());
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_email_dispatch
             WHERE factory_quote_id = :quoteId
               AND status = 'SENT'
            """, Map.of("quoteId", draft.id()), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);

        // Draining again (an idle worker tick finding nothing claimable) must not resend.
        drainDispatches();
        verify(factoryEmail, times(1)).send(ticketId, "Factory A", "factory-a@example.com", "Subject", "Body", java.util.List.of());
    }

    // ---- Outbox worker: crash-window recovery (COMMIT 2) ----------------------------------

    @Test
    void dispatchClaimedRowIsNotReclaimedBeforeTimeoutButIsReclaimedAndSentAfterIt() throws Exception {
        // Simulates window A from the review finding: the app crashes right after claiming a
        // dispatch (SENDING) but before ever calling the mail provider. The row must stay
        // un-reclaimable until the configured timeout elapses (dispatchProperties: 2s here), and
        // must become claimable and completable once it does.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-1111-4111-8111-a00000000001"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());

        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENDING");
        // Simulate the crash: no attemptSend/finalizeDispatch call here at all.

        // Immediately after: still within the reclaim timeout, must NOT be reclaimable.
        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isFalse();

        Thread.sleep(2200); // past dispatchProperties' 2s reclaimTimeoutSeconds

        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        factoryQuoteService.attemptSend(dispatchId);
        factoryQuoteService.finalizeDispatch(dispatchId);

        assertThat(factoryQuoteService.get(draft.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENT");
        verify(factoryEmail, times(1)).send(ticketId, "Factory A", "factory-a@example.com", "Subject", "Body", java.util.List.of());
    }

    @Test
    void dispatchCrashedAfterProviderSuccessBeforeFinalizeIsCompletedByTheNextClaimWithoutDuplicatingAuditTrail() {
        // Window B: the provider accepted the email but the app crashed before finalize ran at
        // all. attemptSend() persists provider_message_id right after the provider call succeeds
        // (simulating that step completing before the crash); finalizeDispatch() is then called
        // directly, standing in for "the next claim completes finalization."
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-2222-4222-8222-a00000000002"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());

        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        factoryQuoteService.attemptSend(dispatchId); // provider "succeeds" — crash happens right after this
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENDING"); // finalize never ran

        FactoryQuoteDto finalized = factoryQuoteService.finalizeDispatch(dispatchId);

        assertThat(finalized.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE link = :link AND type = 'FACTORY_EMAIL_SENT'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
        // The email itself must never be sent a second time by any later step.
        verify(factoryEmail, times(1)).send(ticketId, "Factory A", "factory-a@example.com", "Subject", "Body", java.util.List.of());
        factoryQuoteService.attemptSend(dispatchId); // a later reclaim attempting to (re)send is a no-op
        verify(factoryEmail, times(1)).send(ticketId, "Factory A", "factory-a@example.com", "Subject", "Body", java.util.List.of());
    }

    @Test
    void dispatchReFinalizeAfterPartialCrashCompletesTheRestWithoutDuplicatingAlreadyWrittenParts() {
        // Window C, deliberately reproduced at the granularity finalizeDispatch() must tolerate:
        // the quote and pricing request already reached their post-send state (as an earlier,
        // interrupted finalize attempt would have left them, since those two steps are individually
        // idempotent) but the event/notification/finalized_at were never written. finalizeDispatch()
        // must complete the missing parts without erroring on the already-applied ones.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-3333-4333-8333-a00000000003"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());
        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        factoryQuoteService.attemptSend(dispatchId);

        // Hand-roll the partial state an interrupted finalize would have left: quote -> REQUESTED
        // and pricing request -> AWAITING_FACTORY_RESPONSE, but nothing else.
        jdbc.update("UPDATE sales.factory_quote SET status = 'REQUESTED', requested_at = now() WHERE factory_quote_id = :id",
            Map.of("id", draft.id()));
        jdbc.update("UPDATE sales.pricing_request SET status = 'AWAITING_FACTORY_RESPONSE' WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isZero();

        FactoryQuoteDto finalized = factoryQuoteService.finalizeDispatch(dispatchId);

        assertThat(finalized.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE link = :link AND type = 'FACTORY_EMAIL_SENT'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void finalizeDispatchCalledAgainAfterFullCompletionIsANoOp() {
        // Once a dispatch is fully finalized, calling finalizeDispatch() again (e.g. an
        // overlapping worker tick) must not re-run the event/notification steps. This is covered
        // by BOTH guards finalizeDispatch carries (finalizedAt != null short-circuits the whole
        // method; existsEventForDispatch additionally guards the insert itself), so it is not a
        // clean mutation target for either guard in isolation — see
        // dispatchFinalizeSkipsDuplicateEventAndNotificationWhenReRunAfterEventAlreadyWritten below
        // for the test that isolates existsEventForDispatch specifically.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-4444-4444-8444-a00000000004"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());
        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        factoryQuoteService.attemptSend(dispatchId);
        factoryQuoteService.finalizeDispatch(dispatchId);

        FactoryQuoteDto again = factoryQuoteService.finalizeDispatch(dispatchId);

        assertThat(again.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE link = :link AND type = 'FACTORY_EMAIL_SENT'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
        verify(factoryEmail, times(1)).send(ticketId, "Factory A", "factory-a@example.com", "Subject", "Body", java.util.List.of());
    }

    @Test
    void dispatchFinalizeSkipsDuplicateEventAndNotificationWhenReRunAfterEventAlreadyWritten() {
        // Regression for the review defect (processDispatch's self-invocation of finalizeDispatch
        // silently disabled @Transactional in production, since a self-call inside the same class
        // bypasses the Spring AOP proxy). That meant a crash between addEvent/notifyCeo and
        // markDispatchFinalized could leave the event/notification already committed with
        // finalized_at still NULL — the one state the old finalizedAt-only guard could NOT detect.
        // This test constructs exactly that state directly (bypassing finalizeDispatch entirely,
        // the way a proxy-bypassed partial commit would have left it) and proves finalizeDispatch's
        // existsEventForDispatch guard catches it even though finalizedAt is null.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-9999-4999-8999-a00000000009"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());
        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        factoryQuoteService.attemptSend(dispatchId);

        // Hand-roll everything a (buggy, non-atomic) finalize would have committed up through
        // notifyCeo, but NOT the final markDispatchFinalized write — the dangerous partial state.
        jdbc.update("""
            UPDATE sales.factory_quote SET status = 'REQUESTED', requested_at = now() WHERE factory_quote_id = :id
            """, Map.of("id", draft.id()));
        jdbc.update("""
            UPDATE sales.pricing_request SET status = 'AWAITING_FACTORY_RESPONSE' WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId));
        pricingRequests.addEvent(pricingRequestId, ticketId, importUserId, importActor.name(), "FACTORY_EMAIL_SENT",
            "IMPORT_REVIEWING", "AWAITING_FACTORY_RESPONSE", "Factory request sent to Factory A",
            "{\"dispatchId\":" + dispatchId + "}");
        notificationRepository.notifyByRoleForPricingRequest("ceo", pricingRequestId, "FACTORY_EMAIL_SENT",
            "ใบขอราคา ทดสอบ ส่งคำขอโรงงาน Factory A");
        assertThat(dispatchFinalizedAt(dispatchId)).isNull();

        FactoryQuoteDto finalized = factoryQuoteService.finalizeDispatch(dispatchId);

        assertThat(finalized.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENT");
        assertThat(dispatchFinalizedAt(dispatchId)).isNotNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE link = :link AND type = 'FACTORY_EMAIL_SENT'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void dispatchFinalizeRollsBackEntirelyOnFailureAfterEventInsertThenCleanRetryWritesEachAuditRecordOnce() {
        // Proves — rather than asserts — that finalizeDispatch is atomic when genuinely entered
        // through a transaction proxy, which is what the production worker now relies on since
        // orchestration moved out of processDispatch() into
        // FactoryQuoteEmailDispatchWorker.pollAndDispatch() (three separate calls into the
        // Spring-proxied bean). AbstractPostgresIntegrationTest has no Spring proxy at all, so —
        // mirroring the pattern commit 1's concurrency tests established for getting genuine
        // transactional coverage in this harness — finalizeDispatch is driven through an explicit
        // TransactionTemplate bound to the same DataSource the test's `jdbc` uses. A
        // NotificationRepository stub throws from inside notifyCeo, i.e. AFTER addEvent has
        // already executed its INSERT within that same transaction, and the assertion is that
        // NOTHING committed — not even the event insert that ran first.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-8888-4888-8888-a00000000008"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());
        assertThat(factoryQuoteService.claimDispatch(dispatchId)).isTrue();
        factoryQuoteService.attemptSend(dispatchId);

        NotificationRepository throwingNotifications = new NotificationRepository(jdbc) {
            @Override
            public void notifyByRoleForPricingRequest(String role, long pricingRequestIdArg, String type, String message) {
                throw new RuntimeException("simulated notification failure");
            }
        };
        FactoryQuoteService crashingService = new FactoryQuoteService(factoryQuoteRepository, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, throwingNotifications,
            new FileStorageService("/tmp/glr-pricing-test-uploads"), dispatchProperties);

        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> crashingService.finalizeDispatch(dispatchId)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("simulated notification failure");

        // Full rollback: quote still DRAFT, pricing request untouched, no event, finalized_at
        // still null — the event insert that ran before the throw did NOT survive.
        assertThat(factoryQuoteService.get(draft.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isZero();
        assertThat(dispatchFinalizedAt(dispatchId)).isNull();
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENDING");

        // A clean re-finalize (working notifications) now completes exactly once.
        FactoryQuoteDto finalized = factoryQuoteService.finalizeDispatch(dispatchId);

        assertThat(finalized.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE link = :link AND type = 'FACTORY_EMAIL_SENT'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENT");
    }

    @Test
    void dispatchRetryCapIsRespectedAndBackoffIsObserved() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-5555-4555-8555-a00000000005"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryEmailService failingEmail = mock(FactoryEmailService.class);
        when(failingEmail.send(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyList()))
            .thenThrow(new RuntimeException("smtp unreachable"));
        FactoryQuoteService failingService = new FactoryQuoteService(factoryQuoteRepository, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), failingEmail, notificationRepository,
            new FileStorageService("/tmp/glr-pricing-test-uploads"), dispatchProperties);

        failingService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());

        int maxAttempts = dispatchProperties.getFactoryQuoteDispatch().getMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            failingService.processDispatch(dispatchId);
            assertThat(dispatchAttemptCount(dispatchId)).isEqualTo(attempt);
            assertThat(dispatchStatus(dispatchId)).isEqualTo("FAILED");
            assertThat(dispatchNextAttemptAt(dispatchId)).isAfter(java.time.Instant.now());
            // Fast-forward past this attempt's backoff so the loop's next claim (or the final
            // out-of-cap assertion below) is not blocked on real wall-clock time.
            jdbc.update("""
                UPDATE sales.factory_quote_email_dispatch
                   SET next_attempt_at = now() - interval '1 second'
                 WHERE factory_quote_email_dispatch_id = :id
                """, Map.of("id", dispatchId));
        }

        // attempt_count now equals maxAttempts: even though next_attempt_at is due, the cap
        // itself must stop further claims — this is what actually leaves it FAILED for good.
        assertThat(failingService.claimDispatch(dispatchId)).isFalse();
        assertThat(dispatchStatus(dispatchId)).isEqualTo("FAILED");
        assertThat(dispatchAttemptCount(dispatchId)).isEqualTo(maxAttempts);
        verify(failingEmail, times(maxAttempts)).send(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyList());
    }

    @Test
    void twoWorkersCannotBothClaimTheSameDispatchRow() throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a0000000-6666-4666-8666-a00000000006"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        long dispatchId = dispatchIdForQuote(draft.id());

        Callable<Boolean> claimTask = () -> factoryQuoteService.claimDispatch(dispatchId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(claimTask);
            Future<Boolean> second = executor.submit(claimTask);
            boolean firstClaimed = first.get(10, TimeUnit.SECONDS);
            boolean secondClaimed = second.get(10, TimeUnit.SECONDS);

            assertThat(firstClaimed ^ secondClaimed).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(dispatchAttemptCount(dispatchId)).isEqualTo(1);
        assertThat(dispatchStatus(dispatchId)).isEqualTo("SENDING");
    }

    @Test
    void firstFactoryResponseOnMultiFactoryRequestMovesToAwaitingFactoryResponseNotCosting() {
        // Regression for the review finding: a single factory answering a multi-factory
        // pricing request must not flip the whole request to "costing in progress" while
        // other factories (Factory B here) are still pending. COSTING_IN_PROGRESS is
        // entered only by PricingCostingService.createDraft(), once every item's factory
        // has a current READY_FOR_COSTING quote.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("44444444-4444-4444-8444-444444444444"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        factoryQuoteService.receive(draft.id(),
            response("REF-DIRECT", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    @Test
    void bothFactoriesReadyThenCreateDraftMovesRequestToCostingInProgress() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        markAllFactoriesReady(pricingRequestId);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        PricingCostingDto draft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);

        assertThat(draft.status()).isEqualTo(PricingCostingStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_IN_PROGRESS);
    }

    @Test
    void factoryQuoteReceiveReplaysLostResponseWithoutDuplicatingRevisionOrSideEffects() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        markAllFactoriesReady(pricingRequestId);
        FactoryQuoteDto factoryA = quoteFor(factoryQuoteService.list(pricingRequestId, importActor), "Factory A");
        PricingCostingDto costing = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);

        String replayClientRequestId = "cccccccc-3333-4333-8333-cccccccccccc";
        FactoryQuoteDto firstAttempt = factoryQuoteService.receive(factoryA.id(),
            response("REF-REVISED", "THB", "90.00", factoryA.items().get(0).pricingRequestItemId(), replayClientRequestId),
            importActor);
        assertThat(firstAttempt.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(firstAttempt.revisionNo()).isEqualTo(2);
        // Baseline captured AFTER the real (first) attempt already marked the costing stale —
        // the replay below must not re-trigger markOpenCostingsStale on top of it.
        Instant costingUpdatedAtAfterFirstAttempt = costingUpdatedAt(costing.id());

        // Simulate the HTTP response for the call above being lost: Import retries with the
        // exact same request against the same (now-superseded) factoryQuoteId.
        FactoryQuoteDto replay = factoryQuoteService.receive(factoryA.id(),
            response("REF-REVISED", "THB", "90.00", factoryA.items().get(0).pricingRequestItemId(), replayClientRequestId),
            importActor);

        assertThat(replay.id()).isEqualTo(firstAttempt.id());
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> "Factory A".equals(q.factoryName()))
            .extracting(FactoryQuoteDto::revisionNo)
            .containsExactly(1, 2);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote
             WHERE pricing_request_id = :id
               AND factory_name_snapshot = 'Factory A'
               AND status = 'SUPERSEDED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification
             WHERE link = :link
               AND type = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_response_receipt
             WHERE client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("clientRequestId", replayClientRequestId), Long.class)).isEqualTo(1L);
        // The replay must not have re-invoked markOpenCostingsStale: the costing row's
        // updated_at must be unchanged from the (already-stale) state left by the first attempt.
        assertThat(costingUpdatedAt(costing.id())).isEqualTo(costingUpdatedAtAfterFirstAttempt);
    }

    @Test
    void factoryQuoteReceiveConcurrentRetryWithSameClientRequestIdBothCallersGetTheSameReplayedResult() throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("dddddddd-4444-4444-8444-dddddddddddd"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        String raceClientRequestId = "eeeeeeee-5555-4555-8555-eeeeeeeeeeee";
        long itemId = draft.items().get(0).pricingRequestItemId();

        // AbstractPostgresIntegrationTest wires FactoryQuoteService directly with `new`, so there
        // is no Spring ApplicationContext/proxy and @Transactional is never applied here — every
        // JDBC call the repository makes would otherwise auto-commit individually, which means
        // lockResponseIdempotencyKey's pg_advisory_xact_lock would be acquired and released
        // within its own single-statement transaction, serializing nothing. To get REAL
        // transactional coverage for that lock (matching what Spring's @Transactional proxy does
        // in production — one connection, one transaction, held for the whole method), each
        // racing call below is wrapped in an explicit TransactionTemplate bound to the same
        // DataSource the test's `jdbc` uses, so the advisory lock genuinely spans the whole
        // receive() call and the second caller really blocks on it.
        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Callable<FactoryQuoteDto> task = () -> txTemplate.execute(status -> factoryQuoteService.receive(draft.id(),
            response("REF-RACE", "THB", "100.00", itemId, raceClientRequestId), importActor));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FactoryQuoteDto> first = executor.submit(task);
            Future<FactoryQuoteDto> second = executor.submit(task);

            FactoryQuoteDto firstResult = first.get(10, TimeUnit.SECONDS);
            FactoryQuoteDto secondResult = second.get(10, TimeUnit.SECONDS);

            // Both callers succeed (a lost-response retry must never surface a 409 to Import) and
            // resolve to the exact same response — one of them raced into the receipt lookup after
            // blocking on the advisory lock and returned the winner's already-recorded result.
            assertThat(firstResult.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(secondResult.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote
             WHERE pricing_request_id = :id
               AND factory_name_snapshot = 'Factory A'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_response_receipt
             WHERE client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("clientRequestId", raceClientRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_RESPONSE_RECEIVED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification
             WHERE link = :link
               AND type = 'FACTORY_RESPONSE_RECEIVED'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void factoryQuoteReceiveConcurrentRevisionRetryWithSameClientRequestIdCreatesExactlyOneRevision() throws Exception {
        // The first-response path is safe from a concurrent retry even without the advisory
        // lock: it UPDATEs an existing row, so Postgres row locking on that row alone forces
        // the loser to block, see the winner's committed receipt, and return it gracefully.
        // The REVISION path has no such row to block on — supersede()+createRevision() INSERTs
        // a brand-new row — so lockResponseIdempotencyKey is the ONLY thing preventing two
        // racing retries of the same clientRequestId from both creating a new revision. This
        // test targets exactly that path.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-7777-4777-8777-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        long itemId = draft.items().get(0).pricingRequestItemId();

        // Move the quote to RESPONSE_RECEIVED first (a first response, not part of the race) so
        // the two racing calls below land on the revision branch.
        FactoryQuoteDto firstResponse = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "120.00", itemId, UUID.randomUUID().toString()), importActor);
        assertThat(firstResponse.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        String raceClientRequestId = "bbbbbbbb-8888-4888-8888-bbbbbbbbbbbb";
        // Same real-transaction rationale as the first-response concurrency test above: this
        // test harness has no Spring proxy, so each racing call is wrapped in its own explicit
        // transaction bound to the same DataSource `jdbc` uses, giving the advisory lock genuine
        // cross-statement coverage for the whole receive() call.
        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Callable<FactoryQuoteDto> task = () -> txTemplate.execute(status -> factoryQuoteService.receive(firstResponse.id(),
            response("REF-A-2", "THB", "110.00", itemId, raceClientRequestId), importActor));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FactoryQuoteDto> first = executor.submit(task);
            Future<FactoryQuoteDto> second = executor.submit(task);

            FactoryQuoteDto firstResult = first.get(10, TimeUnit.SECONDS);
            FactoryQuoteDto secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
            assertThat(firstResult.revisionNo()).isEqualTo(2);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote
             WHERE pricing_request_id = :id
               AND factory_name_snapshot = 'Factory A'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_response_receipt
             WHERE client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("clientRequestId", raceClientRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification
             WHERE link = :link
               AND type = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void factoryQuoteReceiveRejectsClientRequestIdReusedAgainstADifferentFactoryQuote() {
        // Regression for the review finding: the receipt lookup must be scoped to the QUOTE
        // CHAIN, not the pricing request. A pricing request has one quote per factory, so
        // reusing a clientRequestId against a different factory's quote in the same pricing
        // request must 409 — silently returning Factory A's receipt would discard Factory B's
        // response with a 200.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("ffffffff-6666-4666-8666-ffffffffffff"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto factoryA = quoteFor(drafts, "Factory A");
        FactoryQuoteDto factoryB = quoteFor(drafts, "Factory B");

        String reusedClientRequestId = "11111111-9999-4999-8999-111111111111";
        FactoryQuoteDto responseA = factoryQuoteService.receive(factoryA.id(),
            response("REF-A", "THB", "100.00", factoryA.items().get(0).pricingRequestItemId(), reusedClientRequestId),
            importActor);
        assertThat(responseA.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        ReceiveFactoryQuoteRequest reusedForB = response("REF-B", "THB", "200.00",
            factoryB.items().get(0).pricingRequestItemId(), reusedClientRequestId);
        assertThatThrownBy(() -> factoryQuoteService.receive(factoryB.id(), reusedForB, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        FactoryQuoteDto factoryBAfter = factoryQuoteService.get(factoryB.id(), importActor);
        assertThat(factoryBAfter.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(factoryBAfter.items())
            .allSatisfy(item -> assertThat(item.rawUnitPrice()).isNull());
    }

    @Test
    void factoryQuoteAttachmentsAreRawQuoteOnlyAndVisibleToCeo() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("55555555-5555-4555-8555-555555555555"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryQuoteAttachmentDto attachment = factoryQuoteService.uploadAttachment(draft.id(),
            new MockMultipartFile("file", "factory-a.pdf", "application/pdf", "quote".getBytes()),
            importActor);

        assertThat(factoryQuoteService.get(draft.id(), ceoActor).attachments())
            .extracting(FactoryQuoteAttachmentDto::id)
            .contains(attachment.id());
        // Wrong-way-round: raw supplier evidence stays Import/CEO only — Sales and Sales Manager
        // (who both otherwise have oversight visibility on the pricing request itself) must not
        // be able to reach it via list/get/download.
        assertThatThrownBy(() -> factoryQuoteService.getAttachment(attachment.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.getAttachment(attachment.id(), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── Factory quote attachment deletion hardening (V69, review remediation COMMIT 4) ──
    //
    // The audit defect this hardens: deletion used to be permitted whenever the parent pricing
    // request was in a broad "mutable" set that included READY_FOR_CEO_REVIEW, and physically
    // removed the row and file. Each test below isolates exactly ONE of the three independent
    // guards deleteAttachment now applies, by constructing state where the OTHER two guards do
    // not (and could not) fire — see each test's own comment for how.

    @Test
    void factoryQuoteAttachmentDeletion_tombstonesInsteadOfPhysicallyDeletingWhenPermitted() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0001-4001-8001-a10000000001"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        FactoryQuoteAttachmentDto attachment = factoryQuoteService.uploadAttachment(draft.id(),
            new MockMultipartFile("file", "factory-a.pdf", "application/pdf", "quote".getBytes()), importActor);
        String filePath = jdbc.queryForObject("""
            SELECT file_path FROM hr.file_attachment WHERE attachment_id = :id
            """, Map.of("id", attachment.id()), String.class);
        assertThat(java.nio.file.Files.exists(java.nio.file.Paths.get(filePath))).isTrue();

        factoryQuoteService.deleteAttachment(attachment.id(), "duplicate upload", importActor);

        // The row and file are both KEPT — this is the tombstone, not a hard delete.
        assertThat(java.nio.file.Files.exists(java.nio.file.Paths.get(filePath))).isTrue();
        FactoryQuoteAttachmentDto tombstoned = factoryQuoteService.get(draft.id(), ceoActor).attachments().stream()
            .filter(a -> a.id() == attachment.id()).findFirst().orElseThrow();
        assertThat(tombstoned.deletedAt()).isNotNull();
        assertThat(tombstoned.deletedBy()).isEqualTo(importActor.id());
        assertThat(tombstoned.deleteReason()).isEqualTo("duplicate upload");
        // A second delete attempt on an already-tombstoned attachment must not succeed again.
        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachment.id(), "again", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * MUTATION-CHECKABLE GUARD 1: {@code FactoryQuoteService.ATTACHMENT_DELETE_STATUSES}
     * deliberately excludes READY_FOR_CEO_REVIEW (unlike the broader upload-time
     * MUTABLE_STATUSES). Isolated from the other two guards by attaching to an OLD quote
     * revision that was superseded BEFORE ever being marked ready or used by any costing — so
     * neither "quote is READY_FOR_COSTING" nor "referenced by a SUBMITTED costing" can
     * coincidentally also be why deletion is refused.
     */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOncePricingRequestReachesReadyForCeoReview() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0002-4002-8002-a10000000002"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryQuoteDto revision1 = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        FactoryQuoteAttachmentDto attachmentOnRevision1 = factoryQuoteService.uploadAttachment(revision1.id(),
            new MockMultipartFile("file", "revision1.pdf", "application/pdf", "quote".getBytes()), importActor);
        // Revise BEFORE marking ready: revision1 becomes SUPERSEDED, never READY_FOR_COSTING,
        // never referenced by any costing (only revision2 is).
        FactoryQuoteDto revision2 = factoryQuoteService.receive(revision1.id(),
            response("REF-A-2", "THB", "95.00", revision1.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        factoryQuoteService.markReadyForCosting(revision2.id(), importActor);

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(),
            new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // Preconditions that prove this test isolates guard 1 alone:
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status())
            .isEqualTo(FactoryQuoteStatus.SUPERSEDED); // not READY_FOR_COSTING — guard 2 cannot fire
        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(revision1.id()))
            .isFalse(); // the submitted costing references revision2, not revision1 — guard 3 cannot fire

        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachmentOnRevision1.id(), "cleanup", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * MUTATION-CHECKABLE GUARD 2: the quote ITSELF has reached READY_FOR_COSTING. Isolated from
     * the other two guards: no costing has ever been created for this pricing request, so guard
     * 3 cannot fire, and the pricing request's own status (still AWAITING_FACTORY_RESPONSE) is
     * within ATTACHMENT_DELETE_STATUSES, so guard 1 cannot fire either.
     */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOnceTheQuoteItselfIsReadyForCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0003-4003-8003-a10000000003"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            response("REF-A", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        FactoryQuoteAttachmentDto attachment = factoryQuoteService.uploadAttachment(responded.id(),
            new MockMultipartFile("file", "factory-a.pdf", "application/pdf", "quote".getBytes()), importActor);

        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isIn(PricingRequestStatus.AWAITING_FACTORY_RESPONSE, PricingRequestStatus.IMPORT_REVIEWING); // guard 1 cannot fire
        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(responded.id())).isFalse(); // guard 3 cannot fire

        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachment.id(), "cleanup", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * MUTATION-CHECKABLE GUARD 3: this exact quote revision is referenced by a costing that has
     * already been SUBMITTED. Isolated from guard 2 by revising the quote again AFTER submit
     * (permitted — a READY_FOR_CEO_REVIEW pricing request's current quote may still be revised,
     * see FactoryQuoteService.receive's RESPONSE_STATUSES): the old, costed revision becomes
     * SUPERSEDED (not READY_FOR_COSTING), so only guard 3 can be why its attachment stays
     * undeletable. Isolated from guard 1 by driving the pricing request to COSTING_IN_PROGRESS
     * afterward (inside ATTACHMENT_DELETE_STATUSES) via direct SQL — Step 3 (design corrections
     * 3+4) removed FactoryQuoteService.receive()'s own auto-transition into COSTING_IN_PROGRESS
     * (a second, divergent reopen path this branch's own change eliminates; see that method's
     * comment), so a real caller would now go through
     * PricingDecisionService.returnToImport() + PricingCostingService.createDraft() to get there
     * — this test only cares about isolating guard 3, not re-proving that state-machine path
     * (covered separately by PricingRequestRepositoryIntegrationTest /
     * PricingRequestStatusTest), so it takes the direct-SQL shortcut like other setup in this
     * file already does.
     */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOnceReferencedByASubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0004-4004-8004-a10000000004"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        FactoryQuoteDto revision1 = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(revision1.id(), importActor);
        FactoryQuoteAttachmentDto attachmentOnRevision1 = factoryQuoteService.uploadAttachment(revision1.id(),
            new MockMultipartFile("file", "revision1.pdf", "application/pdf", "quote".getBytes()), importActor);

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(),
            new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);

        // Revise AFTER submit: revision1 (the costed one) becomes SUPERSEDED — not
        // READY_FOR_COSTING. The pricing request stays READY_FOR_CEO_REVIEW (Step 3's change —
        // see this test's own javadoc); drive it on to COSTING_IN_PROGRESS directly so guard 1
        // (READY_FOR_CEO_REVIEW excluded from ATTACHMENT_DELETE_STATUSES) cannot be why deletion
        // is refused, isolating guard 3.
        factoryQuoteService.receive(revision1.id(),
            response("REF-A-2", "THB", "90.00", revision1.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        jdbc.update("UPDATE sales.pricing_request SET status = :status WHERE pricing_request_id = :id",
            Map.of("status", PricingRequestStatus.COSTING_IN_PROGRESS, "id", pricingRequestId));

        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(revision1.id())).isTrue();

        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachmentOnRevision1.id(), "cleanup", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Outgoing factory email carries Pricing Request attachments Import marked for inclusion. */
    @Test
    void factoryEmailDispatch_attachesPricingRequestAttachmentsMarkedIncludeInFactoryEmail() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0005-4005-8005-a10000000005"), salesActor).summary().id();
        PricingRequestDtos.PricingRequestAttachmentDto prAttachment = pricingRequestService.uploadAttachment(
            pricingRequestId,
            new MockMultipartFile("file", "sketch.pdf", "application/pdf", "sketch".getBytes()), salesActor);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        pricingRequestService.setAttachmentIncludeInFactoryEmail(prAttachment.id(),
            new PricingRequestRequests.UpdatePricingRequestAttachmentRequest(true), importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body", UUID.randomUUID().toString()),
            importActor);
        drainDispatches();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<th.co.glr.hr.factory.FactoryEmailService.EmailAttachment>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(factoryEmail).send(eq(ticketId), eq("Factory A"), eq("factory-a@example.com"), eq("Subject"), eq("Body"),
            captor.capture());
        assertThat(captor.getValue()).extracting(th.co.glr.hr.factory.FactoryEmailService.EmailAttachment::fileName)
            .containsExactly("sketch.pdf");
    }

    @Test
    void unitConversionRejectsMissingBoxConversionBeforeCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("66666666-6666-4666-8666-666666666666"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        ReceiveFactoryQuoteRequest badBox = new ReceiveFactoryQuoteRequest("REF-BOX", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("1.00"),
                "PER_BOX", "PER_BOX", new BigDecimal("120.00"), "THB", null, null, null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());

        assertThatThrownBy(() -> factoryQuoteService.receive(draft.id(), badBox, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Finding A (financial-integrity review, commit 3): submit()'s catalog-completeness gate,
    // through the REAL PricingRequestService + PricingRequestRepository + real Postgres — not
    // Mockito. PricingRequestServiceTest (Mockito-based) covers the branch being chosen with
    // hand-built PricingRequestItemDto stubs; it cannot prove PricingRequestRepository.
    // snapshotCatalogSelections's actual SQL (a join across price_catalog.product_prices /
    // price_list_versions / factories) resolves correctly — a mocked repository would pass
    // happily even if that SQL joined on the wrong column. These tests are written
    // wrong-way-round per CLAUDE.md: assert the request CANNOT be submitted / CANNOT reach
    // Import unpriced, and assert the persisted database row, not just the thrown exception.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submitCatalogGate_rejectsFreeTextItemAndLeavesRequestInDraft() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(freeTextPricingItem("Free-text tile, no catalog product"))),
            salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.submit(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getMessage()).contains("รายการที่ 1");
            });
        // Wrong-way-round: assert the row genuinely never left DRAFT, not merely that an
        // exception was thrown — a bug that threw AFTER a partial commit would still "throw"
        // but leave the request wrongly advanced.
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.DRAFT);
    }

    @Test
    void submitCatalogGate_rejectsItemPointingAtNonActivePriceListVersion() {
        long archivedProductId = insertCatalogProduct("Factory Archived", "TH", "ARCHIVED-001",
            new BigDecimal("50.00"), "THB", "per_piece", "ARCHIVED");
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(new PricingRequestRequests.PricingRequestItemRequest(
                    null, archivedProductId, null, "Brand", "Model", "Brand Model", null, null, "60x60",
                    "Factory Archived", new BigDecimal("1"), new BigDecimal("1"), "piece", UnitBasis.PER_PIECE,
                    QuantityType.CONFIRMED, null, null, null))),
            salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.submit(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.DRAFT);
    }

    @Test
    void submitCatalogGate_acceptsItemPointingAtActiveCatalogPriceAndPersistsAllSixSnapshotColumns() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")))),
            salesActor).summary().id();

        pricingRequestService.submit(pricingRequestId, salesActor);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT catalog_price_id, price_list_version_id, catalog_base_price, catalog_currency,
                   resolved_factory_id, resolved_factory_name
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId));
        assertThat(row.get("catalog_price_id")).isEqualTo(catalogProductIdFactoryA);
        assertThat(row.get("price_list_version_id")).isNotNull();
        assertThat(((java.math.BigDecimal) row.get("catalog_base_price"))).isEqualByComparingTo("100.00");
        assertThat(row.get("catalog_currency")).isEqualTo("THB");
        assertThat(row.get("resolved_factory_id")).isNotNull();
        assertThat(row.get("resolved_factory_name")).isEqualTo("Factory A");
    }

    @Test
    void submitCatalogGate_reportsEveryFailingLineNumberAcrossMultipleItems() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(
                    pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")),
                    freeTextPricingItem("Free-text line 2"),
                    freeTextPricingItem("Free-text line 3"))),
            salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.submit(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getMessage()).contains("รายการที่ 2, 3");
            });
    }

    private PricingRequestRequests.PricingRequestItemRequest freeTextPricingItem(String description) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, null, null, description,
            null, null, "60x60", "Free Text Factory", new BigDecimal("1"), new BigDecimal("1"), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Finding B (financial-integrity review, commit 3): unit-normalization test matrix.
    //
    // All five "should succeed" cases below are built so the SAME physical quantity (200
    // pieces) at the SAME per-piece price (50 THB/piece) is expressed through five different
    // quote-basis / request-basis combinations. Every one of them must land on the exact same
    // total: 10,000.0000 THB — that identity IS the correctness property being tested: the
    // total must depend only on the physical quantity and price, never on which unit either
    // side happened to be expressed in. This is also the review's own worked example (case 1
    // below): factory quotes 1,000 THB/box, 20 pieces/box, customer wants 10 boxes -> the
    // pre-fix code computed 1000/20*10 = 500 (treating "10" as if it were already a piece
    // count); the fix computes 1000/20*(10*20) = 10,000.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unitConversion_quotePerBoxRequestInBoxes_worksedExampleGoodsComponentIs10000NotThe500ThePreFixCodeProduced() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("10000.0000");
    }

    @Test
    void unitConversion_quotePerBoxRequestInPieces() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("200"), UnitBasis.PER_PIECE,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200");
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("10000.0000");
    }

    @Test
    void unitConversion_quotePerPieceRequestInBoxes() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_PIECE, new BigDecimal("200.00"), "50.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("10000.0000");
    }

    @Test
    void unitConversion_quotePerSqmRequestInPieces() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("200"), UnitBasis.PER_PIECE,
            UnitBasis.PER_SQM, new BigDecimal("100.00"), "100.00",
            new BigDecimal("0.5"), null, null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200");
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("10000.0000");
    }

    @Test
    void unitConversion_quotePerPieceRequestInSqm() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("100"), UnitBasis.PER_SQM,
            UnitBasis.PER_PIECE, new BigDecimal("200.00"), "50.00",
            new BigDecimal("0.5"), null, null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("10000.0000");
    }

    // The gap the review specifically called out: FactoryQuoteService.receive()'s own
    // per-basis validation only requires piecesPerBox when the QUOTE's own unitBasis is
    // PER_BOX (see unitConversionRejectsMissingBoxConversionBeforeCosting above) — it has no
    // way to know the REQUEST is PER_BOX, since that lives on a different aggregate. A
    // PER_PIECE quote with no piecesPerBox therefore sails through receive() even when the
    // request itself is in boxes; PricingCostingService.calculate() must be the one place that
    // catches this, since it is the only place both bases are known at once.
    @Test
    void unitConversion_missingPiecesPerBoxForBoxRequestAgainstAPerPieceQuoteIs422() {
        assertThatThrownBy(() -> singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_PIECE, new BigDecimal("200.00"), "50.00",
            new BigDecimal("0.5"), null, null))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getMessage()).contains("piecesPerBox");
            });
    }

    /**
     * Builds a fresh single-item pricing request against "Factory C" / TestLand's all-zero
     * price_calc_config (see the {@code catalogProductIdFactoryC} field javadoc), drives it
     * through submit -> pickup -> generate draft -> factory response -> mark ready -> costing
     * draft -> recalculate, and returns the resulting single costing line.
     */
    private PricingCostingItemDto singleItemCosting(
        BigDecimal requestedQty, String requestedUnitBasis,
        String quotedUnitBasis, BigDecimal quotedQuantity, String rawPrice,
        BigDecimal sqmPerUnit, BigDecimal piecesPerBox, BigDecimal linearMPerUnit
    ) {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryC, null, "TestBrand", "TestModel", "TestBrand TestModel",
            null, null, "1x1", "Factory C", requestedQty, requestedQty, "unit", requestedUnitBasis,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "unit conversion test", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C");
        ReceiveFactoryQuoteRequest response = responseWithUnit("REF-UNIT", "THB", rawPrice,
            draft.items().get(0).pricingRequestItemId(), quotedUnitBasis, quotedQuantity,
            sqmPerUnit, piecesPerBox, linearMPerUnit);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        PricingCostingDto costing = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("unit conversion test", UUID.randomUUID().toString()), importActor);
        PricingCostingDto calculated = costingService.recalculate(costing.id(),
            new RecalculateCostingRequest("calc"), importActor);
        return calculated.items().get(0);
    }

    @Test
    void customerChangeRevisionSupersedesPriorRequestAndCreatesNewDraftRevision() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("88888888-8888-4888-8888-888888888888"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        PricingRequestRequests.CustomerChangeRevisionRequest revision =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "Customer changed size", "12121212-1212-4212-8212-121212121212",
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(20),
                new BigDecimal("1200.00"), "THB", "revision request",
                List.of(pricingItem("SCG", "Tile A revised", "Factory A", new BigDecimal("12"))));
        PricingRequestDtos.PricingRequestDetailDto created =
            pricingRequestService.createCustomerChangeRevision(pricingRequestId, revision, salesActor);

        assertThat(created.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(created.summary().revisionNo()).isEqualTo(2);
        assertThat(created.summary().parentPricingRequestId()).isEqualTo(pricingRequestId);
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUPERSEDED);
    }

    // Review remediation (COMMIT 5, P2 finding 2): createCustomerChangeRevision computed the next
    // revision_no via a bare SELECT MAX(...)+1 with no lock, so two concurrent callers racing the
    // SAME parent (different clientRequestIds -- two genuinely distinct customer-change requests,
    // not a retry of the same one) could both read the same MAX before either INSERT committed.
    @Test
    void createCustomerChangeRevisionSerializesConcurrentCallersOnTheSameChainAndAvoidsDuplicateRevisionNumbers()
            throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-9999-4999-8999-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);

        // This test harness (AbstractPostgresIntegrationTest) wires PricingRequestService with
        // `new`, so there is no Spring proxy and @Transactional is inert here -- without an
        // explicit transaction, each JDBC call from the repository would auto-commit individually
        // and the advisory lock (scoped to the current transaction via pg_advisory_XACT_lock)
        // would not span the whole createCustomerChangeRevision call the way it does in
        // production. Same pattern as
        // PricingFactoryQuoteCostingIntegrationTest's own
        // factoryQuoteReceiveConcurrentRevisionRetryWithSameClientRequestIdCreatesExactlyOneRevision:
        // wrap each racing call in an explicit transaction bound to the same DataSource `jdbc` uses.
        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);

        PricingRequestRequests.CustomerChangeRevisionRequest revisionA =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "Customer changed size", "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb",
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(20),
                new BigDecimal("1200.00"), "THB", "revision A",
                List.of(pricingItem("SCG", "Tile A revised", "Factory A", new BigDecimal("12"))));
        PricingRequestRequests.CustomerChangeRevisionRequest revisionB =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "Customer changed quantity", "cccccccc-2222-4222-8222-cccccccccccc",
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(21),
                new BigDecimal("1500.00"), "THB", "revision B",
                List.of(pricingItem("SCG", "Tile A revised again", "Factory A", new BigDecimal("15"))));

        Callable<Long> taskA = () -> txTemplate.execute(status ->
            pricingRequestService.createCustomerChangeRevision(pricingRequestId, revisionA, salesActor)
                .summary().id());
        Callable<Long> taskB = () -> txTemplate.execute(status ->
            pricingRequestService.createCustomerChangeRevision(pricingRequestId, revisionB, salesActor)
                .summary().id());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Long> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<Long> futureA = executor.submit(taskA);
            Future<Long> futureB = executor.submit(taskB);
            for (Future<Long> future : List.of(futureA, futureB)) {
                try {
                    successes.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Exactly one racer wins (creates the revision); the other is refused cleanly, not left
        // dangling. Its failure is the CLEAN outcome (PricingRequestRepository serialized the two
        // computations so no revision_no ever collided at INSERT time; the loser fails later, at
        // supersedeForCustomerRevision's own compare-and-set, once it sees the parent already
        // SUPERSEDED) -- proving the advisory lock actually serialized this, rather than the two
        // racers colliding on the DB unique index and one surfacing a raw, uncaught
        // DataIntegrityViolationException instead.
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        List<Map<String, Object>> duplicateRevisions = jdbc.queryForList("""
            SELECT revision_no, COUNT(*) AS cnt
              FROM sales.pricing_request
             WHERE pricing_request_id = :root OR root_pricing_request_id = :root
             GROUP BY revision_no
            HAVING COUNT(*) > 1
            """, Map.of("root", pricingRequestId));
        assertThat(duplicateRevisions).isEmpty();

        Long chainRowCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request
             WHERE pricing_request_id = :root OR root_pricing_request_id = :root
            """, Map.of("root", pricingRequestId), Long.class);
        assertThat(chainRowCount).isEqualTo(2L); // root + exactly the one successful revision

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request
             WHERE root_pricing_request_id = :root AND revision_no = 2
            """, Map.of("root", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    /** Simulates one outbox worker tick draining everything currently claimable. */
    private void drainDispatches() {
        for (long id : factoryQuoteService.claimableDispatchIds()) {
            factoryQuoteService.processDispatch(id);
        }
    }

    private long dispatchIdForQuote(long quoteId) {
        return jdbc.queryForObject("""
            SELECT factory_quote_email_dispatch_id
              FROM sales.factory_quote_email_dispatch
             WHERE factory_quote_id = :quoteId
             ORDER BY factory_quote_email_dispatch_id DESC
             LIMIT 1
            """, Map.of("quoteId", quoteId), Long.class);
    }

    private String dispatchStatus(long dispatchId) {
        return jdbc.queryForObject("""
            SELECT status FROM sales.factory_quote_email_dispatch WHERE factory_quote_email_dispatch_id = :id
            """, Map.of("id", dispatchId), String.class);
    }

    private int dispatchAttemptCount(long dispatchId) {
        return jdbc.queryForObject("""
            SELECT attempt_count FROM sales.factory_quote_email_dispatch WHERE factory_quote_email_dispatch_id = :id
            """, Map.of("id", dispatchId), Integer.class);
    }

    private Instant dispatchNextAttemptAt(long dispatchId) {
        java.sql.Timestamp ts = jdbc.queryForObject("""
            SELECT next_attempt_at FROM sales.factory_quote_email_dispatch WHERE factory_quote_email_dispatch_id = :id
            """, Map.of("id", dispatchId), java.sql.Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    private Instant dispatchFinalizedAt(long dispatchId) {
        java.sql.Timestamp ts = jdbc.queryForObject("""
            SELECT finalized_at FROM sales.factory_quote_email_dispatch WHERE factory_quote_email_dispatch_id = :id
            """, Map.of("id", dispatchId), java.sql.Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    private void markAllFactoriesReady(long pricingRequestId) {
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            FactoryQuoteDto response = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(response.id(), importActor);
        }
    }

    private FactoryQuoteDto quoteFor(List<FactoryQuoteDto> quotes, String factoryName) {
        return quotes.stream()
            .filter(quote -> factoryName.equals(quote.factoryName()))
            .findFirst()
            .orElseThrow();
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return response(ref, currency, price, pricingRequestItemId, UUID.randomUUID().toString());
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId,
                                                String clientRequestId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            clientRequestId);
    }

    /**
     * Finding B (financial-integrity review, commit 3) test helper: lets a test specify exactly
     * the QUOTE's unit basis, quoted quantity, and every conversion factor — for the
     * unit-normalization test matrix, where the quote's basis and the request's basis are
     * deliberately varied independently of each other.
     */
    private ReceiveFactoryQuoteRequest responseWithUnit(String ref, String currency, String price,
                                                        long pricingRequestItemId, String quotedUnitBasis,
                                                        BigDecimal quotedQuantity, BigDecimal sqmPerUnit,
                                                        BigDecimal piecesPerBox, BigDecimal linearMPerUnit) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quotedQuantity, quotedUnitBasis, quotedUnitBasis,
                new BigDecimal(price), currency, null, sqmPerUnit, piecesPerBox, linearMPerUnit,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    /**
     * Single-item, single-factory (Factory A only) variant of {@link #pricingRequest()} — used by
     * the attachment-immutability tests below, which need fine-grained control over exactly one
     * quote's revision history without a second factory's quote also needing to reach
     * READY_FOR_COSTING before a costing draft can be created.
     */
    private PricingRequestRequests.CreatePricingRequestRequest singleFactoryPricingRequest(String clientRequestId) {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 2 request", clientRequestId,
            List.of(pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10"))));
    }

    private PricingRequestRequests.CreatePricingRequestRequest pricingRequest() {
        return pricingRequest("77777777-7777-7777-7777-777777777777");
    }

    private PricingRequestRequests.CreatePricingRequestRequest pricingRequest(String clientRequestId) {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 2 request", clientRequestId,
            List.of(
                pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B", "Factory B", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        // Financial-integrity review Finding A (commit 3): submit() now requires every item's
        // catalog snapshot to be fully resolved, so this must reference a real, ACTIVE catalog
        // product — one dedicated catalog product per factory, created in wireServicesAndCreateDeal.
        Long productId = "Factory A".equals(factory) ? catalogProductIdFactoryA
            : "Factory B".equals(factory) ? catalogProductIdFactoryB : null;
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qty, qty, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    private Instant costingUpdatedAt(long costingId) {
        return jdbc.queryForObject("""
            SELECT updated_at FROM sales.pricing_costing WHERE pricing_costing_id = :id
            """, Map.of("id", costingId), java.sql.Timestamp.class).toInstant();
    }
}
