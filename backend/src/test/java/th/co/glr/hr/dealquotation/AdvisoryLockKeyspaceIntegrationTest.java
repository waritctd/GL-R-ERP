package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Pins the advisory-lock keyspaces: a ticket lock must not alias a pricing-request lock with the
 * same numeric id (createFromPricingRequest and approveAndIssuePricingRequestOrigin take
 * PR-then-ticket; a shared keyspace deadlocks with crossed ids). Not covered: the full crossed-id
 * deadlock itself (two service-level transactions) — the keyspace separation is what prevents it.
 */
class AdvisoryLockKeyspaceIntegrationTest extends AbstractPostgresIntegrationTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private DealQuotationRepository deals() {
        return new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
    }

    private CustomerQuotationRepository requests() {
        return new CustomerQuotationRepository(jdbc);
    }

    /** A holds lockA in an open transaction; B then runs lockB under a 2s lock_timeout.
     * Returns B's failure (null if B completed). */
    private Throwable holdThenTry(Consumer<Long> lockA, long idA, Consumer<Long> lockB, long idB) throws Exception {
        CountDownLatch aHolds = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        Future<?> a = pool.submit(() -> transactionTemplate.execute(s -> {
            lockA.accept(idA);
            aHolds.countDown();
            try {
                releaseA.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(aHolds.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Throwable> b = pool.submit(() -> {
            try {
                transactionTemplate.execute(s -> {
                    jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '2s'");
                    lockB.accept(idB);
                    return null;
                });
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        Throwable result = b.get(10, TimeUnit.SECONDS);
        releaseA.countDown();
        a.get(10, TimeUnit.SECONDS);
        return result;
    }

    @Test
    void lockTicket_doesNotBlock_lockPricingRequestOnTheSameId() throws Exception {
        Throwable bFailure = holdThenTry(deals()::lockTicket, 424242L, requests()::lockPricingRequest, 424242L);
        assertThat(bFailure).isNull();
    }

    @Test
    void lockTicket_stillBlocks_aSecondLockTicketOnTheSameId() throws Exception {
        Throwable bFailure = holdThenTry(deals()::lockTicket, 424243L, deals()::lockTicket, 424243L);
        assertThat(bFailure).isNotNull().hasMessageContaining("lock timeout");
    }

    @Test
    void lockPricingRequest_stillBlocks_aSecondLockPricingRequestOnTheSameId() throws Exception {
        Throwable bFailure = holdThenTry(requests()::lockPricingRequest, 424244L, requests()::lockPricingRequest, 424244L);
        assertThat(bFailure).isNotNull().hasMessageContaining("lock timeout");
    }
}
