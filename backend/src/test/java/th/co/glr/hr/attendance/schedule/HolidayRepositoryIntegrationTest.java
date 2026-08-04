package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of {@link HolidayRepository#reconcileBankHolidaysForYear} — this is SQL
 * (an {@code ON CONFLICT ... WHERE} upsert plus a scoped {@code DELETE}), so a Mockito-mocked
 * repository would happily "pass" while the SQL did something else. Every case here proves the
 * reconciliation contract from {@link HolidayRepository}'s class javadoc, not just that a row went
 * in.
 */
class HolidayRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private HolidayRepository repository;

    private void wire() {
        repository = new HolidayRepository(jdbc);
    }

    /**
     * V129: {@code name_th} was {@code VARCHAR(120)} and production's reconcile write failed with
     * {@code value too long for type character varying(120)} on a real BOT holiday. This is a
     * genuine Thai holiday name -- a substitution-day prefix plus the King's full formal royal
     * title, the exact combination that triggered the production failure -- not padding, since the
     * point is that the column must now accept what BOT actually sends, not merely accept 120
     * arbitrary characters. Round-trips through the real upsert so both the {@code INSERT} and the
     * {@code ON CONFLICT ... DO UPDATE} branch are proven, not just whichever one a mocked
     * repository would have let through either way.
     */
    @Test
    void aHolidayNameLongerThan120CharactersRoundTripsThroughReconcile() {
        wire();
        String longName = "วันหยุดชดเชยวันเฉลิมพระชนมพรรษาพระบาทสมเด็จพระปรเมนทรรามาธิบดีศรีสินทรมหาวชิราลงกรณ "
            + "พระวชิรเกล้าเจ้าอยู่หัว และวันหยุดราชการเป็นกรณีพิเศษ";
        assertThat(longName.length()).isGreaterThan(120);
        LocalDate date = LocalDate.of(2026, 7, 28);

        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(date, longName)));
        Map<String, Object> inserted = rowFor(date).orElseThrow();
        assertThat(inserted.get("name_th")).isEqualTo(longName);

        // Also exercise the ON CONFLICT ... DO UPDATE branch with the same long value, not only the
        // plain INSERT -- V129 widened the column type, and both write paths reference it.
        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(date, longName)));
        Map<String, Object> updated = rowFor(date).orElseThrow();
        assertThat(updated.get("name_th")).isEqualTo(longName);
    }

    @Test
    void reRunningTheSameYearWithTheSameFetchIsIdempotent() {
        wire();
        List<BankHoliday> holidays = List.of(
            new BankHoliday(LocalDate.of(2026, 1, 1), "วันขึ้นปีใหม่"),
            new BankHoliday(LocalDate.of(2026, 4, 13), "วันสงกรานต์"));

        repository.reconcileBankHolidaysForYear(2026, holidays);
        repository.reconcileBankHolidaysForYear(2026, holidays);

        List<Map<String, Object>> rows = allRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(row -> "BANK".equals(row.get("source")));
    }

    @Test
    void reRunningWithAChangedNameUpdatesInPlaceRatherThanDuplicating() {
        wire();
        LocalDate date = LocalDate.of(2026, 5, 1);
        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(date, "วันแรงงาน (เดิม)")));
        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(date, "วันแรงงาน")));

        List<Map<String, Object>> rows = allRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("name_th", "วันแรงงาน");
    }

    /**
     * The core reconciliation contract, asserted on one fixture as required: a BANK row the new
     * fetch no longer returns is deleted, while a COMPANY row for the same year is left completely
     * untouched by the same call.
     */
    @Test
    void aStaleBankRowIsDeletedWhileACompanyRowInTheSameYearSurvives() {
        wire();
        LocalDate staleBank = LocalDate.of(2026, 3, 2);
        LocalDate companyDay = LocalDate.of(2026, 3, 10);
        insertHoliday(staleBank, "ถูกถอดออกจากรายการใหม่", "BANK");
        insertHoliday(companyDay, "วันหยุดพิเศษของบริษัท", "COMPANY");

        LocalDate freshBank = LocalDate.of(2026, 3, 20);
        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(freshBank, "วันหยุดใหม่")));

        assertThat(rowFor(staleBank)).isEmpty();
        Map<String, Object> company = rowFor(companyDay).orElseThrow();
        assertThat(company.get("name_th")).isEqualTo("วันหยุดพิเศษของบริษัท");
        assertThat(company.get("source")).isEqualTo("COMPANY");
        assertThat(rowFor(freshBank)).isPresent();
    }

    @Test
    void anEmptyFetchOverAPopulatedYearLeavesEveryRowIntact() {
        wire();
        LocalDate bankDay = LocalDate.of(2026, 6, 3);
        LocalDate companyDay = LocalDate.of(2026, 6, 10);
        insertHoliday(bankDay, "วันเฉลิมพระชนมพรรษาสมเด็จพระราชินี", "BANK");
        insertHoliday(companyDay, "วันหยุดพิเศษของบริษัท", "COMPANY");

        repository.reconcileBankHolidaysForYear(2026, List.of());

        assertThat(rowFor(bankDay)).isPresent();
        assertThat(rowFor(companyDay)).isPresent();
        assertThat(allRows()).hasSize(2);
    }

    @Test
    void aBankFetchNeverOverwritesAnExistingCompanyRowOnTheSameDate() {
        wire();
        LocalDate collidingDate = LocalDate.of(2026, 7, 1);
        insertHoliday(collidingDate, "วันหยุดพิเศษของบริษัท (ห้ามแก้)", "COMPANY");

        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(collidingDate, "จากธนาคาร")));

        Map<String, Object> row = rowFor(collidingDate).orElseThrow();
        assertThat(row.get("source")).isEqualTo("COMPANY");
        assertThat(row.get("name_th")).isEqualTo("วันหยุดพิเศษของบริษัท (ห้ามแก้)");
    }

    @Test
    void deletionIsScopedToTheFetchedYearOnly() {
        wire();
        LocalDate otherYearBank = LocalDate.of(2025, 12, 31);
        insertHoliday(otherYearBank, "วันสิ้นปี 2568", "BANK");

        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(LocalDate.of(2026, 1, 1), "วันขึ้นปีใหม่")));

        assertThat(rowFor(otherYearBank)).isPresent();
    }

    private void insertHoliday(LocalDate date, String nameTh, String source) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source) VALUES (:date, :name, :source)
            """, Map.of("date", date, "name", nameTh, "source", source));
    }

    private List<Map<String, Object>> allRows() {
        return jdbc.queryForList("SELECT holiday_date, name_th, source FROM hr.holiday", Map.of());
    }

    private java.util.Optional<Map<String, Object>> rowFor(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT holiday_date, name_th, source FROM hr.holiday WHERE holiday_date = :date",
            Map.of("date", date));
        return rows.stream().findFirst();
    }
}
