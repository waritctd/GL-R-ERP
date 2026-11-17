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
import org.junit.jupiter.api.Test;
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

    static Stream<Arguments> listCases() throws Exception {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : table().get("listStage")) {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("listCases")
    void listStageFollowsTheTable(String name, JsonNode c) {
        Long confirmedId = c.get("confirmedQuotationId").isNull() ? null : c.get("confirmedQuotationId").asLong();
        assertThat(DocumentChainRules.listStage(facts(c.get("facts")), c.get("promoted").asBoolean(), confirmedId,
            c.get("quotationId").asLong())).isEqualTo(text(c, "stage"));
    }

    /** The table is not vacuous: every state and reason code the rules can return appears in it at least once. */
    @Test
    void theTableCoversEveryStateAndReason() throws Exception {
        JsonNode t = table();
        List<String> seen = new ArrayList<>();
        for (JsonNode c : t.get("chain")) {
            for (String f : List.of("depositState", "depositBlockedReason", "remainingState", "remainingBlockedReason")) {
                if (!c.get(f).isNull()) seen.add(c.get(f).asText());
            }
        }
        for (JsonNode c : t.get("listStage")) {
            if (!c.get("stage").isNull()) seen.add(c.get("stage").asText());
        }
        assertThat(seen).contains(
            "NOT_REQUIRED", "NOT_YET", "TO_CREATE", "DRAFT", "ISSUED", "PAID",
            "WAITING", "NOT_NEEDED",
            "ORDER_NOT_CONFIRMED", "DEPOSIT_NOT_RECEIVED", "GOODS_NOT_READY", "DEAL_NOT_ACTIVE",
            "AWAITING_ORDER", "AWAITING_DEPOSIT_NOTICE", "NO_DEPOSIT", "DEPOSIT_NOTICE_ISSUED", "REMAINING_INVOICE_ISSUED");
    }

    @Test
    void orderConfirmedIsThePaymentTrackHavingStarted() {
        assertThat(DocumentChainRules.orderConfirmed(new DocumentChainRules.Facts(
            "draft", "ACTIVE", "REQUIRED", null, null, false, false, false, false))).isFalse();
        assertThat(DocumentChainRules.orderConfirmed(new DocumentChainRules.Facts(
            "quotation_issued", "ACTIVE", "REQUIRED", "CUSTOMER_CONFIRMED", null, false, false, false, false))).isTrue();
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
