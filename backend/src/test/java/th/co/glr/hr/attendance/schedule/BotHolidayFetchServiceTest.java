package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/**
 * Unit-tests {@link BotHolidayFetchService#parseHolidays} against BOT's confirmed OpenAPI schema:
 * a bare top-level array of objects with {@code Date} (ISO {@code yyyy-MM-dd}), {@code
 * HolidayDescriptionThai} and {@code HolidayDescription} (see the class javadoc for the full field
 * list and the {@code Authorization}-header clarification). No network is used — these are
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

    /**
     * Before the schema was confirmed, this class hedged by searching the whole response tree for
     * anything array-shaped, so a {@code result}-wrapped array would have parsed successfully. Now
     * that BOT's OpenAPI spec confirms a bare top-level array, a wrapped array is a shape that does
     * not match the contract and must be rejected — not coerced into holidays — exactly like any
     * other unrecognised shape. See {@link #aWrongShapedTwoHundredResponseTakesTheWarnAndWriteNothingPath}
     * for the same fixture driven through the full outcome/logging path.
     */
    @Test
    void aResultWrappedArrayIsNoLongerAcceptedSinceTheDocumentedShapeIsBareArray() {
        String json = """
            {
              "result": [
                {"Date": "2026-05-01", "HolidayDescription": "Labour Day", "HolidayDescriptionThai": "วันแรงงาน"}
              ]
            }
            """;

        assertThat(service.parseHolidays(json, 2026)).isEmpty();
    }

    /**
     * Same point as above, against the deeper envelope {@code BotFxFetchService}'s own BOT endpoint
     * happens to use ({@code result.data.data_detail}) — this class no longer guesses that the
     * holiday endpoint follows the same house convention; the confirmed spec says it does not.
     */
    @Test
    void aBotFxStyleNestedEnvelopeIsNoLongerAcceptedEither() {
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

        assertThat(service.parseHolidays(json, 2026)).isEmpty();
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

    /**
     * The strict-parse guard, driven end-to-end through {@link BotHolidayFetchService#processResponse}
     * rather than just {@link BotHolidayFetchService#parseHolidays}: a wrong-shaped-but-HTTP-200
     * response (here, the {@code result.data.data_detail} envelope an earlier version of this class
     * would have accepted) must write nothing and must WARN, not silently succeed with the wrong
     * holidays or silently do nothing at INFO. {@code holidays} is {@code null} in this test's {@code
     * service} — if this path incorrectly called {@code reconcileBankHolidaysForYear}, this test
     * would fail with an NPE rather than a soft assertion failure, which is a second, independent
     * tripwire on top of the {@code holidayCount()}/WARN assertions below.
     */
    @Test
    void aWrongShapedTwoHundredResponseTakesTheWarnAndWriteNothingPath() {
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

    /**
     * {@link BotHolidayFetchService#MANUAL_FETCH_COOLDOWN}: the manual-trigger endpoint's only
     * protection against burning BOT's 100-calls/hour token budget via a double-submit or a retry
     * loop, since {@code runFetch()} itself has no rate limiting. All four cases below use the
     * package-private {@code Clock}-injecting constructor so the guard is exercised deterministically
     * — no real wall-clock sleep, no bare {@code Instant.now()} that would make CI flaky.
     */
    @Test
    void cooldownBlocksASecondImmediateManualCall() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"), BANGKOK);
        BotHolidayFetchService service = serviceWithClock(clock);
        service.fetchNow(); // first attempt: token is blank, so this returns an empty list, not a throw

        // Second call, same instant: refused, and distinguishably so -- a 429 the caller can tell
        // apart from "fetch ran and BOT genuinely had nothing", which a silently-returned empty
        // list could never do.
        assertThatThrownBy(service::fetchNow)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).getStatus())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aCallAfterTheCooldownIntervalProceeds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"), BANGKOK);
        BotHolidayFetchService service = serviceWithClock(clock);
        service.fetchNow();

        clock.advance(BotHolidayFetchService.MANUAL_FETCH_COOLDOWN.plusSeconds(1));

        assertThatCode(service::fetchNow).doesNotThrowAnyException();
    }

    /**
     * The interaction the coordinator specifically flagged as a "nasty, slow-to-notice bug" risk:
     * {@link BotHolidayFetchService#scheduledFetch()} must run its fetch logic even when a manual
     * attempt happened moments earlier and would itself be well within {@code
     * MANUAL_FETCH_COOLDOWN}. Proven two ways in one test: (a) {@code scheduledFetch()} throws
     * nothing, where {@code fetchNow()} at the same instant would; and (b) the token-missing WARN
     * log — which only fires from inside {@code runFetch()}'s body — actually appears, so this is
     * positive evidence the scheduled path executed its fetch logic rather than merely not throwing
     * for an unrelated reason.
     */
    @Test
    void scheduledFetchStillRunsEvenImmediatelyAfterAManualAttempt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"), BANGKOK);
        BotHolidayFetchService service = serviceWithClock(clock);
        service.fetchNow(); // starts the manual cooldown

        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            // Same instant as the manual call above -- deep inside the 10-minute cooldown window.
            assertThatCode(service::scheduledFetch).doesNotThrowAnyException();

            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("BOT_HOLIDAY_API_TOKEN not configured"));
        } finally {
            detachLogAppender(appender);
        }
    }

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private BotHolidayFetchService serviceWithClock(Clock clock) {
        return new BotHolidayFetchService(
            null, // token is blank in every cooldown test, so runFetch() never reaches this
            new AppProperties(),
            RestClient.builder(),
            new ObjectMapper().registerModule(new JavaTimeModule()),
            clock);
    }

    /** A {@link Clock} a test can advance by hand, so the cooldown guard needs no real sleep. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
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
