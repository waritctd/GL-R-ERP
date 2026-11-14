package th.co.glr.hr.attendance.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/** Fetches bank holidays into hr.holiday using BOT_HOLIDAY_API_TOKEN; accepts known response envelopes and logs
 * failures without interrupting the app. */
@Service
public class BotHolidayFetchService {

    private static final Logger log = LoggerFactory.getLogger(BotHolidayFetchService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String BOT_HOLIDAY_URL = "https://gateway.api.bot.or.th/financial-institutions-holidays/";

    /** Minimum interval between #fetchNow() (the manual-trigger path — HolidayController) attempts. Scheduled load
     * never threatens that on its own: this fetcher makes at most 2 calls/month. */
    static final Duration MANUAL_FETCH_COOLDOWN = Duration.ofMinutes(10);

    private final HolidayRepository holidays;
    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** When #fetchNow() last attempted a fetch, for #MANUAL_FETCH_COOLDOWN. Never touched by #scheduledFetch() —
     * see that method's javadoc for why. */
    private volatile Instant lastManualFetchAttemptAt;

    @Autowired
    public BotHolidayFetchService(HolidayRepository holidays, AppProperties props,
                                   RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this(holidays, props, restClientBuilder, objectMapper, Clock.system(BANGKOK));
    }

    /** Test-only seam: lets BotHolidayFetchServiceTest inject a fixed Clock so #MANUAL_FETCH_COOLDOWN assertions
     * are deterministic instead of racing the real wall clock. */
    BotHolidayFetchService(HolidayRepository holidays, AppProperties props,
                            RestClient.Builder restClientBuilder, ObjectMapper objectMapper, Clock clock) {
        this.holidays     = holidays;
        this.props        = props;
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clock        = clock;
    }

    /** Cadence: the 1st of every month at 03:00 Bangkok — cheap (at most two HTTP calls a month) and frequent
     * enough that a mid-year BOT announcement of a special/one-off financial-institution holiday is picked up
     * within a month rather than waiting for a yearly refresh. */
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Bangkok")
    public void scheduledFetch() {
        runFetch();
    }

    /** Manual-trigger entry point for HolidayController — see that controller for the role gate. The cooldown clock
     * starts on the attempt itself (before the token check or any network call), so a misconfigured/blank token
     * cannot be used to bypass it by retrying in a loop. */
    public List<FetchOutcome> fetchNow() {
        Instant now = clock.instant();
        Instant previous = lastManualFetchAttemptAt;
        if (previous != null) {
            Duration sinceLast = Duration.between(previous, now);
            if (sinceLast.compareTo(MANUAL_FETCH_COOLDOWN) < 0) {
                long secondsLeft = Math.max(1, MANUAL_FETCH_COOLDOWN.minus(sinceLast).toSeconds());
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "รอสักครู่ก่อนเรียกดึงข้อมูลวันหยุดจากธนาคารแห่งประเทศไทยอีกครั้ง (ประมาณ "
                        + secondsLeft + " วินาที)");
            }
        }
        lastManualFetchAttemptAt = now;
        return runFetch();
    }

    /** Fetches the current year, and next year once it is Q4, reconciling each year independently. The shared core
     * both #scheduledFetch() and #fetchNow() delegate to after their own, different, gating decisions — this
     * method itself has no rate-limiting logic. */
    private List<FetchOutcome> runFetch() {
        String token = props.getBot().getHolidayApiToken();
        if (token == null || token.isBlank()) {
            log.warn("BOT_HOLIDAY_API_TOKEN not configured — skipping holiday auto-fetch");
            return List.of();
        }

        List<FetchOutcome> outcomes = new ArrayList<>();
        int currentYear = Year.now(clock).getValue();
        outcomes.add(fetchYear(currentYear, token));
        if (LocalDate.now(clock).getMonthValue() >= 10) {
            outcomes.add(fetchYear(currentYear + 1, token));
        }
        return outcomes;
    }

