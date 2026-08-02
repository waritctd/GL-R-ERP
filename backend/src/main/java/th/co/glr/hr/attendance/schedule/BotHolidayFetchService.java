package th.co.glr.hr.attendance.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.config.AppProperties;

/**
 * Fills {@code hr.holiday} (V115, seeded with zero rows) from the Bank of Thailand's "Financial
 * Institutions' Holidays" list, mirroring {@code th.co.glr.hr.pricing.BotFxFetchService}'s
 * structure: {@link RestClient}, a Bangkok-zoned {@link Scheduled} cron, the token from {@link
 * AppProperties.Bot#getApiToken()} ({@code BOT_API_TOKEN}), an {@code Authorization} header, and
 * failures logged and swallowed rather than thrown so one bad fetch never takes anything else
 * down with it.
 *
 * <p><strong>Why this is the right source at all</strong> — governing company announcement
 * ประกาศ "วันเวลาทำงาน และการหยุดงาน" (1 Oct 2567) §3: company holidays <em>are</em> the commercial
 * bank holiday list, no carve-out. BOT's Financial Institutions' Holidays list is exactly that
 * list.
 *
 * <p><strong>Package choice</strong>: sibling of {@link DbHolidayCalendar} /
 * {@link HolidayRepository} in {@code attendance.schedule}, not {@code pricing} alongside {@code
 * BotFxFetchService}. The FX fetcher lives in {@code pricing} because it feeds a pricing table
 * ({@code sales.fx_rates}); this fetcher feeds the attendance/holiday table this package already
 * owns and reads via {@link DbHolidayCalendar}. Sharing a BOT host and a code shape with the FX
 * fetcher does not make this a pricing concern.
 *
 * <p><strong>Response shape is unconfirmed.</strong> The legacy BOT API portal
 * ({@code apiportal.bot.or.th} / {@code apigw1.bot.or.th}) was discontinued 31 Dec 2025; the live
 * host is {@code gateway.api.bot.or.th} (the same host {@code BotFxFetchService} already uses).
 * Unauthenticated probing found {@code https://gateway.api.bot.or.th/financial-institutions-holidays/?year=YYYY}
 * returns 401 (exists, needs a token) while several other guessed paths 404. No authenticated
 * payload has been seen. BOT's published field names for this dataset are {@code HolidayWeekDay},
 * {@code HolidayWeekDayThai}, {@code Date}, {@code DateThai}, {@code HolidayDescription} and
 * {@code HolidayDescriptionThai} — but the envelope wrapping the list of entries is a guess, so
 * {@link #findHolidayArray} searches the whole response tree for the first array of objects that
 * carries a recognisable date field, instead of hardcoding one assumed nesting (as {@code
 * BotFxFetchService} can, because its shape has been observed). If the real payload does not match
 * — different field names, a date format {@link #parseDate} does not try, or no array at all — this
 * degrades to "zero entries parsed", which is exactly the same code path as a genuinely empty
 * response: touches no rows in {@code hr.holiday}, never a thrown exception, never a write with a
 * wrong or missing value. Unlike a genuinely empty response, this case is logged at WARN with the
 * response's field names (never values — see {@link #describeShape}) precisely so that "shape
 * changed" is distinguishable from "BOT has nothing published yet" on the first live run, rather
 * than both looking like silence. Confirming and hardening this parser against a real authenticated
 * payload once a token is available is a known follow-up, not something this branch can close out.
 */
@Service
public class BotHolidayFetchService {

