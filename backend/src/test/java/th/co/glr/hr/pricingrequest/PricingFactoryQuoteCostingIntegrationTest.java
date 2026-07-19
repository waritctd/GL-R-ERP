package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
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
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;

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

        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc));
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications);
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
            new SendFactoryQuoteRequest("factory-a@example.com", null, null), secondImportActor);
        factoryQuoteService.send(factoryB.id(),
            new SendFactoryQuoteRequest("factory-b@example.com", null, null), secondImportActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        FactoryQuoteDto factoryARevision1 = factoryQuoteService.receive(factoryA.id(),
            response("REF-A-1", "THB", "120.00", factoryA.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryARevision1.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> "Factory A".equals(q.factoryName()))
            .hasSize(1);

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
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null,
                "45 days", null, null)));
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
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qty, qty, "piece",
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
}
