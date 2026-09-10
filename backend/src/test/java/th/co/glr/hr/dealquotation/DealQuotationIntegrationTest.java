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
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
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

    @BeforeEach
    void wireServicesAndCreateDeals() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
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
        quotationService = new DealQuotationService(quotationRepository, tickets, customers, notifications,
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

        CustomerDto customer = customers.create(
            "บริษัท Deal Quotation จำกัด", "0100000000099", "999 ถนนทดสอบ", "สนญ.", "02-999-9999");
        ProjectDto project = projects.create(customer.id(), "โครงการ Deal Quotation");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล DQ", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, null), salesActor);
        ticketId = created.summary().id();
        TicketDto otherCreated = ticketService.create(
            new CreateTicketRequest("ดีล DQ อื่น", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, null), otherSalesActor);
        otherTicketId = otherCreated.summary().id();
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
        assertThat(approved.quotationDate()).isEqualTo(approved.approvedAt()
            .atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalDate());

        // In-app notification to the rep (== creator here, so exactly one row, not two).
        assertThat(notifications.findByEmployeeId(salesRepId))
            .anyMatch(n -> n.type().equals("DEAL_QUOTATION_APPROVED"));

        // Email attempted via the capturing mailer, synchronously (no active transaction in this
        // hand-wired call — see DealQuotationService#afterCommit's "no transaction, no deferral").
        assertThat(mailer.attachmentsSent).isNotEmpty();
        assertThat(mailer.attachmentsSent.get(0)[1]).contains(approved.number());
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

        List<DealQuotationDto> results = quotationService.search(null, salesActor);
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
        assertForbidden(() -> quotationService.search(null, qcActor));
    }

    @Test
    void importAndAccount_canReadAnyDeal_readOnly() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(List.of(sampleItem("100.00", 10))), salesActor);
        for (UserPrincipal reader : List.of(importActor, accountActor)) {
            assertThat(quotationService.get(created.id(), reader).id()).isEqualTo(created.id());
            assertThat(quotationService.listForTicket(ticketId, reader))
                .extracting(DealQuotationDto::id).contains(created.id());
            assertThat(quotationService.search(null, reader))
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
        return new UpsertDealQuotationRequest("P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
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
}
