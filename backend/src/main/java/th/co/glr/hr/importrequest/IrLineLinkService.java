package th.co.glr.hr.importrequest;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.RelatedDocumentType;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * The stuck-deal exit: gives an ISSUED IR line that points at no deal line its deal line (import or CEO, any IR step including
 * RECEIVED). Same deal only, a live deal, and the sizes must agree when both are written. Quantities, lots, receipts and
 * history do not change. Lock order as everywhere: the deal's rounds, its import requests, then the ticket. The deal gets one
 * event, the IR re-derives (a deal line with a ไม่ได้รับของ mark changes what the line still wants), then the normal
 * goods-received rollup and the delivery-completion check. A second identical request does nothing. No customer mail.
 */
@Service
public class IrLineLinkService {
    private final ImportRequestRepository stored;
    private final ShipmentRoundService rounds;
    private final ImportRequestService irService;
    private final TicketService ticketService;
    private final TicketRepository tickets;

    public IrLineLinkService(ImportRequestRepository stored, ShipmentRoundService rounds, ImportRequestService irService,
                             TicketService ticketService, TicketRepository tickets) {
        this.stored = stored;
        this.rounds = rounds;
        this.irService = irService;
        this.ticketService = ticketService;
        this.tickets = tickets;
    }

    @Transactional
    public void link(long lineId, long ticketItemId, UserPrincipal actor) {
        if (!"import".equals(actor.role()) && !"ceo".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        ImportRequestRepository.LineToLink probe = stored.findLine(lineId);
        if (probe == null || probe.ticketId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการในใบขอซื้อของงาน");
        }
        long ticketId = probe.ticketId();
        List<IrLock> irs = rounds.lockTicketIrs(ticketId);
        String lifecycle = tickets.lockTicketForUpdate(ticketId);
        ImportRequestRepository.LineToLink line = stored.findLine(lineId);
        if (line.ticketItemId() != null) {
            if (line.ticketItemId() == ticketItemId) {
                return; // a double submit
            }
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ผูกกับรายการอื่นของงานแล้ว");
        }
        if (!ImportRequestStatus.ISSUED.equals(line.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ผูกได้เฉพาะรายการของใบขอซื้อที่ออกแล้ว");
        }
        if (!DealLifecycle.ACTIVE.equals(lifecycle)) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนี้ไม่ได้อยู่ในสถานะที่ดำเนินการได้");
        }
        if (!stored.dealItemIds(ticketId).contains(ticketItemId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รายการนี้ไม่ใช่รายการของงานเดียวกัน");
        }
        String dealSize = stored.ticketItemSize(ticketId, ticketItemId);
        if (!sameSize(line.size(), dealSize)) {
            throw new ApiException(HttpStatus.CONFLICT, "ขนาดไม่ตรงกัน (ใบขอซื้อ " + line.size() + " / งาน " + dealSize + ") — เลือกรายการของสินค้าเดียวกัน");
        }
        stored.linkLine(lineId, ticketItemId);
        ImportRequestDtos.ImportRequestDto ir = irService.requireStored(line.importRequestId());
        tickets.addEventWithDocument(ticketId, actor.id(), actor.name(), TicketEventKind.IMPORT_STEP_ADVANCED, null, null,
            "ผูกรายการ " + line.code() + " ของใบขอซื้อ " + ir.docNumber() + " เข้ากับรายการของงาน",
            RelatedDocumentType.IMPORT_REQUEST, ir.id());
        rounds.rederiveAfterNotDelivered(irs, ticketItemId, actor);
        irService.receiptCorrected(ticketId, ir.docNumber(), ir.factoryName(), actor);
        ticketService.completeDeliveryAfterIrRevision(ticketId, actor);
    }

    private static boolean sameSize(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return true; // not checkable
        }
        java.util.function.UnaryOperator<String> norm = v -> v.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s×*]", "");
        return norm.apply(a).equals(norm.apply(b));
    }
}
