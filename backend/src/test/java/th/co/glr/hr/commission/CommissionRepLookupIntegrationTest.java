package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Issue #737 — the manual-commission rep picker's data source, {@code GET /api/commissions/reps}
 * (backed by {@link CommissionService#listManualCommissionRepOptions}), proved against real
 * Postgres.
 *
 * <p>Per CLAUDE.md's "Permission changes must ship evidence": this endpoint is a NEW
 * authorization boundary (only {@code sales_manager}/{@code ceo} may call it — the identical
 * {@code MANUAL_CREATE_ROLES} gate as {@link CommissionService#createManualCommission}), so this
 * class proves it wrong-way-round against the real {@link CommissionService}, backed by the real
 * {@link CommissionRepository} AND the real {@link EmployeeRepository}, on real Postgres. Mockito
 * cannot prove the {@code WHERE e.is_active} filter, the ฝ่ายขาย division match, or the
 * {@code ORDER BY} clause actually reach the SQL — a mocked repository happily "passes" while the
 * real query does something else. {@link AuditService} and {@link NotificationService} are mocked
 * deliberately: this read-only method touches neither, unlike {@link
 * CommissionService#createManualCommission} in the same class. Model and helper shape follow
 * {@code ManualCommissionIntegrationTest} and {@code CommissionListScopeIntegrationTest} in this
 * package.
 *
 * <p><b>SCOPE, per owner ruling 2026-08-14 — the SECOND ruling on this endpoint</b> (Ploy, after
 * PR #767 was already open and green: "sales_manager and ceo should see every sales who get their
 * commission", confirmed explicit: division MEMBERSHIP, not "has a commission record", so a
 * brand-new rep still appears). This superseded a brief per-caller-division-scoped design that
 * had lived here in between (a {@code sales_manager} scoped to their own division, {@code ceo}
 * company-wide) — that design, and its dedicated tests, are gone; do not resurrect them.
 * {@code sales_manager} and {@code ceo} now get the IDENTICAL list: every ACTIVE employee in
 * ฝ่ายขาย. Cases at the bottom of this class are this rule's own wrong-way-round proof, on top of
 * the role-gate cases 1-4 above it.
 *
 * <p>Every wrong-way-round case matters more than the positive ones: "sales/hr/account/employee
 * CANNOT reach this" is the assertion that catches a widened role gate; "an employee outside
 * ฝ่ายขาย is never returned, for EITHER role" is the assertion that catches a widened scope.
 * "sales_manager/ceo CAN reach the endpoint at all" is the cheap half.
 */
class CommissionRepLookupIntegrationTest extends AbstractPostgresIntegrationTest {

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private EmployeeRepository employees;

    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesActor;
    private UserPrincipal hrActor;
    private UserPrincipal accountActor;
    private UserPrincipal employeeActor;

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));

        long managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย รายชื่อ", "reps-manager@glr.co.th", "SA", "แผนกขาย");
        long ceoEmployeeId = createEmployee("ผู้บริหาร รายชื่อ", "reps-ceo@glr.co.th", "MD", "ผู้บริหาร");
        // Both actors carry their REAL division_id via the 3-arg principal()/divisionIdOf()
        // helpers, deliberately, even though the current code no longer reads divisionId for
        // EITHER role (scoping is division-of-the-TARGET-employee now, not
        // division-of-the-caller). Keep them realistic anyway: a principal shaped unlike
        // production is exactly how a prior vacuous-test bug got into this class (a null-division
        // ceoActor made every CEO-path test in an earlier per-caller-scoped design unable to tell
        // "correctly ignores division" apart from "never had a division to ignore" -- caught only
        // by an independent mutation-check, not by inspection). Reverting to the 2-arg (null
        // division) form would silently reopen that trap the next time this method's scoping
        // logic changes.
        managerActor = principal(managerEmployeeId, "sales_manager", divisionIdOf(managerEmployeeId));
        ceoActor = principal(ceoEmployeeId, "ceo", divisionIdOf(ceoEmployeeId));

        // Denied roles are rejected at the role gate before any DB lookup (same as
        // CommissionListScopeIntegrationTest's importActor/employeeActor/etc.) — synthetic ids.
        salesActor = principal(999_711L, "sales");
        hrActor = principal(999_722L, "hr"); // hr CAN read /api/employees; must NOT reach this narrower list.
        accountActor = principal(999_733L, "account");
        employeeActor = principal(999_744L, "employee"); // DivisionAccessPolicy fallback role.
    }

    // ── 1-4: wrong-way-round — denied roles get FORBIDDEN, never a list ─────────────────────

    @Test
    void salesActor_isForbidden() {
        wireService();
        assertThatThrownBy(() -> commissionService.listManualCommissionRepOptions(salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void hrActor_isForbidden() {
        wireService();
        // hr is the one role that CAN read /api/employees (EmployeeController#list) -- it must
        // NOT also reach this narrower, purpose-built list. A widened gate here would be the
        // asymmetry CLAUDE.md's mock-vs-real warning is about, just inside real code this time.
        assertThatThrownBy(() -> commissionService.listManualCommissionRepOptions(hrActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void accountActor_isForbidden() {
        wireService();
        assertThatThrownBy(() -> commissionService.listManualCommissionRepOptions(accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void plainEmployeeActor_isForbidden() {
        wireService();
        assertThatThrownBy(() -> commissionService.listManualCommissionRepOptions(employeeActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── 5-6: positive side — issue #737's actual symptom, an employee who owns no deal ──────

    @Test
    void salesManager_seesAnEmployeeWhoOwnsNoDealAtAll() {
        wireService();
        // The whole point of #737: this employee has NO ticket, NO deal, nothing in
        // sales.commission_record -- TicketRepository is mocked in this class (never touched by
        // listManualCommissionRepOptions), so there is no code path here that could even create
        // one. The OLD picker (api.tickets.list({})'s distinct createdById) would never have
        // shown this person; the new one must, because it reads hr.employee directly. "SA" here
        // is load-bearing: it must be ฝ่ายขาย for this employee to be visible at all -- see
        // employeeOutsideSalesDivision_isNotReturned below for the negative case.
        long noDealEmployeeId = createEmployee("พนักงานขาย ไม่มีดีลเลย", "reps-no-deal@glr.co.th", "SA", "แผนกขาย");

        List<CommissionRepOptionDto> reps = commissionService.listManualCommissionRepOptions(managerActor);

        CommissionRepOptionDto found = reps.stream()
            .filter(rep -> rep.id() == noDealEmployeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("employee with no deal at all is missing from the rep picker"));
        assertThat(found.name()).isEqualTo("พนักงานขาย ไม่มีดีลเลย");
    }

    @Test
    void ceo_seesAnEmployeeWhoOwnsNoDealAtAll() {
        wireService();
        long noDealEmployeeId = createEmployee("พนักงานขาย ไม่มีดีลเลยสอง", "reps-no-deal-2@glr.co.th", "SA", "แผนกขาย");

        List<CommissionRepOptionDto> reps = commissionService.listManualCommissionRepOptions(ceoActor);

        CommissionRepOptionDto found = reps.stream()
            .filter(rep -> rep.id() == noDealEmployeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("employee with no deal at all is missing from the rep picker"));
        assertThat(found.name()).isEqualTo("พนักงานขาย ไม่มีดีลเลยสอง");
    }

    // ── 7: is_active is a filter (alongside the ฝ่ายขาย match) ───────────────────────────────

    @Test
    void inactiveEmployee_isNotReturned() {
        wireService();
        // Both employees are ฝ่ายขาย ("SA") -- deliberately, so this pins is_active specifically
        // and not, incidentally, the division match instead.
        long inactiveId = createEmployee("พนักงานขาย พ้นสภาพ", "reps-inactive@glr.co.th", "SA", "แผนกขาย");
        long activeControlId = createEmployee("พนักงานขาย ยังทำงานอยู่", "reps-active-control@glr.co.th", "SA", "แผนกขาย");
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id", Map.of("id", inactiveId));

        List<Long> ids = commissionService.listManualCommissionRepOptions(managerActor).stream()
            .map(CommissionRepOptionDto::id)
            .toList();

        assertThat(ids).doesNotContain(inactiveId);
        assertThat(ids).contains(activeControlId);
    }

    // ── 8: ordering — primary sort direction and no LIMIT (read the note before trusting more) ──

    /**
     * What this pins, and — as important — what it does NOT.
     *
     * <p><b>Pinned:</b> the repository's {@code ORDER BY} starts with {@code display_name} ASC,
     * and applies no LIMIT, within ฝ่ายขาย. Both are proven the same way: an oracle query written
     * HERE (not a call into the method under test) independently computes the expected id order,
     * and the method's actual output must equal it exactly. A DESC flip (or any reordering of the
     * primary key) turns this red because the two sequences would then disagree; an added LIMIT
     * turns this red because {@code reps} would come back shorter than {@code expectedOrder}.
     *
     * <p>The oracle is deliberately SCOPED to ฝ่ายขาย, matching the real query's actual current
     * contract (there is no company-wide path left to compare against): {@code wireService()}'s
     * own {@code ceoEmployeeId} sits in division MD, so an unscoped oracle would silently start
     * disagreeing with the real, ฝ่ายขาย-only result the moment the picker correctly excludes it.
     *
     * <p><b>Deliberately NOT pinned: the {@code employee_id} tie-break.</b> An earlier version of
     * this test seeded two employees with an IDENTICAL display name and asserted they came back
     * in ascending-id order. That assertion does not work, and this was not a hunch — it was
     * mutation-checked: dropping {@code , e.employee_id} from the repository's {@code ORDER BY}
     * left every test in this class green, including that one, run twice. Postgres is free to
     * return tied rows in whatever order its plan produces, and for a freshly-seeded table with
     * no updates that happens to already match insertion order — which is indistinguishable, in a
     * single run, from a real tie-break actually doing the ordering. A test that cannot fail is
     * not evidence (CLAUDE.md), so the assertion was removed rather than kept under a comment
     * promising coverage it does not have. The tie-break stays in the production SQL (it is still
     * the right thing to ship — it makes the query's own single execution deterministic, which
     * matters even though this suite cannot independently prove it from outside).
     *
     * <p>The {@code is_active} filter is pinned by {@link #inactiveEmployee_isNotReturned()}
     * above, the {@code COALESCE} name fallback by {@link
     * #repOptions_fallsBackToEmployeeCode_whenBothNameColumnsAreNull()} below, and the ฝ่ายขาย
     * match itself by {@link #employeeOutsideSalesDivision_isNotReturned()} below — NOT by the
     * oracle here, whose only extra condition beyond the real query's own is a fixed division
     * filter mirrored, not independently exercised.
     */
    @Test
    void repOptions_areOrderedByDisplayName_withNoLimit() {
        wireService();
        // Distinctly-named ฝ่ายขาย employees on top of wireService's managerEmployeeId, so the
        // ordering being checked is over more than an incidental two-row set.
        createEmployee("พนักงานขาย เรียงลำดับหนึ่ง", "reps-order-a@glr.co.th", "SA", "แผนกขาย");
        createEmployee("พนักงานขาย เรียงลำดับสอง", "reps-order-b@glr.co.th", "SA", "แผนกขาย");

        List<CommissionRepOptionDto> reps = commissionService.listManualCommissionRepOptions(ceoActor);

        List<Long> expectedOrder = jdbc.queryForList("""
            SELECT e.employee_id
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE e.is_active
               AND LOWER(TRIM(COALESCE(NULLIF(TRIM(d.source_code), ''), split_part(d.name_th, '-', 1)))) = :salesDivisionCode
             ORDER BY COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.employee_code),
                      e.employee_id
            """, Map.of("salesDivisionCode", DivisionAccessPolicy.SALES_DIVISION_CODE), Long.class);
        assertThat(reps.stream().map(CommissionRepOptionDto::id).toList()).isEqualTo(expectedOrder);
    }

    // ── 9: the COALESCE fallback — employee_code when both Thai name columns are null ───────

    @Test
    void repOptions_fallsBackToEmployeeCode_whenBothNameColumnsAreNull() {
        wireService();
        // first_name_th/last_name_th are nullable (V1__employee_master_schema.sql:110-111); the
        // createEmployee helper below always supplies a Thai name, so nulling both out directly
        // is the only way to reach display_name's COALESCE fallback branch.
        long employeeId = createEmployee("พนักงานทดสอบ รหัสสำรอง", "reps-fallback@glr.co.th", "SA", "แผนกขาย");
        jdbc.update("UPDATE hr.employee SET first_name_th = NULL, last_name_th = NULL WHERE employee_id = :id",
            Map.of("id", employeeId));
        String expectedCode = jdbc.queryForObject(
            "SELECT employee_code FROM hr.employee WHERE employee_id = :id", Map.of("id", employeeId), String.class);

        List<CommissionRepOptionDto> reps = commissionService.listManualCommissionRepOptions(managerActor);

        CommissionRepOptionDto found = reps.stream()
            .filter(rep -> rep.id() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("employee with null Thai name columns is missing from the rep picker"));
        // Not null, not blank: a blank display_name would render an empty <option> in the picker
        // -- indistinguishable from a rendering bug, and just as unusable for the user.
        assertThat(found.name()).isNotBlank();
        assertThat(found.name()).isEqualTo(expectedCode);
    }

    // ── ฝ่ายขาย SCOPE — owner ruling 2026-08-14, 2nd ruling ───────────────────────────────────

    /**
     * The assertion that matters most in this whole class now: an employee outside ฝ่ายขาย must
     * never appear, for EITHER role — there is no longer a per-caller scope for one role to leak
     * around. Asserted on ids, not counts, for both {@code managerActor} and {@code ceoActor}
     * from the SAME seeded employee.
     */
    @Test
    void employeeOutsideSalesDivision_isNotReturned() {
        wireService();
        long outsideId = createEmployee("พนักงาน ฝ่ายคลังสินค้า", "reps-outside-sales@glr.co.th", "WH", "คลังสินค้า");

        List<Long> managerIds = commissionService.listManualCommissionRepOptions(managerActor).stream()
            .map(CommissionRepOptionDto::id)
            .toList();
        List<Long> ceoIds = commissionService.listManualCommissionRepOptions(ceoActor).stream()
            .map(CommissionRepOptionDto::id)
            .toList();

        assertThat(managerIds).doesNotContain(outsideId);
        assertThat(ceoIds).doesNotContain(outsideId);
    }

    /**
     * Pins that the per-caller scoping is really gone, not accidentally still applying to one
     * role: {@code sales_manager} and {@code ceo} must get the byte-for-byte IDENTICAL list
     * (same ids, same order — {@code containsExactlyElementsOf} checks both).
     *
     * <p>The SECOND employee, seeded into a DIFFERENT {@code hr.division} row than {@code
     * managerActor}'s own (a name-only division, resolved via the {@code split_part} fallback —
     * same mechanism as {@link #employeeInDivisionWithNoSourceCode_matchesByNamePrefixFallback()}),
     * is load-bearing, not incidental. {@code managerActor}'s own division IS the {@code "SA"}
     * ฝ่ายขาย row, so a single employee seeded into THAT SAME row leaves "my division" and
     * "ฝ่ายขาย" indistinguishable — a mutation that quietly restored per-caller scoping for {@code
     * sales_manager} only would still pass this test, because {@code managerActor}'s own division
     * happens to already be the whole answer. Confirmed by actually running that mutation, not
     * assumed: see the class-level MUTATION-CHECK RECORD.
     */
    @Test
    void salesManager_andCeo_seeTheSameSalesDivisionList() {
        wireService();
        createEmployee("พนักงานขาย เหมือนกันทั้งสองสิทธิ์", "reps-same-list@glr.co.th", "SA", "แผนกขาย");
        createEmployee("พนักงานขาย อีกฝ่ายรหัสว่าง", "reps-same-list-2@glr.co.th", null, "SA-ฝ่ายขาย");

        List<CommissionRepOptionDto> managerReps = commissionService.listManualCommissionRepOptions(managerActor);
        List<CommissionRepOptionDto> ceoReps = commissionService.listManualCommissionRepOptions(ceoActor);

        assertThat(managerReps).containsExactlyElementsOf(ceoReps);
    }

    /**
     * Proves the SQL reproduces {@code DivisionAccessPolicy#divisionCode}'s name-prefix fallback,
     * not only a {@code source_code} match — genuinely reachable, traced through real code rather
     * than assumed: {@code EmployeeReferenceRepository#ensureDivision} routes a null/blank source
     * code to {@code #findOrInsertDivisionByName}, whose {@code INSERT} omits {@code source_code}
     * from the column list entirely (leaving it {@code NULL}, not merely blank) — confirmed by
     * the sanity assertion below, which would fail loudly if a schema/repository change ever made
     * this path stop leaving {@code source_code} null.
     */
    @Test
    void employeeInDivisionWithNoSourceCode_matchesByNamePrefixFallback() {
        wireService();
        long employeeId = createEmployee("พนักงานขาย ไม่มีรหัสฝ่าย", "reps-name-prefix@glr.co.th", null, "SA-ฝ่ายขาย");
        String actualSourceCode = jdbc.queryForObject("""
            SELECT d.source_code
              FROM hr.division d
              JOIN hr.employee e ON e.division_id = d.division_id
             WHERE e.employee_id = :id
            """, Map.of("id", employeeId), String.class);
        assertThat(actualSourceCode).isNull(); // sanity: this case means nothing if source_code ended up non-null

        List<Long> ids = commissionService.listManualCommissionRepOptions(managerActor).stream()
            .map(CommissionRepOptionDto::id)
            .toList();

        assertThat(ids).contains(employeeId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /**
     * Reads back the real {@code division_id} an employee's creation resolved to (via {@code
     * EmployeeReferenceRepository#ensureDivision}'s get-or-create-by-source_code). Needed because
     * {@code createEmployee} takes a source CODE ("SA"), not the numeric FK a {@link UserPrincipal}
     * needs — same pattern {@code ManagerApproverInvariantIntegrationTest} uses.
     */
    private long divisionIdOf(long employeeId) {
        return jdbc.queryForObject(
            "SELECT division_id FROM hr.employee WHERE employee_id = :id", Map.of("id", employeeId), Long.class);
    }

    /** Denied-role callers: no employee-linked division. */
    private static UserPrincipal principal(long employeeId, String role) {
        return principal(employeeId, role, null);
    }

    /**
     * As {@link #principal(long, String)}, plus an explicit {@code divisionId} — kept realistic
     * for {@code managerActor}/{@code ceoActor} (see {@link #wireService()}'s comment) even though
     * the current scope rule no longer reads either actor's own division.
     */
    private static UserPrincipal principal(long employeeId, String role, Long divisionId) {
        return new UserPrincipal(employeeId, role + "-" + employeeId + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, divisionId, false);
    }
}
