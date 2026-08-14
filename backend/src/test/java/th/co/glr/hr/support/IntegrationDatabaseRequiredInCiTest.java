package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Makes the DB-backed guards refuse to vanish quietly: <b>in CI, a missing integration database is
 * a stated build failure.</b> {@code DealStageCheckConstraintIntegrationTest} (PR #717, extended by
 * PR #720) exists so that {@link th.co.glr.hr.ticket.DealStage#ORDER} and
 * {@link th.co.glr.hr.ticket.DealLifecycle#VALID} cannot drift from the live
 * {@code chk_ticket_sales_stage} / {@code chk_ticket_lifecycle} CHECK constraints; a green run in
 * which it never executed says <i>nothing</i> about those mirrors while looking exactly like a run
 * in which they held. Same for every real-DB authz proof {@code CLAUDE.md} requires — the
 * requirement is that the test <i>runs</i>, not that it exists.
 *
 * <h2>What a DB-less run actually does today — measured, and not what the docs say</h2>
 *
 * <p>{@link AbstractPostgresIntegrationTest} carries
 * {@code @EnabledIf(PostgresTestSupport#isAvailable)}, and both its own Javadoc and
 * {@code CLAUDE.md} describe the result as "the DB-backed tests skip gracefully, so a DB-less
 * {@code mvnw verify} still runs green". <b>That is not what happens.</b> JUnit's
 * {@code @EnabledIf} is <b>not</b> {@code @Inherited} (verified against junit-jupiter-api 5.12.2:
 * its meta-annotations are {@code @Target}, {@code @Retention}, {@code @Documented},
 * {@code @ExtendWith}, {@code @API} — no {@code @Inherited}), and JUnit's annotation lookup only
 * walks to a superclass for annotations that are. Declared on an abstract base class, it therefore
 * governs that base class and nothing else: <b>no subclass is disabled by it.</b> Confirmed
 * directly with a throwaway {@code @EnabledIf(<always false>)} base class whose subclass ran
 * anyway.
 *
 * <p>So with neither {@code TEST_DB_URL} nor Docker, every subclass <i>runs</i> and dies in
 * {@code @BeforeEach} — {@code Could not find a valid Docker environment}, or
 * {@code Failed to initialize pool: FATAL: database "wrk_it" does not exist} once
 * {@link PostgresTestSupport#usesContainer()} sends the reset down the external-DB path. Loud, but
 * accidental and misleading: it reads as a broken database rather than as an absent precondition,
 * and it arrives ~130 times.
 *
 * <h2>Two layers, answering two different questions</h2>
 *
 * <p>The owner ruling ("Docker should be enabled") is implemented primarily at the <b>CI level</b>:
 * {@code .github/workflows/backend-ci.yml} has a <i>Require an integration database</i> step that
 * fails the job in ~1s, before the JDK and LibreOffice are even provisioned, when the runner has
 * neither a reachable Docker daemon nor {@code TEST_DB_URL}. That is the right home for it — it is
 * a statement about the machine, it fails early and legibly, and it is visible to anyone reading
 * the workflow.
 *
 * <p>This class is the <b>second layer</b>, and it is not redundant with the first:
 *
 * <ol>
 *   <li><b>It asks the component that actually decides.</b> {@code docker info} is what the
 *       <i>shell</i> can see. {@link PostgresTestSupport#isAvailable()} consults
 *       {@code DockerClientFactory}, which additionally honours {@code DOCKER_HOST},
 *       {@code ~/.testcontainers.properties} and its own strategy chain. The two can disagree —
 *       and a disagreement in that direction fails <i>green</i>: the workflow step passes, the
 *       suite finds no Docker, and the build is meaningless. Only this side closes that gap.</li>
 *   <li><b>It holds the invariant if the skip is ever repaired.</b> The moment anyone makes that
 *       {@code @EnabledIf} effective — moving it onto the concrete classes, a JUnit change, or
 *       "fixing" the inheritance in good faith — a DB-less run flips from ~130 red to fully
 *       green-and-meaningless. That is precisely the failure the ruling is against, and it is one
 *       plausible edit away.</li>
 *   <li><b>It travels with the repo.</b> The workflow step protects GitHub Actions. This protects
 *       any CI, and it is the layer a developer rehearsing with {@code CI=true} actually runs.</li>
 * </ol>
 *
 * <p>Deliberately NOT an {@link AbstractPostgresIntegrationTest}: it must not inherit the very
 * condition under discussion, and it touches no database. It asserts the <i>precondition</i>, which
 * is the thing the environment can take away.
 *
 * <h2>Why "in CI", rather than always</h2>
 *
 * <p>Making a DB-less run fail <i>everywhere</i> — by design rather than by accident — was
 * considered and rejected: it breaks {@code ./mvnw verify} for every developer without Docker, on a
 * machine where nothing is merging and no verdict is published. The asymmetry that makes this
 * tractable is that a local skip harms no one while a skip <i>in CI</i> turns a required check into
 * a lie. So the environment decides.
 *
 * <p>{@code CI} is set to {@code true} by GitHub Actions on every runner (and by essentially every
 * other CI vendor); the regex also accepts {@code 1}. Nothing in this repository sets it, so local
 * runs are unaffected unless a developer opts in by exporting it — a useful way to rehearse CI.
 *
 * <h2>Alternatives weighed</h2>
 *
 * <ul>
 *   <li><b>Parsing {@code target/surefire-reports/*.xml} for {@code skipped} counts.</b> The only
 *       mechanism that could assert a <i>named class</i> ran. Rejected: it hardcodes class names in
 *       a workflow file, rots on the first rename, and covers one class rather than the whole
 *       family.</li>
 *   <li><b>Deleting the {@code @EnabledIf}.</b> Tempting now that it is known to be inert on
 *       subclasses — but it is accurate for {@link AbstractPostgresIntegrationTest} itself and
 *       removing it changes nothing about the behaviour above. Left alone deliberately; the
 *       documentation that described it wrongly is what needed correcting.</li>
 * </ul>
 *
 * <h2>What this does and does not prove</h2>
 *
 * <p><b>Does:</b> that a CI run reaching the Maven build had a usable integration database — as
 * Testcontainers itself judges it, not as the shell does — so no DB-backed guard could have been
 * skipped for want of one. That is the failure mode with no diff attached to it, because the
 * environment causes it.
 *
 * <p><b>Does not:</b> that a specific class ran. Deleting, renaming or {@code @Disabled}-ing
 * {@code DealStageCheckConstraintIntegrationTest} still slips past this — but each of those is a
 * visible line in a reviewed diff, which is a different problem from an environment quietly
 * removing a test's precondition.
 */
@EnabledIfEnvironmentVariable(named = "CI", matches = "(?i)true|1",
    disabledReason = "Not a CI run — a DB-less local `mvnw verify` is not this test's business")
class IntegrationDatabaseRequiredInCiTest {

    @Test
    void ciRunsMustHaveAnIntegrationDatabaseSoNoDbBackedGuardCanSelfDisable() {
        assertThat(PostgresTestSupport.isAvailable())
            .as("""
                CI has neither TEST_DB_URL nor a Docker daemon Testcontainers can reach. Every \
                DB-backed guard in this build is therefore worthless — DealStageCheckConstraint\
                IntegrationTest's Java<->Postgres stage mirror, and every real-DB authz proof \
                CLAUDE.md requires. Restore Docker on the runner, or set TEST_DB_URL. Do not \
                silence this by unsetting CI.""")
            .isTrue();
    }
}
