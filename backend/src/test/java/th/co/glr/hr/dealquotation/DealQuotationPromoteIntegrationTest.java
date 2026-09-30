package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.PromoteToDealResultDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceRenderer;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EditItemsRequest;
import th.co.glr.hr.ticket.QuotationOnlyTickets;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * GLA-136 (owner ruling, Ploy 2026-09-30) — real-DB, real-service proof for "สร้างดีลจากใบเสนอราคา"
 * ({@link DealQuotationService#promoteToDeal}) and the quotation-only container ticket it promotes
 * (V193 {@code sales.ticket.quotation_only}).
 *
 * <p>Every service is the production class, hand-wired against real Postgres (no Mockito anywhere):
 * the authz decision, the list-exclusion SQL and the promotion's writes are all exercised through
 * the real repositories. The promotion itself is called through {@link #transactional} — a real
 * transactional AOP proxy — so the atomicity test below proves the method's own
 * {@code @Transactional} is what rolls a half-done promotion back, not a template the test supplied.
 *
 * <p>Authz cases are written wrong-way-round per CLAUDE.md: every refusal asserts that NOTHING was
 * written (the ticket is still quotation-only, still {@code draft}, still has no event).
 */
class DealQuotationPromoteIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String V193 = "db/migration/V193__ticket_quotation_only.sql";

    private TicketRepository tickets;
    private TicketService ticketService;
    private DealQuotationService quotationService;
    private DealQuotationService promoter; // quotationService behind a real @Transactional proxy
    private PricingRequestService pricingRequestService;
    private DepositNoticeService depositNoticeService;
    private CustomerRepository customers;
    private EmployeeRepository employees;

    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal importActor;
    private UserPrincipal accountActor;
    private UserPrincipal grantedQcActor;

    private CustomerDto customer;
    private long projectId;

    @BeforeEach
    void wireServices() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), new FileStorageService("/tmp/glr-gla136-promote-test-uploads"),
            factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, customers, new QuotationRenderer(),
            pricingRequestService, new EmployeeAuthRepository(jdbc));

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
        CustomerQuotationRepository legacyQuotations = new CustomerQuotationRepository(jdbc);
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)),
            tickets, customers, new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, new PricingDecisionRepository(jdbc),
            legacyQuotations);
        quotationService.wireTicketService(ticketService);
        promoter = transactional(quotationService);

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, legacyQuotations);

        salesActor = actor(createEmployee("พนักงานขาย 136", "sales-136@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        otherSalesActor = actor(createEmployee("พนักงานขาย 136 อื่น", "sales-136-other@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
        salesManagerActor = actor(createEmployee("ผจก.ขาย 136", "sm-136@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");
        ceoActor = actor(createEmployee("ผู้บริหาร 136", "ceo-136@glr.co.th", "MD", "ผู้บริหาร",
            "กรรมการผู้จัดการ"), "ceo");
        importActor = actor(createEmployee("นำเข้า 136", "import-136@glr.co.th", "PCIM", "ฝ่ายนำเข้า", null), "import");
        accountActor = actor(createEmployee("บัญชี 136", "account-136@glr.co.th", "ACCT", "ฝ่ายบัญชี", null), "account");
        long qcId = createEmployee("QC 136", "qc-136@glr.co.th", "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qcId));
        grantedQcActor = actor(qcId, "qc");

        customer = customers.create("บริษัท ใบเสนอราคาตรง จำกัด", "0100000000136", "136 ถนนทดสอบ", "สนญ.", "02-000-0136");
        ProjectDto project = projects.create(customer.id(), "โครงการใบเสนอราคาตรง 136");
        projectId = project.id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Happy path — owner promotes; the deal lands at S10 with the quotation's own lines, and the
    // ticket-keyed money side (deposit-notice draft) unlocks.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ownerPromotes_approvedDirectQuotation_landsAtOrderReceived_withQuotationLines_andDepositDraftUnlocks() {
        long ghost = quotationOnlyTicket(salesActor);
        assertThat(summary(ghost).quotationOnly()).isTrue();
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);

        PromoteToDealResultDto result = promoter.promoteToDeal(approved.id(), salesActor);

        assertThat(result.ticketId()).isEqualTo(ghost); // the SAME ticket — never a second one
        TicketSummaryDto after = summary(ghost);
        assertThat(after.quotationOnly()).isFalse();
        assertThat(after.status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(after.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(after.paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(result.ticket().quotationOnly()).isFalse();
        assertThat(result.ticket().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(result.quotation().docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket", Map.of(), Integer.class))
            .as("promotion never mints a ticket").isEqualTo(1);

        // ticket_item == the quotation's GOODS lines (TILE + PLAIN), never the ADJUSTMENT row.
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT brand, model, color, texture, size, qty, qty_sqm, unit_basis, raw_unit,
                   proposed_price, approved_price, raw_price
              FROM sales.ticket_item WHERE ticket_id = :id ORDER BY sort_order
            """, Map.of("id", ghost));
        assertThat(items).hasSize(2);
        Map<String, Object> tile = items.get(0);
        assertThat(tile.get("brand")).isEqualTo("SCG");
        assertThat(tile.get("model")).isEqualTo("Direct Tile");
        assertThat(tile.get("color")).isEqualTo("White");
        assertThat(tile.get("texture")).isEqualTo("Matte");
        assertThat(tile.get("size")).isEqualTo("60x60");
        // 10 pieces, NONE wastage, roundToFullBox=false -> the printed quantity is 10 pieces.
        BigDecimal tileQty = approved.items().stream()
            .filter(i -> WastageCalculator.LINE_TYPE_TILE.equals(i.lineType())).findFirst().orElseThrow().quantity();
        assertThat((BigDecimal) tile.get("qty")).isEqualByComparingTo(tileQty).isEqualByComparingTo("10");
        assertThat((BigDecimal) tile.get("qty_sqm")).isEqualByComparingTo("3.60"); // 10 x 0.36
        assertThat(tile.get("unit_basis")).isEqualTo("PIECE");
        assertThat(tile.get("raw_unit")).isEqualTo("แผ่น");
        Map<String, Object> plain = items.get(1);
        assertThat(plain.get("brand")).isEqualTo("ค่าขนส่ง");
        assertThat((BigDecimal) plain.get("qty")).isEqualByComparingTo("2");
        assertThat(plain.get("qty_sqm")).isNull();
        assertThat(plain.get("raw_unit")).isEqualTo("JOB");
        for (Map<String, Object> row : items) {
            assertThat(row.get("proposed_price")).as("no pricing field is copied").isNull();
            assertThat(row.get("approved_price")).isNull();
            assertThat(row.get("raw_price")).isNull();
        }

        // Exactly one promotion event, draft -> quotation_issued, pointing at THIS quotation.
        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT from_status, to_status, related_document_type, related_document_id
              FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind
            """, Map.of("id", ghost, "kind", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("from_status")).isEqualTo(TicketStatus.DRAFT);
        assertThat(events.get(0).get("to_status")).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(events.get(0).get("related_document_type")).isEqualTo("QUOTATION");
        assertThat(((Number) events.get(0).get("related_document_id")).longValue()).isEqualTo(approved.id());

        // The money side unlocks: the ticket-level deposit-notice draft (keyed on quotation_issued)
        // now succeeds, autofilled from the DEAL_DIRECT APPROVED quotation (#1069).
        DepositNoticeDto draft = depositNoticeService.createDraft(ghost,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        assertThat(draft.ticketId()).isEqualTo(ghost);
        assertThat(draft.items()).isNotEmpty();
    }

    /** The same deposit-notice draft is REFUSED before promotion — the negative half of "the money
     * side unlocks", so the positive assertion above cannot be passing for an unrelated reason. */
    @Test
    void depositDraft_isRefused_whileTheTicketIsStillQuotationOnly() {
        long ghost = quotationOnlyTicket(salesActor);
        approvedDirectQuotation(ghost, salesActor);
        assertThatThrownBy(() -> depositNoticeService.createDraft(ghost,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void salesManagerPromotesARepsQuotation_confirmCustomerOwnerGateDoesNotBlockIt() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);

        promoter.promoteToDeal(approved.id(), salesManagerActor);

        TicketSummaryDto after = summary(ghost);
        assertThat(after.quotationOnly()).isFalse();
        assertThat(after.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(after.paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(after.createdById()).as("ownership never moves to the promoter").isEqualTo(salesActor.id());
    }

    @Test
    void quotationGrantHolderPromotes_onAnyDeal() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);

        promoter.promoteToDeal(approved.id(), grantedQcActor);

        assertThat(summary(ghost).status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    @Test
    void replay_isIdempotent_returnsTheDeal_andWritesNothingMore() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        promoter.promoteToDeal(approved.id(), salesActor);
        int eventsBefore = eventCount(ghost);
        int itemsBefore = itemCount(ghost);

        PromoteToDealResultDto replay = promoter.promoteToDeal(approved.id(), salesActor);

        assertThat(replay.ticketId()).isEqualTo(ghost);
        assertThat(replay.ticket().status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(eventCount(ghost)).isEqualTo(eventsBefore);
        assertThat(itemCount(ghost)).isEqualTo(itemsBefore);
    }

    /**
     * Atomicity, through the real {@code @Transactional} proxy: the LAST step (confirmCustomer's
     * payment-track guard) fails AFTER quotation_only/status/items/event have all been written. The
     * whole promotion must roll back — a half-promoted ticket (out of the quotation-only state but
     * without CUSTOMER_CONFIRMED) would be stranded: invisible to promotion, visible in the pipeline.
     */
    @Test
    void failureInTheLastStep_rollsTheWholePromotionBack() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        // A payment status past CUSTOMER_CONFIRMED makes applyCustomerConfirmation refuse (409).
        jdbc.update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_PAID' WHERE ticket_id = :id", Map.of("id", ghost));

        assertThatThrownBy(() -> promoter.promoteToDeal(approved.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertNothingPromoted(ghost);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — WRONG-WAY-ROUND. Each refusal asserts nothing was written.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwningSales_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> promoter.promoteToDeal(approved.id(), otherSalesActor));
        assertNothingPromoted(ghost);
    }

    @Test
    void importRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> promoter.promoteToDeal(approved.id(), importActor));
        assertNothingPromoted(ghost);
    }

    /** ceo APPROVES direct quotations but does not write them — promotion is the writer's action. */
    @Test
    void ceoRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> promoter.promoteToDeal(approved.id(), ceoActor));
        assertNothingPromoted(ghost);
    }

    @Test
    void accountRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> promoter.promoteToDeal(approved.id(), accountActor));
        assertNothingPromoted(ghost);
    }

    /** 403 wins over 409: a caller with no access must not learn the quotation's state either. */
    @Test
    void nonOwningSales_onADraftQuotation_getsTheSame403_notA409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto draft = quotationService.create(ghost, directDraft(), salesActor);
        assertForbidden(() -> promoter.promoteToDeal(draft.id(), otherSalesActor));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Preconditions — 409, nothing written.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void draftDirectQuotation_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto draft = quotationService.create(ghost, directDraft(), salesActor);
        assertConflict(() -> promoter.promoteToDeal(draft.id(), salesActor), "อนุมัติแล้ว");
        assertNothingPromoted(ghost);
    }

    @Test
    void pendingApprovalDirectQuotation_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(ghost, directDraft(), salesActor).id(), salesActor);
        assertThat(pending.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertConflict(() -> promoter.promoteToDeal(pending.id(), salesActor), "อนุมัติแล้ว");
        assertNothingPromoted(ghost);
    }

    /** R10 / separation: a PRICING_REQUEST-origin quotation is never promoted by this route — it has
     * its own confirmOrder. Its row is produced here by re-tagging an approved direct one (the
     * origin column is the only thing the guard reads), linked to a real pricing_request row. */
    @Test
    void pricingRequestOriginQuotation_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        long pricingRequestId = insertPricingRequest(ghost, salesActor.id());
        jdbc.update("""
            UPDATE sales.quotation SET origin = 'PRICING_REQUEST', pricing_request_id = :pr
             WHERE quotation_id = :id
            """, Map.of("pr", pricingRequestId, "id", approved.id()));
        assertConflict(() -> promoter.promoteToDeal(approved.id(), salesActor), "ไม่ผ่านคำขอราคา");
        assertNothingPromoted(ghost);
    }

    /** A ticket that was always a pipeline deal (created without the flag, e.g. from the deal page)
     * and was never promoted: 409, never a silent "success". */
    @Test
    void ordinaryPipelineDeal_isRefused_409_notTreatedAsAReplay() {
        long pipeline = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(pipeline, salesActor);
        assertConflict(() -> promoter.promoteToDeal(approved.id(), salesActor), "อยู่ใน pipeline อยู่แล้ว");
        assertThat(summary(pipeline).status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(eventCount(pipeline, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isZero();
    }

    /** Reached quotation_issued by ANOTHER path (the pricing-request confirmOrder bridge writes the
     * same status) — not quotation-only, no promotion event: 409, nothing overwritten. */
    @Test
    void ticketAlreadyInThePipelineByAnotherPath_isRefused_409() {
        long pipeline = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(pipeline, salesActor);
        assertThat(tickets.markQuotationIssuedForOrderConfirmation(pipeline)).isEqualTo(1);
        assertConflict(() -> promoter.promoteToDeal(approved.id(), salesActor), "อยู่ใน pipeline อยู่แล้ว");
        assertThat(summary(pipeline).paymentStatus()).isNull();
        assertThat(itemCount(pipeline)).isZero();
    }

    @Test
    void onHoldQuotationOnlyTicket_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", Map.of("id", ghost));
        assertConflict(() -> promoter.promoteToDeal(approved.id(), salesActor), "ACTIVE");
        assertNothingPromoted(ghost);
    }

    @Test
    void missingQuotation_is404() {
        assertThatThrownBy(() -> promoter.promoteToDeal(999_999L, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Pipeline reads exclude a quotation-only ticket; single reads still serve it.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void repsOwnList_excludesTheQuotationOnlyTicket_untilItIsPromoted() {
        long ghost = quotationOnlyTicket(salesActor);
        long pipeline = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);

        assertThat(listIds(salesActor)).containsExactly(pipeline);
        assertThat(listIds(salesManagerActor)).doesNotContain(ghost).contains(pipeline);
        assertThat(ticketService.listPage(null, salesActor, new th.co.glr.hr.common.PageRequest(0, 50)).total())
            .as("the COUNT agrees with the rows").isEqualTo(1);
        // get() still serves it — the quotation editor reads this summary.
        assertThat(ticketService.get(ghost, salesActor).summary().quotationOnly()).isTrue();

        promoter.promoteToDeal(approved.id(), salesActor);

        assertThat(listIds(salesActor)).containsExactlyInAnyOrder(ghost, pipeline);
        assertThat(ticketService.listPage(null, salesActor, new th.co.glr.hr.common.PageRequest(0, 50)).total())
            .isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Manual pipeline writes refuse a quotation-only ticket (409); quotation CRUD does not.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void updateStage_onQuotationOnlyTicket_is409_andTheStageDoesNotMove() {
        long ghost = quotationOnlyTicket(salesActor);
        assertConflict(() -> ticketService.updateStage(ghost, DealStage.SPEC_APPROVED, "ทดสอบ", salesActor),
            QuotationOnlyTickets.REFUSAL_MESSAGE);
        assertThat(summary(ghost).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
    }

    /** Wrong-way-round ordering: a caller with no write access still gets 403, never the 409 that
     * would confirm what kind of ticket this is. */
    @Test
    void updateStage_onQuotationOnlyTicket_byNonOwner_isStill403() {
        long ghost = quotationOnlyTicket(salesActor);
        assertForbidden(() -> ticketService.updateStage(ghost, DealStage.SPEC_APPROVED, "ทดสอบ", otherSalesActor));
    }

    @Test
    void editItems_onQuotationOnlyTicket_is409() {
        long ghost = quotationOnlyTicket(salesActor);
        assertConflict(() -> ticketService.editItems(ghost,
            new EditItemsRequest(List.of(ticketItem()), null), salesActor), QuotationOnlyTickets.REFUSAL_MESSAGE);
        assertThat(itemCount(ghost)).isZero();
    }

    @Test
    void setEntryChannel_andTender_onQuotationOnlyTicket_are409() {
        long ghost = quotationOnlyTicket(salesActor);
        assertConflict(() -> ticketService.setEntryChannel(ghost, "OWNER_DIRECT", null, salesActor),
            QuotationOnlyTickets.REFUSAL_MESSAGE);
        assertConflict(() -> ticketService.setTenderRequirement(ghost, "REQUIRED", salesActor),
            QuotationOnlyTickets.REFUSAL_MESSAGE);
    }

    @Test
    void createPricingRequest_onQuotationOnlyTicket_is409_andNoPricingRequestExists() {
        long ghost = quotationOnlyTicket(salesActor);
        assertConflict(() -> pricingRequestService.createDraft(ghost,
            new CreatePricingRequestRequest("DESIGNER", null, "ผู้ออกแบบ", null, null, "THB", null,
                java.util.UUID.randomUUID().toString(), List.of()), salesActor),
            QuotationOnlyTickets.REFUSAL_MESSAGE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.pricing_request WHERE ticket_id = :id",
            Map.of("id", ghost), Integer.class)).isZero();
    }

    /** The pipeline gates must not reach an ordinary deal: the same moves still work there. */
    @Test
    void pipelineDeal_isUnaffectedByTheGate() {
        long pipeline = pipelineTicket(salesActor);
        TicketDto moved = ticketService.setEntryChannel(pipeline, "OWNER_DIRECT", null, salesActor);
        assertThat(moved.summary().entryChannel()).isEqualTo("OWNER_DIRECT");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // V193 backfill — V193 re-executed VERBATIM from the classpath over hand-shaped rows.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void v193Backfill_marksOnlyUntouchedDirectOnlyDraftTickets() throws Exception {
        long ghost = pipelineTicket(salesActor);                 // direct-only, draft, LEAD_APPROACH
        quotationService.create(ghost, directDraft(), salesActor);
        long withPricingRequest = pipelineTicket(salesActor);    // + a pricing_request row
        quotationService.create(withPricingRequest, directDraft(), salesActor);
        insertPricingRequest(withPricingRequest, salesActor.id());
        long mixedOrigin = pipelineTicket(salesActor);           // + a legacy (origin NULL) quotation
        quotationService.create(mixedOrigin, directDraft(), salesActor);
        insertLegacyQuotation(mixedOrigin, salesActor.id());
        long advancedStage = pipelineTicket(salesActor);         // stage moved on
        quotationService.create(advancedStage, directDraft(), salesActor);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'QUOTE_BUYER' WHERE ticket_id = :id",
            Map.of("id", advancedStage));
        long advancedStatus = pipelineTicket(salesActor);        // status moved on
        quotationService.create(advancedStatus, directDraft(), salesActor);
        tickets.markQuotationIssuedForOrderConfirmation(advancedStatus);
        long noQuotation = pipelineTicket(salesActor);           // no quotation at all

        runV193();
        runV193(); // re-runnable

        assertThat(summary(ghost).quotationOnly()).isTrue();
        assertThat(summary(withPricingRequest).quotationOnly()).isFalse();
        assertThat(summary(mixedOrigin).quotationOnly()).isFalse();
        assertThat(summary(advancedStage).quotationOnly()).isFalse();
        assertThat(summary(advancedStatus).quotationOnly()).isFalse();
        assertThat(summary(noQuotation).quotationOnly()).isFalse();
    }

    /** chk_event_kind as re-declared by V193 still accepts every kind V186 added, plus the new one. */
    @Test
    void v193_chkEventKind_keepsEarlierKinds_andAddsThePromotionKind() {
        long ticket = pipelineTicket(salesActor);
        for (String kind : List.of(TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION, TicketEventKind.DEAL_QUOTATION_REORDERED,
                TicketEventKind.DEAL_QUOTATION_SUPERSEDED, TicketEventKind.IMPORT_STEP_ADVANCED,
                TicketEventKind.IMPORT_REQUEST_EMAIL_SENT, TicketEventKind.ORDER_CONFIRMED_FROM_QUOTATION)) {
            tickets.addEvent(ticket, salesActor.id(), "t", kind, null, null, null);
        }
        assertThat(eventCount(ticket, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Exactly what the quotation editor's inline create sends (QuotationEditorPage
     * #handleInlineCreate): no contact, no items, a follow-up date, and quotationOnly=true. */
    private long quotationOnlyTicket(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest(customer.name(), "NORMAL", customer.name(), customer.id(),
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7), true), creator).summary().id();
    }

    private long pipelineTicket(UserPrincipal creator) {
        return ticketService.create(new CreateTicketRequest("ดีล pipeline", "NORMAL", customer.name(), customer.id(),
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7)), creator).summary().id();
    }

    private DealQuotationDto approvedDirectQuotation(long ticketId, UserPrincipal creator) {
        DealQuotationDto created = quotationService.create(ticketId, directDraft(), creator);
        DealQuotationDto submitted = quotationService.submit(created.id(), creator);
        DealQuotationDto approved = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    /** One TILE line (10 pcs, loose — roundToFullBox=false), one PLAIN line (2 JOB), one ADJUSTMENT (flat -100). */
    private UpsertDealQuotationRequest directDraft() {
        ItemInput tile = new ItemInput(
            null, null, null, "SCG", "Direct Tile", "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false);
        ItemInput plain = new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, new BigDecimal("500.00"), BigDecimal.ZERO, null,
            null, null, null, WastageCalculator.LINE_TYPE_PLAIN, "ค่าขนส่ง", new BigDecimal("2"), "JOB", null, null,
            null, null, null, null, null, null);
        ItemInput adjustment = new ItemInput(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, WastageCalculator.LINE_TYPE_ADJUSTMENT, "ส่วนลดพิเศษ", null, null, null, null,
            null, null, new BigDecimal("100.00"), null, null, null);
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(tile, plain, adjustment));
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Typed", "White", "Matte", "60x60", null,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private void assertNothingPromoted(long ticketId) {
        TicketSummaryDto s = summary(ticketId);
        assertThat(s.quotationOnly()).as("still quotation-only").isTrue();
        assertThat(s.status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(s.salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(itemCount(ticketId)).isZero();
        assertThat(eventCount(ticketId, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isZero();
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains(messagePart);
            });
    }

    private TicketSummaryDto summary(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary();
    }

    private List<Long> listIds(UserPrincipal actor) {
        return ticketService.list(null, actor).stream().map(TicketSummaryDto::id).toList();
    }

    private int itemCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    private int eventCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    private int eventCount(long ticketId, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            Map.of("id", ticketId, "kind", kind), Integer.class);
    }

    private long insertPricingRequest(long ticketId, long requestedBy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, requested_by)
            VALUES (:code, :ticketId, 'DESIGNER', :by)
            RETURNING pricing_request_id
            """, new MapSqlParameterSource()
                .addValue("code", "PCR-136-" + ticketId)
                .addValue("ticketId", ticketId)
                .addValue("by", requestedBy), Long.class);
    }

    private void insertLegacyQuotation(long ticketId, long issuedBy) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, quotation_version)
            VALUES (:ticketId, :number, :by, NULL, 'ISSUED', 99)
            """, new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QN-LEGACY-136-" + ticketId)
                .addValue("by", issuedBy));
    }

    /** V193 AS SHIPPED — the whole file, verbatim, from the classpath. The ghost fixture starts at
     * quotation_only = FALSE (it was created without the flag), so only the backfill UPDATE can turn
     * it TRUE; if that statement is removed or its predicate loosened/tightened, the test goes red. */
    private void runV193() throws Exception {
        String text;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(V193)) {
            assertThat(in).as(V193 + " on the classpath").isNotNull();
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.getJdbcOperations().execute(text);
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh,
                                String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
