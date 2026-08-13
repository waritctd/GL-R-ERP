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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Publishes the API surface the application actually serves, and fails when the committed copy
 * drifts from it.
 *
 * <p><b>Why this exists.</b> The e2e sweep used to derive its subject from the frontend's
 * {@code API_ROUTES} table — a *consumer* of the API, not the API. An endpoint no frontend route
 * happened to call was invisible to it, which is exactly the endpoint nothing else covers either.
 * A 2026-08-14 audit measured the cost: 49 backend endpoints absent from that surface, 24 more
 * swept under a verb they do not implement, and {@code routes.js}'s
 * {@code action: (id, action) => …} factory collapsing ~22 real deal actions into
 * {@code /api/tickets/999999/999999} — a path no controller maps, which still answered 401
 * anonymously and 404 authenticated and so satisfied every assertion while exercising nothing.
 *
 * <p><b>The source of truth is springdoc's {@code /v3/api-docs}</b>, read here over the real
 * {@code SecurityFilterChain} exactly as {@link OpenApiDocsIntegrationTest} does. That document is
 * generated from Spring's own handler mappings, so it is what the app will dispatch — not a
 * transcription of it. Verified against an independent parse of all 39 controller sources on
 * 2026-08-14: 276 operations, identical in both directions, zero difference.
 *
 * <p><b>What is committed is a digest, not the whole document.</b> {@code docs/api/api-surface.json}
 * holds the sorted {@code "VERB /path"} list plus the {@code @Deprecated} subset. The full 237KB document
 * churns whenever any DTO gains a field, and an artifact that churns for unrelated reasons trains
 * everyone to regenerate it without reading the diff — which is the guard failing quietly. This
 * digest changes if and only if the surface changes, so <b>its diff IS the API contract change</b>,
 * visible to a reviewer in the PR that makes it.
 *
 * <p><b>When this test fails</b>, that is the point: an endpoint was added, removed, renamed, or
 * had its verb changed. Regenerate and commit the result, and say so in the PR body:
 * <pre>cd backend &amp;&amp; ./mvnw -Dtest=ApiSurfaceContractTest -Dapi.surface.update=true test</pre>
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class ApiSurfaceContractTest {

    /** Set {@code -Dapi.surface.update=true} to rewrite the committed digest instead of asserting. */
    private static final String UPDATE_PROPERTY = "api.surface.update";

    private static final List<String> HTTP_METHODS =
        List.of("get", "post", "put", "patch", "delete");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ApiSurfaceContractTest(WebApplicationContext context,
                           @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void committedApiSurfaceMatchesWhatTheApplicationServes() throws Exception {
        Surface live = readSurface();

        // A surface that came back empty means the fetch or the parse broke, not that the app
        // serves nothing. Without this the next assertion would "pass" against a rewritten-empty
        // digest and the guard would be gone.
        assertThat(live.operations())
            .as("springdoc returned no operations — the fetch or parse is broken, not the app")
            .hasSizeGreaterThan(150);

        Path digest = digestPath();
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            write(digest, live);
            return;
        }

        assertThat(Files.exists(digest))
            .as("%s is missing — regenerate with -D%s=true", digest, UPDATE_PROPERTY)
            .isTrue();

        assertThat(live.operations())
            .as("The API surface changed. This is a contract change: regenerate with"
                + " `./mvnw -Dtest=ApiSurfaceContractTest -D%s=true test`, commit %s, and state the"
                + " change in the PR body.", UPDATE_PROPERTY, digest.getFileName())
            .containsExactlyElementsOf(readCommitted(digest, "operations"));

        // Deprecation is tracked separately because it changes without the surface changing: a
        // route severed in place (V141/#702 kept three costing writes routed, @Deprecated and
        // throwing) keeps its verb and path. Comparing only `operations` would miss that
        // entirely, and the frontend guard reads this list to assert nothing newly calls one.
        assertThat(live.deprecated())
            .as("The set of @Deprecated endpoints changed. Regenerate as above, and check whether"
                + " any frontend caller now needs to move off a severed route.")
            .containsExactlyElementsOf(readCommitted(digest, "deprecated"));
    }

    /**
     * Reads springdoc once and splits out the deprecated subset.
     *
     * <p>springdoc sets {@code deprecated: true} on an operation whose handler carries
     * {@code @Deprecated}, which is how V141 (#702) severed the three costing write routes: kept
     * routed and throwing unconditionally rather than deleted, the house pattern from
     * {@code TicketService.submit}. Publishing that here lets the frontend guard assert nothing
     * newly starts calling a severed route WITHOUT a second Java parser to keep in step.
     */
    private Surface readSurface() throws Exception {
        String body = mvc.perform(get("/v3/api-docs").session(sessionFor("employee")))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = mapper.readTree(body).path("paths");

        List<String> operations = new ArrayList<>();
        List<String> deprecated = new ArrayList<>();
        // properties(), not the deprecated fields() — #724 cleared the Spring 7 / Boot 4
        // deprecation warnings and this file should not reintroduce any.
        for (Map.Entry<String, JsonNode> entry : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : entry.getValue().properties()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                String key = operation.getKey().toUpperCase(Locale.ROOT) + " " + entry.getKey();
                operations.add(key);
                if (operation.getValue().path("deprecated").asBoolean(false)) {
                    deprecated.add(key);
                }
            }
        }
        // Sorted so the committed file has one canonical form — otherwise the digest would churn
        // on springdoc's iteration order and every unrelated PR would show a diff here.
        return new Surface(
            operations.stream().distinct().sorted(Comparator.naturalOrder()).toList(),
            deprecated.stream().distinct().sorted(Comparator.naturalOrder()).toList());
    }

    private record Surface(List<String> operations, List<String> deprecated) {
    }

    private List<String> readCommitted(Path digest, String field) throws IOException {
        JsonNode root = mapper.readTree(Files.readString(digest, StandardCharsets.UTF_8));
        List<String> values = new ArrayList<>();
        root.path(field).forEach(node -> values.add(node.asText()));
        return values;
    }

    private void write(Path digest, Surface surface) throws IOException {
        List<String> operations = surface.operations();
        ObjectNode root = mapper.createObjectNode();
        root.put("$comment", "GENERATED by ApiSurfaceContractTest — do not hand-edit."
            + " Regenerate: cd backend && ./mvnw -Dtest=ApiSurfaceContractTest"
            + " -Dapi.surface.update=true test");
        root.put("source", "springdoc /v3/api-docs, generated from Spring's handler mappings");
        root.put("count", operations.size());
        root.put("deprecatedCount", surface.deprecated().size());
        ArrayNode array = root.putArray("operations");
        operations.forEach(array::add);
        ArrayNode deprecated = root.putArray("deprecated");
        surface.deprecated().forEach(deprecated::add);

        // One operation per line. Jackson's default pretty printer puts array elements inline,
        // which renders the whole surface as a single 20KB line — and a one-line diff is exactly
        // what this file exists to avoid, since its diff is meant to BE the reviewable record of
        // an API contract change.
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        Files.createDirectories(digest.getParent());
        Files.writeString(digest,
            mapper.writer(printer).writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
    }

    /**
     * {@code docs/api/api-surface.json}, resolved from the repository root.
     *
     * <p>Surefire runs with {@code backend/} as the working directory, but this walks up looking
     * for the marker rather than hardcoding {@code ../}: the same test is run from the repo root
     * by some IDEs, and a relative path that silently resolves somewhere else would write the
     * digest to a location nothing reads.
     */
    private static Path digestPath() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("CLAUDE.md"))
                && Files.isDirectory(candidate.resolve("backend"))) {
                return candidate.resolve("docs/api/api-surface.json");
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
