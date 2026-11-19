package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** THE GUARD THIS CLASS EXISTS TO ADD: every value-list CHECK constraint on sales.ticket that has a
 * hand-maintained Java mirror must permit exactly what that mirror declares. Do not "fix" isValid or add
 * validation nobody needs. */
class DealStageCheckConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SCHEMA = "sales";
    private static final String TABLE = "ticket";
    private static final String CONSTRAINT = "chk_ticket_sales_stage";

    /** How Postgres renders col IN ('a','b') for a scalar column — as (col)::text = ANY ((ARRAY['a'::character
     * varying, ...])::text[]). */
    private static final String ARRAY_OPEN = "ARRAY[";

    /** One element of that array: a quoted literal plus the type cast Postgres prints after it. Every
     * comma-separated element must match — see #permittedValues for why "must" is the whole point. */
    private static final Pattern ARRAY_ELEMENT =
        Pattern.compile("\\s*'([^']*)'(?:::[A-Za-z ]+)?\\s*");

    private TicketRepository tickets;
    private long ticketId;

    @BeforeEach
    void createOneRealDealToWriteStagesOnto() {
        tickets = new TicketRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long ownerId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย ทดสอบขั้นตอน", null, null, null, null, null, null, null,
            "sales-stage-check@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        ticketId = tickets.create(
            new CreateTicketRequest("ดีลทดสอบ CHECK constraint", "NORMAL", "ลูกค้าทดสอบ",
                null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), ownerId, "พนักงานขาย ทดสอบขั้นตอน");
    }

    /** Checks the database constraint and DealStage.ORDER contain exactly the same stage codes. */
    @Test
    void theConstraintPermitsExactlyTheStagesDealStageOrderDeclares() {
        Set<String> permitted = permittedStages();
        Set<String> declared = Set.copyOf(DealStage.ORDER);

        assertThat(permitted)
            .as("%s permits a value DealStage.ORDER does not declare — a migration widened the "
                + "CHECK and the Java mirror was not updated, so a row can hold a stage the "
                + "application cannot label, gate, order or phase", CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(declared);

        // Stated the other way round as well, so the failure message names the right side of the mirror whichever one
        // drifted. containsExactlyInAnyOrder above already covers both, but its message reads from the constraint's
        // point of view only.
        assertThat(declared)
            .as("DealStage.ORDER declares a stage %s does not permit — writing a deal to it fails "
                + "with a constraint violation at runtime. V143's closing comment asks for a "
                + "forward migration here; this is that comment, enforced", CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(permitted);
    }

    /** ANTI-VACUITY for the two probes above: the constraint is still ENFORCING. The deal must also still be
     * sitting where it was: a rejected statement must not have moved it. That is what makes it independent
     * evidence for the probes rather than a second copy of them. */
    @Test
    void anUnknownStageIsStillRejected_soTheProbesAboveAreNotVacuous() {
        tickets.updateSalesStage(ticketId, DealStage.NEGOTIATION);

        assertThatThrownBy(() -> tickets.updateSalesStage(ticketId, "NOT_A_STAGE"))
            .as("%s no longer rejects an unknown stage — it has been dropped or widened to "
                + "everything, and the probes in this class are proving nothing", CONSTRAINT)
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stageOfTheDeal()).isEqualTo(DealStage.NEGOTIATION);
    }

    /** The rendered definition of the live constraint, from the catalog. contype = 'c' and convalidated are part of
     * the WHERE on purpose: a NOT VALID check constraint is enforced for new writes but was never checked against
     * existing rows, and this guard should not quietly treat one as the same object. */
    private String liveConstraintDefinition(String constraint) {
        List<String> definitions = jdbc.queryForList("""
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = :schema
               AND t.relname = :table
               AND c.conname = :constraint
               AND c.contype = 'c'
               AND c.convalidated
            """,
            Map.of("schema", SCHEMA, "table", TABLE, "constraint", constraint),
            String.class);

        assertThat(definitions)
            .as("expected exactly one validated CHECK constraint %s.%s.%s — zero means it was "
                + "renamed or dropped, and this guard must fail rather than compare against nothing",
                SCHEMA, TABLE, constraint)
            .hasSize(1);
        return definitions.get(0);
    }

    /** The set of stage codes the live constraint permits. */
    private Set<String> permittedStages() {
        return permittedValuesOf(CONSTRAINT);
    }

    private Set<String> permittedValuesOf(String constraint) {
        return new LinkedHashSet<>(permittedValues(liveConstraintDefinition(constraint), constraint));
    }

    /** Pulls the value list out of a rendered definition, refusing to guess. The contract that matters: every
     * comma-separated element must be recognised. Failing on an unrecognised element converts that silent gap into
     * a red. */
    private static List<String> permittedValues(String definition, String constraint) {
        int open = definition.indexOf(ARRAY_OPEN);
        if (open < 0) {
            throw new AssertionError(constraint + " is not rendered as a value list, so this guard "
                + "cannot extract what it permits. Definition: " + definition);
        }
        int close = definition.indexOf(']', open);
        if (close < 0) {
            throw new AssertionError("unterminated value list in " + constraint
                + ". Definition: " + definition);
        }
        String block = definition.substring(open + ARRAY_OPEN.length(), close);

        List<String> values = new ArrayList<>();
        for (String element : block.split(",")) {
            Matcher matcher = ARRAY_ELEMENT.matcher(element);
            if (!matcher.matches()) {
                throw new AssertionError("could not read element <" + element + "> of "
                    + constraint + " as a quoted value — this guard will not report a partial set. "
                    + "Definition: " + definition);
            }
            values.add(matcher.group(1));
        }
        return values;
    }

    private String stageOfTheDeal() {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

}
