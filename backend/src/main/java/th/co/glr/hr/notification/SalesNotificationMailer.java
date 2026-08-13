package th.co.glr.hr.notification;

import java.util.List;

/**
 * Email delivery for the sales-pipeline notifications that {@link NotificationRepository} writes.
 *
 * <p><b>Why this hangs off the repository rather than the nine sales services.</b> Every
 * sales-pipeline in-app notification in this codebase — 18 direct call sites across {@code
 * TicketService}, {@code PricingRequestService}, {@code PricingCostingService}, {@code
 * PricingDecisionService}, {@code FactoryQuoteService}, {@code CustomerQuotationService}, {@code
 * DepositNoticeService}, {@code OrderConfirmationService} and {@code ProcurementService}, and about
 * 30 notification-raising points once each service's own {@code notifyCeo} helper is counted through
 * — passes through exactly four {@code NotificationRepository} methods. Hanging email off those four
 * means the email set is derived from the rows that were actually inserted, so it <b>cannot drift</b>
 * from the in-app set: a service that adds a notification tomorrow gets its email for free, and one
 * that stops raising a notification stops mailing at the same moment. Adding a parallel dispatch at
 * each call site would have been 18 places for the two channels to disagree.
 *
 * <p>The repository reports <i>what it did</i>; the implementation decides <i>who gets mail</i>.
 * Routing lives entirely in {@link SalesNotificationMailRouter} — the repository knows nothing about
 * mailboxes.
 */
public interface SalesNotificationMailer {

    /**
     * A sales-pipeline notification was fanned out to a role.
     *
     * @param role                 the role as passed to {@code NotificationRepository#notifyByRole}
     *                             — {@code import}, {@code account}, {@code ceo} or {@code sales}
     * @param notifiedEmployeeIds  the employees that actually received an in-app row, straight from
     *                             the {@code INSERT ... RETURNING}. Used only by the {@code sales}
     *                             branch, which delivers per rep; the shared-box and CEO branches
     *                             deliberately ignore it (see {@link SalesNotificationMailRouter})
     * @param title                the Thai title stored on the in-app row — becomes the mail
     *                             subject, so the two channels always read the same
     * @param message              the Thai body stored on the in-app row
     * @param link                 the portal path stored on the in-app row, e.g.
     *                             {@code /pricing-requests/42}
     */
    void emailForRole(String role, List<Long> notifiedEmployeeIds, String title, String message, String link);

    /** A sales-pipeline notification was addressed at one employee. */
    void emailForEmployee(long employeeId, String title, String message, String link);

    /**
     * In-app only — writes the notification row and sends nothing.
     *
     * <p>Exists for the integration tests that assert on {@code hr.notification} rows and have no
     * interest in mail. It is deliberately <b>not</b> a convenience constructor on {@link
     * NotificationRepository}: a test that wires this is asserting nothing about email, and that
     * has to be visible at the wiring site rather than hidden behind a shorter constructor. A
     * "no mail was sent" assertion made against this implementation is vacuous — it passes because
     * nothing can ever send, not because the routing decided not to.
     */
    SalesNotificationMailer NO_OP = new SalesNotificationMailer() {
        @Override
        public void emailForRole(String role, List<Long> notifiedEmployeeIds, String title, String message,
                                 String link) {
            // in-app only
        }

        @Override
        public void emailForEmployee(long employeeId, String title, String message, String link) {
            // in-app only
        }
    };
}
