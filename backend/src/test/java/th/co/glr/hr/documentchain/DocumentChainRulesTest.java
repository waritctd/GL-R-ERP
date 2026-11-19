package th.co.glr.hr.documentchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The server's chain rules against the ONE state table both sides read
 * ({@code frontend/src/features/documentChain/documentChainCases.json}). mockApi.js's mirror is pinned to the same
 * table by {@code documentChainCases.test.js}, so a rule changed on one side only fails one of the two.
 */
class DocumentChainRulesTest {

    private static JsonNode table() throws Exception {
        return new ObjectMapper().readTree(Files.readString(repositoryRoot()
            .resolve("frontend/src/features/documentChain/documentChainCases.json")));
    }

    static Stream<Arguments> chainCases() throws Exception {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : table().get("chain")) {
            out.add(Arguments.of(c.get("name").asText(), c));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chainCases")
    void stepStatesFollowTheTable(String name, JsonNode c) {
        DocumentChainRules.Facts facts = facts(c.get("facts"));
        assertThat(DocumentChainRules.depositState(facts)).as("depositState").isEqualTo(text(c, "depositState"));
        assertThat(DocumentChainRules.depositBlockedReason(facts)).as("depositBlockedReason")
            .isEqualTo(text(c, "depositBlockedReason"));
        assertThat(DocumentChainRules.remainingState(facts)).as("remainingState").isEqualTo(text(c, "remainingState"));
        assertThat(DocumentChainRules.remainingBlockedReason(facts)).as("remainingBlockedReason")
            .isEqualTo(text(c, "remainingBlockedReason"));
    }

    static DocumentChainRules.Facts facts(JsonNode f) {
        return new DocumentChainRules.Facts(
            text(f, "status"), text(f, "lifecycle"), text(f, "depositPolicy"), text(f, "paymentStatus"),
            text(f, "fulfillmentStatus"),
            f.path("noticeDraft").asBoolean(false), f.path("noticeIssued").asBoolean(false),
            f.path("invoiceDraft").asBoolean(false), f.path("invoiceIssued").asBoolean(false));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("backend/pom.xml"))
                && Files.isDirectory(candidate.resolve("frontend/src"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the repository root from " + current);
    }
}
