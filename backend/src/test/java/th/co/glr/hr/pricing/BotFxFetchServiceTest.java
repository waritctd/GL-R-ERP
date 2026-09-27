package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/**
 * Review fix (2026-09-28): {@link BotFxFetchService#fetchNow()} previously had no throttle at all,
 * so 20 CEO-console clicks could exhaust BOT's ~100-calls/hour-per-token budget and make the 18:00
 * {@link BotFxFetchService#fetchDailyRates()} 429 silently. This mirrors {@code
 * BotHolidayFetchServiceTest}'s cooldown/clock coverage exactly, against the sibling class's own
 * {@link BotFxFetchService#MANUAL_FETCH_COOLDOWN} and Bangkok-zoned {@link Clock} seam.
 *
 * <p>No real HTTP is used anywhere in this class: {@link RestClient} is mocked at every step of its
 * fluent chain (see {@link #mockRestClient}), so "no BOT call made" is provable by counting
 * invocations of the mock, not merely inferred from a blank token short-circuiting before the loop.
 */
class BotFxFetchServiceTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** A BOT response carrying a real, parseable rate for every currency queried (the mocked
     * {@link RestClient} returns this same body regardless of which currency was requested — the
     * URI itself, captured separately, is what proves which currency/date was actually asked for). */
    private static final String VALID_BOT_JSON = """
        {"result": {"data": {"data_detail": [{"currency_id": "USD", "selling": "36.5000"}]}}}
        """;

    /**
     * (a) — the case B1 exists for: a second manual call inside the 10-minute window must be
     * refused with a 429 carrying the remaining-seconds message {@code
     * frontend/src/features/attendanceCalendar/holidayFetch.js}'s {@code parseCooldownSeconds}
     * parses (the pattern is generic — any Thai message containing "N วินาที" — but this also pins
     * the exact wording), and — the part a blank-token-only test could never prove — must make
     * <strong>no BOT call at all</strong> for that second, refused attempt. A real token and a fully
     * mocked {@link RestClient} chain are used specifically so this is provable by counting
     * invocations, not merely inferred from the token check short-circuiting.
     */
    @Test
    void cooldownBlocksASecondImmediateManualCallAndMakesNoBotCall() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        MockedRestClient rest = mockRestClient(VALID_BOT_JSON);
        BotFxFetchService service = serviceWithClock(mock(FxRateRepository.class), rest.client, clock, "fake-token");

        // First attempt: allowed through, and it does reach BOT (proves the mock is wired, not
        // merely never called for an unrelated reason) -- one GET per TRACKED_CURRENCIES entry.
        service.fetchNow();
        verify(rest.client, times(5)).get();

        // Second call, same instant: refused before any further BOT interaction, and the message
        // carries the remaining-seconds figure holidayFetch.js's parseCooldownSeconds parses.
        assertThatThrownBy(service::fetchNow)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).getStatus())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThatThrownBy(service::fetchNow)
            .hasMessageMatching(".*\\(ประมาณ \\d+ วินาที\\).*");

        // Still exactly 5 -- the refused calls above made no additional BOT request.
        verify(rest.client, times(5)).get();
    }

    /** (b) — a call after the cooldown interval has elapsed proceeds normally, reaching BOT again. */
    @Test
    void aCallAfterTheCooldownIntervalProceeds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        MockedRestClient rest = mockRestClient(VALID_BOT_JSON);
        BotFxFetchService service = serviceWithClock(mock(FxRateRepository.class), rest.client, clock, "fake-token");
        service.fetchNow();

        clock.advance(BotFxFetchService.MANUAL_FETCH_COOLDOWN.plusSeconds(1));

        assertThatCode(service::fetchNow).doesNotThrowAnyException();
        verify(rest.client, times(10)).get(); // 5 currencies x 2 successful invocations
    }

    /**
     * (c) — the interaction B1 specifically calls out: {@link BotFxFetchService#fetchDailyRates()}
     * must run its fetch logic even when a manual attempt happened moments earlier and would itself
     * be well within {@link BotFxFetchService#MANUAL_FETCH_COOLDOWN}. Proven positively -- the
     * scheduled call must still reach BOT (a real interaction count), not merely "did not throw",
     * which could also be true of a silently-skipped no-op.
     */
    @Test
    void scheduledFetchStillRunsEvenImmediatelyAfterAManualAttempt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        MockedRestClient rest = mockRestClient(VALID_BOT_JSON);
        BotFxFetchService service = serviceWithClock(mock(FxRateRepository.class), rest.client, clock, "fake-token");
        service.fetchNow(); // starts the manual cooldown
        verify(rest.client, times(5)).get();

        // Same instant as the manual call above -- deep inside the 10-minute cooldown window.
        assertThatCode(service::fetchDailyRates).doesNotThrowAnyException();

        // The scheduled path made its own 5 BOT calls despite the still-active manual cooldown.
        verify(rest.client, times(10)).get();
    }

    /**
     * S3 — the Bangkok-date fix. A fixed instant of 2026-09-27T18:30:00Z is 2026-09-28 01:30
     * Bangkok time (UTC+7): the requested BOT period AND the {@code effective_date} written via
     * {@link FxRateRepository#upsertFromBot} must both be 2026-09-28, never 2026-09-27 (which is
     * what {@code LocalDate.now()} under a UTC JVM default -- production's actual deployment, see
     * CLAUDE.md -- would have produced).
     */
    @Test
    void aFetchAtOneThirtyAmBangkokTimeUsesTheBangkokCalendarDayNotTheUtcOne() {
        Instant fixedInstant = Instant.parse("2026-09-27T18:30:00Z");
        MutableClock clock = new MutableClock(fixedInstant);
        MockedRestClient rest = mockRestClient(VALID_BOT_JSON);
        FxRateRepository repo = mock(FxRateRepository.class);
        BotFxFetchService service = serviceWithClock(repo, rest.client, clock, "fake-token");

        BotFxFetchService.FxFetchResult result = service.fetchNow();

        assertThat(result.asOf()).isEqualTo("2026-09-28");

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(rest.uriSpec, times(5)).uri(uriCaptor.capture());
        assertThat(uriCaptor.getAllValues()).allSatisfy(uri -> {
            assertThat(uri).contains("start_period=2026-09-28").contains("end_period=2026-09-28");
            assertThat(uri).doesNotContain("2026-09-27");
        });

        verify(repo, times(5)).upsertFromBot(
            anyString(), eq(new BigDecimal("36.5000")), eq(LocalDate.of(2026, 9, 28)));
    }

    /** Missing/blank token still throws the pre-existing 503, and the cooldown attempt is still
     * recorded even though the fetch itself never reached BOT -- mirrors {@code
     * BotHolidayFetchServiceTest}'s equivalent case: a misconfigured token must not be a way to
     * dodge the cooldown by retrying in a loop. */
    @Test
    void aMissingTokenStillThrowsAndStillCountsAsAManualAttempt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        RestClient restClient = mock(RestClient.class);
        BotFxFetchService service = serviceWithClock(mock(FxRateRepository.class), restClient, clock, "");

        assertThatThrownBy(service::fetchNow)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).getStatus())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(restClient);

        // The cooldown clock started on the attempt itself (before the token check), so an
        // immediate second call is refused with 429, not another 503.
        assertThatThrownBy(service::fetchNow)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).getStatus())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(restClient, never()).get();
    }

    private BotFxFetchService serviceWithClock(FxRateRepository repo, RestClient restClient, Clock clock, String token) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);
        AppProperties props = new AppProperties();
        props.getBot().setFxApiToken(token);
        return new BotFxFetchService(repo, props, builder, new ObjectMapper(), clock);
    }

    /** Bundles the mocked {@link RestClient} together with the URI-spec step of its fluent chain,
     * since {@link #aFetchAtOneThirtyAmBangkokTimeUsesTheBangkokCalendarDayNotTheUtcOne} needs to
     * capture the exact URI each currency was requested with. */
    private record MockedRestClient(RestClient client, RestClient.RequestHeadersUriSpec uriSpec) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MockedRestClient mockRestClient(String jsonBody) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(jsonBody);
        return new MockedRestClient(restClient, uriSpec);
    }

    /** A {@link Clock} a test can advance by hand, so the cooldown guard needs no real sleep. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this(instant, BANGKOK);
        }

        private MutableClock(Instant instant, ZoneId zone) {
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
}
