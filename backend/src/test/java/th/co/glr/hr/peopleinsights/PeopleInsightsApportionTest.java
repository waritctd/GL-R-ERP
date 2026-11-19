package th.co.glr.hr.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D3: a leave is split once over every month it spans (all years) with cumulative rounding. */
class PeopleInsightsApportionTest {
    private static BigDecimal sum(Map<YearMonth, BigDecimal> parts) {
        return parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aLeaveAcrossNewYearSumsToTheTotalWhicheverYearsAreAdded() {
        BigDecimal total = new BigDecimal("1.25");
        BigDecimal in2025 = sum(PeopleInsightsRepository.apportion(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), total, 2025));
        BigDecimal in2026 = sum(PeopleInsightsRepository.apportion(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), total, 2026));
        assertThat(in2025.add(in2026)).isEqualByComparingTo("1.25");
    }

}
