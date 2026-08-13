package th.co.glr.hr.support;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIf;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Shared plumbing for the contract tests that read springdoc's generated OpenAPI document and
 * commit a digest of some slice of it.
 *
 * <p>There are now three such digests, published by tests written independently within a day of
 * each other: {@code ApiSurfaceContractTest} (verb+path and the {@code @Deprecated} subset),
 * {@code ResponseFieldContractTest} (Schema.field pairs the frontend reads), and
 * {@code UiReachableContractTest} (the endpoints a screen can actually reach). Each had its own
 * copy of the same five things — MockMvc over the real filter chain, the authenticated
 * {@code /v3/api-docs} fetch, {@code sessionFor}, the repository-root walk, and the digest writer.
 * {@code sessionFor} was byte-identical across two of them; the root walk differed only in its
 * method name.
 *
 * <p><b>Read the generated document, never the Java sources.</b> Jackson decides the wire names,
 * so {@code @JsonProperty} is honoured for free: <b>84 fields on this API serialize under a name
 * different from their record component</b> ({@code badgeCardNo} → {@code badge_card_no}). A source
 * parse comparing Java names makes whole attendance DTOs look dead — a rework discovered the
 * expensive way while building {@code ResponseFieldContractTest}.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
public abstract class SpringdocContractSupport {

    /** The verbs an OpenAPI path item can carry that this codebase actually uses. */
    protected static final List<String> HTTP_METHODS =
        List.of("get", "post", "put", "patch", "delete");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    protected final MockMvc mvc;
    protected final ObjectMapper mapper = new ObjectMapper();

    protected SpringdocContractSupport(WebApplicationContext context,
                                       @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        // The REAL filter chain, not a bare context: /v3/api-docs is deliberately not allowlisted
        // in SecurityConfig, so it answers 401 anonymously. Reading it needs a session, and going
        // through the real chain is what proves that is still true (OpenApiDocsIntegrationTest
        // asserts both halves).
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    /** The generated OpenAPI document, fetched over the real filter chain with a session. */
    protected JsonNode apiDocs() throws Exception {
        String body = mvc.perform(get("/v3/api-docs").session(sessionFor("employee")))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    protected MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }

    /**
     * The repository root, found by walking up for the marker rather than hardcoding {@code ../}.
     *
     * <p>Surefire runs with {@code backend/} as the working directory, but some IDEs run from the
     * repo root, and a relative path that silently resolves somewhere else would write a digest to
     * a location nothing reads — passing green while guarding nothing.
     */
    protected static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("CLAUDE.md"))
                && Files.isDirectory(candidate.resolve("backend"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the repository root from " + current);
    }

    /**
     * Write a digest with ONE ARRAY ENTRY PER LINE.
     *
     * <p>Jackson's default pretty printer puts array elements inline, rendering the whole artifact
     * as a single multi-KB line. That defeats the entire point of committing these files: their
     * DIFF is meant to be the reviewable record of a contract change. Keep the indenter.
     */
    protected void writeDigest(Path path, ObjectNode root) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        Files.createDirectories(path.getParent());
        Files.writeString(path,
            mapper.writer(printer).writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
    }

    /** The standard header every generated digest carries, so a reader knows not to hand-edit it. */
    protected ObjectNode digestRoot(String generatedBy, String updateProperty, String source) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$comment", "GENERATED by " + generatedBy + " — do not hand-edit."
            + " Regenerate: cd backend && ./mvnw -Dtest=" + generatedBy
            + " -D" + updateProperty + "=true test");
        root.put("source", source);
        return root;
    }
}
