package th.co.glr.hr.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/**
 * Fetches daily average FX rates from the Bank of Thailand (BOT) API at 18:00 Bangkok time.
 * Token must be set via the {@code BOT_FX_API_TOKEN} env var (never hardcoded) — BOT issues a
 * separate key per API, so this is <strong>not</strong> the same token {@code
 * BotHolidayFetchService} uses; there is deliberately no fallback between the two (see {@code
 * AppProperties.Bot}'s javadoc for why).
 * Falls back silently when BOT has not published today's rate yet.
 *
 * <p><strong>Review fix (2026-09-28), mirroring {@code BotHolidayFetchService} exactly</strong> — the
 * CEO console's manual "ดึงเรตจาก BOT ตอนนี้" trigger ({@link #fetchNow}, added by commit 94e258c9)
 * originally had no throttle of its own. BOT enforces the same ~100 calls/hour per token budget
 * this class's sibling documents; 20 clicks (this class makes 5 BOT calls per {@link #fetchNow}
 * invocation, one per {@link #TRACKED_CURRENCIES}) exhausts that budget, and the 18:00 {@link
 * #fetchDailyRates} scheduled run would then 429 — silently, since the per-currency loop below
 * catches {@code Exception} and only logs a WARN. {@link #MANUAL_FETCH_COOLDOWN} guards the manual
 * path only, exactly as {@code BotHolidayFetchService.MANUAL_FETCH_COOLDOWN} does — see that
 * class's javadoc for the full reasoning (in-memory/per-instance, not a distributed limiter,
 * deliberately). {@link #fetchDailyRates} calls the shared fetch core directly and never touches
 * the cooldown, so a manual click can never suppress the scheduled fetch.
 *
 * <p>Also picked up a {@link Clock} seam (Bangkok-zoned in production, exactly like {@code
 * BotHolidayFetchService}) so {@link #fetchNow}'s "today" is always the Bangkok calendar day, not
 * the JVM default (production runs UTC — no TZ set in the Dockerfile/render.yaml — so a CEO
 * clicking at 01:00 Bangkok time, which is still 18:00 UTC the PREVIOUS day, would otherwise query
 * BOT for and write {@code effective_date} as yesterday).
 */
@Service
public class BotFxFetchService {

