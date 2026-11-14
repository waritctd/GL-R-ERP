package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/** Writes hr.holiday (V115) on behalf of BotHolidayFetchService (bank reconcile) and HolidayAdminService
 * (manual HR/CEO CRUD). DbHolidayCalendar stays read-only on purpose — this class is the one place that ever
 * mutates hr.holiday. */
@Repository
public class HolidayRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public HolidayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One {@code hr.holiday} row, for the manual admin CRUD surface. */
    public record HolidayRow(LocalDate holidayDate, String nameTh, String source) {}

    /** All holidays between {@code from} and {@code to} (inclusive both ends), for the admin list view. */
    public List<HolidayRow> listBetween(LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT holiday_date, name_th, source
              FROM hr.holiday
             WHERE holiday_date BETWEEN :from AND :to
             ORDER BY holiday_date
            """,
            new MapSqlParameterSource().addValue("from", from).addValue("to", to),
            (rs, rowNum) -> new HolidayRow(
                rs.getObject("holiday_date", LocalDate.class), rs.getString("name_th"), rs.getString("source")));
    }

    public Optional<HolidayRow> findByDate(LocalDate date) {
        List<HolidayRow> rows = jdbc.query(
            "SELECT holiday_date, name_th, source FROM hr.holiday WHERE holiday_date = :date",
            new MapSqlParameterSource("date", date),
            (rs, rowNum) -> new HolidayRow(
                rs.getObject("holiday_date", LocalDate.class), rs.getString("name_th"), rs.getString("source")));
        return rows.stream().findFirst();
    }

    public boolean existsForDate(LocalDate date) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM hr.holiday WHERE holiday_date = :date)",
            new MapSqlParameterSource("date", date), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Inserts a new, HR/CEO-authored holiday. Callers must check #existsForDate first (the PK would reject a
     * duplicate anyway, but a caller-facing 409/400 with a Thai message is what HolidayAdminService wants, not a
     * raw constraint violation). */
    public void createManual(LocalDate date, String nameTh) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :name, 'COMPANY')
            """,
            new MapSqlParameterSource().addValue("date", date).addValue("name", nameTh));
    }

    /** Updates an existing row's name and forces source = 'COMPANY' regardless of what it was before — once HR
     * edits a row by hand (even one BOT originally created), it is HR's row from then on and must stay immune to
     * the next bank reconcile, exactly like a fresh manual create. */
    public boolean updateManual(LocalDate date, String nameTh) {
        int updated = jdbc.update("""
            UPDATE hr.holiday
               SET name_th = :name,
                   source  = 'COMPANY'
             WHERE holiday_date = :date
            """,
            new MapSqlParameterSource().addValue("date", date).addValue("name", nameTh));
        return updated > 0;
    }

    /** Returns {@code false} if no row existed for {@code date} (caller turns that into 404). */
    public boolean delete(LocalDate date) {
        int deleted = jdbc.update(
            "DELETE FROM hr.holiday WHERE holiday_date = :date",
            new MapSqlParameterSource("date", date));
        return deleted > 0;
    }

    /** Upserts holidays as source = 'BANK' rows and deletes any existing source = 'BANK' row for year that holidays
     * no longer contains. Deliberately refuses to reconcile when holidays is empty, rather than trusting the
     * caller to have already checked. */
    public void reconcileBankHolidaysForYear(int year, List<BankHoliday> holidays) {
        if (holidays == null || holidays.isEmpty()) {
            return;
        }

        SqlParameterSource[] upsertParams = holidays.stream()
            .map(h -> new MapSqlParameterSource()
                .addValue("date", h.date())
                .addValue("name", h.nameTh()))
            .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :name, 'BANK')
            ON CONFLICT (holiday_date) DO UPDATE
               SET name_th = EXCLUDED.name_th,
                   source  = 'BANK'
             WHERE hr.holiday.source = 'BANK'
            """, upsertParams);

        Set<LocalDate> fetchedDates = new LinkedHashSet<>();
        holidays.forEach(h -> fetchedDates.add(h.date()));
        jdbc.update("""
            DELETE FROM hr.holiday
             WHERE source = 'BANK'
               AND EXTRACT(YEAR FROM holiday_date) = :year
               AND holiday_date NOT IN (:fetchedDates)
            """,
            new MapSqlParameterSource()
                .addValue("year", year)
                .addValue("fetchedDates", fetchedDates));
    }

    /** One day off, as published by the Bank of Thailand for a given calendar year. */
    public record BankHoliday(LocalDate date, String nameTh) {}
}
