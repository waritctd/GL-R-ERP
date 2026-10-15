package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * The month-end attendance email: one mail to one recipient, one line per configured employee, and
 * the count is the number of distinct days with at least one punch — a lone clock-in or a lone
 * clock-out is a day attended.
 */
class AttendanceMonthlyReportJobIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final String RECIPIENT = "report@example.test";

    private final CapturingMailer mailer = new CapturingMailer();

    private long alice;
    private long bob;
    private long someoneElse;

    @BeforeEach
    void seedEmployees() {
        alice = insertEmployee("10016", "ทดสอบ", "หนึ่ง");
        bob = insertEmployee("10030", "ทดสอบ", "สอง");
        someoneElse = insertEmployee("10099", "คนอื่น", "ไม่เกี่ยว");
    }

    @Test
    void aDayWithOnlyAClockInOrOnlyAClockOutCountsAsAttended() {
        insertPunch(alice, at(1, 8, 20));
        insertPunch(alice, at(1, 17, 40));   // in + out on the 1st: one day, not two
        insertPunch(alice, at(2, 8, 25));    // clock-in only
        insertPunch(alice, at(3, 17, 35));   // clock-out only

        job(List.of("10016")).send(OCTOBER);

        assertThat(mailer.onlyBody()).contains("ทดสอบ หนึ่ง (10016): 3 วัน");
    }

    /**
     * The prod shape for these two people: fingerprint/PIN punches arrive with the employee CODE as
     * badge_code and are stored with employee_id NULL (bulk ingest matches badge_card_no only), and
     * only the odd card tap is linked. The rest of attendance resolves both via
     * AttendanceSql.RESOLVED_EMPLOYEE_JOIN; this report must count the same days.
     */
    @Test
    void punchesStoredWithoutAnEmployeeIdStillCountWhenTheBadgeResolvesToThem() {
        insertUnlinkedPunch("10016", at(1, 8, 20));               // fingerprint, by employee code
        insertPunch(alice, at(2, 8, 20));                         // linked card tap ...
        insertUnlinkedPunch("10016", at(2, 17, 40));              // ... plus fingerprint, same day
        insertUnlinkedPunch("CARD10016", at(3, 8, 20));           // unlinked tap, by card serial
        insertUnlinkedPunch("10099", at(4, 8, 20));               // someone else's code

        job(List.of("10016")).send(OCTOBER);

        assertThat(mailer.onlyBody()).contains("ทดสอบ หนึ่ง (10016): 3 วัน");
    }

    @Test
    void punchesOnTheDaysEitherSideOfTheMonthAreNotCounted() {
        insertPunch(alice, LocalDate.of(2026, 9, 30).atTime(23, 50));
        insertPunch(alice, at(31, 9, 0));
        insertPunch(alice, LocalDate.of(2026, 11, 1).atTime(0, 10));

        job(List.of("10016")).send(OCTOBER);

        assertThat(mailer.onlyBody()).contains("ทดสอบ หนึ่ง (10016): 1 วัน");
    }

    @Test
    void anEmployeeWithNoPunchesIsListedWithZeroRatherThanLeftOut() {
        insertPunch(alice, at(5, 8, 30));

        job(List.of("10016", "10030")).send(OCTOBER);

        assertThat(mailer.onlyBody())
            .contains("ทดสอบ หนึ่ง (10016): 1 วัน")
            .contains("ทดสอบ สอง (10030): 0 วัน");
    }

    @Test
    void anotherEmployeesPunchesNeitherAppearNorInflateTheCount() {
        insertPunch(alice, at(6, 8, 30));
        insertPunch(someoneElse, at(6, 8, 31));
        insertPunch(someoneElse, at(7, 8, 30));
        insertPunch(someoneElse, at(8, 8, 30));

        job(List.of("10016", "10030")).send(OCTOBER);

        assertThat(mailer.onlyBody())
            .contains("ทดสอบ หนึ่ง (10016): 1 วัน")
            .contains("ทดสอบ สอง (10030): 0 วัน")
            .doesNotContain("10099")
            .doesNotContain("คนอื่น");
    }

    @Test
    void aConfiguredCodeWithNoEmployeeIsCalledOutNotSilentlyDropped() {
        job(List.of("10016", "77777")).send(OCTOBER);

        assertThat(mailer.onlyBody())
            .contains("ทดสอบ หนึ่ง (10016): 0 วัน")
            .contains("77777: ไม่พบรหัสพนักงานนี้ในระบบ");
    }

    @Test
    void sendsExactlyOneMailToTheRecipientNamingTheMonthInThai() {
        job(List.of("10016", "10030")).send(OCTOBER);

        assertThat(mailer.sent).hasSize(1);
        assertThat(mailer.sent.get(0).to()).isEqualTo(RECIPIENT);
        assertThat(mailer.sent.get(0).subject()).contains("ตุลาคม 2569");
    }

    @Test
    void aBlankRecipientSendsNothing() {
        insertPunch(alice, at(1, 8, 20));

        new AttendanceMonthlyReportJob(jdbc, mailer, " ", List.of("10016")).send(OCTOBER);

        assertThat(mailer.sent).isEmpty();
    }

    @Test
    void noEmployeeCodesSendsNothing() {
        new AttendanceMonthlyReportJob(jdbc, mailer, RECIPIENT, List.of(" ", "")).send(OCTOBER);

        assertThat(mailer.sent).isEmpty();
    }

    @Test
    void anEmployeeWithNoNameOnFileIsListedByCodeWithoutPrintingNull() {
        jdbc.update("INSERT INTO hr.employee (employee_code, is_active) VALUES ('10050', TRUE)", Map.of());

        job(List.of("10050")).send(OCTOBER);

        assertThat(mailer.onlyBody()).contains("(10050): 0 วัน").doesNotContain("null");
    }

    @Test
    void theRunOnTheFirstReportsTheMonthThatJustEnded() {
        insertPunch(alice, at(20, 8, 30));
        Clock firstOfNovember = Clock.fixed(
            ZonedDateTime.of(2026, 11, 1, 8, 0, 0, 0, BANGKOK).toInstant(), BANGKOK);

        new AttendanceMonthlyReportJob(jdbc, mailer, RECIPIENT, List.of("10016"), firstOfNovember)
            .sendPreviousMonth();

        assertThat(mailer.sent.get(0).subject()).contains("ตุลาคม 2569");
        assertThat(mailer.onlyBody()).contains("(10016): 1 วัน");
    }

    @Test
    void theRunOnNewYearsDayReportsDecemberOfTheYearBefore() {
        Clock firstOfJanuary = Clock.fixed(
            ZonedDateTime.of(2027, 1, 1, 8, 0, 0, 0, BANGKOK).toInstant(), BANGKOK);

        new AttendanceMonthlyReportJob(jdbc, mailer, RECIPIENT, List.of("10016"), firstOfJanuary)
            .sendPreviousMonth();

        assertThat(mailer.sent).hasSize(1);
        assertThat(mailer.sent.get(0).subject()).contains("ธันวาคม 2569");
    }

    @Test
    void firesOnTheFirstOfEveryMonthAt0800Bangkok() throws Exception {
        Scheduled scheduled = AttendanceMonthlyReportJob.class
            .getMethod("sendPreviousMonth")
            .getAnnotation(Scheduled.class);
        assertThat(scheduled).as("sendPreviousMonth must be @Scheduled").isNotNull();
        assertThat(scheduled.zone()).isEqualTo("Asia/Bangkok");

        CronExpression cron = CronExpression.parse(scheduled.cron());
        ZonedDateTime midOctober = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, BANGKOK);
        ZonedDateTime first = cron.next(midOctober);
        ZonedDateTime second = cron.next(first);

        assertThat(first).isEqualTo(ZonedDateTime.of(2026, 11, 1, 8, 0, 0, 0, BANGKOK));
        assertThat(second).isEqualTo(ZonedDateTime.of(2026, 12, 1, 8, 0, 0, 0, BANGKOK));
    }

    private AttendanceMonthlyReportJob job(List<String> employeeCodes) {
        return new AttendanceMonthlyReportJob(jdbc, mailer, RECIPIENT, employeeCodes);
    }

    /**
     * Mirrors the prod rows: created by the attendance import, so no division, hire date or email,
     * and badge_card_no holds the card serial, not the employee code.
     */
    private long insertEmployee(String code, String firstName, String lastName) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th, is_active)
            VALUES (:code, 'CARD' || :code, :firstName, :lastName, TRUE)
            RETURNING employee_id
            """, Map.of("code", code, "firstName", firstName, "lastName", lastName), Long.class);
    }

    private void insertUnlinkedPunch(String badgeCode, LocalDateTime local) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date)
            VALUES ('SHOWROOM', :badge, :at, :workDate)
            """,
            Map.of("badge", badgeCode, "at", local.atZone(BANGKOK).toOffsetDateTime(),
                   "workDate", local.toLocalDate()));
    }

    private void insertPunch(long employeeId, LocalDateTime local) {
        OffsetDateTime at = local.atZone(BANGKOK).toOffsetDateTime();
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date, employee_id)
            VALUES ('SHOWROOM', :badge, :at, :workDate, :employeeId)
            """,
            Map.of("badge", "B" + employeeId, "at", at, "workDate", local.toLocalDate(),
                   "employeeId", employeeId));
    }

    private static LocalDateTime at(int dayOfOctober, int hour, int minute) {
        return OCTOBER.atDay(dayOfOctober).atTime(hour, minute);
    }

    private static final class CapturingMailer implements Mailer {
        record SentMail(String to, String subject, String body) {}

        final List<SentMail> sent = new ArrayList<>();

        String onlyBody() {
            assertThat(sent).as("exactly one report mail").hasSize(1);
            return sent.get(0).body();
        }

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new SentMail(to, subject, body));
        }

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody,
                             List<InlineImage> inlineImages) {
            sent.add(new SentMail(to, subject, textBody));
        }

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            sent.add(new SentMail(to, subject, body));
        }

        @Override
        public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
            sent.add(new SentMail(to, subject, body));
        }
    }
}
