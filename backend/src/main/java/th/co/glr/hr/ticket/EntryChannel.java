package th.co.glr.hr.ticket;

import java.util.Set;

/**
 * How a deal arrived: the owner's three business routes, plus an honest "nobody said".
 *
 * <p><b>{@link #UNSPECIFIED} is valid as STORED but invalid as INPUT to
 * {@link TicketService#setEntryChannel}</b> — the same shape as
 * {@link QuotationRecipient#UNSPECIFIED}, and for the same reason: once a channel has been stated
 * it must not be possible to un-state it. It is accepted at deal-creation time only because
 * omitting the field means exactly that anyway (see {@link TicketRepository#create}).
 *
 * <p><b>Data cutoff (V144, 2026-08-13).</b> Before V144 the column defaulted to
 * {@code DESIGNER_LED}, so every pre-V144 row reads {@code DESIGNER_LED} whether a human chose it
 * or nobody did. Those rows were deliberately NOT backfilled — rewriting them would destroy the
 * deliberate ones and leaving them asserts a fact for the rest — so treat the channel on any
 * ticket created before V144 as unreliable and exclude it from channel-based reporting.
 */
public final class EntryChannel {
    public static final String DESIGNER_LED = "DESIGNER_LED";
    public static final String OWNER_DIRECT = "OWNER_DIRECT";
    public static final String BUYER_DIRECT = "BUYER_DIRECT";

    /** "Not stated" — the column default from V144 onward. Never a legal PATCH input. */
    public static final String UNSPECIFIED = "UNSPECIFIED";

    public static final Set<String> VALID = Set.of(DESIGNER_LED, OWNER_DIRECT, BUYER_DIRECT, UNSPECIFIED);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    private EntryChannel() {}
}
