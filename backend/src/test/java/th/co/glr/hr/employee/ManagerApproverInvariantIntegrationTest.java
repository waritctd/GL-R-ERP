package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeLoginRecord;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Pins {@link ManagerApproverRepository}'s SQL to the Java predicate it routes against.
 *
 * <p>It is tempting to state the relationship as a plain equivalence — "no manager approver exactly
 * when nobody can manage them" — and an earlier draft of this test did. It is <b>false</b>, and this
 * test is what proved it: a ผู้จัดการ in a ฝ่าย that has a second ผู้จัดการ <em>does</em> have
 * someone who satisfies {@code managesEmployee}, yet must still skip the manager stage, because the
 * owner's rule is that a manager's own request goes straight to the CEO. The real contract is four
 * separate properties (a third routing rule -- reporting to an active executive -- was added
 * 2026-09-01, CEO-approval-reach follow-on; see {@link ManagerApproverRepository}'s own Javadoc):
 *
 * <ol>
 *   <li><b>No stranding.</b> {@code hasManagerApprover(e)} ⟹ some active {@code u} satisfies
 *       {@code managesEmployee(e, u)}. Violate this and a request is routed to a manager stage
 *       nobody can clear — the exact stall this change exists to remove.</li>
 *   <li><b>Deliberate bypass.</b> {@code e} is a ผู้จัดการ ⟹ {@code hasManagerApprover(e)} is
 *       false, whether or not a peer manager exists.</li>
 *   <li><b>No needless bypass.</b> {@code e} is not a ผู้จัดการ, does not report to an active
 *       executive, and some {@code u} can manage them ⟹ {@code hasManagerApprover(e)} is true.
 *       Violate this and the CEO silently absorbs the manager stage for ordinary staff, which is a
 *       weakening of the two-stage control.</li>
 *   <li><b>Executive-report bypass.</b> {@code e} reports directly to an ACTIVE executive (division
 *       {@code md}, or a position containing "กรรมการ") ⟹ {@code hasManagerApprover(e)} is false,
 *       regardless of whether a peer manager is reachable in {@code e}'s own division.</li>
 * </ol>
 *
 * <p>Property 1 is the safety one; 2, 3 and 4 are what stop the implementation from satisfying it
 * trivially by always returning false.
 *
 * <p>{@code managesEmployee} is private to the two services, so it is restated below. The usual
 * objection to that is drift, and it is a fair one — but the restatement is four lines and the
 * matrix covers every combination of inputs it reads, so a change to the real predicate that this
 * copy does not follow will disagree on at least one row. {@code managesEmployee} itself does not
 * read {@code reports_to_employee_id} at all (division-only, see its own Javadoc for the
 * "removes, never grants" nuance) -- property 4's executive check is restated SEPARATELY, against
 * the fixture's own reports-to/division/position rows, precisely because it is NOT part of
 * {@code managesEmployee}'s restatement below.
 */
class ManagerApproverInvariantIntegrationTest extends AbstractPostgresIntegrationTest {

    private ManagerApproverRepository repository;
    private long divisionWithManager;
    private long divisionWithoutManager;
    private int positionSequence;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new ManagerApproverRepository(jdbc);
        positionSequence = 0;
        divisionWithManager = insertDivision("SLS", "ฝ่ายขาย");
        divisionWithoutManager = insertDivision("MNT", "ฝ่ายซ่อมบำรุง");
    }

    @Test
    void sqlHoldsAllFourRoutingPropertiesOnEveryOrgChartShape() {
        record Shape(String label, Long divisionId, String position, boolean active, Long reportsTo) { }

        // Executive fixtures for rule 3 / property 4 (CEO-approval-reach follow-on, 2026-09-01).
        // Two disjuncts, both exercised: position-based (a กรรมการ, regardless of division) and
        // division-based (division source_code "MD", regardless of position) -- mirroring
        // DivisionAccessPolicy.roleFor's "ceo" branch exactly, which is what REPORTS_TO_EXECUTIVE
        // is built to match. No division_id needed for a position-based executive; no position
        // needed for a division-based one.
        long activeExecutiveByPosition = insertEmployee("EXEC-POS", null, "กรรมการ", true);
        long inactiveExecutiveByPosition = insertEmployee("EXEC-GONE", null, "กรรมการ", false);
        long divisionMd = insertDivision("MD", "ผู้บริหารระดับสูง");
        long activeExecutiveByDivision = insertEmployee("EXEC-DIV", divisionMd, null, true);

        List<Shape> shapes = List.of(
            new Shape("staff in a ฝ่าย that has a ผู้จัดการ", divisionWithManager, null, true, null),
            new Shape("staff in a ฝ่าย with no ผู้จัดการ", divisionWithoutManager, null, true, null),
            new Shape("the ผู้จัดการ of a ฝ่าย, in that ฝ่าย", divisionWithManager, "ผู้จัดการฝ่ายขาย", true, null),
            new Shape("a ผู้ช่วยผู้จัดการ", divisionWithManager, "ผู้ช่วยผู้จัดการฝ่ายขาย", true, null),
            new Shape("a กรรมการผู้จัดการ", divisionWithManager, "กรรมการผู้จัดการ", true, null),
            new Shape("a กรรมการ (executive, not a ผู้จัดการ)", divisionWithManager, "กรรมการ", true, null),
            new Shape("the lone ผู้จัดการ of a ฝ่าย with no other manager", divisionWithoutManager,
                "ผู้จัดการฝ่ายซ่อมบำรุง", true, null),
            new Shape("staff with no ฝ่าย at all", null, null, true, null),
            new Shape("an inactive employee", divisionWithManager, null, false, null),
            // Rule 3 / property 4: reports to an active executive (position-based), in a ฝ่าย that
            // otherwise HAS a reachable ผู้จัดการ -- the headline new case. someoneCanManage would
            // be true here under the OLD rule; routesToAManager must now be false anyway.
            new Shape("staff in a ฝ่าย that has a ผู้จัดการ, reporting to an active executive (position)",
                divisionWithManager, null, true, activeExecutiveByPosition),
            // Same shape, division-based executive -- proves the OR's second disjunct independently
            // of the first.
            new Shape("staff in a ฝ่าย that has a ผู้จัดการ, reporting to an active executive (division md)",
                divisionWithManager, null, true, activeExecutiveByDivision),
            // Negative control for boss.is_active = TRUE: an INACTIVE executive must not grant the
            // bypass -- this shape must still route to the division's ผู้จัดการ, same as the very
            // first shape above.
            new Shape("staff in a ฝ่าย that has a ผู้จัดการ, reporting to an INACTIVE executive",
                divisionWithManager, null, true, inactiveExecutiveByPosition));

        // The single active ผู้จัดการ that makes divisionWithManager "covered". Inserted before the
        // shapes so every shape sees the same org chart.
        insertEmployee("MGR", divisionWithManager, "ผู้จัดการฝ่ายขาย", true);
        // ...and an INACTIVE ผู้จัดการ in the other ฝ่าย. This is the case a naive query gets wrong:
        // the row exists and matches on position and division, but the person cannot log in, so
        // they are not an approver and that ฝ่าย must still count as manager-less.
        insertEmployee("MGR_GONE", divisionWithoutManager, "ผู้จัดการฝ่ายซ่อมบำรุง", false);

        List<String> violations = new ArrayList<>();
        for (Shape shape : shapes) {
            long employeeId = insertEmployee("E_" + shapes.indexOf(shape), shape.divisionId(),
                shape.position(), shape.active(), shape.reportsTo());

            boolean routesToAManager = repository.hasManagerApprover(employeeId);
            boolean someoneCanManage = anyActiveEmployeeManages(employeeId);
            boolean isManager = isManager(shape.position());
            boolean reportsToActiveExecutive = reportsToAnActiveExecutive(shape.reportsTo());

            if (routesToAManager && !someoneCanManage) {
                violations.add("STRANDED: " + shape.label()
                    + " routes to a manager stage, but no active employee satisfies managesEmployee");
            }
            if (isManager && routesToAManager) {
                violations.add("MISSED BYPASS: " + shape.label()
                    + " is a ผู้จัดการ and must go straight to the CEO");
            }
            // Property 3, restated 2026-09-01: "no needless bypass" now also requires NOT reporting
            // to an active executive -- rule 3 deliberately bypasses a reachable ผู้จัดการ for that
            // case, the same way rule 2 deliberately bypasses one for a ผู้จัดการ's own request.
            if (!isManager && !reportsToActiveExecutive && someoneCanManage && !routesToAManager) {
                violations.add("NEEDLESS BYPASS: " + shape.label()
                    + " has a reachable ผู้จัดการ but was routed past them to the CEO");
            }
            // Property 4 (new): the executive-report bypass must actually fire -- a request must
            // never sit with a division ผู้จัดการ who cannot act because the employee's real
            // overseer is the executive they report to.
            if (reportsToActiveExecutive && routesToAManager) {
                violations.add("MISSED EXECUTIVE BYPASS: " + shape.label()
                    + " reports to an active executive but still routes to a manager stage");
            }
            // The notification target set must be non-empty exactly when we route to a manager
            // stage. Drift here is silent in the worst way: the request routes to a manager stage
            // and nobody is told, or it routes to the CEO while managers get pinged about a
            // request they cannot clear.
            boolean hasSomeoneToNotify =
                !repository.findManagerApproverEmployeeIds(employeeId).isEmpty();
            if (hasSomeoneToNotify != routesToAManager) {
                violations.add("NOTIFY MISMATCH: " + shape.label()
                    + " routesToAManager=" + routesToAManager
                    + " but findManagerApproverEmployeeIds non-empty=" + hasSomeoneToNotify);
            }
        }

        assertThat(violations).isEmpty();
    }

    /** Mirrors {@code DivisionAccessPolicy.isManager}, against the position name the fixture set. */
    private boolean isManager(String positionNameTh) {
        return positionNameTh != null && positionNameTh.replaceAll("\\s+", "").contains("ผู้จัดการ");
    }

    @Test
    void theShapesActuallyExerciseBothOutcomes() {
        // Guards the test above against passing vacuously. If a fixture change ever made every
        // shape land on the same side, the loop would still find zero disagreements and report
        // success while proving nothing about the interesting half of the equivalence.
        insertEmployee("MGR", divisionWithManager, "ผู้จัดการฝ่ายขาย", true);
        long covered = insertEmployee("S1", divisionWithManager, null, true);
        long manager = insertEmployee("S2", divisionWithManager, "ผู้จัดการฝ่ายขาย", true);
        long orphan = insertEmployee("S3", divisionWithoutManager, null, true);

        assertThat(repository.hasManagerApprover(covered)).isTrue();
        // A ผู้จัดการ never routes through another ผู้จัดการ, even though one exists in their ฝ่าย.
        assertThat(repository.hasManagerApprover(manager)).isFalse();
        assertThat(repository.hasManagerApprover(orphan)).isFalse();
    }

    @Test
    void anEmployeeRowThatDoesNotExistFailsClosed() {
        // Unreachable through the services, but the answer must withhold the CEO bypass rather
        // than grant it on the strength of a missing row.
        assertThat(repository.hasManagerApprover(-1L)).isTrue();
    }

    /**
     * Sweeps every active employee and asks whether any of them would satisfy the services'
     * {@code managesEmployee(employeeId, user)} — the brute-force definition the SQL optimises.
     */
    private boolean anyActiveEmployeeManages(long employeeId) {
        Long targetDivision = jdbc.queryForObject(
            "SELECT division_id FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId), Long.class);

        return jdbc.query("""
            SELECT e.employee_id, e.division_id, e.is_active, p.name_th AS position_name,
                   d.source_code AS division_code, d.name_th AS division_name
              FROM hr.employee e
              LEFT JOIN hr.position p ON p.position_id = e.position_id
              LEFT JOIN hr.division d ON d.division_id = e.division_id
             WHERE e.is_active = TRUE
            """, Map.of(), (rs, rowNum) -> {
                // division_id is a narrower integer type in the schema, so read it as a long and
                // null-check via wasNull() rather than casting whatever getObject boxes it into.
                long divisionId = rs.getLong("division_id");
                EmployeeLoginRecord record = new EmployeeLoginRecord(
                    rs.getLong("employee_id"), null, null, null, rs.getBoolean("is_active"),
                    rs.wasNull() ? null : divisionId, rs.getString("division_code"),
                    rs.getString("division_name"), rs.getString("position_name"),
                    LocalDate.now(), null, false);
                // Build the principal exactly as login does, so the `manager` flag under test is
                // derived rather than asserted by the fixture.
                UserPrincipal candidate = new UserPrincipal(
                    record.employeeId(), null, null, DivisionAccessPolicy.roleFor(record),
                    record.employeeId(), true, LocalDate.now(), false,
                    record.divisionId(), DivisionAccessPolicy.isManager(record));
                return managesEmployee(employeeId, targetDivision, candidate);
            })
            .stream()
            .anyMatch(Boolean::booleanValue);
    }

    /** Verbatim restatement of {@code OvertimeService.managesEmployee} / its welfare twin. */
    private boolean managesEmployee(long employeeId, Long employeeDivisionId, UserPrincipal user) {
        return user.manager()
            && user.divisionId() != null
            && user.divisionId().equals(employeeDivisionId)
            && employeeId != user.employeeId();
    }

    /**
     * Rule 3 / property 4 (CEO-approval-reach follow-on, 2026-09-01): {@code false} when
     * {@code reportsToId} is null (no boss at all). Otherwise restated against the REAL Java
     * authority {@code DivisionAccessPolicy.roleFor} -- deliberately NOT a second copy of
     * {@code ManagerApproverRepository}'s SQL, since restating the same SQL against the fixture
     * would only prove the plumbing (parameter substitution) and say nothing about whether the
     * predicate itself agrees with the Java-side "who counts as an executive" definition this SQL
     * is supposed to mirror. {@code "ceo".equals(roleFor(...))} is exactly that definition: division
     * {@code md} OR a position containing "กรรมการ", the same union
     * {@code ManagerApproverRepository}'s Javadoc says REPORTS_TO_EXECUTIVE mirrors.
     */
    private boolean reportsToAnActiveExecutive(Long reportsToId) {
        if (reportsToId == null) {
            return false;
        }
        return jdbc.query("""
            SELECT p.name_th AS position_name, d.source_code AS division_code, d.name_th AS division_name
              FROM hr.employee e
              LEFT JOIN hr.position p ON p.position_id = e.position_id
              LEFT JOIN hr.division d ON d.division_id = e.division_id
             WHERE e.employee_id = :bossId
               AND e.is_active = TRUE
            """, Map.of("bossId", reportsToId), (rs, rowNum) -> {
                EmployeeLoginRecord record = new EmployeeLoginRecord(
                    reportsToId, null, null, null, true, null,
                    rs.getString("division_code"), rs.getString("division_name"),
                    rs.getString("position_name"), LocalDate.now(), null, false);
                return "ceo".equals(DivisionAccessPolicy.roleFor(record));
            })
            .stream()
            .findFirst()
            .orElse(false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, String positionNameTh, boolean active) {
        return insertEmployee(code, divisionId, positionNameTh, active, null);
    }

    /**
     * Rule 3 / property 4 (CEO-approval-reach follow-on, 2026-09-01): {@code reportsTo} overload,
     * for shapes that need {@code reports_to_employee_id} set. The 4-arg overload above delegates
     * here with {@code null} rather than every existing call site changing shape.
     */
    private long insertEmployee(
            String code, Long divisionId, String positionNameTh, boolean active, Long reportsTo) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("active", active);
        params.put("reportsTo", reportsTo);
        params.put("positionId", positionNameTh == null ? null : insertPosition(code, positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, position_id, reports_to_employee_id, hire_date,
                                     is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :positionId, :reportsTo,
                    DATE '2020-01-01', :active)
            RETURNING employee_id
            """, params, Long.class);
    }

    /**
     * {@code hr.position.source_code} is UNIQUE and the migrated schema already seeds real ones
     * (including "MGR"), so fixture codes are generated rather than derived from the employee code
     * — otherwise a plausible-looking test code collides with seed data and the test dies before
     * asserting anything.
     */
    private long insertPosition(String unusedCode, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZ" + positionSequence++, "name", nameTh), Long.class);
    }
}