    private static final Logger log = LoggerFactory.getLogger(BotFxFetchService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String BOT_BASE_URL =
        "https://gateway.api.bot.or.th/Stat-ExchangeRate/v2/DAILY_AVG_EXG_RATE/";
    private static final List<String> TRACKED_CURRENCIES = List.of("USD", "EUR", "JPY", "CNY", "GBP");

    /**
     * Minimum interval between {@link #fetchNow()} attempts — mirrors {@code
     * BotHolidayFetchService.MANUAL_FETCH_COOLDOWN} exactly, including its reasoning: BOT's
     * ~100-calls/hour-per-token budget, a separate token per API/environment (see {@code
     * AppProperties.Bot}'s javadoc), and this guard existing solely to bound a human hammering the
     * manual trigger, not as a distributed limiter (in-memory, per-JVM-instance state; a restart
     * forgets it; N instances allow N times this rate — accepted, see the sibling class for why).
     * This class's per-click cost is higher than the holiday fetcher's (5 BOT calls per {@link
     * #fetchNow} invocation vs. its 1-2), so the same 10-minute window bounds a single instance to
     * at most 6 manual attempts/hour = 30 BOT calls/hour from this path alone, still comfortably
     * inside the 100/hour budget even stacked on the 18:00 scheduled run's own 5 calls.
     */
    static final Duration MANUAL_FETCH_COOLDOWN = Duration.ofMinutes(10);

    private final FxRateRepository fxRates;
    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** When {@link #fetchNow()} last attempted a fetch, for {@link #MANUAL_FETCH_COOLDOWN}. Never
     * touched by {@link #fetchDailyRates()} — see that method's javadoc for why. */
    private volatile Instant lastManualFetchAttemptAt;

    @Autowired
    public BotFxFetchService(FxRateRepository fxRates, AppProperties props,
                              RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this(fxRates, props, restClientBuilder, objectMapper, Clock.system(BANGKOK));
    }

    /**
     * Test-only seam: lets {@code BotFxFetchServiceTest} inject a fixed {@link Clock} so both the
     * {@link #MANUAL_FETCH_COOLDOWN} and the Bangkok-date behaviour are deterministic instead of
     * racing the real wall clock. Mirrors {@code BotHolidayFetchService}'s identical constructor.
     */
    BotFxFetchService(FxRateRepository fxRates, AppProperties props,
                       RestClient.Builder restClientBuilder, ObjectMapper objectMapper, Clock clock) {
        this.fxRates      = fxRates;
        this.props        = props;
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clock        = clock;
    }

    /**
     * What a fetch changed: how many of the {@link #TRACKED_CURRENCIES} got a rate written, out of
     * how many were attempted, for which BOT publication date. Returned by {@link #fetchNow} so the
     * CEO console can report "อัปเดต N/5 สกุล" instead of guessing from a bare 200.
     */
    public record FxFetchResult(int updated, int total, String asOf, List<String> updatedCurrencies) {}

    /**
     * The daily scheduled fetch. Calls {@link #doFetch()} <strong>directly, not {@link
     * #fetchNow()}</strong> — exactly like {@code BotHolidayFetchService.scheduledFetch()} — so this
     * never checks or updates {@link #MANUAL_FETCH_COOLDOWN}: a CEO clicking the manual button
     * moments before 18:00 must never suppress that day's scheduled fetch. Swallows the "not
     * configured" refusal as a warn — a {@code @Scheduled} method must never throw. Behaviour with a
     * configured token is byte-for-byte what it always was.
     */
    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Bangkok")
    public void fetchDailyRates() {
        try {
            doFetch();
        } catch (ApiException e) {
            log.warn("BOT FX scheduled fetch skipped: {}", e.getMessage());
        }
    }

    /**
     * Manual-trigger entry point for {@code FxRateController} (the CEO console's "ดึงเรตจาก BOT
     * ตอนนี้" button) — see that controller for the role gate. Enforces {@link
     * #MANUAL_FETCH_COOLDOWN}: a call within the cooldown of the previous manual attempt throws a
     * 429 {@link ApiException} <strong>without making any BOT call</strong>, rather than silently
     * doing nothing. The cooldown clock starts on the attempt itself (before the token check or any
     * network call), so a misconfigured/blank token cannot be used to bypass it by retrying in a
     * loop. Delegates to {@link #doFetch()} for the actual fetch, which THROWS a 503 when the token
     * is missing, so a manual caller is told the config is incomplete rather than silently doing
     * nothing.
     */
    public FxFetchResult fetchNow() {
        Instant now = clock.instant();
        Instant previous = lastManualFetchAttemptAt;
        if (previous != null) {
            Duration sinceLast = Duration.between(previous, now);
            if (sinceLast.compareTo(MANUAL_FETCH_COOLDOWN) < 0) {
                long secondsLeft = Math.max(1, MANUAL_FETCH_COOLDOWN.minus(sinceLast).toSeconds());
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "รอสักครู่ก่อนเรียกดึงอัตราแลกเปลี่ยนจากธนาคารแห่งประเทศไทยอีกครั้ง (ประมาณ "
                        + secondsLeft + " วินาที)");
            }
        }
        lastManualFetchAttemptAt = now;
        return doFetch();
    }

    /**
     * Fetches today's (Bangkok calendar day — see {@link #clock}) BOT rates. The shared core both
     * {@link #fetchDailyRates()} and {@link #fetchNow()} delegate to <em>after</em> their own,
     * different, gating decisions — this method itself has no rate-limiting logic.
     */
    private FxFetchResult doFetch() {
        String token = props.getBot().getFxApiToken();
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "ยังไม่ได้ตั้งค่า BOT_FX_API_TOKEN — ตั้งค่าก่อนจึงจะดึงอัตราจาก BOT ได้");
        }

        // Bangkok calendar day, not the JVM default -- production runs UTC (no TZ set in the
        // Dockerfile/render.yaml), so LocalDate.now() at 01:00 Bangkok (= 18:00 UTC the PREVIOUS
        // day) would query BOT for, and write effective_date as, yesterday. Mirrors
        // BotHolidayFetchService's identical Clock-based fix.
        LocalDate today = LocalDate.now(clock);
        String dateStr = today.toString(); // yyyy-MM-dd

        List<String> updated = new ArrayList<>();
        for (String currency : TRACKED_CURRENCIES) {
            try {
                String url = BOT_BASE_URL + "?start_period=" + dateStr + "&end_period=" + dateStr
                    + "&currency=" + currency;
                String json = restClient.get()
                    .uri(url)
                    .header("Authorization", token)
                    .retrieve()
                    .body(String.class);

                BigDecimal rate = parseRate(json);
                if (rate == null) {
                    log.info("BOT FX: no rate published yet for {} on {}", currency, dateStr);
                    continue;
                }
                fxRates.upsertFromBot(currency, rate, today);
                updated.add(currency);
                log.info("BOT FX: updated {} = {} THB ({})", currency, rate, dateStr);
            } catch (Exception e) {
                log.warn("BOT FX: failed to fetch {} — {}", currency, e.getMessage());
            }
        }
        log.info("BOT FX fetch completed: {}/{} currencies updated", updated.size(), TRACKED_CURRENCIES.size());
        return new FxFetchResult(updated.size(), TRACKED_CURRENCIES.size(), dateStr, updated);
    }

    private BigDecimal parseRate(String json) throws Exception {
        BotResponse response = objectMapper.readValue(json, BotResponse.class);
        if (response == null || response.result() == null
                || response.result().data() == null
                || response.result().data().dataDetail() == null
                || response.result().data().dataDetail().isEmpty()) {
            return null;
        }
        BotDataDetail detail = response.result().data().dataDetail().get(0);
        if (detail.selling() != null && !detail.selling().isBlank()) {
            return new BigDecimal(detail.selling().replaceAll(",", ""));
        }
        return null;
    }

    // BOT API response shape (unknown fields ignored)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BotResponse(BotResult result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BotResult(BotData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BotData(@JsonProperty("data_detail") List<BotDataDetail> dataDetail) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BotDataDetail(
        @JsonProperty("currency_id") String currencyId,
        String selling
    ) {}
}
