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

/** Fetches daily average FX rates from the Bank of Thailand (BOT) API at 18:00 Bangkok time. Falls back
 * silently when BOT has not published today's rate yet. */
@Service
public class BotFxFetchService {

    private static final Logger log = LoggerFactory.getLogger(BotFxFetchService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String BOT_BASE_URL =
        "https://gateway.api.bot.or.th/Stat-ExchangeRate/v2/DAILY_AVG_EXG_RATE/";
    private static final List<String> TRACKED_CURRENCIES = List.of("USD", "EUR", "JPY", "CNY", "GBP");

    /** Minimum interval between #fetchNow attempts. */
    static final Duration MANUAL_FETCH_COOLDOWN = Duration.ofMinutes(10);

    private final FxRateRepository fxRates;
    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** When #fetchNow() last attempted a fetch, for #MANUAL_FETCH_COOLDOWN. Never touched by #fetchDailyRates() —
     * see that method's javadoc for why. */
    private volatile Instant lastManualFetchAttemptAt;

    @Autowired
    public BotFxFetchService(FxRateRepository fxRates, AppProperties props,
                              RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this(fxRates, props, restClientBuilder, objectMapper, Clock.system(BANGKOK));
    }

    /** Test-only seam: lets BotFxFetchServiceTest inject a fixed Clock so both the #MANUAL_FETCH_COOLDOWN and the
     * Bangkok-date behaviour are deterministic instead of racing the real wall clock. Mirrors
     * BotHolidayFetchService's identical constructor. */
    BotFxFetchService(FxRateRepository fxRates, AppProperties props,
                       RestClient.Builder restClientBuilder, ObjectMapper objectMapper, Clock clock) {
        this.fxRates      = fxRates;
        this.props        = props;
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clock        = clock;
    }

    /** What a fetch changed: how many of the #TRACKED_CURRENCIES got a rate written, out of how many were
     * attempted, for which BOT publication date. Returned by #fetchNow so the CEO console can report "อัปเดต N/5
     * สกุล" instead of guessing from a bare 200. */
    public record FxFetchResult(int updated, int total, String asOf, List<String> updatedCurrencies) {}

    /** The daily scheduled fetch. Calls #doFetch() directly, not #fetchNow() — exactly like
     * BotHolidayFetchService.scheduledFetch() — so this never checks or updates #MANUAL_FETCH_COOLDOWN: a CEO
     * clicking the manual button moments before 18:00 must never suppress that day's scheduled fetch. */
    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Bangkok")
    public void fetchDailyRates() {
        try {
            doFetch();
        } catch (ApiException e) {
            log.warn("BOT FX scheduled fetch skipped: {}", e.getMessage());
        }
    }

    /** Manual-trigger entry point for FxRateController (the CEO console's "ดึงเรตจาก BOT ตอนนี้" button) — see that
     * controller for the role gate. */
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

    /** Fetches today's (Bangkok calendar day — see #clock) BOT rates. The shared core both #fetchDailyRates() and
     * #fetchNow() delegate to after their own, different, gating decisions — this method itself has no
     * rate-limiting logic. */
    private FxFetchResult doFetch() {
        String token = props.getBot().getFxApiToken();
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "ยังไม่ได้ตั้งค่า BOT_FX_API_TOKEN — ตั้งค่าก่อนจึงจะดึงอัตราจาก BOT ได้");
        }

        // Bangkok calendar day, not the JVM default -- production runs UTC (no TZ set in the Dockerfile/render.yaml),
        // so LocalDate.now() at 01:00 Bangkok (= 18:00 UTC the PREVIOUS day) would query BOT for, and write
        // effective_date as, yesterday. Mirrors BotHolidayFetchService's identical Clock-based fix.
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
