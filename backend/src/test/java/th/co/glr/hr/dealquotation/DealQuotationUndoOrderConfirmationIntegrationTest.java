package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.PromoteToDealResultDto;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.ProcurementSourceRequest;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Real-DB proof for {@link DealQuotationService#undoOrderConfirmation} ({@code POST
 * /api/deal-quotations/{id}/undo-confirm-order}), the reverse of
 * {@link DealQuotationService#confirmOrderFromDirectQuotation}: a rep who pressed "ยืนยันคำสั่งซื้อ" by mistake takes
 * the order back. Production services are hand-wired against real Postgres, and the undo is called through a REAL
 * {@code @Transactional} proxy ({@link #confirmer}), so the atomicity cases prove the method's own annotation.
 *
 * <p>Convention the tests rely on (the spec leaves the wording to the implementation): the
 * {@code ORDER_CONFIRMATION_UNDONE} event's note contains the substring {@code "เหตุผล"} if and only if a non-blank
 * reason was given, followed by the trimmed reason.
 *
 * <p>Every refusal asserts through {@link #assertRefusedAndNothingWritten}: a full before/after snapshot of the deal
 * ({@link #state}) is identical, so a refusal that half-wrote would be caught.
 */
class DealQuotationUndoOrderConfirmationIntegrationTest extends DirectQuotationDealTestBase {
    private static final String AUTO_STAGE_NOTE = "อัตโนมัติจากขั้นตอนของดีล";

    private record Confirmed(long deal, DealQuotationDto quotation) {}

    // ───────────────────────────────────────────────────────────────────────────────────
    // 1. Happy path — quotation-first deal with a deposit: every write is reversed
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void happyPath_quotationFirstDealWithDeposit_reversesEveryWriteOfTheConfirmation() {
        long deal = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        long partyId = partyOf(deal, approved.id());
        String preStage = summary(deal).salesStage();
        assertThat(preStage).as("fixture: the quotation's own recipient stage").isEqualTo(DealStage.QUOTE_OWNER);
        List<String> kindsBeforeConfirm = eventKinds(deal);
        // confirmed by the SALES MANAGER; undone by the owning rep -- the note must name who had confirmed.
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesManagerActor);
        assertThat(partyStatus(partyId)).as("fixture: confirmation awards the party").isEqualTo("AWARDED");
        assertThat(summary(deal).salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(itemCount(deal)).isEqualTo(2);

        PromoteToDealResultDto result = confirmer.undoOrderConfirmation(approved.id(), "ลูกค้าแจ้งว่ากดผิด", salesActor);

        assertThat(result.ticketId()).isEqualTo(deal);
        assertThat(result.ticket().status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(result.ticket().paymentStatus()).isNull();
        assertThat(result.ticket().salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(result.quotation().docStatus()).as("the quotation itself stays APPROVED")
            .isEqualTo(QuotationStatus.APPROVED);

        Map<String, Object> ticket = jdbc.queryForMap("""
            SELECT status, payment_status, sales_stage, fulfillment_status, deposit_policy, deposit_policy_set_by,
                   quotation_only FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", deal));
        assertThat(ticket.get("status")).isEqualTo(TicketStatus.DRAFT);
        assertThat(ticket.get("payment_status")).isNull();
        assertThat(ticket.get("sales_stage")).as("exactly the pre-confirm stage").isEqualTo(preStage);
        assertThat(ticket.get("fulfillment_status")).isNull();
        assertThat(ticket.get("deposit_policy")).as("a 50% quotation leaves REQUIRED as it was")
            .isEqualTo(DepositPolicy.REQUIRED);
        assertThat(ticket.get("quotation_only")).as("provenance only, deliberately NOT restored").isEqualTo(false);
        assertThat(itemCount(deal)).as("the copies made at confirm are gone").isZero();
        assertThat(partyStatus(partyId)).as("AWARDED -> QUOTED").isEqualTo("QUOTED");

        // The confirmation's events are DELETED (SQL readers treat the promotion event's existence as "confirmed").
        assertThat(eventCount(deal, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isZero();
        assertThat(eventCount(deal, TicketEventKind.CUSTOMER_CONFIRMED)).isZero();
        assertThat(jdbc.queryForList("SELECT to_status FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'STAGE_CHANGED'"
            + " ORDER BY event_id", Map.of("id", deal), String.class))
            .as("the automatic move to ORDER_RECEIVED is gone; the earlier move onto the quotation's stage stays")
            .containsExactly(DealStage.QUOTE_OWNER);
        // Net event log == the log before the confirmation, plus exactly the one undo event, last.
        List<String> expectedKinds = new ArrayList<>(kindsBeforeConfirm);
        expectedKinds.add(TicketEventKind.ORDER_CONFIRMATION_UNDONE);
        assertThat(eventKinds(deal)).isEqualTo(expectedKinds);

        Map<String, Object> undo = jdbc.queryForMap("""
            SELECT from_status, to_status, related_document_type, related_document_id, actor_id, message
              FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind
            """, Map.of("id", deal, "kind", TicketEventKind.ORDER_CONFIRMATION_UNDONE));
        assertThat(undo.get("from_status")).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(undo.get("to_status")).isEqualTo(TicketStatus.DRAFT);
        assertThat(undo.get("related_document_type")).isEqualTo("QUOTATION");
        assertThat(((Number) undo.get("related_document_id")).longValue()).isEqualTo(approved.id());
        assertThat(((Number) undo.get("actor_id")).longValue()).isEqualTo(salesActor.id());
        assertThat((String) undo.get("message"))
            .contains(approved.number())
            .contains(salesManagerActor.name())
            .contains("เหตุผล").contains("ลูกค้าแจ้งว่ากดผิด")
            .doesNotContain("ใบแจ้งรับมัดจำ"); // no deposit notice was deleted
    }

    @Test
    void reason_isTrimmed_andABlankReasonIsNoReason() {
        long padded = pipelineTicket(salesActor);
        DealQuotationDto paddedQuote = approvedDirectQuotation(padded, salesActor);
        confirmer.confirmOrderFromDirectQuotation(paddedQuote.id(), salesActor);
        long blank = pipelineTicket(salesActor);
        DealQuotationDto blankQuote = approvedDirectQuotation(blank, salesActor);
        confirmer.confirmOrderFromDirectQuotation(blankQuote.id(), salesActor);

        confirmer.undoOrderConfirmation(paddedQuote.id(), "  กดผิด  ", salesActor);
        confirmer.undoOrderConfirmation(blankQuote.id(), "   ", salesActor);

        assertThat(undoNote(padded)).contains("เหตุผล").contains("กดผิด").doesNotContain("  กดผิด").doesNotContain("กดผิด  ");
        assertThat(undoNote(blank)).doesNotContain("เหตุผล");
    }

    @Test
    void noReason_isAccepted() {
        Confirmed c = confirmedDeal();

        confirmer.undoOrderConfirmation(c.quotation().id(), null, salesActor);

        assertThat(summary(c.deal()).status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(undoNote(c.deal())).contains(c.quotation().number()).doesNotContain("เหตุผล");
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 2. A DRAFT deposit notice is deleted (with its items); another deal's is not
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void draftDepositNotice_isDeleted_withItsItems_andTheNoteSaysSo() {
        Confirmed c = confirmedDeal();
        DepositNoticeDto draft = depositNoticeService.createDraft(c.deal(),
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        assertThat(draft.items()).as("fixture: the draft has lines").isNotEmpty();
        Confirmed other = confirmedDeal();
        DepositNoticeDto othersDraft = depositNoticeService.createDraft(other.deal(),
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);

        confirmer.undoOrderConfirmation(c.quotation().id(), null, salesActor);

        assertThat(count("SELECT COUNT(*) FROM sales.deposit_notice WHERE ticket_id = :id", c.deal())).isZero();
        assertThat(noticeItemCount(draft.id())).isZero();
        assertThat(undoNote(c.deal())).contains("ใบแจ้งรับมัดจำ");
        assertThat(count("SELECT COUNT(*) FROM sales.deposit_notice WHERE ticket_id = :id", other.deal()))
            .as("another deal's draft is untouched").isEqualTo(1);
        assertThat(noticeItemCount(othersDraft.id())).isEqualTo(othersDraft.items().size());
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 3. 0% deposit: the system's policy flip is reversed; the all-stock auto-route is undone
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void zeroDeposit_systemPolicyFlipIsFlippedBack_withNullReason_andItsEventDeleted() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedZeroDepositQuotation(deal);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        assertThat(summary(deal).depositPolicy()).as("fixture").isEqualTo(DepositPolicy.NOT_REQUIRED);
        assertThat(eventCount(deal, TicketEventKind.POLICY_CHANGED)).as("fixture").isEqualTo(1);

        confirmer.undoOrderConfirmation(approved.id(), null, salesActor);

        TicketSummaryDto after = summary(deal);
        assertThat(after.depositPolicy()).isEqualTo(DepositPolicy.REQUIRED);
        assertThat(after.depositPolicyReason()).isNull();
        assertThat(jdbc.queryForObject("SELECT deposit_policy_set_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal), Long.class)).isNull();
        assertThat(eventCount(deal, TicketEventKind.POLICY_CHANGED)).isZero();
    }

    @Test
    void zeroDeposit_aPolicyAPersonSetAfterTheConfirmation_isLeftAlone() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedZeroDepositQuotation(deal);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        tickets.updateDepositPolicy(deal, DepositPolicy.CREDIT_CUSTOMER, "ลูกค้าเครดิต", salesManagerActor.id());

        confirmer.undoOrderConfirmation(approved.id(), null, salesActor);

        TicketSummaryDto after = summary(deal);
        assertThat(after.depositPolicy()).isEqualTo(DepositPolicy.CREDIT_CUSTOMER);
        assertThat(after.depositPolicyReason()).isEqualTo("ลูกค้าเครดิต");
        assertThat(jdbc.queryForObject("SELECT deposit_policy_set_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal), Long.class)).isEqualTo(salesManagerActor.id());
        assertThat(after.status()).as("the rest of the undo still happened").isEqualTo(TicketStatus.DRAFT);
    }

    @Test
    void zeroDepositAllStockDeal_autoRoutedToDeliveryScheduling_isUndone_stageAndFulfilment() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal,
            stockDraft(0, "BEFORE_DELIVERY", stockTile("Stock A", true)));
        String preStage = summary(deal).salesStage();
        assertThat(preStage).isEqualTo(DealStage.QUOTE_OWNER);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        TicketSummaryDto confirmed = summary(deal);
        assertThat(confirmed.salesStage()).as("fixture: auto-routed").isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(confirmed.fulfillmentStatus()).as("fixture").isEqualTo(FulfilmentStatus.FROM_STOCK);

        PromoteToDealResultDto result = confirmer.undoOrderConfirmation(approved.id(), null, salesActor);

        TicketSummaryDto after = summary(deal);
        assertThat(after.salesStage()).as("the FIRST automatic move's `from`, not the intermediate ORDER_RECEIVED")
            .isEqualTo(preStage);
        assertThat(after.fulfillmentStatus()).isNull();
        assertThat(after.status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(after.paymentStatus()).isNull();
        assertThat(after.depositPolicy()).isEqualTo(DepositPolicy.REQUIRED);
        assertThat(itemCount(deal)).isZero();
        assertThat(result.ticket().fulfillmentStatus()).isNull();
        assertThat(jdbc.queryForList("SELECT to_status FROM sales.ticket_event WHERE ticket_id = :id"
            + " AND kind = 'STAGE_CHANGED' AND message = :note ORDER BY event_id",
            Map.of("id", deal, "note", AUTO_STAGE_NOTE), String.class))
            .as("both automatic moves written by the confirmation are deleted").containsExactly(DealStage.QUOTE_OWNER);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 4. After the undo the deal is a normal pre-order deal again
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void afterUndo_aRevisionCanBeCreated_approved_andTheDealConfirmedFromIt() {
        Confirmed c = confirmedDeal();
        assertStatus(HttpStatus.CONFLICT, () -> quotationService.createRevision(c.quotation().id(), salesActor));
        confirmer.undoOrderConfirmation(c.quotation().id(), null, salesActor);

        DealQuotationDto revision = approvedRevision(c.quotation(), directDraft());

        assertThat(revision.parentQuotationId()).isEqualTo(c.quotation().id());
        confirmer.confirmOrderFromDirectQuotation(revision.id(), salesActor);
        assertThat(summary(c.deal()).salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(tickets.isOrderConfirmedFromQuotation(c.deal(), revision.id())).isTrue();
        assertThat(tickets.isOrderConfirmedFromQuotation(c.deal(), c.quotation().id())).isFalse();
    }

    @Test
    void afterUndo_confirmOrderWorksFullyAgain_onTheSameQuotation() {
        Confirmed c = confirmedDeal();
        confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", salesActor);

        confirmer.confirmOrderFromDirectQuotation(c.quotation().id(), salesActor);

        TicketSummaryDto after = summary(c.deal());
        assertThat(after.status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(after.paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(after.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(itemCount(c.deal())).isEqualTo(2);
        assertThat(eventCount(c.deal(), TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
        assertThat(eventCount(c.deal(), TicketEventKind.CUSTOMER_CONFIRMED)).as("one, not two").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id"
            + " AND kind = 'STAGE_CHANGED' AND to_status = 'ORDER_RECEIVED'", Map.of("id", c.deal()), Integer.class))
            .isEqualTo(1);
        assertThat(eventCount(c.deal(), TicketEventKind.ORDER_CONFIRMATION_UNDONE)).as("history of the undo is kept").isEqualTo(1);
        assertThat(depositNoticeService.createDraft(c.deal(),
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor).items())
            .as("the money side unlocks again").isNotEmpty();
        // ...and the undo can be pressed again: the promotion names this quotation once more.
        confirmer.undoOrderConfirmation(c.quotation().id(), null, salesActor);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 5. Siblings the confirmation auto-cancelled stay cancelled
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void autoCancelledSibling_staysCancelled_andItsCancelledEventIsKept() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto clone = quotationService.createReorder(v1.id(), salesActor);
        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);
        assertThat(quotationService.get(clone.id(), salesActor).docStatus()).as("fixture").isEqualTo(QuotationStatus.CANCELLED);

        confirmer.undoOrderConfirmation(v1.id(), null, salesActor);

        assertThat(quotationService.get(clone.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.CANCELLED);
        assertThat(quotationService.get(v1.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(eventCount(deal, TicketEventKind.CANCELLED)).as("the auto-cancel event is kept").isEqualTo(1);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 6. No notification of any kind
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void undo_producesNoNotificationAndNoMail() {
        mailer.seedRoleEmployee("account");
        mailer.seedRoleEmployee("sales_manager");
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedQuotation(deal,
            stockDraft(0, "BEFORE_DELIVERY", stockTile("Stock A", true)));
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        int bellRows = bellRows();
        int mails = mailer.mails().size();
        assertThat(bellRows).as("fixture: the CONFIRMATION does notify (CEO, account, SM)").isPositive();

        confirmer.undoOrderConfirmation(approved.id(), "กดผิด", salesActor);

        assertThat(bellRows()).isEqualTo(bellRows);
        assertThat(mailer.mails()).hasSize(mails);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 7. Cut-offs: each is a 409 and NOTHING is written
    // ───────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ISSUED", "SUPERSEDED"})
    void cutOff_aDepositNoticeThatIsNotADraft(String status) {
        Confirmed c = confirmedDeal();
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, 1, :status, 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, 32100.00)
            """, Map.of("id", c.deal(), "status", status));

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_aDraftNoticeNextToAnIssuedOne_deletesNothing() {
        Confirmed c = confirmedDeal();
        depositNoticeService.createDraft(c.deal(),
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, 2, 'ISSUED', 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, 32100.00)
            """, Map.of("id", c.deal()));

        assertRefusedAndNothingWritten(c); // the snapshot includes the draft notice and its items
    }

    @Test
    void cutOff_aPaymentReceipt() {
        Confirmed c = confirmedDeal();
        jdbc.update("INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by) VALUES (:id, 'DEPOSIT', 100, :by)",
            Map.of("id", c.deal(), "by", accountActor.id()));

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_aDraftRemainingInvoice() {
        Confirmed c = confirmedDeal();
        jdbc.update("INSERT INTO sales.remaining_invoice (ticket_id, status) VALUES (:id, 'DRAFT')", Map.of("id", c.deal()));

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISSUED", "SUPERSEDED"})
    void cutOff_aRemainingInvoiceOfAnyOtherStatus(String status) {
        Confirmed c = confirmedDeal();
        jdbc.update("""
            INSERT INTO sales.remaining_invoice
                (ticket_id, base_number, version, doc_number, status, items_total, deposit_deduction, net_amount,
                 vat_amount, grand_total, issued_by_id, issued_by_name, issued_at)
            VALUES (:id, 'GLR69000001', 1, 'GLR69000001-1', :status, 0, 0, 0, 0, 0, :by, 'Actor', now())
            """, Map.of("id", c.deal(), "status", status, "by", accountActor.id()));

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ISSUED"})
    void cutOff_anImportRequest(String status) {
        Confirmed c = confirmedDeal();
        long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES ('Factory Undo Cutoff', 'IT', 'EUR') RETURNING factory_id
            """, Map.of(), Long.class);
        if ("DRAFT".equals(status)) {
            jdbc.update("""
                INSERT INTO sales.import_request (ticket_id, factory_id, factory_name, brand, version, status)
                VALUES (:t, :f, 'Factory Undo Cutoff', 'Brand', 1, 'DRAFT')
                """, Map.of("t", c.deal(), "f", factoryId));
        } else {
            jdbc.update("""
                INSERT INTO sales.import_request
                    (ticket_id, factory_id, factory_name, brand, version, status, doc_number, issue_date,
                     import_step, import_step_at, first_issued_date)
                VALUES (:t, :f, 'Factory Undo Cutoff', 'Brand', 1, 'ISSUED', 'IR-UNDO-1', CURRENT_DATE,
                        'CONTACTED', CURRENT_DATE, CURRENT_DATE)
                """, Map.of("t", c.deal(), "f", factoryId));
        }

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_aDeliveryRecord() {
        Confirmed c = confirmedDeal();
        jdbc.update("INSERT INTO sales.delivery_record (ticket_id, source, delivered_by) VALUES (:id, 'WAREHOUSE', :by)",
            Map.of("id", c.deal(), "by", salesActor.id()));

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT, DealStage.DELIVERY_SCHEDULING})
    void cutOff_aSalesStagePastOrderReceived(String stage) {
        Confirmed c = confirmedDeal(); // 50% deposit: stays at ORDER_RECEIVED, fulfilment null
        tickets.updateSalesStage(c.deal(), stage);

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {FulfilmentStatus.IR_ISSUED, FulfilmentStatus.GOODS_RECEIVED, FulfilmentStatus.PARTIALLY_DELIVERED})
    void cutOff_deliverySchedulingWithAnyFulfilmentOtherThanFromStock(String fulfilment) {
        Confirmed c = confirmedDeal();
        tickets.updateSalesStage(c.deal(), DealStage.DELIVERY_SCHEDULING);
        tickets.updateFulfillmentStatus(c.deal(), fulfilment);

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_lifecycleNotActive() {
        Confirmed c = confirmedDeal();
        tickets.updateLifecycle(c.deal(), DealLifecycle.ON_HOLD);

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_ticketStatusNoLongerQuotationIssued() {
        Confirmed c = confirmedDeal();
        jdbc.update("UPDATE sales.ticket SET status = 'closed' WHERE ticket_id = :id", Map.of("id", c.deal()));

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPOSIT_NOTICE_ISSUED", "DEPOSIT_PAID"})
    void cutOff_paymentStatusPastCustomerConfirmed(String paymentStatus) {
        Confirmed c = confirmedDeal();
        jdbc.update("UPDATE sales.ticket SET payment_status = :p WHERE ticket_id = :id",
            Map.of("id", c.deal(), "p", paymentStatus));

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {TicketEventKind.BID_OUTCOME_RECORDED, TicketEventKind.DEAL_SPLIT_FROM_PARENT})
    void cutOff_theConfirmationCameFromABidOutcomeOrASplit(String kind) {
        Confirmed c = confirmedDeal();
        tickets.addEvent(c.deal(), salesActor.id(), salesActor.name(), kind, null, null, "test");

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_aPersonReservedStockAfterTheConfirmation_partial() {
        Confirmed c = confirmedDeal();
        long tileItem = firstItemId(c.deal());
        ticketService.reserveStock(c.deal(), new StockReservationRequest(
            List.of(new StockReservationRequest.Line(tileItem, new BigDecimal("3"), null))), ceoActor);
        assertThat(summary(c.deal()).salesStage()).as("fixture: still ORDER_RECEIVED").isEqualTo(DealStage.ORDER_RECEIVED);

        assertRefusedAndNothingWritten(c);
    }

    /** The exception to the stage rule is only for what the CONFIRMATION itself auto-routed: a person's all-stock
     * reservation lands at DELIVERY_SCHEDULING + FROM_STOCK too, and must still be refused. */
    @Test
    void cutOff_aPersonReservedAllStockAfterTheConfirmation_evenThoughItLooksLikeTheAutoRoute() {
        Confirmed c = confirmedDeal();
        List<StockReservationRequest.Line> all = jdbc.queryForList(
            "SELECT item_id, qty FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", c.deal())).stream()
            .map(r -> new StockReservationRequest.Line(((Number) r.get("item_id")).longValue(), (BigDecimal) r.get("qty"), null))
            .toList();
        ticketService.reserveStock(c.deal(), new StockReservationRequest(all), ceoActor);
        TicketSummaryDto s = summary(c.deal());
        assertThat(s.salesStage()).as("fixture").isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(s.fulfillmentStatus()).as("fixture").isEqualTo(FulfilmentStatus.FROM_STOCK);

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_aProcurementSourceDecisionAfterTheConfirmation() {
        Confirmed c = confirmedDeal();
        List<ProcurementSourceRequest.Line> lines = jdbc.queryForList(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", c.deal()), Long.class).stream()
            .map(id -> new ProcurementSourceRequest.Line(id, false)).toList();
        ticketService.decideProcurementSource(c.deal(), new ProcurementSourceRequest(lines), salesActor);
        assertThat(eventCount(c.deal(), TicketEventKind.PROCUREMENT_SOURCE_DECIDED)).as("fixture").isEqualTo(1);

        assertRefusedAndNothingWritten(c);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 8. Quotation / state refusals
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void neverConfirmedQuotation_is409() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        Map<String, Object> before = state(deal);

        assertStatus(HttpStatus.CONFLICT, () -> confirmer.undoOrderConfirmation(approved.id(), null, salesActor));

        assertThat(state(deal)).isEqualTo(before);
    }

    @Test
    void aDifferentQuotationThanTheConfirmedOne_is409() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto v1 = approvedDirectQuotation(deal, salesActor);
        DealQuotationDto clone = quotationService.createReorder(v1.id(), salesActor);
        confirmer.confirmOrderFromDirectQuotation(v1.id(), salesActor);
        Map<String, Object> before = state(deal);

        assertStatus(HttpStatus.CONFLICT, () -> confirmer.undoOrderConfirmation(clone.id(), null, salesActor));

        assertThat(state(deal)).isEqualTo(before);
        assertThat(eventCount(deal, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
    }

    @Test
    void pricingRequestOriginQuotation_is409() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        jdbc.update("UPDATE sales.quotation SET origin = 'PRICING_REQUEST' WHERE quotation_id = :id",
            Map.of("id", approved.id()));
        Map<String, Object> before = state(deal);

        assertStatus(HttpStatus.CONFLICT, () -> confirmer.undoOrderConfirmation(approved.id(), null, salesActor));

        assertThat(state(deal)).isEqualTo(before);
    }

    @Test
    void missingQuotation_is404() {
        assertStatus(HttpStatus.NOT_FOUND, () -> confirmer.undoOrderConfirmation(987_654_321L, null, salesActor));
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 9. Authz: who cannot reach it (403 before any 409), who can
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwningSales_ceo_import_account_areRefused403_andNothingIsWritten() {
        Confirmed confirmed = confirmedDeal();
        long unconfirmedDeal = pipelineTicket(salesActor);
        DealQuotationDto unconfirmed = approvedDirectQuotation(unconfirmedDeal, salesActor);
        Map<String, Object> confirmedBefore = state(confirmed.deal());
        Map<String, Object> unconfirmedBefore = state(unconfirmedDeal);

        for (UserPrincipal outsider : List.of(otherSalesActor, ceoActor, importActor, accountActor)) {
            assertThatThrownBy(() -> confirmer.undoOrderConfirmation(confirmed.quotation().id(), "x", outsider))
                .as(outsider.role() + " on a confirmed order")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> confirmer.undoOrderConfirmation(unconfirmed.id(), "x", outsider))
                .as(outsider.role() + " where the state alone would be a 409: 403 first")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(state(confirmed.deal())).isEqualTo(confirmedBefore);
        assertThat(state(unconfirmedDeal)).isEqualTo(unconfirmedBefore);
    }

    @Test
    void salesManager_canUndoARepsOrder() {
        Confirmed c = confirmedDeal();

        confirmer.undoOrderConfirmation(c.quotation().id(), null, salesManagerActor);

        assertThat(summary(c.deal()).status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(summary(c.deal()).createdById()).as("ownership never moves").isEqualTo(salesActor.id());
    }

    @Test
    void quotationGrantHolder_canUndoARepsOrder() {
        Confirmed c = confirmedDeal();

        confirmer.undoOrderConfirmation(c.quotation().id(), null, grantedQcActor);

        assertThat(summary(c.deal()).status()).isEqualTo(TicketStatus.DRAFT);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 10. Idempotent replay
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void replay_returnsTheSameState_andWritesNothingMore() {
        Confirmed c = confirmedDeal();
        PromoteToDealResultDto first = confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", salesActor);
        Map<String, Object> afterFirst = state(c.deal());

        PromoteToDealResultDto replay = confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", salesActor);

        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThat(replay.ticket().status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(replay.ticket().salesStage()).isEqualTo(first.ticket().salesStage());
        assertThat(state(c.deal())).isEqualTo(afterFirst);
        assertThat(eventCount(c.deal(), TicketEventKind.ORDER_CONFIRMATION_UNDONE)).isEqualTo(1);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // 11. Atomicity — through the real @Transactional proxy
    // ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A failure injected at a write the undo makes (a trigger that RAISEs) rolls every earlier write back. Three
     * injection points so that whichever write the implementation does LAST is covered: the undo event insert, the
     * promotion event delete, the ticket status reset. The exception must be the database's, never the not-yet-written
     * undo itself.
     */
    @ParameterizedTest
    @ValueSource(strings = {"undoEventInsert", "promotionEventDelete", "ticketStatusReset"})
    void aFailureLateInTheUndo_rollsTheWholeUndoBack(String failurePoint) {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedZeroDepositQuotation(deal); // exercises policy flip, items, events
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        Map<String, Object> before = state(deal);
        String trigger = switch (failurePoint) {
            case "undoEventInsert" -> "BEFORE INSERT ON sales.ticket_event FOR EACH ROW"
                + " WHEN (NEW.kind = 'ORDER_CONFIRMATION_UNDONE')";
            case "promotionEventDelete" -> "BEFORE DELETE ON sales.ticket_event FOR EACH ROW"
                + " WHEN (OLD.kind = 'DEAL_PROMOTED_FROM_QUOTATION')";
            default -> "BEFORE UPDATE ON sales.ticket FOR EACH ROW"
                + " WHEN (OLD.status = 'quotation_issued' AND NEW.status = 'draft')";
        };
        jdbc.getJdbcOperations().execute("CREATE FUNCTION sales.fail_undo_test() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'injected undo failure'; END $$");
        jdbc.getJdbcOperations().execute("CREATE TRIGGER trg_fail_undo_test " + trigger
            + " EXECUTE FUNCTION sales.fail_undo_test()");

        assertThatThrownBy(() -> confirmer.undoOrderConfirmation(approved.id(), "กดผิด", salesActor))
            .as("the injected database failure, not an ApiException")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("injected undo failure");

        assertThat(state(deal)).as("nothing of the undo survived").isEqualTo(before);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────────────────

    /** An ordinary deal with an APPROVED 50% direct quotation, order confirmed by the owning rep. */
    private Confirmed confirmedDeal() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        return new Confirmed(deal, approved);
    }

    private void assertRefusedAndNothingWritten(Confirmed c) {
        Map<String, Object> before = state(c.deal());
        assertThatThrownBy(() -> confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).as("a Thai message of its own").isNotBlank().containsPattern("[\\u0E00-\\u0E7F]");
            });
        assertThat(state(c.deal())).as("a refusal writes nothing").isEqualTo(before);
        assertThat(eventCount(c.deal(), TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
        assertThat(eventCount(c.deal(), TicketEventKind.ORDER_CONFIRMATION_UNDONE)).isZero();
    }

    /** Everything an undo may touch (or must not), for before/after equality. */
    private Map<String, Object> state(long deal) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT status, payment_status, sales_stage, fulfillment_status, lifecycle, deposit_policy,
                   deposit_policy_reason, deposit_policy_set_by, quotation_only, procurement_source_decided_at
              FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", deal)));
        m.put("items", jdbc.queryForList(
            "SELECT item_id, qty, qty_from_stock, qty_delivered FROM sales.ticket_item WHERE ticket_id = :id ORDER BY item_id",
            Map.of("id", deal)));
        m.put("events", jdbc.queryForList(
            "SELECT event_id, kind, from_status, to_status FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", deal)));
        m.put("depositNotices", jdbc.queryForList(
            "SELECT deposit_notice_id, status FROM sales.deposit_notice WHERE ticket_id = :id ORDER BY 1", Map.of("id", deal)));
        m.put("depositNoticeItems", jdbc.queryForList("""
            SELECT i.item_id FROM sales.deposit_notice_item i JOIN sales.deposit_notice d USING (deposit_notice_id)
             WHERE d.ticket_id = :id ORDER BY 1
            """, Map.of("id", deal)));
        m.put("quotations", jdbc.queryForList(
            "SELECT quotation_id, doc_status, origin FROM sales.quotation WHERE ticket_id = :id ORDER BY 1", Map.of("id", deal)));
        m.put("parties", jdbc.queryForList(
            "SELECT party_id, status FROM sales.deal_party WHERE ticket_id = :id ORDER BY 1", Map.of("id", deal)));
        return m;
    }

    private List<String> eventKinds(long deal) {
        return jdbc.queryForList("SELECT kind FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", deal), String.class);
    }

    private String undoNote(long deal) {
        return jdbc.queryForObject("SELECT message FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            Map.of("id", deal, "kind", TicketEventKind.ORDER_CONFIRMATION_UNDONE), String.class);
    }

    private long firstItemId(long deal) {
        return jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :id ORDER BY sort_order LIMIT 1",
            Map.of("id", deal), Long.class);
    }

    private int noticeItemCount(long noticeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.deposit_notice_item WHERE deposit_notice_id = :id",
            Map.of("id", noticeId), Integer.class);
    }

    private int bellRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification", Map.of(), Integer.class);
    }

    private int count(String sql, long deal) {
        return jdbc.queryForObject(sql, Map.of("id", deal), Integer.class);
    }

    /** The quotation's deal_party (V204): the row the quotation already points at, else one inserted for it. */
    private long partyOf(long deal, long quotationId) {
        Long party = jdbc.queryForObject("SELECT party_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class);
        if (party == null) {
            party = jdbc.queryForObject("""
                INSERT INTO sales.deal_party (ticket_id, customer_id, role, status, is_headline)
                VALUES (:t, :c, 'OWNER', 'QUOTED', FALSE) RETURNING party_id
                """, Map.of("t", deal, "c", customer.id()), Long.class);
            jdbc.update("UPDATE sales.quotation SET party_id = :p WHERE quotation_id = :id",
                Map.of("p", party, "id", quotationId));
        }
        return party;
    }

    private String partyStatus(long partyId) {
        return jdbc.queryForObject("SELECT status FROM sales.deal_party WHERE party_id = :id", Map.of("id", partyId),
            String.class);
    }
}
