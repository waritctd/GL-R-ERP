package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;

/**
 * Slice 6c: a CHARACTERIZATION pin of {@link ImportRequestService#createDrafts}, written and run green BEFORE the
 * split was extracted for the preview, then left unchanged. It captures, for the deal in {@link SplitFixtureSupport}
 * (every branch of the cascade and the lead-time choice at once), the call's whole response AND everything it wrote:
 * the import_request and import_request_item rows, the auto-created factory, the line whose factory the pick filled
 * in, the deal row, its events and notifications, and the document sequence.
 *
 * <p>Only values that cannot be equal across runs are masked: surrogate ids, timestamps and dates, and the minted
 * IR number (which embeds the Buddhist year). Everything else — which factory got which lines in which order, the
 * quantities, the brand label, the lead time and its derived copy, the statuses and steps, the email body's text —
 * must match the golden text byte for byte. The extraction proves nothing moved by leaving this green.
 */
class CreateDraftsCharacterizationIntegrationTest extends SplitFixtureSupport {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Key names masked out: surrogate ids, timestamps, dates, the minted number. */
    private static final Pattern VOLATILE_KEY = Pattern.compile(
        "(id|.*_id|.*Id|.*_by|.*_at|.*At|.*date|.*Date|expectedArrivalFrom|expectedArrivalTo"
            + "|doc_number|docNumber|last_seq|year_th)");
    private static final Pattern IR_NUMBER = Pattern.compile("IR\\d{5}");
    private static final Pattern SLASH_DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Test
    void createDrafts_responseAndEveryRowItWrites_matchTheGoldenCapturedBeforeTheExtraction() throws Exception {
        RichDeal deal = richDeal();

        List<ImportRequestDto> response = service.createDrafts(deal.ticketId(), fullRequest(deal), owner);

        String actual = canonical(deal.ticketId(), response);
        assertThat(actual).isEqualTo(golden());
    }

    private String canonical(long ticketId, List<ImportRequestDto> response) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("== response\n").append(mask(JSON.valueToTree(response))).append('\n');
        section(out, "import_request", """
            SELECT to_jsonb(r) AS j FROM sales.import_request r WHERE r.ticket_id = :t ORDER BY r.factory_name, r.version
            """, ticketId);
        section(out, "import_request_item", """
            SELECT to_jsonb(i) AS j FROM sales.import_request_item i
              JOIN sales.import_request r ON r.import_request_id = i.import_request_id
             WHERE r.ticket_id = :t ORDER BY r.factory_name, r.version, i.seq
            """, ticketId);
        section(out, "factories", """
            SELECT to_jsonb(f) AS j FROM price_catalog.factories f WHERE f.name ILIKE 'pin%' ORDER BY f.name
            """, ticketId);
        section(out, "ticket_item", """
            SELECT to_jsonb(ti) AS j FROM sales.ticket_item ti WHERE ti.ticket_id = :t ORDER BY ti.sort_order
            """, ticketId);
        section(out, "ticket", "SELECT to_jsonb(t) AS j FROM sales.ticket t WHERE t.ticket_id = :t", ticketId);
        section(out, "ticket_event", """
            SELECT jsonb_build_object('kind', e.kind, 'n', count(*)) AS j FROM sales.ticket_event e
             WHERE e.ticket_id = :t GROUP BY e.kind ORDER BY e.kind
            """, ticketId);
        section(out, "notification", """
            SELECT jsonb_build_object('type', n.type, 'title', n.title, 'message', n.message, 'link', n.link) AS j
              FROM hr.notification n ORDER BY n.type, n.title, n.message
            """, ticketId);
        section(out, "document_sequence", """
            SELECT jsonb_build_object('doc_type', s.doc_type, 'n', count(*)) AS j FROM sales.document_sequence s
             GROUP BY s.doc_type ORDER BY s.doc_type
            """, ticketId);
        return out.toString();
    }

    private void section(StringBuilder out, String name, String sql, long ticketId) throws Exception {
        out.append("== ").append(name).append('\n');
        for (String row : jdbc.queryForList(sql, Map.of("t", ticketId), String.class)) {
            out.append(mask(JSON.readTree(row))).append('\n');
        }
    }

    private static String mask(JsonNode node) throws Exception {
        return JSON.writeValueAsString(masked(node));
    }

    private static JsonNode masked(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            ObjectNode copy = JSON.createObjectNode();
            names.stream().sorted().forEach(n -> copy.set(n, VOLATILE_KEY.matcher(n).matches()
                ? (obj.get(n).isNull() ? obj.get(n) : TextNode.valueOf("<masked>")) : masked(obj.get(n))));
            return copy;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode copy = JSON.createArrayNode();
            for (Iterator<JsonNode> it = arr.elements(); it.hasNext();) {
                copy.add(masked(it.next()));
            }
            return copy;
        }
        if (node.isTextual()) {
            String s = IR_NUMBER.matcher(node.asText()).replaceAll("IR#####");
            s = SLASH_DATE.matcher(s).replaceAll("dd/mm/yy");
            return TextNode.valueOf(ISO_DATE.matcher(s).replaceAll("yyyy-mm-dd"));
        }
        return node;
    }

    /** Captured from the code BEFORE the 6c extraction (see the class Javadoc), as a resource so the JSON needs no
     * escaping. Line endings normalized: a checkout may turn the file's LF into CRLF. */
    private static String golden() throws Exception {
        try (var in = CreateDraftsCharacterizationIntegrationTest.class
                .getResourceAsStream("/importrequest/create-drafts-split-golden.txt")) {
            assertThat(in).as("golden resource").isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
