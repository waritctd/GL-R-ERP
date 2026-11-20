package th.co.glr.hr.auth;

import java.util.function.LongPredicate;

/** The ONE definition of "oversight reader": may read every deal and its tabs but edits nothing of other teams. The role
 * {@code sales_manager}, or a holder of the per-person {@code hr.employee.can_read_all_sales} grant in any role except
 * {@code import} (import stays price-free whatever the flag says). Use it ONLY at READ gates: it is never a write grant and
 * must not be OR-ed into an ownership predicate. {@code flag} is the live grant read (never cached on the session). */
public final class OversightReader {
    private OversightReader() {
    }

    public static boolean is(UserPrincipal actor, LongPredicate flag) {
        return actor != null && ("sales_manager".equals(actor.role()) || holder(actor, flag));
    }

    /** The grant holder alone (role sales_manager not counted): who reads by the flag, not by what the role already allows. */
    public static boolean holder(UserPrincipal actor, LongPredicate flag) {
        return actor != null && !"import".equals(actor.role()) && flag.test(actor.id());
    }
}
