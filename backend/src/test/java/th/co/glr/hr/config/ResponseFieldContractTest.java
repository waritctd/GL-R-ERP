package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.support.SpringdocContractSupport;

/** Pins the response FIELD NAMES the frontend reads, and fails when the API stops serving one. Under
 * VITE_USE_MOCKS=true it renders correctly forever, because mockApi never learned about the rename — the same
 * unguarded-mirror family as WIN_PROBABILITY_DEFAULTS and stageMeta.js, one level further in. */
class ResponseFieldContractTest extends SpringdocContractSupport {

    /** Set {@code -Dresponse.fields.update=true} to rewrite the committed pin instead of asserting. */
    private static final String UPDATE_PROPERTY = "response.fields.update";

    /** Frontend files whose field references are NOT evidence that the application consumes a field. mockApi.js
     * declares the shape it invents, hrApi.js and routes.js compose requests, and a test fixture asserts whatever
     * it was written to assert. */
    private static final List<String> NOT_APP_CODE =
        List.of("/api/mockApi", "/api/hrApi", "/api/routes", "/api/apiSurface", ".test.", "/test/");

    /** {@code .field}, {@code ['field']}, and {@code { field, other }} destructuring. */
    private static final Pattern READ_DOT = Pattern.compile("(?<![0-9])\\.\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern READ_BRACKET =
        Pattern.compile("\\[\\s*[\"']([A-Za-z_$][\\w$]*)[\"']\\s*]");
    private static final Pattern DESTRUCTURE = Pattern.compile("\\{([^{}]*?)}\\s*=");
    private static final Pattern IDENTIFIER = Pattern.compile("([A-Za-z_$][\\w$]*)");

    @Autowired
    ResponseFieldContractTest(WebApplicationContext context,
                              @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        super(context, securityFilterChain);
    }

    @Test
    void everyPinnedResponseFieldIsStillServed() throws Exception {
        Set<String> live = liveResponseFields();

        // An empty or tiny live set means the fetch or the schema walk broke, not that the API serves no fields.
        // Without this, "every pinned field is present" would hold vacuously against a rewritten-empty pin and the
        // guard would be gone while staying green.
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

    /** Every "SchemaName.fieldName" reachable from a RESPONSE schema, as Jackson serializes it. Request bodies are
     * never entered, so a name that exists only on a *Request record is correctly absent. Qualified by schema, and
     * that is load-bearing. */
    private Set<String> liveResponseFields() throws Exception {
        JsonNode root = apiDocs();
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

    /** Identifiers application code READS — property access, bracket access, destructuring. A key is the frontend
     * declaring a shape of its own; only a read is evidence that the frontend depends on the backend sending
     * something. */
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
        // A scanner that silently matched nothing would regenerate an empty pin, which the emptiness assertion above
        // would then have to catch on the NEXT run. Fail here instead.
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

    /** The pinned set: Schema.field entries whose field is read by application code and is distinctive enough to
     * carry signal. "Distinctive" means a camelCase hump or an underscore — ticketCode, badge_card_no. */
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
        ObjectNode root = digestRoot("ResponseFieldContractTest", UPDATE_PROPERTY,
            "Schema.field pairs from springdoc /v3/api-docs response schemas, kept"
            + " where the field name is read in frontend/src (excluding mockApi, hrApi, routes"
            + " and tests)");
        root.put("count", fields.size());
        ArrayNode array = root.putArray("fields");
        fields.forEach(array::add);

        writeDigest(pin, root);
    }

    private static Path pinPath() {
        return repositoryRoot().resolve("docs/api/response-fields.json");
    }

    /** The repository root, found by walking up for the marker rather than hardcoding ../. Surefire runs with
     * backend/ as the working directory but some IDEs run from the repo root, and a relative path that silently
     * resolves somewhere else would scan nothing and write the pin where nothing reads it. */

}