    private FetchOutcome fetchYear(int year, String token) {
        try {
            String json = restClient.get()
                .uri(BOT_HOLIDAY_URL + "?year=" + year)
                .header("Authorization", token)
                .retrieve()
                .body(String.class);
            return processResponse(json, year);
        } catch (Exception e) {
            // Reaches here only for a failure BEFORE or DURING the HTTP call itself -- a future year predictably
            // 404s/errors until BOT publishes it, or a genuine network/auth failure.
            log.info("BOT holiday fetch: no data for {} ({})", year, e.getMessage());
            return new FetchOutcome(year, 0, false);
        }
    }

    /** Turns an already-successful (HTTP 200) response body into a FetchOutcome, reconciling and logging as
     * appropriate. Package-private for exactly that: BotHolidayFetchServiceTest drives the "200 but nothing
     * parsed" WARN path, and the database-write-failure WARN below, directly. */
    FetchOutcome processResponse(String json, int year) {
        List<BankHoliday> parsed = parseHolidays(json, year);
        if (parsed.isEmpty()) {
            // HTTP succeeded (no exception on the way in) but nothing parsed out of it.
            log.warn("BOT holidays: HTTP 200 but no holidays parsed for year {}; response keys "
                + "were {} — the documented shape may have changed", year, describeShape(json));
            return new FetchOutcome(year, 0, false);
        }
        try {
            holidays.reconcileBankHolidaysForYear(year, parsed);
        } catch (DataAccessException e) {
            // Our bug, not BOT's silence: WARN, and say plainly that parsing succeeded but the write did not, including
            // the cause — never share wording with fetchYear's routine "no data" INFO log.
            log.warn("BOT holiday fetch: {} parsed {} holiday(s) successfully but the database write "
                + "failed — {}: {}", year, parsed.size(), e.getClass().getSimpleName(), e.getMessage(), e);
            return new FetchOutcome(year, 0, false);
        }
        log.info("BOT holiday fetch: reconciled {} holiday(s) for {}", parsed.size(), year);
        return new FetchOutcome(year, parsed.size(), true);
    }

