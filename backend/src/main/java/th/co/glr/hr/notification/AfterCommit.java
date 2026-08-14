package th.co.glr.hr.notification;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side effect until the surrounding transaction has actually committed.
 *
 * <p>Notification email is the canonical case: every {@code notify*} call runs inside the business
 * transaction that caused it, so sending the mail inline would put a message in someone's inbox
 * about a pricing request that a later constraint violation rolled straight back out of the
 * database. Mail cannot be un-sent. This is the same defect class as PR #708's orphaned attachment
 * files and #721's deferred render, and it is why {@link NotificationService} has deferred its own
 * sends since the notification-email backbone landed — this class is that logic extracted verbatim
 * so the sales-pipeline router ({@link SalesNotificationMailRouter}) cannot drift from it.
 *
 * <p><b>No transaction, no deferral.</b> When nothing is synchronising — a direct call from a unit
 * test, or a code path outside any transaction — the action runs immediately. That is deliberate:
 * "defer forever" would silently swallow the mail rather than send it, and a swallowed send is
 * exactly the failure mode this repo has already paid for once (Resend's 403 is swallowed too).
 */
final class AfterCommit {

    private AfterCommit() {
    }

    static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
