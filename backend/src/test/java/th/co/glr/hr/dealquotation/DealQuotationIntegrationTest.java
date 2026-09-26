package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ChromiumPdfPrinter;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.CustomerService;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RejectRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationDto;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesMailRecipientRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.notification.SalesNotificationMailRouter;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB acceptance + authz + concurrency coverage for Quotation v2 (direct deal quotation,
 * V165) — see docs/sales/quotation-v2-plan.md. Copies the hand-wiring style of {@code
 * customerquotation/CustomerQuotationIntegrationTest} (its own {@code @BeforeEach}/helpers).
 */
class DealQuotationIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private TicketRepository tickets;
    private TicketService ticketService;
    private CustomerRepository customers;
    private ContactRepository contacts;
    private ProjectRepository projects;
    private NotificationRepository notifications;
    private DealQuotationRepository quotationRepository;
    private DealQuotationService quotationService;
    private EmployeeAuthRepository employeeAuth;
    // Quotation-editor bug fix (2026-09-16) — see ContactUpdateRefreshesDraftQuotations* tests
    // below. Wrapped in #transactional(...) at each call site, not here, so its own @Transactional
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
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        contacts = new ContactRepository(jdbc);
        projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        // pricingRequests (last arg) is dead-deal-cascade-only (markLost/cancel) -- unused by
        // create(), the only TicketService method this test calls.
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), null, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        mailer = new CapturingMailer();
        NotificationEmailService approvalMailer =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.test");
        employeeAuth = new EmployeeAuthRepository(jdbc);
        EmployeeSignatureRepository signatureRepository = new EmployeeSignatureRepository(jdbc);
        // Exercise the Chromium engine end-to-end through the service wherever a Chromium can be
        // launched; elsewhere the default LibreOffice engine keeps the PDF assertions meaningful.
        QuotationRenderer quotationRenderer = new QuotationRenderer();
        if (ChromiumPdfPrinter.isAvailable()) {
            quotationRenderer.setPdfRenderer(QuotationRenderer.PDF_RENDERER_CHROMIUM);
        }
        quotationService = new DealQuotationService(quotationRepository, tickets, customers, contacts, notifications,
            approvalMailer, quotationRenderer, employeeAuth, signatureRepository, new CatalogRepository(jdbc),
            "https://portal.test",
            // app.quotation.bank-block-line1..3 — empty here, so the English document prints the
            // proforma-invoice line. DealQuotationEnglishFormTest covers the configured block.
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
        // ผู้สั่งซื้อ (owner feedback F2): every deal here carries a contact, because create/update/
        // submit now REFUSE a quotation without one -- the no-contact cases build their own ticket
        // (see #create_withoutAnyResolvableContact_isBadRequest).
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Acceptance
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void acceptanceScenario_createUpdateSubmitApprove() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(created.subtotalAmount()).isEqualByComparingTo("1000.00");
        assertThat(created.vatAmount()).isEqualByComparingTo("70.00");
        assertThat(created.grandTotal()).isEqualByComparingTo("1070.00");
        assertThat(created.salesRepId()).isEqualTo(salesRepId); // = ticket.createdById
        assertThat(created.createdById()).isEqualTo(salesRepId);
        assertThat(created.customerName()).isEqualTo("บริษัท Deal Quotation จำกัด");

        DealQuotationDto updated = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("200.00", 5))), salesActor);
        assertThat(updated.subtotalAmount()).isEqualByComparingTo("1000.00"); // 200 * 5
        assertThat(updated.items()).hasSize(1);

        DealQuotationDto submitted = quotationService.submit(updated.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(submitted.submittedAt()).isNotNull();

        // Submitting notified sales_manager + ceo.
        assertThat(notifications.findByEmployeeId(salesManagerId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_SUBMITTED"));
        assertThat(notifications.findByEmployeeId(ceoId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_SUBMITTED"));
        // Owner request (2026-09-15): the submitting rep gets their own confirmation too, not just
        // the two approvers -- same event kind, but the message reads from the rep's own side ("ส่ง
        // ... แล้ว" rather than "... รอการอนุมัติ").
        assertThat(notifications.findByEmployeeId(salesRepId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_SUBMITTED") && n.message().contains("ส่งใบเสนอราคา"));

        DealQuotationDto approved = quotationService.approve(submitted.id(), new ApproveRequest("อนุมัติแล้ว"), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(approved.approvedById()).isEqualTo(salesManagerId);
        assertThat(approved.approvalNote()).isEqualTo("อนุมัติแล้ว");
        assertThat(approved.validityDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(30));
        // Owner feedback F8 (2026-09-10): the header/DTO date is the CREATED date, for every
        // status -- it used to follow approvedAt once approved. Asserted wrong-way-round too: the
        // approval must NOT move it, so an approval on a later day cannot re-date the document.
        assertThat(approved.quotationDate()).isEqualTo(approved.createdAt()
            .atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalDate());
        assertThat(approved.quotationDate()).isEqualTo(submitted.quotationDate())
            .isEqualTo(updated.quotationDate());

        // In-app notification to the rep (== creator here, so exactly one row, not two).
        assertThat(notifications.findByEmployeeId(salesRepId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_APPROVED"));

        // Owner request (2026-09-15): approval used to ALSO send a second, plain-text email with
        // the PDF attached (via #sendApprovalEmail's own mailer) alongside the branded HTML one
        // notifyRepAndCreator already sends — the same recipient got two emails for one approval.
        // That second send is now gone; only the in-app row (and its own HTML email, asserted via
        // notifyRepAndCreator above) fires.
        assertThat(mailer.attachmentsSent).isEmpty();
    }

    /**
     * Regression test for a double-{@code afterCommit}-defer bug (found 2026-09-15): {@code
     * submitDraftRow} used to wrap {@code notifySubmitted(submitted)} in this class's OWN {@code
     * afterCommit()} helper, on top of the fact that {@code notifyByRoleAtLink}/{@code
     * notifyEmployeeAtLink} already defer their OWN mail send until commit via {@code
     * notification.AfterCommit.run()} inside {@link SalesNotificationMailRouter}. That is a SECOND
     * {@code TransactionSynchronization} registered from inside the FIRST one's own {@code
     * afterCommit()} callback — proven (a standalone repro against this repo's exact Spring 7.0.8)
     * to be silently dropped: the nested synchronization's {@code afterCommit()} never runs, even
     * though {@code isSynchronizationActive()} still reads {@code true} at the point it is
     * registered. So EVERY mail {@code notifySubmitted()} sends — sales_manager, ceo, and the rep's
     * own submission confirmation — was silently swallowed, while the in-app bell rows (a plain,
     * synchronous {@code INSERT} that simply joins the ambient transaction, no manual deferral
     * needed) still landed fine.
     *
     * <p>The {@code @BeforeEach}-wired {@code notifications}/{@code quotationService} use {@code
     * SalesNotificationMailer.NO_OP}, which structurally cannot see this bug — {@code NO_OP} never
     * defers at all, so double-deferring on top of it is a no-op on a no-op. This test wires the
     * REAL {@link SalesNotificationMailRouter} (backed by the shared {@link CapturingMailer}) so a
     * reintroduced double-defer fails this test loudly instead of being invisible again, exactly as
     * it was invisible to {@link #acceptanceScenario_createUpdateSubmitApprove} above despite that
     * test asserting the in-app rows correctly the whole time.
     *
     * <p><b>Must go through {@link #transactional}, not a bare {@code new DealQuotationService}
     * call.</b> This whole suite hand-wires services with {@code new} (see {@code
     * AbstractPostgresIntegrationTest}'s own Javadoc on {@code transactional}), so a plain call has
     * NO Spring AOP proxy and {@code @Transactional} is inert — every {@code afterCommit}-style
     * defer then hits its own "no transaction active" fallback and just runs the action inline, mail
     * included, regardless of whether the double-defer bug is present or fixed. Confirmed by first
     * getting this test to pass identically against BOTH the buggy and the fixed service when called
     * bare — a false-positive green that proved nothing. Wrapping in {@code transactional(...)}
     * gives a REAL {@code PlatformTransactionManager}-driven commit, which is what actually
     * distinguishes the two: bare-called, the bug is unreachable; proxied, it reproduces.
     */
    @Test
    void submit_actuallyMailsSalesManagerCeoAndRep_throughTheRealMailRouter() {
        NotificationEmailService realEmailService =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.test");
        NotificationRepository realNotifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(realEmailService, new SalesMailRecipientRepository(jdbc)));
        DealQuotationService realQuotationService = transactional(new DealQuotationService(quotationRepository,
            tickets, customers, contacts, realNotifications, realEmailService, new QuotationRenderer(), employeeAuth,
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test", "", "", ""));

        DealQuotationDto created = realQuotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        realQuotationService.submit(created.id(), salesActor);

        assertThat(mailer.htmlSent).as("sales_manager, ceo, and the rep's own confirmation each sent mail")
            .hasSize(3);
        // "rarm@glr.co.th" is SalesNotificationMailRouter.CEO_MAILBOX -- package-private there
        // (th.co.glr.hr.notification), not reachable from this package, so hardcoded verbatim.
        assertThat(mailer.htmlSent).extracting(sent -> sent[0]).containsExactlyInAnyOrder(
            "sales-manager-dq@glr.co.th", "rarm@glr.co.th", "sales-dq@glr.co.th");
        // The subject is the fixed TICKET_EVENT_TITLES entry for DEAL_QUOTATION_SUBMITTED
        // ("ใบเสนอราคารออนุมัติ"), the same for all three recipients regardless of each call's own
        // message text -- NotificationRepository#ticketEventTitle, prefixed "[GL&R HR] " by
        // NotificationEmailService#send.
        assertThat(mailer.htmlSent).allSatisfy(sent -> assertThat(sent[1]).contains("ใบเสนอราคารออนุมัติ"));
    }

    /**
     * Owner feedback F8 (2026-09-10): "for วันที่ at the top of the page it should be the date it
     * was created by the sale." Written wrong-way-round on the only case that can tell the two
     * rules apart — a document CREATED on one day and APPROVED on another. A fresh integration run
     * creates and approves within the same minute, so the create date is backdated in the DB
     * first; the old rule (approved date, else today) would print TODAY, the new one prints the
     * backdated day, on both the DTO and the printed B4 header cell.
     */
    @Test
    void quotationDateAndPrintedHeader_followTheCreatedDate_notTheApprovalDate() throws Exception {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        LocalDate createdOn = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).minusDays(5);
        jdbc.update("UPDATE sales.quotation SET issued_at = :d WHERE quotation_id = :id",
            java.util.Map.of("d", java.sql.Timestamp.from(
                    createdOn.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Bangkok")).toInstant()),
                "id", approved.id()));

        DealQuotationDto reread = quotationService.get(approved.id(), salesActor);
        assertThat(reread.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(reread.approvedAt().atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalDate())
            .as("still approved today — so the two rules genuinely disagree here")
            .isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")));
        assertThat(reread.quotationDate()).isEqualTo(createdOn);

        byte[] xls = quotationService.renderXlsx(approved.id(), salesActor);
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(xls))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            String header = sheet.getRow(3).getCell(1).getStringCellValue();
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
            assertThat(header)
                .as("B4 prints the created date (\"d MMMM BBBB\" in Thai)")
                .startsWith(createdOn.getDayOfMonth() + " ")
                .endsWith(String.valueOf(createdOn.getYear() + 543));
            assertThat(header).as("and NOT the approval date, which is today")
                .doesNotStartWith(today.getDayOfMonth() + " ");
        }
    }

    @Test
    void approve_supersedesParentOnlyWhenRevisionItselfReachesApproved() {
        DealQuotationDto parent = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        assertThat(quotationService.get(parent.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);

        DealQuotationDto revision = quotationService.createRevision(parent.id(), salesActor);
        assertThat(revision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(revision.parentQuotationId()).isEqualTo(parent.id());
        assertThat(revision.revisionNo()).isEqualTo(parent.revisionNo() + 1);
        // Owner feedback 2026-09-11: the parent's own number is now "{base}-1" (not bare), so the
        // child is "{base}-2" -- NOT "{parent.number()}-2" (which would double up to "{base}-1-2").
        String parentBase = DealQuotationRepository.baseNumber(parent.number(), parent.revisionNo());
        assertThat(revision.number()).isEqualTo(parentBase + "-" + revision.revisionNo());

        // Parent stays APPROVED (live/valid) while the revision is still a draft.
        assertThat(quotationService.get(parent.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);

        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto childApproved = quotationService.approve(revision.id(), new ApproveRequest(null), ceoActor);
        assertThat(childApproved.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // Only NOW does the parent become SUPERSEDED.
        assertThat(quotationService.get(parent.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void firstIssue_carriesTheRevisionSuffixFromTheStart() {
        // Owner feedback 2026-09-11 ("มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรกเป็น QT-2026-0014-1"):
        // the FIRST issued document is now "{base}-1", not a bare "{base}".
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.revisionNo()).isEqualTo(1);
        assertThat(created.number()).matches("QT-\\d{4}-\\d{4}-1");
        // The base sequence allocation itself is untouched -- the number minus its "-1" suffix is
        // still exactly the {@code QT-<year>-<4-digit seq>} shape nextQuotationCode() produces.
        String base = DealQuotationRepository.baseNumber(created.number(), created.revisionNo());
        assertThat(created.number()).isEqualTo(base + "-1");
        assertThat(base).matches("QT-\\d{4}-\\d{4}");
    }

    @Test
    void revisionNumbering_chainsOffTheOriginalBaseNumber_notTheImmediateParent() {
        DealQuotationDto rev1 = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        // rev1.number() is now "{base}-1" (owner feedback 2026-09-11) -- recover the bare base the
        // same way DealQuotationService#createRevision does, rather than assuming rev1.number()
        // IS the base (it no longer is).
        String base = DealQuotationRepository.baseNumber(rev1.number(), rev1.revisionNo());
        assertThat(rev1.number()).isEqualTo(base + "-1");

        DealQuotationDto rev2 = quotationService.createRevision(rev1.id(), salesActor);
        assertThat(rev2.number()).isEqualTo(base + "-2");
        quotationService.submit(rev2.id(), salesActor);
        quotationService.approve(rev2.id(), new ApproveRequest(null), salesManagerActor);

        DealQuotationDto rev3 = quotationService.createRevision(rev2.id(), salesActor);
        // Must be "{base}-3", never "{base}-1-2-3" or "{base}-2-3".
        assertThat(rev3.number()).isEqualTo(base + "-3");
        assertThat(rev3.revisionNo()).isEqualTo(3);
    }

    @Test
    void revisionOfALegacyBareNumberedQuotation_appendsMinus2_neverRewritesTheBareNumber_neverCollides() {
        // EXISTING ROWS MUST NOT BREAK (CLAUDE.md, owner feedback 2026-09-11): a quotation issued
        // BEFORE this change carries a BARE number at revisionNo 1 (no "-1"), and that number is
        // never rewritten by this change -- no migration touches sales.quotation.number. Simulate
        // that pre-change row by creating one normally (which now mints "{base}-1") and then
        // stripping the suffix directly in the DB, exactly as a row committed before this deploy
        // would already read.
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        String bareLegacyNumber = DealQuotationRepository.baseNumber(created.number(), created.revisionNo());
        jdbc.update("UPDATE sales.quotation SET number = :number WHERE quotation_id = :id",
            java.util.Map.of("number", bareLegacyNumber, "id", created.id()));

        DealQuotationDto legacy = quotationService.submit(created.id(), salesActor);
        assertThat(legacy.number()).isEqualTo(bareLegacyNumber);
        DealQuotationDto approvedLegacy =
            quotationService.approve(legacy.id(), new ApproveRequest(null), salesManagerActor);
        // The parent's own (bare) number is untouched by approve -- no rewrite of history.
        assertThat(approvedLegacy.number()).isEqualTo(bareLegacyNumber);

        DealQuotationDto revision = quotationService.createRevision(approvedLegacy.id(), salesActor);
        // "{bareNumber}-2", never "{bareNumber}-1-2" and never colliding with a fresh quotation's
        // own "{base}-1" (a different base entirely, since nextQuotationCode() never repeats).
        assertThat(revision.number()).isEqualTo(bareLegacyNumber + "-2");
        assertThat(revision.revisionNo()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-74 part 1 ("สร้างจากใบเดิม" / สั่งเหมือนเดิม) — clone an APPROVED quotation into a new,
    // INDEPENDENT draft.
    //
    // ⚠️ Owner ruling 2026-09-19 (INVERTS this section's own former header comment, which said
    // "the SOURCE stays APPROVED, never superseded, neither on clone creation nor once the clone
    // itself is submitted and approved" -- that second half is now FALSE): a deal may hold only
    // ONE APPROVED DEAL_DIRECT quotation at a time. Clone CREATION itself still leaves the source
    // untouched (still APPROVED) -- that half is unchanged, see the first test below -- but once
    // the CLONE ITSELF reaches APPROVED, DealQuotationService#approve's same-ticket sweep
    // supersedes the source then, exactly as approving a revision or a second, wholly independent
    // first-issue quotation would.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createReorder_clonesApprovedIntoNewDraft_withDerivedFromSet_noParentLink_itemsAndPictureCopied()
            throws Exception {
        // Picture must be attached while the row is still DRAFT (uploadItemPicture is DRAFT-only)
        // -- attach it BEFORE submit/approve, unlike createSubmittedApproved's shorthand.
        DealQuotationDto draft = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        long sourceItemId = draft.items().get(0).id();
        quotationService.uploadItemPicture(draft.id(), sourceItemId, pngFile(), "BELOW", salesActor);
        quotationService.submit(draft.id(), salesActor);
        DealQuotationDto source = quotationService.approve(draft.id(), new ApproveRequest(null), salesManagerActor);

        DealQuotationDto reorder = quotationService.createReorder(source.id(), salesActor);

        assertThat(reorder.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(reorder.ticketId()).isEqualTo(source.ticketId());
        // The core distinguishing fact: derivedFromQuotationId is set, parentQuotationId is NOT --
        // this is what keeps the source out of reach of supersede()/hasOpenRevision()/the "แก้"
        // bucket predicate, all of which key on parentQuotationId only.
        assertThat(reorder.derivedFromQuotationId()).isEqualTo(source.id());
        assertThat(reorder.parentQuotationId()).isNull();
        // Same shared {base}-{n} numbering family a revision would use.
        String base = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());
        assertThat(reorder.number()).isEqualTo(base + "-2");
        // Items copied verbatim, at NEW item ids (a fresh clone, not a shared row).
        assertThat(reorder.items()).hasSameSizeAs(source.items());
        assertThat(reorder.items().get(0).id()).isNotEqualTo(sourceItemId);
        assertThat(reorder.items().get(0).netUnitPrice()).isEqualByComparingTo(source.items().get(0).netUnitPrice());
        // GLA-75: the picture link is copied too (matched by seq), same as a revision's own copy.
        assertThat(reorder.items().get(0).hasPicture()).isTrue();

        // The source itself: untouched, still APPROVED.
        DealQuotationDto reloadedSource = quotationService.get(source.id(), salesActor);
        assertThat(reloadedSource.docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    /**
     * ⚠️ INVERTED (owner ruling 2026-09-19) — THE key test (per the task brief): the source must
     * now be SUPERSEDED once the clone is itself approved, not stay APPROVED as this test
     * originally asserted. Still APPROVED while the clone is merely DRAFT or PENDING_APPROVAL —
     * this is the entire point of the sweep firing at APPROVAL time, not at clone-creation time.
     * Mutation-checked: temporarily made {@code DealQuotationRepository#supersedeOtherApprovedOnTicket}
     * a no-op and confirmed this exact test (and the two-independent-quotations test below) went
     * red, then restored — see the PR body for the run.
     */
    @Test
    void createReorder_sourceIsSupersededOnceCloneIsApproved_butStaysApprovedWhileCloneIsOnlyDraftOrPending() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);

        DealQuotationDto reorder = quotationService.createReorder(source.id(), salesActor);
        // Still APPROVED while the clone is a fresh DRAFT.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);

        quotationService.submit(reorder.id(), salesActor);
        // Still APPROVED while the clone is merely PENDING_APPROVAL -- the sweep only fires on
        // the CLONE's own approval, not its submission.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);

        DealQuotationDto approvedReorder =
            quotationService.approve(reorder.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedReorder.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // The moment of truth: approving the CLONE now supersedes the SOURCE -- a deal may hold
        // only ONE APPROVED DEAL_DIRECT quotation, and the clone is the one just approved.
        DealQuotationDto reloadedSource = quotationService.get(source.id(), salesActor);
        assertThat(reloadedSource.docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // Exactly ONE quotation on this ticket is APPROVED -- the clone.
        assertThat(quotationRepository.findByTicket(source.ticketId()))
            .filteredOn(q -> q.docStatus().equals(QuotationStatus.APPROVED))
            .containsExactly(quotationService.get(approvedReorder.id(), salesActor));
        // Item 5 (Opus review): the sweep must actually WRITE the ticket_event row, exactly once,
        // with the Thai "ใบ X ถูกแทนที่ด้วย Y" wording -- not merely flip the status column.
        assertSupersededEventWrittenExactlyOnce(source.ticketId(), source.number(), approvedReorder.number());
    }

    /** Two wholly independent first-issue quotations on the SAME deal (no revision/clone lineage
     * at all -- the ancestor walk can never reach either one from the other, since neither
     * carries the other's id anywhere). Approving the second must supersede the first. */
    @Test
    void createReorder_twoIndependentFirstIssueQuotations_approvingTheSecondSupersedesTheFirst() {
        DealQuotationDto first = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        assertThat(first.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        DealQuotationDto second = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        assertThat(second.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        assertThat(quotationService.get(first.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertSupersededEventWrittenExactlyOnce(ticketId, first.number(), second.number());
    }

    /** A revision of the ORIGINAL source is approved while a CLONE of that same source is still
     * DRAFT -- then the clone is approved too. Exactly one APPROVED row must remain (the clone,
     * being the LAST one approved); the revision must be SUPERSEDED. Pins that the sweep applies
     * uniformly regardless of which lineage (ancestor-chain revision vs. derivedFrom clone) a
     * sibling belongs to. */
    @Test
    void createReorder_revisionOfSourceApprovedWhileCloneIsDraft_thenCloneApproved_exactlyOneApprovedRemains() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);

        DealQuotationDto clone = quotationService.createReorder(source.id(), salesActor);
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        assertThat(clone.docStatus()).isEqualTo(QuotationStatus.DRAFT);

        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto approvedRevision =
            quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedRevision.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        // The revision's approval supersedes the ORIGINAL source (its own parent)...
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // ...but the clone (unrelated lineage, still DRAFT) is untouched.
        assertThat(quotationService.get(clone.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);
        // Item 4/5 (Opus review): this is the MOST COMMON case (an ordinary revision superseding
        // its still-APPROVED parent) -- the sweep, not the silent ancestor walk, is what handles
        // it now that the sweep runs first, so it MUST produce exactly one event here too.
        assertSupersededEventWrittenExactlyOnce(source.ticketId(), source.number(), approvedRevision.number());

        quotationService.submit(clone.id(), salesActor);
        DealQuotationDto approvedClone =
            quotationService.approve(clone.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedClone.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // Exactly ONE APPROVED row remains on this ticket -- the clone, the LAST one approved.
        assertThat(quotationRepository.findByTicket(source.ticketId()))
            .filteredOn(q -> q.docStatus().equals(QuotationStatus.APPROVED))
            .containsExactly(quotationService.get(approvedClone.id(), salesActor));
        assertThat(quotationService.get(revision.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertSupersededEventWrittenExactlyOnce(source.ticketId(), revision.number(), approvedClone.number());
    }

    /** Item 5 (Opus review): the sweep must actually WRITE the {@code sales.ticket_event} row --
     * a skipped {@code addEvent} inside the sweep loop would survive every other assertion in
     * this class, since none of them read the event table. Asserts the exact kind and the exact
     * Thai wording, and that there is EXACTLY ONE such row for this old->new pair (not zero, not
     * duplicated). */
    private void assertSupersededEventWrittenExactlyOnce(long ticketIdParam, String oldNumber, String newNumber) {
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind AND message = :message
            """,
            java.util.Map.of("ticketId", ticketIdParam, "kind", TicketEventKind.DEAL_QUOTATION_SUPERSEDED,
                "message", "ใบ " + oldNumber + " ถูกแทนที่ด้วย " + newNumber),
            Long.class);
        assertThat(count).as("exactly one DEAL_QUOTATION_SUPERSEDED event for %s -> %s", oldNumber, newNumber)
            .isEqualTo(1L);
    }

    /** A quotation on a DIFFERENT deal must never be touched by this deal's approval sweep -- the
     * sweep is scoped to {@code ticketId}, not global. */
    @Test
    void createReorder_approvalSweep_neverTouchesAQuotationOnADifferentDeal() {
        DealQuotationDto onOtherTicket = createSubmittedApproved(otherTicketId, otherSalesActor, salesManagerActor);
        assertThat(onOtherTicket.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // Approve an unrelated quotation on THIS ticket -- must not touch the other deal at all.
        createSubmittedApproved(ticketId, salesActor, salesManagerActor);

        assertThat(quotationService.get(onOtherTicket.id(), otherSalesActor).docStatus())
            .isEqualTo(QuotationStatus.APPROVED);
    }

    /**
     * A PCR/customerquotation row on the SAME ticket ({@code origin} NULL, {@code
     * pricing_request_id} set — the legacy pricing-chain flow's own shape, V74/V165's own header)
     * must never be touched by this sweep: {@code supersedeOtherApprovedOnTicket}'s WHERE clause
     * filters {@code origin = 'DEAL_DIRECT'} explicitly, so a PCR-chain row is invisible to it
     * regardless of ticket or status. Inserted directly via SQL (no CustomerQuotationService
     * wiring in this suite) — the minimal legal row this table's own constraints allow.
     */
    @Test
    void createReorder_approvalSweep_neverTouchesAPricingChainCustomerQuotationRow() {
        jdbc.update("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, requested_by)
            VALUES (:code, :ticketId, 'BUYER', :requestedBy)
            """, java.util.Map.of("code", "PCR-TEST-" + ticketId, "ticketId", ticketId, "requestedBy", salesRepId));
        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.pricing_request WHERE request_code = :code",
            java.util.Map.of("code", "PCR-TEST-" + ticketId), Long.class);
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, doc_status, recipient_type,
                 quotation_revision_no, created_by, sales_rep_id, pricing_request_id, origin)
            VALUES
                (:ticketId, :number, :issuedBy, 0, 'THB', 'APPROVED', 'BUYER',
                 1, :issuedBy, :issuedBy, :pricingRequestId, NULL)
            """, java.util.Map.of("ticketId", ticketId, "number", "PCR-QT-TEST-" + ticketId,
                "issuedBy", salesRepId, "pricingRequestId", pricingRequestId));
        long pcrQuotationId = jdbc.queryForObject(
            "SELECT quotation_id FROM sales.quotation WHERE number = :number",
            java.util.Map.of("number", "PCR-QT-TEST-" + ticketId), Long.class);

        // Approve an ordinary DEAL_DIRECT quotation on the SAME ticket -- must not touch the
        // PCR-chain row above.
        createSubmittedApproved(ticketId, salesActor, salesManagerActor);

        String pcrStatusAfter = jdbc.queryForObject(
            "SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            java.util.Map.of("id", pcrQuotationId), String.class);
        assertThat(pcrStatusAfter).isEqualTo("APPROVED");
    }

    /** Cloning an already-SUPERSEDED source is refused (409) the same way a DRAFT/PENDING one is
     * -- item 3's own requirement: only the CLONE's approval supersedes the source, but once it
     * has, the source is no longer eligible to be cloned again either. */
    @Test
    void createReorder_ofASupersededSource_isConflict() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        DealQuotationDto clone = quotationService.createReorder(source.id(), salesActor);
        quotationService.submit(clone.id(), salesActor);
        quotationService.approve(clone.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        assertThatThrownBy(() -> quotationService.createReorder(source.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    /**
     * Opus review pin (item 6): {@code createReorder} used to check APPROVED only BEFORE taking
     * the ticket lock -- a caller reading "still APPROVED" and then losing a race for the lock to
     * a concurrent {@code approve()} elsewhere (which would supersede this exact source via the
     * one-approved-per-deal sweep) could mint a clone off a row that is no longer the deal's live
     * document by the time the lock is actually acquired. Reproduced DETERMINISTICALLY, without
     * real threads or timing: a repository subclass mutates the target row to SUPERSEDED INSIDE
     * {@code lockTicket()} itself, right after the (real) advisory lock is acquired but before
     * {@code createReorder}'s own post-lock re-check runs -- simulating exactly what a genuinely
     * concurrent {@code approve()} would have committed in that window. Asserts BOTH the 409 AND
     * that no clone row landed (CLAUDE.md: a thrown exception alone is not proof nothing was
     * written first).
     */
    @Test
    void createReorder_reCheckAfterLock_refusesIfSourceWasSupersededWhileWaitingForTheLock() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        long countBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);

        DealQuotationService supersedingService = serviceSupersedingOnLock(source.id());
        assertThatThrownBy(() -> supersedingService.createReorder(source.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        long countAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);
        assertThat(countAfter).as("no clone row inserted").isEqualTo(countBefore);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /** The identical gap and fix on {@link DealQuotationService#createRevision} -- item 6's own
     * "behaviour tightening on createRevision too", same message shape, same proof shape. */
    @Test
    void createRevision_reCheckAfterLock_refusesIfSourceWasSupersededWhileWaitingForTheLock() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        long countBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);

        DealQuotationService supersedingService = serviceSupersedingOnLock(source.id());
        assertThatThrownBy(() -> supersedingService.createRevision(source.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        long countAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);
        assertThat(countAfter).as("no revision row inserted").isEqualTo(countBefore);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /** A {@link DealQuotationService} wired with a repository whose {@code lockTicket} mutates
     * {@code targetQuotationId} to SUPERSEDED right after taking the REAL advisory lock -- see
     * the two tests above for why this is the deterministic stand-in for a genuinely concurrent
     * {@code approve()} winning the race for the lock first. */
    private DealQuotationService serviceSupersedingOnLock(long targetQuotationId) {
        DealQuotationRepository supersedingRepository =
            new SupersedeOnLockDealQuotationRepository(jdbc, new CatalogRepository(jdbc), targetQuotationId);
        NotificationEmailService mailer =
            new NotificationEmailService(new CapturingMailer(), new BrandAssets(), "", "", "https://portal.test");
        return new DealQuotationService(supersedingRepository, tickets, customers, contacts, notifications,
            mailer, new QuotationRenderer(), employeeAuth, new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
    }

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

    /** Numbering interleaves correctly with revisions in the same family: two clones minted off
     * the SAME still-APPROVED source, then one is approved (superseding the source and minting a
     * revision off IT), then the second clone is separately approved and cloned again -- all
     * share ONE counter (the task's own instruction: reuse {@code nextRevisionNo}, do not
     * re-implement numbering), so no duplicate key is ever hit and the suffixes are dense
     * (-2, -3, -4, -5). Also pins that a SUPERSEDED source can no longer be cloned (item 3). */
    @Test
    void createReorder_numbering_interleavesWithRevisions_noDuplicateKey() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        String base = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());

        // Both clones minted while source is STILL approved -- multiple clones are allowed.
        DealQuotationDto reorder1 = quotationService.createReorder(source.id(), salesActor);
        assertThat(reorder1.number()).isEqualTo(base + "-2");
        DealQuotationDto reorder2 = quotationService.createReorder(source.id(), salesActor);
        assertThat(reorder2.number()).isEqualTo(base + "-3");

        quotationService.submit(reorder1.id(), salesActor);
        DealQuotationDto approvedReorder1 =
            quotationService.approve(reorder1.id(), new ApproveRequest(null), salesManagerActor);
        // The source is now superseded by reorder1's approval.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // reorder2 (a sibling clone, unrelated lineage to reorder1) is untouched by that sweep.
        assertThat(quotationService.get(reorder2.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);

        DealQuotationDto revisionOfReorder1 = quotationService.createRevision(approvedReorder1.id(), salesActor);
        assertThat(revisionOfReorder1.number()).isEqualTo(base + "-4");
        // Its parentQuotationId points at reorder1 (an ordinary revision link) -- unrelated to
        // reorder1's OWN derivedFromQuotationId (which still points at the original source).
        assertThat(revisionOfReorder1.parentQuotationId()).isEqualTo(approvedReorder1.id());

        // The ORIGINAL source is already SUPERSEDED -- cloning it again must now 409 (item 3).
        assertThatThrownBy(() -> quotationService.createReorder(source.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        // reorder2, however, is still its own live APPROVED-eligible document -- approve it, then
        // clone IT (next suffix in the SAME shared family).
        quotationService.submit(reorder2.id(), salesActor);
        DealQuotationDto approvedReorder2 =
            quotationService.approve(reorder2.id(), new ApproveRequest(null), salesManagerActor);
        DealQuotationDto reorder3 = quotationService.createReorder(approvedReorder2.id(), salesActor);
        assertThat(reorder3.number()).isEqualTo(base + "-5");
        assertThat(reorder3.derivedFromQuotationId()).isEqualTo(approvedReorder2.id());
    }

    @Test
    void createReorder_nonApprovedSource_isConflict() {
        DealQuotationDto draft = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        assertThatThrownBy(() -> quotationService.createReorder(draft.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        DealQuotationDto submitted = quotationService.submit(draft.id(), salesActor);
        assertThatThrownBy(() -> quotationService.createReorder(submitted.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    /** Unlike {@link #createRevision_twiceOffTheSameApprovedParent_secondIs409NotADuplicateKeyCrash},
     * a SECOND (and third) reorder of the same source succeeds -- multiple clones are explicitly
     * allowed (the task's own instruction), never gated by an open-child check. */
    @Test
    void createReorder_multipleClonesOfTheSameSource_areAllAllowed() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);

        DealQuotationDto first = quotationService.createReorder(source.id(), salesActor);
        DealQuotationDto second = quotationService.createReorder(source.id(), salesActor);

        assertThat(first.number()).isNotEqualTo(second.number());
        assertThat(first.derivedFromQuotationId()).isEqualTo(source.id());
        assertThat(second.derivedFromQuotationId()).isEqualTo(source.id());
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    /** The general invariant (owner ruling 2026-09-19), checked after a SEQUENCE of approvals
     * spanning every mechanism this ticket can mint a sibling through -- a second first-issue
     * quotation, a clone, and a revision of the clone -- rather than any single pairwise case:
     * {@code count(APPROVED DEAL_DIRECT quotations WHERE ticket_id = X) <= 1} always holds. */
    @Test
    void createReorder_invariant_atMostOneApprovedDealDirectQuotationPerTicket_acrossMixedApprovals() {
        DealQuotationDto first = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        assertApprovedCountOnTicket(ticketId, 1);

        DealQuotationDto second = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        assertApprovedCountOnTicket(ticketId, 1);
        assertThat(quotationService.get(first.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        DealQuotationDto clone = quotationService.createReorder(second.id(), salesActor);
        quotationService.submit(clone.id(), salesActor);
        DealQuotationDto approvedClone = quotationService.approve(clone.id(), new ApproveRequest(null), salesManagerActor);
        assertApprovedCountOnTicket(ticketId, 1);
        assertThat(quotationService.get(second.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        DealQuotationDto revisionOfClone = quotationService.createRevision(approvedClone.id(), salesActor);
        quotationService.submit(revisionOfClone.id(), salesActor);
        quotationService.approve(revisionOfClone.id(), new ApproveRequest(null), salesManagerActor);
        assertApprovedCountOnTicket(ticketId, 1);
        assertThat(quotationService.get(approvedClone.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    private void assertApprovedCountOnTicket(long ticketIdParam, int expected) {
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT' AND doc_status = 'APPROVED'
            """, java.util.Map.of("ticketId", ticketIdParam), Long.class);
        assertThat(count).isEqualTo((long) expected);
    }

    /**
     * Authz, WRONG-WAY-ROUND (CLAUDE.md "Permission changes must ship evidence"): the SAME gate
     * {@link #createRevision} uses ({@code requireEditAccessForQuotation}) -- a plain {@code sales}
     * rep who does not own this deal must be refused, and the refusal must be provable by reading
     * the database back, not merely by catching the exception (a 403 thrown after the row had
     * already landed would otherwise still pass a test that only checks for the throw).
     */
    @Test
    void createReorder_byActorWithoutEditAccess_isForbidden_andNoRowInserted() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        long countBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);

        assertForbidden(() -> quotationService.createReorder(source.id(), otherSalesActor));

        long countAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);
        assertThat(countAfter).isEqualTo(countBefore);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    /** Same wrong-way-round style as the {@code sales} case above, for the two OTHER roles that
     * hold neither an {@code EDIT_ROLES} membership nor the {@code canCreateQuotation} grant in
     * this fixture: {@code ceo} and {@code import}. Both must be refused, and refused with no row
     * landed either. */
    @Test
    void createReorder_ceoAndImport_areForbidden_andNoRowInserted() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        long countBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);

        assertForbidden(() -> quotationService.createReorder(source.id(), ceoActor));
        assertForbidden(() -> quotationService.createReorder(source.id(), importActor));

        long countAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            java.util.Map.of("id", source.ticketId()), Long.class);
        assertThat(countAfter).isEqualTo(countBefore);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    /**
     * MAJOR gap closed (Opus review): the ticket lock {@link DealQuotationService#approve} takes
     * before the sweep was previously untested -- deleting the {@code quotations.lockTicket(...)}
     * call at approve()'s top left all 174 other tests green, because this hand-wired suite calls
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
     * <p>Mutation-checked (see the PR body for the exact run log): with {@code lockTicket}
     * commented out of {@code approve()}, this test failed 5-for-5 sequential runs -- both rows
     * APPROVED every time, never a false pass -- then passed again 5-for-5 once the line was
     * restored. The one-sided sleep makes the race deterministic rather than merely probable.
     */
    @Test
    void approveConcurrently_onSameTicket_exactlyOneEndsApproved_theLockSerialisesTheOtherAfterCommit() throws Exception {
        DealQuotationDto first = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto pending1 = quotationService.submit(first.id(), salesActor);
        DealQuotationDto second = quotationService.create(ticketId,
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

        // Both calls are expected to SUCCEED (neither DB constraint nor a re-check throws here --
        // each targets its OWN, distinct PENDING_APPROVAL row, so both compare-and-sets succeed
        // regardless of ordering). The invariant under test is what happens AFTER both land: with
        // the lock serialising them, the second caller's sweep runs against the FIRST caller's
        // already-committed APPROVED row and supersedes it.
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

    /** {@link DealQuotationRepository#approve}, but sleeping AFTER its own compare-and-set UPDATE
     * (while the caller's transaction is still open/uncommitted) -- see
     * {@link #approveConcurrently_onSameTicket_exactlyOneEndsApproved_theLockSerialisesTheOtherAfterCommit}'s
     * own Javadoc for why this is a one-sided sleep, not a mutual rendezvous latch. */
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

    /**
     * Opus review pin (independently verified correct against real Postgres): resubmitting a
     * REJECTED clone mints a REVISION of the clone (the SAME owner-clarified "ตีกลับ then resubmit
     * mints a new number" rule {@link #submit_afterRejection_mintsANewRevision_notTheSameRow}
     * already pins) -- {@code createReorder} itself is untouched by this path, but the resulting
     * chain is (source -[derivedFrom]-> clone -[parentQuotationId]-> revision).
     *
     * <p>⚠️ Owner ruling 2026-09-19 INVERTS the second half of this test (formerly
     * "...originalSourceStaysApproved_supersedeWalkStopsAtClone"): approving the revision now
     * ALSO supersedes the ORIGINAL source, but via the SEPARATE same-ticket sweep, not via the
     * ancestor walk -- the walk genuinely does still stop at the clone (its own {@code
     * parentQuotationId} is null, so it never climbs past it to the source; the clone was never
     * itself approved along this path, so the walk is the ONLY thing that could have touched it,
     * and it did). The source's own supersession is the sweep's doing: it was still the deal's
     * one live APPROVED document right up to this approval (the clone/revision chain was
     * rejected, then revised, never approved in between), so once ANY DEAL_DIRECT quotation on
     * this ticket is approved, it goes.
     */
    @Test
    void createReorder_rejectedCloneThenResubmittedAndApproved_ancestorWalkStopsAtCloneButSweepStillSupersedesSource() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        DealQuotationDto clone = quotationService.createReorder(source.id(), salesActor);
        quotationService.submit(clone.id(), salesActor);
        DealQuotationDto rejectedClone = quotationService.reject(clone.id(),
            new RejectRequest("แก้ไขราคาก่อนอนุมัติ"), salesManagerActor);
        assertThat(rejectedClone.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(rejectedClone.derivedFromQuotationId()).isEqualTo(source.id());
        // Rejection never approves anything, so the source is untouched so far.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // Resubmitting the rejected clone mints a REVISION of it (a new row/number, parent =
        // the clone) -- not a same-row PENDING_APPROVAL flip. This revision carries NO
        // derivedFromQuotationId of its own (item 2's fix) -- only parentQuotationId.
        DealQuotationDto revisionOfClone = quotationService.submit(clone.id(), salesActor);
        assertThat(revisionOfClone.id()).isNotEqualTo(clone.id());
        assertThat(revisionOfClone.parentQuotationId()).isEqualTo(clone.id());
        assertThat(revisionOfClone.derivedFromQuotationId()).isNull();
        assertThat(revisionOfClone.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        DealQuotationDto approvedRevision =
            quotationService.approve(revisionOfClone.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedRevision.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // The clone (the revision's own parent) is superseded by the ANCESTOR WALK...
        assertThat(quotationService.get(clone.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // ...and the ORIGINAL source is ALSO now superseded -- but by the SEPARATE same-ticket
        // SWEEP (it was still the one live APPROVED document on this ticket), not because the
        // ancestor walk somehow climbed past the clone (it did not -- the clone's own
        // parentQuotationId is null).
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /**
     * Opus review pin, UPDATED for the owner's 2026-09-19 ruling: an ordinary REVISION of an
     * APPROVED clone (via {@link #createRevision}, not a rejection-resubmit) supersedes only the
     * clone itself via the ANCESTOR WALK -- {@code approve}'s ancestor walk climbs {@code
     * parentQuotationId} only, and the clone's {@code parentQuotationId} is null (it links to its
     * source via {@code derivedFromQuotationId} only), so the walk itself has nowhere further to
     * climb. The ORIGINAL source is superseded too, but EARLIER, and by the SEPARATE same-ticket
     * sweep -- the moment the clone itself was first approved -- not as a side effect of this
     * later revision approval; pins that the sweep's timing is "the moment of approval", not
     * "walked to eventually".
     */
    @Test
    void revisionOfAnApprovedReorderClone_whenApproved_supersedesTheCloneViaAncestorWalk_sourceAlreadySupersededEarlier() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        DealQuotationDto clone = quotationService.createReorder(source.id(), salesActor);
        quotationService.submit(clone.id(), salesActor);
        DealQuotationDto approvedClone = quotationService.approve(clone.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedClone.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        // The source is superseded ALREADY, right here -- the clone's own approval sweep, not
        // anything the revision below will do.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        DealQuotationDto revision = quotationService.createRevision(approvedClone.id(), salesActor);
        assertThat(revision.parentQuotationId()).isEqualTo(approvedClone.id());
        assertThat(revision.derivedFromQuotationId()).isNull();
        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto approvedRevision =
            quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedRevision.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // The clone is superseded (it is the revision's direct parent, via the ancestor walk)...
        assertThat(quotationService.get(approvedClone.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.SUPERSEDED);
        // ...and the ORIGINAL source stays superseded -- it already was, unaffected by this
        // SECOND approval (the ancestor walk never climbs past the clone; the source is not
        // re-touched because it is no longer APPROVED, so the sweep's own compare-and-set has
        // nothing to do to it this time).
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /** Cloning a clone: the next suffix in the SAME shared numbering family, and
     * {@code derivedFromQuotationId} points at the IMMEDIATE source (the first clone), never the
     * original root -- provenance is one hop, not transitively resolved to the root.
     *
     * <p>⚠️ Owner ruling 2026-09-19: the ORIGINAL root {@code source} is superseded by clone1's
     * OWN approval (the same-ticket sweep), a step BEFORE clone2 is even created -- this is no
     * longer "never superseded", but cloning clone1 (still APPROVED, unaffected) into clone2 is
     * itself still a complete no-op on both clone1 and the (already-superseded) root. */
    @Test
    void createReorder_cloningAClone_getsTheNextSuffix_andPointsDerivedFromAtTheImmediateSource() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        String base = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());

        DealQuotationDto clone1 = quotationService.createReorder(source.id(), salesActor);
        assertThat(clone1.number()).isEqualTo(base + "-2");
        quotationService.submit(clone1.id(), salesActor);
        DealQuotationDto approvedClone1 =
            quotationService.approve(clone1.id(), new ApproveRequest(null), salesManagerActor);
        // The root is superseded HERE, by clone1's own approval sweep -- before clone2 exists.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        DealQuotationDto clone2 = quotationService.createReorder(approvedClone1.id(), salesActor);
        assertThat(clone2.number()).isEqualTo(base + "-3");
        // Points at clone1 (its own, immediate source) -- NOT at the original root `source`.
        assertThat(clone2.derivedFromQuotationId()).isEqualTo(approvedClone1.id());
        assertThat(clone2.derivedFromQuotationId()).isNotEqualTo(source.id());
        assertThat(clone2.parentQuotationId()).isNull();

        // Cloning clone1 into clone2 is ITSELF a no-op on both clone1 and the root: clone
        // CREATION never supersedes anything (only approval does), so clone1 stays APPROVED and
        // the already-superseded root stays exactly as it was.
        assertThat(quotationService.get(approvedClone1.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.APPROVED);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /** Legacy row (owner ruling, see {@link #revisionOfALegacyBareNumberedQuotation_appendsMinus2_neverRewritesTheBareNumber_neverCollides}
     * above for the identical rule on the revision path): a quotation issued before the
     * -1-suffix change carries a BARE number at revisionNo 1. Cloning one must append "-2" to the
     * bare number, never rewrite it, and never collide with the "-1" format a fresh quotation
     * mints today. */
    @Test
    void createReorder_ofALegacyBareNumberedQuotation_appendsMinus2_neverRewritesTheBareNumber() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        String bareLegacyNumber = DealQuotationRepository.baseNumber(created.number(), created.revisionNo());
        jdbc.update("UPDATE sales.quotation SET number = :number WHERE quotation_id = :id",
            java.util.Map.of("number", bareLegacyNumber, "id", created.id()));

        quotationService.submit(created.id(), salesActor);
        DealQuotationDto approvedLegacy =
            quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedLegacy.number()).isEqualTo(bareLegacyNumber);

        DealQuotationDto clone = quotationService.createReorder(approvedLegacy.id(), salesActor);
        assertThat(clone.number()).isEqualTo(bareLegacyNumber + "-2");
        assertThat(clone.revisionNo()).isEqualTo(2);
        assertThat(clone.derivedFromQuotationId()).isEqualTo(approvedLegacy.id());
        assertThat(clone.parentQuotationId()).isNull();
    }

    /** The ticket_event row itself, read back through {@code TicketRepository#findById} (a REAL
     * read of {@code sales.ticket_event}, not a mock) -- pins that {@code createReorder} actually
     * writes {@code TicketEventKind.DEAL_QUOTATION_REORDERED} (a real ticket_event kind, chk_event_kind
     * widened for it in V186), carrying both numbers in its Thai message. */
    @Test
    void createReorder_writesADealQuotationReorderedTicketEvent() {
        DealQuotationDto source = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        DealQuotationDto clone = quotationService.createReorder(source.id(), salesActor);

        var ticket = tickets.findById(ticketId).orElseThrow();
        assertThat(ticket.events())
            .anySatisfy(event -> {
                assertThat(event.kind()).isEqualTo(TicketEventKind.DEAL_QUOTATION_REORDERED);
                assertThat(event.message())
                    .contains(clone.number())
                    .contains(source.number())
                    .contains("สั่งเหมือนเดิม");
            });
    }

    @Test
    void reject_returnsToDraftWithReasonAsNote() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);

        DealQuotationDto rejected = quotationService.reject(submitted.id(),
            new RejectRequest("ราคาผิดพลาด กรุณาแก้ไข"), salesManagerActor);

        // ตีกลับ ITSELF never renumbers -- SAME row, SAME number, back to DRAFT with the reason
        // recorded as its note (owner clarification 2026-09-15, this class's own header comment).
        assertThat(rejected.id()).isEqualTo(created.id());
        assertThat(rejected.number()).isEqualTo(created.number());
        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(rejected.approvalNote()).isEqualTo("ราคาผิดพลาด กรุณาแก้ไข");
        assertThat(notifications.findByEmployeeId(salesRepId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_REJECTED") && n.message().contains("ราคาผิดพลาด"));
    }

    // ── owner clarification (2026-09-15): resubmitting a ตีกลับ'd draft mints a NEW revision ──
    //
    // "ตีกลับ = แก้ใบเดิม เลขไม่เปลี่ยน (this row, this number, back to DRAFT) — but resubmitting
    // it, once sales has fixed it, ออกใบใหม่ เลขเปลี่ยน (a new row/number), the SAME way revising
    // an already-APPROVED document always has." reject() itself is unchanged (see the test above);
    // everything below pins what submit() now does to a DRAFT that carries a rejection.

    /** The core new behaviour: submit() on a rejected draft mints a revision of it instead of
     * flipping the SAME row to PENDING_APPROVAL -- new id, new number ({base}-{n+1}, exactly the
     * numbering scheme #createRevision already uses), parent = the rejected row, and the REJECTED
     * row itself is left exactly as reject() put it (still DRAFT, its approval_note untouched --
     * a permanent record of why THAT number was retired, not cleared the way a same-row resubmit
     * used to clear it). */
    @Test
    void submit_afterRejection_mintsANewRevision_notTheSameRow() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected = quotationService.reject(submitted.id(),
            new RejectRequest("ราคาผิดพลาด กรุณาแก้ไข"), salesManagerActor);

        DealQuotationDto resubmitted = quotationService.submit(rejected.id(), salesActor);

        assertThat(resubmitted.id()).as("a NEW row, not the rejected one").isNotEqualTo(rejected.id());
        assertThat(resubmitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(resubmitted.parentQuotationId()).isEqualTo(rejected.id());
        assertThat(resubmitted.revisionNo()).isEqualTo(rejected.revisionNo() + 1);
        String base = DealQuotationRepository.baseNumber(rejected.number(), rejected.revisionNo());
        assertThat(resubmitted.number()).isEqualTo(base + "-" + resubmitted.revisionNo());
        assertThat(resubmitted.number()).as("a genuinely different number").isNotEqualTo(rejected.number());

        // The rejected row itself: untouched by this call -- still DRAFT, still carrying the SAME
        // reason it always did (not cleared, unlike the old same-row-resubmit behaviour this
        // supersedes).
        DealQuotationDto rejectedReread = quotationService.get(rejected.id(), salesActor);
        assertThat(rejectedReread.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(rejectedReread.approvalNote()).isEqualTo("ราคาผิดพลาด กรุณาแก้ไข");
    }

    /** Mirrors {@link #approve_supersedesParentOnlyWhenRevisionItselfReachesApproved} exactly, for
     * a DRAFT (rejected) parent instead of an APPROVED one -- the SAME timing rule
     * ({@code DealQuotationRepository#supersede}'s own Javadoc), now reachable from a second
     * starting status. */
    @Test
    void submit_afterRejection_theRejectedParentBecomesSupersededOnlyOnceTheRevisionIsApproved() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected = quotationService.reject(submitted.id(),
            new RejectRequest("แก้ราคา"), salesManagerActor);

        DealQuotationDto revision = quotationService.submit(rejected.id(), salesActor);
        // Still DRAFT, not yet SUPERSEDED -- the revision itself hasn't been approved yet.
        assertThat(quotationService.get(rejected.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);

        quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);

        assertThat(quotationService.get(rejected.id(), salesActor).docStatus())
            .as("only NOW, once the revision itself reaches APPROVED")
            .isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /** Mirrors {@link #createRevision_twiceOffTheSameApprovedParent_secondIs409NotADuplicateKeyCrash}
     * -- the SAME {@code hasOpenRevision} guard, reached from submit()'s new branch instead of
     * {@code createRevision} directly. A rep double-clicking "ส่งใหม่" (a slow network, an
     * impatient double-tap) must not either crash on the unique-index violation or silently mint
     * two revisions of the same rejected draft. */
    @Test
    void submit_afterRejection_twiceOnTheSameRejectedRow_secondIs409NotADuplicateKeyCrash() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected = quotationService.reject(submitted.id(),
            new RejectRequest("แก้ราคา"), salesManagerActor);

        DealQuotationDto firstRevision = quotationService.submit(rejected.id(), salesActor);
        assertThat(firstRevision.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> quotationService.submit(rejected.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
            .hasMessageContaining("มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว");
    }

    /** The negative space of the new behaviour: a plain first-ever submit (never decided on --
     * {@code approval_note} is null) must be COMPLETELY untouched -- same row, same number, still
     * the ordinary DRAFT -> PENDING_APPROVAL compare-and-set. Guards against the new branch's
     * condition ever being loosened to fire on every DRAFT rather than only a REJECTED one. */
    @Test
    void submit_firstTimeNeverRejected_stillSubmitsTheSameRowSameNumber() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.approvalNote()).isNull();

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);

        assertThat(submitted.id()).isEqualTo(created.id());
        assertThat(submitted.number()).isEqualTo(created.number());
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(submitted.parentQuotationId()).isNull();
    }

    // ── owner request (2026-09-16): a revision's submit must read as a REVISION to
    // sales_manager/ceo, not the identical "รออนุมัติ" text a first-time submit sends ──

    /**
     * {@link #createRevision}'s own submit (parent {@code APPROVED}): sales_manager + CEO get the
     * NEW {@code DEAL_QUOTATION_REVISION_SUBMITTED} kind, mailed with the REVISION subject/title
     * ("ใบเสนอราคาฉบับแก้ไขรออนุมัติ" -- {@code NotificationRepository.TICKET_EVENT_TITLES}), and a
     * message naming the actor, the new number, and the number it revises -- wired against the
     * REAL {@link SalesNotificationMailRouter} (see {@link
     * #submit_actuallyMailsSalesManagerCeoAndRep_throughTheRealMailRouter}'s own Javadoc for why a
     * bare {@code new DealQuotationService} would be a false positive for the mail assertions).
     */
    @Test
    void createRevision_thenSubmit_mailsAndNotifiesSalesManagerAndCeoWithTheRevisionSubjectAndMessage() {
        NotificationEmailService realEmailService =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.test");
        NotificationRepository realNotifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(realEmailService, new SalesMailRecipientRepository(jdbc)));
        DealQuotationService realQuotationService = transactional(new DealQuotationService(quotationRepository,
            tickets, customers, contacts, realNotifications, realEmailService, new QuotationRenderer(), employeeAuth,
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test", "", "", ""));

        DealQuotationDto created = realQuotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = realQuotationService.submit(created.id(), salesActor);
        DealQuotationDto approved =
            realQuotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        // Isolate the revision submit's OWN mail from the create/submit/approve noise above.
        mailer.htmlSent.clear();

        DealQuotationDto revision = realQuotationService.createRevision(approved.id(), salesActor);
        DealQuotationDto revisionSubmitted = realQuotationService.submit(revision.id(), salesActor);

        assertThat(mailer.htmlSent).extracting(sent -> sent[0])
            .as("sales_manager, ceo, and the rep's own confirmation each sent mail")
            .containsExactlyInAnyOrder("sales-manager-dq@glr.co.th", "rarm@glr.co.th", "sales-dq@glr.co.th");
        assertThat(mailer.htmlSent)
            .filteredOn(sent -> !sent[0].equals("sales-dq@glr.co.th"))
            .as("sales_manager + ceo get the REVISION subject, not the first-time-submit one")
            .allSatisfy(sent -> assertThat(sent[1]).contains("ใบเสนอราคาฉบับแก้ไขรออนุมัติ"));

        // The message body itself (htmlSent only captures [to, subject] in this fixture) is
        // asserted through the in-app row instead -- same underlying Postgres row the real mail
        // router reads from.
        //
        // Opus review (2026-09-16): the revision number is ALWAYS the parent number plus a
        // "-n" suffix (see revisionNumber(base, n) below), so it CONTAINS the parent number as a
        // literal substring -- asserting message().contains(newNumber) makes
        // message().contains(parentNumber) pass trivially even if "(แก้ไขจาก ...)" were dropped
        // entirely. Pin the WHOLE message verbatim instead, so the "(แก้ไขจาก {parent})" wording
        // and the two fixed phrases ("ฉบับแก้ไข", "กรุณาตรวจสอบและพิจารณาอนุมัติ") are all load-bearing.
        String expectedMessage = salesActor.name() + " ได้จัดทำใบเสนอราคาฉบับแก้ไข " + revisionSubmitted.number()
            + " (แก้ไขจาก " + approved.number() + ") กรุณาตรวจสอบและพิจารณาอนุมัติ";
        assertThat(notifications.findByEmployeeId(salesManagerId))
            .filteredOn(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"))
            .as("sales_manager gets exactly one revision-submitted row, message verbatim")
            .singleElement()
            .extracting(NotificationDto::message)
            .isEqualTo(expectedMessage);
        assertThat(notifications.findByEmployeeId(ceoId))
            .filteredOn(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"))
            .as("ceo gets exactly one revision-submitted row, message verbatim")
            .singleElement()
            .extracting(NotificationDto::message)
            .isEqualTo(expectedMessage);
    }

    /**
     * {@link #submitAsRevisionOfRejected}'s path (parent {@code DRAFT}, carrying a rejection):
     * same REVISION kind/subject as {@link
     * #createRevision_thenSubmit_mailsAndNotifiesSalesManagerAndCeoWithTheRevisionSubjectAndMessage}
     * above, PLUS the rejection reason appended to the message -- {@code parent.docStatus()}
     * being {@code DRAFT} (not {@code approvalNote() != null} alone) is what {@code
     * notifyRevisionSubmitted} keys the suffix on, since an APPROVED parent's own {@code
     * approvalNote} is an approval note, not a rejection reason (see that method's own Javadoc).
     */
    @Test
    void submit_afterRejection_mailsAndNotifiesWithTheRevisionSubjectAndTheRejectionReason() {
        NotificationEmailService realEmailService =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.test");
        NotificationRepository realNotifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(realEmailService, new SalesMailRecipientRepository(jdbc)));
        DealQuotationService realQuotationService = transactional(new DealQuotationService(quotationRepository,
            tickets, customers, contacts, realNotifications, realEmailService, new QuotationRenderer(), employeeAuth,
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test", "", "", ""));

        DealQuotationDto created = realQuotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = realQuotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected = realQuotationService.reject(submitted.id(),
            new RejectRequest("ราคาผิดพลาด กรุณาแก้ไข"), salesManagerActor);
        mailer.htmlSent.clear();

        DealQuotationDto resubmitted = realQuotationService.submit(rejected.id(), salesActor);

        // Opus review (2026-09-16): filteredOn(...).allSatisfy(...) passes vacuously on an EMPTY
        // list (verified against this repo's assertj-core 3.27.7) -- exactly the shape that stayed
        // green through the documented nested-afterCommit bug (submitDraftRow's own comment above),
        // where every revision mail was silently swallowed while the in-app bell rows still landed
        // synchronously. Pin the recipient set FIRST so a totally-lost mail batch fails loudly here,
        // before the filtered subject check even runs.
        assertThat(mailer.htmlSent).hasSize(3);
        assertThat(mailer.htmlSent).extracting(sent -> sent[0])
            .as("sales_manager, ceo, and the rep's own confirmation each sent mail on the resubmit")
            .containsExactlyInAnyOrder("sales-manager-dq@glr.co.th", "rarm@glr.co.th", "sales-dq@glr.co.th");
        assertThat(mailer.htmlSent)
            .filteredOn(sent -> !sent[0].equals("sales-dq@glr.co.th"))
            .as("sales_manager + ceo get the REVISION subject on a resubmit-after-ตีกลับ too")
            .allSatisfy(sent -> assertThat(sent[1]).contains("ใบเสนอราคาฉบับแก้ไขรออนุมัติ"));

        // Same reasoning as the sibling test above: pin the WHOLE message, not just substrings the
        // revision number's own "{parent}-n" shape would satisfy regardless.
        String expectedMessage = salesActor.name() + " ได้จัดทำใบเสนอราคาฉบับแก้ไข " + resubmitted.number()
            + " (แก้ไขจาก " + rejected.number() + ") กรุณาตรวจสอบและพิจารณาอนุมัติ"
            + " — แก้ไขตามที่ตีกลับ: ราคาผิดพลาด กรุณาแก้ไข";
        assertThat(notifications.findByEmployeeId(salesManagerId))
            .filteredOn(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"))
            .as("sales_manager gets exactly one revision-submitted row, message verbatim")
            .singleElement()
            .extracting(NotificationDto::message)
            .isEqualTo(expectedMessage);
        assertThat(notifications.findByEmployeeId(ceoId))
            .filteredOn(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"))
            .as("ceo gets exactly one revision-submitted row, message verbatim")
            .singleElement()
            .extracting(NotificationDto::message)
            .isEqualTo(expectedMessage);
    }

    /**
     * Opus review (2026-09-16), finding 4: {@code notifyRevisionSubmitted} interpolates {@code
     * actor.name()} with no null/blank guard, so a blank name would otherwise print the literal
     * "  ได้จัดทำใบเสนอราคาฉบับแก้ไข ..." (an empty leading word) in both the bell row and the
     * e-mail. Pins the blank-safe fallback this service now falls back to instead, mirroring
     * {@link #approve}'s own {@code approvedByName() != null ? ... : "ผู้อนุมัติ"} pattern
     * elsewhere in this class.
     */
    @Test
    void createRevision_thenSubmit_byActorWithBlankName_fallsBackToARoleLabelNotBlank() {
        UserPrincipal blankNamedSalesActor = new UserPrincipal(
            salesRepId, "sales-dq@glr.co.th", "   ", "sales", salesRepId, true, LocalDate.now(), false, null, false);

        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved =
            quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);

        DealQuotationDto revision = quotationService.createRevision(approved.id(), blankNamedSalesActor);
        DealQuotationDto revisionSubmitted = quotationService.submit(revision.id(), blankNamedSalesActor);

        String expectedMessage = "ผู้เสนอราคา ได้จัดทำใบเสนอราคาฉบับแก้ไข " + revisionSubmitted.number()
            + " (แก้ไขจาก " + approved.number() + ") กรุณาตรวจสอบและพิจารณาอนุมัติ";
        assertThat(notifications.findByEmployeeId(salesManagerId))
            .filteredOn(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"))
            .as("a blank actor name falls back to a role label, never a literal blank/\"null\"")
            .singleElement()
            .extracting(NotificationDto::message)
            .isEqualTo(expectedMessage);
    }

    /**
     * Wrong-way-round (CLAUDE.md's own rule for this kind of assertion): an ORDINARY first-time
     * submit -- no parent, {@link #submit_firstTimeNeverRejected_stillSubmitsTheSameRowSameNumber}
     * above already pins {@code parentQuotationId()} null for it -- must NOT use the revision
     * kind, subject, or wording, byte-identical to before this feature. Guards against the new
     * {@code parentQuotationId() != null} branch in {@code notifySubmitted} ever being loosened
     * (or its condition inverted) to fire on a first-time submit too.
     */
    @Test
    void submit_firstTime_doesNotUseTheRevisionKindSubjectOrMessage() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);

        assertThat(submitted.parentQuotationId()).isNull();
        List<NotificationDto> managerNotifications = notifications.findByEmployeeId(salesManagerId);
        assertThat(managerNotifications).noneMatch(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"));
        assertThat(managerNotifications).anyMatch(n ->
            n.type().equals("DEAL_QUOTATION_SUBMITTED")
                && n.message().equals("ใบเสนอราคา " + submitted.number() + " รอการอนุมัติ")
                && !n.message().contains("ฉบับแก้ไข"));
        List<NotificationDto> ceoNotifications = notifications.findByEmployeeId(ceoId);
        assertThat(ceoNotifications).noneMatch(n -> n.type().equals("DEAL_QUOTATION_REVISION_SUBMITTED"));
    }

    /** The revision must copy the rejected draft's CURRENT (edited-after-rejection) data, not a
     * stale snapshot from before the rework -- the same verbatim-copy contract
     * {@code createRevision_carriesPriceModeAndEveryRowTypeVerbatim} pins for an ordinary
     * revision, exercised here through the OTHER path that now shares its copying code. */
    @Test
    void submit_afterRejection_copiesTheDraftsPostRejectionEditsVerbatim() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected = quotationService.reject(submitted.id(),
            new RejectRequest("แก้ราคา"), salesManagerActor);

        // Sales fixes the price AFTER the rejection -- the edit reject() itself asked for.
        DealQuotationDto edited = quotationService.update(rejected.id(),
            upsertRequest(List.of(sampleItem("250.00", 4))), salesActor);
        assertThat(edited.items()).hasSize(1);

        DealQuotationDto revision = quotationService.submit(rejected.id(), salesActor);

        // Mutation-check note: item data alone doesn't discriminate "copied into a new revision"
        // from "the same row, still holding update()'s own edit" -- both would read identically
        // here. id/parentQuotationId are what actually pin THIS test to the revision mechanism
        // specifically (mintsANewRevision_notTheSameRow already covers this more directly, but a
        // reader of THIS test alone should not be misled into thinking it proves that on its own).
        assertThat(revision.id()).isNotEqualTo(rejected.id());
        assertThat(revision.parentQuotationId()).isEqualTo(rejected.id());
        assertThat(revision.items()).hasSize(1);
        assertThat(revision.items().get(0).unitPrice()).isEqualByComparingTo("250.00");
        assertThat(revision.items().get(0).quantity()).isEqualByComparingTo("4");
    }

    /** Numbering must chain off the ORIGINAL base through multiple reject/resubmit cycles, the
     * same way {@link #revisionNumbering_chainsOffTheOriginalBaseNumber_notTheImmediateParent}
     * already pins for ordinary approve-then-revise cycles -- never "{base}-1-2-3" and never
     * colliding with a fresh quotation's own "{base}-1". */
    @Test
    void submit_afterRejection_numberingChainsThroughMultipleRejectCycles() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        String base = DealQuotationRepository.baseNumber(created.number(), created.revisionNo());

        DealQuotationDto submitted1 = quotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected1 = quotationService.reject(submitted1.id(),
            new RejectRequest("รอบ 1"), salesManagerActor);
        DealQuotationDto revision2 = quotationService.submit(rejected1.id(), salesActor);
        assertThat(revision2.number()).isEqualTo(base + "-2");

        DealQuotationDto rejected2 = quotationService.reject(revision2.id(),
            new RejectRequest("รอบ 2"), salesManagerActor);
        DealQuotationDto revision3 = quotationService.submit(rejected2.id(), salesActor);
        // Must chain off the ORIGINAL base ("QT-...-3"), never off rejected2's own number as if it
        // were itself a base ("QT-...-2-3").
        assertThat(revision3.number()).isEqualTo(base + "-3");
        assertThat(revision3.revisionNo()).isEqualTo(3);
        assertThat(revision3.parentQuotationId()).isEqualTo(rejected2.id());
    }

    /** Opus review (2026-09-15), REQUIRED: {@code approve}'s supersede used to walk only ONE hop
     * up {@code parent_quotation_id}, so a SECOND (or later) reject/resubmit cycle left every
     * ancestor ABOVE the immediate parent stranded in DRAFT forever the moment the FINAL revision
     * was approved -- proven against real Postgres by a since-removed probe. Continues the exact
     * chain {@link #submit_afterRejection_numberingChainsThroughMultipleRejectCycles} builds
     * (created -> reject -> revision2 -> reject -> revision3) one step further: approves
     * revision3 and asserts EVERY ancestor in the chain is SUPERSEDED, not just revision2 (the
     * immediate parent) -- and that the whole chain has therefore left the "ฉบับแก้" bucket,
     * which is exactly the symptom ("A sits in the rep's ฉบับแก้ bucket forever on a deal whose
     * quotation is already approved") the missing walk produced. */
    @Test
    void approve_supersedesEveryAncestorInAMultiCycleRejectChain_notJustTheImmediateParent() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        long originalId = created.id();

        DealQuotationDto submitted1 = quotationService.submit(created.id(), salesActor);
        DealQuotationDto rejected1 = quotationService.reject(submitted1.id(),
            new RejectRequest("รอบ 1"), salesManagerActor);
        DealQuotationDto revision2 = quotationService.submit(rejected1.id(), salesActor);
        long revision2Id = revision2.id();

        DealQuotationDto rejected2 = quotationService.reject(revision2.id(),
            new RejectRequest("รอบ 2"), salesManagerActor);
        DealQuotationDto revision3 = quotationService.submit(rejected2.id(), salesActor);

        // Before approval: both ancestors still sit in ฉบับแก้ (needsRework), same as ever.
        assertThat(quotationService.search(null, true, salesActor)).extracting(DealQuotationDto::id)
            .contains(originalId, revision2Id);

        quotationService.approve(revision3.id(), new ApproveRequest(null), salesManagerActor);

        // The IMMEDIATE parent (revision2) -- already covered by
        // theRejectedParentBecomesSupersededOnlyOnceTheRevisionIsApproved, re-asserted here as
        // part of the SAME chain this test is actually about.
        assertThat(quotationService.get(revision2Id, salesActor).docStatus())
            .isEqualTo(QuotationStatus.SUPERSEDED);
        // The GRANDPARENT (the original) -- THIS is what the single-hop version left stranded.
        assertThat(quotationService.get(originalId, salesActor).docStatus())
            .as("the original must ALSO become SUPERSEDED, not stay DRAFT forever")
            .isEqualTo(QuotationStatus.SUPERSEDED);

        // Neither ancestor is reachable through ฉบับแก้ any more -- the whole chain retired.
        assertThat(quotationService.search(null, true, salesActor)).extracting(DealQuotationDto::id)
            .doesNotContain(originalId, revision2Id);
    }

    /** Opus review (2026-09-15), REQUIRED, wrong-way-round: the ancestry a rejected-and-resubmitted
     * row can grow is a TREE, not a straight line -- {@link DealQuotationRepository#hasOpenRevision}
     * only looks at DIRECT children, so cancelling a DRAFT row that itself has an OPEN child used to
     * make that child's GRANDPARENT look "free" again (the direct child is now CANCELLED, not
     * open), letting the grandparent mint a SECOND, sibling branch while the first branch (the
     * cancelled row's own child) was still alive. Approving one branch's leaf only ever walks
     * UPWARD from that leaf -- it can never reach across to supersede a SIBLING branch -- so BOTH
     * branches could end APPROVED: two independently-approved quotations on the same ticket.
     * Proves the fix (cancel() now refuses a row with an open child) blocks the exact sequence
     * that produced this. */
    @Test
    void cancel_refusesADraftWithAnOpenChild_closingTheDoubleApproveHole() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        long originalId = created.id();

        // A -> reject -> resubmit mints B -> reject -> resubmit mints C. B now has an open child.
        DealQuotationDto submitted = quotationService.submit(originalId, salesActor);
        DealQuotationDto rejectedA = quotationService.reject(submitted.id(),
            new RejectRequest("รอบ 1"), salesManagerActor);
        DealQuotationDto revisionB = quotationService.submit(rejectedA.id(), salesActor);
        DealQuotationDto rejectedB = quotationService.reject(revisionB.id(),
            new RejectRequest("รอบ 2"), salesManagerActor);
        DealQuotationDto revisionC = quotationService.submit(rejectedB.id(), salesActor);

        // THE FIX: B cannot be cancelled out from under its own open child C.
        assertThatThrownBy(() -> quotationService.cancel(revisionB.id(), new CancelRequest(null), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
            .hasMessageContaining("มีฉบับแก้ไขของใบเสนอราคานี้อยู่");

        // Without this guard, the ORIGINAL sequence that broke the invariant continued: cancel(B)
        // would have succeeded, hasOpenRevision(originalId) would then have read false (B's only
        // status is CANCELLED, not open), and submit(originalId) would have minted a SECOND
        // branch alongside C's. Confirm that second branch is ALSO still blocked, transitively:
        // C itself must be resolved to a terminal CANCELLED state before B can be freed.
        DealQuotationDto rejectedC = quotationService.reject(revisionC.id(),
            new RejectRequest("รอบ 3"), salesManagerActor);
        quotationService.cancel(rejectedC.id(), new CancelRequest(null), salesActor);
        // NOW B has no open child (C is CANCELLED, a terminal state) -- cancelling B is legitimate.
        DealQuotationDto cancelledB = quotationService.cancel(revisionB.id(), new CancelRequest(null), salesActor);
        assertThat(cancelledB.docStatus()).isEqualTo(QuotationStatus.CANCELLED);

        // The original can now legitimately be resubmitted -- its whole subtree (B, C) is
        // terminal, so there is only ONE live path from here, not a second sibling branch.
        DealQuotationDto revisionB2 = quotationService.submit(originalId, salesActor);
        assertThat(revisionB2.id()).isNotEqualTo(revisionB.id());
        quotationService.approve(revisionB2.id(), new ApproveRequest(null), salesManagerActor);

        // The invariant this whole feature exists to protect: never more than ONE APPROVED
        // quotation on the same ticket at a time.
        assertThat(quotationService.search(List.of("APPROVED"), false, salesActor))
            .extracting(DealQuotationDto::id).containsExactly(revisionB2.id());
    }

    /** Regression guard: cancelling an ORDINARY leaf DRAFT (no open child of its own) must still
     * work exactly as before -- the new guard in {@link #cancel_refusesADraftWithAnOpenChild_closingTheDoubleApproveHole}
     * must not block the common case. */
    @Test
    void cancel_stillWorksOnAPlainDraftWithNoOpenChild() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto cancelled = quotationService.cancel(created.id(), new CancelRequest(null), salesActor);
        assertThat(cancelled.docStatus()).isEqualTo(QuotationStatus.CANCELLED);
    }

    /** LOW: reject() used to skip the existence check and go straight to the compare-and-set
     * UPDATE, so a missing id and a wrong-status id were indistinguishable (both 409) — unlike
     * approve/submit/cancel/update, which all 404 first. */
    @Test
    void reject_missingId_is404NotConflict() {
        long missingId = 999_999_999L;
        assertThatThrownBy(() -> quotationService.reject(missingId, new RejectRequest("no"), salesManagerActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    /** M3: two "create a revision" calls on the same APPROVED parent used to both pass the status
     * check, then race INSERTs of a child sharing the SAME deterministic number ({base}-{n}) —
     * sales.quotation.number is UNIQUE (V6), so the second insert threw DuplicateKeyException,
     * surfaced as a bare 500. A single-threaded double-call already reproduces the DEFECT this
     * fixes: the second call must now see the FIRST call's already-inserted open child and 409
     * with an intelligible Thai reason, never reach the INSERT at all. */
    @Test
    void createRevision_twiceOffTheSameApprovedParent_secondIs409NotADuplicateKeyCrash() {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);

        DealQuotationDto firstRevision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(firstRevision.docStatus()).isEqualTo(QuotationStatus.DRAFT);

        assertThatThrownBy(() -> quotationService.createRevision(approved.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
            .hasMessageContaining("มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว");
    }

    /** M3 counterpart: once the OPEN child itself resolves (rejected back... no — cancelled is
     * DRAFT-only and a revision is created straight into DRAFT, so REJECTING isn't reachable from
     * DRAFT; cancelling it IS), a fresh revision must be creatable again — the guard blocks a
     * concurrent DUPLICATE, not every subsequent revision forever. */
    @Test
    void createRevision_afterTheOpenChildIsCancelled_aNewRevisionCanBeCreated() {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        DealQuotationDto firstRevision = quotationService.createRevision(approved.id(), salesActor);
        quotationService.cancel(firstRevision.id(), new CancelRequest(null), salesActor);

        DealQuotationDto secondRevision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(secondRevision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(secondRevision.id()).isNotEqualTo(firstRevision.id());
    }

    /** M4: {@code buildItem}'s catch used to list only {@code IllegalArgumentException}; an
     * absurd (but bean-validation-unbounded-at-the-pure-calculator-level — see
     * {@code WastageCalculatorTest#areaMode_absurdlyLargeAreaSqm_throwsArithmeticExceptionNotSilentOverflow})
     * areaSqm throws {@code ArithmeticException} from {@code BigDecimal#intValueExact} instead,
     * and reached the generic 500 handler. Calling the SERVICE directly (as this integration test
     * does) bypasses the controller's {@code @Valid} bean validation that would otherwise catch
     * this earlier as a 400 — exercising the service's OWN defence, not the DTO's. */
    @Test
    void createItem_absurdAreaSqm_isBadRequestNot500() {
        // Item completeness rule: thicknessMm/piecesPerBox filled in (otherwise the completeness
        // check -- which now runs BEFORE WastageCalculator -- would 400 for a DIFFERENT reason and
        // this test would stop actually exercising the ArithmeticException path its own Javadoc
        // above describes).
        ItemInput absurd = new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), BigDecimal.ONE, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("1E30"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO,
            "ไทย-สต็อก", 30, 45, null);
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(absurd)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Item completeness rule (inline-deal-spec.md, owner ruling 2026-09-10)
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Complete, typed (non-catalog) item — 201, the "happy path" the rule must not regress. */
    @Test
    void completeTypedItem_createSucceeds() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.id()).isPositive();
        assertThat(created.items()).hasSize(1);
    }

    @Test
    void incompleteItem_missingModel_isBadRequestNamingTheRowAndField() {
        ItemInput missingModel = itemMissing(i -> i.withModel(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(missingModel)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("รุ่น");
    }

    @Test
    void incompleteItem_missingColor_isBadRequestNamingTheRowAndField() {
        ItemInput missingColor = itemMissing(i -> i.withColor(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(missingColor)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("สี");
    }

    @Test
    void incompleteItem_missingTexture_isBadRequestNamingTheRowAndField() {
        ItemInput missingTexture = itemMissing(i -> i.withTexture(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(missingTexture)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("ผิว");
    }

    @Test
    void incompleteItem_missingSizeText_isBadRequestNamingTheRowAndField() {
        // ⚠️ 2026-09-12: sizeText no longer has anything to do with ตร.ม./แผ่น -- the deleted
        // size-string heuristic used to make blanking sizeText ALSO remove the only way
        // sqmPerPiece could be derived, so this test used to name both. #sampleItem now supplies
        // sqmPerPiece EXPLICITLY (see its own comment), so blanking sizeText here is a standalone
        // "ขนาด" completeness gap, unrelated to ตร.ม./แผ่น.
        ItemInput missingSize = itemMissing(i -> i.withSizeText(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(missingSize)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("ขนาด");
    }

    @Test
    void incompleteItem_missingOrZeroThickness_isBadRequestNamingTheRowAndField() {
        ItemInput missingThickness = itemMissing(i -> i.withThicknessMm(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(missingThickness)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("ความหนา");

        ItemInput zeroThickness = itemMissing(i -> i.withThicknessMm(BigDecimal.ZERO));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(zeroThickness)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ความหนา");
    }

    @Test
    void incompleteItem_missingPiecesPerBox_isBadRequestNamingTheRowAndField() {
        ItemInput missingPpb = itemMissing(i -> i.withPiecesPerBox(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(missingPpb)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("จำนวนแผ่นต่อกล่อง");
    }

    @Test
    void incompleteItem_unparseableSizeAndNoExplicitSqmPerPiece_isBadRequestForSqmPerPiece() {
        // ⚠️ 2026-09-12: retargeted -- there is no longer a size-string parser to fail against
        // (DealQuotationService#resolveSqmPerPiece never reads sizeText; see its own Javadoc), so
        // "unparseable size" cannot be this test's mechanism any more. The real current rule this
        // test must exercise is resolution step 4: no item-supplied sqmPerPiece AND no catalog
        // link (so no catalogue basis either) => resolves to null => "ตร.ม./แผ่น" missing. sizeText
        // itself is irrelevant to that rule now; "ตามภาพ" is kept only as a realistic free-text
        // value a rep might actually type.
        ItemInput unparseableSize = itemMissing(i -> i.withSizeText("ตามภาพ").withSqmPerPiece(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(unparseableSize)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ตร.ม./แผ่น");
    }

    @Test
    void incompleteItem_zeroUnitPrice_isBadRequestNamingTheRowAndField() {
        // Bean validation (@DecimalMin) already rejects this at the controller; calling the
        // SERVICE directly (as this test does throughout) proves the completeness check is ALSO a
        // real defence, not just a DTO-layer one a stale/older client could bypass.
        ItemInput zeroPrice = itemMissing(i -> i.withUnitPrice(BigDecimal.ZERO));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(zeroPrice)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ราคาต่อหน่วย");
    }

    @Test
    void incompleteItem_areaModeWithNoArea_isBadRequestNamingQuantity() {
        ItemInput input = new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), null, WastageCalculator.QUANTITY_MODE_AREA, null, null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO,
            "ไทย-สต็อก", 30, 45, null);
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(input)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("จำนวน");
    }

    @Test
    void incompleteItem_piecesModeWithNoPieces_isBadRequestNamingQuantity() {
        ItemInput input = itemMissing(i -> i.withPiecesInput(null));
        assertThatThrownBy(() -> quotationService.create(ticketId, upsertRequest(List.of(input)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1").hasMessageContaining("จำนวน");
    }

    /** Item completeness must be checked per-row: the SECOND item's missing model must not be
     * masked by the first item being complete, and the message names row 2, not row 1. */
    @Test
    void incompleteItem_secondOfTwoRows_messageNamesRowTwo() {
        ItemInput complete = sampleItem("100.00", 10);
        ItemInput incomplete = itemMissing(i -> i.withModel(null));
        assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(List.of(complete, incomplete)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 2").hasMessageContaining("รุ่น");
    }

    /** {@code calculate-line} stays lenient (inline-deal-spec.md, explicit): the item editor calls
     * this on every keystroke, often with the row still half-typed -- it must preview, not 400. */
    @Test
    void calculateLine_staysLenient_onAnIncompleteItem() {
        // model/color/texture/sizeText/thicknessMm/piecesPerBox ALL missing -- exactly the fields
        // the item completeness rule requires on create/update/submit -- yet calculate-line must
        // still preview: quantityMode+piecesInput+unitPrice is all WastageCalculator itself
        // (unrelated to this rule) needs to compute a line.
        ItemInput incomplete = new ItemInput(null, null, null, null, null, null, null, null,
            null, null, WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, new BigDecimal("10.00"), BigDecimal.ZERO,
            null, null, null, null);
        // Must not throw.
        DealQuotationItemDto preview = quotationService.calculateLine(incomplete, salesActor);
        assertThat(preview).isNotNull();
        assertThat(preview.lineAmount()).isEqualByComparingTo("100.00");
    }

    /** Defence-in-depth: submit re-checks the STORED rows, not just create/update's write-time
     * check -- see DealQuotationService#submit's own comment for why. Proven here by writing an
     * incomplete row straight through the repository (bypassing the service), the same way a
     * legacy pre-rule row would exist. */
    @Test
    void submit_reChecksStoredItems_incompleteRowIsBadRequest() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        jdbc.update("""
            UPDATE sales.quotation_item SET model = NULL
             WHERE quotation_id = :id
            """, java.util.Map.of("id", created.id()));

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รุ่น");
    }

    // ── item #7 (2026-09-14): submit requires a lead time on every TILE row ────────────────

    /** A deliberate sales-workflow rule change (owner feedback #7): submit now refuses a TILE row
     * with no lead time, naming the offending row's OWN printed number -- a draft may still be
     * saved with one missing (create/update do not enforce this at all). */
    @Test
    void submit_rejectsATileItemWithNoLeadTime_namingTheItemNumber() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(itemMissing(ItemInputBuilder::withNoLeadTime))), salesActor);

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุระยะเวลานำเข้า")
            .hasMessageContaining("รายการที่ 1");
    }

    /** Multiple offending rows are named together, comma-separated, by their OWN seq numbers --
     * not just "something is wrong". */
    @Test
    void submit_rejectsMultipleTileItemsWithNoLeadTime_namingEveryItemNumber() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),
                itemMissing(ItemInputBuilder::withNoLeadTime),
                itemMissing(ItemInputBuilder::withNoLeadTime))),
            salesActor);

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 2, 3")
            // Item 1 (sampleItem) HAS a lead time -- it must not be named alongside the two that don't.
            .satisfies(e -> assertThat(((ApiException) e).getMessage()).doesNotContain("รายการที่ 1,"));
    }

    /** Wrong-way-round twin of the rejection above: PLAIN and ADJUSTMENT rows are EXEMPT -- neither
     * has a lead-time concept at all (freight/consumables/a ส่วนลดพิเศษ line cannot "arrive"), so a
     * document made of a TILE row (with a lead time) plus PLAIN/ADJUSTMENT rows with none still
     * submits. */
    @Test
    void submit_stillAcceptsPlainAndAdjustmentRowsWithNoLeadTime() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),
                plainItem("ค่าขนส่ง", "1", "JOB", "500.00"),
                adjustmentPctItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    /** Defence-in-depth twin of {@link #submit_reChecksStoredItems_incompleteRowIsBadRequest}: the
     * lead-time gate also re-checks the STORED rows, not just what create/update most recently
     * wrote -- proven by nulling it out straight through the repository, the way a pre-#7 row
     * would exist. */
    @Test
    void submit_reChecksStoredItems_missingLeadTimeIsBadRequest() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        jdbc.update("""
            UPDATE sales.quotation_item SET lead_time_min_days = NULL, lead_time_max_days = NULL
             WHERE quotation_id = :id
            """, java.util.Map.of("id", created.id()));

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุระยะเวลานำเข้า");
    }

    // ── Wording-scan fix 3 (2026-09-17): an ENTERED lead-time range must be min>=1, max>=min ────

    /** {@code ประมาณ 0-3 วัน} saved on 2 approved quotations, and {@code 90-75}/negatives would too
     * -- refused on save now, TILE row, the exact message the frontend validator mirrors. */
    @Test
    void create_refusesATileLeadTimeMinBelowOne() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withLeadTime(0, 3)))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1")
            .hasMessageContaining("ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด");
    }

    /** Same rule, the "max < min" half -- "90-75" is a range running backwards. */
    @Test
    void create_refusesATileLeadTimeMaxBelowMin() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withLeadTime(90, 75)))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด");
    }

    /** A negative min is refused the same way as zero. */
    @Test
    void create_refusesANegativeTileLeadTimeMin() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withLeadTime(-5, 10)))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด");
    }

    /** The SAME rule applies to a PLAIN (สินค้า/บริการอื่น) row's optional lead time, not only TILE. */
    @Test
    void create_refusesAnInvalidLeadTimeOnAPlainRowToo() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(plainItemWithLeadTime("สุขภัณฑ์", "1", "ชุด", "5000.00", 0, 3))),
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด");
    }

    /** Wrong-way-round: a valid range (including the min==max exact-day case) is untouched -- this
     * rule refuses only genuinely invalid values, never a well-formed one. */
    @Test
    void create_acceptsAValidLeadTimeRange_minEqualsMaxIncluded() {
        DealQuotationDto range = quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withLeadTime(75, 90)))), salesActor);
        assertThat(range.items().get(0).leadTimeMinDays()).isEqualTo(75);
        DealQuotationDto exact = quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withLeadTime(1, 1)))), salesActor);
        assertThat(exact.items().get(0).leadTimeMinDays()).isEqualTo(1);
        assertThat(exact.items().get(0).leadTimeMaxDays()).isEqualTo(1);
    }

    /** A lone value (the OTHER field still blank) is an INCOMPLETENESS question, not an invalid
     * one -- this rule must not fire until BOTH fields are entered (the "is it required at all"
     * rules are unchanged and untested here; see the #7 section above). */
    @Test
    void create_doesNotValidateAPartiallyEnteredLeadTime() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withLeadTime(0, null)))), salesActor);
        assertThat(created.items().get(0).leadTimeMinDays()).isEqualTo(0);
        assertThat(created.items().get(0).leadTimeMaxDays()).isNull();
    }

    // ── Wording-scan fix 4 (2026-09-17): an AREA-mode row that computes to ZERO pieces ──────────

    /** {@code (พื้นที่ 0.01 ตร.ม.ๆละ 2.78 แผ่น รวม 0 แผ่น ...)} saved today -- a document line
     * selling nothing. sqmPerPiece 0.36 (sampleItem's fixed value) -> ~2.78 pcs/sqm; 0.01 sqm
     * rounds to 0 pieces before wastage (round2(0.01 x 2.78) = round(0.0278) = 0). */
    @Test
    void create_refusesAnAreaThatComputesToZeroPieces() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withAreaMode(new BigDecimal("0.01"))))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1")
            .hasMessageContaining("พื้นที่น้อยเกินไป คำนวณได้ 0 แผ่น");
    }

    /** The same check on the LENIENT calculate-line preview -- consistent with how every other
     * item error already behaves there (#requirePriceValidForType's own precedent). */
    @Test
    void calculateLine_refusesAnAreaThatComputesToZeroPieces() {
        assertThatThrownBy(() -> quotationService.calculateLine(
            itemMutated(m -> m.withAreaMode(new BigDecimal("0.01"))), "TH", salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("พื้นที่น้อยเกินไป คำนวณได้ 0 แผ่น");
    }

    /** Wrong-way-round: an area that rounds to exactly 1 piece (not 0) is accepted -- this rule
     * refuses only a genuine zero, never a small-but-real quantity. */
    @Test
    void create_acceptsAnAreaThatComputesToExactlyOnePiece() {
        // 0.36 sqm/piece (sampleItem) -> 2.78 pcs/sqm; 0.36 sqm x 2.78 = 1.0008 -> rounds to 1.
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m -> m.withAreaMode(new BigDecimal("0.36"))))), salesActor);
        assertThat(created.items().get(0).piecesBeforeWastage()).isEqualTo(1);
    }

    // ── Wording-scan fix 5 (2026-09-17): PIECES wastage must be a whole number ────────────────

    /** {@code + เผื่อ 0.5 แผ่น} saved today. Refused now, whole-number PIECES wastage only. */
    @Test
    void create_refusesAFractionalPiecesWastageValue() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m ->
                m.withWastage(WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("0.5"))))),
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายการที่ 1")
            .hasMessageContaining("จำนวนแผ่นที่เผื่อต้องเป็นจำนวนเต็ม");
    }

    /** Wrong-way-round: a whole-number PIECES wastage (including one written "2.00") is untouched. */
    @Test
    void create_acceptsAWholeNumberPiecesWastageValue_evenWithATrailingZeroScale() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m ->
                m.withWastage(WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("2.00"))))),
            salesActor);
        assertThat(created.items().get(0).piecesAfterWastage())
            .isEqualTo(created.items().get(0).piecesBeforeWastage() + 2);
    }

    /** PERCENT wastage keeps accepting decimals -- this rule is PIECES-mode only. */
    @Test
    void create_stillAcceptsAFractionalPercentWastageValue() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(itemMutated(m ->
                m.withWastage(WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("2.5"))))),
            salesActor);
        assertThat(created.items().get(0)).isNotNull();
    }

    // ── Wording-scan fix 6 (2026-09-17): CREDIT remainder needs at least 1 credit day ──────────

    /** {@code ส่วนที่เหลือเครดิต 0 วัน} can print today. An explicit 0 (or negative) is refused on
     * SAVE (create/update), matching the "invalid value" half of the rule; a BLANK value still
     * saves (see {@link #submit_refusesACreditRemainderWithNoCreditDays} for the submit-time half,
     * mirroring how {@code fullPaymentTerm} is gated for a zero-deposit document). */
    @Test
    void create_refusesZeroCreditDaysWhenRemainderModeIsCredit() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 0, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))),
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
    }

    /** A negative creditDays is refused the same way (bean {@code @Min(0)} already blocks anything
     * below -1... this proves the SERVICE rule, not the bean bound, is what actually names zero). */
    @Test
    void update_refusesZeroCreditDaysWhenRemainderModeIsCredit() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        assertThatThrownBy(() -> quotationService.update(created.id(),
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 0, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))),
            salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
    }

    /** The blank-on-draft half: create/update still accept a CREDIT remainder with NO creditDays
     * typed yet (a draft may be incomplete) -- only {@link #submit} refuses to advance it, the
     * EXACT "create/update permissive, submit strict" split {@code fullPaymentTerm} already uses. */
    @Test
    void create_stillAcceptsACreditRemainderWithBlankCreditDays() {
        DealQuotationDto created = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", null, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))),
            salesActor);
        assertThat(created.remainderMode()).isEqualTo("CREDIT");
        assertThat(created.creditDays()).isNull();
    }

    /** The submit-time half: a CREDIT-remainder draft with creditDays still blank may not advance
     * past DRAFT. */
    @Test
    void submit_refusesACreditRemainderWithNoCreditDays() {
        DealQuotationDto created = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", null, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))),
            salesActor);

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
    }

    /** Wrong-way-round: a positive creditDays, or a non-CREDIT remainder mode with creditDays left
     * however it likes, is completely untouched by this rule. */
    @Test
    void create_acceptsAPositiveCreditDays_andANonCreditRemainderModeRegardlessOfCreditDays() {
        DealQuotationDto credit = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 1, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))),
            salesActor);
        assertThat(credit.creditDays()).isEqualTo(1);

        DealQuotationDto delivery = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "ON_DELIVERY", 0, 30,
                "หมายเหตุทดสอบ", List.of(sampleItem("100.00", 10))),
            salesActor);
        assertThat(delivery.remainderMode()).isEqualTo("ON_DELIVERY");
    }

    /** Builder-shaped helper over the complete {@link #sampleItem} fixture, for one-field-at-a-time
     * incompleteness tests -- avoids a 21-argument constructor call per test case. */
    private ItemInput itemMissing(java.util.function.UnaryOperator<ItemInputBuilder> mutate) {
        return mutate.apply(new ItemInputBuilder(sampleItem("100.00", 10))).build();
    }

    /** Same builder, different name for a mutation that is not about INCOMPLETENESS (a value the
     * validation rejects while every required field is still present) -- shares the exact same
     * fixture and mechanism as {@link #itemMissing}. */
    private ItemInput itemMutated(java.util.function.UnaryOperator<ItemInputBuilder> mutate) {
        return mutate.apply(new ItemInputBuilder(sampleItem("100.00", 10))).build();
    }

    /** Minimal wither-style builder over {@link ItemInput} — this record has no canonical builder,
     * and a 21-argument constructor call per single-field mutation is exactly the kind of test
     * code a field reorder would silently miscompile without ever failing to compile. */
    private static final class ItemInputBuilder {
        private String locationLabel; private Long catalogPriceId; private String productCode;
        private String brand; private String model; private String color; private String texture;
        private String sizeText; private BigDecimal thicknessMm; private BigDecimal sqmPerPiece;
        private String quantityMode; private BigDecimal areaSqm; private Integer piecesInput;
        private String wastageMode; private BigDecimal wastageValue; private Integer piecesPerBox;
        private BigDecimal unitPrice; private BigDecimal discountPct; private String originCountry;
        private Integer leadTimeMinDays; private Integer leadTimeMaxDays; private String itemNotes;

        ItemInputBuilder(ItemInput src) {
            locationLabel = src.locationLabel(); catalogPriceId = src.catalogPriceId();
            productCode = src.productCode(); brand = src.brand(); model = src.model();
            color = src.color(); texture = src.texture(); sizeText = src.sizeText();
            thicknessMm = src.thicknessMm(); sqmPerPiece = src.sqmPerPiece();
            quantityMode = src.quantityMode(); areaSqm = src.areaSqm(); piecesInput = src.piecesInput();
            wastageMode = src.wastageMode(); wastageValue = src.wastageValue(); piecesPerBox = src.piecesPerBox();
            unitPrice = src.unitPrice(); discountPct = src.discountPct(); originCountry = src.originCountry();
            leadTimeMinDays = src.leadTimeMinDays(); leadTimeMaxDays = src.leadTimeMaxDays(); itemNotes = src.itemNotes();
        }

        ItemInputBuilder withModel(String v) { model = v; return this; }
        ItemInputBuilder withColor(String v) { color = v; return this; }
        ItemInputBuilder withTexture(String v) { texture = v; return this; }
        ItemInputBuilder withSizeText(String v) { sizeText = v; return this; }
        ItemInputBuilder withThicknessMm(BigDecimal v) { thicknessMm = v; return this; }
        ItemInputBuilder withSqmPerPiece(BigDecimal v) { sqmPerPiece = v; return this; }
        ItemInputBuilder withPiecesPerBox(Integer v) { piecesPerBox = v; return this; }
        ItemInputBuilder withUnitPrice(BigDecimal v) { unitPrice = v; return this; }
        ItemInputBuilder withPiecesInput(Integer v) { piecesInput = v; return this; }
        // #7 (2026-09-14): submit's new lead-time requirement.
        ItemInputBuilder withNoLeadTime() { leadTimeMinDays = null; leadTimeMaxDays = null; return this; }
        // Wording-scan fix 3 (2026-09-17): an explicit (possibly invalid) lead-time range.
        ItemInputBuilder withLeadTime(Integer min, Integer max) { leadTimeMinDays = min; leadTimeMaxDays = max; return this; }
        // Wording-scan fix 4 (2026-09-17): AREA quantity mode with a caller-chosen area.
        ItemInputBuilder withAreaMode(BigDecimal area) {
            quantityMode = WastageCalculator.QUANTITY_MODE_AREA; areaSqm = area; piecesInput = null; return this;
        }
        // Wording-scan fix 5 (2026-09-17): a caller-chosen wastage mode/value.
        ItemInputBuilder withWastage(String mode, BigDecimal value) { wastageMode = mode; wastageValue = value; return this; }

        ItemInput build() {
            return new ItemInput(locationLabel, catalogPriceId, productCode, brand, model, color, texture,
                sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode,
                wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes);
        }
    }

    @Test
    void cancel_onlyFromDraft() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto cancelled = quotationService.cancel(created.id(), new CancelRequest("ลูกค้ายกเลิก"), salesActor);
        assertThat(cancelled.docStatus()).isEqualTo(QuotationStatus.CANCELLED);

        DealQuotationDto submittedOne = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        quotationService.submit(submittedOne.id(), salesActor);
        assertThatThrownBy(() -> quotationService.cancel(submittedOne.id(), new CancelRequest(null), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    void totalsAreServerRecomputed_fromCurrentItemsOnly() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10), sampleItem("50.00", 4))), salesActor);
        // 1000 + 200 = 1200
        assertThat(created.subtotalAmount()).isEqualByComparingTo("1200.00");

        DealQuotationDto updated = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("10.00", 3))), salesActor);
        assertThat(updated.subtotalAmount()).isEqualByComparingTo("30.00");
        assertThat(updated.items()).hasSize(1);
    }

    /** {@code findItemsForQuotations} batches every id's items into ONE query, ordered
     * {@code (quotation_id, seq)}, via a RowCallbackHandler -- Spring has ALREADY called
     * {@code rs.next()} to position the ResultSet on the current row before invoking it. A stray
     * inner {@code while (rs.next())} there re-advances past that row and consumes the rest of the
     * result set in one call, so Spring's own outer loop finds nothing left and stops -- net effect,
     * the very FIRST row of the WHOLE result set is silently dropped on every {@code hydrate} call,
     * regardless of how many ids it was asked for. Two quotations here only make the fixture
     * legible (which row is "first" is otherwise arbitrary) -- the SAME bug drops the first line
     * item of a SINGLE quotation too, e.g. {@code findByTicket} on a ticket with exactly one
     * quotation renders that quotation missing its own first item. {@code search} here (ceo reads
     * every deal, unscoped -- see {@code LIST_SEE_ALL_ROLES}, owner ruling 2026-09-24) routes
     * through {@code DealQuotationService#search -> DealQuotationRepository#hydrate ->
     * #findItemsForQuotations}, the same path {@code findByTicket} and the approver queue use.
     * {@link #findById} (single-quotation {@code GET})
     * uses the unbatched {@code #findItems} instead and is unaffected -- this test is what proves
     * the batched path specifically. */
    @Test
    void search_returnsEveryItemAcrossMultipleQuotations_firstQuotationsSeqOneItemNotDropped() {
        DealQuotationDto first = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("111.00", 1), sampleItem("222.00", 1))), salesActor);
        DealQuotationDto second = quotationService.create(otherTicketId,
            upsertRequest(List.of(sampleItem("333.00", 1), sampleItem("444.00", 1))), otherSalesActor);
        // The bug is keyed on ORDER BY quotation_id -- confirm which one sorts first so the
        // assertions below are checking the row the bug actually drops, not by luck.
        assertThat(first.id()).isLessThan(second.id());

        // ceoActor, NOT importActor (owner ruling 2026-09-24): the global list is now scoped to
        // sales_manager/ceo for "see everything" -- import is scoped to its own deals (created
        // none here), which would make this fixture's own assertions vacuous.
        List<DealQuotationDto> results = quotationService.search(null, false, ceoActor);

        // Ties the fixture to the bug's actual trigger (the SMALLEST quotation_id in the whole
        // result set, not just relative to `second`) -- guards against this test going silently
        // vacuous if a future fixture/migration ever seeds another DEAL_DIRECT quotation with a
        // lower id than `first`, which would make some OTHER row the one the bug drops.
        assertThat(first.id()).isEqualTo(
            results.stream().mapToLong(DealQuotationDto::id).min().orElseThrow());

        DealQuotationDto hydratedFirst = results.stream()
            .filter(q -> q.id() == first.id()).findFirst().orElseThrow();
        DealQuotationDto hydratedSecond = results.stream()
            .filter(q -> q.id() == second.id()).findFirst().orElseThrow();
        assertThat(hydratedFirst.items())
            .as("seq-1 item of the quotation with the SMALLEST quotation_id must not be dropped")
            .extracting(DealQuotationItemDto::seq).containsExactly(1, 2);
        assertThat(hydratedFirst.items())
            .extracting(DealQuotationItemDto::unitPrice)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("111.00"), new BigDecimal("222.00"));
        assertThat(hydratedSecond.items()).extracting(DealQuotationItemDto::seq).containsExactly(1, 2);
    }

    @Test
    void renderPdfAndXlsx_nonEmptyBytes() {
        // H1: a locationLabel forces QuotationRenderer down its heading-row path
        // (underlinedStyle/writeHeadingRow) on BOTH calls below -- renderXlsx then renderPdf,
        // through the SAME injected QuotationRenderer instance (wired once in @BeforeEach, like
        // the real @Component singleton) -- which is exactly the "second render through one
        // renderer instance with a heading" shape the underlineCache regression needed to throw.
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("ชั้น 1", "100.00", 10))), salesActor);

        byte[] xlsx = quotationService.renderXlsx(created.id(), salesActor);
        assertThat(xlsx).isNotEmpty();
        // OLE2 (Compound File Binary Format) magic bytes -- the legacy .xls format QuotationRenderer
        // actually emits (th.co.glr.hr.ticket.QuotationRenderer#toXlsx wraps toXls's HSSF bytes).
        assertThat(xlsx).startsWith((byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1);

        Assumptions.assumeTrue(ChromiumPdfPrinter.isAvailable() || LibreOfficePdfConverter.isAvailable(),
            "neither Chromium nor LibreOffice available locally");
        byte[] pdf = quotationService.renderPdf(created.id(), salesActor);
        assertThat(pdf).isNotEmpty();
        assertThat(pdf[0]).isEqualTo((byte) '%');
        assertThat(pdf[1]).isEqualTo((byte) 'P');
        assertThat(pdf[2]).isEqualTo((byte) 'D');
        assertThat(pdf[3]).isEqualTo((byte) 'F');
    }

    /** Extends the render coverage onto an APPROVED quotation (a DRAFT prints no approver name at
     * all) -- asserts the approver's name reaches the PDF and that the item's calculation line
     * (DealQuotationLines#calculationLine, served on the DTO) is the text that actually printed. */
    @Test
    void renderPdf_approvedQuotation_showsApproverNameAndCalculationLine() throws Exception {
        Assumptions.assumeTrue(ChromiumPdfPrinter.isAvailable() || LibreOfficePdfConverter.isAvailable(),
            "neither Chromium nor LibreOffice available locally");
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        // sampleItem(PIECES, wastage NONE, no piecesPerBox): calculationLine = "(จำนวน 10 แผ่น)"
        // (F1 fix, 2026-09-16 review: no longer "(จำนวน 10 แผ่น = 10 แผ่น)" -- that trailing "="
        // was a pure echo of the count already stated, with no box or wastage to justify it).
        String expectedCalcLine = approved.items().get(0).calculationLine();
        assertThat(expectedCalcLine).contains("จำนวน 10 แผ่น");

        byte[] pdf = quotationService.renderPdf(approved.id(), salesActor);
        String text = new org.apache.pdfbox.text.PDFTextStripper()
            .getText(org.apache.pdfbox.Loader.loadPDF(pdf));
        String flat = text.replaceAll("\\s+", "");

        // approvedByName == salesManagerActor's employee row's nameTh ("ผู้จัดการฝ่ายขาย คิว").
        assertThat(approved.approvedByName()).isNotBlank();
        assertThat(flat).contains("(" + approved.approvedByName().replaceAll("\\s+", "") + ")");
        assertThat(flat).contains(expectedCalcLine.replaceAll("\\s+", ""));
        assertThat(flat).contains("พนักงานขาย");
        assertThat(flat).contains("ผู้จัดการฝ่ายขาย");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Owner feedback pass 1 (2026-09-10) — F2 ผู้สั่งซื้อ: mandatory, snapshotted (V167)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_defaultsToTheDealsContact_andSnapshotsNamePhoneEmail() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.contactId()).isEqualTo(contact.id());
        assertThat(created.contactName()).isEqualTo("สมหญิง ใจดี");
        assertThat(created.contactPhone()).isEqualTo("081-111-2222");
        assertThat(created.contactEmail()).isEqualTo("somying@customer.test");
    }

    /** The snapshot is FROZEN: editing the contact row afterwards must not change what the
     * quotation carries (or prints) — an approved, emailed document keeps the name it was
     * approved with. Proven by rewriting the contact row directly and re-reading. */
    @Test
    void contactSnapshot_isFrozen_laterContactEditDoesNotChangeTheQuotation() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        jdbc.update("""
            UPDATE customers.contact SET first_name = 'สมชาย', last_name = 'เปลี่ยนแล้ว', phone = '099-000-0000'
             WHERE contact_id = :id
            """, java.util.Map.of("id", contact.id()));

        DealQuotationDto reread = quotationService.get(created.id(), salesActor);
        assertThat(reread.contactName()).isEqualTo("สมหญิง ใจดี");
        assertThat(reread.contactPhone()).isEqualTo("081-111-2222");
        // And the list/search hydration path reads the same frozen columns.
        assertThat(quotationService.search(null, false, salesActor))
            .filteredOn(q -> q.id() == created.id())
            .extracting(DealQuotationDto::contactName).containsExactly("สมหญิง ใจดี");
    }

    /**
     * Owner feedback F7 (2026-09-10): the deal card now edits the SELECTED customer's
     * เลขที่ผู้เสียภาษี / โทร. in place, and promises "the values on screen at save time". That was
     * false while {@code updateHeader} left the four {@code customer_*} columns alone — correcting
     * a wrong tax id and re-saving the draft still printed the old one. Wrong-way-round half: the
     * freeze must STILL hold once the document is approved, which is the whole reason the snapshot
     * exists.
     */
    @Test
    void customerSnapshot_isReSnapshottedOnEveryDraftSave_butFrozenOnceApproved() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.customerTaxId()).isEqualTo("0100000000099");
        assertThat(created.customerPhone()).isEqualTo("02-999-9999");

        // The rep corrects the customer master (exactly what PUT /api/customers/{id} does) …
        customers.update(customer.id(), null, "0105566778899", null, null, "02-777-7777");
        // … and re-saves the DRAFT.
        DealQuotationDto resaved = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(resaved.customerTaxId()).as("the correction reached the snapshot").isEqualTo("0105566778899");
        assertThat(resaved.customerPhone()).isEqualTo("02-777-7777");
        assertThat(renderedStrings(resaved.id()))
            .as("and the rendered document prints it, not the stale one")
            .anyMatch(s -> s.contains("0105566778899"))
            .anyMatch(s -> s.contains("02-777-7777"))
            .noneMatch(s -> s.contains("0100000000099"));

        // Approve, then correct the customer AGAIN: the approved document must not move.
        DealQuotationDto approved = quotationService.approve(
            quotationService.submit(resaved.id(), salesActor).id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        customers.update(customer.id(), null, "0999999999999", null, null, "02-000-0000");

        DealQuotationDto reread = quotationService.get(approved.id(), salesActor);
        assertThat(reread.customerTaxId()).as("frozen at approval").isEqualTo("0105566778899");
        assertThat(reread.customerPhone()).isEqualTo("02-777-7777");
        assertThat(renderedStrings(reread.id()))
            .anyMatch(s -> s.contains("0105566778899"))
            .noneMatch(s -> s.contains("0999999999999"));
        // And the DRAFT-only WHERE clause is what enforces it — an update at all is a 409 here.
        assertThatThrownBy(() -> quotationService.update(approved.id(),
                upsertRequest(List.of(sampleItem("100.00", 10))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    /** Opus review fix (2026-09-14): the SAME re-snapshot guarantee as the test above, but for the
     * customer's NAME specifically -- {@code customerSnapshot} used to take the name from the
     * ticket's own frozen column unconditionally (never re-read), which silently defeated the
     * owner's "correct a typo in the name" request (CustomerDetailsFields' new ชื่อลูกค้า field):
     * the correction reached {@code customers.customer.name} but never the printed document. */
    @Test
    void customerSnapshot_nameIsReSnapshottedOnEveryDraftSave_butFrozenOnceApproved() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        String originalName = created.customerName();

        // The rep corrects a typo in the customer master (exactly what the new ชื่อลูกค้า field's
        // PUT /api/customers/{id} does) …
        customers.update(customer.id(), "บริษัท ชื่อที่ถูกต้อง จำกัด", null, null, null, null);
        // … and re-saves the DRAFT.
        DealQuotationDto resaved = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(resaved.customerName()).as("the correction reached the snapshot")
            .isEqualTo("บริษัท ชื่อที่ถูกต้อง จำกัด");
        assertThat(renderedStrings(resaved.id()))
            .as("and the rendered document prints it, not the stale one")
            .anyMatch(s -> s.contains("บริษัท ชื่อที่ถูกต้อง จำกัด"))
            .noneMatch(s -> s.contains(originalName));

        // Approve, then correct the name AGAIN: the approved document must not move.
        DealQuotationDto approved = quotationService.approve(
            quotationService.submit(resaved.id(), salesActor).id(), new ApproveRequest(null), salesManagerActor);
        customers.update(customer.id(), "บริษัท เปลี่ยนอีกครั้ง จำกัด", null, null, null, null);

        DealQuotationDto reread = quotationService.get(approved.id(), salesActor);
        assertThat(reread.customerName()).as("frozen at approval").isEqualTo("บริษัท ชื่อที่ถูกต้อง จำกัด");
        assertThat(renderedStrings(reread.id()))
            .anyMatch(s -> s.contains("บริษัท ชื่อที่ถูกต้อง จำกัด"))
            .noneMatch(s -> s.contains("บริษัท เปลี่ยนอีกครั้ง จำกัด"));
    }

    // The documented reason `customerSnapshot`'s name falls back to the ticket's own frozen
    // column at all -- a deal with no resolvable customer row should still print the name it was
    // created with, not a blank -- has NO reachable test through the public service today, tried
    // two ways and confirmed both closed:
    //   1. A real DELETE of the referenced customer row: refused by a live FK
    //      (`ticket_customer_id_fkey`) while any ticket still points at it.
    //   2. Nulling `sales.ticket.customer_id` directly: `resolveContact`'s own
    //      `ticket.customerId() != null && ...` guard (DealQuotationService.java:896) throws
    //      "กรุณาระบุผู้สั่งซื้อ" before `customerSnapshot` is ever reached, for the SAME reason.
    // So this fallback is unreachable dead-code-safety today, not a live branch -- worth flagging
    // in the PR body rather than forcing a test around it.

    /** Every string cell of the rendered XLS, so an assertion can ask "does the document say X"
     * without hardcoding which cell the adapter happens to put it in. */
    private List<String> renderedStrings(long quotationId) {
        byte[] xls = quotationService.renderXlsx(quotationId, salesActor);
        List<String> out = new java.util.ArrayList<>();
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xls))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            for (var row : sheet) {
                for (var cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        out.add(cell.getStringCellValue());
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Test
    void create_withoutAnyResolvableContact_isBadRequest() {
        ProjectDto project = projects.create(customer.id(), "โครงการไม่มีผู้ติดต่อ");
        long noContactTicket = createTicket("ดีลไม่มีผู้สั่งซื้อ", project.id(), null, salesActor);
        assertThatThrownBy(() -> quotationService.create(noContactTicket,
                upsertRequest(List.of(sampleItem("100.00", 10))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessage("กรุณาระบุผู้สั่งซื้อ");
        assertThat(quotationRepository.findByTicket(noContactTicket)).as("nothing was inserted").isEmpty();
    }

    /** An explicit {@code contactId} overrides the deal's default — and a contact of ANOTHER
     * customer is refused with the same 400, wrong-way-round. */
    @Test
    void create_withExplicitContact_usesIt_andRefusesAnotherCustomersContact() {
        ContactDto second = contacts.create(customer.id(), "วิชัย", null, null, null, "082-222-3333");
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(second.id(), List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.contactId()).isEqualTo(second.id());
        assertThat(created.contactName()).isEqualTo("วิชัย");
        assertThat(created.contactEmail()).isNull();

        CustomerDto otherCustomer = customers.create("บริษัท อื่น จำกัด", "0100000000098", "1 ถนนอื่น", "สนญ.", null);
        ContactDto foreign = contacts.create(otherCustomer.id(), "คนนอก", "ลูกค้าอื่น", null, null, null);
        assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(foreign.id(), List.of(sampleItem("100.00", 10))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessage("กรุณาระบุผู้สั่งซื้อ");
        // A contact id that does not exist at all: same answer, nothing leaks.
        assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(999_999_999L, List.of(sampleItem("100.00", 10))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    /** update: an omitted {@code contactId} keeps the draft's own contact (re-snapshotted from the
     * row as it stands now); an explicit one switches it. */
    @Test
    void update_keepsTheDraftsContactWhenOmitted_andSwitchesOnAnExplicitId() {
        ContactDto second = contacts.create(customer.id(), "วิชัย", "สลับ", null, null, null);
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(second.id(), List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.contactId()).isEqualTo(second.id());

        DealQuotationDto kept = quotationService.update(created.id(),
            upsertRequest(null, List.of(sampleItem("200.00", 5))), salesActor);
        assertThat(kept.contactId()).as("omitted -> the draft's own contact, not the ticket's").isEqualTo(second.id());

        DealQuotationDto switched = quotationService.update(created.id(),
            upsertRequest(contact.id(), List.of(sampleItem("200.00", 5))), salesActor);
        assertThat(switched.contactId()).isEqualTo(contact.id());
        assertThat(switched.contactName()).isEqualTo("สมหญิง ใจดี");
    }

    // ── owner feedback 2026-09-14: โครงการ (project name) is now editable, not write-once ──────

    /** Every OTHER test in this class omits {@code projectName} entirely (the oldest legacy
     * constructor shapes) and still passes -- that already proves the create-time fallback to
     * {@code ticket.projectName()} is unbroken. This pins the other half: an EXPLICIT value wins. */
    @Test
    void create_withAnExplicitProjectName_usesItInsteadOfTheTickets() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithProjectName("โครงการทดสอบ A", List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.projectName()).isEqualTo("โครงการทดสอบ A");
    }

    @Test
    void update_correctsATypoInTheProjectName_andRoundTripsOnRead() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithProjectName("ABC", List.of(sampleItem("100.00", 10))), salesActor);

        DealQuotationDto corrected = quotationService.update(created.id(),
            upsertRequestWithProjectName("Associates By Choice", List.of(sampleItem("200.00", 5))), salesActor);
        assertThat(corrected.projectName()).isEqualTo("Associates By Choice");

        DealQuotationDto reread = quotationService.get(created.id(), salesActor);
        assertThat(reread.projectName()).isEqualTo("Associates By Choice");
    }

    /** No "missing keeps stored" for this field (unlike priceMode/documentLanguage/validityMode):
     * the editor always sends its current value, blank included, so a blank is a deliberate clear
     * -- same discipline as customerNotes/deptCode/unitCode on the same call. */
    @Test
    void update_withABlankProjectName_clearsIt() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithProjectName("โครงการเดิม", List.of(sampleItem("100.00", 10))), salesActor);

        DealQuotationDto cleared = quotationService.update(created.id(),
            upsertRequestWithProjectName("", List.of(sampleItem("200.00", 5))), salesActor);
        assertThat(cleared.projectName()).isNull();
    }

    /** submit is the last gate: a row that predates V167 (no snapshot) cannot go to an approver.
     * Reproduced by blanking the snapshot straight in the DB. */
    @Test
    void submit_refusesARowWithNoContactSnapshot() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        jdbc.update("UPDATE sales.quotation SET contact_id = NULL, contact_name = NULL WHERE quotation_id = :id",
            java.util.Map.of("id", created.id()));
        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessage("กรุณาระบุผู้สั่งซื้อ");
    }

    @Test
    void createRevision_copiesTheParentsContactSnapshotVerbatim() {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        jdbc.update("UPDATE customers.contact SET first_name = 'เปลี่ยน' WHERE contact_id = :id",
            java.util.Map.of("id", contact.id()));
        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.contactId()).isEqualTo(contact.id());
        assertThat(revision.contactName()).as("the parent's frozen name, not the live row").isEqualTo("สมหญิง ใจดี");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Owner feedback pass 1 — F2/F4 on the rendered document: slot 4 name + the dates row
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The 0-based index of the signature LABELS row in a rendered sheet, found by its own text —
     * the footer block floats with the item count and the remark layout, so no fixed row number
     * is safe here (the renderer's own unit tests pin 44 only for their single-remark fixture). */
    private static int labelsRow(org.apache.poi.ss.usermodel.Sheet sheet) {
        for (var row : sheet) {
            var cell = row.getCell(0);
            if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                && cell.getStringCellValue().contains("ผู้พิมพ์") && cell.getStringCellValue().contains("ผู้สั่งซื้อ")) {
                return row.getRowNum();
            }
        }
        throw new AssertionError("signature labels row not found in the rendered XLS");
    }

    /** Reads the three signature rows (labels, names, dates) out of the rendered XLS. */
    private String[] signatureRows(byte[] xls) throws Exception {
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xls))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            int r = labelsRow(sheet);
            return new String[]{
                sheet.getRow(r).getCell(0).getStringCellValue(),
                sheet.getRow(r + 1).getCell(0).getStringCellValue(),
                sheet.getRow(r + 2).getCell(0).getStringCellValue()};
        }
    }

    private static String thaiShort(java.time.Instant at) {
        LocalDate d = at.atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalDate();
        return "วันที่ " + d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + (d.getYear() + 543);
    }

    /** Owner-directed reversal of F2 (2026-09-26): a DRAFT with a contact snapshot but no manual
     * orderedByName must print the DOTTED placeholder in slot 4, never the contact's name any
     * more -- this is the F2-reversal regression guard, end-to-end through the real service and
     * repository (not just the adapter unit tests). */
    @Test
    void render_draft_neverAutoFillsContactNameInSlot4_evenThoughOneIsRecorded() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.contactName()).as("a contact IS recorded on this deal").isEqualTo("สมหญิง ใจดี");
        String[] rows = signatureRows(quotationService.renderXlsx(created.id(), salesActor));
        String names = rows[1];
        String dates = rows[2];
        assertThat(names).doesNotContain("(สมหญิง ใจดี)");
        // Draft: no approver name yet AND no manual ผู้สั่งซื้อ name -- two dotted placeholders.
        assertThat(names.split("\\(\\.{26}\\)", -1)).hasSize(3);
        // Dates: ผู้พิมพ์ = created date; the other three slots stay dotted.
        assertThat(dates).contains(thaiShort(created.createdAt()));
        assertThat(dates.split("วันที่........./........./.........", -1)).as("three dotted date slots").hasSize(4);
    }

    /** The manual override this reversal adds: a rep-typed {@code orderedByName} on the request
     * reaches the printed signature slot exactly as typed, end-to-end (create -> render), even
     * though the deal ALSO carries a contact snapshot the slot must NOT fall back to. */
    @Test
    void render_draft_printsTheManualOrderedByNameWhenSet_notTheContactName() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithOrderedByName("คุณวิชัย มั่นคง", List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.orderedByName()).isEqualTo("คุณวิชัย มั่นคง");
        assertThat(created.contactName()).as("still a contact IS recorded on this deal").isEqualTo("สมหญิง ใจดี");
        String[] rows = signatureRows(quotationService.renderXlsx(created.id(), salesActor));
        String names = rows[1];
        assertThat(names).contains("(คุณวิชัย มั่นคง)").doesNotContain("(สมหญิง ใจดี)");
    }

    @Test
    void render_approved_printsAllThreeDates_andOrderedByStaysDottedWithNoManualName() throws Exception {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        String[] rows = signatureRows(quotationService.renderXlsx(approved.id(), salesActor));
        String dates = rows[2];
        assertThat(dates).contains(thaiShort(approved.createdAt()));
        assertThat(dates).contains(thaiShort(approved.submittedAt()));
        assertThat(dates).contains(thaiShort(approved.approvedAt()));
        // ผู้สั่งซื้อ never gets a date -- the customer signs and dates on paper.
        assertThat(dates.split("วันที่........./........./.........", -1)).as("exactly one dotted date slot").hasSize(2);
        assertThat(dates.lastIndexOf("วันที่........./........./.........")).isGreaterThan(dates.lastIndexOf(thaiShort(approved.approvedAt())));
        // F2-reversal regression guard: NO manual name was set on this fixture, so the ผู้สั่งซื้อ
        // slot stays the dotted placeholder even though a contact ("สมหญิง ใจดี") is recorded.
        assertThat(rows[1]).contains("(" + approved.approvedByName() + ")").doesNotContain("(สมหญิง ใจดี)");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Owner feedback pass 1 — F3: the approver's signature image, whichever role approved
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Real PNG bytes (a drawn squiggle, not just magic bytes) — POI must be able to read the
     * image's dimensions for the anchor to be placed at all. */
    private MockMultipartFile realSignatureFile() {
        return new MockMultipartFile("file", "sig.png", "image/png", HtmlXlsFidelityTest.signaturePng());
    }

    /** The picture whose anchor BOTTOM sits in the signature labels row (F6: on the rule) — null
     * when none is anchored there. The letterhead/cert pictures sit near row 0 and never match. */
    private org.apache.poi.hssf.usermodel.HSSFPicture signaturePicture(byte[] xls) throws Exception {
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xls))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            int labels = labelsRow(sheet);
            var hssf = (org.apache.poi.hssf.usermodel.HSSFSheet) sheet;
            org.apache.poi.hssf.usermodel.HSSFPicture found = null;
            for (var shape : hssf.getDrawingPatriarch().getChildren()) {
                // Owner feedback F6-amended (2026-09-10): the picture's bottom now deliberately
                // crosses the underscore rule and so lands in the row BELOW the labels row —
                // this used to require row2 == labels exactly, which stopped finding it. Top at
                // or above the labels row, bottom in it or just past it; the letterhead/cert
                // images sit near row 0 and match neither.
                if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture pic
                    && pic.getClientAnchor().getRow1() <= labels
                    && pic.getClientAnchor().getRow2() >= labels
                    && pic.getClientAnchor().getRow2() <= labels + 1) {
                    found = pic;
                }
            }
            return found;
        }
    }

    @Test
    void approvedByCeo_documentCarriesTheCeosSignatureImageAndName() throws Exception {
        signatureService.upload(ceoId, realSignatureFile(), ceoActor);
        assertApproverSignatureRendered(ceoActor);
    }

    @Test
    void approvedBySalesManager_documentCarriesTheManagersSignatureImageAndName() throws Exception {
        signatureService.upload(salesManagerId, realSignatureFile(), salesManagerActor);
        assertApproverSignatureRendered(salesManagerActor);
    }

    /** Slot 3 is the approver whoever they are: the picture must be anchored in the signature
     * rows of the XLS (the same workbook both PDF engines print), and the name row must carry
     * the approver's name. The PDF half runs only where a converter exists. */
    private void assertApproverSignatureRendered(UserPrincipal approver) throws Exception {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, approver);
        assertThat(approved.approvedById()).isEqualTo(approver.id());
        assertThat(approved.approverHasSignature()).isTrue();

        byte[] xls = quotationService.renderXlsx(approved.id(), salesActor);
        var picture = signaturePicture(xls);
        assertThat(picture).as("approver signature picture anchored in the signature rows").isNotNull();
        String[] rows = signatureRows(xls);
        assertThat(rows[1]).contains("(" + approved.approvedByName() + ")");

        // A draft on the same deal (no approver yet) must NOT carry the image.
        DealQuotationDto draft = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(signaturePicture(quotationService.renderXlsx(draft.id(), salesActor))).isNull();

        Assumptions.assumeTrue(ChromiumPdfPrinter.isAvailable() || LibreOfficePdfConverter.isAvailable(),
            "neither Chromium nor LibreOffice available locally");
        byte[] pdf = quotationService.renderPdf(approved.id(), salesActor);
        String flat = new org.apache.pdfbox.text.PDFTextStripper()
            .getText(org.apache.pdfbox.Loader.loadPDF(pdf)).replaceAll("\\s+", "");
        assertThat(flat).contains("(" + approved.approvedByName().replaceAll("\\s+", "") + ")");
        int xObjects = 0;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            for (var page : doc.getPages()) {
                for (var ignored : page.getResources().getXObjectNames()) xObjects++;
            }
        }
        // Template pictures (logo + badge) plus the signature: strictly more than the two the
        // template itself embeds.
        assertThat(xObjects).isGreaterThan(2);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Owner feedback pass 1 — F5: the "แก้" bucket (needsRework) + per-status counts, owner-scoped
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Rep A: a rejected draft, a revision-in-progress draft, a plain draft, a pending one; rep B:
     * a rejected draft of their own. Wrong-way-round first: B never sees A's rework rows, and A's
     * plain DRAFT is not "rework". */
    @Test
    void needsRework_returnsRejectedAndRevisionDraftsOnly_ownerScopedForSales() {
        DealQuotationDto rejected = quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        quotationService.submit(rejected.id(), salesActor);
        quotationService.reject(rejected.id(), new RejectRequest("แก้ราคา"), salesManagerActor);
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        DealQuotationDto plainDraft = quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(), salesActor);

        DealQuotationDto othersRejected = quotationService.create(otherTicketId, upsertRequest(List.of(sampleItem("100.00", 10))), otherSalesActor);
        quotationService.submit(othersRejected.id(), otherSalesActor);
        quotationService.reject(othersRejected.id(), new RejectRequest("แก้"), salesManagerActor);

        List<Long> othersView = quotationService.search(null, true, otherSalesActor).stream().map(DealQuotationDto::id).toList();
        assertThat(othersView).as("rep B must not see rep A's rework rows").doesNotContain(rejected.id(), revision.id());
        assertThat(othersView).containsExactly(othersRejected.id());

        List<Long> ownView = quotationService.search(null, true, salesActor).stream().map(DealQuotationDto::id).toList();
        assertThat(ownView).containsExactlyInAnyOrder(rejected.id(), revision.id());
        assertThat(ownView).doesNotContain(plainDraft.id(), pending.id(), approved.id());

        // Composes with a status filter (AND): DRAFT + needsRework == the same two rows; a
        // status that excludes DRAFT yields nothing.
        assertThat(quotationService.search(List.of("DRAFT"), true, salesActor)).extracting(DealQuotationDto::id)
            .containsExactlyInAnyOrder(rejected.id(), revision.id());
        assertThat(quotationService.search(List.of("PENDING_APPROVAL"), true, salesActor)).isEmpty();

        // The approver queue (unscoped) sees every rep's rework rows.
        assertThat(quotationService.search(null, true, salesManagerActor)).extracting(DealQuotationDto::id)
            .containsExactlyInAnyOrder(rejected.id(), revision.id(), othersRejected.id());
    }

    /** Counts are the list's own sizes under the same scope — pinned by computing both. */
    @Test
    void counts_matchTheListsUnderTheSameScope_andExcludeOtherRepsRows() {
        DealQuotationDto rejected = quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        quotationService.submit(rejected.id(), salesActor);
        quotationService.reject(rejected.id(), new RejectRequest("แก้ราคา"), salesManagerActor);
        createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        quotationService.submit(quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(), salesActor);
        quotationService.cancel(quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(),
            new CancelRequest(null), salesActor);
        quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor); // plain draft
        // Rep B's rows must not leak into rep A's counts.
        DealQuotationDto othersPending = quotationService.submit(
            quotationService.create(otherTicketId, upsertRequest(List.of(sampleItem("100.00", 10))), otherSalesActor).id(), otherSalesActor);
        quotationService.approve(othersPending.id(), new ApproveRequest(null), ceoActor);

        DealQuotationCountsDto own = quotationService.counts(salesActor);
        assertThat(own.all()).isEqualTo(5);
        assertThat(own.pendingApproval()).isEqualTo(1);
        assertThat(own.needsRework()).isEqualTo(1);
        assertThat(own.cancelled()).isEqualTo(1);
        assertThat(own.approved()).isEqualTo(1);
        assertCountsMatchLists(own, salesActor);

        DealQuotationCountsDto others = quotationService.counts(otherSalesActor);
        assertThat(others.all()).as("rep B sees only their own row").isEqualTo(1);
        assertThat(others.approved()).isEqualTo(1);
        assertThat(others.needsRework()).isZero();
        assertCountsMatchLists(others, otherSalesActor);

        DealQuotationCountsDto queue = quotationService.counts(salesManagerActor);
        assertThat(queue.all()).isEqualTo(6);
        assertThat(queue.approved()).isEqualTo(2);
        assertCountsMatchLists(queue, salesManagerActor);

        // Same gate as search: a role outside VIEW_ROLES with no grant is 403.
        assertForbidden(() -> quotationService.counts(qcActor));
    }

    private void assertCountsMatchLists(DealQuotationCountsDto counts, UserPrincipal actor) {
        assertThat(counts.all()).isEqualTo(quotationService.search(null, false, actor).size());
        assertThat(counts.pendingApproval()).isEqualTo(quotationService.search(List.of("PENDING_APPROVAL"), false, actor).size());
        assertThat(counts.needsRework()).isEqualTo(quotationService.search(null, true, actor).size());
        assertThat(counts.cancelled()).isEqualTo(quotationService.search(List.of("CANCELLED"), false, actor).size());
        assertThat(counts.approved()).isEqualTo(quotationService.search(List.of("APPROVED"), false, actor).size());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — wrong-way-round (the caller must NOT reach what they should not)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void sales_cannotCreateOnAnotherRepsDeal() {
        assertForbidden(() -> quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), otherSalesActor));
    }

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
    // H5 — read-side authz: get/listForTicket/search/renderPdf, wrong-way-round.
    // Mutation-checked by deleting the owner scope in DealQuotationService#requireViewAccess AND
    // the ownerFilter branch in #search, confirming these tests (and only these) go red, then
    // reverting.
    //
    // Owner ruling 2026-09-24 (LIST_SEE_ALL_ROLES) — mutation-checked separately, by editing
    // #listOwnerScope to `return null;` (always "see all"): confirms ONLY the global-list scope
    // tests below (search_seesOnlyOwnDeals, listSeeAll_*, importAndAccount_..._butGlobalListScoped
    // ToOwn, grantHolder_search...) go red, then reverting by hand (never `git checkout`) and
    // re-running with `clean`. See the PR body for the recorded run.
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

    /** Owner ruling 2026-09-24 — positive case: sales_manager and ceo see BOTH reps' quotations
     * on the global list, unlike sales above. */
    @Test
    void listSeeAllRoles_salesManagerAndCeo_seeBothRepsQuotationsOnTheGlobalList() {
        DealQuotationDto own = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto others = quotationService.create(otherTicketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), otherSalesActor);

        for (UserPrincipal seesAll : List.of(salesManagerActor, ceoActor)) {
            List<DealQuotationDto> results = quotationService.search(null, false, seesAll);
            assertThat(results).extracting(DealQuotationDto::id).contains(own.id(), others.id());
        }
    }

    /** Owner ruling 2026-09-24, wrong-way-round: import created NOTHING (salesActor owns
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

    /** Owner ruling 2026-09-24 — the KEY new guard: a {@code canCreateQuotation} grant-holder
     * (qc, ภิญญดา's own capability, V166) may still READ any deal's quotation individually via
     * {@link DealQuotationService#get} (the grant still widens detail access, unchanged) — but on
     * the GLOBAL LIST, the grant no longer widens scope: it sees only quotations on deals IT
     * created, not {@code salesActor}'s. */
    @Test
    void grantHolder_search_seesOnlyOwnDeals_butCanStillGetAnotherRepsQuotationIndividually() {
        grantQuotationCapability(qcUserId);
        long projectId = projects.findByCustomer(customer.id()).get(0).id();
        long grantHolderTicketId = createTicket("ดีล QC คิว", projectId, contact.id(), qcActor);
        DealQuotationDto ownQuotation = quotationService.create(grantHolderTicketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), qcActor);
        DealQuotationDto salesActorsQuotation = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("200.00", 5))), salesActor);

        List<DealQuotationDto> results = quotationService.search(null, false, qcActor);
        assertThat(results).extracting(DealQuotationDto::id).contains(ownQuotation.id());
        assertThat(results).extracting(DealQuotationDto::id).doesNotContain(salesActorsQuotation.id());

        // Detail access via requireViewAccess is UNCHANGED -- the grant still lets qcActor read
        // salesActor's quotation individually, even though it is now absent from qcActor's list.
        assertThat(quotationService.get(salesActorsQuotation.id(), qcActor).id())
            .isEqualTo(salesActorsQuotation.id());

        // counts().all() must always equal the size of the list that tab would show (F5) -- same
        // scope decision, so this must equal 1 (ownQuotation only), never 2.
        assertThat(quotationService.counts(qcActor).all()).isEqualTo(results.size());
    }

    @Test
    void sales_cannotRenderPdfForAnotherRepsQuotation() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        // No LibreOffice dependency here: requireViewAccess throws INSIDE buildRenderModel, before
        // renderer.toPdf(...) is ever invoked -- see DealQuotationService#renderPdf.
        assertForbidden(() -> quotationService.renderPdf(created.id(), otherSalesActor));
    }

    @Test
    void qcWithoutGrant_cannotGetListSearchOrRenderPdf() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertForbidden(() -> quotationService.get(created.id(), qcActor));
        assertForbidden(() -> quotationService.listForTicket(ticketId, qcActor));
        assertForbidden(() -> quotationService.renderPdf(created.id(), qcActor));
        // search() with no grant and a role outside VIEW_ROLES also 403s (unlike `sales`, which
        // is IN VIEW_ROLES and gets scoped to zero rows instead -- qc has neither path open).
        assertForbidden(() -> quotationService.search(null, false, qcActor));
    }

    /** Owner ruling 2026-09-24: import/account may still read an INDIVIDUAL deal's quotation(s)
     * via {@link DealQuotationService#get}/{@link DealQuotationService#listForTicket} — that is
     * unchanged, per-deal viewing on the deal page. But the GLOBAL LIST ({@link
     * DealQuotationService#search}) is now scoped to deals THEY created, and neither import nor
     * account created this deal, so it is empty for them there. */
    @Test
    void importAndAccount_readIndividualDeals_butGlobalListScopedToOwn() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        for (UserPrincipal reader : List.of(importActor, accountActor)) {
            assertThat(quotationService.get(created.id(), reader).id()).isEqualTo(created.id());
            assertThat(quotationService.listForTicket(ticketId, reader))
                .extracting(DealQuotationDto::id).contains(created.id());
            // NEW guard (2026-09-24): the global list no longer includes a deal this reader did
            // not create, even though get()/listForTicket() above still can.
            assertThat(quotationService.search(null, false, reader))
                .extracting(DealQuotationDto::id).doesNotContain(created.id());
            // Still read-only: neither role may create/edit/approve.
            assertForbidden(() -> quotationService.update(created.id(),
                upsertRequest(List.of(sampleItem("1.00", 1))), reader));
            assertForbidden(() -> quotationService.approve(created.id(), new ApproveRequest(null), reader));
        }
    }

    @Test
    void importAccountQcEmployee_cannotCreate() {
        for (UserPrincipal disallowed : List.of(importActor, accountActor, qcActor, employeeActor)) {
            assertForbidden(() -> quotationService.create(ticketId,
                upsertRequest(List.of(sampleItem("100.00", 10))), disallowed));
        }
    }

    @Test
    void importAccountQcEmployee_cannotApprove() {
        DealQuotationDto submitted = quotationService.submit(
            quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(),
            salesActor);
        for (UserPrincipal disallowed : List.of(importActor, accountActor, qcActor, employeeActor)) {
            assertForbidden(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), disallowed));
        }
    }

    @Test
    void sales_cannotApproveOrReject() {
        DealQuotationDto submitted = quotationService.submit(
            quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(),
            salesActor);
        assertForbidden(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), salesActor));
        assertForbidden(() -> quotationService.reject(submitted.id(), new RejectRequest("no"), salesActor));
    }

    @Test
    void salesManagerAndCeo_bothApproveSuccessfully() {
        DealQuotationDto submitted1 = quotationService.submit(
            quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(),
            salesActor);
        DealQuotationDto approvedByManager = quotationService.approve(submitted1.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedByManager.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        DealQuotationDto submitted2 = quotationService.submit(
            quotationService.create(ticketId, upsertRequest(List.of(sampleItem("100.00", 10))), salesActor).id(),
            salesActor);
        DealQuotationDto approvedByCeo = quotationService.approve(submitted2.id(), new ApproveRequest(null), ceoActor);
        assertThat(approvedByCeo.docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    @Test
    void salesManager_canCreateEditSubmitOnAnyDeal_notJustOwn() {
        // salesManagerActor is not the ticket owner (salesRepId is) -- "any deal" per the plan.
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesManagerActor);
        assertThat(created.docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — the canCreateQuotation grant (owner ruling, Ploy 2026-09-09)
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** {@code qc} WITHOUT the grant is covered by {@link #importAccountQcEmployee_cannotCreate}
     * above (kept as-is) — this is its WITH-grant counterpart: a granted employee may create,
     * update and submit on a deal they do NOT own, but still may not approve. */
    @Test
    void qcWithGrant_canCreateUpdateSubmitOnAnotherRepsDeal_butNotApprove() {
        grantQuotationCapability(qcUserId);

        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), qcActor);
        assertThat(created.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(created.createdById()).isEqualTo(qcUserId);
        // ticketId belongs to salesRepId, not qcUserId -- "any deal" per the grant.

        DealQuotationDto updated = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("200.00", 5))), qcActor);
        assertThat(updated.items()).hasSize(1);

        DealQuotationDto submitted = quotationService.submit(updated.id(), qcActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        // View/list/download also work for the granted employee, on this deal they do not own.
        assertThat(quotationService.get(submitted.id(), qcActor).id()).isEqualTo(submitted.id());
        assertThat(quotationService.listForTicket(ticketId, qcActor)).isNotEmpty();

        // Approve/reject stay 403 -- the grant never crosses into the approve gate.
        assertForbidden(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), qcActor));
        assertForbidden(() -> quotationService.reject(submitted.id(), new RejectRequest("no"), qcActor));
    }

    /** A grant on an INACTIVE employee must not work — {@code canCreateQuotation} filters
     * {@code is_active}, exactly like {@code isAdmin}. */
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

    @Test
    void signatureUpload_byAnotherPlainEmployee_isForbidden() throws Exception {
        MockMultipartFile file = pngFile();
        assertThatThrownBy(() -> signatureService.upload(salesManagerId, file, employeeActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void signatureUpload_selfCeoOrAdmin_allowed_andMagicBytesValidated() throws Exception {
        MockMultipartFile file = pngFile();
        // Self.
        signatureService.upload(salesManagerId, file, salesManagerActor);
        assertThat(signatureService.get(salesManagerId, salesManagerActor).mimeType()).isEqualTo("image/png");

        // CEO on someone else's signature.
        signatureService.upload(salesRepId, file, ceoActor);
        assertThat(signatureService.get(salesRepId, ceoActor).mimeType()).isEqualTo("image/png");

        // Declared PNG content-type but NOT real PNG magic bytes -- rejected at the container sniff.
        MockMultipartFile fake = new MockMultipartFile("file", "sig.png", "image/png", "not a real png".getBytes());
        assertThatThrownBy(() -> signatureService.upload(salesRepId, fake, salesRepActor()))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        // Real PNG MAGIC BYTES, but no genuine decodable image stream -- passes the container
        // sniff and is THEN rejected by ImageDecodability.decode (EmployeeSignatureService#upload).
        // This is what "andMagicBytesValidated" pins: magic bytes alone are not sufficient any
        // more, the file must actually decode.
        MockMultipartFile undecodable = undecodablePngBytesFile();
        assertThatThrownBy(() -> signatureService.upload(salesRepId, undecodable, salesRepActor()))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ไม่สามารถอ่านไฟล์รูปภาพนี้ได้");
    }

    /** Wrong-way-round: an employee id with no {@code hr.employee} row is 404 on all three verbs —
     * the real-backend write sweep found DELETE answering 204 against id 999999. The 403 gate
     * still comes first, so a caller without the capability learns nothing about which ids exist. */
    @Test
    void signature_unknownEmployee_isNotFoundOnEveryVerb_andStillForbiddenWithoutTheCapability() throws Exception {
        long unknown = 999_999L;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr.employee WHERE employee_id = :id",
            java.util.Map.of("id", unknown), Integer.class)).as("fixture: id must not exist").isZero();

        // Built outside the Runnable lambdas below -- pngFile() throws a checked Exception (it
        // genuinely encodes a PNG via ImageIO now) and Runnable#run() cannot declare one.
        MockMultipartFile file = pngFile();
        assertNotFound(() -> signatureService.delete(unknown, ceoActor), "ไม่พบพนักงาน");
        assertNotFound(() -> signatureService.get(unknown, ceoActor), "ไม่พบพนักงาน");
        assertNotFound(() -> signatureService.upload(unknown, file, ceoActor), "ไม่พบพนักงาน");

        assertForbidden(() -> signatureService.delete(unknown, employeeActor));
        assertForbidden(() -> signatureService.get(unknown, salesManagerActor));
    }

    /** DELETE stays idempotent for an employee that EXISTS: no signature stored is 204, not 404. */
    @Test
    void signatureDelete_existingEmployeeWithoutSignature_isIdempotent() throws Exception {
        signatureService.delete(salesRepId, ceoActor); // nothing stored yet — must not throw
        signatureService.upload(salesRepId, pngFile(), ceoActor);
        signatureService.delete(salesRepId, ceoActor);
        assertNotFound(() -> signatureService.get(salesRepId, ceoActor), "ยังไม่มีลายเซ็น");
        signatureService.delete(salesRepId, ceoActor); // and again
    }

    private void assertNotFound(Runnable action, String messageFragment) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
            .hasMessageContaining(messageFragment);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // V178 — remark 7's second กำหนดยืนยันราคา variant (an exact validity_until date, gated on
    // the document having special pricing — owner ruling 2026-09-14).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_dateMode_withoutSpecialPricing_isBadRequest() {
        // DIRECT_NET with net == list price -- no discount anywhere -- refuses DATE mode outright,
        // before even checking for a date.
        UpsertDealQuotationRequest request = withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "100.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, LocalDate.now(BANGKOK).plusDays(30));
        assertThatThrownBy(() -> quotationService.create(ticketId, request, salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ระบุวันที่ยืนราคาได้เฉพาะใบเสนอราคาที่มีราคาพิเศษหรือส่วนลด");
    }

    @Test
    void create_dateMode_withSpecialPricing_butNoDate_isBadRequest() {
        UpsertDealQuotationRequest request = withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))), // net < list -- special pricing
            WastageCalculator.VALIDITY_MODE_DATE, null);
        assertThatThrownBy(() -> quotationService.create(ticketId, request, salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("กรุณาระบุวันที่ยืนราคา");
    }

    @Test
    void create_dateMode_withSpecialPricing_dateBeforeQuotationDate_isBadRequest() {
        UpsertDealQuotationRequest request = withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, LocalDate.now(BANGKOK).minusDays(1));
        assertThatThrownBy(() -> quotationService.create(ticketId, request, salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("วันที่ยืนราคาต้องไม่ก่อนวันที่ใบเสนอราคา");
    }

    @Test
    void create_dateMode_withSpecialPricing_roundTripsThroughSaveAndRead() {
        LocalDate until = LocalDate.now(BANGKOK).plusDays(45);
        UpsertDealQuotationRequest request = withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, until);
        DealQuotationDto created = quotationService.create(ticketId, request, salesActor);
        assertThat(created.validityMode()).isEqualTo(WastageCalculator.VALIDITY_MODE_DATE);
        assertThat(created.validityUntil()).isEqualTo(until);

        // Re-read from Postgres, not the returned object.
        DealQuotationDto reread = quotationService.get(created.id(), salesActor);
        assertThat(reread.validityMode()).isEqualTo(WastageCalculator.VALIDITY_MODE_DATE);
        assertThat(reread.validityUntil()).isEqualTo(until);
    }

    @Test
    void update_dateMode_lostSpecialPricing_isBadRequest() {
        // Created WITH special pricing (net < list) in DATE mode -- fine. The SAME save that
        // clears the discount (net back to list) while still asking for DATE mode must 400: the
        // gate is decided from the rows being WRITTEN, not from what was there before.
        DealQuotationDto created = quotationService.create(ticketId, withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, LocalDate.now(BANGKOK).plusDays(30)), salesActor);

        UpsertDealQuotationRequest noLongerSpecial = withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "100.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, LocalDate.now(BANGKOK).plusDays(30));
        assertThatThrownBy(() -> quotationService.update(created.id(), noLongerSpecial, salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ระบุวันที่ยืนราคาได้เฉพาะใบเสนอราคาที่มีราคาพิเศษหรือส่วนลด");
    }

    @Test
    void submit_dateModeValidityAlreadyPast_isBadRequest() {
        // Match the service's business date: UTC CI can be a day behind Bangkok.
        // Start with a future expiry so crossing midnight during setup cannot reject the draft.
        DealQuotationDto created = quotationService.create(ticketId, withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, LocalDate.now(BANGKOK).plusDays(30)), salesActor);
        // Time moves on after the draft is saved -- simulate that directly (create/update already
        // refuse a date before the quotation's OWN date, so a past date cannot reach storage any
        // other way in one test run).
        jdbc.update("UPDATE sales.quotation SET validity_until = :d WHERE quotation_id = :id",
            java.util.Map.of("d", LocalDate.now(BANGKOK).minusDays(1), "id", created.id()));
        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("วันที่ยืนราคาผ่านไปแล้ว");
    }

    @Test
    void approve_dateModeWithSpecialPricing_setsValidityDateToValidityUntil_notApprovalPlusDays() {
        LocalDate until = LocalDate.now(BANGKOK).plusDays(60);
        DealQuotationDto created = quotationService.create(ticketId, withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, until), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved =
            quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.validityDate()).isEqualTo(until);
        // Wrong-way-round: NOT approvalDate + validityDays (30, the request's own default).
        assertThat(approved.validityDate())
            .isNotEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(30));
    }

    @Test
    void createRevision_copiesValidityModeAndUntil_verbatim() {
        LocalDate until = LocalDate.now(BANGKOK).plusDays(60);
        DealQuotationDto created = quotationService.create(ticketId, withValidity(
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("100.00", 10, "85.00"))),
            WastageCalculator.VALIDITY_MODE_DATE, until), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved =
            quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.validityMode()).isEqualTo(WastageCalculator.VALIDITY_MODE_DATE);
        assertThat(approved.validityUntil()).isEqualTo(until);

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.validityMode()).isEqualTo(WastageCalculator.VALIDITY_MODE_DATE);
        assertThat(revision.validityUntil()).isEqualTo(until);
    }

    @Test
    void daysMode_behaviourIsUnchanged() {
        // The default shape every OTHER test in this file already relies on: no validityMode sent
        // at all normalises to DAYS, validityUntil stays null, and approve keeps computing
        // approvalDate + validityDays -- see #acceptanceScenario_createUpdateSubmitApprove's own
        // assertion on this, pinned again here explicitly for V178.
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.validityMode()).isEqualTo(WastageCalculator.VALIDITY_MODE_DAYS);
        assertThat(created.validityUntil()).isNull();

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved =
            quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.validityMode()).isEqualTo(WastageCalculator.VALIDITY_MODE_DAYS);
        assertThat(approved.validityDate())
            .isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(30));
    }

    /** {@code withValidity} — the ONE wither this file's {@code UpsertDealQuotationRequest}
     * builders lack, added for V178 so every test above can start from an existing request
     * (built by {@link #upsertRequestWithMode}) and set just the two new fields. */
    private UpsertDealQuotationRequest withValidity(UpsertDealQuotationRequest base, String validityMode,
                                                     LocalDate validityUntil) {
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(),
            base.offerDate(), base.depositPercent(), base.remainderMode(), base.creditDays(),
            base.validityDays(), validityMode, validityUntil, base.customerNotes(), base.priceMode(),
            base.documentLanguage(), base.currency(), base.items());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ") + Item 4 (V181, "ไม่รับมัดจำ")
    // owner ruling 2026-09-16 — real-DB coverage through the real service.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Item 4 wither — sets depositPercent/remainderMode/creditDays/fullPaymentTerm together,
     * since {@code DealQuotationService} resolves the four as one unit (see
     * #resolveFullPaymentTerm's own Javadoc). */
    private UpsertDealQuotationRequest withDepositAndTerm(UpsertDealQuotationRequest base,
            Integer depositPercent, String remainderMode, Integer creditDays, String fullPaymentTerm) {
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(),
            base.offerDate(), depositPercent, remainderMode, creditDays,
            base.validityDays(), base.validityMode(), base.validityUntil(), base.customerNotes(),
            base.priceMode(), base.documentLanguage(), base.currency(), base.printedByDisplayId(),
            base.salesRepDisplayId(), base.projectName(), base.omitContactHonorific(), fullPaymentTerm,
            base.items());
    }

    /** Item 2 wither. */
    private UpsertDealQuotationRequest withOmitContactHonorific(UpsertDealQuotationRequest base, Boolean omit) {
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(),
            base.offerDate(), base.depositPercent(), base.remainderMode(), base.creditDays(),
            base.validityDays(), base.validityMode(), base.validityUntil(), base.customerNotes(),
            base.priceMode(), base.documentLanguage(), base.currency(), base.printedByDisplayId(),
            base.salesRepDisplayId(), base.projectName(), omit, base.fullPaymentTerm(), base.items());
    }

    @Test
    void create_omitContactHonorific_defaultsFalse_whenNotSent() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.omitContactHonorific()).isFalse();
    }

    @Test
    void create_omitContactHonorificTrue_persists() {
        DealQuotationDto created = quotationService.create(ticketId,
            withOmitContactHonorific(upsertRequest(List.of(sampleItem("100.00", 10))), true), salesActor);
        assertThat(created.omitContactHonorific()).isTrue();
    }

    /** No "missing keeps stored" case for this flag on UPDATE — the editor always sends its
     * CURRENT value, so an update that omits it (or sends {@code false}) clears a previously-set
     * {@code true} back to {@code false}, exactly like {@code printedByDisplayId}/{@code projectName}. */
    @Test
    void update_omitContactHonorific_clearsWhenOmittedFromThePayload() {
        DealQuotationDto created = quotationService.create(ticketId,
            withOmitContactHonorific(upsertRequest(List.of(sampleItem("100.00", 10))), true), salesActor);
        assertThat(created.omitContactHonorific()).isTrue();

        DealQuotationDto updated = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(updated.omitContactHonorific()).isFalse();
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

    /** A rep who unticks "ไม่รับมัดจำ" (re-enters an ordinary percentage) can never leave a stale
     * term attached, EVEN IF the request still sends one — wrong-way-round: the field the request
     * carries is not the field the stored row ends up with. */
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

    /** A {@code null} depositPercent is NOT "no deposit" (it defaults to 30% at render time) — same
     * refusal as an explicit non-zero percentage. */
    @Test
    void create_nullDepositPercent_alsoForcesFullPaymentTermNull() {
        DealQuotationDto created = quotationService.create(ticketId,
            withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))),
                null, "CREDIT", 30, WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY),
            salesActor);
        assertThat(created.depositPercent()).isNull();
        assertThat(created.fullPaymentTerm()).isNull();
    }

    @Test
    void update_switchingToZeroDeposit_clearsRemainderModeAndCreditDays_andStoresTheChosenTerm() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor); // 30%, CREDIT, 30 days
        DealQuotationDto updated = quotationService.update(created.id(),
            withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))),
                0, "CREDIT", 45, WastageCalculator.FULL_PAYMENT_TERM_BEFORE_DELIVERY),
            salesActor);
        assertThat(updated.depositPercent()).isEqualTo(0);
        assertThat(updated.remainderMode()).isNull();
        assertThat(updated.creditDays()).isNull();
        assertThat(updated.fullPaymentTerm()).isEqualTo(WastageCalculator.FULL_PAYMENT_TERM_BEFORE_DELIVERY);
    }

    /** Wrong-way-round: a zero-deposit DRAFT is SAVEABLE with no term chosen yet (create/update
     * above never refuse it) — {@link DealQuotationService#submit} is the one gate, the last check
     * before an approver ever sees the document, same "create/update permissive, submit strict"
     * split the DATE-mode validity check and the per-item lead-time check already use. */
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

    @Test
    void submit_zeroDepositWithTermChosen_succeeds() {
        DealQuotationDto created = quotationService.create(ticketId,
            withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))),
                0, null, null, WastageCalculator.FULL_PAYMENT_TERM_ON_OR_BEFORE_DELIVERY),
            salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(submitted.fullPaymentTerm())
            .isEqualTo(WastageCalculator.FULL_PAYMENT_TERM_ON_OR_BEFORE_DELIVERY);
    }

    /** A non-zero-deposit document is NEVER blocked by the new submit gate — the condition can
     * only ever fire on a genuinely zero-deposit document (regression guard for the guard itself). */
    @Test
    void submit_nonZeroDeposit_isUnaffectedByTheNewGate() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void revision_copiesOmitContactHonorificAndFullPaymentTermVerbatim() {
        DealQuotationDto created = quotationService.create(ticketId,
            withOmitContactHonorific(
                withDepositAndTerm(upsertRequest(List.of(sampleItem("100.00", 10))),
                    0, null, null, WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY),
                true),
            salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.omitContactHonorific()).isTrue();
        assertThat(revision.fullPaymentTerm()).isEqualTo(WastageCalculator.FULL_PAYMENT_TERM_ON_DELIVERY);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Contact-update DRAFT snapshot refresh (2026-09-16 fix, owner re-report "แก้หรือเพิ่ม Email
    // ผู้สั่งซื้อภายหลังไม่ได้") — CustomerService#updateContact /
    // DealQuotationRepository#refreshDraftContactSnapshot. Written wrong-way-round per CLAUDE.md
    // ("Permission changes must ship evidence" — same discipline applied to this scope guard):
    // negative cases (a row that must NOT move) come first. Every call goes through
    // #transactional(customerService) rather than the bare field, so #updateContact's own
    // @Transactional is exercised through a real AOP proxy instead of being inert — see that
    // helper's own Javadoc and the double-afterCommit-defer test above for why a bare `new`-wired
    // service would prove nothing about the annotation.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void updateContact_approvedQuotation_snapshotNeverChanges() {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        assertThat(approved.contactEmail()).isEqualTo(contact.email());

        transactional(customerService).updateContact(customer.id(), contact.id(),
            null, null, null, "changed-after-approval@customer.test", "099-000-0000");

        DealQuotationDto reloaded = quotationRepository.findById(approved.id()).orElseThrow();
        assertThat(reloaded.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(reloaded.contactEmail()).isEqualTo(contact.email());
        assertThat(reloaded.contactPhone()).isEqualTo(contact.phone());
    }

    @Test
    void updateContact_pendingApprovalQuotation_snapshotNeverChanges() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.contactEmail()).isEqualTo(contact.email());

        transactional(customerService).updateContact(customer.id(), contact.id(),
            null, null, null, "changed-while-pending@customer.test", null);

        DealQuotationDto reloaded = quotationRepository.findById(submitted.id()).orElseThrow();
        assertThat(reloaded.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(reloaded.contactEmail()).isEqualTo(contact.email());
    }

    @Test
    void updateContact_draftBelongingToADifferentContact_notChanged() {
        ContactDto otherContact = contacts.create(customer.id(), "สมชาย", "รักดี", "ผู้จัดการ",
            "somchai@customer.test", "082-222-3333");
        ProjectDto otherProject = projects.create(customer.id(), "โครงการผู้สั่งซื้ออื่น");
        long otherTicket = createTicket("ดีล DQ ผู้สั่งซื้ออื่น", otherProject.id(), otherContact.id(), salesActor);
        DealQuotationDto otherDraft = quotationService.create(otherTicket,
            upsertRequest(otherContact.id(), List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(otherDraft.contactEmail()).isEqualTo(otherContact.email());

        // Updating the ORIGINAL contact (`contact`, not `otherContact`) must never touch a draft
        // snapshotted against a DIFFERENT contact id.
        transactional(customerService).updateContact(customer.id(), contact.id(),
            null, null, null, "changed-for-original-contact@customer.test", null);

        DealQuotationDto reloaded = quotationRepository.findById(otherDraft.id()).orElseThrow();
        assertThat(reloaded.contactEmail()).isEqualTo(otherContact.email());
        assertThat(reloaded.contactId()).isEqualTo(otherContact.id());
    }

    @Test
    void updateContact_draftQuotation_phoneAndEmailRefreshedImmediately_nameLeftAlone() {
        DealQuotationDto draft = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(draft.contactEmail()).isEqualTo(contact.email());
        String originalName = draft.contactName();

        // No re-save of the DRAFT itself -- the fix is that the CONTACT update alone reaches it.
        transactional(customerService).updateContact(customer.id(), contact.id(),
            null, null, null, "new-email@customer.test", "088-777-6666");

        DealQuotationDto reloaded = quotationRepository.findById(draft.id()).orElseThrow();
        assertThat(reloaded.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(reloaded.contactEmail()).isEqualTo("new-email@customer.test");
        assertThat(reloaded.contactPhone()).isEqualTo("088-777-6666");
        // A phone/email-only edit never re-snapshots the printed NAME -- see
        // DealQuotationRepository#refreshDraftContactSnapshot's own Javadoc on `refreshName`.
        assertThat(reloaded.contactName()).isEqualTo(originalName);
    }

    @Test
    void updateContact_draftQuotation_nameRefreshedOnlyWhenNameFieldsAreSent() {
        DealQuotationDto draft = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        transactional(customerService).updateContact(customer.id(), contact.id(),
            "สมหญิง2", "ใจดี2", null, null, null);

        DealQuotationDto reloaded = quotationRepository.findById(draft.id()).orElseThrow();
        assertThat(reloaded.contactName()).isEqualTo("สมหญิง2 ใจดี2");
    }

    /**
     * D5 (Opus review 2026-09-16): {@code refreshDraftContactSnapshot}'s own {@code UPDATE} carries
     * no actor/ownership predicate at all -- it moves EVERY DRAFT pointing at this contact id,
     * regardless of whose ticket it belongs to, even though a DRAFT is otherwise visible only to
     * its owning rep plus CEO/sales_manager (DealEntryAccess is role-only). Pinned here as the
     * INTENDED behaviour, not an authz gap: the snapshot is derived data rep B's own next save on
     * their DRAFT would reproduce verbatim anyway (same contact row, same {@code resolveContact}
     * read), so this carries no information about rep B's ticket that rep A could not already see
     * by looking at the (shared) contact record itself. See this test's own PR-body paragraph
     * (in the branch's PR description) for the full reasoning a reviewer needs to tell this apart
     * from a genuine cross-rep leak.
     */
    @Test
    void updateContact_anotherRepsDraftOnTheSameContact_isAlsoRefreshed() {
        DealQuotationDto repBsDraft = quotationService.create(otherTicketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), otherSalesActor);
        assertThat(repBsDraft.contactEmail()).isEqualTo(contact.email());
        assertThat(repBsDraft.salesRepId()).isEqualTo(otherSalesId);

        // Rep A (or anyone reaching CustomerService#updateContact -- it takes no actor) corrects
        // the SHARED contact. Rep B never touched their own draft.
        transactional(customerService).updateContact(customer.id(), contact.id(),
            null, null, null, "shared-contact-new-email@customer.test", "085-000-1111");

        DealQuotationDto reloaded = quotationRepository.findById(repBsDraft.id()).orElseThrow();
        assertThat(reloaded.contactEmail()).isEqualTo("shared-contact-new-email@customer.test");
        assertThat(reloaded.contactPhone()).isEqualTo("085-000-1111");
    }

    /**
     * D6 (Opus review 2026-09-16): {@code ContactRepository#update}'s own
     * {@code COALESCE(:email, email)} treats an explicit {@code ""} (as opposed to a literal
     * {@code null} parameter, which means "leave unchanged") as "clear this field", so
     * {@code customers.contact.email} genuinely holds {@code ''} after this call --
     * {@code refreshDraftContactSnapshot} must still normalise that to {@code NULL} on
     * {@code sales.quotation}, matching every other writer of this column
     * ({@code DealQuotationService#resolveContact}'s own {@code blankToNull}).
     */
    @Test
    void updateContact_draftQuotation_clearingEmailStoresNullNotEmptyString() {
        DealQuotationDto draft = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(draft.contactEmail()).isEqualTo(contact.email());

        transactional(customerService).updateContact(customer.id(), contact.id(),
            null, null, null, "", null);

        DealQuotationDto reloaded = quotationRepository.findById(draft.id()).orElseThrow();
        assertThat(reloaded.contactEmail()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private UserPrincipal salesRepActor() {
        return salesActor;
    }

    private DealQuotationDto createSubmittedApproved(long ticketIdParam, UserPrincipal creator, UserPrincipal approver) {
        DealQuotationDto created = quotationService.create(ticketIdParam,
            upsertRequest(List.of(sampleItem("100.00", 10))), creator);
        DealQuotationDto submitted = quotationService.submit(created.id(), creator);
        return quotationService.approve(submitted.id(), new ApproveRequest(null), approver);
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    private UpsertDealQuotationRequest upsertRequest(List<ItemInput> items) {
        return upsertRequest(null, items);
    }

    /** {@code contactId} null = "default to the deal's contact" (the normal wire shape). */
    private UpsertDealQuotationRequest upsertRequest(Long contactId, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(contactId, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            "หมายเหตุทดสอบ", items);
    }

    private ItemInput sampleItem(String unitPrice, int pieces) {
        return sampleItem(null, unitPrice, pieces);
    }

    /** H1: a location heading is what actually exercises {@code QuotationRenderer}'s
     * underline-style cache — see {@link #renderPdfAndXlsx_nonEmptyBytes}'s own comment. */
    private ItemInput sampleItem(String locationLabel, String unitPrice, int pieces) {
        // Item completeness rule (inline-deal-spec.md, owner ruling 2026-09-10): thicknessMm and
        // piecesPerBox are now REQUIRED on create/update -- this fixture predates that rule and
        // used to leave both null (sqmPerPiece was already covered, derived from "60x60").
        // ⚠️ 2026-09-12: that derivation is GONE (owner ruling "2) ไม่มีค่อยคำนวนเอง" --
        // DealQuotationService#resolveSqmPerPiece no longer parses sizeText at all, see its own
        // Javadoc), so this fixture now supplies sqmPerPiece EXPLICITLY (60x60cm = 0.36 sqm/piece)
        // rather than relying on the deleted size-string heuristic.
        // piecesPerBox=1 (not e.g. 4) deliberately -- box rounding is a no-op at box size 1
        // (ceil(n/1)*1 == n for every n), so every dollar-amount assertion across this large
        // file that was written against the OLD (no-box-rounding) piecesFinal keeps passing
        // unchanged; a caller that specifically wants to exercise box rounding builds its own
        // ItemInput (see e.g. #incompleteItem_missingPiecesPerBox_isBadRequestNamingTheRowAndField's
        // ItemInputBuilder).
        return new ItemInput(locationLabel, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, pieces, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal(unitPrice), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
    }

    /** A REAL, decodable PNG — {@code EmployeeSignatureService#upload} now runs {@code
     * ImageDecodability.decode} and re-encodes to canonical 8-bit ARGB, so magic bytes alone
     * (this helper's old behaviour) are no longer enough; see {@link #undecodablePngBytesFile()}
     * for the deliberately-broken counterpart that still proves that rejection. Alpha is
     * meaningful here (not incidental) -- a signature sits over a rule, and alpha preservation
     * through the re-encode is deliberate. Same construction as {@code
     * QuotationRendererTest#realPng}. */
    private MockMultipartFile pngFile() throws Exception {
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(0, 0, 0, 128)); // half-transparent -- exercises alpha, not just RGB.
        g.fillRect(0, 0, 4, 4);
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "sig.png", "image/png", out.toByteArray());
    }

    /** Real PNG MAGIC BYTES (89 50 4E 47 0D 0A 1A 0A) but no genuine IHDR/IDAT/IEND stream --
     * passes the container sniff, then fails {@code ImageDecodability.decode}. This is the
     * regression {@code signatureUpload_selfCeoOrAdmin_allowed_andMagicBytesValidated}'s
     * {@code andMagicBytesValidated} name refers to. */
    private MockMultipartFile undecodablePngBytesFile() {
        byte[] bytes = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00
        };
        return new MockMultipartFile("file", "sig.png", "image/png", bytes);
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /**
     * Unlike {@link #createEmployee}, which leaves {@code positionTh} null, this sets a real
     * position containing "ผู้จัดการ" -- {@code NotificationRepository#notifyByRole}'s own
     * {@code sales_manager} predicate keys on {@code hr.position.name_th}, NOT the employee's
     * display name, so an employee with no position can never match it (see that predicate's own
     * comment for why it is deliberately narrower than a division match).
     */
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

    /** Records every {@code sendWithAttachment} call as {@code [to, subject]} — the "capturing
     * mailer" the report's evidence for the approval email cites. */
    private static final class CapturingMailer implements Mailer {
        final List<String[]> attachmentsSent = new ArrayList<>();
        // The branded HTML notification path (NotificationEmailService#send -> Mailer#sendHtml) --
        // distinct from attachmentsSent above, which is the dead PDF-attachment path's own mailer
        // call. {to, subject} per send, in call order.
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
    // Quotation v3 (owner feedback pass 3, 2026-09-11) — price modes, PLAIN and ADJUSTMENT rows.
    //
    // Every negative case below is written WRONG-WAY-ROUND per CLAUDE.md: it asserts what the
    // service must REFUSE, not what it accepts. These run through the REAL DealQuotationService
    // against a REAL Postgres (AbstractPostgresIntegrationTest), so they prove the rule survives
    // into the stored row, not merely that a branch was taken.
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** S1, end to end: the owner's QN6900704-2 item 1 — ราคาพิเศษ 1,350 บาท/ตร.ม. incl. VAT on a
     * 60x60 tile (0.36 ตร.ม./แผ่น) prints คงเหลือ 453.84. Asserted off the row READ BACK from
     * Postgres, so the column round-trip is covered too, not just the in-memory computation. */
    @Test
    void specialSqmMode_reproducesTheOwnersPrintedNetPrice_throughTheDatabase() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"))),
            salesActor);

        assertThat(created.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        DealQuotationItemDto item = created.items().get(0);
        assertThat(item.netUnitPrice()).isEqualByComparingTo("453.84");
        assertThat(item.lineAmount()).isEqualByComparingTo("4538.40"); // 453.84 x 10 แผ่น
        // ราคา stays the CATALOG LIST price the rep typed — it is not overwritten by the special.
        assertThat(item.unitPrice()).isEqualByComparingTo("2000.00");
        assertThat(item.specialPriceSqm()).isEqualByComparingTo("1350");
        assertThat(item.specialPriceLine())
            .isEqualTo("(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)");
        // The percentage discount is cleared, not left stale — the ส่วนลด column reads "พิเศษ".
        assertThat(item.discountPct()).isNull();
        assertThat(created.subtotalAmount()).isEqualByComparingTo("4538.40");
    }

    /** S1: DIRECT_NET keeps the list price in ราคา and stores the rep's typed net in คงเหลือ. */
    @Test
    void directNetMode_keepsTheListPrice_andStoresTheTypedNet() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("2000.00", 10, "777.50"))),
            salesActor);

        DealQuotationItemDto item = created.items().get(0);
        assertThat(item.unitPrice()).isEqualByComparingTo("2000.00");
        assertThat(item.netUnitPrice()).isEqualByComparingTo("777.50");
        assertThat(item.lineAmount()).isEqualByComparingTo("7775.00");
        assertThat(created.subtotalAmount()).isEqualByComparingTo("7775.00");
    }

    /** NET mode is untouched by v3 — the default when no mode is sent, and the arithmetic is the
     * pre-v3 one. Guards against the new switch silently changing today's documents. */
    @Test
    void netMode_isTheDefault_andItsArithmeticIsUnchanged() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_NET);
        assertThat(created.items().get(0).lineAmount()).isEqualByComparingTo("1000.00");
        assertThat(created.items().get(0).unit()).isEqualTo("แผ่น");
        assertThat(created.items().get(0).quantity()).isEqualByComparingTo("10");
        assertThat(created.items().get(0).lineType()).isEqualTo(WastageCalculator.LINE_TYPE_TILE);
    }

    /** S2: a PLAIN row carries description/quantity/unit/price and NONE of the tile machinery. */
    @Test
    void plainRow_hasNoTileMachinery_andAmountIsQuantityTimesNetPrice() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),
                plainItem("Transportation Charges from China to Male Port, Maldives", "1", "JOB", "50000.00"),
                plainItem("Mapei Adhesive (20kg/Bag)", "85", "Bags", "450.00"))),
            salesActor);

        DealQuotationItemDto freight = created.items().get(1);
        assertThat(freight.lineType()).isEqualTo(WastageCalculator.LINE_TYPE_PLAIN);
        assertThat(freight.descriptionLine())
            .isEqualTo("Transportation Charges from China to Male Port, Maldives");
        assertThat(freight.quantity()).isEqualByComparingTo("1");
        assertThat(freight.unit()).isEqualTo("JOB");
        assertThat(freight.lineAmount()).isEqualByComparingTo("50000.00");
        // None of the tile machinery: no size line, no wastage/calculation line, no catalog link,
        // no ตร.ม./แผ่น, no แผ่น/กล่อง.
        assertThat(freight.sizeLine()).isNull();
        assertThat(freight.calculationLine()).isNull();
        assertThat(freight.catalogPriceId()).isNull();
        assertThat(freight.sqmPerPiece()).isNull();
        assertThat(freight.piecesPerSqm()).isNull();
        assertThat(freight.piecesPerBox()).isNull();
        assertThat(freight.boxes()).isNull();

        DealQuotationItemDto mapei = created.items().get(2);
        assertThat(mapei.quantity()).isEqualByComparingTo("85");
        assertThat(mapei.lineAmount()).isEqualByComparingTo("38250.00"); // 85 x 450
        assertThat(created.subtotalAmount()).isEqualByComparingTo("89250.00"); // 1000 + 50000 + 38250
    }

    /**
     * D1 (owner decision, 2026-09-16, review of V182): a PLAIN row (สินค้า/บริการอื่น — sanitaryware
     * sold on ชุด) may now carry an OPTIONAL import lead time, exactly like a TILE row's. This is
     * the production PATH the review flagged as missing evidence for — {@code ItemInput} built the
     * way the wired editor now sends it (never a hand-constructed {@code DealQuotationItemDto}),
     * through the REAL {@code DealQuotationService#create} → {@code #buildPlainItem}, proving the
     * fields the UI now sends actually reach the saved item and, from there, the printed document —
     * not merely that a fixture built directly at the render layer can express the state.
     */
    @Test
    void plainRow_optionalLeadTime_reachesTheSavedItem_andPrintsInTheNonTileRemarks() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                plainItemWithLeadTime("สุขภัณฑ์", "1", "ชุด", "5000.00", 75, 90))),
            salesActor);

        DealQuotationItemDto item = created.items().get(0);
        assertThat(item.lineType()).isEqualTo(WastageCalculator.LINE_TYPE_PLAIN);
        assertThat(item.leadTimeMinDays()).isEqualTo(75);
        assertThat(item.leadTimeMaxDays()).isEqualTo(90);

        // The saved document has no TILE line at all, so it takes the non-tile remark set — and
        // now that this PLAIN row carries a lead time, remark 3 prints it rather than dropping.
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(created, null, null);
        assertThat(model.remarkLines().get(2)).isEqualTo(
            "3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า รายการที่ 1 ประมาณ 75-90 วัน "
                + "หลังจากได้รับมัดจำ 30% เรียบร้อยแล้ว");
    }

    /** Wording-scan fix 2 (2026-09-17): at 100% deposit the non-tile remark 3's own
     * "หลังจากได้รับมัดจำ N% เรียบร้อยแล้ว" clause is replaced with "หลังจากได้รับชำระเงินเรียบร้อยแล้ว"
     * -- naming a "deposit" percentage at 100% is naming money that was never separate from the
     * full price. Exercised through the REAL create() -> render path, not a hand-built DTO. */
    @Test
    void plainRow_hundredPercentDeposit_nonTileRemark_statesFullPaymentReceived_notADeposit() {
        DealQuotationDto created = quotationService.create(ticketId,
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 100, "CREDIT", 45, 30,
                "หมายเหตุทดสอบ", List.of(plainItemWithLeadTime("สุขภัณฑ์", "1", "ชุด", "5000.00", 75, 90))),
            salesActor);

        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(created, null, null);
        assertThat(model.remarkLines().get(1)).isEqualTo("2.บริษัทขอรับเงินค่าสินค้า 100% เมื่อสั่งซื้อสินค้า");
        assertThat(model.remarkLines().get(2)).isEqualTo(
            "3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า รายการที่ 1 ประมาณ 75-90 วัน "
                + "หลังจากได้รับชำระเงินเรียบร้อยแล้ว");
        // Wrong-way-round: no "มัดจำ 100%"/credit-days wording anywhere in the remark block.
        assertThat(model.remarkLines()).noneMatch(l -> l.contains("มัดจำ 100%") || l.contains("เครดิต 45"));
    }

    /** D1 twin: a PLAIN row saved with NO lead time (today's ordinary case) still saves exactly as
     * before — the field is optional, never required, for a non-TILE row. */
    @Test
    void plainRow_withNoLeadTime_stillSavesAndPrintsUnchanged() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(plainItem("ค่าขนส่ง", "1", "JOB", "500.00"))), salesActor);

        DealQuotationItemDto item = created.items().get(0);
        assertThat(item.leadTimeMinDays()).isNull();
        assertThat(item.leadTimeMaxDays()).isNull();

        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(created, null, null);
        assertThat(model.remarkLines()).noneMatch(l -> l.contains("ระยะเวลานำเข้า"));
    }

    /** S2: a fractional PLAIN quantity must survive — the reason `quantity` is a BigDecimal and
     * not an int. An int would have thrown ArithmeticException on the read path instead. */
    @Test
    void plainRow_acceptsAFractionalQuantity() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10),
                plainItem("ค่าบริการตัดกระเบื้องตามแบบ", "2.5", "ชม.", "400.00"))),
            salesActor);
        DealQuotationItemDto cut = created.items().get(1);
        assertThat(cut.quantity()).isEqualByComparingTo("2.5");
        assertThat(cut.lineAmount()).isEqualByComparingTo("1000.00");
    }

    /**
     * S3, pinned against the owner's QN6900704-2: 3% of the four preceding line amounts
     * (149,767.20 + 96,012.60 + 528,269.76 + 499,224.00 = 1,273,273.56) is 38,198.21, and the row
     * prints จำนวน −1, no unit, a POSITIVE ราคา/คงเหลือ and a NEGATIVE เป็นเงิน.
     */
    @Test
    void adjustmentRow_isDerivedFromThePrecedingRows_andPrintsTheOwnersFigure() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                plainItem("รายการที่ 1", "1", "ชุด", "149767.20"),
                plainItem("รายการที่ 2", "1", "ชุด", "96012.60"),
                plainItem("รายการที่ 3", "1", "ชุด", "528269.76"),
                plainItem("รายการที่ 4", "1", "ชุด", "499224.00"),
                adjustmentPctItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);

        DealQuotationItemDto adj = created.items().get(4);
        assertThat(adj.lineType()).isEqualTo(WastageCalculator.LINE_TYPE_ADJUSTMENT);
        assertThat(adj.descriptionLine()).isEqualTo("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");
        assertThat(adj.quantity()).isEqualByComparingTo("-1");
        assertThat(adj.unit()).isNull();                          // no หน่วย at all
        assertThat(adj.unitPrice()).isEqualByComparingTo("38198.21");    // ราคา — POSITIVE
        assertThat(adj.netUnitPrice()).isEqualByComparingTo("38198.21"); // คงเหลือ — POSITIVE
        assertThat(adj.lineAmount()).isEqualByComparingTo("-38198.21");  // เป็นเงิน — NEGATIVE
        assertThat(adj.adjustmentPct()).isEqualByComparingTo("3");
        assertThat(adj.adjustmentDeadline()).isEqualTo(LocalDate.of(2026, 7, 31));

        // The document total nets the discount out — and so, therefore, does the VAT and the
        // grand total. (Both are computed FROM the subtotal, not summed from the per-line vat
        // columns, so a negative line reduces the tax the customer is charged rather than being
        // taxed separately. Pinned because getting this wrong would over-charge VAT on every
        // discounted document while every other assertion here still passed.)
        assertThat(created.subtotalAmount()).isEqualByComparingTo("1235075.35"); // 1273273.56 - 38198.21
        assertThat(created.vatAmount()).isEqualByComparingTo("86455.27");        // 7% of the NET subtotal
        assertThat(created.grandTotal()).isEqualByComparingTo("1321530.62");
    }

    /** S3: a flat baht adjustment, the alternative to a percentage. */
    @Test
    void adjustmentRow_acceptsAFlatBahtAmount() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10), adjustmentFlatItem("250.00", "ส่วนลดตามตกลง"))),
            salesActor);
        DealQuotationItemDto adj = created.items().get(1);
        assertThat(adj.descriptionLine()).isEqualTo("ส่วนลดตามตกลง");
        assertThat(adj.unitPrice()).isEqualByComparingTo("250.00");
        assertThat(adj.lineAmount()).isEqualByComparingTo("-250.00");
        assertThat(adj.adjustmentPct()).isNull();
        assertThat(created.subtotalAmount()).isEqualByComparingTo("750.00");
    }

    /**
     * ⚠️ ORDERING IS LOAD-BEARING. An adjustment applies to the rows ABOVE it, so it must sort
     * LAST whatever order the client sent — otherwise its base is whatever happened to precede it
     * in the payload, and the same document totals differently depending on row order in the UI.
     */
    @Test
    void adjustmentRow_sortsLast_whateverOrderTheClientSends() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                adjustmentPctItem("10", null),                 // sent FIRST
                sampleItem("100.00", 10),                      // 1,000
                plainItem("ค่าขนส่ง", "1", "JOB", "500.00"))), // 500
            salesActor);

        assertThat(created.items()).extracting(DealQuotationItemDto::lineType)
            .containsExactly(WastageCalculator.LINE_TYPE_TILE, WastageCalculator.LINE_TYPE_PLAIN,
                WastageCalculator.LINE_TYPE_ADJUSTMENT);
        assertThat(created.items()).extracting(DealQuotationItemDto::seq).containsExactly(1, 2, 3);
        // 10% of (1000 + 500), NOT 10% of nothing — which is what a first-position adjustment
        // would have computed had the reorder not happened.
        assertThat(created.items().get(2).lineAmount()).isEqualByComparingTo("-150.00");
        assertThat(created.subtotalAmount()).isEqualByComparingTo("1350.00");
    }

    /**
     * Two adjustments do NOT compound: each is a percentage of the same NON-adjustment subtotal.
     * ⚠️ This is the one behaviour in this pass the owner has not ruled on — flagged in the PR
     * body. Pinned here so that if she chooses compounding, this test names the decision rather
     * than the change slipping in unnoticed.
     */
    @Test
    void twoAdjustments_doNotCompound() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),                    // 1,000
                adjustmentPctItem("10", null),
                adjustmentPctItem("5", null))),
            salesActor);

        assertThat(created.items().get(1).lineAmount()).isEqualByComparingTo("-100.00"); // 10% of 1000
        // 5% of 1,000 = 50.00. Compounding would make it 5% of 900 = 45.00.
        assertThat(created.items().get(2).lineAmount()).isEqualByComparingTo("-50.00");
        assertThat(created.subtotalAmount()).isEqualByComparingTo("850.00");
    }

    /** A document of nothing but adjustments has no base to discount — refuse it rather than
     * silently writing a ฿0 discount line. */
    @Test
    void adjustmentOnly_document_isRejected() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(adjustmentPctItem("3", null))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    // ── Type-aware price validation, wrong-way-round ────────────────────────────────────────

    /**
     * The regression guard for the {@code @DecimalMin("0.01")} that was removed from
     * {@code ItemInput.unitPrice}: a TILE row must STILL refuse a non-positive or missing price.
     *
     * <p>⚠️ <b>Mutation-checked, and the result is worth recording:</b> deleting the TILE/PLAIN
     * price guard from {@code DealQuotationService#requirePriceValidForType} does NOT red this
     * test. On the create path a bad price is caught a second time by
     * {@code #requireItemComplete}'s own "ราคาต่อหน่วย" check, which pre-dates v3. That
     * belt-and-braces is welcome, but it means THIS test does not isolate the new rule — the two
     * that do are {@link #tileRow_withNonPositivePrice_isRejectedOnTheCalculateLinePreviewToo}
     * (the preview path skips completeness entirely) and
     * {@link #plainRow_withNonPositiveOrMissingPrice_isRejected} (a PLAIN row has no completeness
     * check to fall back on). Those two are the real guard; keep this one as the statement of
     * intent, but do not read a green here as evidence the guard exists.
     */
    @Test
    void tileRow_withNonPositiveOrMissingPrice_isStillRejected() {
        for (BigDecimal bad : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-5"), null}) {
            assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(List.of(itemMissing(b -> b.withUnitPrice(bad)))), salesActor))
                .as("TILE unitPrice = %s", bad)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
    }

    /** ...and on the LENIENT preview path too. calculate-line used to 400 on a zero price via the
     * bean annotation; moving the rule must not have quietly loosened that. */
    @Test
    void tileRow_withNonPositivePrice_isRejectedOnTheCalculateLinePreviewToo() {
        assertThatThrownBy(() -> quotationService.calculateLine(
            itemMissing(b -> b.withUnitPrice(BigDecimal.ZERO)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> quotationService.calculateLine(
            itemMissing(b -> b.withUnitPrice(null)), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void plainRow_withNonPositiveOrMissingPrice_isRejected() {
        for (String bad : new String[]{"0", "-5"}) {
            assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(List.of(sampleItem("100.00", 10),
                    plainItem("ค่าขนส่ง", "1", "JOB", bad))), salesActor))
                .as("PLAIN unitPrice = %s", bad)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10),
                plainItem("ค่าขนส่ง", "1", "JOB", null))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    /**
     * An ADJUSTMENT's amount is DERIVED, and a client-supplied {@code unitPrice} on such a row is
     * IGNORED — not refused (review fix F2). It used to 400, which made the round-trip below
     * impossible; the caller still cannot dictate the discount, because
     * {@code #buildAdjustmentItem} never reads {@code unitPrice} at all.
     *
     * <p>Written wrong-way-round in the sense that matters here: the assertion is that the absurd
     * 99,999.00 the client sent has NO effect on the stored figure, which stays the derived 3% of
     * the rows above. A fix that "accepted" the price by using it would fail this.
     */
    @Test
    void adjustmentRow_withARepSuppliedPrice_ignoresIt_andStillDerivesTheAmount() {
        ItemInput handPriced = new ItemInput(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            new BigDecimal("99999.00"), null, null, null, null, null,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, null, null, null, null, null,
            new BigDecimal("3"), null, null);

        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10), handPriced)), salesActor);

        DealQuotationItemDto adj = created.items().get(1);
        assertThat(adj.unitPrice()).isEqualByComparingTo("30.00");    // 3% of 1,000 — NOT 99,999.00
        assertThat(adj.lineAmount()).isEqualByComparingTo("-30.00");
        assertThat(created.subtotalAmount()).isEqualByComparingTo("970.00");
    }

    /** Exactly one of percent / flat amount — neither and both are both errors. */
    @Test
    void adjustmentRow_needsExactlyOneOfPercentAndFlatAmount() {
        ItemInput neither = adjustmentItem(null, null, null, null);
        ItemInput both = adjustmentItem(new BigDecimal("3"), new BigDecimal("500"), null, null);
        for (ItemInput bad : List.of(neither, both)) {
            assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(List.of(sampleItem("100.00", 10), bad)), salesActor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void adjustmentRow_withANonPositivePercentOrAmount_isRejected() {
        for (ItemInput bad : List.of(
                adjustmentItem(BigDecimal.ZERO, null, null, null),
                adjustmentItem(null, BigDecimal.ZERO, null, null),
                adjustmentItem(null, new BigDecimal("-5"), null, null))) {
            assertThatThrownBy(() -> quotationService.create(ticketId,
                upsertRequest(List.of(sampleItem("100.00", 10), bad)), salesActor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
    }

    /** A price mode whose own price is missing must 400, NEVER fall back to the list price —
     * a silent fallback would quote the customer the undiscounted figure. */
    @Test
    void specialSqmMode_withoutASpecialPrice_isRejected_notSilentlyFallenBackToTheListPrice() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(sampleItem("2000.00", 10))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ราคาพิเศษ");

        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "0"))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void directNetMode_withoutATypedNet_isRejected() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(sampleItem("2000.00", 10))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ราคาสุทธิ");
    }

    // ── submit / revision over the new row types ────────────────────────────────────────────

    /** submit re-checks the STORED rows. Its tile completeness rule must NOT be applied to a
     * PLAIN or ADJUSTMENT row — without the type switch, every document containing a freight line
     * would be un-submittable because it has no รุ่น/สี/ผิว/ขนาด. */
    @Test
    void submit_acceptsADocumentContainingPlainAndAdjustmentRows() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),
                plainItem("ค่าขนส่ง", "1", "JOB", "500.00"),
                adjustmentPctItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);

        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    /** A PLAIN row missing its description is still refused at submit — the type switch narrows
     * the rule, it does not remove it. Written by breaking the stored row directly, the way a
     * legacy/pre-rule row would exist. */
    @Test
    void submit_stillRejectsAPlainRowWithNoDescription() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10),
                plainItem("ค่าขนส่ง", "1", "JOB", "500.00"))), salesActor);
        jdbc.update("""
            UPDATE sales.quotation_item SET description = NULL
             WHERE quotation_id = :id AND line_type = 'PLAIN'
            """, java.util.Map.of("id", created.id()));

        assertThatThrownBy(() -> quotationService.submit(created.id(), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รายละเอียด");
    }

    /** A revision copies the parent's rows VERBATIM — adjustments included, at their approved
     * figure, rather than re-deriving them. */
    @Test
    void createRevision_carriesPriceModeAndEveryRowTypeVerbatim() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"),
                    plainItem("ค่าขนส่ง", "1", "JOB", "500.00"),
                    adjustmentPctItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);
        quotationService.submit(created.id(), salesActor);
        quotationService.approve(created.id(), new ApproveRequest("ok"), salesManagerActor);

        DealQuotationDto revision = quotationService.createRevision(created.id(), salesActor);
        assertThat(revision.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        assertThat(revision.items()).extracting(DealQuotationItemDto::lineType)
            .containsExactly(WastageCalculator.LINE_TYPE_TILE, WastageCalculator.LINE_TYPE_PLAIN,
                WastageCalculator.LINE_TYPE_ADJUSTMENT);
        assertThat(revision.items().get(0).netUnitPrice()).isEqualByComparingTo("453.84");
        assertThat(revision.items().get(0).specialPriceSqm()).isEqualByComparingTo("1350");
        assertThat(revision.items().get(2).lineAmount())
            .isEqualByComparingTo(created.items().get(2).lineAmount());
        assertThat(revision.subtotalAmount()).isEqualByComparingTo(created.subtotalAmount());
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // Review fixes F1 / F2 / F3 — the UPDATE path.
    //
    // ⚠️ The v3 update() path had NO coverage at all until these landed: replacing
    // `resolvePriceMode(request.priceMode())` at DealQuotationService#update with a hardcoded
    // PRICE_MODE_NET left the whole suite green while silently repricing every SPECIAL_SQM and
    // DIRECT_NET document a rep edits. Every test in this block runs through the REAL service
    // against a REAL Postgres, and the first three are the ones that red under that mutation.
    //
    // MUTATION-CHECK RECORD (actually run against a real Postgres, not simulated; each reverted
    // with an editor, never `git checkout --`, because these worktrees are shared). Baseline for
    // all four: 216 green across th.co.glr.hr.dealquotation.*Test + QuotationRendererTest.
    //
    //   MC1  update()'s price mode hardcoded to PRICE_MODE_NET  -> exactly 4 red, ALL NEW:
    //        update_withNoPriceMode_keepsTheStoredSpecialSqmMode_andRepricesNothing,
    //        ...keepsTheStoredDirectNetMode..., update_withAnExplicitPriceMode_switchesTheDocumentBothWays,
    //        getThenPutVerbatim_roundTripsASpecialSqmDocument. Nothing else moved.
    //   MC2  the ADJUSTMENT "cannot supply a price" throw put back -> exactly 3 red:
    //        adjustmentRow_withARepSuppliedPrice_ignoresIt_andStillDerivesTheAmount and both
    //        getThenPutVerbatim_* round-trips.
    //   MC3  the negative-subtotal guard disabled (signum() < -1) -> exactly 3 red:
    //        twoAdjustments_drivingTheSubtotalNegative_areRejected,
    //        aFlatAdjustmentLargerThanTheDocument_isRejected, update_drivingTheSubtotalNegative_isRejected.
    //   MC4  each of the three @Digits removed in turn (DealQuotationRequests) -> exactly ONE red
    //        each, the matching case in DealQuotationRequestsValidationTest.
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * F1: a rep creates a SPECIAL_SQM quotation, reopens the draft, edits ONE header field and
     * saves without re-sending {@code priceMode}. Nothing about the money may move.
     *
     * <p>Before the fix the document repriced: 453.84 → 2,000.00 per แผ่น, {@code
     * special_price_sqm} to NULL, the ราคาพิเศษ sub-line gone, ส่วนลด พิเศษ→Net, subtotal 4.4×.
     */
    @Test
    void update_withNoPriceMode_keepsTheStoredSpecialSqmMode_andRepricesNothing() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"))),
            salesActor);

        // The obvious client edit: same items, a changed note, NO priceMode field on the wire.
        DealQuotationDto saved = quotationService.update(created.id(),
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
                "แก้หมายเหตุ", List.of(specialSqmItem("2000.00", 10, "1350"))),
            salesActor);

        assertThat(saved.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        DealQuotationItemDto item = saved.items().get(0);
        assertThat(item.netUnitPrice()).isEqualByComparingTo("453.84");
        assertThat(item.specialPriceSqm()).isEqualByComparingTo("1350");
        assertThat(item.specialPriceLine())
            .isEqualTo("(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)");
        assertThat(item.discountPct()).isNull();          // ส่วนลด stays "พิเศษ", never flips to Net
        assertThat(item.lineAmount()).isEqualByComparingTo("4538.40");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("4538.40");
        // Re-read from Postgres rather than trusting the returned object: the column, not the DTO.
        assertThat(quotationService.get(created.id(), salesActor).priceMode())
            .isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
    }

    /**
     * F1, DIRECT_NET — the worse half. The rep-typed net is deliberately NOT stored in a column of
     * its own (it IS final_unit_price), so once an update overwrites it with the list price there
     * is nothing left to recover it from.
     */
    @Test
    void update_withNoPriceMode_keepsTheStoredDirectNetMode_andRepricesNothing() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_DIRECT_NET,
                List.of(directNetItem("2000.00", 10, "777.50"))),
            salesActor);

        DealQuotationDto saved = quotationService.update(created.id(),
            new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
                "แก้หมายเหตุ", List.of(directNetItem("2000.00", 10, "777.50"))),
            salesActor);

        assertThat(saved.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_DIRECT_NET);
        assertThat(saved.items().get(0).unitPrice()).isEqualByComparingTo("2000.00");
        assertThat(saved.items().get(0).netUnitPrice()).isEqualByComparingTo("777.50");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("7775.00");
    }

    /** F1: an EXPLICIT mode still wins — that is what lets a rep switch a draft's mode at all.
     * Both directions, because keeping the stored mode must not become "ignore what was sent". */
    @Test
    void update_withAnExplicitPriceMode_switchesTheDocumentBothWays() {
        DealQuotationDto net = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("2000.00", 10))), salesActor);
        assertThat(net.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_NET);

        DealQuotationDto toSpecial = quotationService.update(net.id(),
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"))),
            salesActor);
        assertThat(toSpecial.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        assertThat(toSpecial.items().get(0).netUnitPrice()).isEqualByComparingTo("453.84");

        DealQuotationDto backToNet = quotationService.update(net.id(),
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_NET,
                List.of(sampleItem("2000.00", 10))),
            salesActor);
        assertThat(backToNet.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_NET);
        assertThat(backToNet.items().get(0).netUnitPrice()).isEqualByComparingTo("2000.00");
        assertThat(backToNet.items().get(0).specialPriceSqm()).isNull();
    }

    /** F1: the pre-v3 case — a NET document updated with no mode stays NET. Deliberately does NOT
     * red under the hardcode mutation (NET is NET either way); it is here to pin that "keep the
     * stored mode" did not change today's behaviour for the 99% of documents that are NET. */
    @Test
    void update_withNoPriceMode_onANetDocument_staysNet() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto saved = quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(saved.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_NET);
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("1000.00");
    }

    /**
     * F2: GET a document containing BOTH kinds of adjustment, hand every item back VERBATIM, and
     * the save must succeed with nothing moved.
     *
     * <p>Two separate traps sit on this path and both had to be fixed for it to pass:
     * <ol>
     *   <li>{@code mapItem} returns {@code unitPrice} = the derived amount on an ADJUSTMENT row
     *       (the ราคา column has to print it) and the service used to REFUSE a supplied price on
     *       exactly that row — a contract that 400s the caller for echoing a field we sent.</li>
     *   <li>A FLAT adjustment's amount had no field on the DTO at all, so there was nothing to
     *       echo and the PUT failed the "exactly one of percent / flat" rule instead. Hence
     *       {@code DealQuotationItemDto.adjustmentAmount}.</li>
     * </ol>
     */
    @Test
    void getThenPutVerbatim_roundTripsADocumentContainingBothKindsOfAdjustment() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                sampleItem("100.00", 10),                                 // 1,000
                plainItem("ค่าขนส่ง", "1", "JOB", "500.00"),               //   500
                adjustmentPctItem("3", LocalDate.of(2026, 7, 31)),        //   -45 (3% of 1,500)
                adjustmentFlatItem("250.00", "ส่วนลดตามตกลง"))),           //  -250
            salesActor);
        assertThat(created.subtotalAmount()).isEqualByComparingTo("1205.00");

        DealQuotationDto fetched = quotationService.get(created.id(), salesActor);
        DealQuotationDto saved = quotationService.update(created.id(),
            upsertRequest(fetched.items().stream()
                .map(item -> echoBack(item, fetched.priceMode())).toList()),
            salesActor);

        assertThat(saved.items()).extracting(DealQuotationItemDto::lineType)
            .containsExactly(WastageCalculator.LINE_TYPE_TILE, WastageCalculator.LINE_TYPE_PLAIN,
                WastageCalculator.LINE_TYPE_ADJUSTMENT, WastageCalculator.LINE_TYPE_ADJUSTMENT);
        assertThat(saved.items().get(0).lineAmount()).isEqualByComparingTo("1000.00");
        assertThat(saved.items().get(1).lineAmount()).isEqualByComparingTo("500.00");
        assertThat(saved.items().get(2).lineAmount()).isEqualByComparingTo("-45.00");
        assertThat(saved.items().get(3).lineAmount()).isEqualByComparingTo("-250.00");
        assertThat(saved.items().get(2).descriptionLine())
            .isEqualTo("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");
        assertThat(saved.items().get(2).adjustmentPct()).isEqualByComparingTo("3");
        assertThat(saved.items().get(2).adjustmentDeadline()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(saved.items().get(3).descriptionLine()).isEqualTo("ส่วนลดตามตกลง");
        assertThat(saved.items().get(3).adjustmentAmount()).isEqualByComparingTo("250.00");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo(created.subtotalAmount());
    }

    /** F2: the same round-trip on a SPECIAL_SQM document, which additionally exercises F1's
     * keep-the-stored-mode (the echoed payload carries no {@code priceMode}). */
    @Test
    void getThenPutVerbatim_roundTripsASpecialSqmDocument() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
                List.of(specialSqmItem("2000.00", 10, "1350"),
                    adjustmentPctItem("3", null))),
            salesActor);

        DealQuotationDto fetched = quotationService.get(created.id(), salesActor);
        DealQuotationDto saved = quotationService.update(created.id(),
            upsertRequest(fetched.items().stream()
                .map(item -> echoBack(item, fetched.priceMode())).toList()),
            salesActor);

        assertThat(saved.priceMode()).isEqualTo(WastageCalculator.PRICE_MODE_SPECIAL_SQM);
        assertThat(saved.items().get(0).netUnitPrice()).isEqualByComparingTo("453.84");
        assertThat(saved.items().get(0).specialPriceSqm()).isEqualByComparingTo("1350");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo(created.subtotalAmount());
    }

    /**
     * F3 — ⚠️ a NEW rule the owner has not ruled on, flagged in the PR body next to the
     * "two adjustments do not compound" question it falls out of.
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

    /** F3: a FLAT adjustment is bounded only by {@code @DecimalMax("99999999")} and bears no
     * relation to the document's own size, so it is the easier of the two paths to hit. */
    @Test
    void aFlatAdjustmentLargerThanTheDocument_isRejected() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10),
                adjustmentFlatItem("1000000.00", "ส่วนลดตามตกลง"))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ติดลบ");
    }

    /** F3, the boundary: EXACTLY zero is allowed. A 100% discount is at least an intelligible
     * document to issue; only a NEGATIVE grand total is refused. */
    @Test
    void anAdjustmentTakingTheSubtotalToExactlyZero_isAccepted() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10), adjustmentPctItem("100", null))),
            salesActor);
        assertThat(created.subtotalAmount()).isEqualByComparingTo("0.00");
    }

    /** F3 applies on UPDATE too, not only on create — the same {@code buildItems} is the single
     * gate, and a draft edited into the negative would otherwise slip past. */
    @Test
    void update_drivingTheSubtotalNegative_isRejected() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);

        assertThatThrownBy(() -> quotationService.update(created.id(),
            upsertRequest(List.of(sampleItem("100.00", 10),
                adjustmentFlatItem("5000.00", "ส่วนลดตามตกลง"))), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ติดลบ");

        // ...and the draft is untouched, because buildItems runs BEFORE the header compare-and-set.
        assertThat(quotationService.get(created.id(), salesActor).subtotalAmount())
            .isEqualByComparingTo("1000.00");
    }

    // ── Quotation arithmetic reconciliation (2026-09-15) — service-level reproductions of five
    // ── of the owner's real printed documents, through create()+get() against real Postgres.
    // ── D6 (English per-sqm boxes) is already pinned in DealQuotationEnglishIntegrationTest;
    // ── not duplicated here. D2/D4/D9 have no service-level equivalent (D2/D4 are plain-row
    // ── totals identical in shape to D1/D8 below; D9 is pieces-only, already pinned at the
    // ── WastageCalculator layer by WastageCalculatorTest/QuotationGoldenDocumentsTest). ────────

    @Test
    void goldenDocument_D1_QN6900971_4_plainRowsWithDiscount20pct() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                plainItemWithDiscount("สุขภัณฑ์ 1", "1", "ชุด", "58889.72", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 2", "1", "ชุด", "12143.93", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 3", "1", "ชุด", "8900.00", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 4", "1", "ชุด", "36598.13", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5", "2", "ชุด", "35433.64", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5.1", "2", "ชุด", "45581.31", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5.2", "2", "ชุด", "36764.49", "20"),
                // Bold in the plan: proves amount = round2(list × qty × (1 − pct/100)), not
                // round2(round2(net) × qty) — the double-rounded formula gives 35,799.64 here.
                plainItemWithDiscount("สุขภัณฑ์ 5.3", "2", "ชุด", "22374.77", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5.4", "2", "ชุด", "10314.02", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5.5", "2", "ชุด", "7153.27", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5.6", "2", "ชุด", "10314.02", "20"),
                plainItemWithDiscount("สุขภัณฑ์ 5.7", "2", "ชุด", "7153.27", "20"))),
            salesActor);
        DealQuotationDto saved = quotationService.get(created.id(), salesActor);
        assertThat(saved.items()).hasSize(12);
        String[] nets = {"47111.78", "9715.14", "7120.00", "29278.50", "28346.91", "36465.05", "29411.59",
            "17899.82", "8251.22", "5722.62", "8251.22", "5722.62"};
        String[] amounts = {"47111.78", "9715.14", "7120.00", "29278.50", "56693.82", "72930.10", "58823.18",
            "35799.63", "16502.43", "11445.23", "16502.43", "11445.23"};
        for (int i = 0; i < saved.items().size(); i++) {
            var item = saved.items().get(i);
            assertThat(item.netUnitPrice()).as("D1 row %s net", i + 1).isEqualByComparingTo(nets[i]);
            assertThat(item.lineAmount()).as("D1 row %s amount", i + 1).isEqualByComparingTo(amounts[i]);
        }
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("373367.47");
        assertThat(saved.vatAmount()).isEqualByComparingTo("26135.72");
        assertThat(saved.grandTotal()).isEqualByComparingTo("399503.19");
    }

    @Test
    void goldenDocument_D3_QN6900981_1_areaModeSpecialSqmNoWastage() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(
                // Row 1: 2,793 × 1.39 = 3,882.27 HALF_UPs to the printed 3,882 (an even box
                // multiple already) — CEILING would give 3,883, box-rounding up to 3,884.
                areaSpecialSqmItem("2793", "0.72", 2, "790"),
                areaSpecialSqmItem("274", "0.72", 2, "790"))),
            salesActor);
        DealQuotationDto saved = quotationService.get(created.id(), salesActor);
        assertThat(saved.items()).hasSize(2);
        assertThat(saved.items().get(0).piecesFinal()).isEqualTo(3882);
        assertThat(saved.items().get(0).netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(saved.items().get(0).lineAmount()).isEqualByComparingTo("2061963.12");
        assertThat(saved.items().get(1).piecesFinal()).isEqualTo(382);
        assertThat(saved.items().get(1).netUnitPrice()).isEqualByComparingTo("531.16");
        assertThat(saved.items().get(1).lineAmount()).isEqualByComparingTo("202903.12");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("2264866.24");
        assertThat(saved.vatAmount()).isEqualByComparingTo("158540.64");
        assertThat(saved.grandTotal()).isEqualByComparingTo("2423406.88");
    }

    @Test
    void goldenDocument_D5_QN6900704_2_piecesModeSpecialSqmWithAdjustment() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(
                piecesTileSpecialSqmItem(329, 3, "881.46", "1350", "0.36"),
                piecesTileSpecialSqmItem(202, 4, "843.14", "1400", "0.36"),
                piecesTileSpecialSqmItem(1161, 4, "881.46", "1350", "0.36"),
                piecesTileSpecialSqmItem(1100, 4, "900.63", "1350", "0.36"),
                adjustmentPctItem("3", LocalDate.of(2026, 7, 31)))),
            salesActor);
        DealQuotationDto saved = quotationService.get(created.id(), salesActor);
        assertThat(saved.items()).hasSize(5);
        int[] finalPieces = {330, 204, 1164, 1100};
        String[] nets = {"453.84", "470.65", "453.84", "453.84"};
        String[] amounts = {"149767.20", "96012.60", "528269.76", "499224.00"};
        for (int i = 0; i < 4; i++) {
            var item = saved.items().get(i);
            assertThat(item.piecesFinal()).as("D5 row %s pieces", i + 1).isEqualTo(finalPieces[i]);
            assertThat(item.netUnitPrice()).as("D5 row %s net", i + 1).isEqualByComparingTo(nets[i]);
            assertThat(item.lineAmount()).as("D5 row %s amount", i + 1).isEqualByComparingTo(amounts[i]);
        }
        assertThat(saved.items().get(4).lineAmount()).isEqualByComparingTo("-38198.21");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("1235075.35");
        assertThat(saved.vatAmount()).isEqualByComparingTo("86455.27");
        assertThat(saved.grandTotal()).isEqualByComparingTo("1321530.62");
    }

    @Test
    void goldenDocument_D7_QN6900648_areaModeSpecialSqmNoWastage() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequestWithMode(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(
                areaSpecialSqmItem("945", "0.72", 2, "1800"),
                areaSpecialSqmItem("339", "0.72", 2, "1800"),
                areaSpecialSqmItem("159", "0.72", 2, "860"),
                // The OTHER headline R-B example: 511 × 1.39 = 710.29 HALF_UPs to the printed
                // 710 (already even); CEILING gives 711, box-rounding up again to 712.
                areaSpecialSqmItem("511", "0.72", 2, "1800"),
                areaSpecialSqmItem("1200", "0.72", 2, "1800"))),
            salesActor);
        DealQuotationDto saved = quotationService.get(created.id(), salesActor);
        assertThat(saved.items()).hasSize(5);
        int[] pieces = {1314, 472, 222, 710, 1668};
        String[] nets = {"1210.25", "1210.25", "578.23", "1210.25", "1210.25"};
        String[] amounts = {"1590268.50", "571238.00", "128367.06", "859277.50", "2018697.00"};
        for (int i = 0; i < 5; i++) {
            var item = saved.items().get(i);
            assertThat(item.piecesFinal()).as("D7 row %s pieces", i + 1).isEqualTo(pieces[i]);
            assertThat(item.netUnitPrice()).as("D7 row %s net", i + 1).isEqualByComparingTo(nets[i]);
            assertThat(item.lineAmount()).as("D7 row %s amount", i + 1).isEqualByComparingTo(amounts[i]);
        }
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("5167848.06");
        assertThat(saved.vatAmount()).isEqualByComparingTo("361749.36");
        assertThat(saved.grandTotal()).isEqualByComparingTo("5529597.42");
    }

    @Test
    void goldenDocument_D8_QN6900782_2_plainRowsNoDiscount() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(
                plainItem("กระเบื้อง A", "22", "แผ่น", "1950"),
                plainItem("กระเบื้อง B", "24", "แผ่น", "385"),
                plainItem("กระเบื้อง C", "24", "แผ่น", "345"))),
            salesActor);
        DealQuotationDto saved = quotationService.get(created.id(), salesActor);
        assertThat(saved.items()).hasSize(3);
        assertThat(saved.items().get(0).lineAmount()).isEqualByComparingTo("42900.00");
        assertThat(saved.items().get(1).lineAmount()).isEqualByComparingTo("9240.00");
        assertThat(saved.items().get(2).lineAmount()).isEqualByComparingTo("8280.00");
        assertThat(saved.subtotalAmount()).isEqualByComparingTo("60420.00");
        assertThat(saved.vatAmount()).isEqualByComparingTo("4229.40");
        assertThat(saved.grandTotal()).isEqualByComparingTo("64649.40");
    }

    // ── Owner-approved "sell loose pieces" (2026-09-16, V182) ─────────────────────────────────

    /** The flag persists, round-trips on GET, and defaults true for a request that never mentions
     * it — same "null reads as true" contract as every other layer. */
    @Test
    void roundToFullBox_persistsAndRoundTripsOnGet() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        assertThat(created.items().get(0).roundToFullBox()).isTrue();

        DealQuotationDto reloaded = quotationService.get(created.id(), salesActor);
        assertThat(reloaded.items().get(0).roundToFullBox()).isTrue();
    }

    /** The headline case, end to end through the real service and Postgres: 32 pieces, box of 10,
     * no wastage — 3 full boxes plus 2 loose, piecesFinal UNROUNDED at 32 (not the old ceiling of
     * 40), and the printed line reflects it. Proves the column round-trips both directions, not
     * only "does not reject false". */
    @Test
    void roundToFullBoxFalse_persistsAndComputesTheLooseSplit_onCreateAndGet() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(loosePiecesItem(32, WastageCalculator.WASTAGE_MODE_NONE, null, 10,
                "50.00", false))),
            salesActor);
        var item = created.items().get(0);
        assertThat(item.roundToFullBox()).isFalse();
        assertThat(item.piecesFinal()).isEqualTo(32);
        assertThat(item.boxes()).isEqualTo(3);
        assertThat(item.calculationLine()).isEqualTo("(จำนวน 32 แผ่น = 3 กล่อง + 2 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
        // 50.00 * 32 (unrounded) = 1,600.00 -- NOT 50.00 * 40 (the old ceiling) = 2,000.00.
        assertThat(item.lineAmount()).isEqualByComparingTo("1600.00");

        DealQuotationDto reloaded = quotationService.get(created.id(), salesActor);
        var reloadedItem = reloaded.items().get(0);
        assertThat(reloadedItem.roundToFullBox()).isFalse();
        assertThat(reloadedItem.piecesFinal()).isEqualTo(32);
        assertThat(reloadedItem.boxes()).isEqualTo(3);
        assertThat(reloadedItem.calculationLine()).isEqualTo(item.calculationLine());
        assertThat(reloadedItem.lineAmount()).isEqualByComparingTo("1600.00");
    }

    /** update() can flip the flag on an existing row, in either direction, and the stored row
     * follows — not merely accepted at create and frozen thereafter. */
    @Test
    void roundToFullBox_canBeToggledOnUpdate_inEitherDirection() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(loosePiecesItem(32, WastageCalculator.WASTAGE_MODE_NONE, null, 10,
                "50.00", true))),
            salesActor);
        assertThat(created.items().get(0).roundToFullBox()).isTrue();
        assertThat(created.items().get(0).piecesFinal()).isEqualTo(40); // ceil(32/10)*10

        DealQuotationDto toggledOff = quotationService.update(created.id(),
            upsertRequest(List.of(loosePiecesItem(32, WastageCalculator.WASTAGE_MODE_NONE, null, 10,
                "50.00", false))),
            salesActor);
        assertThat(toggledOff.items().get(0).roundToFullBox()).isFalse();
        assertThat(toggledOff.items().get(0).piecesFinal()).isEqualTo(32);

        DealQuotationDto toggledBackOn = quotationService.update(created.id(),
            upsertRequest(List.of(loosePiecesItem(32, WastageCalculator.WASTAGE_MODE_NONE, null, 10,
                "50.00", true))),
            salesActor);
        assertThat(toggledBackOn.items().get(0).roundToFullBox()).isTrue();
        assertThat(toggledBackOn.items().get(0).piecesFinal()).isEqualTo(40);
    }

    /** {@code createRevision} copies its parent's rows VERBATIM (the class's own documented
     * contract for every other item field) — proven here for roundToFullBox specifically, in
     * BOTH directions, so a regression that silently defaulted a revision back to true (or froze
     * it at false) would be caught either way. */
    @Test
    void createRevision_copiesRoundToFullBoxVerbatim_bothDirections() {
        DealQuotationDto createdFalse = quotationService.create(ticketId,
            upsertRequest(List.of(loosePiecesItem(32, WastageCalculator.WASTAGE_MODE_NONE, null, 10,
                "50.00", false))),
            salesActor);
        quotationService.submit(createdFalse.id(), salesActor);
        DealQuotationDto approvedFalse =
            quotationService.approve(createdFalse.id(), new ApproveRequest(null), salesManagerActor);
        DealQuotationDto revisionOfFalse = quotationService.createRevision(approvedFalse.id(), salesActor);
        assertThat(revisionOfFalse.items().get(0).roundToFullBox()).isFalse();
        assertThat(revisionOfFalse.items().get(0).piecesFinal()).isEqualTo(32);
        assertThat(revisionOfFalse.items().get(0).boxes()).isEqualTo(3);

        DealQuotationDto createdTrue = quotationService.create(ticketId,
            upsertRequest(List.of(loosePiecesItem(32, WastageCalculator.WASTAGE_MODE_NONE, null, 10,
                "50.00", true))),
            salesActor);
        quotationService.submit(createdTrue.id(), salesActor);
        DealQuotationDto approvedTrue =
            quotationService.approve(createdTrue.id(), new ApproveRequest(null), salesManagerActor);
        DealQuotationDto revisionOfTrue = quotationService.createRevision(approvedTrue.id(), salesActor);
        assertThat(revisionOfTrue.items().get(0).roundToFullBox()).isTrue();
        assertThat(revisionOfTrue.items().get(0).piecesFinal()).isEqualTo(40);
    }

    /** A PLAIN row is unaffected: it has no {@code piecesPerBox} concept at all, and the stored
     * flag reads {@code true} (the moot default) rather than propagating whatever a client might
     * have sent for a TILE row elsewhere in the same payload. */
    @Test
    void roundToFullBox_plainRow_isAlwaysTrue_flagIsMootWithoutABox() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(plainItem("ค่าขนส่ง", "1", "JOB", "500.00"))), salesActor);
        assertThat(created.items().get(0).roundToFullBox()).isTrue();
    }

    /** V182 "sell loose pieces" fixture: a PIECES-mode TILE row with explicit piecesPerBox,
     * wastage and roundToFullBox — every other field mirrors {@link #sampleItem}'s 60x60/
     * 0.36-sqm-per-piece fixture (so it satisfies the same item-completeness rule). */
    private ItemInput loosePiecesItem(int piecesInput, String wastageMode, String wastageValue,
                                      int piecesPerBox, String unitPrice, boolean roundToFullBox) {
        return new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, piecesInput,
            wastageMode, wastageValue == null ? null : new BigDecimal(wastageValue), piecesPerBox,
            new BigDecimal(unitPrice), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null,
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            null, null, null, null, null,
            null, null, roundToFullBox);
    }

    /** D1/D8 — a PLAIN row with an explicit discount ({@link #plainItem} has none). */
    private ItemInput plainItemWithDiscount(String description, String quantity, String unit,
                                            String unitPrice, String discountPct) {
        return new ItemInput(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            new BigDecimal(unitPrice), new BigDecimal(discountPct), null, null, null, null,
            WastageCalculator.LINE_TYPE_PLAIN, description, new BigDecimal(quantity), unit,
            null, null, null, null, null);
    }

    /** D1 (owner decision, 2026-09-16) — a PLAIN row carrying the OPTIONAL import lead time
     * QuotationPlainItemRow's new control now sends ({@link #plainItem} has none, same as every
     * PLAIN row before this fix). */
    private ItemInput plainItemWithLeadTime(String description, String quantity, String unit,
                                            String unitPrice, int leadTimeMinDays, int leadTimeMaxDays) {
        return new ItemInput(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            new BigDecimal(unitPrice), null, null, leadTimeMinDays, leadTimeMaxDays, null,
            WastageCalculator.LINE_TYPE_PLAIN, description, new BigDecimal(quantity), unit,
            null, null, null, null, null);
    }

    /** D3/D7 — an AREA-mode TILE row priced SPECIAL_SQM, no wastage. {@code unitPrice} is a
     * dummy positive value: SPECIAL_SQM pricing never reads it for the net/amount computation
     * (only validates it is positive; the printed ราคา column instead shows the special price). */
    private ItemInput areaSpecialSqmItem(String area, String sqmPerPiece, int piecesPerBox, String specialPerSqm) {
        return new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x120",
            new BigDecimal("10"), new BigDecimal(sqmPerPiece),
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal(area), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, piecesPerBox,
            new BigDecimal("1"), null, "ไทย-สต็อก", 30, 45, null,
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            new BigDecimal(specialPerSqm), null, null, null, null);
    }

    /** D5 — a PIECES-mode TILE row priced SPECIAL_SQM, no wastage, box rounding only. */
    private ItemInput piecesTileSpecialSqmItem(int piecesInput, int piecesPerBox, String listPrice,
                                               String specialPerSqm, String sqmPerPiece) {
        return new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal(sqmPerPiece),
            WastageCalculator.QUANTITY_MODE_PIECES, null, piecesInput,
            WastageCalculator.WASTAGE_MODE_NONE, null, piecesPerBox,
            new BigDecimal(listPrice), null, "ไทย-สต็อก", 30, 45, null,
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            new BigDecimal(specialPerSqm), null, null, null, null);
    }

    // ── v3 fixtures ────────────────────────────────────────────────────────────────────────

    /**
     * What an honest UI does on save: hand every field the GET returned straight back. Mirrors
     * {@code DealQuotationItemDto} onto {@code ItemInput} one-for-one, with the two mappings the
     * DTO cannot make for itself:
     * <ul>
     *   <li>{@code description} comes from {@code descriptionLine} — the same field under the two
     *       names it has on the way out and the way in.</li>
     *   <li>{@code directNetPrice} comes from {@code netUnitPrice}. There is no {@code
     *       directNetPrice} on the DTO ON PURPOSE (V168: the typed net IS final_unit_price, and a
     *       second column could only drift), so this mapping is the caller's job — recoverable,
     *       unlike a flat adjustment's amount, which is why THAT one needed a DTO field.</li>
     * </ul>
     */
    private ItemInput echoBack(DealQuotationItemDto src, String priceMode) {
        boolean directNetTile = WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)
            && WastageCalculator.LINE_TYPE_TILE.equals(src.lineType());
        return new ItemInput(src.locationLabel(), src.catalogPriceId(), src.productCode(), src.brand(),
            src.model(), src.color(), src.texture(), src.sizeText(), src.thicknessMm(), src.sqmPerPiece(),
            src.quantityMode(), src.areaSqm(), src.piecesInput(), src.wastageMode(), src.wastageValue(),
            src.piecesPerBox(), src.unitPrice(), src.discountPct(), src.originCountry(),
            src.leadTimeMinDays(), src.leadTimeMaxDays(), src.itemNotes(),
            src.lineType(), src.descriptionLine(), src.quantity(), src.unit(), src.specialPriceSqm(),
            directNetTile ? src.netUnitPrice() : null,
            src.adjustmentPct(), src.adjustmentDeadline(), src.adjustmentAmount());
    }

    private UpsertDealQuotationRequest upsertRequestWithMode(String priceMode, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            "หมายเหตุทดสอบ", priceMode, items);
    }

    /** The full canonical constructor, for the one field (projectName) none of this class's other
     * helpers thread through. Every other field left at its "use the default" value. */
    private UpsertDealQuotationRequest upsertRequestWithProjectName(String projectName, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            null, null, "หมายเหตุทดสอบ", null, null, null, null, null, projectName, items);
    }

    /** Owner-directed reversal of F2 (2026-09-26) — the full canonical constructor, for the one
     * field (orderedByName) none of this class's other helpers thread through. Every other field
     * left at its "use the default" value. */
    private UpsertDealQuotationRequest upsertRequestWithOrderedByName(String orderedByName, List<ItemInput> items) {
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            null, null, "หมายเหตุทดสอบ", null, null, null, null, null, null, null, null, orderedByName, items);
    }

    /** {@link #sampleItem} (60x60 -> 0.36 ตร.ม./แผ่น, piecesPerBox 1) plus a ราคาพิเศษ. */
    private ItemInput specialSqmItem(String listPrice, int pieces, String specialPerSqm) {
        ItemInput base = sampleItem(listPrice, pieces);
        return new ItemInput(base.locationLabel(), base.catalogPriceId(), base.productCode(), base.brand(),
            base.model(), base.color(), base.texture(), base.sizeText(), base.thicknessMm(), base.sqmPerPiece(),
            base.quantityMode(), base.areaSqm(), base.piecesInput(), base.wastageMode(), base.wastageValue(),
            base.piecesPerBox(), base.unitPrice(), base.discountPct(), base.originCountry(),
            base.leadTimeMinDays(), base.leadTimeMaxDays(), base.itemNotes(),
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            new BigDecimal(specialPerSqm), null, null, null, null);
    }

    private ItemInput directNetItem(String listPrice, int pieces, String directNet) {
        ItemInput base = sampleItem(listPrice, pieces);
        return new ItemInput(base.locationLabel(), base.catalogPriceId(), base.productCode(), base.brand(),
            base.model(), base.color(), base.texture(), base.sizeText(), base.thicknessMm(), base.sqmPerPiece(),
            base.quantityMode(), base.areaSqm(), base.piecesInput(), base.wastageMode(), base.wastageValue(),
            base.piecesPerBox(), base.unitPrice(), base.discountPct(), base.originCountry(),
            base.leadTimeMinDays(), base.leadTimeMaxDays(), base.itemNotes(),
            WastageCalculator.LINE_TYPE_TILE, null, null, null, null,
            new BigDecimal(directNet), null, null, null);
    }

    /** S2 — description/quantity/unit/price and nothing else. */
    private ItemInput plainItem(String description, String quantity, String unit, String unitPrice) {
        return new ItemInput(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            unitPrice == null ? null : new BigDecimal(unitPrice), null, null, null, null, null,
            WastageCalculator.LINE_TYPE_PLAIN, description, new BigDecimal(quantity), unit,
            null, null, null, null, null);
    }

    private ItemInput adjustmentPctItem(String pct, LocalDate deadline) {
        return adjustmentItem(new BigDecimal(pct), null, deadline, null);
    }

    private ItemInput adjustmentFlatItem(String amount, String description) {
        return adjustmentItem(null, new BigDecimal(amount), null, description);
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
