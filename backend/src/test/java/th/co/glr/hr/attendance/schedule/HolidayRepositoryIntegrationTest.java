package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-Postgres coverage of HolidayRepository#reconcileBankHolidaysForYear — this is SQL (an ON CONFLICT ...
 * WHERE upsert plus a scoped DELETE), so a Mockito-mocked repository would happily "pass" while the SQL did
 * something else. */
class HolidayRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private HolidayRepository repository;

    private void wire() {
        repository = new HolidayRepository(jdbc);
    }

    /** The core reconciliation contract, asserted on one fixture as required: a BANK row the new fetch no longer
     * returns is deleted, while a COMPANY row for the same year is left completely untouched by the same call. */
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
    void aBankFetchNeverOverwritesAnExistingCompanyRowOnTheSameDate() {
        wire();
        LocalDate collidingDate = LocalDate.of(2026, 7, 1);
        insertHoliday(collidingDate, "วันหยุดพิเศษของบริษัท (ห้ามแก้)", "COMPANY");

        repository.reconcileBankHolidaysForYear(2026, List.of(new BankHoliday(collidingDate, "จากธนาคาร")));

        Map<String, Object> row = rowFor(collidingDate).orElseThrow();
        assertThat(row.get("source")).isEqualTo("COMPANY");
        assertThat(row.get("name_th")).isEqualTo("วันหยุดพิเศษของบริษัท (ห้ามแก้)");
    }

    private void insertHoliday(LocalDate date, String nameTh, String source) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source) VALUES (:date, :name, :source)
            """, Map.of("date", date, "name", nameTh, "source", source));
    }

    private java.util.Optional<Map<String, Object>> rowFor(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT holiday_date, name_th, source FROM hr.holiday WHERE holiday_date = :date",
            Map.of("date", date));
        return rows.stream().findFirst();
    }
}
