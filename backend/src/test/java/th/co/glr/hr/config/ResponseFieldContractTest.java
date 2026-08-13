package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Pins the response FIELD NAMES the frontend reads, and fails when the API stops serving one.
 *
 * <p><b>The gap this closes.</b> {@link ApiSurfaceContractTest} pins which operations exist —
 * verb and path. {@code contract.test.js} pins mockApi against hrApi — method names and parameter
 * counts. {@code serverContract.test.js} pins hrApi's paths against the controllers. Between them,
 * nothing looked <i>inside</i> a response. Rename {@code TicketSummaryDto.ticketCode} to
 * {@code dealCode} and every one of those guards stays green: the operation still exists, the
 * method still takes one argument, the path still resolves. The frontend reads
 * {@code deal.ticketCode}, gets {@code undefined}, and renders a blank cell in production. Under
 * {@code VITE_USE_MOCKS=true} it renders correctly forever, because mockApi never learned about the
 * rename — the same unguarded-mirror family as {@code WIN_PROBABILITY_DEFAULTS} and
 * {@code stageMeta.js}, one level further in.
 *
 * <p><b>The source of truth is springdoc's {@code /v3/api-docs}</b>, read over the real
 * {@code SecurityFilterChain} exactly as {@link ApiSurfaceContractTest} does. Reading the generated
 * document rather than parsing Java means Jackson's own serialization decides the names, so
 * {@code @JsonProperty} is honoured for free. That is not a nicety: 84 fields on this API serialize
 * under a name different from their Java record component — {@code badgeCardNo} goes on the wire as
 * {@code badge_card_no} — and a source parse that compares Java names makes whole attendance DTOs
 * look dead. Only RESPONSE schemas are collected (see {@link #liveResponseFields()}); request
 * bodies are a separate contract and are deliberately out of scope.
 *
 * <p><b>What is committed is the intersection, not the schema.</b>
 * {@code docs/api/response-fields.json} holds only the field names that BOTH appear in a response
 * schema AND are read by application code under {@code frontend/src}. That choice is what keeps
 * this guard quiet: a backend field ADDED by an unrelated PR does not appear in the file, so the
 * file does not churn, so nobody is trained to regenerate it without reading the diff. This is the
 * same reasoning {@link ApiSurfaceContractTest} gives for committing a digest rather than the whole
 * 237KB OpenAPI document, applied one level down.
 *
 * <p><b>The assertion is deliberately one-directional:</b> every pinned field must still exist.
 *
 * <ul>
 *   <li>Backend ADDS a field → live set grows, pinned set unchanged → <b>passes</b>. No churn.</li>
 *   <li>Backend REMOVES or RENAMES a field the frontend reads → <b>fails</b>. This is the bug.</li>
 *   <li>Frontend starts reading a field that was never pinned → not caught until someone
 *       regenerates. Stated plainly here rather than papered over; the completeness half needs a
 *       frontend-side scan on every run, which is a cost this guard does not pay.</li>
 * </ul>
 *
 * <p><b>When this test fails</b>, do not reflexively regenerate — the failure is the signal.
 * Decide which happened:
 *
 * <ol>
 *   <li><b>A field was renamed.</b> Update the frontend readers too, then regenerate. Regenerating
 *       alone silences the guard and ships the blank cell.</li>
 *   <li><b>A field was deliberately dropped.</b> Remove its frontend readers, then regenerate, and
 *       say so in the PR body.</li>
 * </ol>
 *
 * <pre>cd backend &amp;&amp; ./mvnw -Dtest=ResponseFieldContractTest -Dresponse.fields.update=true test</pre>
 *
 * <p>Update mode rewrites the file from scratch: it re-scans {@code frontend/src} and re-reads the
 * live schema, so it both drops fields the API no longer serves and picks up fields the frontend
 * has started reading. Regeneration is therefore the one place the completeness gap above is
 * closed, which is why the command is documented here rather than buried.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class ResponseFieldContractTest {

    /** Set {@code -Dresponse.fields.update=true} to rewrite the committed pin instead of asserting. */
    private static final String UPDATE_PROPERTY = "response.fields.update";

    private static final List<String> HTTP_METHODS =
        List.of("get", "post", "put", "patch", "delete");

    /**
     * Frontend files whose field references are NOT evidence that the application consumes a field.
     *
     * <p>{@code mockApi.js} declares the shape it invents, {@code hrApi.js} and {@code routes.js}
     * compose requests, and a test fixture asserts whatever it was written to assert. Pinning any
     * of those would let the mock's own imagination hold the backend contract in place — the exact
     * inversion CLAUDE.md warns about under "Mock API contract".
     */
    private static final List<String> NOT_APP_CODE =
        List.of("/api/mockApi", "/api/hrApi", "/api/routes", "/api/apiSurface", ".test.", "/test/");

    /** {@code .field}, {@code ['field']}, and {@code { field, other }} destructuring. */
    private static final Pattern READ_DOT = Pattern.compile("(?<![0-9])\\.\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern READ_BRACKET =
        Pattern.compile("\\[\\s*[\"']([A-Za-z_$][\\w$]*)[\"']\\s*]");
    private static final Pattern DESTRUCTURE = Pattern.compile("\\{([^{}]*?)}\\s*=");
    private static final Pattern IDENTIFIER = Pattern.compile("([A-Za-z_$][\\w$]*)");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ResponseFieldContractTest(WebApplicationContext context,
                              @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void everyPinnedResponseFieldIsStillServed() throws Exception {
        Set<String> live = liveResponseFields();

        // An empty or tiny live set means the fetch or the schema walk broke, not that the API
        // serves no fields. Without this, "every pinned field is present" would hold vacuously
        // against a rewritten-empty pin and the guard would be gone while staying green.
        assertThat(live)
            .as("springdoc returned almost no response fields — the fetch or the schema walk is"
                + " broken, not the app")
            .hasSizeGreaterThan(700);

        Path pin = pinPath();
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            write(pin, intersect(live, appReadIdentifiers()));
            return;
        }

        assertThat(Files.exists(pin))
            .as("%s is missing — regenerate with -D%s=true", pin, UPDATE_PROPERTY)
            .isTrue();

        List<String> pinned = readCommitted(pin);
        assertThat(pinned)
            .as("%s is empty — a pin that pins nothing cannot fail, so it is not a guard", pin)
            .hasSizeGreaterThan(300);

        assertThat(live)
            .as("The API stopped serving response fields the frontend reads. Each Schema.field"
                + " below is read by application code under frontend/src and is no longer on that"
                + " schema, so those readers now get `undefined` against a real backend while"
                + " staying green under VITE_USE_MOCKS=true. If a field was RENAMED, update its"
                + " readers before regenerating — regenerating alone silences this and ships the"
                + " bug. Then: `./mvnw -Dtest=ResponseFieldContractTest -D%s=true test`.",
                UPDATE_PROPERTY)
            .containsAll(pinned);
    }

    /**
     * Every {@code "SchemaName.fieldName"} reachable from a RESPONSE schema, as Jackson serializes it.
     *
     * <p>Walks {@code paths.*.<verb>.responses.*.content.*.schema} to the set of schemas that can
     * appear in a response, then closes transitively over {@code $ref}, {@code items} and
     * {@code additionalProperties} so nested DTOs count too. Request bodies are never entered, so a
     * name that exists only on a {@code *Request} record is correctly absent.
     *
     * <p><b>Qualified by schema, and that is load-bearing.</b> An earlier draft pinned bare field
     * names and a mutation-check proved it could not fail: renaming
     * {@code NotificationDto.ticketCode} to {@code dealCode} on the wire left the guard green,
     * because {@code PricingRequestSummaryDto} and {@code FactoryPurchaseOrderDto} also declare a
     * {@code ticketCode} and the flat name set still contained one. Any field name shared by two
     * DTOs was unfalsifiable — and shared names are the norm here ({@code ticketCode},
     * {@code employeeName}, {@code createdAt}). Qualifying costs nothing in accuracy: springdoc
     * already reports which schema each property belongs to, so no guess is involved.
     */
    private Set<String> liveResponseFields() throws Exception {
        String body = mvc.perform(get("/v3/api-docs").session(sessionFor("employee")))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(body);
        JsonNode schemas = root.path("components").path("schemas");

        Deque<String> queue = new ArrayDeque<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = root.path("paths").fields(); paths.hasNext(); ) {
            JsonNode operations = paths.next().getValue();
            for (Iterator<String> verbs = operations.fieldNames(); verbs.hasNext(); ) {
                String verb = verbs.next();
                if (!HTTP_METHODS.contains(verb)) continue;
                JsonNode responses = operations.path(verb).path("responses");
                for (Iterator<String> codes = responses.fieldNames(); codes.hasNext(); ) {
                    JsonNode content = responses.path(codes.next()).path("content");
                    for (Iterator<String> types = content.fieldNames(); types.hasNext(); ) {
                        collectRefs(content.path(types.next()).path("schema"), queue);
                    }
                }
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> fields = new TreeSet<>();
        while (!queue.isEmpty()) {
            String name = queue.pop();
            if (!visited.add(name)) continue;
            JsonNode schema = schemas.path(name);
            JsonNode properties = schema.path("properties");
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> property = it.next();
                fields.add(name + "." + property.getKey());
                collectRefs(property.getValue(), queue);
            }
            collectRefs(schema.path("items"), queue);
            collectRefs(schema.path("additionalProperties"), queue);
        }
        return fields;
    }

    /** Push every schema name {@code node} can reach one hop away. */
    private void collectRefs(JsonNode node, Deque<String> queue) {
        if (node == null || node.isMissingNode()) return;
        JsonNode ref = node.path("$ref");
        if (ref.isTextual()) {
            String value = ref.asText();
            queue.push(value.substring(value.lastIndexOf('/') + 1));
        }
        collectRefs(node.path("items"), queue);
        collectRefs(node.path("additionalProperties"), queue);
        for (String composite : List.of("allOf", "anyOf", "oneOf")) {
            node.path(composite).forEach(child -> collectRefs(child, queue));
        }
    }

    /**
     * Identifiers application code READS — property access, bracket access, destructuring.
     *
     * <p>Object literal KEYS are deliberately not collected. A key is the frontend declaring a
     * shape of its own; only a read is evidence that the frontend depends on the backend sending
     * something. Files in {@link #NOT_APP_CODE} are skipped for the same reason.
     */
    private Set<String> appReadIdentifiers() throws IOException {
        Path source = repositoryRoot().resolve("frontend/src");
        Set<String> read = new HashSet<>();
        try (Stream<Path> tree = Files.walk(source)) {
            tree.filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.toString().replace('\\', '/');
                    if (!name.endsWith(".js") && !name.endsWith(".jsx")) return false;
                    return NOT_APP_CODE.stream().noneMatch(name::contains);
                })
                .forEach(path -> {
                    String text;
                    try {
                        text = Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    addMatches(READ_DOT.matcher(text), read);
                    addMatches(READ_BRACKET.matcher(text), read);
                    Matcher destructured = DESTRUCTURE.matcher(text);
                    while (destructured.find()) {
                        addMatches(IDENTIFIER.matcher(destructured.group(1)), read);
                    }
                });
        }
        // A scanner that silently matched nothing would regenerate an empty pin, which the
        // emptiness assertion above would then have to catch on the NEXT run. Fail here instead.
        assertThat(read)
            .as("scanned %s and found almost no property reads — the scanner is broken", source)
            .hasSizeGreaterThan(1_000);
        return read;
    }

    private void addMatches(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    /**
     * The pinned set: {@code Schema.field} entries whose field is read by application code and is
     * distinctive enough to carry signal.
     *
     * <p>"Distinctive" means a camelCase hump or an underscore — {@code ticketCode},
     * {@code badge_card_no}. Bare single words are dropped because they carry no signal: every
     * frontend file reads {@code .id}, {@code .status} and {@code .name} off local view models, so
     * pinning {@code SomeDto.id} on the strength of that would assert a field nothing was shown to
     * depend on, and would turn every unrelated DTO tidy-up into a failure.
     *
     * <p>A field is pinned on EVERY schema that declares it, because a read of {@code .ticketCode}
     * cannot be attributed to one DTO by static analysis. That over-pins slightly — renaming
     * {@code ticketCode} on a DTO whose readers do not exist still fails — and that is the
     * deliberate direction: a wire rename should make a human look, and the alternative
     * (pin nothing shared) is what the mutation-check showed to be no guard at all.
     */
    private List<String> intersect(Set<String> live, Set<String> read) {
        return live.stream()
            .filter(qualified -> {
                String field = qualified.substring(qualified.indexOf('.') + 1);
                return read.contains(field) && isDistinctive(field);
            })
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private static boolean isDistinctive(String field) {
        if (field.indexOf('_') >= 0) return true;
        for (int i = 1; i < field.length(); i++) {
            if (Character.isUpperCase(field.charAt(i)) && Character.isLowerCase(field.charAt(i - 1))) {
                return true;
            }
        }
        return false;
    }

    private List<String> readCommitted(Path pin) throws IOException {
        JsonNode root = mapper.readTree(Files.readString(pin, StandardCharsets.UTF_8));
        List<String> fields = new ArrayList<>();
        root.path("fields").forEach(node -> fields.add(node.asText()));
        return fields;
    }

    private void write(Path pin, List<String> fields) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("$comment", "GENERATED by ResponseFieldContractTest — do not hand-edit."
            + " Regenerate: cd backend && ./mvnw -Dtest=ResponseFieldContractTest"
            + " -Dresponse.fields.update=true test");
        root.put("source", "Schema.field pairs from springdoc /v3/api-docs response schemas, kept"
            + " where the field name is read in frontend/src (excluding mockApi, hrApi, routes"
            + " and tests)");
        root.put("count", fields.size());
        ArrayNode array = root.putArray("fields");
        fields.forEach(array::add);

        // One field per line, for the same reason api-surface.json does it: the diff of this file
        // is meant to be readable as the record of a contract change, not a single 12KB line.
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        Files.createDirectories(pin.getParent());
        Files.writeString(pin,
            mapper.writer(printer).writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
    }

    private static Path pinPath() {
        return repositoryRoot().resolve("docs/api/response-fields.json");
    }

    /**
     * The repository root, found by walking up for the marker rather than hardcoding {@code ../}.
     *
     * <p>Surefire runs with {@code backend/} as the working directory but some IDEs run from the
     * repo root, and a relative path that silently resolves somewhere else would scan nothing and
     * write the pin where nothing reads it. Same idiom as {@code ApiSurfaceContractTest}.
     */
    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("CLAUDE.md"))
                && Files.isDirectory(candidate.resolve("backend"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the repository root from " + current);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }
}
