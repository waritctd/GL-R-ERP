package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestAttachmentDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RequestMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RespondMoreInformationRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;

/**
 * End-to-end acceptance walk for Step 1 of the sales pricing-request separation
 * (see docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md, "commit 7").
 *
 * <p>Exercises {@link TicketService}, {@link PricingRequestService},
 * {@link PricingRequestRepository}, {@link NotificationRepository} and
 * {@link EmployeeRepository} together against a real PostgreSQL database — the gap
 * neither {@code TicketServiceTest}/{@code PricingRequestServiceTest} (Mockito, all
 * collaborators stubbed) nor {@code PricingRequestRepositoryIntegrationTest} (single
 * repository, no service-layer authz) can cover: whether the two aggregates
 * (deal/ticket vs pricing request) actually stay decoupled when driven through their
 * real service layer, not just through direct repository calls.
 *
 * <p>Collaborators that would be awkward to stand up for real — {@link PriceCalcService}
 * (pricing engine business logic, unrelated to this walk) — are mocked. Everything else
 * ({@link TicketRepository}, {@link PricingRequestRepository}, {@link PricingRequestService},
 * {@link NotificationRepository}, {@link EmployeeRepository}, {@link CustomerRepository},
 * {@link ProjectRepository}, {@link QuotationRenderer}) is real and backed by the same
 * {@code jdbc} the base class resets before every test.
 */
class PricingRequestFlowIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private NotificationRepository notifications;

    private long salesRepId;
    private long secondSalesRepId;
    private long importUserId;
    private long secondImportUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal secondSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal secondImportActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;

    private long ticketId;
    // Financial-integrity review Finding A (commit 3): submit() now requires every item's
    // catalog snapshot to be fully resolved, so pricingItem() below must reference a real,
    // ACTIVE catalog product rather than pure free text.
    private long catalogProductId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        ObjectMapper objectMapper = new ObjectMapper();
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-pricing-flow-test-uploads"));
        // PriceCalcService is genuinely awkward business logic unrelated to this walk
        // (no price is ever proposed/calculated here) — mocked, per the task brief.
        // Every other collaborator is real and cheap to construct.
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย หนึ่ง", "sales1@glr.co.th", "SALES", "แผนกขาย");
        secondSalesRepId = createEmployee(employees, "พนักงานขาย สอง", "sales2@glr.co.th", "SALES", "แผนกขาย");
        // source_code 'PCIM' matches NotificationRepository.notifyByRole("import", ...)'s
        // "d.source_code ILIKE 'PCIM%'" filter — required for the "Import got a
        // notification" assertion below to actually find a row.
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        // Principal role drives service authz; keep this employee out of PCIM so
        // submit-notification tests still have exactly one Import recipient.
        secondImportUserId = createEmployee(employees, "ฝ่ายนำเข้า สำรอง", "import2@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ");
        ceoUserId = createEmployee(employees, "ผู้บริหาร ทดสอบ", "ceo@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "ฝ่ายบัญชี ทดสอบ", "account@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager@glr.co.th", "SALES", "ฝ่ายขาย");

        salesActor = actor(salesRepId, "sales");
        secondSalesActor = actor(secondSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        secondImportActor = actor(secondImportUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        catalogProductId = insertCatalogProduct("Test Catalog Factory", "TH", "TEST-PROD-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท ทดสอบ จำกัด", "0100000000000", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบ");

        TicketDto created = ticketService.create(
            new CreateTicketRequest("ใบเสนอราคา", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null,
                List.of(
                    item("Toyota", "Hilux"),
                    item("Honda", "Civic"),
                    item("Mazda", "BT-50"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ── 1. Creating a deal with 3 products starts no pricing ───────────────

    @Test
    void createDeal_withThreeItems_startsAsDraftWithOneCreatedEventAndNoNotifications() {
        String status = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(status).isEqualTo(TicketStatus.DRAFT);

        List<String> eventKinds = jdbc.query(
            "SELECT kind FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), (rs, rowNum) -> rs.getString("kind"));
        assertThat(eventKinds).containsExactly(TicketEventKind.CREATED);

        long notificationCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.notification", Map.of(), Long.class);
        assertThat(notificationCount).isZero();
        Long importNotificationCount = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification n
              JOIN hr.employee e ON e.employee_id = n.employee_id
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE d.source_code ILIKE 'PCIM%'
            """, Map.of(), Long.class);
        assertThat(importNotificationCount).isZero();

        int itemCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId), Integer.class);
        assertThat(itemCount).isEqualTo(3);
    }

    // ── 2. Two pricing requests coexist under one deal ─────────────────────

    @Test
    void twoPricingRequests_coexistOnTheSameDeal_bothDraftWithDistinctCodes() {
        PricingRequestDetailDto designer = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor);
        PricingRequestDetailDto buyer = pricingRequestService.createDraft(ticketId, buyerCreateRequest(), salesActor);

        assertThat(designer.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(buyer.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(designer.summary().requestCode()).matches("PCR-" + java.time.Year.now() + "-\\d{4}");
        assertThat(buyer.summary().requestCode()).matches("PCR-" + java.time.Year.now() + "-\\d{4}");
        assertThat(designer.summary().requestCode()).isNotEqualTo(buyer.summary().requestCode());

        List<PricingRequestSummaryDto> forTicket = pricingRequestService.listForTicket(ticketId, salesActor);
        assertThat(forTicket).extracting(PricingRequestSummaryDto::id)
            .containsExactlyInAnyOrder(designer.summary().id(), buyer.summary().id());
    }

    // ── 3. Submitting one leaves the other untouched, and does not move the deal ──

    @Test
    void submittingOneRequest_leavesTheOtherUntouchedAndDoesNotMoveTheDeal() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        long buyerId = pricingRequestService.createDraft(ticketId, buyerCreateRequest(), salesActor).summary().id();

        String salesStageBefore = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);

        PricingRequestDetailDto submitted = pricingRequestService.submit(designerId, salesActor);

        assertThat(submitted.summary().status()).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(submitted.summary().submittedAt()).isNotNull();

        PricingRequestSummaryDto stillDraft = pricingRequestService.get(buyerId, salesActor).summary();
        assertThat(stillDraft.status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(stillDraft.submittedAt()).isNull();

        String statusAfter = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        String salesStageAfter = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(statusAfter).isEqualTo(TicketStatus.DRAFT);
        assertThat(salesStageAfter).isEqualTo(salesStageBefore);

        List<Long> notifiedEmployeeIds = jdbc.queryForList(
            "SELECT employee_id FROM hr.notification ORDER BY employee_id", Map.of(), Long.class);
        assertThat(notifiedEmployeeIds).containsExactly(importUserId, ceoUserId);
        List<String> links = jdbc.queryForList(
            "SELECT link FROM hr.notification ORDER BY employee_id", Map.of(), String.class);
        assertThat(links).containsExactly("/pricing-requests/" + designerId, "/pricing-requests/" + designerId);
    }

    // ── 4. Import pickup assigns the request, never the deal (the landmine guard) ──

    @Test
    void importPickup_assignsTheRequestOnly_neverTheDealOrItsEventLog() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        long buyerId = pricingRequestService.createDraft(ticketId, buyerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(designerId, salesActor);

        PricingRequestDetailDto pickedUp = pricingRequestService.pickup(designerId, importActor);

        assertThat(pickedUp.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(pickedUp.summary().assignedImportId()).isEqualTo(importUserId);

        Long ticketAssignedTo = jdbc.queryForObject(
            "SELECT assigned_to FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), Long.class);
        assertThat(ticketAssignedTo).isNull();

        List<String> eventKinds = jdbc.query(
            "SELECT kind FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), (rs, rowNum) -> rs.getString("kind"));
        assertThat(eventKinds).containsExactly(TicketEventKind.CREATED); // still exactly one, unchanged

        PricingRequestSummaryDto stillDraft = pricingRequestService.get(buyerId, salesActor).summary();
        assertThat(stillDraft.status()).isEqualTo(PricingRequestStatus.DRAFT);
    }

    // ── 5. The information loop preserves ownership ────────────────────────

    @Test
    void informationLoop_returnsToImportReviewingAndPreservesPickupOwnership() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(designerId, salesActor);
        pricingRequestService.pickup(designerId, importActor);

        PricingRequestSummaryDto beforeLoop = pricingRequestService.get(designerId, importActor).summary();
        Instant pickedUpAtBefore = beforeLoop.pickedUpAt();
        assertThat(pickedUpAtBefore).isNotNull();

        pricingRequestService.requestInformation(designerId,
            new RequestMoreInformationRequest("กรุณาระบุขนาดสินค้าเพิ่มเติม", null), importActor);
        PricingRequestDetailDto responded = pricingRequestService.respondInformation(designerId,
            new RespondMoreInformationRequest("ขนาด 60x60 ซม."), salesActor);

        assertThat(responded.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(responded.summary().pickedUpAt()).isEqualTo(pickedUpAtBefore);
        assertThat(responded.summary().assignedImportId()).isEqualTo(importUserId);
    }

    // ── 6. Gap 2 (review-remediation Commit F): the full pickup -> request-info ──
    // -> respond sequence, driven through the real services with a status
    // assertion after EVERY step, not just the two isolated fragments above
    // (#4 stops at pickup; #5 fabricates its starting point via pickup() but
    // never checks submit's/pickup's own returned status). Ends by proving
    // pickedUpAt/assignedImportId survived the whole round trip byte-identical,
    // per the task brief.

    @Test
    void fullSequence_pickupThenRequestInformationThenRespond_assertsStatusAtEveryStepAndPreservesPickupFields() {
        PricingRequestDetailDto draft = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor);
        long id = draft.summary().id();
        assertThat(draft.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);

        PricingRequestDetailDto submitted = pricingRequestService.submit(id, salesActor);
        assertThat(submitted.summary().status()).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(submitted.summary().pickedUpAt()).isNull();
        assertThat(submitted.summary().assignedImportId()).isNull();

        PricingRequestDetailDto pickedUp = pricingRequestService.pickup(id, importActor);
        assertThat(pickedUp.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(pickedUp.summary().assignedImportId()).isEqualTo(importUserId);
        Instant pickedUpAt = pickedUp.summary().pickedUpAt();
        assertThat(pickedUpAt).isNotNull();

        PricingRequestDetailDto infoRequested = pricingRequestService.requestInformation(id,
            new RequestMoreInformationRequest("กรุณาระบุขนาดสินค้าเพิ่มเติม", null), importActor);
        assertThat(infoRequested.summary().status()).isEqualTo(PricingRequestStatus.MORE_INFO_REQUIRED);
        // The COALESCE-preserving transition() must not touch either field just
        // because the status moved away from IMPORT_REVIEWING.
        assertThat(infoRequested.summary().pickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(infoRequested.summary().assignedImportId()).isEqualTo(importUserId);

        PricingRequestDetailDto responded = pricingRequestService.respondInformation(id,
            new RespondMoreInformationRequest("ขนาด 60x60 ซม."), salesActor);
        assertThat(responded.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);

        // picked_up_at and assigned_import_id survived the entire pickup ->
        // request-info -> respond round trip unchanged (byte-identical, not
        // just "still non-null").
        assertThat(responded.summary().pickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(responded.summary().assignedImportId()).isEqualTo(importUserId);

        // Read straight from Postgres too — not just the DTO the service handed
        // back — so a bug that resets the column but not the in-memory object
        // (or vice versa) cannot hide behind the assertions above.
        Instant persistedPickedUpAt = jdbc.queryForObject(
            "SELECT picked_up_at FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", id), Instant.class);
        Long persistedAssignedImportId = jdbc.queryForObject(
            "SELECT assigned_import_id FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", id), Long.class);
        assertThat(persistedPickedUpAt).isEqualTo(pickedUpAt);
        assertThat(persistedAssignedImportId).isEqualTo(importUserId);
    }

    // ── extra: a second sales rep cannot discover draft, then cannot read submitted ──

    @Test
    void aSecondSalesRep_cannotReadTheFirstReps_pricingRequest() {
        long designerId = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.get(designerId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        pricingRequestService.submit(designerId, salesActor);
        assertThatThrownBy(() -> pricingRequestService.get(designerId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.listForTicket(ticketId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── Pricing Request attachments (V69, review remediation COMMIT 4) ──────

    @Test
    void owner_canUploadListDownloadAndDeleteAnAttachmentWhileDraft() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        PricingRequestAttachmentDto uploaded =
            pricingRequestService.uploadAttachment(id, sampleFile(), salesActor);
        assertThat(uploaded.includeInFactoryEmail()).isFalse();
        assertThat(pricingRequestService.listAttachments(id, salesActor))
            .extracting(PricingRequestAttachmentDto::id).contains(uploaded.id());
        String path = pricingRequestService.attachmentFilePath(uploaded.id(), salesActor);
        assertThat(java.nio.file.Files.exists(java.nio.file.Paths.get(path))).isTrue();

        pricingRequestService.deleteAttachment(uploaded.id(), salesActor);

        assertThat(pricingRequestService.listAttachments(id, salesActor)).isEmpty();
        assertThatThrownBy(() -> pricingRequestService.attachmentFilePath(uploaded.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * Wrong-way-round: a second sales rep must never reach the first rep's Pricing Request
     * attachments, at any status — this is the authz case the review explicitly calls out.
     * Mirrors {@code aSecondSalesRep_cannotReadTheFirstReps_pricingRequest} above: 404 while
     * DRAFT (draft privacy — indistinguishable from "no such id"), 403 once visible-but-not-owned.
     */
    @Test
    void aSecondSalesRep_cannotUploadListDownloadOrDeleteTheFirstReps_pricingRequestAttachments() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        PricingRequestAttachmentDto uploaded =
            pricingRequestService.uploadAttachment(id, sampleFile(), salesActor);

        assertThatThrownBy(() -> pricingRequestService.uploadAttachment(id, sampleFile(), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> pricingRequestService.listAttachments(id, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> pricingRequestService.attachmentFilePath(uploaded.id(), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> pricingRequestService.deleteAttachment(uploaded.id(), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        pricingRequestService.submit(id, salesActor);
        pricingRequestService.pickup(id, importActor);
        pricingRequestService.requestInformation(id,
            new RequestMoreInformationRequest("กรุณาระบุขนาดสินค้าเพิ่มเติม", null), importActor);
        assertThat(pricingRequestService.get(id, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.MORE_INFO_REQUIRED);

        assertThatThrownBy(() -> pricingRequestService.uploadAttachment(id, sampleFile(), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.listAttachments(id, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.attachmentFilePath(uploaded.id(), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.deleteAttachment(uploaded.id(), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // The owner is unaffected throughout.
        assertThat(pricingRequestService.listAttachments(id, salesActor))
            .extracting(PricingRequestAttachmentDto::id).contains(uploaded.id());
    }

    /**
     * MUTATION-CHECKABLE GUARD: {@code PricingRequestService.uploadAttachment}'s {@code
     * requireRole(actor, SALES_ROLES)}. Deliberately exercised on a MORE_INFO_REQUIRED (not
     * DRAFT) request, where Import is a normal VIEWER — so nothing else (draft privacy,
     * ownership — which is role="sales"-gated in requireViewable) would coincidentally block
     * Import here. If this role gate were ever removed, Import could upload directly to a
     * Pricing Request whose attachments are meant to be Sales-authored evidence.
     */
    @Test
    void importCannotUploadOrDeletePricingRequestAttachments_evenWhileMoreInfoRequired() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);
        pricingRequestService.pickup(id, importActor);
        pricingRequestService.requestInformation(id,
            new RequestMoreInformationRequest("ขอข้อมูลเพิ่มเติม", null), importActor);

        assertThatThrownBy(() -> pricingRequestService.uploadAttachment(id, sampleFile(), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        PricingRequestAttachmentDto uploaded =
            pricingRequestService.uploadAttachment(id, sampleFile(), salesActor);
        assertThatThrownBy(() -> pricingRequestService.deleteAttachment(uploaded.id(), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * MUTATION-CHECKABLE GUARD: {@code PricingRequestService.setAttachmentIncludeInFactoryEmail}'s
     * {@code requireRole(actor, IMPORT_ROLES)} — a brand-new method/gate this commit adds. Nothing
     * else in that method restricts by role (requireViewableAttachment only requires a VIEWER
     * role, which Sales also has), so this is the sole guard standing between a Sales actor and
     * marking their own attachment for inclusion in a factory email Import controls.
     */
    @Test
    void salesCannotToggleIncludeInFactoryEmail_onlyImportCan() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        PricingRequestAttachmentDto uploaded =
            pricingRequestService.uploadAttachment(id, sampleFile(), salesActor);
        pricingRequestService.submit(id, salesActor);
        pricingRequestService.pickup(id, importActor);

        assertThatThrownBy(() -> pricingRequestService.setAttachmentIncludeInFactoryEmail(
            uploaded.id(), new PricingRequestRequests.UpdatePricingRequestAttachmentRequest(true), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        PricingRequestAttachmentDto updated = pricingRequestService.setAttachmentIncludeInFactoryEmail(
            uploaded.id(), new PricingRequestRequests.UpdatePricingRequestAttachmentRequest(true), importActor);
        assertThat(updated.includeInFactoryEmail()).isTrue();
    }

    @Test
    void draftPricingRequest_isPrivateToOwnerAndOversightUntilSubmitted() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        assertThat(pricingRequestService.get(id, salesActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, salesActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);
        assertThat(pricingRequestService.list(null, null, false, salesActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);

        assertThat(pricingRequestService.get(id, ceoActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, ceoActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);
        assertThat(pricingRequestService.get(id, salesManagerActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, salesManagerActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);

        assertThatThrownBy(() -> pricingRequestService.get(id, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(pricingRequestService.list(null, null, false, secondSalesActor))
            .extracting(PricingRequestSummaryDto::id)
            .doesNotContain(id);
        assertThatThrownBy(() -> pricingRequestService.listForTicket(ticketId, secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> pricingRequestService.get(id, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(pricingRequestService.listForTicket(ticketId, importActor))
            .extracting(PricingRequestSummaryDto::id)
            .doesNotContain(id);
        assertThat(pricingRequestService.list(null, null, false, importActor))
            .extracting(PricingRequestSummaryDto::id)
            .doesNotContain(id);
    }

    @Test
    void submittedPricingRequest_handsOffToImportAndReflectsPickupToOwner() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();

        PricingRequestDetailDto submitted = pricingRequestService.submit(id, salesActor);
        assertThat(submitted.summary().status()).isEqualTo(PricingRequestStatus.SUBMITTED);

        assertThat(pricingRequestService.get(id, importActor).summary().id()).isEqualTo(id);
        assertThat(pricingRequestService.listForTicket(ticketId, importActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);
        assertThat(pricingRequestService.list("SUBMITTED", null, true, importActor))
            .extracting(PricingRequestSummaryDto::id)
            .contains(id);

        PricingRequestDetailDto pickedUp = pricingRequestService.pickup(id, importActor);
        assertThat(pickedUp.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(pickedUp.summary().assignedImportId()).isEqualTo(importUserId);
        assertThat(pricingRequestService.get(id, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
    }

    @Test
    void informationLoop_recordsBothTurnsAndAllowsDepartmentWideImport() {
        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);
        pricingRequestService.pickup(id, importActor);

        PricingRequestDetailDto infoRequested = pricingRequestService.requestInformation(id,
            new RequestMoreInformationRequest("กรุณาระบุขนาดสินค้าเพิ่มเติม", null), secondImportActor);
        assertThat(infoRequested.summary().status()).isEqualTo(PricingRequestStatus.MORE_INFO_REQUIRED);

        assertThatThrownBy(() -> pricingRequestService.respondInformation(id,
            new RespondMoreInformationRequest("ไม่ใช่เจ้าของดีล"), secondSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        PricingRequestDetailDto responded = pricingRequestService.respondInformation(id,
            new RespondMoreInformationRequest("ขนาด 60x60 ซม."), salesActor);
        assertThat(responded.summary().status()).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(responded.events()).extracting(event -> event.eventKind())
            .contains(PricingRequestEventKind.MORE_INFO_REQUESTED, PricingRequestEventKind.MORE_INFO_RESPONDED);
        assertThat(responded.events()).extracting(event -> event.message())
            .contains("กรุณาระบุขนาดสินค้าเพิ่มเติม", "ขนาด 60x60 ซม.");
    }

    @Test
    void accountingCanReadDealPaymentInfoButCannotReadPricingRequests() {
        TicketDto deal = ticketService.get(ticketId, accountActor);
        assertThat(deal.summary().id()).isEqualTo(ticketId);
        assertThat(ticketService.listPayments(ticketId, accountActor)).isEmpty();

        long id = pricingRequestService.createDraft(ticketId, designerCreateRequest(), salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);

        assertThatThrownBy(() -> pricingRequestService.get(id, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.listForTicket(ticketId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void sequentialCreateDraft_sameClientRequestId_returnsOneRequestWithOneItemSetAndOneCreatedEvent() {
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "sequential retry", "66666666-6666-6666-6666-666666666666",
            List.of(pricingItem("Toyota", "Hilux")));

        PricingRequestDetailDto first = pricingRequestService.createDraft(ticketId, request, salesActor);
        PricingRequestDetailDto replay = pricingRequestService.createDraft(ticketId, request, salesActor);

        assertThat(replay.summary().id()).isEqualTo(first.summary().id());
        assertOneRequestOneItemSetOneCreatedEvent(first.summary().id(), salesRepId, request.clientRequestId());
    }

    @Test
    void concurrentCreateDraft_sameClientRequestId_returnsOneRequestWithOneItemSetAndOneCreatedEvent() throws Exception {
        String clientRequestId = "55555555-5555-5555-5555-555555555555";
        var racingRequests = new RacingPricingRequestRepository(jdbc, salesRepId, clientRequestId);
        var racingService = new PricingRequestService(
            racingRequests, tickets, notifications, new ObjectMapper(), new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-pricing-flow-test-uploads"));
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "race request", clientRequestId,
            List.of(pricingItem("Toyota", "Hilux")));
        Callable<PricingRequestDetailDto> task = () -> racingService.createDraft(ticketId, request, salesActor);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(task);
            var second = executor.submit(task);

            PricingRequestDetailDto firstResult = first.get(5, TimeUnit.SECONDS);
            PricingRequestDetailDto secondResult = second.get(5, TimeUnit.SECONDS);

            long id = firstResult.summary().id();
            assertThat(secondResult.summary().id()).isEqualTo(id);
            assertOneRequestOneItemSetOneCreatedEvent(id, salesRepId, clientRequestId);
        } finally {
            executor.shutdownNow();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private CreatePricingRequestRequest designerCreateRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "note for designer", "33333333-3333-3333-3333-333333333333",
            List.of(pricingItem("Toyota", "Hilux")));
    }

    private CreatePricingRequestRequest buyerCreateRequest() {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(21),
            new BigDecimal("1200.00"), "THB", "note for buyer", "44444444-4444-4444-4444-444444444444",
            List.of(pricingItem("Honda", "Civic")));
    }

    private PricingRequestItemRequest pricingItem(String brand, String model) {
        return new PricingRequestItemRequest(null, catalogProductId, null, brand, model, brand + " " + model, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", UnitBasis.PER_PIECE, QuantityType.REFERENCE, null, null, null);
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "evidence.pdf", "application/pdf", "content".getBytes());
    }

    private TicketItemRequest item(String brand, String model) {
        return new TicketItemRequest(brand, model, "White", "Matte", "L", null,
            new BigDecimal("1"), null, null, null, null, null, null, "THB");
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

    private void assertOneRequestOneItemSetOneCreatedEvent(long id, long requestedBy, String clientRequestId) {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request
             WHERE requested_by = :requestedBy
               AND client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("requestedBy", requestedBy, "clientRequestId", clientRequestId), Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
            """, Map.of("id", id), Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = :eventKind
            """, Map.of("id", id, "eventKind", PricingRequestEventKind.PRICING_REQUEST_CREATED), Long.class))
            .isEqualTo(1L);
    }

    private static class RacingPricingRequestRepository extends PricingRequestRepository {
        private final long requestedBy;
        private final String clientRequestId;
        private final AtomicInteger emptyLookups = new AtomicInteger();
        private final CountDownLatch bothThreadsChecked = new CountDownLatch(2);

        RacingPricingRequestRepository(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                                       long requestedBy, String clientRequestId) {
            super(jdbc);
            this.requestedBy = requestedBy;
            this.clientRequestId = clientRequestId;
        }

        @Override
        public Optional<PricingRequestSummaryDto> findByClientRequestId(long requestedBy, String clientRequestId) {
            Optional<PricingRequestSummaryDto> existing = super.findByClientRequestId(requestedBy, clientRequestId);
            if (existing.isEmpty()
                    && requestedBy == this.requestedBy
                    && this.clientRequestId.equals(clientRequestId)
                    && emptyLookups.incrementAndGet() <= 2) {
                bothThreadsChecked.countDown();
                try {
                    if (!bothThreadsChecked.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for both createDraft calls to reach the race point");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for concurrent createDraft race", e);
                }
            }
            return existing;
        }
    }
}
