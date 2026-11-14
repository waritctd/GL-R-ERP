package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.support.SpringdocContractSupport;

/** Publishes the API surface the application actually serves, and fails when the committed copy drifts from it.
 * This digest changes if and only if the surface changes, so its diff IS the API contract change, visible to a
 * reviewer in the PR that makes it. */
class ApiSurfaceContractTest extends SpringdocContractSupport {

    /** Set {@code -Dapi.surface.update=true} to rewrite the committed digest instead of asserting. */
    private static final String UPDATE_PROPERTY = "api.surface.update";

    @Autowired
    ApiSurfaceContractTest(WebApplicationContext context,
                           @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        super(context, securityFilterChain);
    }

    @Test
    void committedApiSurfaceMatchesWhatTheApplicationServes() throws Exception {
        Surface live = readSurface();

        // A surface that came back empty means the fetch or the parse broke, not that the app serves nothing. Without
        // this the next assertion would "pass" against a rewritten-empty digest and the guard would be gone.
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

        // Deprecation is tracked separately because it changes without the surface changing: a route severed in place
        // (V141/#702 kept three costing writes routed, @Deprecated and throwing) keeps its verb and path.
        assertThat(live.deprecated())
            .as("The set of @Deprecated endpoints changed. Regenerate as above, and check whether"
                + " any frontend caller now needs to move off a severed route.")
            .containsExactlyElementsOf(readCommitted(digest, "deprecated"));
    }

    /** Reads springdoc once and splits out the deprecated subset. Publishing that here lets the frontend guard
     * assert nothing newly starts calling a severed route WITHOUT a second Java parser to keep in step. */
    private Surface readSurface() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        List<String> operations = new ArrayList<>();
        List<String> deprecated = new ArrayList<>();
        // properties(), not the deprecated fields() — #724 cleared the Spring 7 / Boot 4 deprecation warnings and this
        // file should not reintroduce any.
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
        // Sorted so the committed file has one canonical form — otherwise the digest would churn on springdoc's
        // iteration order and every unrelated PR would show a diff here.
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
        ObjectNode root = digestRoot("ApiSurfaceContractTest", UPDATE_PROPERTY,
            "springdoc /v3/api-docs, generated from Spring's handler mappings");
        root.put("count", operations.size());
        root.put("deprecatedCount", surface.deprecated().size());
        ArrayNode array = root.putArray("operations");
        operations.forEach(array::add);
        ArrayNode deprecated = root.putArray("deprecated");
        surface.deprecated().forEach(deprecated::add);

        writeDigest(digest, root);
    }

    /** {@code docs/api/api-surface.json}, resolved from the repository root. */
    private static Path digestPath() {
        return repositoryRoot().resolve("docs/api/api-surface.json");
    }

}
