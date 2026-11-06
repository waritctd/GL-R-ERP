package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.LandedCostCalculator.CalculationResult;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** A failing costing SQL statement must not poison the caller's transaction (Postgres aborts it). */
class CeoReviewNoticeSavepointIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void aCostingSqlErrorDoesNotAbortTheCallersTransaction() {
        jdbc.getJdbcTemplate().execute("CREATE TABLE public.savepoint_scratch (id int)");
        LandedCostCalculator failing = new LandedCostCalculator(null, null, null, null, null, null) {
            @Override
            public CalculationResult calculate(PricingRequestSummaryDto summary) {
                return jdbc.queryForObject("SELECT 1/0", Map.of(), CalculationResult.class);
            }
        };

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(CeoReviewNotice.costsOrNull(failing, summary())).isNull();
            jdbc.update("INSERT INTO public.savepoint_scratch (id) VALUES (1)", Map.of());
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.savepoint_scratch", Map.of(), Integer.class))
            .isEqualTo(1);
    }

    @Test
    void aSuccessfulCostingStillReturnsItsResultInsideATransaction() {
        CalculationResult result = new CalculationResult(java.util.List.of(), null);
        LandedCostCalculator ok = new LandedCostCalculator(null, null, null, null, null, null) {
            @Override
            public CalculationResult calculate(PricingRequestSummaryDto summary) {
                return result;
            }
        };

        CalculationResult got = transactionTemplate.execute(status -> CeoReviewNotice.costsOrNull(ok, summary()));

        assertThat(got).isSameAs(result);
    }

    private static PricingRequestSummaryDto summary() {
        return new PricingRequestSummaryDto(1L, "PR-0001", 1L, "T-0001", "โครงการ", "ACME", 1L, "DESIGNER", null,
            "Designer", "READY_FOR_CEO_REVIEW", 1L, "เซล", null, null, null, null, "THB", null, 1, 1, null,
            null, null, null, null, null, null);
    }
}
