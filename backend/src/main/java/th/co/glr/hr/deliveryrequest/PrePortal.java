package th.co.glr.hr.deliveryrequest;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ApprovedQuotationDto;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * A deal ordered before the portal (sales.ticket.pre_portal) has no confirmed order in it. The first document that needs
 * one (request send, IR, deposit notice, remaining invoice) confirms it here by the existing confirm, so the ticket items,
 * payable and document chain exist, with no second item-writing path. The customer's deposit was paid outside the portal
 * and is on the deal as a PRE_PORTAL receipt, so the confirm leaves the deposit policy NOT_REQUIRED: no gate waits for one.
 *
 * <p>Every caller has already authorized its actor for its own action; the confirm here does not re-check it (the CEO may
 * create the first IR). Lock order: the confirm takes the advisory lock then the ticket row, so the pre_portal flag (a
 * ticket row write) is set only after it.
 */
@Component
public class PrePortal {
    private final TicketRepository tickets;
    private final DeliveryRequestRepository rounds;
    private final DealQuotationService quotations;
    private final TicketService ticketService;

    public PrePortal(TicketRepository tickets, DeliveryRequestRepository rounds, DealQuotationService quotations,
                     TicketService ticketService) {
        this.tickets = tickets;
        this.rounds = rounds;
        this.quotations = quotations;
        this.ticketService = ticketService;
    }

    /** No-op unless the deal is pre_portal and not yet confirmed. {@code quotationId} null = the newest approved one. */
    @Transactional
    public void ensureOrderConfirmed(long ticketId, Long quotationId, UserPrincipal actor) {
        if (tickets.isPrePortal(ticketId)) {
            if (confirmIfNeeded(ticketId, quotationId, actor)) {
                requireDepositWithinTotal(ticketId);
            }
        }
    }

    /**
     * An old deal's first request: confirms its order from {@code quotationId}, then marks it pre-portal with the deposit
     * the customer already paid ({@link TicketService#becomePrePortal}). The deposit is validated first (400), and
     * everything rolls back if it exceeds the quotation total (409).
     */
    @Transactional
    public void confirmAndMark(long ticketId, Long quotationId, BigDecimal deposit, java.time.LocalDate receivedOn,
                               UserPrincipal actor) {
        TicketService.requirePreDeposit(deposit, receivedOn);
        confirmIfNeeded(ticketId, quotationId, actor);
        ticketService.becomePrePortal(ticketId, deposit, receivedOn, actor);
        requireDepositWithinTotal(ticketId);
    }

    /** True when this call confirmed the order. */
    private boolean confirmIfNeeded(long ticketId, Long quotationId, UserPrincipal actor) {
        if (tickets.hasPromotionFromQuotationEvent(ticketId) || rounds.hasAcceptedPricingQuotation(ticketId)) {
            return false;
        }
        Long chosen = quotationId;
        if (chosen == null) {
            chosen = rounds.approvedQuotations(ticketId).stream().filter(q -> "APPROVED".equals(q.docStatus()))
                .map(ApprovedQuotationDto::id).reduce((a, b) -> b).orElse(null);
        }
        if (chosen == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องมีใบเสนอราคาที่อนุมัติแล้ว");
        }
        String before = tickets.findSummaryById(ticketId).orElseThrow().salesStage();
        quotations.confirmOrderAuthorizedByCaller(chosen, actor);
        var confirmed = tickets.findSummaryById(ticketId).orElseThrow();
        if (DealStage.indexOf(confirmed.salesStage()) < DealStage.indexOf(before)) {
            tickets.updateSalesStage(ticketId, before);
            tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.STAGE_CHANGED, confirmed.salesStage(), before,
                "คงขั้นเดิมของงานที่เดินมาก่อนใช้ระบบ");
        }
        if (!DepositPolicy.NOT_REQUIRED.equals(confirmed.depositPolicy())) {
            tickets.updateDepositPolicy(ticketId, DepositPolicy.NOT_REQUIRED, TicketRepository.PRE_PORTAL_POLICY_REASON, actor.id());
            // The note names the policy it replaced: the undo of this confirmation puts it back from here.
            tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.POLICY_CHANGED, before, before,
                TicketRepository.prePortalPolicyNote(confirmed.depositPolicy()));
        }
        return true;
    }

    /**
     * The money received (signed: account's ADJUSTMENT lowers it) must not exceed what the confirmed quotation owes (VAT
     * included) when the rep entered a deposit; account corrects an error with an ADJUSTMENT.
     */
    private void requireDepositWithinTotal(long ticketId) {
        BigDecimal payable = tickets.payableAmount(ticketId);
        if (tickets.sumPrePortalReceipts(ticketId).signum() > 0 && tickets.sumPaid(ticketId).compareTo(payable) > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มัดจำที่รับแล้วก่อนใช้ระบบเกินยอดรวมใบเสนอราคา (ยอดรวม " + payable.toPlainString()
                    + " บาท) ให้ฝ่ายบัญชีปรับยอด");
        }
    }
}
