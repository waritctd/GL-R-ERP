package th.co.glr.hr.importrequest;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.RelatedDocumentType;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Closes out an IR line that points at no deal line (goods that will not be on this deal) and takes it back. Import or the CEO,
 * any IR step. The row, its lots and its receipts stay; the line stops holding the deal's delivery and stops being wanted. Lock
 * order as everywhere: the deal's rounds, its import requests, then the ticket. Reversible until the deal is fully delivered.
 * A deal with no deal lines is never completed by this (completeDeliveryIfDone needs a line): it stays a human hold.
 */
@Service
public class IrLineExitService {
    private final ImportRequestRepository stored;
    private final ShipmentRoundService rounds;
    private final TicketService ticketService;
    private final TicketRepository tickets;

    public IrLineExitService(ImportRequestRepository stored, ShipmentRoundService rounds, TicketService ticketService, TicketRepository tickets) {
        this.stored = stored;
        this.rounds = rounds;
        this.ticketService = ticketService;
        this.tickets = tickets;
    }

    @Transactional
    public void closeOut(long lineId, String reason, UserPrincipal actor) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุเหตุผลที่ปิดรายการนี้ (ไม่เกิน 255 ตัวอักษร)");
        }
        Locked locked = lockLine(lineId, actor);
        ImportRequestRepository.LineToLink line = locked.line();
        if (line.closedOut()) {
            return; // a double submit
        }
        if (line.ticketItemId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ผูกกับรายการของงานแล้ว — ปิดได้เฉพาะรายการที่ไม่อยู่ในงาน");
        }
        stored.closeOutLine(lineId, why, actor.id());
        finish(locked, "ปิดรายการ " + line.code() + " ของใบขอซื้อ " + docNumber(line) + " นอกงานนี้: " + why, actor, true);
    }

    @Transactional
    public void reopen(long lineId, UserPrincipal actor) {
        Locked locked = lockLine(lineId, actor);
        ImportRequestRepository.LineToLink line = locked.line();
        if (!line.closedOut()) {
            return; // a double submit
        }
        String fulfilment = ticketService.fulfilmentStatusOf(line.ticketId());
        if (FulfilmentStatus.FULLY_DELIVERED.equals(fulfilment)) {
            throw new ApiException(HttpStatus.CONFLICT, "งานส่งมอบครบแล้ว เปิดรายการกลับในระบบไม่ได้ — แจ้งผู้ดูแลระบบ");
        }
        stored.reopenLine(lineId);
        finish(locked, "เปิดรายการ " + line.code() + " ของใบขอซื้อ " + docNumber(line) + " กลับมาเป็นรายการค้าง", actor, false);
    }

    private record Locked(ImportRequestRepository.LineToLink line, List<IrLock> irs) {}

    private Locked lockLine(long lineId, UserPrincipal actor) {
        if (!"import".equals(actor.role()) && !"ceo".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        ImportRequestRepository.LineToLink probe = stored.findLine(lineId);
        if (probe == null || probe.ticketId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการในใบขอซื้อของงาน");
        }
        List<IrLock> irs = rounds.lockTicketIrs(probe.ticketId());
        ImportRequestRepository.LineToLink line = stored.findLine(lineId);
        if (!ImportRequestStatus.ISSUED.equals(line.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ทำได้เฉพาะรายการของใบขอซื้อที่ออกแล้ว");
        }
        if (!DealLifecycle.ACTIVE.equals(tickets.lockTicketForUpdate(probe.ticketId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนี้ไม่ได้อยู่ในสถานะที่ดำเนินการได้");
        }
        return new Locked(line, irs);
    }

    private void finish(Locked locked, String message, UserPrincipal actor, boolean completeAfter) {
        ImportRequestRepository.LineToLink line = locked.line();
        tickets.addEventWithDocument(line.ticketId(), actor.id(), actor.name(), TicketEventKind.IMPORT_STEP_ADVANCED, null, null,
            message, RelatedDocumentType.IMPORT_REQUEST, line.importRequestId());
        rounds.rederiveAfterLineExit(locked.irs(), line.importRequestId(), actor);
        if (completeAfter) {
            ticketService.completeDeliveryAfterIrRevision(line.ticketId(), actor);
        }
    }

    private String docNumber(ImportRequestRepository.LineToLink line) {
        return stored.findById(line.importRequestId()).map(ImportRequestDtos.ImportRequestDto::docNumber).orElse("");
    }
}
