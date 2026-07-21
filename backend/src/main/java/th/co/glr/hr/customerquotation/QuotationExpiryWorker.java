package th.co.glr.hr.customerquotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background sweep for Step 5's automatic EXPIRED transition (V75): {@code
 * CustomerQuotationService.expireOverdueQuotations} is a single guarded UPDATE, not an outbox
 * needing claim/reclaim/backoff like {@code FactoryQuoteEmailDispatchWorker} — this isn't calling
 * an external system, so the worker itself is intentionally this small. All the actual logic
 * lives on the service so it can be (and is) exercised directly by tests without waiting on the
 * scheduler; this class is just the trigger, mirroring {@code FactoryQuoteEmailDispatchWorker}'s
 * own documented split between "trigger" and "logic".
 */
@Component
public class QuotationExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(QuotationExpiryWorker.class);

    private final CustomerQuotationService quotations;

    public QuotationExpiryWorker(CustomerQuotationService quotations) {
        this.quotations = quotations;
    }

    @Scheduled(fixedDelayString = "${app.quotation-expiry.sweep-interval-ms:3600000}")
    public void sweep() {
        int expired = quotations.expireOverdueQuotations();
        if (expired > 0) {
            log.info("Quotation expiry sweep flipped {} ISSUED quotation(s) to EXPIRED", expired);
        }
    }
}
