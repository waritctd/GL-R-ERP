package th.co.glr.hr.customerquotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import th.co.glr.hr.dealquotation.DealQuotationService;

/** Background sweep for Step 5's automatic EXPIRED transition: CustomerQuotationService.expireOverdueQuotations
 * is a single guarded UPDATE, not an outbox needing claim/reclaim/backoff like FactoryQuoteEmailDispatchWorker.
 * A DEAL_DIRECT row is untouched by either call (D5 — that origin never expires). */
@Component
public class QuotationExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(QuotationExpiryWorker.class);

    private final CustomerQuotationService quotations;
    private final DealQuotationService dealQuotations;

    public QuotationExpiryWorker(CustomerQuotationService quotations, DealQuotationService dealQuotations) {
        this.quotations = quotations;
        this.dealQuotations = dealQuotations;
    }

    @Scheduled(fixedDelayString = "${app.quotation-expiry.sweep-interval-ms:3600000}")
    public void sweep() {
        int expired = quotations.expireOverdueQuotations();
        if (expired > 0) {
            log.info("Quotation expiry sweep flipped {} ISSUED quotation(s) to EXPIRED", expired);
        }
        int expiredPricingRequestOrigin = dealQuotations.expireOverdueQuotations();
        if (expiredPricingRequestOrigin > 0) {
            log.info("Quotation expiry sweep flipped {} PRICING_REQUEST-origin ISSUED quotation(s) to EXPIRED",
                expiredPricingRequestOrigin);
        }
    }
}
