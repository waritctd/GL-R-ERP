package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.correction.AttendanceCorrectionStatus;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeLoginRecord;
import th.co.glr.hr.commission.CommissionStatus;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.leave.LeaveStatus;
import th.co.glr.hr.overtime.OvertimeStatus;
import th.co.glr.hr.pricingdecision.PricingDecisionStatus;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.specialmoney.SpecialMoneyStatus;
import th.co.glr.hr.ticket.Priority;
import th.co.glr.hr.ticket.TicketStatus;

/** Publishes every backend status/enum constant the frontend has to translate, and fails when the committed
 * copy drifts from what reflection finds today. Why this exists. The label kept advertising a workflow step
 * that no longer existed, and Import read the stale badge as the request being stuck. */
class StatusCatalogContractTest {

    /** Set {@code -Dstatus.catalog.update=true} to rewrite the committed digest instead of asserting. */
    private static final String UPDATE_PROPERTY = "status.catalog.update";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void committedStatusCatalogMatchesReflectionOverTheBackendStatusTypes() throws Exception {
        Map<String, List<String>> live = readGroups();

        // Anti-vacuity, group by group. A digest that "generated nothing" for one group must fail loudly here rather
        // than write an empty array that the frontend guard would then pass against vacuously — see this class's own
        // Javadoc on the enum-vs-constant split.
        for (Map.Entry<String, List<String>> group : live.entrySet()) {
            assertThat(group.getValue())
                .as("%s produced no constants — the extraction for this type is broken (wrong "
                    + "field name, or an enum read as a String-constant holder), not that the "
                    + "type genuinely has zero statuses", group.getKey())
                .isNotEmpty();
        }
        int total = live.values().stream().mapToInt(List::size).sum();
        assertThat(total)
            .as("total status constants across every group — floor set well under today's real "
                + "count so this fails on a wipeout, not on ordinary growth")
            .isGreaterThan(50);

        Path digest = digestPath();
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            write(digest, live);
            return;
        }

        assertThat(Files.exists(digest))
            .as("%s is missing — regenerate with -D%s=true", digest, UPDATE_PROPERTY)
            .isTrue();