    /** Strictly parses a BOT holiday-list response by locating the holiday array via #resolveHolidayArray and
     * mapping each element via #toHoliday, skipping (not throwing on) any element that does not parse into a
     * valid, year-matching holiday. */
    List<BankHoliday> parseHolidays(String json, int year) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("BOT holiday fetch: response for {} was not valid JSON — {}", year, e.getMessage());
            return List.of();
        }
        JsonNode array = resolveHolidayArray(root);
        if (array == null) {
            // None of the named candidates matched. Deliberately does not fall back to searching the tree for something
            // array-shaped — see the class javadoc on why that hedge was removed.
            return List.of();
        }

        List<BankHoliday> result = new ArrayList<>();
        for (JsonNode entry : array) {
            BankHoliday holiday = toHoliday(entry, year);
            if (holiday != null) {
                result.add(holiday);
            }
        }
        return result;
    }

    /** Locates the holiday array within an ordered list of named candidate locations, returning the first candidate
     * that is a non-empty JSON array whose first element carries a Date field — enough to tell a holiday list
     * apart from any other array BOT might send, without reading further into the entry. */
    private static JsonNode resolveHolidayArray(JsonNode root) {
        // 1. The documented shape (BOT's OpenAPI spec): a bare top-level array. Kept first so a future spec-conforming
        // response still works without touching this method.
        if (isHolidayArray(root)) {
            return root;
        }
        // 2. The observed live shape, confirmed against production 2026-08-03: {"result": [...]}. This is the one
        // actually hit in practice today — see the class javadoc.
        JsonNode result = pathTo(root, "result");
        if (isHolidayArray(result)) {
            return result;
        }
        // 3. One layer deeper than (2), in case BOT nests it under "data" the way other BOT APIs do.
        JsonNode resultData = pathTo(result, "data");
        if (isHolidayArray(resultData)) {
            return resultData;
        }
        // 4. The FX API's own envelope shape (see BotFxFetchService): result.data.data_detail. Cheap insurance — BOT
        // already uses this exact shape elsewhere on the same gateway.
        JsonNode resultDataDetail = pathTo(resultData, "data_detail");
        if (isHolidayArray(resultDataDetail)) {
            return resultDataDetail;
        }
        return null;
    }

    /** {@code null}-safe single-field lookup: {@code node.field} if {@code node} is an object, else {@code null}. */
    private static JsonNode pathTo(JsonNode node, String field) {
        return (node != null && node.isObject()) ? node.get(field) : null;
    }

    /** A candidate matches when it is a non-empty array whose first element is an object carrying a {@code Date} field. */
    private static boolean isHolidayArray(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty()
            && node.get(0).isObject() && node.get(0).has("Date");
    }

    private BankHoliday toHoliday(JsonNode entry, int year) {
        if (entry == null || !entry.isObject()) {
            return null;
        }
        JsonNode dateNode = entry.get("Date");
        LocalDate date = (dateNode == null || dateNode.isNull()) ? null : parseDate(dateNode.asText(null));
        if (date == null) {
            // Field names only, never the node itself: an unrecognised 200 payload may carry arbitrary content, and this
            // line is the one place a whole entry could reach the log.
            log.warn("BOT holiday fetch: skipping entry with no parseable Date; entry fields were {}",
                fieldNames(entry));
            return null;
        }
        if (date.getYear() != year) {
            log.warn("BOT holiday fetch: skipping entry dated {} outside requested year {}", date, year);
            return null;
        }

        // HolidayWeekDay/HolidayWeekDayThai/DateThai are deliberately not read — DateThai is Buddhist-era and would
        // need a calendar conversion this class does not implement; Date is unambiguous and sufficient. See the class
        // javadoc.
        String nameTh = textOrNull(entry.get("HolidayDescriptionThai"));
        String nameEn = textOrNull(entry.get("HolidayDescription"));
        String name = (nameTh != null && !nameTh.isBlank()) ? nameTh : nameEn;
        if (name == null || name.isBlank()) {
            log.warn("BOT holiday fetch: skipping entry dated {} with no description", date);
            return null;
        }
        return new BankHoliday(date, name);
    }

    private static String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText(null);
    }

    /** {@code Date} is documented as ISO {@code yyyy-MM-dd} — no format guessing needed. */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Builds the diagnostic string logged when a fetch returns HTTP 200 but #parseHolidays found nothing. Field
     * names only, never values — payload content is not ours to spray into logs, so this never returns anything
     * read from a field's value, only the keys. */
    private String describeShape(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String topLevelKeys = root.isObject() ? fieldNames(root)
                : root.isArray() ? "<array of " + root.size() + " element(s)>"
                : "<" + root.getNodeType() + ">";
            JsonNode firstArrayElement = findFirstArrayElement(root);
            String elementKeys = (firstArrayElement != null && firstArrayElement.isObject())
                ? fieldNames(firstArrayElement)
                : "none";
            return truncate("topLevel=" + topLevelKeys + ", firstArrayElement=" + elementKeys, 500);
        } catch (Exception e) {
            return "<response was not valid JSON>";
        }
    }

    private static String fieldNames(JsonNode objectNode) {
        List<String> names = new ArrayList<>();
        objectNode.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    /** Depth-first search for the first non-empty array anywhere in the tree, for diagnostics only. */
    private static JsonNode findFirstArrayElement(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0);
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            JsonNode found = findFirstArrayElement(children.next());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }

    /** Outcome of one year's fetch attempt, returned to the manual-trigger endpoint. */
    public record FetchOutcome(int year, int holidayCount, boolean applied) {}
}
