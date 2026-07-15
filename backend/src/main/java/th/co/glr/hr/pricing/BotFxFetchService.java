package th.co.glr.hr.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.config.AppProperties;

/**
 * Fetches daily average FX rates from the Bank of Thailand (BOT) API at 18:00 Bangkok time.
 * Token must be set via BOT_API_TOKEN env var (never hardcoded).
 * Falls back silently when BOT has not published today's rate yet.
 */
@Service
public class BotFxFetchService {

    private static final Logger log = LoggerFactory.getLogger(BotFxFetchService.class);
    private static final String BOT_BASE_URL =
        "https://gateway.api.bot.or.th/Stat-ExchangeRate/v2/DAILY_AVG_EXG_RATE/";
    private static final List<String> TRACKED_CURRENCIES = List.of("USD", "EUR", "JPY", "CNY", "GBP");

    private final FxRateRepository fxRates;
    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BotFxFetchService(FxRateRepository fxRates, AppProperties props,
                              RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.fxRates      = fxRates;
        this.props        = props;
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Bangkok")
    public void fetchDailyRates() {
        String token = props.getBot().getApiToken();
        if (token == null || token.isBlank()) {
            log.warn("BOT_API_TOKEN not configured — skipping FX auto-fetch");
            return;
        }

        LocalDate today = LocalDate.now();
        String dateStr = today.toString(); // yyyy-MM-dd

        int fetched = 0;
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
                fetched++;
                log.info("BOT FX: updated {} = {} THB ({})", currency, rate, dateStr);
            } catch (Exception e) {
                log.warn("BOT FX: failed to fetch {} — {}", currency, e.getMessage());
            }
        }
        log.info("BOT FX fetch completed: {}/{} currencies updated", fetched, TRACKED_CURRENCIES.size());
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
