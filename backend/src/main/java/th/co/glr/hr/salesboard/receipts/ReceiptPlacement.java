package th.co.glr.hr.salesboard.receipts;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.ExpectedPaymentRepository;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.QueuedRow;
import th.co.glr.hr.ticket.TicketService;

/**
 * The one transactional unit that puts an Express row's money on a deal: the receipt
 * ({@link TicketService#recordExpressReceipt}, which re-checks the role and, for a rep, ownership), and the deal's
 * nearest OPEN expected payment marked RECEIVED. Its own bean so each row commits or rolls back alone — the upload
 * loop is deliberately not one transaction, and a refusal here (409 overpayment, deal not active) rolls back only
 * this row.
 */
@Service
public class ReceiptPlacement {
    private final TicketService tickets;
    private final ExpectedPaymentRepository expected;
    private final ReceiptImportRepository imports;

    public ReceiptPlacement(TicketService tickets, ExpectedPaymentRepository expected, ReceiptImportRepository imports) {
        this.tickets = tickets;
        this.expected = expected;
        this.imports = imports;
    }

    /** Writes the receipt and marks the expected payment; returns the receipt id. */
    @Transactional
    public long place(long ticketId, ParsedReceipt row, UserPrincipal actor) {
        long receiptId = tickets.recordExpressReceipt(ticketId, row.amount(),
            ReceiptImportRepository.receivedAt(row.docDate()).toInstant(), row.docNo().trim(), row.customerName(),
            actor);
        expected.markNearestOpenReceived(ticketId, row.amount(), row.docDate(), receiptId);
        return receiptId;
    }

    /** Attaches a queued row to {@code ticketId}: locks it, places it, marks it RESOLVED — or 409 if already handled. */
    @Transactional
    public void resolve(long rowId, long ticketId, UserPrincipal actor) {
        QueuedRow row = imports.lockRow(rowId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
        if (!ReceiptImportDtos.UNMATCHED.equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้จับคู่หรือข้ามไปแล้ว");
        }
        long receiptId = place(ticketId, row.receipt(), actor);
        imports.markResolved(rowId, ticketId, receiptId, actor.id());
    }
}
