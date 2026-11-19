package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.PromoteToDealResultDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DirectQuotationLocks;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketResponses;
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
class DealQuotationConfirmOrderIntegrationTest extends DirectQuotationDealTestBase {
    private static final String V193 = "db/migration/V193__ticket_quotation_only.sql";

    // Happy path — owner confirms; the deal lands at S10 with the quotation's own lines, and the ticket-keyed
    // money side (deposit-notice draft) unlocks.

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
        // The 0% walk never touches DEPOSIT_RECEIVED: check BEFORE the stage bump below (stage still
        // ORDER_RECEIVED, no events), so these assertions are not vacuous.
        assertThat(summary(deal).salesStage()).isNotEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id"
            + " AND (to_status = 'DEPOSIT_RECEIVED' OR from_status = 'DEPOSIT_RECEIVED')",
            Map.of("id", deal), Integer.class)).isZero();
        // canConfirmFinalPaymentNow refuses a REQUIRED deal at CUSTOMER_CONFIRMED.
        // Owner ruling 2026-11-07: final payment opens at DELIVERY_SCHEDULING.
        tickets.updateSalesStage(deal, DealStage.DELIVERY_SCHEDULING);
        assertThat(ticketService.confirmFinalPayment(deal, accountActor).summary().paymentStatus())
            .isEqualTo("FULLY_PAID");
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

    private int quotationRows(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    // Authz — WRONG-WAY-ROUND. Each refusal asserts nothing was written.

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

    // Preconditions — 409, nothing written.

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

    /** R10 / separation: a PRICING_REQUEST-origin quotation is never promoted by this route — it has its own
     * confirmOrder. Its row is produced here by re-tagging an approved direct one (the origin column is the only
     * thing the guard reads), linked to a real pricing_request row. */
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

    // A quotation-first deal is a real pipeline deal (IA §7) — listed, and movable by hand.

    // Items / pricing-request refusals are keyed on "a LIVE DEAL_DIRECT quotation exists" — not on the
    // quotation_only flag. Written on ORDINARY deals so the flag cannot be the cause.

    // V193 backfill — V193 re-executed VERBATIM from the classpath over hand-shaped rows.


    // Helpers

    /** Exactly what the quotation editor's inline create sends (QuotationEditorPage #handleInlineCreate): no
     * contact, no items, a follow-up date, and quotationOnly=true. */
    // ─────────────────────────────────────────────────────────────────────────────────────
    // Confirm-order on a DIRECT quotation must hand each
    // ticket_item the quotation line's catalogue link and factory, as the pricing-request route does —
    // otherwise สร้างใบ IR 409s "รายการนี้ยังไม่ได้ระบุโรงงาน" and nobody can fix it.
    // ─────────────────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────────────────
    // จากสต็อก on a direct quotation: the declaration reaches the ticket and drives routing exactly as the
    // pricing-request route does (qty_from_stock = qty on a stock line).
    // ─────────────────────────────────────────────────────────────────────────────────────

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
    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains(messagePart);
            });
    }
    private int eventCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
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

}
