package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads {@code hr.holiday} (V115). Seeded with zero rows until HR supplies the BOT holiday list. */
@Repository
public class DbHolidayCalendar implements HolidayCalendar {

    private final NamedParameterJdbcTemplate jdbc;

    public DbHolidayCalendar(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isHoliday(LocalDate date) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM hr.holiday WHERE holiday_date = :date)",
            new MapSqlParameterSource("date", date),
            Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Set<LocalDate> holidaysBetween(LocalDate fromDate, LocalDate toDate) {
        Set<LocalDate> dates = new HashSet<>();
        jdbc.query(
            "SELECT holiday_date FROM hr.holiday WHERE holiday_date BETWEEN :fromDate AND :toDate",
            new MapSqlParameterSource()
                .addValue("fromDate", fromDate)
                .addValue("toDate", toDate),
            rs -> {
                dates.add(rs.getObject("holiday_date", LocalDate.class));
            });
        return dates;
    }
}
