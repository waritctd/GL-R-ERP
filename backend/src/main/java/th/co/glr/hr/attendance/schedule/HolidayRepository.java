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

/**
 * Writes {@code hr.holiday} (V115) on behalf of {@link BotHolidayFetchService} (bank reconcile)
 * and {@link HolidayAdminService} (manual HR/CEO CRUD). {@link DbHolidayCalendar} stays read-only
 * on purpose — this class is the one place that ever mutates {@code hr.holiday}.
 *
 * <p>The reconciliation contract, which matters more than the SQL itself:
 * <ul>
 *   <li>Upsert is idempotent by {@code holiday_date} (the primary key) — re-running the same year
 *       with the same fetch result changes nothing.
 *   <li>A stored row is removed for a fetched year only if it is no longer in that year's fetch
 *       <strong>and</strong> its {@code source = 'BANK'}. A {@code source = 'COMPANY'} row — HR's
 *       own addition — is never deleted or overwritten by this class, fetch or no fetch. This is
 *       enforced twice: the {@code ON CONFLICT ... WHERE} guard on the upsert refuses to touch a
 *       COMPANY row that happens to share a date with a bank holiday, and the {@code DELETE}'s
 *       {@code source = 'BANK'} filter keeps the removal scoped to bank-sourced rows only.
 *   <li>An empty {@code holidays} list is a no-op, not "delete everything for the year". See
 *       {@link #reconcileBankHolidaysForYear} for why that guard lives here too, not only in the
 *       caller.
 * </ul>
 *
 * <p><strong>Manual writes ({@link #createManual}/{@link #updateManual}) never accept a {@code
 * source} parameter</strong> — they hardcode {@code 'COMPANY'} in the SQL literal itself, not as a
 * default. This is deliberate, not an oversight: {@link #reconcileBankHolidaysForYear}'s DELETE is
 * scoped to {@code source = 'BANK'}, so a manually-created/-edited row can only stay immune to a
 * future bank reconcile by being {@code COMPANY} — if a manual write could ever produce a {@code
 * BANK} row (e.g. a caller-supplied {@code source}), the very next monthly fetch would see it as
 * "not in this year's bank list" and silently delete it. See {@link HolidayAdminService}.
 */
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

    /**
     * Inserts a new, HR/CEO-authored holiday. Always {@code source = 'COMPANY'} — see this class's
     * javadoc for why that is hardcoded rather than a parameter. Callers must check
     * {@link #existsForDate} first (the PK would reject a duplicate anyway, but a caller-facing
     * 409/400 with a Thai message is what {@link HolidayAdminService} wants, not a raw constraint
     * violation).
     */
    public void createManual(LocalDate date, String nameTh) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :name, 'COMPANY')
            """,
            new MapSqlParameterSource().addValue("date", date).addValue("name", nameTh));
    }

    /**
     * Updates an existing row's name and forces {@code source = 'COMPANY'} regardless of what it
     * was before — once HR edits a row by hand (even one BOT originally created), it is HR's row
     * from then on and must stay immune to the next bank reconcile, exactly like a fresh manual
     * create. Returns {@code false} if no row exists for {@code date} (caller turns that into 404).
     */
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

    /**
     * Upserts {@code holidays} as {@code source = 'BANK'} rows and deletes any existing
     * {@code source = 'BANK'} row for {@code year} that {@code holidays} no longer contains.
     *
     * <p>Deliberately refuses to reconcile when {@code holidays} is empty, rather than trusting the
     * caller to have already checked. {@link BotHolidayFetchService} treats an empty/unparseable
     * BOT response as "no data, change nothing" before it ever calls this method, but a bug in that
     * decision (or a future caller that skips it) must not be able to wipe a year's holidays just
     * because the upstream fetch came back empty — that is the exact failure mode this guard exists
     * to close off, and it is deliberately independent of the caller's own check.
     */
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
