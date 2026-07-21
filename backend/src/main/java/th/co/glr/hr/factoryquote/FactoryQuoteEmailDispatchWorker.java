package th.co.glr.hr.factoryquote;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background outbox worker for {@code sales.factory_quote_email_dispatch} (V64, extended in V67).
 *
 * <p>{@code FactoryQuoteService.send()} only enqueues a {@code PENDING} dispatch row and returns;
 * this worker is what actually calls the mail provider and finalizes the quote/pricing-request
 * state, on its own schedule and its own transaction(s) — so an application crash between
 * "provider accepted the email" and "quote/pricing-request updated" cannot happen inside a single
 * HTTP request the way it could in the old synchronous {@code send()}. All the actual logic
 * (claim, send, finalize, failure/backoff) lives in {@link FactoryQuoteService} so it can be
 * exercised directly by tests without going through the scheduler; this class is just the trigger.
 *
 * <p><b>Deliberately calls {@code claimDispatch}/{@code attemptSend}/{@code finalizeDispatch} as
 * three separate calls on the injected {@code factoryQuotes} bean, not via {@code
 * FactoryQuoteService.processDispatch}.</b> {@code factoryQuotes} here is the Spring-managed proxy,
 * so each of those calls is a genuine external call through the AOP proxy, and {@code
 * finalizeDispatch}'s {@code @Transactional} actually applies. Routing through {@code
 * processDispatch} instead would make {@code finalizeDispatch} a same-class self-invocation
 * *inside* {@code FactoryQuoteService}, which bypasses the proxy and silently turns off the
 * transaction — exactly the defect this worker exists to avoid. See {@code
 * FactoryQuoteService#processDispatch}'s javadoc for why that method still exists (test-only).
 */
@Component
public class FactoryQuoteEmailDispatchWorker {
    private static final Logger log = LoggerFactory.getLogger(FactoryQuoteEmailDispatchWorker.class);

    private final FactoryQuoteService factoryQuotes;

    public FactoryQuoteEmailDispatchWorker(FactoryQuoteService factoryQuotes) {
        this.factoryQuotes = factoryQuotes;
    }

    @Scheduled(fixedDelayString = "${app.factory-quote-dispatch.poll-interval-ms:5000}")
    public void pollAndDispatch() {
        List<Long> claimable = factoryQuotes.claimableDispatchIds();
        for (long dispatchId : claimable) {
            dispatchOne(dispatchId);
        }
    }

    private void dispatchOne(long dispatchId) {
        try {
            if (!factoryQuotes.claimDispatch(dispatchId)) {
                return;
            }
            factoryQuotes.attemptSend(dispatchId);
            factoryQuotes.finalizeDispatch(dispatchId);
        } catch (RuntimeException e) {
            try {
                factoryQuotes.markDispatchFailed(dispatchId, e.getMessage());
            } catch (RuntimeException markFailedError) {
                // Should not happen (markDispatchFailedWithBackoff is a plain guarded UPDATE with
                // no external calls), but one bad row must never stop the rest of the batch.
                log.error("Factory quote email dispatch {} failed and could not be marked FAILED: {}",
                    dispatchId, markFailedError.getMessage(), markFailedError);
            }
        }
    }
}
