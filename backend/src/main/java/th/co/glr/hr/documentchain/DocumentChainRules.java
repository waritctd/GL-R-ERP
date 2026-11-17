package th.co.glr.hr.documentchain;

import java.util.Set;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.PaymentTrack;
import th.co.glr.hr.ticket.TicketStatus;

/**
 * The document chain's rules — ใบเสนอราคา → ยืนยันคำสั่งซื้อ → ใบแจ้งยอดเงินรับมัดจำ → ใบแจ้งหนี้ส่วนที่เหลือ — as pure
 * functions of a deal's facts. The ONE place the quotation page's chain card, the deal page's เงิน tab and the
 * quotation list's chain mark get their states from: all three read them off the server ({@link DocumentChainService}),
 * never derive them in the browser.
 *
 * <p>The states are the deal page's own (DealPaymentSection's deposit and remaining-invoice steps as they stood on
 * 2026-11-17), moved here unchanged; the blocked REASONS say what the write paths refuse, so no page offers a button the
 * server would refuse:
 * <ul>
 *   <li>{@link #ORDER_NOT_CONFIRMED} on the remaining invoice = the deal is not {@code quotation_issued}:
 *       {@code DepositNoticeService#requireQuotationIssuedForRemainingInvoice}, which
 *       {@code RemainingInvoiceService#createDraft} runs. That is the guard, not the payment track: a pricing-request
 *       deal already at {@code quotation_issued} whose order is not yet confirmed is NOT refused by it (fix round 1,
 *       F-4 — the earlier comment claimed it was);</li>
 *   <li>{@link #DEAL_NOT_ACTIVE} = a paused / closed lifecycle, or a dead deal (lost, cancelled, or the legacy
 *       {@code status = 'cancelled'} on an ACTIVE lifecycle): both create paths and both issue paths run
 *       {@code requireActiveLifecycle}, and the deposit create's {@code requireApprovedTicket} refuses a cancelled
 *       status. A DRAFT is blocked the same way, since issuing it needs an active deal (fix round 1, F-2 / F-3).</li>
 * </ul>
 *
 * <p>The table both this class and mockApi.js's mirror are pinned to is
 * {@code frontend/src/features/documentChain/documentChainCases.json}.
 */
public final class DocumentChainRules {

    // ── deposit (ใบแจ้งยอดเงินรับมัดจำ) states ──
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String NOT_YET = "NOT_YET";
    public static final String TO_CREATE = "TO_CREATE";
    public static final String DRAFT = "DRAFT";
    public static final String ISSUED = "ISSUED";
    public static final String PAID = "PAID";
    // ── remaining invoice (ใบแจ้งหนี้ส่วนที่เหลือ) states, besides TO_CREATE / DRAFT / ISSUED / PAID ──
    public static final String WAITING = "WAITING";
    public static final String NOT_NEEDED = "NOT_NEEDED";

    // ── why a step cannot be taken yet (stable codes; the frontend words them) ──
    public static final String ORDER_NOT_CONFIRMED = "ORDER_NOT_CONFIRMED";
    public static final String DEPOSIT_NOT_RECEIVED = "DEPOSIT_NOT_RECEIVED";
    public static final String GOODS_NOT_READY = "GOODS_NOT_READY";
    public static final String DEAL_NOT_ACTIVE = "DEAL_NOT_ACTIVE";

    // ── the quotation list's per-row chain mark ──
    public static final String AWAITING_ORDER = "AWAITING_ORDER";
    public static final String AWAITING_DEPOSIT_NOTICE = "AWAITING_DEPOSIT_NOTICE";
    public static final String NO_DEPOSIT = "NO_DEPOSIT";
    public static final String DEPOSIT_NOTICE_ISSUED = "DEPOSIT_NOTICE_ISSUED";
    public static final String REMAINING_INVOICE_ISSUED = "REMAINING_INVOICE_ISSUED";

    /** The payment-track statuses at which the deposit is in hand (dealMoneyStatus.js DEPOSIT_PAID_STATUSES). */
    static final Set<String> DEPOSIT_PAID_STATUSES =
        Set.of(PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);

    /** When the remaining invoice becomes available (remainingInvoiceReadiness.js, unchanged). */
    static final Set<String> REMAINING_INVOICE_READY_FULFILMENT =
        Set.of("GOODS_RECEIVED", "FROM_STOCK", "PARTIALLY_DELIVERED", "FULLY_DELIVERED");

    /** The deal facts every state is a function of: the deal's own columns, plus which document rows exist. */
    public record Facts(
        String status, String lifecycle, String depositPolicy, String paymentStatus, String fulfillmentStatus,
        boolean noticeDraft, boolean noticeIssued, boolean invoiceDraft, boolean invoiceIssued
    ) {}

