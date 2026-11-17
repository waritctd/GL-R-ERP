package th.co.glr.hr.ticket;

import java.util.Set;

/** How a deal arrived: the owner's three business routes, plus an honest "nobody said". #UNSPECIFIED is valid
 * as STORED but invalid as INPUT to TicketService#setEntryChannel. It is accepted at deal-creation time only
 * because omitting the field means exactly that anyway (see TicketRepository#create). */
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
