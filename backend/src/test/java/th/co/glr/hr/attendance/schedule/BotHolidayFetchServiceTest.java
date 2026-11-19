package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.config.AppProperties;

/** Unit-tests BotHolidayFetchService#parseHolidays against the shapes it must now accept: BOT's documented bare
 * top-level array, and the result-wrapped envelope a real production call actually returned on 2026-08-03
 * (plus two deeper envelope candidates kept as insurance — see the class javadoc). */
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

    /** The strict-parse guard, driven end-to-end through BotHolidayFetchService#processResponse rather than just
     * BotHolidayFetchService#parseHolidays: a wrong-shaped-but-HTTP-200 response. */
    @Test
    void aWrongShapedTwoHundredResponseTakesTheWarnAndWriteNothingPath() {
        String json = """
            {
              "result": {
                "holidays": [
                    {"Date": "2026-12-31", "HolidayDescription": "New Year's Eve", "HolidayDescriptionThai": "วันสิ้นปี"}
                ]
              }
            }
            """;
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        try {
            BotHolidayFetchService.FetchOutcome outcome = service.processResponse(json, 2026);

            assertThat(outcome.holidayCount()).isZero();
            assertThat(outcome.applied()).isFalse();
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
