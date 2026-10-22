package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionAccountResolver;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.dealquotation.EmployeeSignatureRepository;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres proof for {@code TicketService.changeCustomer} ("ลูกค้า must also be editable when
 * still not sent for approval, in case of a typo", owner 2026-10-12).
 *
 * <p>This is a PERMISSION change (who may rewrite a deal's customer) and a STATE-GATE change (it is
 * refused once any quotation of the deal has left DRAFT), so per {@code CLAUDE.md} it is proved
 * through the real service and real repository against real Postgres, written wrong-way-round:
 * every refusal case asserts the {@code sales.ticket} row did not move, not merely that something
 * threw. Modelled on {@link DealStageQuoteOwnerAndRouteIntegrationTest}. {@code @Transactional} is
 * inert in this suite, so no assertion relies on a rollback.
 */
class TicketChangeCustomerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TicketRepository tickets;
    private TicketService ticketService;
    private DealQuotationService quotationService;
    private ContactRepository contactRepository;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal otherRep;
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;

    private CustomerDto custA;
    private ProjectDto projectA;
    private ContactDto contactA;
    private CustomerDto custB;
    private ProjectDto projectB;
    private ContactDto contactB;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // REAL pricing-request service: changeCustomer's "live pricing request" gate is now asked of
        // it, and a mock would answer "none" whatever the table holds.
        PricingRequestService pricingRequests = new PricingRequestService(new PricingRequestRepository(jdbc),
            tickets, notifications, new ObjectMapper(), new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-change-customer-test-uploads"), factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new EmployeeAuthRepository(jdbc), new ContactRepository(jdbc), new ProjectRepository(jdbc),
            new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "พนักงานขาย เจ้าของดีล", "sales-owner-cc@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        otherRep = principal(createEmployee(employees, "พนักงานขาย คนอื่น", "sales-other-cc@glr.co.th"), "sales");
        salesManager = principal(createEmployee(employees, "ผจก.ขาย", "salesmgr-cc@glr.co.th"), "sales_manager");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "ceo-cc@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี", "account-cc@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "import-cc@glr.co.th"), "import");

        ProjectRepository projects = new ProjectRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        contactRepository = contacts;
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)),
            tickets, customers, contacts, notifications,
            new NotificationEmailService(null, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        int n = SEQ.incrementAndGet();
        custA = customers.create("บริษัท เอ ทดสอบ จำกัด " + n, taxId(n, 1), "1 ถนนเอ", "สำนักงานใหญ่", "02-111-1111");
        projectA = projects.create(custA.id(), "โครงการ เอ " + n);
        contactA = contacts.create(custA.id(), "เอ", "หนึ่ง", "ผู้จัดการ", "a" + n + "@test.co.th", "081-111-1111");
        custB = customers.create("บริษัท บี ถูกต้อง จำกัด " + n, taxId(n, 2), "2 ถนนบี", "สำนักงานใหญ่", "02-222-2222");
        projectB = projects.create(custB.id(), "โครงการ บี " + n);
        contactB = contacts.create(custB.id(), "บี", "สอง", "ผู้จัดการ", "b" + n + "@test.co.th", "082-222-2222");
    }

    // ── wrong-way-round: who is refused, and the row does not move ──────────

    @Test
    void anotherSalesRepWhoDoesNotOwnTheDealIsRefusedAndTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), otherRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(403);
                assertThat(e.getMessage()).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
            });

        assertTicketStillUnderCustomerA(ticketId);
    }

    @Test
    void accountAndImportAreRefusedAndTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();

        for (UserPrincipal denied : List.of(accountActor, importActor)) {
            assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), denied))
                .as("%s must not change a deal's customer", denied.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
            assertTicketStillUnderCustomerA(ticketId);
        }
    }

    @Test
    void aSubmittedDirectQuotationFreezesTheCustomer_409_andTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();
        insertDirectQuotation(ticketId, "PENDING_APPROVAL", custA.name());

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(409);
                assertThat(e.getMessage()).isEqualTo("เปลี่ยนลูกค้าไม่ได้ เนื่องจากมีใบเสนอราคาที่ส่งขออนุมัติแล้ว");
            });

        assertTicketStillUnderCustomerA(ticketId);
    }

    /**
     * "ANY quotation row of this ticket": an APPROVED row and a pricing-request-origin row freeze
     * the customer exactly like a PENDING_APPROVAL one. Each loop turn gets its own ticket so one
     * refusal cannot mask another.
     */
    @Test
    void everyNonDraftNonCancelledQuotationFreezesTheCustomerWhateverItsOrigin() {
        for (String status : List.of("APPROVED", "ISSUED", "SENT", "SUPERSEDED", "REJECTED")) {
            long ticketId = createTicketUnderCustomerA();
            insertDirectQuotation(ticketId, status, custA.name());
            assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep))
                .as("a DEAL_DIRECT quotation in %s", status)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
            assertTicketStillUnderCustomerA(ticketId);
        }

        long pricingRequestDeal = createTicketUnderCustomerA();
        insertPricingRequestQuotation(pricingRequestDeal, "APPROVED", custA.name());
        assertThatThrownBy(() -> ticketService.changeCustomer(pricingRequestDeal, toCustomerB(null), ownerRep))
            .as("a PRICING_REQUEST-origin quotation in APPROVED")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        assertTicketStillUnderCustomerA(pricingRequestDeal);
    }

    @Test
    void aProjectThatBelongsToTheOtherCustomerIsRefused_400_andTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();

        // customer B, but project A: the project is not under the chosen customer.
        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId,
            new ChangeCustomerRequest(custB.id(), projectA.id(), null), ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getMessage()).isEqualTo("โครงการนี้ไม่ได้อยู่ภายใต้ลูกค้าที่เลือก");
            });

        assertTicketStillUnderCustomerA(ticketId);
    }

    @Test
    void aContactThatBelongsToTheOtherCustomerIsRefused_400_andTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();

        // customer B + project B are consistent, but the contact is customer A's.
        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId,
            new ChangeCustomerRequest(custB.id(), projectB.id(), contactA.id()), ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertTicketStillUnderCustomerA(ticketId);
    }

    @Test
    void aCustomerOrProjectThatDoesNotExistIsRefusedAndTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId,
            new ChangeCustomerRequest(999_999_001L, projectB.id(), null), ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getMessage()).isEqualTo("ไม่พบลูกค้าที่เลือก");
            });
        assertTicketStillUnderCustomerA(ticketId);

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId,
            new ChangeCustomerRequest(custB.id(), 999_999_002L, null), ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getMessage()).isEqualTo("ไม่พบโครงการที่เลือก");
            });
        assertTicketStillUnderCustomerA(ticketId);
    }

    /** Whatever {@code requireActive} refuses for the other deal mutations, this refuses too (409). */
    @Test
    void aClosedLostDealIsRefusedLikeEveryOtherMutation_409_andTheCustomerDoesNotMove() {
        long ticketId = createTicketUnderCustomerA();
        ticketService.markLost(ticketId, DealLostReason.PRICE, null, ownerRep);

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        assertTicketStillUnderCustomerA(ticketId);
    }

    // ── the happy paths ──────────────────────────────────────────────────────

    @Test
    void theOwnerMovesTheDealToTheOtherCustomer_andWritesAnEvent() {
        long ticketId = createTicketUnderCustomerA();
        int eventsBefore = eventCount(ticketId);

        TicketDto result = ticketService.changeCustomer(ticketId, toCustomerB(contactB.id()), ownerRep);

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("customer_id")).isEqualTo(custB.id());
        assertThat(row.get("project_id")).isEqualTo(projectB.id());
        assertThat(row.get("contact_id")).isEqualTo(contactB.id());
        assertThat(row.get("customer_name")).isEqualTo(custB.name());
        assertThat(result.summary().customerId()).isEqualTo(custB.id());
        assertThat(result.summary().customerName()).isEqualTo(custB.name());

        assertThat(eventCount(ticketId)).isEqualTo(eventsBefore + 1);
        assertThat(lastEventMessage(ticketId)).contains("เปลี่ยนลูกค้าเป็น").contains(custB.name());
    }

    // ── the drafts' printed header moves in the SAME transaction as the deal ──────────────────

    /**
     * The header a reader of the draft sees (customer block + project) must already be the new
     * customer's the moment {@code changeCustomer} returns, with NO {@code update} call: a draft
     * reloaded or submitted before any autosave must never show customer A on deal B.
     */
    @Test
    void theDraftsPrintedHeaderIsResnapshottedToTheNewCustomerInTheSameCall_withoutAnyUpdate() {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto draft = quotationService.create(ticketId, saveRequest(), ownerRep);
        Map<String, Object> before = quotationHeader(draft.id());
        assertThat(before.get("customer_name")).isEqualTo(custA.name());
        assertThat(before.get("project_name")).isEqualTo(projectA.name());
        java.sql.Timestamp updatedBefore = (java.sql.Timestamp) before.get("updated_at");

        ticketService.changeCustomer(ticketId, toCustomerB(contactB.id()), ownerRep);

        Map<String, Object> after = quotationHeader(draft.id());
        assertThat(after.get("customer_name")).as("customer_name").isEqualTo(custB.name());
        assertThat(after.get("customer_address")).as("customer_address").isEqualTo(custB.address());
        assertThat(after.get("customer_tax_id")).as("customer_tax_id").isEqualTo(custB.taxId());
        assertThat(after.get("customer_phone")).as("customer_phone").isEqualTo(custB.phone());
        assertThat(after.get("project_name")).as("project_name").isEqualTo(projectB.name());
        assertThat(((java.sql.Timestamp) after.get("updated_at")).after(updatedBefore))
            .as("updated_at advanced").isTrue();
    }

    @Test
    void everyDraftOfTheDealIsResnapshotted_aCancelledRowIsLeftAlone() {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto first = quotationService.create(ticketId, saveRequest(), ownerRep);
        long second = insertDirectQuotation(ticketId, "DRAFT", custA.name()); // N6: the service allows one live
        long cancelled = insertDirectQuotation(ticketId, "CANCELLED", custA.name());

        ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep);

        assertThat(quotationHeader(first.id()).get("customer_name")).isEqualTo(custB.name());
        assertThat(quotationHeader(second).get("customer_name")).isEqualTo(custB.name());
        // CANCELLED is a dead document, not a live draft: it keeps the customer it was cancelled under.
        assertThat(quotationHeader(cancelled).get("customer_name")).isEqualTo(custA.name());
    }

    /** Wrong way round: another deal's rows must not move, whatever their status. */
    @Test
    void aQuotationOfAnotherDealIsNeverTouched_pendingOrDraft() {
        long ticketId = createTicketUnderCustomerA();
        quotationService.create(ticketId, saveRequest(), ownerRep);
        long otherDeal = createTicketUnderCustomerA();
        long pendingOnOtherDeal = insertDirectQuotation(otherDeal, "PENDING_APPROVAL", custA.name());
        long draftOnOtherDeal = insertDirectQuotation(otherDeal, "DRAFT", custA.name()); // N6: a pending one is already live there
        Map<String, Object> pendingBefore = quotationHeader(pendingOnOtherDeal);
        Map<String, Object> draftBefore = quotationHeader(draftOnOtherDeal);

        ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep);

        assertThat(quotationHeader(pendingOnOtherDeal)).isEqualTo(pendingBefore);
        assertThat(quotationHeader(draftOnOtherDeal)).isEqualTo(draftBefore);
    }

    @Test
    void aDraftOrCancelledQuotationDoesNotBlockTheChange_andNoContactClearsTheContact() {
        long ticketId = createTicketUnderCustomerA();
        insertDirectQuotation(ticketId, "DRAFT", custA.name());
        insertDirectQuotation(ticketId, "CANCELLED", custA.name());

        assertThatCode(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep))
            .doesNotThrowAnyException();

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("customer_id")).isEqualTo(custB.id());
        assertThat(row.get("contact_id")).isNull();
    }

    @Test
    void theSalesManagerAndTheCeoMayChangeADealTheyDoNotOwn() {
        long byManager = createTicketUnderCustomerA();
        ticketService.changeCustomer(byManager, toCustomerB(null), salesManager);
        assertThat(ticketRow(byManager).get("customer_id")).isEqualTo(custB.id());

        long byCeo = createTicketUnderCustomerA();
        ticketService.changeCustomer(byCeo, toCustomerB(null), ceo);
        assertThat(ticketRow(byCeo).get("customer_id")).isEqualTo(custB.id());
    }

    // ── a draft's frozen contact must follow the deal's customer ──

    /**
     * A draft carries customer A's contact (picked at create). After the deal moves to customer B
     * with no contact, its stored contact is cleared, so the editor's next save with no contactId
     * does not resolve customer A's contact against B and fail 400 "กรุณาระบุผู้สั่งซื้อ".
     */
    @Test
    void aDraftCarryingTheOldCustomersContactCanStillBeSavedAfterTheChange_andItsContactIsCleared() {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto draft = quotationService.create(ticketId, saveRequest(contactA.id()), ownerRep);
        assertThat(quotationContactId(draft.id())).isEqualTo(contactA.id());
        assertThat(quotationContact(draft.id()).get("ordered_by_name")).isEqualTo(contactA.fullName());

        ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep);

        Map<String, Object> q = quotationContact(draft.id());
        assertThat(q.get("contact_id")).as("contact_id").isNull();
        assertThat(q.get("contact_name")).as("contact_name").isNull();
        assertThat(q.get("contact_phone")).as("contact_phone").isNull();
        assertThat(q.get("contact_email")).as("contact_email").isNull();
        assertThat(q.get("ordered_by_name")).as("signature name: customer A's contact must not print").isNull();
        assertThatCode(() -> quotationService.update(draft.id(), saveRequest(), ownerRep))
            .doesNotThrowAnyException();
        assertThat(quotationContactId(draft.id())).isNull();
    }

    @Test
    void aContactOfTheNewCustomerIsFrozenOntoTheDraftInsteadOfTheOldOne() {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto draft = quotationService.create(ticketId, saveRequest(contactA.id()), ownerRep);

        ticketService.changeCustomer(ticketId, toCustomerB(contactB.id()), ownerRep);

        Map<String, Object> q = quotationContact(draft.id());
        ContactDto b = contactRepository.findById(contactB.id()).orElseThrow();
        assertThat(q.get("contact_id")).isEqualTo(contactB.id());
        assertThat(q.get("contact_name")).isEqualTo(b.fullName());
        assertThat(q.get("ordered_by_name")).as("signature name").isEqualTo(b.fullName());
        assertThat(q.get("contact_phone")).isEqualTo(b.phone());
        assertThat(q.get("contact_email")).isEqualTo(b.email());
        assertThatCode(() -> quotationService.update(draft.id(), saveRequest(), ownerRep))
            .doesNotThrowAnyException();
    }

    @Test
    void aLivePricingRequestFreezesTheCustomer_409_andTheCustomerDoesNotMove() {
        for (String status : List.of("DRAFT", "SUBMITTED", "IMPORT_REVIEWING", "READY_FOR_CEO_REVIEW", "QUOTATION_ISSUED")) {
            long ticketId = createTicketUnderCustomerA();
            insertPricingRequest(ticketId, status);
            assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep))
                .as("a pricing request in %s", status)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("เปลี่ยนลูกค้าไม่ได้ เนื่องจากดีลนี้มีคำขอราคาอยู่");
                });
            assertTicketStillUnderCustomerA(ticketId);
        }
    }

    @Test
    void aDealWhoseOnlyPricingRequestIsCancelledMayChangeCustomer() {
        long ticketId = createTicketUnderCustomerA();
        insertPricingRequest(ticketId, "CANCELLED");

        assertThatCode(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep))
            .doesNotThrowAnyException();
        assertThat(ticketRow(ticketId).get("customer_id")).isEqualTo(custB.id());
    }

    // ── the checks and the writes are serialised on the deal's lock ───────────────────────────

    /**
     * A submit is in flight (uncommitted, holding the deal's lock). A change of customer that
     * started AFTER it must wait for it and then see a quotation that has left DRAFT: 409, and the
     * ticket is still under customer A. Without the lock the change passes its "still a draft?"
     * check against the pre-submit snapshot, moves the ticket, and leaves a PENDING_APPROVAL
     * document printing customer A on a deal that is customer B's.
     */
    @Test
    void aChangeWaitsForASubmitInFlight_andThenIsRefusedBecauseTheQuotationIsPending() throws Exception {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto draft = quotationService.create(ticketId, saveRequest(), ownerRep);
        TicketService changing = transactional(ticketService);

        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> change = transactionTemplate.execute(submitting -> {
                quotationService.submit(draft.id(), ownerRep);
                Future<Throwable> started = otherConnection.submit(
                    () -> catchThrowable(() -> changing.changeCustomer(ticketId, toCustomerB(null), ownerRep)));
                assertThat(isOverWithin(started, 1_000))
                    .as("whether the change is over while the submit is still uncommitted").isFalse();
                return started;
            });

            Throwable outcome = change.get(10, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(409);
                assertThat(e.getMessage()).isEqualTo("เปลี่ยนลูกค้าไม่ได้ เนื่องจากมีใบเสนอราคาที่ส่งขออนุมัติแล้ว");
            });
        } finally {
            otherConnection.shutdownNow();
        }
        assertTicketStillUnderCustomerA(ticketId);
        assertThat(quotationHeader(draft.id()).get("customer_name")).isEqualTo(custA.name());
        assertThat(quotationStatus(draft.id())).isEqualTo("PENDING_APPROVAL");
    }

    /**
     * The other order: a change is in flight (uncommitted, holding the lock). A submit that started
     * after it waits, then succeeds, and the document it sends for approval carries customer B.
     */
    @Test
    void aSubmitWaitsForAChangeInFlight_andThenSendsTheNewCustomersDocument() throws Exception {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto draft = quotationService.create(ticketId, saveRequest(), ownerRep);
        DealQuotationService submitting = transactional(quotationService);

        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> submit = transactionTemplate.execute(changing -> {
                ticketService.changeCustomer(ticketId, toCustomerB(null), ownerRep);
                Future<Throwable> started = otherConnection.submit(
                    () -> catchThrowable(() -> submitting.submit(draft.id(), ownerRep)));
                assertThat(isOverWithin(started, 1_000))
                    .as("whether the submit is over while the change is still uncommitted").isFalse();
                return started;
            });

            assertThat(submit.get(10, TimeUnit.SECONDS)).as("the submit, which waited, goes through").isNull();
        } finally {
            otherConnection.shutdownNow();
        }
        Map<String, Object> header = quotationHeader(draft.id());
        assertThat(quotationStatus(draft.id())).isEqualTo("PENDING_APPROVAL");
        assertThat(header.get("customer_name")).isEqualTo(custB.name());
        assertThat(header.get("project_name")).isEqualTo(projectB.name());
    }

    /**
     * The change test above would still pass if only the change took the lock, because the change's
     * own UPDATE of the draft row makes the submit wait on a row lock. This one holds the deal's
     * lock with nothing else, so it is red unless {@code submit} itself takes it.
     */
    @Test
    void aSubmitWaitsForTheDealsLockEvenWhenNothingElseIsHeld() throws Exception {
        long ticketId = createTicketUnderCustomerA();
        DealQuotationDto draft = quotationService.create(ticketId, saveRequest(), ownerRep);
        DealQuotationService submitting = transactional(quotationService);
        DealQuotationRepository lockOnly = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));

        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> submit = transactionTemplate.execute(holding -> {
                lockOnly.lockTicket(ticketId);
                Future<Throwable> started = otherConnection.submit(
                    () -> catchThrowable(() -> submitting.submit(draft.id(), ownerRep)));
                assertThat(isOverWithin(started, 1_000))
                    .as("whether the submit is over while the deal's lock is held").isFalse();
                return started;
            });
            assertThat(submit.get(10, TimeUnit.SECONDS)).isNull();
        } finally {
            otherConnection.shutdownNow();
        }
        assertThat(quotationStatus(draft.id())).isEqualTo("PENDING_APPROVAL");
    }

    /** Whether {@code task} is over within {@code millis}. One that waits for a lock is not. */
    private static boolean isOverWithin(Future<?> task, long millis) {
        try {
            task.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException stillWaiting) {
            return false;
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ChangeCustomerRequest toCustomerB(Long contactId) {
        return new ChangeCustomerRequest(custB.id(), projectB.id(), contactId);
    }

    // ── can_create_quotation grant holder outside ฝ่ายขาย acts as sales (owner ruling 2026-10-14) ──
    // The actor comes from SessionAccountResolver on a real QC-division row, never a hand-written role.

    @Test
    void qcEmployeeWithGrantWhoCreatedTheDealChangesItsCustomer() {
        long qcId = createQcEmployee("qc-owner-cc@glr.co.th", true);
        UserPrincipal actor = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(qcId).orElseThrow();
        long ticketId = createTicketCreatedBy(qcId);

        ticketService.changeCustomer(ticketId, toCustomerB(null), actor);

        assertThat(ticketRow(ticketId).get("customer_id")).isEqualTo(custB.id());
    }

    @Test
    void qcEmployeeWithoutGrantIsRefusedOnHisOwnDeal_andTheCustomerDoesNotMove() {
        long qcId = createQcEmployee("qc-nogrant-cc@glr.co.th", false);
        UserPrincipal actor = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(qcId).orElseThrow();
        long ticketId = createTicketCreatedBy(qcId);

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertTicketStillUnderCustomerA(ticketId);
    }

    @Test
    void qcEmployeeWithGrantIsRefusedOnADealAnotherRepCreated_andTheCustomerDoesNotMove() {
        long qcId = createQcEmployee("qc-other-cc@glr.co.th", true);
        UserPrincipal actor = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(qcId).orElseThrow();
        long ticketId = createTicketUnderCustomerA(); // created by ownerRepId

        assertThatThrownBy(() -> ticketService.changeCustomer(ticketId, toCustomerB(null), actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertTicketStillUnderCustomerA(ticketId);
    }

    private long createQcEmployee(String email, boolean grant) {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long id = employees.create(new UpsertEmployeeRequest(
            null, null, "QC ผู้ถือสิทธิ์", null, null, null, null, null, null, null,
            email, null, "QC", "QC-QC&ISO", "QC-QC&ISO",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        jdbc.update("UPDATE hr.employee SET can_create_quotation = :g WHERE employee_id = :id",
            Map.of("g", grant, "id", id));
        return id;
    }

    private long createTicketCreatedBy(long employeeId) {
        return tickets.create(
            new CreateTicketRequest("ดีลของ QC", "NORMAL", custA.name(),
                custA.id(), projectA.id(), contactA.id(), null, null, List.of()),
            tickets.nextTicketCode(), employeeId, "QC ผู้ถือสิทธิ์");
    }

    private long createTicketUnderCustomerA() {
        return tickets.create(
            new CreateTicketRequest("ดีลเปลี่ยนลูกค้า", "NORMAL", custA.name(),
                custA.id(), projectA.id(), contactA.id(), null, null, List.of()),
            tickets.nextTicketCode(), ownerRepId, "พนักงานขาย เจ้าของดีล");
    }

    private void assertTicketStillUnderCustomerA(long ticketId) {
        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("customer_id")).as("customer_id").isEqualTo(custA.id());
        assertThat(row.get("project_id")).as("project_id").isEqualTo(projectA.id());
        assertThat(row.get("contact_id")).as("contact_id").isEqualTo(contactA.id());
        assertThat(row.get("customer_name")).as("customer_name").isEqualTo(custA.name());
    }

    private Map<String, Object> ticketRow(long ticketId) {
        return jdbc.queryForMap(
            "SELECT customer_id, project_id, contact_id, customer_name FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId));
    }

    private int eventCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    private String lastEventMessage(long ticketId) {
        return jdbc.queryForObject(
            "SELECT message FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id DESC LIMIT 1",
            Map.of("id", ticketId), String.class);
    }

    private Map<String, Object> quotationHeader(long quotationId) {
        return jdbc.queryForMap("""
            SELECT customer_name, customer_address, customer_tax_id, customer_phone, project_name,
                   contact_id, contact_name, contact_phone, contact_email, doc_status, updated_at
              FROM sales.quotation WHERE quotation_id = :id
            """, Map.of("id", quotationId));
    }

    private Long quotationContactId(long quotationId) {
        return (Long) quotationContact(quotationId).get("contact_id");
    }

    private Map<String, Object> quotationContact(long quotationId) {
        return jdbc.queryForMap(
            "SELECT contact_id, contact_name, contact_phone, contact_email, ordered_by_name FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId));
    }

    private UpsertDealQuotationRequest saveRequest() {
        return saveRequest(null);
    }

    private UpsertDealQuotationRequest saveRequest(Long contactId) {
        ItemInput item = new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
        return new UpsertDealQuotationRequest(contactId, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            "หมายเหตุทดสอบ", List.of(item)).withRecipientType("OWNER");
    }

    private void insertPricingRequest(long ticketId, String status) {
        jdbc.update("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, requested_by, status, cancelled_at)
            VALUES (:code, :ticketId, 'BUYER', :requestedBy, :status,
                    CASE WHEN :status = 'CANCELLED' THEN now() END)
            """, Map.of("code", "PCR-CC-F1-" + SEQ.incrementAndGet(), "ticketId", ticketId,
                "requestedBy", ownerRepId, "status", status));
    }

    private String quotationStatus(long quotationId) {
        return jdbc.queryForObject("SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), String.class);
    }

    /** A direct-deal quotation row, inserted directly so it can sit in any status. */
    private long insertDirectQuotation(long ticketId, String docStatus, String customerName) {
        int n = SEQ.incrementAndGet();
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status,
                 recipient_type, quotation_revision_no, origin, created_by, sales_rep_id, customer_name)
            VALUES
                (:ticketId, :number, :salesRepId, 0, 'THB', :version, :docStatus,
                 'UNSPECIFIED', 1, 'DEAL_DIRECT', :salesRepId, :salesRepId, :customerName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QT-CC-" + ticketId + "-" + n)
                .addValue("salesRepId", ownerRepId)
                .addValue("version", n)
                .addValue("docStatus", docStatus)
                .addValue("customerName", customerName));
        return jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE number = :number",
            Map.of("number", "QT-CC-" + ticketId + "-" + n), Long.class);
    }

    /** The legacy pricing-request-origin shape ({@code origin = 'PRICING_REQUEST'}), same SQL style as DealQuotationIntegrationTest. */
    private long insertPricingRequestQuotation(long ticketId, String docStatus, String customerName) {
        int n = SEQ.incrementAndGet();
        String code = "PCR-CC-" + ticketId + "-" + n;
        jdbc.update("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, requested_by)
            VALUES (:code, :ticketId, 'BUYER', :requestedBy)
            """, Map.of("code", code, "ticketId", ticketId, "requestedBy", ownerRepId));
        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.pricing_request WHERE request_code = :code",
            Map.of("code", code), Long.class);
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, doc_status, recipient_type,
                 quotation_revision_no, created_by, sales_rep_id, pricing_request_id, origin, customer_name)
            VALUES
                (:ticketId, :number, :salesRepId, 0, 'THB', :docStatus, 'BUYER',
                 1, :salesRepId, :salesRepId, :pricingRequestId, 'PRICING_REQUEST', :customerName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QT-CC-PR-" + ticketId + "-" + n)
                .addValue("salesRepId", ownerRepId)
                .addValue("docStatus", docStatus)
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("customerName", customerName));
        return jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE number = :number",
            Map.of("number", "QT-CC-PR-" + ticketId + "-" + n), Long.class);
    }

    private static String taxId(int n, int salt) {
        return String.format("%013d", 1_000_000_000_000L + (long) n * 10 + salt);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-cc@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
