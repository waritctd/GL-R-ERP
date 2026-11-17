package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private static final String LIFECYCLE_CONSTRAINT = "chk_ticket_lifecycle";

    /** The stage count that exists today (V143). Used only as a LOWER bound, never an equality: a legitimate 16th
     * stage must fail on the set comparison, which names the offending code, and not here on a count that says
     * nothing about which side drifted. */
    private static final int STAGES_TODAY = 15;

    /** The lifecycle count that exists today (V51). A LOWER bound, for the same reason as #STAGES_TODAY. */
    private static final int LIFECYCLES_TODAY = 6;

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

    // ── Anti-vacuity: prove the extraction found something before comparing anything ──────────

    /** Checks the live stage constraint is readable and nonempty before comparing its values with Java. */
    @Test
    void theLiveConstraintIsReadableAndItsValueListIsPlausible() {
        String definition = liveConstraintDefinition(CONSTRAINT);

        assertThat(definition)
            .as("chk_ticket_sales_stage is not shaped like a value list any more — this guard "
                + "cannot read it, and a human has to look rather than let it pass")
            .contains(ARRAY_OPEN);

        Set<String> permitted = permittedStages();
        assertThat(permitted)
            .as("read %d value(s) out of %s — the pipeline has had at least %d stages since V143, "
                + "so this is a broken extraction, not a shrunken constraint. Definition: %s",
                permitted.size(), CONSTRAINT, STAGES_TODAY, definition)
            .hasSizeGreaterThanOrEqualTo(STAGES_TODAY)
            .contains(DealStage.QUOTE_OWNER);

        // …and the same for the other side of the comparison, so an emptied ORDER cannot make the set equality below
        // pass by matching an equally empty extraction.
        assertThat(DealStage.ORDER)
            .hasSizeGreaterThanOrEqualTo(STAGES_TODAY)
            .contains(DealStage.QUOTE_OWNER);
    }

    // ── The guard itself, both directions ────────────────────────────────────────────────────

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

    // ── The same claim, probed rather than parsed ────────────────────────────────────────────

    /** Direction 1 behaviourally, with no dependency on the parse: every code in DealStage#ORDER really is writable
     * to a real ticket row through the real repository. */
    @Test
    void everyStageInOrderCanReallyBeWrittenToADeal() {
        for (String stage : DealStage.ORDER) {
            assertThatCode(() -> tickets.updateSalesStage(ticketId, stage))
                .as("DealStage.ORDER declares %s but sales.ticket refuses it — the forward "
                    + "migration widening %s is missing", stage, CONSTRAINT)
                .doesNotThrowAnyException();
            assertThat(stageOfTheDeal()).isEqualTo(stage);
        }
    }

    /** Direction 2's other half: every value the parse pulled out of the definition really is accepted. */
    @Test
    void everyValueTheParseCallsPermittedReallyIsAccepted() {
        for (String value : permittedStages()) {
            assertThatCode(() -> tickets.updateSalesStage(ticketId, value))
                .as("this guard read %s as a value %s permits, but sales.ticket refused it — the "
                    + "extraction is unreliable and the comparison it feeds cannot be trusted",
                    value, CONSTRAINT)
                .doesNotThrowAnyException();
        }
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

    // The same guard, for chk_ticket_lifecycle ←→ DealLifecycle.VALID (2026-08-13).

    /** ANTI-VACUITY, the lifecycle half of #theLiveConstraintIsReadableAndItsValueListIsPlausible(): a query that
     * matched no constraint, or a parse that matched no literal, would hand every assertion below an empty set
     * that trivially satisfies "nothing is missing" in one direction. */
    @Test
    void theLiveLifecycleConstraintIsReadableAndItsValueListIsPlausible() {
        String definition = liveConstraintDefinition(LIFECYCLE_CONSTRAINT);

        assertThat(definition)
            .as("chk_ticket_lifecycle is not shaped like a value list any more — this guard cannot "
                + "read it, and a human has to look rather than let it pass")
            .contains(ARRAY_OPEN);

        Set<String> permitted = permittedLifecycles();
        assertThat(permitted)
            .as("read %d value(s) out of %s — the deal lifecycle has had at least %d values since "
                + "V51, so this is a broken extraction, not a shrunken constraint. Definition: %s",
                permitted.size(), LIFECYCLE_CONSTRAINT, LIFECYCLES_TODAY, definition)
            .hasSizeGreaterThanOrEqualTo(LIFECYCLES_TODAY)
            .contains(DealLifecycle.COMPLETED);

        assertThat(DealLifecycle.VALID)
            .hasSizeGreaterThanOrEqualTo(LIFECYCLES_TODAY)
            .contains(DealLifecycle.COMPLETED);
    }

    /** THE LIFECYCLE GUARD. Both directions, reported separately because they are different bugs. In VALID,
     * rejected by the constraint — a lifecycle was added to Java with no forward migration. Fix: add the
     * migration. */
    @Test
    void theConstraintPermitsExactlyTheLifecyclesDealLifecycleValidDeclares() {
        Set<String> permitted = permittedLifecycles();

        assertThat(permitted)
            .as("%s permits a value DealLifecycle.VALID does not declare — a migration widened the "
                + "CHECK and the Java mirror was not updated, so a deal can hold a lifecycle the "
                + "application has no constant for", LIFECYCLE_CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(DealLifecycle.VALID);

        // Stated the other way round as well, so the failure message names the right side of the mirror whichever one
        // drifted.
        assertThat(DealLifecycle.VALID)
            .as("DealLifecycle.VALID declares a lifecycle %s does not permit — updateLifecycle "
                + "fails with a constraint violation the first time a deal reaches it",
                LIFECYCLE_CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(permitted);
    }

    /** Direction 1 behaviourally, with no dependency on the parse: every value in DealLifecycle#VALID really is
     * writable through the real repository — the same method every production caller uses. */
    @Test
    void everyLifecycleInValidCanReallyBeWrittenToADeal() {
        for (String lifecycle : DealLifecycle.VALID) {
            assertThatCode(() -> tickets.updateLifecycle(ticketId, lifecycle))
                .as("DealLifecycle.VALID declares %s but sales.ticket refuses it — the forward "
                    + "migration widening %s is missing", lifecycle, LIFECYCLE_CONSTRAINT)
                .doesNotThrowAnyException();
            assertThat(lifecycleOfTheDeal()).isEqualTo(lifecycle);
        }
    }

    /** Direction 2's other half: every value the parse pulled out really is accepted, so #permittedLifecycles() is
     * evidence rather than a hopeful regex. */
    @Test
    void everyValueTheLifecycleParseCallsPermittedReallyIsAccepted() {
        for (String value : permittedLifecycles()) {
            assertThatCode(() -> tickets.updateLifecycle(ticketId, value))
                .as("this guard read %s as a value %s permits, but sales.ticket refused it — the "
                    + "extraction is unreliable and the comparison it feeds cannot be trusted",
                    value, LIFECYCLE_CONSTRAINT)
                .doesNotThrowAnyException();
        }
    }

    /** ANTI-VACUITY for the two lifecycle probes: the constraint is still ENFORCING. A migration that dropped
     * chk_ticket_lifecycle would make every write succeed and both probes pass while the column accepted anything
     * at all. */
    @Test
    void anUnknownLifecycleIsStillRejected_soTheProbesAboveAreNotVacuous() {
        tickets.updateLifecycle(ticketId, DealLifecycle.ON_HOLD);

        assertThatThrownBy(() -> tickets.updateLifecycle(ticketId, "NOT_A_LIFECYCLE"))
            .as("%s no longer rejects an unknown lifecycle — it has been dropped or widened to "
                + "everything, and the probes in this class are proving nothing", LIFECYCLE_CONSTRAINT)
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(lifecycleOfTheDeal()).isEqualTo(DealLifecycle.ON_HOLD);
    }

    // ── reading the live constraint ──────────────────────────────────────────────────────────

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

    /** The set of lifecycle codes the live constraint permits. */
    private Set<String> permittedLifecycles() {
        return permittedValuesOf(LIFECYCLE_CONSTRAINT);
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

    private String lifecycleOfTheDeal() {
        return jdbc.queryForObject("SELECT lifecycle FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }
}