    private static final Logger log = LoggerFactory.getLogger(BotHolidayFetchService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String BOT_HOLIDAY_URL = "https://gateway.api.bot.or.th/financial-institutions-holidays/";

    /** Candidate date formats, tried in order, since the real payload's format is unconfirmed. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,        // 2026-01-01
        DateTimeFormatter.ofPattern("yyyyMMdd"),  // 20260101
        DateTimeFormatter.ofPattern("dd/MM/yyyy") // 01/01/2026
    );

    /** Field names, tried case-insensitively, that might carry the Gregorian holiday date. */
    private static final List<String> DATE_FIELDS = List.of("Date", "HolidayDate");
    private static final List<String> NAME_TH_FIELDS = List.of("HolidayDescriptionThai", "HolidayDescriptionTh");
    private static final List<String> NAME_EN_FIELDS = List.of("HolidayDescription", "HolidayDescriptionEn");

    private final HolidayRepository holidays;
    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BotHolidayFetchService(HolidayRepository holidays, AppProperties props,
                                   RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.holidays     = holidays;
        this.props        = props;
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Cadence: the 1st of every month at 03:00 Bangkok — cheap (at most two HTTP calls a month) and
     * frequent enough that a mid-year BOT announcement of a special/one-off financial-institution
     * holiday is picked up within a month rather than waiting for a yearly refresh. A once-a-year
     * cron would miss exactly the announcements BOT makes outside the January publication window.
     *
     * <p>Fetches the current year every run. Also fetches next year from October (Q4) onward, since
     * BOT typically publishes next year's list late in Q4 — before that, the request predictably
     * 404s/empties, which {@link #fetchYear} already treats as "no data yet", not an error, so the
     * extra Q4 attempts are harmless and cheap.
     */
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Bangkok")
    public void scheduledFetch() {
        fetchNow();
    }

    /**
     * Fetches the current year, and next year once it is Q4, reconciling each year independently.
     * Exposed (not just {@code private}) so an admin-triggered manual refresh
     * ({@code HolidayController}) can call the exact same logic on demand instead of waiting for the
     * cron — see that controller for the role gate.
     */
    public List<FetchOutcome> fetchNow() {
        String token = props.getBot().getApiToken();
        if (token == null || token.isBlank()) {
            log.warn("BOT_API_TOKEN not configured — skipping holiday auto-fetch");
            return List.of();
        }

        List<FetchOutcome> outcomes = new ArrayList<>();
        int currentYear = Year.now(BANGKOK).getValue();
        outcomes.add(fetchYear(currentYear, token));
        if (LocalDate.now(BANGKOK).getMonthValue() >= 10) {
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
            // A future year predictably 404s/errors until BOT publishes it — log at INFO, not WARN,
            // so that expected case does not read as a real failure. Anything else (auth, network,
            // 5xx) still surfaces, just without taking the scheduler thread down with it.
            log.info("BOT holiday fetch: no data for {} ({})", year, e.getMessage());
            return new FetchOutcome(year, 0, false);
        }
    }

    /**
     * Turns an already-successful (HTTP 200) response body into a {@link FetchOutcome}, reconciling
     * and logging as appropriate. Split out from {@link #fetchYear} — which owns the network call
     * and its own failure handling — so this half (parse, decide, log, reconcile) is reachable from
     * a unit test without a real HTTP call. Package-private for exactly that: {@code
     * BotHolidayFetchServiceTest} drives the "200 but nothing parsed" WARN path directly.
     */
    FetchOutcome processResponse(String json, int year) {
        List<BankHoliday> parsed = parseHolidays(json, year);
        if (parsed.isEmpty()) {
            // HTTP succeeded (no exception on the way in) but nothing parsed out of it. That is
            // indistinguishable from "BOT legitimately has no holidays for this year yet" unless we
            // say what shape we actually got — WARN, not INFO, plus field names (never values: this
            // is a field-name-only diagnostic, nothing from the payload itself is logged) so the
            // first live run is fixable in one iteration instead of by guesswork.
            log.warn("BOT holidays: HTTP 200 but no holidays parsed for year {}; response keys "
                + "were {} — the documented shape may have changed", year, describeShape(json));
            return new FetchOutcome(year, 0, false);
        }
        holidays.reconcileBankHolidaysForYear(year, parsed);
        log.info("BOT holiday fetch: reconciled {} holiday(s) for {}", parsed.size(), year);
        return new FetchOutcome(year, parsed.size(), true);
    }

    /**
     * Defensively parses a BOT holiday-list response of unconfirmed shape. Searches the whole
     * response tree for the first JSON array whose elements are objects carrying a recognisable
     * date field, then maps each element via {@link #toHoliday}, skipping (not throwing on) any
     * element that does not parse into a valid, year-matching holiday. Malformed/non-JSON input,
     * or a response with no such array, yields an empty list rather than an exception.
     */
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

        JsonNode array = findHolidayArray(root);
        if (array == null) {
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

    /** Depth-first search for the first array-of-objects node that looks like a holiday list. */
    private JsonNode findHolidayArray(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray() && !node.isEmpty() && looksLikeHolidayArray(node)) {
            return node;
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            JsonNode found = findHolidayArray(children.next());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean looksLikeHolidayArray(JsonNode array) {
        JsonNode first = array.get(0);
        return first != null && first.isObject() && findField(first, DATE_FIELDS) != null;
    }

    private BankHoliday toHoliday(JsonNode entry, int year) {
        if (entry == null || !entry.isObject()) {
            return null;
        }
        JsonNode dateNode = findField(entry, DATE_FIELDS);
        LocalDate date = dateNode == null ? null : parseDate(dateNode.asText(null));
        if (date == null) {
            // Field names only, never the node itself: an unrecognised 200 payload may carry
            // arbitrary content, and this line is the one place a whole entry could reach the log.
            log.warn("BOT holiday fetch: skipping entry with no parseable date; entry fields were {}",
                fieldNames(entry));
            return null;
        }
        if (date.getYear() != year) {
            log.warn("BOT holiday fetch: skipping entry dated {} outside requested year {}", date, year);
            return null;
        }

        String nameTh = textOrNull(findField(entry, NAME_TH_FIELDS));
        String nameEn = textOrNull(findField(entry, NAME_EN_FIELDS));
        String name = (nameTh != null && !nameTh.isBlank()) ? nameTh : nameEn;
        if (name == null || name.isBlank()) {
            log.warn("BOT holiday fetch: skipping entry dated {} with no description", date);
            return null;
        }
        return new BankHoliday(date, name);
    }

    private static JsonNode findField(JsonNode node, List<String> candidates) {
        for (String candidate : candidates) {
            JsonNode direct = node.get(candidate);
            if (direct != null && !direct.isNull()) {
                return direct;
            }
        }
        // Case-insensitive fallback, since the confirmed field names above are a documented guess.
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            for (String candidate : candidates) {
                if (field.getKey().equalsIgnoreCase(candidate) && !field.getValue().isNull()) {
                    return field.getValue();
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node == null ? null : node.asText(null);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // try the next candidate format
            }
        }
        return null;
    }

    /**
     * Builds the diagnostic string logged when a fetch returns HTTP 200 but {@link #parseHolidays}
     * found nothing — the top-level field names, and the field names of the first element of any
     * array anywhere in the response (regardless of whether it looked like a holiday array), so a
     * human can tell in one glance whether BOT's shape changed or the year is just genuinely
     * unpublished yet. <strong>Field names only, never values</strong> — payload content is not
     * ours to spray into logs, so this never returns anything read from a field's value, only the
     * keys. Truncated to a sane length since a field-name list is not bounded by this class.
     */
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
