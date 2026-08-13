package th.co.glr.hr.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import th.co.glr.hr.notification.SalesMailRecipientRepository.SalesMailRecipient;

/**
 * Where a sales-pipeline notification email goes. Owner-ruled routing, 2026-08-14:
 *
 * <table>
 *   <caption>Sales-pipeline notification email routing</caption>
 *   <tr><th>Recipient role</th><th>Address</th></tr>
 *   <tr><td>import</td><td>{@code import@glr.co.th} — shared box only, nobody personally</td></tr>
 *   <tr><td>account</td><td>{@code account@glr.co.th} — shared box only, nobody personally</td></tr>
 *   <tr><td>ceo</td><td>{@code rarm@glr.co.th} — hardcoded, never resolved from an employee row</td></tr>
 *   <tr><td>sales</td><td>the rep's own address, falling back to their manager's</td></tr>
 * </table>
 *
 * <p><b>This is a deliberate contract, and each row of it costs something.</b>
 *
 * <ol>
 *   <li><b>The shared box replaces individual delivery, it does not supplement it.</b> An
 *       import-role notification emails {@code import@glr.co.th} and nobody personally — including
 *       when the notification names one import staffer (a returned costing is addressed at the
 *       pricing request's {@code assignedImportId}, and that person still gets only the shared-box
 *       copy). Accepted cost: no personal nudge, so it depends on someone working the shared inbox.</li>
 *   <li><b>The CEO address is hardcoded.</b> {@link CeoApproverRule} still decides who gets the
 *       in-app row, and it is untouched here and everywhere else; it is simply not asked for an
 *       address. Hardcoding is immune to the recorded production gap where CEO approver records
 *       carry no email at all, and to the rule's own documented empty-set case — if no active
 *       employee holds กรรมการผู้จัดการ the in-app queue goes silent, but the email still arrives.</li>
 *   <li><b>A rep with no email falls back to their manager, and stops there.</b> Owner's choice over
 *       logging-and-skipping. If the manager has no address either, this logs at ERROR and sends
 *       nothing — it does not chain further up the reporting line, and it does not fail silently.
 *       That path is real: {@code email} doubles as the login identity and many employee rows have
 *       none.</li>
 * </ol>
 *
 * <p><b>Divergence from {@link NotificationService#notify} worth knowing about.</b> That path
 * deliberately calls {@link NotificationEmailService} even for an employee with no address, so
 * {@code app.mail.override-to} can still rescue the mail into a test inbox. The owner's ruling 3
 * above is the opposite for sales: both-missing means send nothing. So on an override-to deployment
 * a sales notification for an addressless rep whose manager is also addressless produces no test
 * mail, where a leave notification would. That is the ruling, stated rather than smuggled.
 *
 * <p>Everything else about delivery — the {@code [GL&R HR] } subject prefix, the HTML shell, the
 * inline logo, HTML-escaping, {@code app.mail.override-to} and the portal CTA — is
 * {@link NotificationEmailService}'s, unchanged. In particular the CTA link is the point of an
 * actionable notification and comes straight from the in-app row's {@code link}; the last email
 * programme shipped with no portal link in any message and 3,548 unit tests did not notice, so
 * {@code SalesNotificationMailRoutingIntegrationTest} pins the rendered {@code href}.
 */
@Service
public class SalesNotificationMailRouter implements SalesNotificationMailer {
    private static final Logger log = LoggerFactory.getLogger(SalesNotificationMailRouter.class);

    /** Shared inbox for ฝ่ายจัดซื้อต่างประเทศ. Owner ruling 1 — replaces individual delivery. */
    static final String IMPORT_MAILBOX = "import@glr.co.th";
    /** Shared inbox for ฝ่ายบัญชี. Owner ruling 1 — replaces individual delivery. */
    static final String ACCOUNT_MAILBOX = "account@glr.co.th";
    /**
     * Owner ruling 2 — hardcoded for the sales pipeline only. Not read from {@code hr.employee}, not
     * resolved through {@link CeoApproverRule}, and not configurable: a config key would reintroduce
     * exactly the "one dashboard variable away from silence" failure this is meant to remove.
     */
    static final String CEO_MAILBOX = "rarm@glr.co.th";

    /**
     * No employee id to log against for a shared-box or CEO send. {@link NotificationEmailService}
     * only uses the id for its log line, and this router logs the routing decision itself, so the
     * sentinel is not load-bearing.
     */
    private static final long NO_EMPLOYEE = 0L;

