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
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
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
 * Quotation ↔ deal linking — real-DB,
 * real-service proof for "ยืนยันคำสั่งซื้อ" on a direct quotation
 * ({@link DealQuotationService#confirmOrderFromDirectQuotation}, {@code POST
 * /api/deal-quotations/{id}/confirm-order}; {@code …/promote-to-deal} is kept as an alias for one
 * release). A quotation-first deal is NOT hidden from the pipeline.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>there is no {@code quotation_only} precondition — an ordinary deal with an APPROVED
 *       direct quotation confirms too; {@code quotation_only} is provenance only;</li>
 *   <li>a quotation-first deal is a real pipeline deal: it is listed, and stage / entry-channel /
 *       tender writes work on it;</li>
 *   <li>the items and pricing-request refusals are keyed on "this deal has a live
 *       DEAL_DIRECT quotation", not the flag ({@link DirectQuotationLocks}).</li>
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
    private RecordingSalesMailer mailer;
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
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
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
            new DepositNoticePdfRenderer(), customers, legacyQuotations);

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

    /** The quotation-first ("ghost") deal confirms. */
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
        // now succeeds, autofilled from the DEAL_DIRECT APPROVED quotation.
        DepositNoticeDto draft = depositNoticeService.createDraft(ghost,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        assertThat(draft.ticketId()).isEqualTo(ghost);
        // The ส่วนลดพิเศษ ADJUSTMENT row (qty -1, -100.00) is folded into the
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

        // The page sends every line back on save; a @PositiveOrZero qty would refuse the whole PUT.
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
     * A deal created the ordinary way (deal page,
     * {@code quotation_only = FALSE}, never a ghost) with an APPROVED direct quotation confirms the
     * order exactly like a quotation-first one — S10, CUSTOMER_CONFIRMED, the quotation's lines, one
     * event (not a 409 "อยู่ใน pipeline อยู่แล้ว").
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

    /** The deal page's document register leads its group with the row the order was confirmed from (PR-2026-0289). */
    @Test
    void orderConfirmedQuotationId_namesTheConfirmedRow_onlyAfterConfirmation_andOnlyWhenVisible() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        List<DealQuotationDto> before = quotationService.listForTicket(deal, salesActor);
        assertThat(quotationService.orderConfirmedQuotationId(deal, before)).as("not confirmed yet").isNull();

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        List<DealQuotationDto> after = quotationService.listForTicket(deal, salesActor);
        assertThat(quotationService.orderConfirmedQuotationId(deal, after)).isEqualTo(approved.id());
        assertThat(quotationService.orderConfirmedQuotationId(deal, List.of()))
            .as("never names a row the viewer was not shown").isNull();
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Hand-off notifications: confirm-order from a direct quotation tells the CEO, and
    // account too when the order carries no deposit. Bell row AND email path are pinned.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final String ORDER_CONFIRMED_TITLE = "ลูกค้ายืนยันคำสั่งซื้อแล้ว";
    private static final String NO_DEPOSIT_TITLE = "ยืนยันคำสั่งซื้อแล้ว — ดีลนี้ไม่เก็บมัดจำ";

    private String ceoOrderMessage(long deal, DealQuotationDto approved, String depositText) {
        TicketSummaryDto s = summary(deal);
        return salesActor.name() + " ยืนยันคำสั่งซื้อจากใบเสนอราคา " + approved.number() + " แล้ว\n"
            + "ดีล " + s.code() + " · ลูกค้า " + s.customerName() + " · ยอดรวม "
            + th.co.glr.hr.common.ThaiText.money(approved.grandTotal()) + " บาท · " + depositText
            + "\nแจ้งเพื่อทราบ ไม่ต้องดำเนินการ";
    }

    private String accountNoDepositMessage(long deal) {
        TicketSummaryDto s = summary(deal);
        return "ลูกค้ายืนยันคำสั่งซื้อแล้ว และดีลนี้ไม่เก็บเงินมัดจำ จึงจะไม่มีใบแจ้งรับมัดจำ\n"
            + "ดีล " + s.code() + " · ลูกค้า " + s.customerName()
            + "\nแจ้งเพื่อทราบ ยอดเต็มจะเรียกเก็บด้วยใบแจ้งหนี้เมื่อสินค้าพร้อมส่ง";
    }

    private DealQuotationDto approvedZeroDepositQuotation(long deal) {
        DealQuotationDto created = quotationService.create(deal, zeroDepositDirectDraft(), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.depositPercent()).isZero();
        return approved;
    }

    /** Deposit order: CEO is told (with the retitled ORDER_CONFIRMED title); account is NOT told "no deposit". */
    @Test
    void confirmOrderFromDirectQuotation_withADeposit_notifiesTheCeoOnly() {
        mailer.seedRoleEmployee("account");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        assertThat(approved.depositPercent()).as("fixture: a 50% deposit").isEqualTo(50);

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        mailer.assertRoleNotice("ceo", "ORDER_CONFIRMED", ORDER_CONFIRMED_TITLE,
            ceoOrderMessage(deal, approved, "มัดจำ 50%"), "/tickets/" + deal, ceoActor.id());
        mailer.assertNoNotice("ORDER_CONFIRMED_NO_DEPOSIT", NO_DEPOSIT_TITLE);
    }

    /** 0% quotation: the CEO notice says ไม่เก็บมัดจำ, and account gets the no-deposit notice (links to ITS page). */
    @Test
    void confirmOrderFromDirectQuotation_zeroDeposit_notifiesCeoAndAccountOfTheNoDepositOrder() {
        long accountId = mailer.seedRoleEmployee("account");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedZeroDepositQuotation(deal);

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        mailer.assertRoleNotice("ceo", "ORDER_CONFIRMED", ORDER_CONFIRMED_TITLE,
            ceoOrderMessage(deal, approved, "ไม่เก็บมัดจำ"), "/tickets/" + deal, ceoActor.id());
        mailer.assertRoleNotice("account", "ORDER_CONFIRMED_NO_DEPOSIT", NO_DEPOSIT_TITLE,
            accountNoDepositMessage(deal), "/finance/deals/" + deal, accountId);
    }

    /** The idempotent replay (a second confirm of the same order) writes nothing and notifies nobody again. */
    @Test
    void confirmOrderFromDirectQuotation_replay_sendsNothingNew() {
        long accountId = mailer.seedRoleEmployee("account");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedZeroDepositQuotation(deal);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        // exactly-once asserts: a duplicate bell row or email would fail the size checks.
        mailer.assertRoleNotice("ceo", "ORDER_CONFIRMED", ORDER_CONFIRMED_TITLE,
            ceoOrderMessage(deal, approved, "ไม่เก็บมัดจำ"), "/tickets/" + deal, ceoActor.id());
        mailer.assertRoleNotice("account", "ORDER_CONFIRMED_NO_DEPOSIT", NO_DEPOSIT_TITLE,
            accountNoDepositMessage(deal), "/finance/deals/" + deal, accountId);
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

    /** A ไม่รับมัดจำ (0%) direct quotation confirms onto the no-deposit
     * payment path, so final payment is not blocked by a deposit that will never exist. */
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
        // The whole 0% walk (0% skips the deposit path automatically).
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
     * Once the order is confirmed from a direct quotation, THAT quotation can no longer
     * be revised (a revision's approval would supersede the agreed order and move the payable).
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
     * The two leaks around the revise gate: after the order is confirmed from v1, no OTHER direct
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

    /** Confirming the order must not strand the deal's other open direct quotations. */
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
        // Only the quotation that was WAITING on approval had told the SM and CEO "รออนุมัติ", so only that
        // cancellation un-tells them (FYI); the DRAFT clone never reached them. Link = the cancelled document.
        TicketSummaryDto d = summary(deal);
        String message = salesActor.name() + " ยกเลิกใบเสนอราคา " + pending.number()
            + " ที่รออนุมัติ เพราะยืนยันคำสั่งซื้อจากใบเสนอราคา " + v1.number() + " แล้ว\nดีล " + d.code() + " · ลูกค้า "
            + d.customerName() + "\nแจ้งเพื่อทราบ ไม่ต้องอนุมัติแล้ว";
        String title = "ใบเสนอราคาถูกยกเลิก — ไม่ต้องอนุมัติแล้ว";
        mailer.assertNoticeFor(salesManagerActor.id(), "DEAL_QUOTATION_CANCELLED", title, message, "/quotations/" + pending.id());
        mailer.assertNoticeFor(ceoActor.id(), "DEAL_QUOTATION_CANCELLED", title, message, "/quotations/" + pending.id());
        mailer.assertBellRecipients("DEAL_QUOTATION_CANCELLED", salesManagerActor.id(), ceoActor.id());
    }

    /**
     * A quotation another transaction moves DRAFT -> PENDING_APPROVAL while the confirmation is in flight
     * must still be reported as pending (so SM + CEO get the cancellation notice). tx1 holds the row lock with the flip
     * uncommitted; the confirmation blocks on it; once tx1 commits, the cancel must see the PENDING row.
     */
    @Test
    void confirmOrder_aQuotationSubmittedConcurrently_isStillReportedAsPending_andNotifiesApprovers() throws Exception {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto racing = quotationService.createReorder(v1.id(), salesActor); // DRAFT
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> confirm = transactionTemplate.execute(status -> {
                jdbc.queryForList("SELECT 1 FROM sales.quotation WHERE quotation_id = :id FOR UPDATE",
                    Map.of("id", racing.id()));
                jdbc.update("UPDATE sales.quotation SET doc_status = 'PENDING_APPROVAL' WHERE quotation_id = :id",
                    Map.of("id", racing.id()));
                java.util.concurrent.Future<?> f = pool.submit(
                    () -> confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND wait_event_type = 'Lock'", Map.of(), Integer.class) == 0) {
                    assertThat(System.nanoTime()).as("confirmation never blocked on the row lock").isLessThan(deadline);
                    assertThat(f.isDone()).as("confirmation finished without blocking").isFalse();
                    try { Thread.sleep(50); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                }
                return f;
            });
            confirm.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(quotationService.get(racing.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.CANCELLED);
        TicketSummaryDto d = summary(deal);
        String message = salesActor.name() + " ยกเลิกใบเสนอราคา " + racing.number()
            + " ที่รออนุมัติ เพราะยืนยันคำสั่งซื้อจากใบเสนอราคา " + v1.number() + " แล้ว\nดีล " + d.code() + " · ลูกค้า "
            + d.customerName() + "\nแจ้งเพื่อทราบ ไม่ต้องอนุมัติแล้ว";
        String title = "ใบเสนอราคาถูกยกเลิก — ไม่ต้องอนุมัติแล้ว";
        mailer.assertNoticeFor(salesManagerActor.id(), "DEAL_QUOTATION_CANCELLED", title, message, "/quotations/" + racing.id());
        mailer.assertNoticeFor(ceoActor.id(), "DEAL_QUOTATION_CANCELLED", title, message, "/quotations/" + racing.id());
    }

    /** Nothing was waiting on approval (only a DRAFT clone is cancelled): the approvers are told nothing. */
    @Test
    void confirmOrder_cancellingOnlyADraftSibling_notifiesNoApprover() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto clone = quotationService.createReorder(v1.id(), salesActor);

        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);

        assertThat(quotationService.get(clone.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.CANCELLED);
        mailer.assertNoNotice("DEAL_QUOTATION_CANCELLED", "ใบเสนอราคาถูกยกเลิก — ไม่ต้องอนุมัติแล้ว");
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

    /**
     * Confirm-order must serialise on the SAME per-ticket advisory lock as approve/create/revise
     * ({@code DealQuotationRepository.lockTicket}), not only the ticket row lock; otherwise it can
     * commit alongside an approval that supersedes the row it confirmed. Pinned deterministically: a
     * second connection holds the advisory lock; the confirmation must wait on it, then finish once it
     * is released.
     */
    @Test
    void confirmOrder_waitsForThePerTicketAdvisoryLock_heldByAnotherTransaction() throws Exception {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        javax.sql.DataSource ds = ((org.springframework.jdbc.datasource.DataSourceTransactionManager) transactionManager)
            .getDataSource();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection holder = ds.getConnection()) {
            holder.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = holder.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, DealQuotationRepository.TICKET_LOCK_CLASS_ID);
                ps.setInt(2, (int) deal);
                ps.execute();
            }
            java.util.concurrent.Future<?> confirm = pool.submit(
                () -> confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor));
            try {
                confirm.get(2, java.util.concurrent.TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.fail(
                    "confirm-order completed while another transaction held the ticket advisory lock");
            } catch (java.util.concurrent.TimeoutException expected) {
                // still waiting on the advisory lock — what we want
            }
            holder.rollback(); // releases pg_advisory_xact_lock
            confirm.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(summary(deal).salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(eventCount(deal, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
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

    /** The quotation-first deal is listed (and
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

    /** Wrong-way-round: with no quotation-only refusal, the deal is still not open to a non-owner. */
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
     * <p><b>Why every fixture is reset to LEAD_APPROACH, and why that is not cheating.</b> Creating
     * a quotation moves the deal onto its recipient's stage ({@code .design/quote-stage-gates}
     * D-2), so {@code quotationService.create} does not leave
     * ANY of these deals at LEAD_APPROACH. That has no production consequence — V193 is a one-shot
     * backfill that already ran, and Flyway will never run it again — but without the resets
     * five of the six assertions would still be GREEN:
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
     * to reconstruct the historical state V193 was written against, which the service itself
     * can no longer produce. {@code advancedStage} is deliberately NOT reset — it is the case
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
    // ─────────────────────────────────────────────────────────────────────────────────────
    // Confirm-order on a DIRECT quotation must hand each
    // ticket_item the quotation line's catalogue link and factory, as the pricing-request route does —
    // otherwise สร้างใบ IR 409s "รายการนี้ยังไม่ได้ระบุโรงงาน" and nobody can fix it.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private UpsertDealQuotationRequest catalogueLineDraft(Long catalogPriceId, String model) {
        ItemInput line = new ItemInput(
            null, catalogPriceId, null, "SCG", model, "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false);
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(line)).withRecipientType("OWNER");
    }

    private List<Map<String, Object>> ticketItemLinks(long ticketId) {
        return jdbc.queryForList("""
            SELECT model, factory, catalog_price_id FROM sales.ticket_item
             WHERE ticket_id = :id ORDER BY sort_order
            """, Map.of("id", ticketId));
    }

    @Test
    void confirmOrder_onCatalogueLines_copiesCatalogPriceIdAndFactoryToEveryTicketItem() {
        long priceA = insertCatalogProduct("W3 Factory A", "IT", "W3-A", new BigDecimal("10"), "EUR", "per_sqm");
        long ticketId = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(ticketId, catalogueLineDraft(priceA, "Line A"));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        List<Map<String, Object>> items = ticketItemLinks(ticketId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("catalog_price_id")).isEqualTo(priceA);
        assertThat(items.get(0).get("factory")).isEqualTo("W3 Factory A");
    }

    @Test
    void confirmOrder_onCatalogueLines_letsSalesCreateAndIssueThePerFactoryIr_insteadOf409() {
        long priceB = insertCatalogProduct("W3 Factory B", "IT", "W3-B", new BigDecimal("10"), "EUR", "per_sqm");
        long ticketId = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(ticketId, catalogueLineDraft(priceB, "Line B"));
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        // create also issues now, so the payment gate applies: the deposit has been received.
        jdbc.update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_PAID' WHERE ticket_id = :id",
            Map.of("id", ticketId));
        th.co.glr.hr.importrequest.ImportRequestService importRequests =
            new th.co.glr.hr.importrequest.ImportRequestService(
                new th.co.glr.hr.importrequest.ImportRequestQueryRepository(jdbc),
                new th.co.glr.hr.importrequest.ImportRequestRenderer(),
                new th.co.glr.hr.importrequest.ImportRequestRepository(jdbc),
                new th.co.glr.hr.factory.FactoryConfigRepository(jdbc), tickets, ticketService,
                new NotificationRepository(jdbc, mailer));

        List<th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto> drafts =
            importRequests.createDrafts(ticketId, null, salesActor);

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).factoryName()).isEqualTo("W3 Factory B");
        assertThat(drafts.get(0).status()).isEqualTo("ISSUED");
    }

    /** Guard (green before and after): a free-text line has no catalogue item, so it keeps factory NULL. */
    @Test
    void confirmOrder_onAFreeTextLine_keepsFactoryAndCatalogLinkNull() {
        long ticketId = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(ticketId, catalogueLineDraft(null, "Free text"));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        Map<String, Object> item = ticketItemLinks(ticketId).get(0);
        assertThat(item.get("factory")).isNull();
        assertThat(item.get("catalog_price_id")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // จากสต็อก on a direct quotation: the declaration reaches the ticket and drives routing exactly as the
    // pricing-request route does (qty_from_stock = qty on a stock line).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void directQuotation_stockSource_isStored_clearedAndRestoredOnUpdate() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto created = quotationService.create(deal,
            stockDraft(50, null, stockTile("Stock A", true)), salesActor);
        assertThat(storedStockSources(created.id())).containsExactly("IN_THAILAND");
        assertThat(created.items().get(0).stockSource()).isEqualTo("IN_THAILAND");

        // Reusing the saved item id takes the in-place UPDATE path, not delete + re-insert.
        long itemId = created.items().get(0).id();
        quotationService.update(created.id(), stockDraft(50, null, stockTile("Stock A", false, itemId)), salesActor);
        assertThat(storedStockSources(created.id())).containsExactly((String) null);

        DealQuotationDto restored = quotationService.update(created.id(),
            stockDraft(50, null, stockTile("Stock A", true, itemId)), salesActor);
        assertThat(restored.items().get(0).id()).isEqualTo(itemId);
        assertThat(storedStockSources(created.id())).containsExactly("IN_THAILAND");
        assertThat(restored.items().get(0).stockSource()).isEqualTo("IN_THAILAND");
    }

    @Test
    void zeroDeposit_allStockLines_confirmOntoFromStock_andNoImportRequestIsIssuable() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal,
            stockDraft(0, "BEFORE_DELIVERY", stockTile("Stock A", true), stockTile("Stock B", true)));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        List<Map<String, Object>> rows = ticketStockRows(deal);
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("sourced_from_stock")).isEqualTo(true);
            assertThat((BigDecimal) r.get("qty_from_stock")).isEqualByComparingTo((BigDecimal) r.get("qty"));
        });
        TicketSummaryDto after = summary(deal);
        assertThat(after.fulfillmentStatus()).isEqualTo("FROM_STOCK");
        assertThat(after.salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertConflict(() -> importRequestService().createDrafts(deal, null, salesActor), "ครอบคลุมจากสต็อกทั้งหมด");
    }

    @Test
    void zeroDeposit_mixedLines_coverOnlyTheStockLine_andKeepTheImportPath() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal,
            stockDraft(0, "BEFORE_DELIVERY", stockTile("Stock A", true), stockTile("Import B", false)));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        List<Map<String, Object>> rows = ticketStockRows(deal);
        assertThat(rows).hasSize(2);
        assertThat((BigDecimal) rows.get(0).get("qty_from_stock")).isEqualByComparingTo((BigDecimal) rows.get(0).get("qty"));
        assertThat(rows.get(1).get("sourced_from_stock")).isEqualTo(false);
        assertThat(rows.get(1).get("qty_from_stock") == null
            || ((BigDecimal) rows.get(1).get("qty_from_stock")).signum() == 0).isTrue();
        TicketSummaryDto after = summary(deal);
        assertThat(after.fulfillmentStatus()).isNull();
        assertThat(after.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(new th.co.glr.hr.importrequest.ImportRequestQueryRepository(jdbc).factoryResolutionCandidates(deal))
            .extracting(th.co.glr.hr.importrequest.ImportRequestQueryRepository.LineFactoryCandidate::model)
            .containsExactly("Import B");
    }

    @Test
    void depositOrder_allStockLines_routeToFromStock_onlyWhenTheDepositIsConfirmed() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal, stockDraft(50, null, stockTile("Stock A", true)));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        assertThat(summary(deal).fulfillmentStatus()).isNull();
        assertThat(summary(deal).salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);

        ticketService.recordPayment(deal, new th.co.glr.hr.ticket.RecordPaymentRequest("DEPOSIT",
            tickets.payableAmount(deal).multiply(new BigDecimal("0.50")), null, null, null, null, false), accountActor);

        assertThat(summary(deal).fulfillmentStatus()).isEqualTo("FROM_STOCK");
        assertThat(summary(deal).salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    @Test
    void repConfirmsWithAStockLine_theSalesManagerIsToldOnce_withoutClaimingTheWholeDeal() {
        long smId = mailer.seedRoleEmployee("sales_manager");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal,
            stockDraft(50, null, stockTile("Stock A", true), stockTile("Import B", false)));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        List<Map<String, Object>> rows = ticketStockRows(deal);
        String declared = ((BigDecimal) rows.get(0).get("qty_from_stock")).stripTrailingZeros().toPlainString();
        String ordered = ((BigDecimal) rows.get(0).get("qty")).add((BigDecimal) rows.get(1).get("qty"))
            .stripTrailingZeros().toPlainString();
        TicketSummaryDto s = summary(deal);
        assertThat(stockReservedMessages(smId)).singleElement().satisfies(m -> assertThat(m)
            .contains(s.code()).contains(s.customerName()).contains(salesActor.name())
            .contains(declared + "/" + ordered).contains("STOCK_BONUS").doesNotContain("ครบทั้งดีล"));
    }

    @Test
    void repConfirmsAnAllStockZeroDepositDeal_theNoticeSaysTheDealLeavesTheImportAxis() {
        long smId = mailer.seedRoleEmployee("sales_manager");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal,
            stockDraft(0, "BEFORE_DELIVERY", stockTile("Stock A", true)));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        assertThat(summary(deal).fulfillmentStatus()).isEqualTo("FROM_STOCK");
        assertThat(stockReservedMessages(smId)).singleElement().satisfies(m -> assertThat(m).contains("ครบทั้งดีล"));
    }

    @Test
    void repConfirmsAnAllStockDepositDeal_theNoticeDoesNotClaimTheDealLeftTheImportAxisYet() {
        long smId = mailer.seedRoleEmployee("sales_manager");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal, stockDraft(50, null, stockTile("Stock A", true)));

        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);

        assertThat(summary(deal).fulfillmentStatus()).isNull();
        assertThat(stockReservedMessages(smId)).singleElement()
            .satisfies(m -> assertThat(m).doesNotContain("ครบทั้งดีล"));
    }

    @Test
    void confirmWithNoStockLine_orByANonRep_orOnReplay_sendsNoStockNotice() {
        long smId = mailer.seedRoleEmployee("sales_manager");
        long plainDeal = pipelineTicket(salesActor);
        confirmer.confirmOrderFromDirectQuotation(approvedDirectQuotation(plainDeal, salesActor).id(), salesActor);
        assertThat(stockReservedMessages(smId)).as("no stock line").isEmpty();

        long managerDeal = pipelineTicket(salesActor);
        DealQuotationDto stockQuote = approvedQuotation(managerDeal, stockDraft(50, null, stockTile("Stock A", true)));
        confirmer.confirmOrderFromDirectQuotation(stockQuote.id(), salesManagerActor);
        assertThat(stockReservedMessages(smId)).as("a non-rep confirming").isEmpty();

        long repDeal = pipelineTicket(salesActor);
        DealQuotationDto repQuote = approvedQuotation(repDeal, stockDraft(50, null, stockTile("Stock A", true)));
        confirmer.confirmOrderFromDirectQuotation(repQuote.id(), salesActor);
        confirmer.confirmOrderFromDirectQuotation(repQuote.id(), salesActor);
        assertThat(stockReservedMessages(smId)).as("replay adds nothing").hasSize(1);
    }

    private List<String> stockReservedMessages(long recipientId) {
        return jdbc.queryForList("""
            SELECT message FROM hr.notification WHERE type = :type AND employee_id = :id ORDER BY notification_id
            """, Map.of("type", TicketEventKind.STOCK_RESERVED, "id", recipientId), String.class);
    }

    private List<String> storedStockSources(long quotationId) {
        return jdbc.queryForList(
            "SELECT stock_source FROM sales.quotation_item WHERE quotation_id = :id ORDER BY seq",
            Map.of("id", quotationId), String.class);
    }

    private List<Map<String, Object>> ticketStockRows(long ticketId) {
        return jdbc.queryForList("""
            SELECT qty, qty_from_stock, sourced_from_stock FROM sales.ticket_item
             WHERE ticket_id = :id ORDER BY sort_order
            """, Map.of("id", ticketId));
    }

    private th.co.glr.hr.importrequest.ImportRequestService importRequestService() {
        return new th.co.glr.hr.importrequest.ImportRequestService(
            new th.co.glr.hr.importrequest.ImportRequestQueryRepository(jdbc),
            new th.co.glr.hr.importrequest.ImportRequestRenderer(),
            new th.co.glr.hr.importrequest.ImportRequestRepository(jdbc),
            new th.co.glr.hr.factory.FactoryConfigRepository(jdbc), tickets, ticketService,
            new NotificationRepository(jdbc, mailer));
    }

    private ItemInput stockTile(String model, boolean stock) {
        return stockTile(model, stock, null);
    }

    /** {@code id} non-null is a row the editor already saved, which update rewrites in place. */
    private ItemInput stockTile(String model, boolean stock, Long id) {
        return new ItemInput(
            null, null, null, "SCG", model, "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            id, null, false).withStockSource(stock ? "IN_THAILAND" : null);
    }

    private UpsertDealQuotationRequest stockDraft(Integer depositPercent, String fullPaymentTerm, ItemInput... lines) {
        UpsertDealQuotationRequest d = directDraft();
        return new UpsertDealQuotationRequest(d.contactId(), d.deptCode(), d.unitCode(), d.offerDate(), depositPercent,
            d.remainderMode(), d.creditDays(), d.validityDays(), d.validityMode(), d.validityUntil(),
            d.customerNotes(), d.priceMode(), d.documentLanguage(), d.currency(), null, null, null, null,
            fullPaymentTerm, null, null, null, null, d.recipientType(), List.of(lines));
    }

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

    /** Revise {@code source} to {@code draft}'s terms, submit, and approve. */
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
     * <p><b>The stage assertion is QUOTE_OWNER, not LEAD_APPROACH</b>
     * ({@code .design/quote-stage-gates} D-2). {@code isEqualTo(LEAD_APPROACH)} would conflate two
     * different claims: "the promotion did not happen" (what this helper is for) and "nothing
     * whatsoever has moved this deal's stage" (an accident of the fixture). Creating the quotation
     * moves the ghost deal onto its recipient's stage — {@code directDraft()} addresses the
     * OWNER, so ขั้น 5.
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
