package th.co.glr.hr.salesboard.receipts;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.Allocation;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.QueuedRow;
import th.co.glr.hr.ticket.TicketService;

/**
 * The transactional unit that puts an Express row's money on a deal: {@link TicketService#recordExpressReceipt}
 * (account only; it also marks expected payments through the shared rule). Its own bean so each row commits or rolls
 * back alone — the upload loop is deliberately not one transaction, and a refusal here rolls back only this row.
 */
@Service
public class ReceiptPlacement {
    private final TicketService tickets;
    private final ReceiptImportRepository imports;

    public ReceiptPlacement(TicketService tickets, ReceiptImportRepository imports) {
        this.tickets = tickets;
        this.imports = imports;
    }

    /** Writes the receipt; returns its id. Throws ExpressReceiptRefusal (409) when it cannot. */
    @Transactional
    public long place(long ticketId, ParsedReceipt row, UserPrincipal actor) {
        return tickets.recordExpressReceipt(ticketId, row.amount(), receivedAt(row), row.docNo().trim(),
            row.customerName(), actor);
    }

    /**
     * Attaches a queued row: locks it, places each allocation, marks it RESOLVED — all or nothing; 409 if it was
     * handled meanwhile. A split writes one receipt per deal, referenced "{doc no}#1", "#2", … (the Express number is
     * one document, the EXPRESS reference is unique) and one receipt_import_allocation line each (review M7).
     */
    @Transactional
    public void resolve(long rowId, List<Allocation> lines, UserPrincipal actor) {
        QueuedRow row = imports.lockRow(rowId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
        if (!ReceiptImportDtos.UNMATCHED.equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้จับคู่หรือข้ามไปแล้ว");
        }
        ParsedReceipt r = row.receipt();
        String docNo = r.docNo().trim();
        boolean split = lines.size() > 1;
        if (split && docNo.length() + 1 + String.valueOf(lines.size()).length() > ReceiptFileParser.MAX_DOC_NO) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เลขที่เอกสารยาวเกินกว่าจะแบ่งได้");
        }
        Long firstReceipt = null;
        Long firstTicket = null;
        int n = 0;
        for (Allocation a : lines) {
            String ref = split ? docNo + "#" + (++n) : docNo;
            long receiptId = tickets.recordExpressReceipt(a.ticketId(), a.amount(), receivedAt(r), ref,
                r.customerName(), actor);
            if (split) {
                imports.insertAllocation(rowId, a.ticketId(), receiptId, a.amount());
            }
            if (firstReceipt == null) {
                firstReceipt = receiptId;
                firstTicket = a.ticketId();
            }
        }
        imports.markResolved(rowId, firstTicket, firstReceipt, actor.id());
    }

    /** Links a POSSIBLE_MANUAL_DUPLICATE row's manual receipt to its Express number and resolves the row; 409 else. */
    @Transactional
    public void sameAsManual(long rowId, UserPrincipal actor) {
        QueuedRow row = imports.lockRow(rowId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
        if (!ReceiptImportDtos.UNMATCHED.equals(row.status())
            || !ReceiptImportService.POSSIBLE_MANUAL_DUPLICATE.equals(row.reason()) || row.suggestedReceiptId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ไม่ได้รอเลือกว่าเป็นรายการเดียวกับที่บันทึกเอง");
        }
        imports.lockReceipt(row.suggestedReceiptId()); // before the "already settled" check below: serialise same-money races
        var manual = imports.manualReceipt(row.suggestedReceiptId())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบรายการที่บันทึกเองนี้แล้ว"));
        if ("TAX_INVOICE".equals(manual.source()) && imports.isSettledByRow(manual.receiptId())) {
            throw new ApiException(HttpStatus.CONFLICT, "เงินก้อนนี้ของใบกำกับถูกจับคู่กับรายการ Express อื่นแล้ว");
        }
        // A tax invoice's receipt carries no Express number: "the same money" only settles this row (RESOLVED against the
        // receipt, nothing written); a void of that invoice puts the row back in the queue.
        if (("MANUAL".equals(manual.source()) || "PRE_PORTAL".equals(manual.source()))
            && imports.linkManualReceipt(manual.receiptId(), row.receipt().docNo()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการที่บันทึกเองนี้ผูกกับเลขที่ Express อื่นแล้ว");
        }
        imports.markResolved(rowId, manual.ticketId(), manual.receiptId(), actor.id());
    }

    private static Instant receivedAt(ParsedReceipt row) {
        return ReceiptImportRepository.receivedAt(row.docDate()).toInstant();
    }
}
