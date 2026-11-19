package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ChromiumPdfPrinter;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.CustomerService;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Real-DB acceptance + authz + concurrency coverage for Quotation v2 (direct deal quotation, V165) — see
 * docs/sales/quotation-v2-plan.md. Copies the hand-wiring style of
 * customerquotation/CustomerQuotationIntegrationTest (its own @BeforeEach/helpers). */
class DealQuotationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private CustomerRepository customers;
    private ContactRepository contacts;
    private ProjectRepository projects;
    private NotificationRepository notifications;
    private RecordingSalesMailer salesMailer;
    private DealQuotationRepository quotationRepository;
    private DealQuotationService quotationService;
    private EmployeeAuthRepository employeeAuth;
    // See ContactUpdateRefreshesDraftQuotations* tests below.
    // Wrapped in #transactional(...) at each call site, not here, so its own @Transactional
    // is exercised through a real AOP proxy rather than being inert (this suite hand-wires with
    // `new` everywhere else — see AbstractPostgresIntegrationTest#transactional's own Javadoc).
    private CustomerService customerService;
    private EmployeeSignatureService signatureService;
    private CapturingMailer mailer;

    private long salesRepId;
    private long otherSalesId;
    private long salesManagerId;
    private long ceoId;
    private long importUserId;
    private long accountUserId;
    private long qcUserId;
    private long employeeUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal importActor;
    private UserPrincipal accountActor;
    private UserPrincipal qcActor;
    private UserPrincipal employeeActor;
    private long ticketId;
    private long otherTicketId;
    private CustomerDto customer;
    private ContactDto contact;

    @BeforeEach
    void wireServicesAndCreateDeals() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        salesMailer = new RecordingSalesMailer(jdbc);
        notifications = new NotificationRepository(jdbc, salesMailer);
        customers = new CustomerRepository(jdbc);
        contacts = new ContactRepository(jdbc);
        projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        // pricingRequests (last arg) is dead-deal-cascade-only (markLost/cancel) -- unused by create(), the only
        // TicketService method this test calls.
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), null, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        mailer = new CapturingMailer();
        NotificationEmailService approvalMailer =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.test");
        employeeAuth = new EmployeeAuthRepository(jdbc);
        EmployeeSignatureRepository signatureRepository = new EmployeeSignatureRepository(jdbc);
        // Exercise the Chromium engine end-to-end through the service wherever a Chromium can be launched; elsewhere
        // the default LibreOffice engine keeps the PDF assertions meaningful.
        QuotationRenderer quotationRenderer = new QuotationRenderer();
        if (ChromiumPdfPrinter.isAvailable()) {
            quotationRenderer.setPdfRenderer(QuotationRenderer.PDF_RENDERER_CHROMIUM);
        }
        quotationService = new DealQuotationService(quotationRepository, tickets, customers, contacts, notifications,
            approvalMailer, quotationRenderer, employeeAuth, signatureRepository, new CatalogRepository(jdbc),
            "https://portal.test",
            // app.quotation.bank-block-line1..3 — empty here, so the English document prints the proforma-invoice line.
            // DealQuotationEnglishFormTest covers the configured block.
            "", "", "");
        customerService = new CustomerService(customers, contacts, projects, employeeAuth, quotationRepository);

        signatureService = new EmployeeSignatureService(signatureRepository, new ActivityLogRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย คิว", "sales-dq@glr.co.th", "SALES", "แผนกขาย");
        otherSalesId = createEmployee(employees, "พนักงานขาย อื่นคิว", "sales-dq-other@glr.co.th", "SALES", "แผนกขาย");
        salesManagerId = createSalesManager(employees, "ผู้จัดการฝ่ายขาย คิว", "sales-manager-dq@glr.co.th");
        ceoId = createManagingDirector(employees, "ผู้บริหาร คิว", "ceo-dq@glr.co.th");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า คิว", "import-dq@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        accountUserId = createEmployee(employees, "บัญชี คิว", "account-dq@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        qcUserId = createEmployee(employees, "QC คิว", "qc-dq@glr.co.th", "QC", "ฝ่าย QC");
        employeeUserId = createEmployee(employees, "พนักงานทั่วไป คิว", "employee-dq@glr.co.th", "OTHER", "ฝ่ายอื่น");

        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        ceoActor = actor(ceoId, "ceo");
        importActor = actor(importUserId, "import");
        accountActor = actor(accountUserId, "account");
        qcActor = actor(qcUserId, "qc");
        employeeActor = actor(employeeUserId, "employee");

        customer = customers.create(
            "บริษัท Deal Quotation จำกัด", "0100000000099", "999 ถนนทดสอบ", "สนญ.", "02-999-9999");
        ProjectDto project = projects.create(customer.id(), "โครงการ Deal Quotation");
        // ผู้ติดต่อ: every deal here is created WITH a contact on the ticket, so a case that picks
        // none proves that the ผู้ติดต่อ is picked per quotation, the server
        // derives ordered_by_name from it, and the deal's contact is never inherited (see
        // #create_withNoContactId_doesNotInheritTheDealsContact).
        contact = contacts.create(customer.id(), "สมหญิง", "ใจดี", "ผู้จัดการจัดซื้อ",
            "somying@customer.test", "081-111-2222");
        ticketId = createTicket("ดีล DQ", project.id(), contact.id(), salesActor);
        otherTicketId = createTicket("ดีล DQ อื่น", project.id(), contact.id(), otherSalesActor);
    }

    private long createTicket(String title, long projectId, Long contactId, UserPrincipal creator) {
        TicketDto created = ticketService.create(
            new CreateTicketRequest(title, "NORMAL", customer.name(), customer.id(), projectId,
                contactId, null, null, null), creator);
        return created.summary().id();
    }

    // Acceptance

    // ─────────────────────────────────────────────────────────────────────────────────────
    // "สร้างจากใบเดิม" / สั่งเหมือนเดิม — clone an APPROVED quotation into a new, INDEPENDENT draft.
    //
    // A deal may hold only ONE live quotation at a time (the sweep is origin-agnostic:
    // DEAL_DIRECT APPROVED, PRICING_REQUEST ISSUED/REVISION_REQUESTED). Clone CREATION itself
    // leaves the source untouched (still APPROVED) -- see the first test below -- but once the
    // CLONE ITSELF reaches APPROVED, DealQuotationService#approve's same-ticket sweep supersedes
    // the source then, exactly as approving a revision or a second, wholly independent
    // first-issue quotation would.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final class SupersedeOnLockDealQuotationRepository extends DealQuotationRepository {
        private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate;
        private final long targetQuotationId;

        SupersedeOnLockDealQuotationRepository(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                                               CatalogRepository catalog, long targetQuotationId) {
            super(jdbc, catalog);
            this.jdbcTemplate = jdbc;
            this.targetQuotationId = targetQuotationId;
        }

        @Override
        public void lockTicket(long ticketId) {
            super.lockTicket(ticketId);
            jdbcTemplate.update("""
                UPDATE sales.quotation SET doc_status = 'SUPERSEDED', updated_at = now()
                 WHERE quotation_id = :id AND origin = 'DEAL_DIRECT'
                """, java.util.Map.of("id", targetQuotationId));
        }
    }

    /**
     * Pins the ticket lock {@link DealQuotationService#approve} takes before the sweep: deleting
     * the {@code quotations.lockTicket(...)} call at approve()'s top would leave every other test
     * in this class green, because this hand-wired suite calls
     * services with {@code new} and no Spring transaction proxy, so a bare test call's {@code
     * pg_advisory_xact_lock} is released the instant its OWN statement finishes, not held for the
     * whole method -- there was nothing for it to serialize against. This test wraps a REAL {@link
     * DealQuotationService} in {@link #transactional} (a genuine {@code @Transactional} AOP proxy,
     * same device as {@code PricingDecisionIntegrationTest#approveConcurrently_exactlyOneWins...})
     * so the lock is actually held for the transaction's full duration, and forces the two
     * transactions to GENUINELY OVERLAP with a one-sided {@link Thread#sleep} injected into a
     * subclassed repository right after its own compare-and-set UPDATE (still inside the open,
     * uncommitted transaction) -- not a mutual rendezvous latch, deliberately: a latch that
     * requires BOTH sides to arrive before either proceeds would DEADLOCK the correct (locked)
     * code path, where the second caller cannot even start its own CAS until the first's whole
     * transaction (lock included) has committed. A plain one-sided sleep has no such hazard: with
     * the lock present it only pads an already-serial execution; with the lock ABSENT it is what
     * guarantees the window where each transaction's own sweep runs BEFORE the other's uncommitted
     * approval becomes visible -- which is precisely the failure mode a stripped lock would let
     * through under real concurrency (both callers' compare-and-set targets are DIFFERENT rows, so
     * neither would ever throw; the two could otherwise simply race to both end APPROVED).
     *
     * <p>Mutation-checked: with {@code lockTicket} commented out of {@code approve()}, this test
     * fails every run -- both rows APPROVED, never a false pass. The one-sided sleep makes the
     * race deterministic rather than merely probable.
     */
    @Test
    void approveConcurrently_onSameTicket_exactlyOneEndsApproved_theLockSerialisesTheOtherAfterCommit() throws Exception {
        DealQuotationDto first = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto pending1 = quotationService.submit(first.id(), salesActor);
        DealQuotationDto second = createAlongsideLive(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto pending2 = quotationService.submit(second.id(), salesActor);
        assertThat(pending1.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(pending2.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        DealQuotationRepository slowRepository = new SlowApproveDealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        NotificationEmailService slowApprovalMailer =
            new NotificationEmailService(new CapturingMailer(), new BrandAssets(), "", "", "https://portal.test");
        DealQuotationService slowService = new DealQuotationService(slowRepository, tickets, customers, contacts,
            notifications, slowApprovalMailer, new QuotationRenderer(), employeeAuth,
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        DealQuotationService transactionalSlowService = transactional(slowService);

        Callable<DealQuotationDto> approveFirst = () ->
            transactionalSlowService.approve(pending1.id(), new ApproveRequest(null), salesManagerActor);
        Callable<DealQuotationDto> approveSecond = () ->
            transactionalSlowService.approve(pending2.id(), new ApproveRequest(null), salesManagerActor);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<DealQuotationDto> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<DealQuotationDto> f1 = executor.submit(approveFirst);
            Future<DealQuotationDto> f2 = executor.submit(approveSecond);
            for (Future<DealQuotationDto> f : List.of(f1, f2)) {
                try {
                    successes.add(f.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Both calls are expected to SUCCEED (neither DB constraint nor a re-check throws here -- each targets its
        // OWN, distinct PENDING_APPROVAL row, so both compare-and-sets succeed regardless of ordering).
        assertThat(failures).as("both approve() calls should succeed: %s", failures).isEmpty();
        assertThat(successes).hasSize(2);

        long approvedCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT' AND doc_status = 'APPROVED'
            """, java.util.Map.of("ticketId", ticketId), Long.class);
        assertThat(approvedCount)
            .as("exactly one DEAL_DIRECT quotation on this ticket may be APPROVED after both approvals land")
            .isEqualTo(1L);
        long supersededCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT' AND doc_status = 'SUPERSEDED'
            """, java.util.Map.of("ticketId", ticketId), Long.class);
        assertThat(supersededCount).isEqualTo(1L);
    }

    /** DealQuotationRepository#approve, but sleeping AFTER its own compare-and-set UPDATE -- see
     * #approveConcurrently_onSameTicket_exactlyOneEndsApproved_theLockSerialisesTheOtherAfterCommit's own Javadoc
     * for why this is a one-sided sleep, not a mutual rendezvous latch. */
    private static final class SlowApproveDealQuotationRepository extends DealQuotationRepository {
        SlowApproveDealQuotationRepository(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                                           CatalogRepository catalog) {
            super(jdbc, catalog);
        }

        @Override
        public int approve(long quotationId, long approverId, String note, LocalDate validityDate) {
            int rows = super.approve(quotationId, approverId, note, validityDate);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while widening the concurrent-approve race window", e);
            }
            return rows;
        }
    }


    // ── resubmitting a ตีกลับ'd draft mints a NEW revision ──
    //
    // "ตีกลับ = แก้ใบเดิม เลขไม่เปลี่ยน (this row, this number, back to DRAFT) — but resubmitting
    // it, once sales has fixed it, ออกใบใหม่ เลขเปลี่ยน (a new row/number), the SAME way revising
    // an already-APPROVED document always has." reject() itself leaves the number alone (see the
    // test above); everything below pins what submit() does to a DRAFT that carries a rejection.

    // ── a revision's submit must read as a REVISION to
    // sales_manager/ceo, not the identical "รออนุมัติ" text a first-time submit sends ──

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Item completeness rule (inline-deal-spec.md)
    // ─────────────────────────────────────────────────────────────────────────────────────

    // ── submit requires a lead time on every TILE row ────────────────

    // ── an ENTERED lead-time range must be min>=1, max>=min ────

    // ── a tiny AREA quantity rounds up to one piece ─────────────────────────────────────────

    /** 0.01 sqm × 2.78 = 0.0278 rounds UP to 1 piece ("ปัดขึ้นทุกกรณี"), so
     * the row is accepted. (The zero-piece guard in {@code DealQuotationService} stays as a backstop;

    // ── PIECES wastage must be a whole number ────────────────

    // ── CREDIT remainder needs at least 1 credit day ──────────

    /** The submit-time half: a CREDIT-remainder draft with creditDays still blank may not advance past DRAFT. */
    @Test
    void submit_refusesACreditRemainderWithNoCreditDays() {
        DealQuotationDto created = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", null, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))).withRecipientType("OWNER"),
            salesActor);

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
    }


    // ─────────────────────────────────────────────────────────────────────────────────────
    // The "แก้" bucket (needsRework) + per-status counts, owner-scoped
    // ─────────────────────────────────────────────────────────────────────────────────────

    // Authz — wrong-way-round (the caller must NOT reach what they should not)

    @Test
    void sales_cannotUpdateAnotherRepsQuotation() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertForbidden(() -> quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("999.00", 1))), otherSalesActor));
    }

    @Test
    void sales_cannotSubmitAnotherRepsQuotation() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertForbidden(() -> quotationService.submit(created.id(), otherSalesActor));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Read-side authz: get/listForTicket/search/renderPdf, wrong-way-round.
    // Mutation-checked by deleting the owner scope in DealQuotationService#requireViewAccess AND
    // the ownerFilter branch in #search: these tests (and only these) go red.
    //
    // LIST_SEE_ALL_ROLES is mutation-checked separately, by editing #listOwnerScope to
    // `return null;` (always "see all"): ONLY the global-list scope tests below
    // (search_seesOnlyOwnDeals, listSeeAll_*, importAndAccount_..._butGlobalListScopedToOwn,
    // grantHolder_search...) go red.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void sales_cannotGetAnotherRepsQuotation() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertForbidden(() -> quotationService.get(created.id(), otherSalesActor));
    }

    @Test
    void sales_cannotListForAnotherRepsTicket() {
        quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        // ticketId belongs to salesRepId, not otherSalesId.
        assertForbidden(() -> quotationService.listForTicket(ticketId, otherSalesActor));
    }

    @Test
    void sales_search_seesOnlyOwnDeals_zeroRowsForAnotherRepsQuotation() {
        DealQuotationDto own = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto others = quotationService.create(otherTicketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), otherSalesActor);

        List<DealQuotationDto> results = quotationService.search(null, false, salesActor);
        assertThat(results).extracting(DealQuotationDto::id).contains(own.id());
        assertThat(results).extracting(DealQuotationDto::id).doesNotContain(others.id());
    }

    /** Wrong-way-round: import created NOTHING (salesActor owns
     * ticketId), so the global list is empty for import even though salesActor's quotation
     * exists. Distinct from {@link #importAndAccount_readIndividualDeals_butGlobalListScopedToOwn}
     * above, which proves the SAME row is still readable individually. */
    @Test
    void listSeeAllRoles_import_doesNotSeeAnotherRepsQuotationOnTheGlobalList() {
        quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        assertThat(quotationService.search(null, false, importActor)).isEmpty();
    }

    /** Same guard as above, for {@code account}. */
    @Test
    void listSeeAllRoles_account_doesNotSeeAnotherRepsQuotationOnTheGlobalList() {
        quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        assertThat(quotationService.search(null, false, accountActor)).isEmpty();
    }

    /** The SAME reads are refused to account on a deal outside its list scope (below S10),
     * on every read path (get / list-for-ticket / PDF); import is unaffected. */
    @Test
    void account_isRefusedADealQuotationBelowS10_onEveryReadPath_importIsNot() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        setSalesStage(ticketId, "NEGOTIATION");

        assertForbidden(() -> quotationService.get(created.id(), accountActor));
        assertForbidden(() -> quotationService.listForTicket(ticketId, accountActor));
        assertForbidden(() -> quotationService.renderPdf(created.id(), accountActor));
        assertThat(quotationService.get(created.id(), importActor).id()).isEqualTo(created.id());
    }

    private static final java.util.Set<String> PRICE_INTERNAL_KEYS = java.util.Set.of(
        "unitPrice", "discountPct", "specialPriceSqm", "adjustmentPct", "adjustmentAmount", "catalogPriceId",
        "specialPriceLine", "calculationLine", "priceMode", "ceoPriceMode", "priceModeChangedFromCeo",
        "priceChangedFromCeo", "itemsRemovedFromCeoCount", "removedCeoItems",
        "ceoListUnitPrice", "ceoDiscountPct", "ceoSpecialPriceSqm", "ceoDirectNetPrice", "ceoNetUnitPrice");

    private com.fasterxml.jackson.databind.JsonNode jsonOf(Object dto) {
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).valueToTree(dto);
    }

    private void assertPriceInternalsEmptied(com.fasterxml.jackson.databind.JsonNode node, String path) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                var v = e.getValue();
                if (PRICE_INTERNAL_KEYS.contains(e.getKey())) {
                    boolean empty = v.isNull() || (v.isBoolean() && !v.asBoolean()) || (v.isNumber() && v.asInt() == 0)
                        || (v.isArray() && v.isEmpty());
                    assertThat(empty).as(path + "." + e.getKey() + " must be emptied but was " + v).isTrue();
                }
                assertPriceInternalsEmptied(v, path + "." + e.getKey());
            });
        } else if (node.isArray()) {
            node.forEach(n -> assertPriceInternalsEmptied(n, path + "[]"));
        }
    }

    private void assertNetFieldsKept(com.fasterxml.jackson.databind.JsonNode quotation) {
        assertThat(quotation.get("number").asText()).isNotBlank();
        assertThat(quotation.get("grandTotal").isNull()).isFalse();
        assertThat(quotation.get("items")).isNotEmpty();
        quotation.get("items").forEach(i -> {
            assertThat(i.get("netUnitPrice").isNull()).isFalse();
            assertThat(i.get("lineAmount").isNull()).isFalse();
        });
    }

    /** Ruling 3 covers EVERY origin: a DEAL_DIRECT quotation read by an in-scope account is redacted too. */
    @Test
    void account_readingADealDirectQuotation_getsNetPricesButNoListPriceOrDiscount() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        setSalesStage(ticketId, "PROCUREMENT");
        setDocStatus(created.id(), "APPROVED"); // account sees only quotations that went to the customer

        var viaGet = jsonOf(quotationService.get(created.id(), accountActor));
        var viaList = jsonOf(quotationService.listForTicket(ticketId, accountActor));

        assertPriceInternalsEmptied(viaGet, "get");
        assertPriceInternalsEmptied(viaList, "list");
        assertNetFieldsKept(viaGet);
        assertNetFieldsKept(viaList.get(0));
        // regression: the owning rep still gets the list price
        assertThat(jsonOf(quotationService.get(created.id(), salesActor)).get("items").get(0).get("unitPrice").isNull())
            .isFalse();
    }

    private void setDocStatus(long quotationId, String status) {
        jdbc.update("UPDATE sales.quotation SET doc_status = :s WHERE quotation_id = :q",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("s", status).addValue("q", quotationId));
    }

    private void setSalesStage(long ticketId, String stage) {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("s", stage).addValue("id", ticketId));
    }

    @Test
    void importAccountQcEmployee_cannotCreate() {
        for (UserPrincipal disallowed : List.of(importActor, accountActor, qcActor, employeeActor)) {
            assertForbidden(() -> quotationService.create(ticketId,
                upsertRequest(List.of(sampleItem("100.00", 10))), disallowed));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — the canCreateQuotation grant
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** A grant on an INACTIVE employee must not work — canCreateQuotation filters is_active, exactly like isAdmin. */
    @Test
    void grantOnInactiveEmployee_isForbidden() {
        grantQuotationCapability(qcUserId);
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id",
            java.util.Map.of("id", qcUserId));

        assertForbidden(() -> quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), qcActor));
    }

    private void grantQuotationCapability(long employeeId) {
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            java.util.Map.of("id", employeeId));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Remark 7's second กำหนดยืนยันราคา variant (an exact validity_until date, gated on
    // the document having special pricing).
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** withValidity — the ONE wither this file's UpsertDealQuotationRequest builders lack, added for V178 so every
     * test above can start from an existing request (built by #upsertRequestWithMode) and set just the two new
     * fields. */
    private UpsertDealQuotationRequest withValidity(UpsertDealQuotationRequest base, String validityMode,
                                                     LocalDate validityUntil) {
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(),
            base.offerDate(), base.depositPercent(), base.remainderMode(), base.creditDays(),
            base.validityDays(), validityMode, validityUntil, base.customerNotes(), base.priceMode(),
            base.documentLanguage(), base.currency(), base.items()).withRecipientType("OWNER");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ" and "ไม่รับมัดจำ" — real-DB coverage through the real
    // service.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Wither — sets depositPercent/remainderMode/creditDays/fullPaymentTerm together,
     * since {@code DealQuotationService} resolves the four as one unit (see
     * #resolveFullPaymentTerm's own Javadoc). */
    private UpsertDealQuotationRequest withDepositAndTerm(UpsertDealQuotationRequest base,
            Integer depositPercent, String remainderMode, Integer creditDays, String fullPaymentTerm) {
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(),
            base.offerDate(), depositPercent, remainderMode, creditDays,
            base.validityDays(), base.validityMode(), base.validityUntil(), base.customerNotes(),
            base.priceMode(), base.documentLanguage(), base.currency(), base.printedByDisplayId(),
            base.salesRepDisplayId(), base.projectName(), base.omitContactHonorific(), fullPaymentTerm,
            base.items()).withRecipientType("OWNER");
    }

    @Test
    void create_zeroDeposit_clearsRemainderModeAndCreditDays_andStoresTheChosenTerm() {
        DealQuotationDto created = quotationService.create(ticketId,
            withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))),
                0, "CREDIT", 45, WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY),
            salesActor);
        assertThat(created.depositPercent()).isEqualTo(0);
        assertThat(created.remainderMode()).isNull();
        assertThat(created.creditDays()).isNull();
        assertThat(created.fullPaymentTerm()).isEqualTo(WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY);
    }

    /** A rep who unticks "ไม่รับมัดจำ" (re-enters an ordinary percentage) can never leave a stale term attached,
     * EVEN IF the request still sends one — wrong-way-round: the field the request carries is not the field the
     * stored row ends up with. */
    @Test
    void create_nonZeroDeposit_forcesFullPaymentTermNull_evenIfTheRequestSendsOne() {
        DealQuotationDto created = quotationService.create(ticketId,
            withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))),
                30, "CREDIT", 30, WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY),
            salesActor);
        assertThat(created.depositPercent()).isEqualTo(30);
        assertThat(created.fullPaymentTerm()).isNull();
        assertThat(created.remainderMode()).isEqualTo("CREDIT");
        assertThat(created.creditDays()).isEqualTo(30);
    }

    /** Wrong-way-round: a zero-deposit DRAFT is SAVEABLE with no term chosen yet. */
    @Test
    void submit_zeroDepositWithNoTermChosen_isBadRequest() {
        DealQuotationDto created = quotationService.create(ticketId,
            withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))), 0, null, null, null),
            salesActor);
        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("เงื่อนไขการชำระเงิน");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Contact-update DRAFT snapshot refresh ("แก้หรือเพิ่ม Email ผู้สั่งซื้อภายหลังไม่ได้") —
    // CustomerService#updateContact / DealQuotationRepository#refreshDraftContactSnapshot.
    // Written wrong-way-round per CLAUDE.md ("Permission changes must ship evidence" — same
    // discipline applied to this scope guard): negative cases (a row that must NOT move) come first. Every call goes through
    // #transactional(customerService) rather than the bare field, so #updateContact's own
    // @Transactional is exercised through a real AOP proxy instead of being inert — see that
    // helper's own Javadoc and the double-afterCommit-defer test above for why a bare `new`-wired
    // service would prove nothing about the annotation.
    // ─────────────────────────────────────────────────────────────────────────────────────

    // Helpers

    /** Slice 2's N6 allows one live direct quotation per deal; the tests below that put several on one deal
     * (legacy/revision shapes) create through LegacyDirectQuotationFixtures. */
    private DealQuotationDto createAlongsideLive(long ticketIdParam, UpsertDealQuotationRequest request,
                                                 UserPrincipal actor) {
        return LegacyDirectQuotationFixtures.createAlongsideLive(jdbc, quotationService, ticketIdParam, request, actor);
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    private UpsertDealQuotationRequest upsertRequest(List<ItemInput> items) {
        return upsertRequest(null, items);
    }

    /** {@code contactId} null = no contact picked: a blank snapshot (the deal's contact is never
     * inherited). Pass {@code contact.id()} when a test needs one on the row. */
    private UpsertDealQuotationRequest upsertRequest(Long contactId, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(contactId, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            "หมายเหตุทดสอบ", items).withRecipientType("OWNER");
    }

    private ItemInput sampleItem(String unitPrice, int pieces) {
        return sampleItem(null, unitPrice, pieces);
    }

    /** A location heading is what actually exercises {@code QuotationRenderer}'s
     * underline-style cache — see {@link #renderPdfAndXlsx_nonEmptyBytes}'s own comment. */
    private ItemInput sampleItem(String locationLabel, String unitPrice, int pieces) {
        // Item completeness rule (inline-deal-spec.md): thicknessMm and
        // piecesPerBox are REQUIRED on create/update.
        // DealQuotationService#resolveSqmPerPiece does not parse sizeText at all (see its own
        // Javadoc), so this fixture supplies sqmPerPiece EXPLICITLY (60x60cm = 0.36 sqm/piece).
        // piecesPerBox=1 (not e.g. 4) deliberately -- box rounding is a no-op at box size 1
        // (ceil(n/1)*1 == n for every n), so every dollar-amount assertion across this large
        // file is unaffected by box rounding; a caller that specifically wants to exercise box rounding builds its own
        // ItemInput (see e.g. #incompleteItem_missingPiecesPerBox_isBadRequestNamingTheRowAndField's
        // ItemInputBuilder).
        return new ItemInput(locationLabel, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, pieces, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal(unitPrice), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** Unlike #createEmployee, which leaves positionTh null, this sets a real position containing "ผู้จัดการ" --
     * NotificationRepository#notifyByRole's own sales_manager predicate keys on hr.position.name_th, NOT the
     * employee's display name, so an employee with no position can never match it. */
    private long createSalesManager(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SALES", "ฝ่ายขาย", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

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

    /** Records every sendWithAttachment call as [to, subject] — the "capturing mailer" the report's evidence for
     * the approval email cites. */
    private static final class CapturingMailer implements Mailer {
        final List<String[]> attachmentsSent = new ArrayList<>();
        // The branded HTML notification path (NotificationEmailService#send -> Mailer#sendHtml) -- distinct from
        // attachmentsSent above, which is the dead PDF-attachment path's own mailer call. {to, subject} per send, in
        // call order.
        final List<String[]> htmlSent = new ArrayList<>();

        @Override
        public void send(String to, String subject, String body) {}

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody, List<InlineImage> inlineImages) {
            htmlSent.add(new String[]{to, subject});
        }

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            attachmentsSent.add(new String[]{to, subject});
        }

        @Override
        public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Quotation v3 — price modes, PLAIN and ADJUSTMENT rows.
    //
    // Every negative case below is written WRONG-WAY-ROUND per CLAUDE.md: it asserts what the
    // service must REFUSE, not what it accepts. These run through the REAL DealQuotationService
    // against a REAL Postgres (AbstractPostgresIntegrationTest), so they prove the rule survives
    // into the stored row, not merely that a branch was taken.
    // ═══════════════════════════════════════════════════════════════════════════════════════

    // ── Type-aware price validation, wrong-way-round ────────────────────────────────────────

    // ── submit / revision over the new row types ────────────────────────────────────────────

    // ══════════════════════════════════════════════════════════════════════════════════════
    // The UPDATE path.
    //
    // ⚠️ Replacing `resolvePriceMode(request.priceMode())` at DealQuotationService#update with a
    // hardcoded PRICE_MODE_NET must turn these red: otherwise the suite stays green while
    // silently repricing every SPECIAL_SQM and DIRECT_NET document a rep edits. Every test in
    // this block runs through the REAL service against a REAL Postgres.
    //
    // Mutations that must each go red:
    //   - update()'s price mode hardcoded to PRICE_MODE_NET: the keepsTheStored*Mode update tests,
    //     update_withAnExplicitPriceMode_switchesTheDocumentBothWays and
    //     getThenPutVerbatim_roundTripsASpecialSqmDocument.
    //   - the ADJUSTMENT "cannot supply a price" throw restored:
    //     adjustmentRow_withARepSuppliedPrice_ignoresIt_andStillDerivesTheAmount and both
    //     getThenPutVerbatim_* round-trips.
    //   - the negative-subtotal guard disabled: twoAdjustments_drivingTheSubtotalNegative_areRejected,
    //     aFlatAdjustmentLargerThanTheDocument_isRejected, update_drivingTheSubtotalNegative_isRejected.
    //   - each of the three @Digits removed in turn (DealQuotationRequests): exactly the matching
    //     case in DealQuotationRequestsValidationTest.
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * ⚠️ A rule the business has not confirmed; it falls out of the "two adjustments do not
     * compound" question above.
     *
     * <p>Because adjustments do not compound, each one takes the FULL non-adjustment base — so two
     * 60% rows take 120% and the document ends up owing the customer money. Refused.
     */
    @Test
    void twoAdjustments_drivingTheSubtotalNegative_areRejected() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),          // 1,000
                adjustmentPctItem("60", null),     //  -600
                adjustmentPctItem("60", null))),   //  -600  => -200
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ติดลบ");
    }

    // ── Quotation arithmetic reconciliation — service-level reproductions of five real printed
    // ── documents, through create()+get() against real Postgres.
    // ── English per-sqm boxes are pinned in DealQuotationEnglishIntegrationTest; not duplicated
    // ── here. Plain-row totals identical in shape to the cases below, and pieces-only documents,
    // ── have no service-level equivalent (pieces-only is pinned at the WastageCalculator layer by
    // ── WastageCalculatorTest/QuotationGoldenDocumentsTest). ────────

    // ── "Sell loose pieces" ─────────────────────────────────

    // ── v3 fixtures ────────────────────────────────────────────────────────────────────────

    private UpsertDealQuotationRequest upsertRequestWithMode(String priceMode, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            "หมายเหตุทดสอบ", priceMode, items).withRecipientType("OWNER");
    }

    private ItemInput adjustmentPctItem(String pct, LocalDate deadline) {
        return adjustmentItem(new BigDecimal(pct), null, deadline, null);
    }

    private ItemInput adjustmentItem(BigDecimal pct, BigDecimal flatAmount, LocalDate deadline,
                                     String description) {
        return new ItemInput(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, description, null, null, null, null,
            pct, deadline, flatAmount);
    }
}
