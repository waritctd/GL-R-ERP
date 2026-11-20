package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Express receipts upload and the unmatched queue (spec 2026-11-15 §7.8, V213 + V214). */
public final class ReceiptImportDtos {
    private ReceiptImportDtos() {}

    public static final String UNMATCHED = "UNMATCHED";
    public static final String RESOLVED = "RESOLVED";
    public static final String IGNORED = "IGNORED";

    /**
     * One upload's outcome; matched + matchedManual + duplicates + unmatched + nonCash + invalid + failed = totalRows.
     * <ul>
     *   <li>{@code possibleManualDuplicates} — the row MAY be a receipt account already typed by hand (same deal,
     *       same amount, ±7 days, not yet linked): queued as POSSIBLE_MANUAL_DUPLICATE with nothing written, for account
     *       to decide (review N1 — it is never linked silently);</li>
     *   <li>{@code duplicates} — its document number is already a receipt (any source), a linked manual receipt, or
     *       queued — or repeats earlier in the same file; nothing written;</li>
     *   <li>{@code nonCash} — not a cash document type (an IV / AI …), "ไม่ใช่ใบเสร็จรับเงิน", nothing written (H4);</li>
     *   <li>{@code invalid} — a row the parser refused; {@code failed} — a row whose write failed unexpectedly (M3).
     *       Both are listed in {@code errors} by line (only on the upload response, not on {@code latest}).</li>
     * </ul>
     */
    public record ImportSummary(long importId, String fileName, Instant uploadedAt, String uploadedByName,
                                int totalRows, int matched, BigDecimal matchedAmount, int possibleManualDuplicates,
                                int duplicates, BigDecimal duplicateAmount, int unmatched, BigDecimal unmatchedAmount,
                                int nonCash, int invalid, int failed, List<ReceiptFileParser.RowError> errors) {}

    /** The account landing card: whether the upload is on, the last upload (null before any), rows still waiting. */
    public record LatestImport(boolean uploadEnabled, ImportSummary latest, int openUnmatched) {}

    /**
     * A deal an unmatched row could go on. {@code hint}: "เลขอ้างอิงตรง", "ลูกค้า + ยอดตรง" or "ลูกค้าตรง".
     * {@code expectedAmount} / {@code expectedWeek} / {@code expectedKind}: its OPEN expected payment nearest the
     * row's amount (the amount still to come), when it has one.
     */
    public record CandidateDto(long ticketId, String title, String customerName, long ownerId, String ownerName,
                               String hint, BigDecimal expectedAmount, LocalDate expectedWeek, String expectedKind) {}

    /**
     * One row of account's queue. {@code reason} is ReceiptMatcher.Reason or POSSIBLE_MANUAL_DUPLICATE; for the latter
     * {@code possibleManual} is the hand-typed receipt it may be (review N1). {@code candidates}: deals worth offering.
     */
    public record UnmatchedRowDto(long rowId, long importId, String docNo, LocalDate docDate, String customerName,
                                  BigDecimal amount, String reference, String reason, String status,
                                  Long resolvedTicketId, Instant managerFlaggedAt, Long possibleManualReceiptId,
                                  PossibleManual possibleManual, List<CandidateDto> candidates) {}

    /**
     * The MANUAL receipt (or the TAX_INVOICE one, {@code source}, with its {@code invoiceNumber} and delivery {@code roundNo}) an Express row may duplicate: on which deal, what kind, how much, when, typed by whom.
     * {@code alreadyLinked}: it is already linked to an Express number (another row), so "same as manual" would 409.
     */
    public record PossibleManual(long receiptId, long ticketId, String kind, BigDecimal amount, Instant receivedAt,
                                 String recordedByName, boolean alreadyLinked, String source, String invoiceNumber,
                                 Integer roundNo) {}

    /** A row account sent to the sales manager to look at (read-only: no deals to pick, no actions). */
    public record FlaggedRowDto(long rowId, String docNo, LocalDate docDate, String customerName, BigDecimal amount,
                                String reference, Instant managerFlaggedAt) {}

    /** sales_manager / ceo read-only view (review H3): how many rows wait, plus only what account flagged. */
    public record ManagerView(int openUnmatched, List<FlaggedRowDto> flagged) {}

    /** The deal picker (review M5): a deal inside account's finance scope. */
    public record DealOption(long ticketId, String code, String title, String customerName, String ownerName) {}

    /** One line of a split (review M7). */
    public record Allocation(Long ticketId, BigDecimal amount) {}

    /** POST …/unmatched/{rowId}/resolve: one deal, or a split whose lines sum exactly to the row. */
    public record ResolveRequest(Long ticketId, List<Allocation> allocations) {}
}
