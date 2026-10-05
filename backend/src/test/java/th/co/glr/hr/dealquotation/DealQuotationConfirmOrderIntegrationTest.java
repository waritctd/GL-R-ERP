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
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
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
import th.co.glr.hr.ticket.DealActivityKind;
import th.co.glr.hr.ticket.DealActivityRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DirectQuotationLocks;
import th.co.glr.hr.ticket.EditItemsRequest;
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
 * Quotation ↔ deal linking, slice 1 (IA §7, owner decisions D1–D7 approved 2026-09-30) — real-DB,
 * real-service proof for "ยืนยันคำสั่งซื้อ" on a direct quotation
 * ({@link DealQuotationService#confirmOrderFromDirectQuotation}, {@code POST
 * /api/deal-quotations/{id}/confirm-order}; {@code …/promote-to-deal} is kept as an alias for one
 * release), and for the reversal of GLA-136's "hide the quotation-first deal" rule.
 *
 * <p>What changed from GLA-136 (#1083), and is pinned here:
 * <ul>
 *   <li>the {@code quotation_only} precondition is DROPPED — an ordinary deal with an APPROVED
 *       direct quotation confirms too; {@code quotation_only} (V193) is provenance only;</li>
 *   <li>a quotation-first deal is a real pipeline deal: it is listed, and stage / entry-channel /
 *       tender writes work on it;</li>
 *   <li>the items and pricing-request refusals are RE-KEYED from the flag to "this deal has a live
 *       DEAL_DIRECT quotation" ({@link DirectQuotationLocks}).</li>
 * </ul>
 *
 * <p>Every service is the production class, hand-wired against real Postgres (no Mockito anywhere):
 * the authz decision, the list SQL and the confirmation's writes are all exercised through the real
 * repositories. The confirmation itself is called through {@link #transactional} — a real
 * transactional AOP proxy — so the atomicity test below proves the method's own
 * {@code @Transactional} is what rolls a half-done confirmation back, not a template the test supplied.
 *
 * <p>Authz cases are written wrong-way-round per CLAUDE.md: every refusal asserts that NOTHING was
 * written (still {@code draft}, still at the lead stage, no items, no event).
 */
class DealQuotationConfirmOrderIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String V193 = "db/migration/V193__ticket_quotation_only.sql";

    private TicketRepository tickets;
    private TicketService ticketService;
    private DealQuotationService quotationService;
    private DealQuotationService confirmer; // quotationService behind a real @Transactional proxy
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
        confirmer = transactional(quotationService);

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
    // Happy path — owner confirms; the deal lands at S10 with the quotation's own lines, and the
    // ticket-keyed money side (deposit-notice draft) unlocks.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The quotation-first ("ghost") deal still confirms — the GLA-136 happy path, unchanged. */
    @Test
    void quotationFirstGhost_stillConfirms_landsAtOrderReceived_withQuotationLines_andDepositDraftUnlocks() {
        long ghost = quotationOnlyTicket(salesActor);
        assertThat(summary(ghost).quotationOnly()).isTrue();
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);

        PromoteToDealResultDto result = confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        assertThat(result.ticketId()).isEqualTo(ghost); // the SAME ticket — never a second one
        TicketSummaryDto after = summary(ghost);
        assertThat(after.quotationOnly()).as("best-effort provenance flip still happens").isFalse();
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

    /** The same deposit-notice draft is REFUSED before confirmation — the negative half of "the money
     * side unlocks", so the positive assertion above cannot be passing for an unrelated reason. */
    @Test
    void depositDraft_isRefused_untilTheOrderIsConfirmed() {
        long ghost = quotationOnlyTicket(salesActor);
        approvedDirectQuotation(ghost, salesActor);
        assertThatThrownBy(() -> depositNoticeService.createDraft(ghost,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The precondition GLA-136 had and this slice DROPS: a deal created the ordinary way (deal page,
     * {@code quotation_only = FALSE}, never a ghost) with an APPROVED direct quotation confirms the
     * order exactly like a quotation-first one — S10, CUSTOMER_CONFIRMED, the quotation's lines, one
     * event. Under GLA-136 this was a 409 "อยู่ใน pipeline อยู่แล้ว".
     */
    @Test
    void ordinaryDeal_withApprovedDirectQuotation_confirmsOrder_landsAtOrderReceived() {
        long deal = pipelineTicket(salesActor);
        assertThat(summary(deal).quotationOnly()).isFalse();
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);

        PromoteToDealResultDto result = confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        assertThat(result.ticketId()).isEqualTo(deal);
        TicketSummaryDto after = summary(deal);
        assertThat(after.quotationOnly()).as("provenance flag untouched on an ordinary deal").isFalse();
        assertThat(after.status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(after.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(after.paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(itemCount(deal)).as("the quotation's two goods lines").isEqualTo(2);
        assertThat(eventCount(deal, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
    }

    @Test
    void salesManagerPromotesARepsQuotation_confirmCustomerOwnerGateDoesNotBlockIt() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesManagerActor);

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

        confirmer.confirmOrderFromDirectQuotation(approved.id(), grantedQcActor);

        assertThat(summary(ghost).status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    @Test
    void replay_isIdempotent_returnsTheDeal_andWritesNothingMore() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        int eventsBefore = eventCount(ghost);
        int itemsBefore = itemCount(ghost);

        PromoteToDealResultDto replay = confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

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

        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertThatThrownBy(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertNothingPromoted(ghost, stageBefore);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — WRONG-WAY-ROUND. Each refusal asserts nothing was written.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwningSales_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), otherSalesActor));
        assertNothingPromoted(ghost, stageBefore);
    }

    @Test
    void importRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), importActor));
        assertNothingPromoted(ghost, stageBefore);
    }

    /** ceo APPROVES direct quotations but does not write them — promotion is the writer's action. */
    @Test
    void ceoRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), ceoActor));
        assertNothingPromoted(ghost, stageBefore);
    }

    @Test
    void accountRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), accountActor));
        assertNothingPromoted(ghost, stageBefore);
    }

    /** 403 wins over 409: a caller with no access must not learn the quotation's state either. */
    @Test
    void nonOwningSales_onADraftQuotation_getsTheSame403_notA409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto draft = quotationService.create(ghost, directDraft(), salesActor);
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(draft.id(), otherSalesActor));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Preconditions — 409, nothing written.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void draftDirectQuotation_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto draft = quotationService.create(ghost, directDraft(), salesActor);
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(draft.id(), salesActor), "อนุมัติแล้ว");
        assertNothingPromoted(ghost, stageBefore);
    }

    @Test
    void pendingApprovalDirectQuotation_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(ghost, directDraft(), salesActor).id(), salesActor);
        assertThat(pending.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(pending.id(), salesActor), "อนุมัติแล้ว");
        assertNothingPromoted(ghost, stageBefore);
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
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor), "ไม่ผ่านคำขอราคา");
        assertNothingPromoted(ghost, stageBefore);
    }

    /** Reached quotation_issued by ANOTHER path (the pricing-request confirmOrder bridge writes the
     * same status) — no confirmation event, so not a replay: 409 on the ticket-status precondition,
     * nothing overwritten. */
    @Test
    void ticketAlreadyPastDraftByAnotherPath_isRefused_409() {
        long pipeline = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(pipeline, salesActor);
        assertThat(tickets.markQuotationIssuedForOrderConfirmation(pipeline)).isEqualTo(1);
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor),
            "ยืนยันคำสั่งซื้อไม่ได้");
        assertThat(summary(pipeline).paymentStatus()).isNull();
        assertThat(itemCount(pipeline)).isZero();
        assertThat(eventCount(pipeline, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isZero();
    }

    @Test
    void onHoldQuotationOnlyTicket_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", Map.of("id", ghost));
        String stageBefore = summary(ghost).salesStage(); // M2: the quotation just created moved the deal
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor), "ACTIVE");
        assertNothingPromoted(ghost, stageBefore);
    }

    @Test
    void missingQuotation_is404() {
        assertThatThrownBy(() -> confirmer.confirmOrderFromDirectQuotation(999_999L, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // A quotation-first deal is a real pipeline deal (IA §7) — listed, and movable by hand.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Inverted from GLA-136's "excludes … until promoted": the quotation-first deal is listed (and
     * counted) from the moment it exists, for the owning rep and for the manager. */
    @Test
    void repsOwnList_includesTheQuotationFirstDeal() {
        long ghost = quotationOnlyTicket(salesActor);
        long pipeline = pipelineTicket(salesActor);
        approvedDirectQuotation(ghost, salesActor);

        assertThat(listIds(salesActor)).containsExactlyInAnyOrder(ghost, pipeline);
        assertThat(listIds(salesManagerActor)).contains(ghost, pipeline);
        assertThat(ticketService.listPage(null, salesActor, new th.co.glr.hr.common.PageRequest(0, 50)).total())
            .as("the COUNT agrees with the rows").isEqualTo(2);
        // Wrong-way-round: listing it does not widen who sees it — another rep still does not.
        assertThat(listIds(otherSalesActor)).doesNotContain(ghost, pipeline);
    }

    @Test
    void updateStage_onQuotationFirstDeal_nowSucceeds() {
        long ghost = quotationOnlyTicket(salesActor);
        ticketService.addActivity(ghost, new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null),
            salesActor);

        TicketDto moved = ticketService.updateStage(ghost, DealStage.PRESENTATION, null, salesActor);

        assertThat(moved.summary().salesStage()).isEqualTo(DealStage.PRESENTATION);
        assertThat(summary(ghost).salesStage()).isEqualTo(DealStage.PRESENTATION);
    }

    /** Wrong-way-round: removing the quotation-only refusal did not open the deal to a non-owner. */
    @Test
    void updateStage_onQuotationFirstDeal_byNonOwner_isStill403() {
        long ghost = quotationOnlyTicket(salesActor);
        assertForbidden(() -> ticketService.updateStage(ghost, DealStage.SPEC_APPROVED, "ทดสอบ", otherSalesActor));
        assertThat(summary(ghost).salesStage()).isEqualTo(DealStage.LEAD_APPROACH);
    }

    @Test
    void setEntryChannel_andTender_onQuotationFirstDeal_nowSucceed() {
        long ghost = quotationOnlyTicket(salesActor);
        TicketDto channel = ticketService.setEntryChannel(ghost, "OWNER_DIRECT", null, salesActor);
        assertThat(channel.summary().entryChannel()).isEqualTo("OWNER_DIRECT");
        TicketDto tender = ticketService.setTenderRequirement(ghost, "REQUIRED", salesActor);
        assertThat(tender.summary().tenderRequirement()).isEqualTo("REQUIRED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Items / pricing-request refusals are keyed on "a LIVE DEAL_DIRECT quotation exists" —
    // not on the quotation_only flag. Written on ORDINARY deals so the flag cannot be the cause.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void editItems_isRefused_whileALiveDirectQuotationExists_andAllowedOnceCancelled() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto draft = quotationService.create(deal, directDraft(), salesActor);

        assertConflict(() -> ticketService.editItems(deal,
            new EditItemsRequest(List.of(ticketItem()), null), salesActor), DirectQuotationLocks.REFUSAL_MESSAGE);
        assertThat(itemCount(deal)).isZero();

        // Once the only direct quotation is CANCELLED the deal's own lines are editable again.
        quotationService.cancel(draft.id(), new CancelRequest(null), salesActor);
        ticketService.editItems(deal, new EditItemsRequest(List.of(ticketItem()), null), salesActor);
        assertThat(itemCount(deal)).isEqualTo(1);
    }

    /** PENDING_APPROVAL and APPROVED are live as well (the latter is the one the order would be
     * confirmed from). */
    @Test
    void editItems_isRefused_whileAPendingOrApprovedDirectQuotationExists() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(deal, directDraft(), salesActor).id(), salesActor);
        assertConflict(() -> ticketService.editItems(deal,
            new EditItemsRequest(List.of(ticketItem()), null), salesActor), DirectQuotationLocks.REFUSAL_MESSAGE);

        quotationService.approve(pending.id(), new ApproveRequest(null), salesManagerActor);
        assertConflict(() -> ticketService.editItems(deal,
            new EditItemsRequest(List.of(ticketItem()), null), salesActor), DirectQuotationLocks.REFUSAL_MESSAGE);
        assertThat(itemCount(deal)).isZero();
    }

    /** A quotation-first deal with NO live direct quotation is not locked — the flag is not the key. */
    @Test
    void editItems_onQuotationFirstDeal_withoutALiveDirectQuotation_isAllowed() {
        long ghost = quotationOnlyTicket(salesActor);
        ticketService.editItems(ghost, new EditItemsRequest(List.of(ticketItem()), null), salesActor);
        assertThat(itemCount(ghost)).isEqualTo(1);
    }

    /** Wrong-way-round ordering: a non-owner gets the 403, never the 409 that would reveal a quotation. */
    @Test
    void editItems_byNonOwner_isStill403_evenWithALiveDirectQuotation() {
        long deal = pipelineTicket(salesActor);
        quotationService.create(deal, directDraft(), salesActor);
        assertForbidden(() -> ticketService.editItems(deal,
            new EditItemsRequest(List.of(ticketItem()), null), otherSalesActor));
    }

    @Test
    void createPricingRequest_isRefused_whileALiveDirectQuotationExists() {
        long deal = pipelineTicket(salesActor);
        quotationService.create(deal, directDraft(), salesActor);
        assertConflict(() -> pricingRequestService.createDraft(deal,
            new CreatePricingRequestRequest("DESIGNER", null, "ผู้ออกแบบ", null, null, "THB", null,
                java.util.UUID.randomUUID().toString(), List.of()), salesActor),
            DirectQuotationLocks.REFUSAL_MESSAGE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.pricing_request WHERE ticket_id = :id",
            Map.of("id", deal), Integer.class)).isZero();
    }

    /** The refusal message says what to do, not only what is wrong (Thai, 409). */
    @Test
    void refusalMessage_tellsTheRepWhatToDo() {
        assertThat(DirectQuotationLocks.REFUSAL_MESSAGE)
            .contains("ใบเสนอราคาตรงที่ยังใช้งานอยู่").contains("แก้ไขรายการที่ใบเสนอราคา").contains("ยกเลิกใบเสนอราคา");
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

        // Creating a direct quotation now moves the deal to its recipient's stage (owner rule of
        // 2026-10-05, M2). These rows model PRE-V193 data, whose stage was still LEAD_APPROACH, so they are
        // re-pinned there — otherwise each would be excluded by the backfill's stage clause, which the
        // quotation just changed, and no longer by the clause (pricing request / legacy origin / status)
        // this test exists to prove. advancedStage keeps the stage it was explicitly given above.
        for (long row : new long[] {ghost, withPricingRequest, mixedOrigin, advancedStatus}) {
            jdbc.update("UPDATE sales.ticket SET sales_stage = 'LEAD_APPROACH' WHERE ticket_id = :id",
                Map.of("id", row));
        }

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
            0, 30, null, "NET", "TH", "THB", List.of(tile, plain, adjustment)).withRecipientType("OWNER");
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Typed", "White", "Matte", "60x60", null,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    /**
     * The refused promotion wrote nothing. {@code stageBefore} is the stage read just before the attempt:
     * it used to be the literal LEAD_APPROACH, but creating the direct quotation these tests start from
     * now moves the deal to its recipient's stage (owner rule of 2026-10-05, M2), so "unmoved" has to be
     * measured against where the deal stood when the promotion was attempted, not against where it began.
     */
    private void assertNothingPromoted(long ticketId, String stageBefore) {
        TicketSummaryDto s = summary(ticketId);
        assertThat(s.quotationOnly()).as("still quotation-only").isTrue();
        assertThat(s.status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(s.salesStage()).as("stage unchanged by the refused promotion").isEqualTo(stageBefore);
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
