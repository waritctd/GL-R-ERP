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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import th.co.glr.hr.attendance.schedule.HolidayRepository.BankHoliday;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/**
 * Fills {@code hr.holiday} (V115, seeded with zero rows) from the Bank of Thailand's "Financial
 * Institutions' Holidays" list, mirroring {@code th.co.glr.hr.pricing.BotFxFetchService}'s
 * structure: {@link RestClient}, a Bangkok-zoned {@link Scheduled} cron, the token from {@link
 * AppProperties.Bot#getHolidayApiToken()} ({@code BOT_HOLIDAY_API_TOKEN}), an {@code Authorization}
 * header, and failures logged and swallowed rather than thrown so one bad fetch never takes
 * anything else down with it. BOT issues a separate key per API — {@code BOT_HOLIDAY_API_TOKEN} is
 * <strong>not</strong> the same token {@code BotFxFetchService} uses ({@code BOT_FX_API_TOKEN}),
 * and there is deliberately no fallback between the two; see {@code AppProperties.Bot}'s javadoc.
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
 * <p><strong>Endpoint and shape are confirmed against BOT's published OpenAPI spec</strong> (the
 * owner supplied it directly — this is no longer inference from unauthenticated probing). The
 * legacy BOT API portal ({@code apiportal.bot.or.th} / {@code apigw1.bot.or.th}) was discontinued
 * 31 Dec 2025; the live host is {@code gateway.api.bot.or.th} (the same host {@code
 * BotFxFetchService} already uses). The security scheme is labelled {@code X-IBM-Client-Id} in the
 * spec, but its definition is {@code {"type":"apiKey","name":"Authorization","in":"header"}} — in
 * OpenAPI, {@code name} is the actual header, so the real header is {@code Authorization}, exactly
 * as {@code BotFxFetchService} sends it. Do not add an {@code X-IBM-Client-Id} header; the label is
 * misleading, not a second requirement. {@code year} (format {@code YYYY}) is a required query
 * parameter.
 *
 * <p>The response is a <strong>bare top-level JSON array</strong> — not an envelope like the FX
 * API's {@code result.data.data_detail} — of objects with string fields {@code HolidayWeekDay},
 * {@code HolidayWeekDayThai}, {@code Date} (ISO {@code yyyy-MM-dd}), {@code DateThai} (Buddhist-era
 * {@code dd/MM/yy}), {@code HolidayDescription} and {@code HolidayDescriptionThai}. {@link
 * #parseHolidays} matches this exactly: a non-array top level, or an element missing {@code Date}
 * or both description fields, does not match and is skipped rather than coerced — no tree-searching
 * fallback. An earlier version of this class searched the whole response tree for anything that
 * looked like a holiday list, as a hedge for when nobody could see a real payload; now that the
 * shape is confirmed, that hedge is deleted deliberately, because it could latch onto an unrelated
 * array in a differently-shaped response and misparse it as holidays. A shape that does not match
 * now fails to match, full stop, rather than possibly matching something wrong.
 *
 * <p>{@code DateThai} and {@code HolidayWeekDay}/{@code HolidayWeekDayThai} are read by nobody
 * here. {@code Date} is unambiguous (ISO, Gregorian); {@code DateThai} is Buddhist-era and would
 * need a calendar conversion this class does not implement — if a future need for it appears, treat
 * that conversion as a deliberate addition, not something to bolt on quietly.
 *
 * <p>If a live response nonetheless does not match this shape — BOT changes the contract, or the
 * spec turns out to have an error the owner didn't catch — {@link #parseHolidays} degrades to "zero
 * entries parsed", the same code path as a genuinely empty year: touches no rows in {@code
 * hr.holiday}, never a thrown exception, never a write with a wrong or missing value. That case is
 * logged at WARN with the response's field names (never values — see {@link #describeShape}) so
 * "shape changed" is distinguishable from "BOT has nothing published yet" on the day this first
 * runs live, rather than both looking like silence.
 */
@Service
public class BotHolidayFetchService {

