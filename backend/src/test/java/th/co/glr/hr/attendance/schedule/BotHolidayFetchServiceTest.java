package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/**
 * Unit-tests {@link BotHolidayFetchService#parseHolidays} against the shapes it must now accept:
 * BOT's documented bare top-level array, and the {@code result}-wrapped envelope a real production
 * call actually returned on 2026-08-03 (plus two deeper envelope candidates kept as insurance —
 * see the class javadoc). Elements carry {@code Date} (ISO {@code yyyy-MM-dd}), {@code
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
     * The case that is actually failing in production: BOT wraps the array in a {@code result}
     * envelope rather than returning it bare, confirmed against a real call on 2026-08-03 (see the
     * class javadoc). Driven through {@link #processResponse} — what actually runs in production —
     * with a mocked {@link HolidayRepository} so the write path can complete instead of NPE-ing on
     * the {@code null} repository the other tests in this class use. Asserts both sides on this one
     * fixture: the wrapped holiday is written and reflected in the outcome (presence), and the "no
     * holidays parsed" WARN this exact response used to trigger before this fix is absent (absence)
     * — proving the successful path took over cleanly rather than merely returning a non-empty list
     * while still also warning.
     */
    @Test
    void aResultWrappedArrayParsesCorrectly() {
        String json = """
            {
              "result": [
                {"Date": "2026-05-01", "HolidayDescription": "Labour Day", "HolidayDescriptionThai": "วันแรงงาน"}
              ]
            }
            """;
        HolidayRepository repository = mock(HolidayRepository.class);
        BotHolidayFetchService service = new BotHolidayFetchService(
            repository, new AppProperties(), RestClient.builder(),
            new ObjectMapper().registerModule(new JavaTimeModule()));
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        try {
            BotHolidayFetchService.FetchOutcome outcome = service.processResponse(json, 2026);

            assertThat(outcome.holidayCount()).isEqualTo(1);
            assertThat(outcome.applied()).isTrue();
            verify(repository).reconcileBankHolidaysForYear(2026,
                List.of(new BankHoliday(LocalDate.of(2026, 5, 1), "วันแรงงาน")));
            assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("no holidays parsed"));
        } finally {
            detachLogAppender(appender);
        }
    }

    /**
     * The bug this class was reported for: a {@link DataIntegrityViolationException} from the write
     * step (e.g. production's real {@code value too long for type character varying(120)} against
     * the pre-V129 column) must WARN with the fact that parsing succeeded but the write failed —
     * never share {@link #fetchYear}'s "no data for {@code year}" INFO wording, which is reserved
     * for BOT genuinely having nothing published. Both sides are asserted on the same JSON fixture
     * and the same log capture so the split is proven real, not just two independently-passing
     * tests: the first call (repository throws) takes the loud WARN path and must not also log the
     * quiet "reconciled" INFO message; the second call, same fixture, repository now succeeding,
     * takes the quiet path and must not also log the loud "database write failed" WARN message.
     */
    @Test
    void aDatabaseWriteFailureLogsALoudWarningWhileASuccessfulWriteOnTheSameFixtureStaysQuiet() {
        String json = """
            {
              "result": [
                {"Date": "2026-05-01", "HolidayDescription": "Labour Day", "HolidayDescriptionThai": "วันแรงงาน"}
              ]
            }
            """;
        HolidayRepository repository = mock(HolidayRepository.class);
        BotHolidayFetchService service = new BotHolidayFetchService(
            repository, new AppProperties(), RestClient.builder(),
            new ObjectMapper().registerModule(new JavaTimeModule()));
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        try {
            // First invocation throws (simulating the production write failure); the second, same
            // arguments, succeeds as a no-op mock call -- Mockito's default for an unstubbed void
            // call -- so the very same fixture can drive both the loud and the quiet path below.
            doThrow(new DataIntegrityViolationException(
                    "ERROR: value too long for type character varying(120)"))
                .doNothing()
                .when(repository).reconcileBankHolidaysForYear(2026,
                    List.of(new BankHoliday(LocalDate.of(2026, 5, 1), "วันแรงงาน")));

            BotHolidayFetchService.FetchOutcome failedOutcome = service.processResponse(json, 2026);

            // Outcome contract unchanged on a write failure: holidayCount 0, applied false, exactly
            // as any other failed/empty fetch -- even though one holiday genuinely parsed.
            assertThat(failedOutcome.holidayCount()).isZero();
            assertThat(failedOutcome.applied()).isFalse();
            assertThat(appender.list).anyMatch(event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("parsed 1 holiday(s) successfully")
                    && event.getFormattedMessage().contains("database write failed")
                    && event.getFormattedMessage().contains("value too long"));
            assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("no data for"));
            assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("reconciled 1 holiday(s)"));

            appender.list.clear();

            BotHolidayFetchService.FetchOutcome successOutcome = service.processResponse(json, 2026);

            assertThat(successOutcome.holidayCount()).isEqualTo(1);
            assertThat(successOutcome.applied()).isTrue();
            assertThat(appender.list).anyMatch(event ->
                event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("reconciled 1 holiday(s)"));
            assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("database write failed"));
        } finally {
            detachLogAppender(appender);
        }
    }

    /**
     * One layer deeper than the confirmed live shape: {@code result.data}, kept as a named
     * candidate in case BOT nests further the way its other APIs do.
     */
    @Test
    void aResultDataShapeParses() {
        String json = """
            {
              "result": {
                "data": [
                  {"Date": "2026-07-06", "HolidayDescription": "Asalha Puja Day", "HolidayDescriptionThai": "วันอาสาฬหบูชา"}
                ]
              }
            }
            """;

        assertThat(service.parseHolidays(json, 2026)).containsExactly(
            new BankHoliday(LocalDate.of(2026, 7, 6), "วันอาสาฬหบูชา"));
    }

    /**
     * The deepest named candidate: {@code result.data.data_detail}, the exact envelope shape {@code
     * BotFxFetchService}'s own BOT endpoint uses — cheap insurance kept for that reason, per the
     * class javadoc.
     */
    @Test
    void aResultDataDataDetailShapeParses() {
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

        assertThat(service.parseHolidays(json, 2026)).containsExactly(
            new BankHoliday(LocalDate.of(2026, 12, 31), "วันสิ้นปี"));
    }

    /**
     * Proves the candidate list is a fixed set of named locations, not a tree search: an unrelated
     * array sitting under a field name that is not one of the four named candidates must never be
     * picked up, even though it is Date-shaped and appears earlier in the document than {@code
     * result}. Asserts both sides on one fixture -- the real, {@code result}-wrapped holiday is
     * present, and the decoy under the unrelated field is absent -- so a regression that widened
     * candidate selection back into a search would be caught by the absence assertion even if the
     * presence assertion alone would not.
     */
    @Test
    void anUnrelatedArrayElsewhereInTheDocumentIsNeverPickedOverTheNamedCandidate() {
        String json = """
            {
              "unrelatedArrayField": [
                {"Date": "2026-12-25", "HolidayDescription": "Decoy", "HolidayDescriptionThai": "ของปลอม"}
              ],
              "result": [
                {"Date": "2026-05-01", "HolidayDescription": "Labour Day", "HolidayDescriptionThai": "วันแรงงาน"}
              ]
            }
            """;

        List<BankHoliday> result = service.parseHolidays(json, 2026);

        assertThat(result).contains(new BankHoliday(LocalDate.of(2026, 5, 1), "วันแรงงาน"));
        assertThat(result).doesNotContain(new BankHoliday(LocalDate.of(2026, 12, 25), "ของปลอม"));
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
     * response — here, an envelope that looks superficially like the accepted candidates (it has a
     * {@code result} object) but nests the array under a field name ({@code holidays}) that is not
     * one of the four named candidates — must write nothing and must WARN, not silently succeed
     * with the wrong holidays or silently do nothing at INFO. {@code holidays} is {@code null} in
     * this test's {@code service} — if this path incorrectly called {@code
     * reconcileBankHolidaysForYear}, this test would fail with an NPE rather than a soft assertion
     * failure, which is a second, independent tripwire on top of the {@code holidayCount()}/WARN
     * assertions below.
     */
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
