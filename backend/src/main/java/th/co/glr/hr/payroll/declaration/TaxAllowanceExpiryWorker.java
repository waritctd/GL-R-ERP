package th.co.glr.hr.payroll.declaration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background sweep for decision #10's yearly expiry (V105's dormant {@code expires_on}/{@code
 * expired_at} columns): {@code TaxAllowanceDeclarationService#expireOverdueVerifications} is a
 * single pass over a handful of guarded UPDATEs, not an outbox needing claim/reclaim/backoff like
 * {@code FactoryQuoteEmailDispatchWorker} — this isn't calling an external system, so the worker
 * itself is intentionally this small. All the actual logic lives on the service so it can be (and
 * is) exercised directly by tests without waiting on the scheduler — mirrors {@code
 * QuotationExpiryWorker}'s own documented split between "trigger" and "logic" exactly.
 *
 * <p>{@code @EnableScheduling} lives on {@code SchedulingConfig}, gated {@code @Profile("!test")} —
 * nothing here needs to change or duplicate that gate; this bean simply never gets its {@code
 * @Scheduled} method registered in a test context.
 */
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
