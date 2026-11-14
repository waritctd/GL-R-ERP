package th.co.glr.hr.payroll.declaration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background sweep for decision #10's yearly expiry: TaxAllowanceDeclarationService#expireOverdueVerifications
 * is a single pass over a handful of guarded UPDATEs, not an outbox needing claim/reclaim/backoff like
 * FactoryQuoteEmailDispatchWorker. */
@Component
public class TaxAllowanceExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(TaxAllowanceExpiryWorker.class);

    private final TaxAllowanceDeclarationService declarations;

    public TaxAllowanceExpiryWorker(TaxAllowanceDeclarationService declarations) {
        this.declarations = declarations;
    }

    @Scheduled(fixedDelayString = "${app.tax-allowance-expiry.sweep-interval-ms:3600000}")
    public void sweep() {
        int expired = declarations.expireOverdueVerifications();
        if (expired > 0) {
            log.info("Tax allowance expiry sweep flipped {} declaration(s) to EXPIRED", expired);
        }
    }
}