    private DocumentChainRules() {}

    /** The order is confirmed once the payment track has started — both confirm routes (direct quotation and pricing
     * request) set {@code payment_status = CUSTOMER_CONFIRMED}. */
    public static boolean orderConfirmed(Facts f) {
        return f.paymentStatus() != null;
    }

    /** An ACTIVE lifecycle on a deal that is not dead: what requireActiveLifecycle (and, for a cancelled status,
     * requireApprovedTicket / requireQuotationIssuedForRemainingInvoice) let a document write through. */
    private static boolean active(Facts f) {
        return DealLifecycle.ACTIVE.equals(f.lifecycle()) && !dead(f);
    }

    public static String depositState(Facts f) {
        if (DepositPolicy.bypassesDepositNotice(f.depositPolicy())) return NOT_REQUIRED;
        if (f.paymentStatus() != null && DEPOSIT_PAID_STATUSES.contains(f.paymentStatus())) return PAID;
        if (f.noticeIssued() || PaymentTrack.DEPOSIT_NOTICE_ISSUED.equals(f.paymentStatus())) return ISSUED;
        if (f.noticeDraft()) return DRAFT;
        if (orderConfirmed(f)) return TO_CREATE;
        return NOT_YET;
    }

    /** Why the deposit notice cannot be created/issued now; null when it can (or when there is nothing to do). */
    public static String depositBlockedReason(Facts f) {
        String state = depositState(f);
        if ((TO_CREATE.equals(state) || DRAFT.equals(state)) && !active(f)) return DEAL_NOT_ACTIVE;
        return null;
    }

    private static boolean depositDone(Facts f) {
        String state = depositState(f);
        return NOT_REQUIRED.equals(state) || PAID.equals(state);
    }

    public static String remainingState(Facts f) {
        boolean fullyPaid = PaymentTrack.FULLY_PAID.equals(f.paymentStatus());
        if (f.invoiceIssued()) return fullyPaid ? PAID : ISSUED;
        if (f.invoiceDraft()) return DRAFT;
        if (fullyPaid) return NOT_NEEDED;
        return depositDone(f) ? TO_CREATE : WAITING;
    }

    /** Why the remaining invoice cannot be created (or its DRAFT issued) now; null when it can, or when it is issued. */
    public static String remainingBlockedReason(Facts f) {
        String state = remainingState(f);
        boolean quotationIssued = TicketStatus.QUOTATION_ISSUED.equals(f.status());
        if (WAITING.equals(state)) {
            if (dead(f)) return DEAL_NOT_ACTIVE;
            return quotationIssued ? DEPOSIT_NOT_RECEIVED : ORDER_NOT_CONFIRMED;
        }
        if (DRAFT.equals(state)) return active(f) ? null : DEAL_NOT_ACTIVE;
        if (!TO_CREATE.equals(state)) return null;
        if (!active(f)) return DEAL_NOT_ACTIVE;
        if (!quotationIssued) return ORDER_NOT_CONFIRMED;
        boolean ready = f.fulfillmentStatus() != null && REMAINING_INVOICE_READY_FULFILMENT.contains(f.fulfillmentStatus());
        return ready ? null : GOODS_NOT_READY;
    }

    private static boolean dead(Facts f) {
        return DealLifecycle.CLOSED_LOST.equals(f.lifecycle()) || DealLifecycle.CANCELLED.equals(f.lifecycle())
            || TicketStatus.CANCELLED.equals(f.status());
    }

    /**
     * The quotation list's chain mark for an APPROVED direct quotation ({@code quotationId}) on this deal, or null where
     * a mark would not be true and useful: a lost/cancelled deal, a deal confirmed from ANOTHER quotation or some other
     * way, or an unconfirmed deal that can no longer take the order (paused).
     *
     * @param promoted the deal has a confirm-order-from-quotation event
     * @param confirmedQuotationId the quotation that event names
     */
    public static String listStage(Facts f, boolean promoted, Long confirmedQuotationId, long quotationId) {
        if (dead(f)) return null;
        if (!promoted) {
            return TicketStatus.DRAFT.equals(f.status()) && active(f) ? AWAITING_ORDER : null;
        }
        if (confirmedQuotationId == null || confirmedQuotationId != quotationId) return null;
        String remaining = remainingState(f);
        if (ISSUED.equals(remaining) || PAID.equals(remaining)) return REMAINING_INVOICE_ISSUED;
        String deposit = depositState(f);
        if (ISSUED.equals(deposit) || PAID.equals(deposit)) return DEPOSIT_NOTICE_ISSUED;
        if (NOT_REQUIRED.equals(deposit)) return NO_DEPOSIT;
        return AWAITING_DEPOSIT_NOTICE;
    }
}
