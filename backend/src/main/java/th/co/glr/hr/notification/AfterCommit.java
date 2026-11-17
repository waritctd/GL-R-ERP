package th.co.glr.hr.notification;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers a side effect until the surrounding transaction has actually committed. Mail cannot be un-sent. No
 * transaction, no deferral. When nothing is synchronising — a direct call from a unit test, or a code path
 * outside any transaction — the action runs immediately. */
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
