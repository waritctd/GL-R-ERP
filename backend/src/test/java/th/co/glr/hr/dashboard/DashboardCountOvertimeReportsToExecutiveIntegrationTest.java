package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * CEO-approval-reach follow-on, round 3 (2026-09-01): closes gap "A3", self-declared by {@code
 * OvertimeReportsToExecutiveIntegrationTest}'s own class Javadoc. {@link
 * DashboardRepository#countOvertime} spliced in the same {@code reportsToExecutiveSql} exclusion
 * that class's "A1"/"A2" tests already proved against real Postgres for {@code
 * OvertimeRepository#findRequests}/{@code #findEmployeeOptions}, but nothing positively proved
 * this THIRD site fires -- {@code
 * DashboardRepositoryIntegrationTest#aggregatesDashboardSectionsWithScopes}'s fixture never sets
 * {@code reports_to_employee_id}, so the exclusion is a no-op there: disabling it causes zero test
 * failures anywhere in the suite. This class is that missing positive proof.
 *
 * <p>The {@code reportsTo}-aware {@code insertEmployee}/{@code insertDivision}/{@code
 * insertPosition} helpers below are lifted in shape from {@code
 * OvertimeReportsToExecutiveIntegrationTest} rather than reinvented -- {@code
 * DashboardRepositoryIntegrationTest}'s own {@code insertEmployee} has no {@code reportsTo}
 * parameter at all, which is exactly why its fixture cannot exercise this exclusion.
 */
class DashboardCountOvertimeReportsToExecutiveIntegrationTest extends AbstractPostgresIntegrationTest {

    private DashboardRepository repository;
    private long division;
    private long activeExecutive;

    @BeforeEach
    void wireRepositoryAndSharedFixture() {
        repository = new DashboardRepository(jdbc);
        division = insertDivision("DOT", "ฝ่ายทดสอบแดชบอร์ด");
        activeExecutive = insertEmployee("DOT-EXEC", null, "กรรมการ", true, null);
    }

    /**
     * The headline positive case: a DIVISION-scoped overtime badge (what {@link
     * DashboardRepository#countOvertime} treats as a division manager's queue) must EXCLUDE a
     * SUBMITTED request from an employee in the division who reports straight to an active
     * executive -- they have no manager stage for anyone to act on (see {@code
     * ManagerApproverRepository}'s rule 3) -- while still COUNTING an ordinary division peer's
     * SUBMITTED request from the SAME query. The peer is a positive control: without it, the
     * exclusion firing and the fixture simply having zero matching rows would look identical (both
     * assert 0). Asserting exactly 1 -- not "zero or more" -- means the bypassed employee's row is
     * the specific one being subtracted, not an accident of an empty fixture.
     */
    @Test
    void divisionScopedOvertimeCountExcludesEmployeeReportingToAnActiveExecutive() {
        long bypassedStaff = insertEmployee("DOT-STF1", division, null, true, activeExecutive);
        long ordinaryPeer = insertEmployee("DOT-STF2", division, null, true, null);
        insertSubmittedOvertime(bypassedStaff);
        insertSubmittedOvertime(ordinaryPeer);

        PendingApprovalsSummaryDto pending = repository.pendingApprovals(
            DashboardQueryScope.division(division),
            new DashboardPendingVisibility(true, true, true, true, true),
            DashboardQueryScope.division(division),
            DashboardQueryScope.division(division),
            DashboardQueryScope.division(division)
        );

        assertThat(pending.overtime()).isEqualTo(1);
    }

    private void insertSubmittedOvertime(long employeeId) {
        LocalDate workDate = LocalDate.of(2026, 7, 5);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        OffsetDateTime endAt = workDate.atTime(19, 0).atOffset(ZoneOffset.ofHours(7));
        jdbc.update("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at,
                planned_minutes, reason, payroll_month
            )
            VALUES (
                :employeeId, :workDate, :startAt, :endAt,
                60, 'Month-end closing', :payrollMonth
            )
            """, Map.of(
                "employeeId", employeeId,
                "workDate", workDate,
                "startAt", startAt,
                "endAt", endAt,
                "payrollMonth", workDate.withDayOfMonth(1)
            ));
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(
            String code, Long divisionId, String positionNameTh, boolean active, Long reportsTo) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsTo);
        params.put("active", active);
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

    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }
}
