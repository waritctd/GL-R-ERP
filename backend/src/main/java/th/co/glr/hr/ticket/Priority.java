package th.co.glr.hr.ticket;

import java.util.Set;

/**
 * Ticket priority vocabulary. Mirrors the {@code chk_ticket_priority} CHECK
 * constraint (V6) so an unvalidated API value can never reach the column and
 * fail closed with a 500. {@link EntryChannel} is the sibling pattern.
 */
public final class Priority {
    public static final String LOW = "LOW";
    public static final String NORMAL = "NORMAL";
    public static final String HIGH = "HIGH";

    /** The repository default when a create/edit omits priority. */
    public static final String DEFAULT = NORMAL;

    public static final Set<String> VALID = Set.of(LOW, NORMAL, HIGH);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    private Priority() {}
}
