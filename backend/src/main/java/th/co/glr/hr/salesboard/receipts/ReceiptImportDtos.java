package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Express receipts upload and the unmatched queue (spec 2026-11-15 §7.8, V213). */
public final class ReceiptImportDtos {
    private ReceiptImportDtos() {}

    public static final String UNMATCHED = "UNMATCHED";
    public static final String RESOLVED = "RESOLVED";
    public static final String IGNORED = "IGNORED";

    /**
     * One upload's outcome. {@code totalRows} = every non-blank data row; matched + duplicates + unmatched + invalid =
     * totalRows. {@code duplicates} are rows whose Express document number is already a receipt (any source) or
     * already queued — including a repeat inside the same file — and so wrote nothing. {@code errors} lists the invalid
     * rows by line (only on the upload response, not on {@code latest}).
     */
    public record ImportSummary(long importId, String fileName, Instant uploadedAt, String uploadedByName,
                                int totalRows, int matched, BigDecimal matchedAmount, int duplicates,
                                BigDecimal duplicateAmount, int unmatched, BigDecimal unmatchedAmount, int invalid,
                                List<ReceiptFileParser.RowError> errors) {}

    /** The account landing card: the last upload (null before any) and how many rows still wait in the queue. */
    public record LatestImport(ImportSummary latest, int openUnmatched) {}

    /**
     * A deal an unmatched row could go on. {@code hint}: "เลขอ้างอิงตรง" (the reference names one of its documents),
     * "ลูกค้า + ยอดตรง" (customer, and an OPEN expected payment near the amount) or "ลูกค้าตรง" (customer only).
     * {@code expectedAmount} / {@code expectedWeek} / {@code expectedKind}: its OPEN expected payment nearest the
     * row's amount, when it has one.
     */
    public record CandidateDto(long ticketId, String title, String customerName, long ownerId, String ownerName,
                               String hint, BigDecimal expectedAmount, LocalDate expectedWeek, String expectedKind) {}

    /**
     * One row of the queue. {@code reason} is why the matcher left it (ReceiptMatcher.Reason). {@code candidates} are
     * the deals worth offering — for a sales rep only their own deals, and a rep sees a row only when it has one.
     */
    public record UnmatchedRowDto(long rowId, long importId, String docNo, LocalDate docDate, String customerName,
                                  BigDecimal amount, String reference, String reason, String status,
                                  Long resolvedTicketId, List<CandidateDto> candidates) {}

    /** POST …/unmatched/{rowId}/resolve. */
    public record ResolveRequest(Long ticketId) {}
}
