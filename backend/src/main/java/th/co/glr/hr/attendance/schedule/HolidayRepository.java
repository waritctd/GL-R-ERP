package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code hr.holiday} (V115) on behalf of {@link BotHolidayFetchService}. {@link
 * DbHolidayCalendar} stays read-only on purpose — this class is the one place that reconciles the
 * bank's published list against what is already stored.
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
 */
@Repository
public class HolidayRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public HolidayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