        Map<String, List<String>> committed = readCommitted(digest);
        assertThat(live.keySet())
            .as("the set of status groups changed (a type was added or removed here) — "
                + "regenerate with -D%s=true, commit %s, and state the change in the PR body",
                UPDATE_PROPERTY, digest.getFileName())
            .isEqualTo(committed.keySet());
        for (String group : live.keySet()) {
            assertThat(live.get(group))
                .as("%s changed. This is a contract change: regenerate with "
                    + "`./mvnw -Dtest=StatusCatalogContractTest -D%s=true test`, commit %s, and "
                    + "state the change in the PR body.", group, UPDATE_PROPERTY, digest.getFileName())
                .containsExactlyElementsOf(committed.get(group));
        }
    }

    /** Every group, keyed by the backend type's simple name. A TreeMap so the committed digest has one canonical
     * (alphabetical) key order regardless of declaration order below. */
    private static Map<String, List<String>> readGroups() throws ReflectiveOperationException {
        Map<String, List<String>> groups = new TreeMap<>();

        // ── String-constant classes with their own canonical collection field ──────────────────
        groups.put("TicketStatus", fromCollectionField(TicketStatus.class, "VALUES"));
        groups.put("PricingRequestStatus", fromCollectionField(PricingRequestStatus.class, "VALUES"));
        groups.put("FactoryQuoteStatus", fromCollectionField(FactoryQuoteStatus.class, "VALUES"));
        // Priority names the same shape of field VALID, not VALUES, and separately declares a DEFAULT alias (= NORMAL)
        // that reading VALID deliberately sidesteps.
        groups.put("Priority", fromCollectionField(Priority.class, "VALID"));

        // ── String-constant classes with NO aggregating field — every declared constant IS a member ──
        groups.put("PricingDecisionStatus", fromPublicStringConstants(PricingDecisionStatus.class));
        groups.put("CommissionStatus", fromPublicStringConstants(CommissionStatus.class));

        // ── Java enums — zero String constants; read via getEnumConstants(), not source text ────
        groups.put("OvertimeStatus", fromEnum(OvertimeStatus.class));
        groups.put("LeaveStatus", fromEnum(LeaveStatus.class));
        groups.put("AttendanceCorrectionStatus", fromEnum(AttendanceCorrectionStatus.class));
        groups.put("SpecialMoneyStatus", fromEnum(SpecialMoneyStatus.class));

        // ── No constants field of any kind — DERIVED by calling the real branch logic ───────────
        groups.put("DivisionAccessPolicyRole", rolesFromDivisionAccessPolicy());

        return groups;
    }

    /** Reads a declared {@code Collection<String>}-shaped field (public or private) off {@code type}. */
    private static List<String> fromCollectionField(Class<?> type, String fieldName)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(null);
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalStateException(
                type.getSimpleName() + "." + fieldName + " is not a Collection — the extraction "
                    + "strategy for this type needs updating, not the field itself");
        }
        return sorted(collection.stream().map(String::valueOf));
    }

    /** Every public static final String field type declares directly. Only safe for a class with no DEFAULT-shaped
     * alias field — see this class's own Javadoc — which is verified true today for both callers
     * (PricingDecisionStatus, CommissionStatus) by reading their source, not assumed. */
    private static List<String> fromPublicStringConstants(Class<?> type) throws ReflectiveOperationException {
        List<String> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && field.getType() == String.class) {
                field.setAccessible(true);
                values.add((String) field.get(null));
            }
        }
        return sorted(values.stream());
    }

    /** Every constant of a Java enum, by name — the shape {@code String}-field reflection cannot see. */
    private static List<String> fromEnum(Class<? extends Enum<?>> type) {
        return sorted(Arrays.stream(type.getEnumConstants()).map(Enum::name));
    }

    /** Every role DivisionAccessPolicy#roleFor can actually return. The only faithful extraction is to CALL the
     * real method — one synthetic EmployeeLoginRecord per branch, collecting the distinct results — not to reflect
     * on a parallel list. */
    private static List<String> rolesFromDivisionAccessPolicy() {
        List<EmployeeLoginRecord> samples = List.of(
            sampleEmployee("md", "MD-ผู้บริหารระดับสูง", "กรรมการผู้จัดการ"),   // ceo, via the "md" code
            sampleEmployee("zz", "ZZ-ไม่มีฝ่ายนี้จริง", "ประธานกรรมการ"),        // ceo, via isExecutive — any division
            sampleEmployee("hr", "HR-บุคคล", "เจ้าหน้าที่บุคคล"),
            sampleEmployee("pcim", "PCIM-จัดซื้อต่างประเทศ", "เจ้าหน้าที่จัดซื้อ"),
            sampleEmployee("ac", "AC-ฝ่ายบัญชี", "เจ้าหน้าที่บัญชี"),
            sampleEmployee("wh", "WH-คลังสินค้า", "เจ้าหน้าที่คลังสินค้า"),
            sampleEmployee("qc", "QC-ควบคุมคุณภาพ", "เจ้าหน้าที่ QC"),
            sampleEmployee("sa", "SA-ฝ่ายขาย", "ผู้จัดการฝ่ายขาย"),             // sales_manager, position contains ผู้จัดการ
            sampleEmployee("sa", "SA-ฝ่ายขาย", "พนักงานขาย"),                  // sales, not a manager
            sampleEmployee("yy", "YY-ไม่มีฝ่ายนี้จริง", "พนักงานทั่วไป"),        // employee, the unmatched-code fallback
            sampleEmployee(null, null, null)                                   // employee, the null/blank fallback
        );
        return sorted(samples.stream().map(DivisionAccessPolicy::roleFor));
    }

    /** A minimal EmployeeLoginRecord varying only the three fields DivisionAccessPolicy#roleFor reads (division
     * code, division name, position title) — the rest are inert placeholders roleFor never looks at. */
    private static EmployeeLoginRecord sampleEmployee(String divisionCode, String divisionName, String positionName) {
        return new EmployeeLoginRecord(
            1L, "E001", "sample@example.invalid", "Sample Employee", true,
            1L, divisionCode, divisionName, positionName,
            LocalDate.now(), "hash", false, false
        );
    }

    private static List<String> sorted(Stream<String> values) {
        return values.distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private Map<String, List<String>> readCommitted(Path digest) throws IOException {
        JsonNode root = mapper.readTree(Files.readString(digest, StandardCharsets.UTF_8));
        Map<String, List<String>> groups = new TreeMap<>();
        for (Map.Entry<String, JsonNode> group : root.path("groups").properties()) {
            List<String> values = new ArrayList<>();
            group.getValue().forEach(node -> values.add(node.asText()));
            groups.put(group.getKey(), values);
        }
        return groups;
    }

    private void write(Path digest, Map<String, List<String>> groups) throws IOException {
        ObjectNode root = digestRoot();
        int total = groups.values().stream().mapToInt(List::size).sum();
        root.put("count", total);
        ObjectNode groupsNode = root.putObject("groups");
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            ArrayNode array = groupsNode.putArray(entry.getKey());
            entry.getValue().forEach(array::add);
        }
        writeDigest(digest, root);
    }

    private ObjectNode digestRoot() {
        ObjectNode root = mapper.createObjectNode();
        root.put("$comment", "GENERATED by StatusCatalogContractTest — do not hand-edit."
            + " Regenerate: cd backend && ./mvnw -Dtest=StatusCatalogContractTest -D"
            + UPDATE_PROPERTY + "=true test");
        root.put("source", "reflection over backend status String-constant classes and enums, plus "
            + "one group (DivisionAccessPolicyRole) derived by executing the real role-derivation "
            + "method — see StatusCatalogContractTest for the group list and each type's extraction "
            + "strategy");
        return root;
    }

    /** One array entry per line — see {@code SpringdocContractSupport#writeDigest}'s own Javadoc for why. */
    private void writeDigest(Path path, ObjectNode root) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        Files.createDirectories(path.getParent());
        Files.writeString(path,
            mapper.writer(printer).writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
    }

    /** The repository root, found by walking up for the marker — see {@code SpringdocContractSupport}. */
    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("backend/pom.xml")) && Files.isDirectory(candidate.resolve("docs/api"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the repository root from " + current);
    }

    /** {@code docs/api/status-catalog.json}, resolved from the repository root. */
    private static Path digestPath() {
        return repositoryRoot().resolve("docs/api/status-catalog.json");
    }
}