    private final NotificationEmailService emailService;
    private final SalesMailRecipientRepository recipients;

    public SalesNotificationMailRouter(NotificationEmailService emailService,
                                       SalesMailRecipientRepository recipients) {
        this.emailService = emailService;
        this.recipients = recipients;
    }

    @Override
    public void emailForRole(String role, List<Long> notifiedEmployeeIds, String title, String message, String link) {
        // Deferred as one unit, lookups included: on a rollback nothing runs at all, so a rolled-back
        // pricing request costs neither a mail nor a query. See AfterCommit.
        AfterCommit.run(() -> routeRole(role, notifiedEmployeeIds, title, message, link));
    }

    @Override
    public void emailForEmployee(long employeeId, String title, String message, String link) {
        AfterCommit.run(() -> routeEmployee(employeeId, title, message, link));
    }

    private void routeRole(String role, List<Long> notifiedEmployeeIds, String title, String message, String link) {
        switch (role) {
            // The shared-box and CEO branches ignore notifiedEmployeeIds entirely, and that is the
            // point of them: the mail goes out even when the in-app fan-out matched nobody. An
            // import division with no active staff, or the CeoApproverRule empty set, would
            // otherwise mean a pricing request sat waiting with no one told by any channel.
            case "import" -> sendToMailbox(IMPORT_MAILBOX, role, title, message, link);
            case "account" -> sendToMailbox(ACCOUNT_MAILBOX, role, title, message, link);
            case "ceo" -> sendToMailbox(CEO_MAILBOX, role, title, message, link);
            // Sales is the one role with no shared box — each rep who got an in-app row gets their
            // own mail, on the same individual path (and the same manager fallback) as a
            // directly-addressed notification.
            case "sales" -> notifiedEmployeeIds.forEach(id -> routeEmployee(id, title, message, link));
            default -> log.warn("Sales notification email skipped: no mail routing for role={}", role);
        }
    }

    private void routeEmployee(long employeeId, String title, String message, String link) {
        recipients.findRecipient(employeeId).ifPresentOrElse(
            recipient -> routeResolved(employeeId, recipient, title, message, link),
            () -> log.warn("Sales notification email skipped: employee={} has no employee row", employeeId));
    }

    private void routeResolved(long employeeId, SalesMailRecipient recipient, String title, String message,
                               String link) {
        // Ruling 1 again, on the individually-addressed path: an import/account person named by a
        // notification is still reached only through their shared box.
        switch (recipient.routingRole()) {
            case "import" -> {
                log.info("Sales notification email for employee={} routed to the shared import box", employeeId);
                sendToMailbox(IMPORT_MAILBOX, "import", title, message, link);
                return;
            }
            case "account" -> {
                log.info("Sales notification email for employee={} routed to the shared account box", employeeId);
                sendToMailbox(ACCOUNT_MAILBOX, "account", title, message, link);
                return;
            }
            default -> { /* sales — personal delivery below */ }
        }

        if (recipient.hasOwnEmail()) {
            emailService.send(employeeId, recipient.email(), recipient.name(), title, message, link);
            return;
        }
        if (recipient.hasManagerEmail()) {
            // Ruling 3: the manager is told on the rep's behalf, so the greeting names the MANAGER
            // (the mail is landing in their inbox) while the log keeps the rep's id for traceability.
            log.warn("Sales notification email for employee={} has no address on file — falling back to their manager",
                employeeId);
            emailService.send(employeeId, recipient.managerEmail(), recipient.managerName(), title, message, link);
            return;
        }
        // Loudly, and once: ERROR because a notification the pipeline decided to raise is now
        // reaching nobody by email. The in-app row still exists — this is a delivery gap, not a
        // lost notification.
        log.error("Sales notification email DROPPED: employee={} has no email on file and no manager address to "
            + "fall back to — subject=\"{}\" link={}. Nobody was emailed; the in-app notification still stands.",
            employeeId, title, link);
    }

    private void sendToMailbox(String mailbox, String role, String title, String message, String link) {
        // recipientName is null on purpose: a shared box is not a person, so the greeting falls back
        // to NotificationEmailService's generic "เรียน ท่านผู้ใช้งาน,".
        emailService.send(NO_EMPLOYEE, mailbox, null, title, message, link);
        log.info("Sales notification email routed to the shared {} box: to={} subject=\"{}\"", role, mailbox, title);
    }
}