    private static final Logger log = LoggerFactory.getLogger(BotHolidayFetchService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String BOT_HOLIDAY_URL = "https://gateway.api.bot.or.th/financial-institutions-holidays/";

    /**
     * Minimum interval between {@link #fetchNow()} (the manual-trigger path — {@code
     * HolidayController}) attempts. BOT enforces a budget of <strong>100 calls/hour per token</strong>,
     * and — per the owner, confirmed 2026-08 — <strong>every BOT API gets its own key, and every
     * environment gets its own key per API</strong>: this fetcher's {@code BOT_HOLIDAY_API_TOKEN} is
     * distinct from {@code BotFxFetchService}'s {@code BOT_FX_API_TOKEN}, and each is set separately
     * per environment (see {@code AppProperties.Bot}'s javadoc — two earlier drafts of this comment
     * claimed a token was shared, first across environments and then across APIs within one
     * environment; neither is true, and this fetcher's 100/hour is now entirely its own, unaffected
     * by anything the FX fetcher does). Scheduled load never threatens that on its own: this fetcher
     * makes at most 2 calls/month. The actual risk is {@code POST /api/holidays/fetch} itself, which
     * has no throttle of its own: a double-submit, a frontend retry loop, or someone hammering a
     * refresh button is the only realistic way to burn this token's 100/hour. Ten minutes bounds a
     * single instance to at most 6 manual attempts/hour, comfortably inside that ceiling even stacked
     * on top of the scheduled calls.
     *
     * <p><strong>What this guarantees and what it does not</strong>: this is in-memory,
     * per-JVM-instance state, not a distributed limiter — N running instances allow N times this
     * rate, and a restart forgets the cooldown entirely. That is accepted and deliberate: it is a
     * sane-use guard against an accidental hot loop from a single browser tab or process, not a hard
     * cap enforceable across a fleet. If this repo ever runs multiple backend instances behind a
     * load balancer, revisit whether that gap actually matters before reaching for a shared store —
     * do not "fix" this into a table pre-emptively.
     */
    static final Duration MANUAL_FETCH_COOLDOWN = Duration.ofMinutes(10);

    private final HolidayRepository holidays;
    private final AppProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** When {@link #fetchNow()} last attempted a fetch, for {@link #MANUAL_FETCH_COOLDOWN}. Never
     * touched by {@link #scheduledFetch()} — see that method's javadoc for why. */
    private volatile Instant lastManualFetchAttemptAt;

    @Autowired
    public BotHolidayFetchService(HolidayRepository holidays, AppProperties props,
                                   RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this(holidays, props, restClientBuilder, objectMapper, Clock.system(BANGKOK));
    }

    /**
     * Test-only seam: lets {@code BotHolidayFetchServiceTest} inject a fixed {@link Clock} so
     * {@link #MANUAL_FETCH_COOLDOWN} assertions are deterministic instead of racing the real wall
     * clock. Mirrors the {@code DashboardService(repository, Clock)} pattern already used in this
     * codebase rather than introducing a {@code Clock} bean nothing else needs.
     */
    BotHolidayFetchService(HolidayRepository holidays, AppProperties props,
                            RestClient.Builder restClientBuilder, ObjectMapper objectMapper, Clock clock) {
        this.holidays     = holidays;
        this.props        = props;
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clock        = clock;
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
     *
     * <p>Calls {@link #runFetch()} <strong>directly, not {@link #fetchNow()}</strong>, and therefore
     * never checks or updates {@link #MANUAL_FETCH_COOLDOWN}. That cooldown exists solely to bound a
     * human hammering the manual-trigger endpoint; a monthly cron tick could never approach the
     * 100/hour budget on its own, and routing it through the same guarded method would risk a
     * scheduled run silently doing nothing because a manual click happened minutes earlier — exactly
     * the "cooldown swallows the cron" failure this split is designed to make structurally
     * impossible rather than something a future edit could reintroduce by accident.
     */
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Bangkok")
    public void scheduledFetch() {
        runFetch();
    }

    /**
     * Manual-trigger entry point for {@code HolidayController} — see that controller for the role
     * gate. Enforces {@link #MANUAL_FETCH_COOLDOWN}: a call within the cooldown of the previous
     * manual attempt throws a 429 {@link ApiException} <strong>without making any BOT call</strong>,
     * rather than silently returning an empty result that would read as "fetch happened, no
     * holidays" to a caller. The cooldown clock starts on the attempt itself (before the token check
     * or any network call), so a misconfigured/blank token cannot be used to bypass it by retrying
     * in a loop.
     */
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

    /**
     * Fetches the current year, and next year once it is Q4, reconciling each year independently.
     * The shared core both {@link #scheduledFetch()} and {@link #fetchNow()} delegate to <em>after</em>
     * their own, different, gating decisions — this method itself has no rate-limiting logic.
     */
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
     * Strictly parses a BOT holiday-list response against the confirmed OpenAPI schema: a bare
     * top-level JSON array of objects. Maps each element via {@link #toHoliday}, skipping (not
     * throwing on) any element that does not parse into a valid, year-matching holiday. A
     * non-array top level, malformed/non-JSON input, or an empty array all yield an empty list
     * rather than an exception — this method never throws.
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
        if (!root.isArray()) {
            // The documented shape is a bare top-level array. Anything else — an envelope, an
            // error object, a bare string — does not match it, deliberately: see the class javadoc
            // on why this no longer falls back to searching the tree for something array-shaped.
            return List.of();
        }

        List<BankHoliday> result = new ArrayList<>();
        for (JsonNode entry : root) {
            BankHoliday holiday = toHoliday(entry, year);
            if (holiday != null) {
                result.add(holiday);
            }
        }
        return result;
    }

    private BankHoliday toHoliday(JsonNode entry, int year) {
        if (entry == null || !entry.isObject()) {
            return null;
        }
        JsonNode dateNode = entry.get("Date");
        LocalDate date = (dateNode == null || dateNode.isNull()) ? null : parseDate(dateNode.asText(null));
        if (date == null) {
            // Field names only, never the node itself: an unrecognised 200 payload may carry
            // arbitrary content, and this line is the one place a whole entry could reach the log.
            log.warn("BOT holiday fetch: skipping entry with no parseable Date; entry fields were {}",
                fieldNames(entry));
            return null;
        }
        if (date.getYear() != year) {
            log.warn("BOT holiday fetch: skipping entry dated {} outside requested year {}", date, year);
            return null;
        }

        // HolidayWeekDay/HolidayWeekDayThai/DateThai are deliberately not read — DateThai is
        // Buddhist-era and would need a calendar conversion this class does not implement; Date is
        // unambiguous and sufficient. See the class javadoc.
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
