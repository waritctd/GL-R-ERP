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
import th.co.glr.hr.deposit.DepositNoticeItemDto;
import th.co.glr.hr.deposit.DepositNoticeItemRequest;
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
import th.co.glr.hr.ticket.TicketResponses;
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
            pricingRequestService, new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

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
        // Owner ruling 2026-10-23: the ส่วนลดพิเศษ ADJUSTMENT row (qty -1, -100.00) is folded into the
        // goods lines by amount: TILE 10 x 100 and PLAIN 2 x 500 take 50.00 each. No negative qty, the
        // descriptions are the goods lines' own, and the subtotal is the quotation's 1,900.
        assertThat(draft.items())
            .extracting(i -> i.qty().stripTrailingZeros().toPlainString(), i -> i.unitPrice().toPlainString(),
                i -> i.netUnitPrice().toPlainString(), DepositNoticeItemDto::discountLabel)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("10", "100.00", "95.00", "ส่วนลดพิเศษ 5 ต่อหน่วย"),
                org.assertj.core.groups.Tuple.tuple("2", "500.00", "475.00", "ส่วนลดพิเศษ 25 ต่อหน่วย"));
        assertThat(draft.items()).extracting(DepositNoticeItemDto::description).doesNotContain("ส่วนลดพิเศษ")
            .last().isEqualTo("ค่าขนส่ง");
        assertThat(draft.subtotal()).isEqualByComparingTo("1900.00");

        // The page sends every line back on save; @PositiveOrZero qty used to refuse the whole PUT.
        DepositNoticeDraftRequest pagePut = new DepositNoticeDraftRequest(
            draft.customerName(), draft.customerTaxId(), draft.customerAddress(), draft.projectName(),
            draft.reference(), draft.depositPercent(), draft.notes(),
            draft.items().stream().map(i -> new DepositNoticeItemRequest(i.seq(), i.description(), i.qty(),
                i.unit(), i.unitPrice(), i.discountLabel(), i.netUnitPrice())).toList(),
            draft.updatedAt());
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(pagePut)).isEmpty();
        }
        assertThat(depositNoticeService.update(draft.id(), pagePut, salesActor).subtotal()).isEqualByComparingTo("1900.00");
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

        assertThatThrownBy(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertNothingPromoted(ghost);
    }

    /** Owner ruling 2026-10-22: a ไม่รับมัดจำ (0%) direct quotation confirms onto the no-deposit
     * payment path, so final payment is no longer blocked by a deposit that will never exist. */
    @Test
    void zeroDepositDirectQuotation_confirmsOntoTheNoDepositPath_andFinalPaymentIsNoLongerBlocked() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto created = quotationService.create(deal, zeroDepositDirectDraft(), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.depositPercent()).isZero();

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        TicketSummaryDto after = summary(deal);
        assertThat(after.depositPolicy()).isEqualTo("NOT_REQUIRED");
        assertThat(after.depositPolicyReason()).contains(approved.number()).contains("ไม่รับมัดจำ");
        assertThat(eventCount(deal, TicketEventKind.POLICY_CHANGED)).isEqualTo(1);
        assertThat(after.paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertConflict(() -> depositNoticeService.createDraft(deal,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor), "ไม่ต้องเก็บมัดจำ");
        // The whole 0% walk (owner ruling 2026-10-27: 0% skips the deposit path automatically).
        assertThat(ticketService.actions(deal, salesActor).availableActions())
            .extracting(TicketResponses.TicketActionDto::action).doesNotContain("ISSUE_DEPOSIT_NOTICE");
        ticketService.requireImportRequestIssuable(after); // a REQUIRED deal 409s here with no deposit
        // canConfirmFinalPaymentNow refuses a REQUIRED deal at CUSTOMER_CONFIRMED.
        assertThat(ticketService.confirmFinalPayment(deal, accountActor).summary().paymentStatus())
            .isEqualTo("FULLY_PAID");
        assertThat(summary(deal).salesStage()).isNotEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id"
            + " AND (to_status = 'DEPOSIT_RECEIVED' OR from_status = 'DEPOSIT_RECEIVED')",
            Map.of("id", deal), Integer.class)).isZero();
    }

    /**
     * Owner ruling 2026-10-28: once the order is confirmed from a direct quotation, THAT quotation can no longer
     * be revised (a revision's approval would supersede the agreed order and move the payable). Replaces the five
     * #1173 tests that revised a confirmed quotation (their policy-sync scenarios are no longer reachable).
     */
    @Test
    void confirmedDirectQuotation_cannotBeRevised_409_andNoRowIsWritten() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto fifty = approvedDirectQuotation(deal, salesActor);
        confirmer.confirmOrderFromDirectQuotation(fifty.id(), salesActor);
        int rowsBefore = quotationRows(deal);

        assertConflict(() -> quotationService.createRevision(fifty.id(), salesActor), "ยืนยันคำสั่งซื้อแล้ว");

        assertThat(quotationRows(deal)).isEqualTo(rowsBefore);
        assertThat(quotationService.get(fifty.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
    }

    /**
     * Round 3: the two leaks around the revise gate. After the order is confirmed from v1, no OTHER direct
     * quotation on the deal may be created from it (clone), submitted, or approved -- approving one would supersede
     * the confirmed v1, move the payable and flip the deposit policy. v1 stays APPROVED, nothing is written.
     */
    @Test
    void afterConfirm_aCloneOfTheConfirmedQuotation_isRefused() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);
        int rows = quotationRows(deal);

        assertConflict(() -> quotationService.createReorder(v1.id(), salesActor), "ยืนยันคำสั่งซื้อแล้ว");

        assertThat(quotationRows(deal)).isEqualTo(rows);
        assertStillTheAgreedOrder(deal, v1);
    }

    @Test
    void afterConfirm_aCloneOrRevisionDraftOpenedBeforeConfirm_cannotBeSubmitted() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto clone = quotationService.createReorder(v1.id(), salesActor);
        DealQuotationDto revisionDraft = quotationService.createRevision(v1.id(), salesActor);
        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);

        assertConflict(() -> quotationService.submit(clone.id(), salesActor), "ยืนยันคำสั่งซื้อจากใบเสนอราคาอื่นแล้ว");
        assertConflict(() -> quotationService.submit(revisionDraft.id(), salesActor), "ยืนยันคำสั่งซื้อจากใบเสนอราคาอื่นแล้ว");

        assertStillTheAgreedOrder(deal, v1);
    }

    @Test
    void afterConfirm_aRevisionSubmittedBeforeConfirm_cannotBeApproved() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto revision = quotationService.createRevision(v1.id(), salesActor);
        quotationService.update(revision.id(), zeroDepositDirectDraft(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);

        assertConflict(() -> quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor),
            "ยืนยันคำสั่งซื้อจากใบเสนอราคาอื่นแล้ว");

        assertStillTheAgreedOrder(deal, v1);
    }

    private void assertStillTheAgreedOrder(long deal, DealQuotationDto v1) {
        assertThat(quotationService.get(v1.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id AND doc_status = 'APPROVED'",
            Map.of("id", deal), Integer.class)).isEqualTo(1);
        assertThat(summary(deal).depositPolicy()).isEqualTo("REQUIRED");
    }

    /** Review finding 2: confirming the order must not strand the deal's other open direct quotations. */
    @Test
    void confirmOrder_cancelsTheDealsOtherOpenDirectQuotations_andLeavesTheConfirmedOneAlone() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto clone = quotationService.createReorder(v1.id(), salesActor);          // DRAFT clone
        DealQuotationDto pending = quotationService.createRevision(v1.id(), salesActor);        // revision ...
        quotationService.update(pending.id(), zeroDepositDirectDraft(), salesActor);
        quotationService.submit(pending.id(), salesActor);                                      // ... PENDING_APPROVAL
        assertThat(quotationService.get(pending.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);

        assertThat(quotationService.get(v1.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(quotationService.get(clone.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.CANCELLED);
        assertThat(quotationService.get(pending.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :k",
            Map.of("id", deal, "k", TicketEventKind.CANCELLED), Integer.class)).isEqualTo(2);
    }

    /** ...and before the order is confirmed the same revision still works. */
    @Test
    void unconfirmedApprovedDirectQuotation_canStillBeRevised() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto fifty = approvedDirectQuotation(deal, salesActor);

        DealQuotationDto revision = quotationService.createRevision(fifty.id(), salesActor);

        assertThat(revision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(revision.parentQuotationId()).isEqualTo(fifty.id());
    }

    private int quotationRows(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — WRONG-WAY-ROUND. Each refusal asserts nothing was written.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwningSales_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), otherSalesActor));
        assertNothingPromoted(ghost);
    }

    @Test
    void importRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), importActor));
        assertNothingPromoted(ghost);
    }

    /** ceo APPROVES direct quotations but does not write them — promotion is the writer's action. */
    @Test
    void ceoRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), ceoActor));
        assertNothingPromoted(ghost);
    }

    @Test
    void accountRole_isRefused_403_andNothingIsWritten() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(ghost, salesActor);
        assertForbidden(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), accountActor));
        assertNothingPromoted(ghost);
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
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(draft.id(), salesActor), "อนุมัติแล้ว");
        assertNothingPromoted(ghost);
    }

    @Test
    void pendingApprovalDirectQuotation_isRefused_409() {
        long ghost = quotationOnlyTicket(salesActor);
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(ghost, directDraft(), salesActor).id(), salesActor);
        assertThat(pending.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(pending.id(), salesActor), "อนุมัติแล้ว");
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
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor), "ไม่ผ่านคำขอราคา");
        assertNothingPromoted(ghost);
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
        assertConflict(() -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor), "ACTIVE");
        assertNothingPromoted(ghost);
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

    /**
     * V193's backfill predicate includes {@code sales_stage = 'LEAD_APPROACH'} — "it has never left
     * the pipeline's starting point".
     *
     * <p><b>Why every fixture is now reset to LEAD_APPROACH, and why that is not cheating.</b> Since
     * the owner's 2026-10-16 ruling ({@code .design/quote-stage-gates} D-2) creating a quotation
     * moves the deal onto its recipient's stage, so {@code quotationService.create} no longer leaves
     * ANY of these deals at LEAD_APPROACH. That has no production consequence — V193 is a one-shot
     * backfill that already ran, and Flyway will never run it again — but it wrecks this test in a
     * way worth stating plainly, because five of the six assertions would still have been GREEN:
     *
     * <ul>
     *   <li>{@code ghost} fails outright (the one true positive, no longer matched).
     *   <li>{@code withPricingRequest}, {@code mixedOrigin} and {@code advancedStatus} keep
     *       reporting false — but excluded by the STAGE clause, not by the pricing-request, origin
     *       and status clauses each one exists to exercise. Three assertions passing for the wrong
     *       reason, proving nothing about the predicate they name.
     * </ul>
     *
     * <p>So the resets restore each case to having exactly ONE reason to be excluded, which is what
     * makes the test evidence rather than coincidence. This is legitimate here specifically because
     * the section's own header says V193 is re-executed verbatim over HAND-SHAPED rows: the job is
     * to reconstruct the historical state V193 was written against, and post-D-2 the service itself
     * can no longer produce it. {@code advancedStage} is deliberately NOT reset — it is the case
     * that tests the stage clause.
     */
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
        // Undo D-2's creation-time advance on every case EXCEPT advancedStage — see the Javadoc.
        // The reset is load-bearing, so it is asserted rather than assumed: a silent no-op here
        // would put three of the six assertions back to passing for the wrong reason.
        for (long id : new long[] {ghost, withPricingRequest, mixedOrigin, advancedStatus}) {
            tickets.updateSalesStage(id, DealStage.LEAD_APPROACH);
            assertThat(summary(id).salesStage()).as("fixture %s reset", id).isEqualTo(DealStage.LEAD_APPROACH);
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

    private DealQuotationDto approvedQuotation(long ticketId, UpsertDealQuotationRequest draft) {
        DealQuotationDto created = quotationService.create(ticketId, draft, salesActor);
        quotationService.submit(created.id(), salesActor);
        return quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
    }

    /** Revise {@code source} to {@code draft}'s terms, submit, and approve — the reviewer's F1 sequence. */
    private DealQuotationDto approvedRevision(DealQuotationDto source, UpsertDealQuotationRequest draft) {
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        quotationService.update(revision.id(), draft, salesActor);
        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        return approved;
    }

    private List<String> policyChangedMessages(long ticketId) {
        return jdbc.queryForList("""
            SELECT message FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind ORDER BY event_id
            """, Map.of("id", ticketId, "kind", TicketEventKind.POLICY_CHANGED), String.class);
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

    /** {@link #directDraft} as a ไม่รับมัดจำ quotation: 0% submits only with a full-payment term. */
    private UpsertDealQuotationRequest blankDepositDirectDraft() {
        UpsertDealQuotationRequest d = directDraft();
        return new UpsertDealQuotationRequest(d.contactId(), d.deptCode(), d.unitCode(), d.offerDate(), null,
            d.remainderMode(), d.creditDays(), d.validityDays(), d.validityMode(), d.validityUntil(),
            d.customerNotes(), d.priceMode(), d.documentLanguage(), d.currency(), null, null, null, null,
            d.fullPaymentTerm(), null, null, null, null, d.recipientType(), d.items());
    }

    private UpsertDealQuotationRequest zeroDepositDirectDraft() {
        UpsertDealQuotationRequest d = directDraft();
        return new UpsertDealQuotationRequest(d.contactId(), d.deptCode(), d.unitCode(), d.offerDate(), 0,
            d.remainderMode(), d.creditDays(), d.validityDays(), d.validityMode(), d.validityUntil(),
            d.customerNotes(), d.priceMode(), d.documentLanguage(), d.currency(), null, null, null, null,
            "BEFORE_DELIVERY", null, null, null, null, d.recipientType(), d.items());
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Typed", "White", "Matte", "60x60", null,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    /**
     * Every refusal above asserts through here: the promotion wrote NOTHING.
     *
     * <p><b>The stage assertion changed with the owner's 2026-10-16 quote-stage ruling</b>
     * ({@code .design/quote-stage-gates} D-2), and the change is the point rather than an
     * accommodation. It used to read {@code isEqualTo(LEAD_APPROACH)}, which conflated two
     * different claims: "the promotion did not happen" (what this helper is for) and "nothing
     * whatsoever has moved this deal's stage" (an accident of the fixture). Creating the quotation
     * now moves the ghost deal onto its recipient's stage — {@code directDraft()} addresses the
     * OWNER, so ขั้น 5 — so the old assertion failed on all nine call sites while the behaviour it
     * exists to protect was untouched.
     *
     * <p>Asserted as QUOTE_OWNER rather than merely "not ORDER_RECEIVED" deliberately: that is the
     * STRONGER statement. It says the refused promotion moved the stage neither forward nor back,
     * and that the only thing which ever moved it was the create. A loose
     * {@code isNotEqualTo(ORDER_RECEIVED)} would stay green if a refusal silently dragged the deal
     * somewhere else entirely.
     */
    private void assertNothingPromoted(long ticketId) {
        TicketSummaryDto s = summary(ticketId);
        assertThat(s.quotationOnly()).as("still quotation-only").isTrue();
        assertThat(s.status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(s.salesStage()).as("the quotation's own recipient stage, untouched by the refusal")
            .isEqualTo(DealStage.QUOTE_OWNER);
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
