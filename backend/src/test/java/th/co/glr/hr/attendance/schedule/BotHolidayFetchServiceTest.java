package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.config.AppProperties;

/**
 * Unit-tests {@link BotHolidayFetchService#parseHolidays} against fixtures shaped like the
 * documented BOT field names ({@code Date}, {@code HolidayDescriptionThai}, {@code
 * HolidayDescription}, ...) under several plausible envelopes, since the real authenticated
 * response shape has never been observed (see the class javadoc). No network is used — these are
 * hand-written JSON strings standing in for a response body.
 */
class BotHolidayFetchServiceTest {

    private final BotHolidayFetchService service = new BotHolidayFetchService(
        null, // not touched by parseHolidays
        new AppProperties(),
        RestClient.builder(),
        new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void parsesABareTopLevelArray() {
        String json = """
            [
              {"HolidayWeekDay": "Wednesday", "HolidayWeekDayThai": "วันพุธ",
               "Date": "2026-01-01", "DateThai": "01/01/2569",
               "HolidayDescription": "New Year's Day", "HolidayDescriptionThai": "วันขึ้นปีใหม่"},
              {"HolidayWeekDay": "Friday", "HolidayWeekDayThai": "วันศุกร์",
               "Date": "2026-04-17", "DateThai": "17/04/2569",
               "HolidayDescription": "Substitution for Songkran Day", "HolidayDescriptionThai": "ชดเชยวันสงกรานต์"}
            ]
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).containsExactlyInAnyOrder(
            new BankHoliday(LocalDate.of(2026, 1, 1), "วันขึ้นปีใหม่"),
            new BankHoliday(LocalDate.of(2026, 4, 17), "ชดเชยวันสงกรานต์"));
    }

    @Test
    void parsesAResultWrappedArray() {
        String json = """
            {
              "result": [
                {"Date": "2026-05-01", "HolidayDescription": "Labour Day", "HolidayDescriptionThai": "วันแรงงาน"}
              ]
            }
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).containsExactly(new BankHoliday(LocalDate.of(2026, 5, 1), "วันแรงงาน"));
    }

    @Test
    void parsesABotFxStyleNestedEnvelope() {
        // Mirrors the shape BotFxFetchService's own BOT endpoint uses (result.data.data_detail),
        // in case the holiday endpoint follows the same house convention.
        String json = """
            {
              "result": {
                "data": {
                  "data_detail": [
                    {"Date": "2026-12-31", "HolidayDescription": "New Year's Eve", "HolidayDescriptionThai": "วันสิ้นปี"}
                  ]
                }
              }
            }
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).containsExactly(new BankHoliday(LocalDate.of(2026, 12, 31), "วันสิ้นปี"));
    }

    @Test
    void fallsBackToTheEnglishDescriptionWhenThaiIsBlank() {
        String json = """
            [{"Date": "2026-06-03", "HolidayDescriptionThai": "", "HolidayDescription": "Queen's Birthday"}]
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).containsExactly(new BankHoliday(LocalDate.of(2026, 6, 3), "Queen's Birthday"));
    }

    @Test
    void skipsAnEntryWithNoParseableDateButKeepsTheRest() {
        String json = """
            [
              {"Date": "not-a-date", "HolidayDescriptionThai": "ผิดพลาด"},
              {"Date": "2026-08-12", "HolidayDescriptionThai": "วันแม่แห่งชาติ"}
            ]
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).containsExactly(new BankHoliday(LocalDate.of(2026, 8, 12), "วันแม่แห่งชาติ"));
    }

    @Test
    void skipsAnEntryWithNoDescriptionAtAll() {
        String json = """
            [{"Date": "2026-10-13", "HolidayDescriptionThai": "", "HolidayDescription": ""}]
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).isEmpty();
    }

    @Test
    void skipsAnEntryDatedOutsideTheRequestedYear() {
        String json = """
            [{"Date": "2027-01-01", "HolidayDescriptionThai": "วันขึ้นปีใหม่"}]
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).isEmpty();
    }

    @Test
    void anEmptyArrayResponseParsesToNoEntries() {
        assertThat(service.parseHolidays("[]", 2026)).isEmpty();
        assertThat(service.parseHolidays("{\"result\": []}", 2026)).isEmpty();
    }

    @Test
    void malformedJsonParsesToNoEntriesRatherThanThrowing() {
        assertThat(service.parseHolidays("{not valid json", 2026)).isEmpty();
        assertThat(service.parseHolidays("", 2026)).isEmpty();
        assertThat(service.parseHolidays(null, 2026)).isEmpty();
    }

    @Test
    void aResponseWithNoRecognisableArrayAtAllParsesToNoEntries() {
        String json = """
            {"status": "unauthorized", "message": "invalid token"}
            """;

        assertThat(service.parseHolidays(json, 2026)).isEmpty();
    }

    /**
     * The diagnosability requirement: an HTTP-200-but-unrecognised-shape response must not be a
     * silent INFO log indistinguishable from "BOT genuinely has nothing published for this year" —
     * it must WARN and name the response's field names, so the day the token is finally set, a
     * shape mismatch is fixable in one iteration instead of by guesswork. Exercises {@link
     * BotHolidayFetchService#processResponse}, the seam that skips the real HTTP call, against a
     * response shaped nothing like the documented fields.
     */
    @Test
    void aTwoHundredResponseWithAnUnrecognisedShapeLogsAWarningNamingTheFieldsSeen() {
        String json = """
            {"status": "ok", "entries": [{"foo": "bar", "baz": 1}]}
            """;
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        try {
            BotHolidayFetchService.FetchOutcome outcome = service.processResponse(json, 2026);

            assertThat(outcome.holidayCount()).isZero();
            assertThat(outcome.applied()).isFalse();
            assertThat(appender.list).anyMatch(event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("HTTP 200 but no holidays parsed for year 2026")
                    && event.getFormattedMessage().contains("status")   // top-level field name
                    && event.getFormattedMessage().contains("entries")  // top-level field name
                    && event.getFormattedMessage().contains("foo")      // first array element's field name
                    && event.getFormattedMessage().contains("baz")
                    // Never the values -- "ok"/"bar" are payload content, not field names.
                    && !event.getFormattedMessage().contains("\"ok\"")
                    && !event.getFormattedMessage().contains("\"bar\""));
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    void aGenuinelyEmptyArrayResponseAlsoLogsTheSameWarning() {
        // Same code path either way -- see the class javadoc on why this branch cannot tell "shape
        // changed" apart from "BOT has nothing yet", and therefore always surfaces the diagnostic.
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        try {
            service.processResponse("[]", 2026);

            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("HTTP 200 but no holidays parsed for year 2026"));
        } finally {
            detachLogAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BotHolidayFetchService.class))
            .addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BotHolidayFetchService.class))
            .detachAppender(appender);
    }
}
