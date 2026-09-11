package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ChromiumPdfPrinter;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
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
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB acceptance + authz + concurrency coverage for Quotation v2 (direct deal quotation,
 * V165) — see docs/sales/quotation-v2-plan.md. Copies the hand-wiring style of {@code
 * customerquotation/CustomerQuotationIntegrationTest} (its own {@code @BeforeEach}/helpers).
 */
class DealQuotationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private CustomerRepository customers;
    private ContactRepository contacts;
    private ProjectRepository projects;
    private NotificationRepository notifications;
    private DealQuotationRepository quotationRepository;
    private DealQuotationService quotationService;
    private EmployeeAuthRepository employeeAuth;
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

        quotationRepository = new DealQuotationRepository(jdbc);
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
            approvalMailer, quotationRenderer, employeeAuth, signatureRepository, "https://portal.test");

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

        // Email attempted via the capturing mailer, synchronously (no active transaction in this
        // hand-wired call — see DealQuotationService#afterCommit's "no transaction, no deferral").
        assertThat(mailer.attachmentsSent).isNotEmpty();
        assertThat(mailer.attachmentsSent.get(0)[1]).contains(approved.number());
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
        assertThat(revision.number()).isEqualTo(parent.number() + "-" + revision.revisionNo());

        // Parent stays APPROVED (live/valid) while the revision is still a draft.
        assertThat(quotationService.get(parent.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);

        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto childApproved = quotationService.approve(revision.id(), new ApproveRequest(null), ceoActor);
        assertThat(childApproved.docStatus()).isEqualTo(QuotationStatus.APPROVED);

        // Only NOW does the parent become SUPERSEDED.
        assertThat(quotationService.get(parent.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void revisionNumbering_chainsOffTheOriginalBaseNumber_notTheImmediateParent() {
        DealQuotationDto rev1 = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        String base = rev1.number();

        DealQuotationDto rev2 = quotationService.createRevision(rev1.id(), salesActor);
        assertThat(rev2.number()).isEqualTo(base + "-2");
        quotationService.submit(rev2.id(), salesActor);
        quotationService.approve(rev2.id(), new ApproveRequest(null), salesManagerActor);

        DealQuotationDto rev3 = quotationService.createRevision(rev2.id(), salesActor);
        // Must be "{base}-3", never "{base}-2-3".
        assertThat(rev3.number()).isEqualTo(base + "-3");
        assertThat(rev3.revisionNo()).isEqualTo(3);
    }

    @Test
    void reject_returnsToDraftWithReasonAsNote() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);

        DealQuotationDto rejected = quotationService.reject(submitted.id(),
            new RejectRequest("ราคาผิดพลาด กรุณาแก้ไข"), salesManagerActor);

        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(rejected.approvalNote()).isEqualTo("ราคาผิดพลาด กรุณาแก้ไข");
        assertThat(notifications.findByEmployeeId(salesRepId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_REJECTED") && n.message().contains("ราคาผิดพลาด"));

        // A rejected-then-resubmitted draft clears the stale note.
        DealQuotationDto resubmitted = quotationService.submit(rejected.id(), salesActor);
        assertThat(resubmitted.approvalNote()).isNull();
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
        // Blanking sizeText ALSO removes the only way sqmPerPiece can be derived, so the message
        // names both -- correct, not a double-count: the row genuinely lacks both pieces of data.
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
        // sizeText present but not a "WxH" shape the parser recognises -- sqmPerPiece cannot be
        // derived and was never typed explicitly either, so completeness must still catch it.
        ItemInput unparseableSize = itemMissing(i -> i.withSizeText("ตามภาพ"));
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

    /** Builder-shaped helper over the complete {@link #sampleItem} fixture, for one-field-at-a-time
     * incompleteness tests -- avoids a 21-argument constructor call per test case. */
    private ItemInput itemMissing(java.util.function.UnaryOperator<ItemInputBuilder> mutate) {
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
        ItemInputBuilder withPiecesPerBox(Integer v) { piecesPerBox = v; return this; }
        ItemInputBuilder withUnitPrice(BigDecimal v) { unitPrice = v; return this; }
        ItemInputBuilder withPiecesInput(Integer v) { piecesInput = v; return this; }

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
        // sampleItem(PIECES, wastage NONE, no piecesPerBox): calculationLine = "(จำนวน 10 แผ่น = 10 แผ่น)".
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

    @Test
    void render_draft_printsContactNameInSlot4_andOnlyThePrintedOnDate() throws Exception {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        String[] rows = signatureRows(quotationService.renderXlsx(created.id(), salesActor));
        String names = rows[1];
        String dates = rows[2];
        assertThat(names).contains("(สมหญิง ใจดี)");
        // Slot 4 is the LAST slot: the contact's name comes after the sales rep's.
        assertThat(names.indexOf("(สมหญิง ใจดี)")).isGreaterThan(names.indexOf("(" + created.salesRepName() + ")"));
        // Draft: no approver name yet -> one dotted name placeholder (slot 3).
        assertThat(names).containsOnlyOnce("(..........................)");
        // Dates: ผู้พิมพ์ = created date; the other three slots stay dotted.
        assertThat(dates).contains(thaiShort(created.createdAt()));
        assertThat(dates.split("วันที่........./........./.........", -1)).as("three dotted date slots").hasSize(4);
    }

    @Test
    void render_approved_printsAllThreeDates_andOrderedByStaysDotted() throws Exception {
        DealQuotationDto approved = createSubmittedApproved(ticketId, salesActor, salesManagerActor);
        String[] rows = signatureRows(quotationService.renderXlsx(approved.id(), salesActor));
        String dates = rows[2];
        assertThat(dates).contains(thaiShort(approved.createdAt()));
        assertThat(dates).contains(thaiShort(approved.submittedAt()));
        assertThat(dates).contains(thaiShort(approved.approvedAt()));
        // ผู้สั่งซื้อ never gets a date -- the customer signs and dates on paper.
        assertThat(dates.split("วันที่........./........./.........", -1)).as("exactly one dotted date slot").hasSize(2);
        assertThat(dates.lastIndexOf("วันที่........./........./.........")).isGreaterThan(dates.lastIndexOf(thaiShort(approved.approvedAt())));
        assertThat(rows[1]).contains("(สมหญิง ใจดี)").contains("(" + approved.approvedByName() + ")");
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
    // Mutation-checked (see the class Javadoc's "H5 mutation check" note at the bottom of this
    // file / the PR body) by deleting the owner scope in DealQuotationService#requireViewAccess
    // AND the ownerFilter branch in #search, confirming these tests (and only these) go red, then
    // reverting.
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

    @Test
    void importAndAccount_canReadAnyDeal_readOnly() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        for (UserPrincipal reader : List.of(importActor, accountActor)) {
            assertThat(quotationService.get(created.id(), reader).id()).isEqualTo(created.id());
            assertThat(quotationService.listForTicket(ticketId, reader))
                .extracting(DealQuotationDto::id).contains(created.id());
            assertThat(quotationService.search(null, false, reader))
                .extracting(DealQuotationDto::id).contains(created.id());
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
    void signatureUpload_byAnotherPlainEmployee_isForbidden() {
        MockMultipartFile file = pngFile();
        assertThatThrownBy(() -> signatureService.upload(salesManagerId, file, employeeActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void signatureUpload_selfCeoOrAdmin_allowed_andMagicBytesValidated() {
        MockMultipartFile file = pngFile();
        // Self.
        signatureService.upload(salesManagerId, file, salesManagerActor);
        assertThat(signatureService.get(salesManagerId, salesManagerActor).mimeType()).isEqualTo("image/png");

        // CEO on someone else's signature.
        signatureService.upload(salesRepId, file, ceoActor);
        assertThat(signatureService.get(salesRepId, ceoActor).mimeType()).isEqualTo("image/png");

        // Declared PNG content-type but NOT real PNG magic bytes -- rejected.
        MockMultipartFile fake = new MockMultipartFile("file", "sig.png", "image/png", "not a real png".getBytes());
        assertThatThrownBy(() -> signatureService.upload(salesRepId, fake, salesRepActor()))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    /** Wrong-way-round: an employee id with no {@code hr.employee} row is 404 on all three verbs —
     * the real-backend write sweep found DELETE answering 204 against id 999999. The 403 gate
     * still comes first, so a caller without the capability learns nothing about which ids exist. */
    @Test
    void signature_unknownEmployee_isNotFoundOnEveryVerb_andStillForbiddenWithoutTheCapability() {
        long unknown = 999_999L;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr.employee WHERE employee_id = :id",
            java.util.Map.of("id", unknown), Integer.class)).as("fixture: id must not exist").isZero();

        assertNotFound(() -> signatureService.delete(unknown, ceoActor), "ไม่พบพนักงาน");
        assertNotFound(() -> signatureService.get(unknown, ceoActor), "ไม่พบพนักงาน");
        assertNotFound(() -> signatureService.upload(unknown, pngFile(), ceoActor), "ไม่พบพนักงาน");

        assertForbidden(() -> signatureService.delete(unknown, employeeActor));
        assertForbidden(() -> signatureService.get(unknown, salesManagerActor));
    }

    /** DELETE stays idempotent for an employee that EXISTS: no signature stored is 204, not 404. */
    @Test
    void signatureDelete_existingEmployeeWithoutSignature_isIdempotent() {
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
        // piecesPerBox=1 (not e.g. 4) deliberately -- box rounding is a no-op at box size 1
        // (ceil(n/1)*1 == n for every n), so every dollar-amount assertion across this large
        // file that was written against the OLD (no-box-rounding) piecesFinal keeps passing
        // unchanged; a caller that specifically wants to exercise box rounding builds its own
        // ItemInput (see e.g. #incompleteItem_missingPiecesPerBox_isBadRequestNamingTheRowAndField's
        // ItemInputBuilder).
        return new ItemInput(locationLabel, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), null,
            WastageCalculator.QUANTITY_MODE_PIECES, null, pieces, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal(unitPrice), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
    }

    private MockMultipartFile pngFile() {
        // Real PNG magic bytes (89 50 4E 47 0D 0A 1A 0A) + a little padding.
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

        @Override
        public void send(String to, String subject, String body) {}

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody, List<InlineImage> inlineImages) {}

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
