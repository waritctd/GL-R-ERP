package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * {@code hr.holiday} (V115) seeds zero rows, so every case here inserts its own fixture rows first.
 * Real Postgres, not a mock: {@link DbHolidayCalendar#holidaysBetween} is a {@code BETWEEN} range
 * scan whose boundary behaviour (inclusive both ends) is exactly the kind of thing worth proving
 * against the real driver rather than assuming from the SQL text.
 */
class DbHolidayCalendarIntegrationTest extends AbstractPostgresIntegrationTest {

    private DbHolidayCalendar calendar;

    private void wire() {
        calendar = new DbHolidayCalendar(jdbc);
    }

    private void insertHoliday(LocalDate date, String nameTh, String source) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source) VALUES (:date, :name, :source)
            """, Map.of("date", date, "name", nameTh, "source", source));
    }

    @Test
    void isHolidayIsTrueOnlyForASeededDate() {
        wire();
        LocalDate holiday = LocalDate.of(2026, 12, 31);
        insertHoliday(holiday, "วันสิ้นปี", "BANK");

        assertThat(calendar.isHoliday(holiday)).isTrue();
        assertThat(calendar.isHoliday(holiday.plusDays(1))).isFalse();
    }

    @Test
    void holidaysBetweenIsInclusiveOfBothEndpointsAndExcludesOutsideDates() {
        wire();
        LocalDate start = LocalDate.of(2026, 4, 13);
        LocalDate end = LocalDate.of(2026, 4, 15);
        insertHoliday(start, "วันสงกรานต์", "BANK");
        insertHoliday(end, "วันสงกรานต์", "BANK");
        insertHoliday(end.plusDays(1), "นอกช่วง", "BANK");

        Set<LocalDate> found = calendar.holidaysBetween(start, end);

        assertThat(found).containsExactlyInAnyOrder(start, end);
        assertThat(found).doesNotContain(end.plusDays(1));
    }

    @Test
    void anEmptyTableProducesNoHolidays() {
        wire();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 1, 1))).isFalse();
        assertThat(calendar.holidaysBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
            .isEmpty();
    }
}
