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
    void aTinyAmountOverFiveMonthsHasNoNegativePartAndSumsToTheTotal() {
        Map<YearMonth, BigDecimal> parts = PeopleInsightsRepository.apportion(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31), new BigDecimal("0.03"), 2026);
        assertThat(parts).hasSize(5);
        assertThat(parts.values()).allSatisfy(p -> assertThat(p).isGreaterThanOrEqualTo(BigDecimal.ZERO));
        assertThat(sum(parts)).isEqualByComparingTo("0.03");
    }

    @Test
    void aLeaveAcrossNewYearSumsToTheTotalWhicheverYearsAreAdded() {
        BigDecimal total = new BigDecimal("1.25");
        BigDecimal in2025 = sum(PeopleInsightsRepository.apportion(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), total, 2025));
        BigDecimal in2026 = sum(PeopleInsightsRepository.apportion(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), total, 2026));
        assertThat(in2025.add(in2026)).isEqualByComparingTo("1.25");
    }

    @Test
    void anOrdinaryTwoMonthLeaveSplitsByCalendarDaysAndSumsToTheTotal() {
        Map<YearMonth, BigDecimal> parts = PeopleInsightsRepository.apportion(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 14), new BigDecimal("20"), 2026);
        assertThat(parts.get(YearMonth.of(2026, 4))).isEqualByComparingTo("8.8");
        assertThat(parts.get(YearMonth.of(2026, 5))).isEqualByComparingTo("11.2");
        assertThat(sum(parts)).isEqualByComparingTo("20");
    }

    @Test
    void onlyTheRequestedYearsMonthsAreReturnedAndTheTwoYearsSumToTheTotal() {
        LocalDate start = LocalDate.of(2025, 11, 20), end = LocalDate.of(2026, 2, 10);
        Map<YearMonth, BigDecimal> y25 = PeopleInsightsRepository.apportion(start, end, new BigDecimal("7"), 2025);
        Map<YearMonth, BigDecimal> y26 = PeopleInsightsRepository.apportion(start, end, new BigDecimal("7"), 2026);
        assertThat(y25.keySet()).containsExactly(YearMonth.of(2025, 11), YearMonth.of(2025, 12));
        assertThat(y26.keySet()).containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        assertThat(sum(y25).add(sum(y26))).isEqualByComparingTo("7");
    }
}
