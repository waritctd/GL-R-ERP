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

/**
 * THE GUARD THIS CLASS EXISTS TO ADD: every value-list CHECK constraint on {@code sales.ticket}
 * that has a hand-maintained Java mirror must permit exactly what that mirror declares. Two such
 * pairs exist and both were unguarded:
 *
 * <ul>
 *   <li>{@code chk_ticket_sales_stage} ←→ {@link DealStage#ORDER} (the original, PR #717)</li>
 *   <li>{@code chk_ticket_lifecycle} ←→ {@link DealLifecycle#VALID} (added 2026-08-13)</li>
 * </ul>
 *
 * <p>{@code V143__deal_stage_quote_owner.sql} ends with "Keep this set in sync with
 * th.co.glr.hr.ticket.DealStage.ORDER, which is its Java mirror." Nothing enforced that. Add a
 * stage to {@code ORDER} without a forward migration widening the CHECK and the code compiles, the
 * whole suite stays green, and the first deal written to the new stage dies on a constraint
 * violation in production. This is the same unguarded-mirror family as the defect #714 fixed —
 * V143 added the 15th stage, two Java/JS maps silently stayed at 14, and 1,541 tests were green
 * throughout.
 *
 * <p><b>The lifecycle pair is the same shape with a different blast radius, and it is worth being
 * precise about what is and is not wrong with it.</b> {@link DealLifecycle#isValid} is dead code,
 * and that is <em>not</em> a defect: a lifecycle value is never externally supplied — every {@code
 * TicketRepository#updateLifecycle} caller passes a {@link DealLifecycle} constant — so there is
 * nothing to validate, and {@code chk_ticket_lifecycle} is the backstop if that ever changes. Do
 * not "fix" {@code isValid} or add validation nobody needs. The ONLY gap is that {@code VALID} and
 * the CHECK could silently drift apart: add a seventh lifecycle to Java with no forward migration
 * and {@code updateLifecycle} 500s on the first deal that reaches it; widen the CHECK without
 * updating Java and a row can hold a lifecycle no {@code DealLifecycle} constant names. This class
 * makes both a red.
 *
 * <h2>Why the live catalog and not the migration files</h2>
 *
 * <p>The stage constraint is defined <em>across</em> migrations: V50 created it over 14 stages,
 * V143 dropped and re-added it over 15. Migrations are forward-only and never edited in place, so
 * any guard that reads SQL text is reading a snapshot that is already wrong (V50) or will be wrong
 * the next time the constraint moves (V143 → whatever comes next). "Parse the newest file mentioning
 * the constraint" just relocates the fragility. The only definition that is true by construction is
 * the one Postgres holds after every migration has run, which is what {@code pg_get_constraintdef}
 * renders and what a production write is actually checked against. ({@code pg_constraint.consrc}
 * was removed in PostgreSQL 12 — the function is the supported way to read this.)
 *
 * <p>{@code chk_ticket_lifecycle} happens to still live in exactly one place today (V51, never
 * re-declared), which makes it tempting to read that file instead. That would be the wrong lesson:
 * the stage constraint looked equally single-sourced right up until V143 moved it, and a guard that
 * reads the catalog needs no maintenance the day the same thing happens here.
 *
 * <p>Two mechanisms are used together, deliberately:
 *
 * <ol>
 *   <li><strong>Parsed</strong> — the value list is read out of the rendered definition, which is
 *       the only way to see a value the constraint permits that {@code ORDER} does not contain.
 *       Direction 2 is unreachable any other way: you cannot discover an unknown permitted string
 *       by probing, only by reading the definition.
 *   <li><strong>Probed</strong> — every code is then really written to a real {@code sales.ticket}
 *       row through the real {@code TicketRepository}. This does not depend on the parse at all, so
 *       it catches direction 1 even if the regex is wrong, and — run over the parsed set — it is
 *       what proves the parse describes the constraint that is actually enforced.
 * </ol>
 *
 * <p>Set comparison, not list comparison: a CHECK constraint's value list is a set and SQL attaches
 * no meaning to its order, so pinning the order would manufacture a red the day someone regroups
 * the literals in a migration without changing what they permit. Pipeline <em>order</em> is
 * {@code ORDER}'s own business and {@code DealStageTest} owns it.
 *
 * <p>No assertion here depends on a rollback: {@code @Transactional} is inert in this suite (no
 * Spring context, services hand-wired with {@code new}), and every statement below runs on
 * autocommit — including the ones expected to fail, which is why a rejected write leaves the next
 * one unaffected.
 */
class DealStageCheckConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SCHEMA = "sales";
    private static final String TABLE = "ticket";
    private static final String CONSTRAINT = "chk_ticket_sales_stage";
    private static final String LIFECYCLE_CONSTRAINT = "chk_ticket_lifecycle";

    /**
     * The stage count that exists today (V143). Used only as a LOWER bound, never an equality: a
     * legitimate 16th stage must fail on the set comparison, which names the offending code, and
     * not here on a count that says nothing about which side drifted.
     */
    private static final int STAGES_TODAY = 15;

    /** The lifecycle count that exists today (V51). A LOWER bound, for the same reason as
     * {@link #STAGES_TODAY}. */
    private static final int LIFECYCLES_TODAY = 6;

    /**
     * How Postgres renders {@code col IN ('a','b')} for a scalar column — as
     * {@code (col)::text = ANY ((ARRAY['a'::character varying, ...])::text[])}. Asserted rather
     * than assumed: if a future migration expresses the constraint some other way, this guard must
     * say "I cannot read this" loudly instead of quietly extracting nothing.
     */
    private static final String ARRAY_OPEN = "ARRAY[";

    /**
     * One element of that array: a quoted literal plus the type cast Postgres prints after it.
     * Every comma-separated element must match — see {@link #permittedValues} for why "must" is
     * the whole point.
     */
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

    /**
     * ANTI-VACUITY. Runs first and stands alone so that "the guard could not read the constraint"
     * has its own named red, distinct from "the two sides disagree".
     *
     * <p>Without it, a query that matched no constraint or a parse that matched no literal would
     * hand every assertion below an empty set — and an empty set trivially satisfies "no code is
     * missing from the other side" in one direction while failing confusingly in the other. A guard
     * that passes because it found nothing is the exact failure this repo keeps hitting.
     */
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

        // …and the same for the other side of the comparison, so an emptied ORDER cannot make the
        // set equality below pass by matching an equally empty extraction.
        assertThat(DealStage.ORDER)
            .hasSizeGreaterThanOrEqualTo(STAGES_TODAY)
            .contains(DealStage.QUOTE_OWNER);
    }

    // ── The guard itself, both directions ────────────────────────────────────────────────────

    /**
     * THE GUARD. Both directions, reported separately because they are different bugs with
     * different fixes.
     *
     * <ul>
     *   <li><strong>In {@code ORDER}, rejected by the constraint</strong> — a stage was added to
     *       Java with no forward migration. Every write to it 500s in production. Fix: add the
     *       migration.
     *   <li><strong>Permitted by the constraint, absent from {@code ORDER}</strong> — a migration
     *       widened the CHECK and Java was not updated. {@code DealStage.isValid} rejects the code,
     *       {@code indexOf} answers -1, {@code gateOf}/{@code phaseOf}/{@code businessCodeOf} all
     *       answer null/0 — so a row can hold a stage the application cannot reason about, which is
     *       precisely the QUOTE_OWNER defect one layer down. Fix: add it to {@code ORDER} and to
     *       everything the completeness guards cover.
     * </ul>
     */
    @Test
    void theConstraintPermitsExactlyTheStagesDealStageOrderDeclares() {
        Set<String> permitted = permittedStages();
        Set<String> declared = Set.copyOf(DealStage.ORDER);

        assertThat(permitted)
            .as("%s permits a value DealStage.ORDER does not declare — a migration widened the "
                + "CHECK and the Java mirror was not updated, so a row can hold a stage the "
                + "application cannot label, gate, order or phase", CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(declared);

        // Stated the other way round as well, so the failure message names the right side of the
        // mirror whichever one drifted. containsExactlyInAnyOrder above already covers both, but
        // its message reads from the constraint's point of view only.
        assertThat(declared)
            .as("DealStage.ORDER declares a stage %s does not permit — writing a deal to it fails "
                + "with a constraint violation at runtime. V143's closing comment asks for a "
                + "forward migration here; this is that comment, enforced", CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(permitted);
    }

    // ── The same claim, probed rather than parsed ────────────────────────────────────────────

    /**
     * Direction 1 behaviourally, with no dependency on the parse: every code in {@link
     * DealStage#ORDER} really is writable to a real ticket row through the real repository.
     *
     * <p>This is what would have caught a missing migration even if the regex above had rotted, and
     * it is a genuinely different question from the set comparison — the set comparison asks what
     * the catalog <em>says</em>, this asks what Postgres <em>does</em>. (The two can disagree for
     * reasons other than the CHECK: {@code sales_stage} is {@code VARCHAR(30)} since V50, so a
     * longer code would be refused by the column type with the constraint none the wiser.)
     */
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

    /**
     * Direction 2's other half: every value the parse pulled out of the definition really is
     * accepted. This is the assertion that makes {@link #permittedStages()} evidence rather than a
     * hopeful regex — if the parse ever picks up a literal from some other part of a compound
     * constraint, that literal will not be writable and this goes red naming it, instead of the set
     * comparison going red naming a stage that was never the problem.
     */
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

    /**
     * ANTI-VACUITY for the two probes above: the constraint is still ENFORCING. Without this, a
     * migration that dropped {@code chk_ticket_sales_stage} outright would make every write succeed
     * and both probes pass while the database accepted anything at all.
     *
     * <p>The deal must also still be sitting where it was: a rejected statement must not have
     * moved it. It is parked on {@link DealStage#NEGOTIATION} rather than on whichever stage was
     * added most recently, so that this test stays green — and keeps answering its own question —
     * when a mutation removes the stage at the frontier. That is what makes it independent
     * evidence for the probes rather than a second copy of them.
     */
    @Test
    void anUnknownStageIsStillRejected_soTheProbesAboveAreNotVacuous() {
        tickets.updateSalesStage(ticketId, DealStage.NEGOTIATION);

        assertThatThrownBy(() -> tickets.updateSalesStage(ticketId, "NOT_A_STAGE"))
            .as("%s no longer rejects an unknown stage — it has been dropped or widened to "
                + "everything, and the probes in this class are proving nothing", CONSTRAINT)
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stageOfTheDeal()).isEqualTo(DealStage.NEGOTIATION);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // The same guard, for chk_ticket_lifecycle ←→ DealLifecycle.VALID (2026-08-13). Same five
    // shapes in the same order — anti-vacuity on the extraction, the set comparison in both
    // directions, both probes, anti-vacuity on the probes — because the mirror is the same shape
    // and a reader who understands one half should not have to re-learn the other.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * ANTI-VACUITY, the lifecycle half of
     * {@link #theLiveConstraintIsReadableAndItsValueListIsPlausible()}: a query that matched no
     * constraint, or a parse that matched no literal, would hand every assertion below an empty
     * set that trivially satisfies "nothing is missing" in one direction.
     */
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

    /**
     * THE LIFECYCLE GUARD. Both directions, reported separately because they are different bugs.
     *
     * <ul>
     *   <li><strong>In {@code VALID}, rejected by the constraint</strong> — a lifecycle was added to
     *       Java with no forward migration. {@code TicketRepository#updateLifecycle} is a bare
     *       UPDATE with no application-level validation in front of it (by design — see the class
     *       Javadoc on {@code isValid}), so the first deal moved there dies on the CHECK.
     *       Fix: add the migration.
     *   <li><strong>Permitted by the constraint, absent from {@code VALID}</strong> — a migration
     *       widened the CHECK and Java was not updated, so a row can hold a lifecycle no {@code
     *       DealLifecycle} constant names and no {@code TicketService} branch handles.
     *       Fix: add the constant to {@code VALID}.
     * </ul>
     */
    @Test
    void theConstraintPermitsExactlyTheLifecyclesDealLifecycleValidDeclares() {
        Set<String> permitted = permittedLifecycles();

        assertThat(permitted)
            .as("%s permits a value DealLifecycle.VALID does not declare — a migration widened the "
                + "CHECK and the Java mirror was not updated, so a deal can hold a lifecycle the "
                + "application has no constant for", LIFECYCLE_CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(DealLifecycle.VALID);

        // Stated the other way round as well, so the failure message names the right side of the
        // mirror whichever one drifted.
        assertThat(DealLifecycle.VALID)
            .as("DealLifecycle.VALID declares a lifecycle %s does not permit — updateLifecycle "
                + "fails with a constraint violation the first time a deal reaches it",
                LIFECYCLE_CONSTRAINT)
            .containsExactlyInAnyOrderElementsOf(permitted);
    }

    /**
     * Direction 1 behaviourally, with no dependency on the parse: every value in {@link
     * DealLifecycle#VALID} really is writable through the real repository — the same method every
     * production caller uses.
     */
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

    /**
     * Direction 2's other half: every value the parse pulled out really is accepted, so
     * {@link #permittedLifecycles()} is evidence rather than a hopeful regex.
     */
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

    /**
     * ANTI-VACUITY for the two lifecycle probes: the constraint is still ENFORCING. A migration
     * that dropped {@code chk_ticket_lifecycle} would make every write succeed and both probes pass
     * while the column accepted anything at all.
     *
     * <p>Parked on {@link DealLifecycle#ON_HOLD} rather than on whichever value was added last, for
     * the same reason its stage counterpart parks on {@code NEGOTIATION}: this test must keep
     * answering its own question when a mutation removes the value at the frontier.
     */
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

    /**
     * The rendered definition of the live constraint, from the catalog.
     *
     * <p>{@code contype = 'c'} and {@code convalidated} are part of the WHERE on purpose: a NOT
     * VALID check constraint is enforced for new writes but was never checked against existing
     * rows, and this guard should not quietly treat one as the same object. Exactly one row must
     * come back — zero means the constraint was renamed or dropped, and that must be a red here
     * rather than an empty set that quietly weakens every comparison downstream.
     */
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

    /**
     * Pulls the value list out of a rendered definition, refusing to guess.
     *
     * <p>The contract that matters: <strong>every comma-separated element must be recognised</strong>.
     * A regex that simply harvested every quoted literal it could see would silently under-report
     * the day one element is rendered in some form it does not match — and under-reporting is
     * invisible in direction 2, where the whole question is whether the constraint permits
     * something extra. Failing on an unrecognised element converts that silent gap into a red.
     */
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
