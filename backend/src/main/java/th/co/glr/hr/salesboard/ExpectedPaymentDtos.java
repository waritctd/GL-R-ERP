package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Expected payments — a rep-entered cash schedule per deal (spec 2026-11-15 §7.7, V212 {@code sales.expected_payment}). */
public final class ExpectedPaymentDtos {
    private ExpectedPaymentDtos() {}

    public static final String DEPOSIT = "DEPOSIT";
    public static final String ON_DELIVERY = "ON_DELIVERY";
    public static final String BALANCE = "BALANCE";
    public static final Set<String> KINDS = Set.of(DEPOSIT, ON_DELIVERY, BALANCE);

    public static final String OPEN = "OPEN";
    public static final String RECEIVED = "RECEIVED";
    public static final String CANCELLED = "CANCELLED";

    /**
     * {@code expectedWeek} is the Monday of the ISO week the cash is expected in. {@code receiptId} /
     * {@code receiptRef} are set when the Express matcher marked it RECEIVED.
     */
    public record ExpectedPaymentDto(long id, long ticketId, String kind, BigDecimal amount, LocalDate expectedWeek,
                                     String status, Long receiptId, String receiptRef, long createdById,
                                     Instant createdAt, Instant updatedAt) {}

    /** POST / PUT body. {@code expectedWeek} must be a Monday. */
    public record ExpectedPaymentRequest(String kind, BigDecimal amount, LocalDate expectedWeek) {}
}
