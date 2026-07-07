package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

class DashboardRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private DashboardRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new DashboardRepository(jdbc);
    }

    @Test
    void aggregatesDashboardSectionsWithScopes() {
        LocalDate today = LocalDate.of(2026, 7, 5);
        LocalDate monthStart = LocalDate.of(2026, 7, 1);
        OffsetDateTime morning = OffsetDateTime.parse("2026-07-05T08:30:00+07:00");
        OffsetDateTime evening = OffsetDateTime.parse("2026-07-05T17:30:00+07:00");

        long salesDivision = insertDivision("SA", "Sales");
        long hrDivision = insertDivision("HR", "Human Resources");
        long salesEmployee = insertEmployee("EMP-001", salesDivision, true);
        long salesInactive = insertEmployee("EMP-002", salesDivision, false);
        long hrEmployee = insertEmployee("EMP-003", hrDivision, true);

        insertProfileRequest(salesEmployee, "pending");
        insertProfileRequest(hrEmployee, "pending");
        insertProfileRequest(salesEmployee, "approved");
        insertOvertime(salesEmployee, today, monthStart);
        insertOvertime(hrEmployee, today, monthStart);
        insertLeave(salesEmployee, today);
        insertLeave(hrEmployee, today);
        insertAttendance(salesEmployee, today, morning, null, 10, 1, false);
        insertAttendance(hrEmployee, today, morning, evening, 0, 2, false);
        insertAttendance(salesInactive, today, null, null, 0, 0, true);
        insertTicket("PR-1", "submitted", salesEmployee, today.minusDays(4));
        insertTicket("PR-2", "price_proposed", hrEmployee, today.minusDays(4));
        insertTicket("PR-3", "closed", salesEmployee, today.minusDays(1));
        insertCommission(salesEmployee, monthStart);
        insertNotification(salesEmployee, false);
        insertNotification(salesEmployee, true);
        insertNotification(hrEmployee, false);

        HeadcountSummaryDto allHeadcount = repository.headcount(DashboardQueryScope.all());
        assertThat(allHeadcount.active()).isEqualTo(2);
        assertThat(allHeadcount.inactive()).isEqualTo(1);
        assertThat(allHeadcount.total()).isEqualTo(3);

        HeadcountSummaryDto divisionHeadcount = repository.headcount(DashboardQueryScope.division(salesDivision));
        assertThat(divisionHeadcount.active()).isEqualTo(1);
        assertThat(divisionHeadcount.inactive()).isEqualTo(1);
        assertThat(divisionHeadcount.byDivision()).hasSize(1);

        PendingApprovalsSummaryDto allPending = repository.pendingApprovals(
            DashboardQueryScope.all(),
            new DashboardPendingVisibility(true, true, true, true, true),
            DashboardQueryScope.all(),
            DashboardQueryScope.all()
        );
        assertThat(allPending.profileRequests()).isEqualTo(2);
        assertThat(allPending.overtime()).isEqualTo(2);
        assertThat(allPending.leave()).isEqualTo(2);
        assertThat(allPending.commissions()).isEqualTo(1);
        assertThat(allPending.tickets()).isEqualTo(2);

        PendingApprovalsSummaryDto salesPending = repository.pendingApprovals(
            DashboardQueryScope.division(salesDivision),
            new DashboardPendingVisibility(true, true, true, true, true),
            DashboardQueryScope.division(salesDivision),
            DashboardQueryScope.self(salesEmployee)
        );
        assertThat(salesPending.profileRequests()).isEqualTo(1);
        assertThat(salesPending.overtime()).isEqualTo(1);
        assertThat(salesPending.leave()).isEqualTo(1);
        assertThat(salesPending.tickets()).isEqualTo(1);

        AttendanceSummaryDto attendance = repository.attendance(DashboardQueryScope.all(), today, monthStart);
        assertThat(attendance.todayPresent()).isEqualTo(2);
        assertThat(attendance.lateToday()).isEqualTo(1);
        assertThat(attendance.missingCheckout()).isEqualTo(1);
        assertThat(attendance.punchCountToday()).isEqualTo(3);

        AttendanceSummaryDto selfAttendance = repository.attendance(DashboardQueryScope.self(salesEmployee), today, monthStart);
        assertThat(selfAttendance.todayStatus()).isEqualTo("PRESENT");
        assertThat(selfAttendance.lateMinutesToday()).isEqualTo(10);

        TicketSummaryDto tickets = repository.tickets(
            DashboardQueryScope.all(),
            monthStart,
            OffsetDateTime.parse("2026-07-02T09:00:00+07:00")
        );
        assertThat(tickets.submitted()).isEqualTo(1);
        assertThat(tickets.priceProposed()).isEqualTo(1);
        assertThat(tickets.totalOpen()).isEqualTo(2);
        assertThat(tickets.closedThisMonth()).isEqualTo(1);
        assertThat(tickets.overdueOver3Days()).isEqualTo(2);

        NotificationSummaryDto notifications = repository.notifications(salesEmployee);
        assertThat(notifications.unread()).isEqualTo(1);
        assertThat(notifications.read()).isEqualTo(1);
        assertThat(notifications.total()).isEqualTo(2);

        assertThat(repository.headcount(DashboardQueryScope.self(salesEmployee)).active()).isNull();
    }

    private long insertDivision(String code, String name) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th)
            VALUES (:code, :name)
            RETURNING division_id
            """, Map.of("code", code, "name", name), Number.class);
        return id.longValue();
    }

    private long insertEmployee(String code, long divisionId, boolean active) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES (:code, :name, :divisionId, :active)
            RETURNING employee_id
            """, Map.of("code", code, "name", code, "divisionId", divisionId, "active", active), Number.class);
        return id.longValue();
    }

    private void insertProfileRequest(long employeeId, String status) {
        jdbc.update("""
            INSERT INTO hr.profile_change_request (
                employee_id, field_key, field_label, new_value, status
            )
            VALUES (:employeeId, 'phone', 'Phone', '0800000000', :status)
            """, Map.of("employeeId", employeeId, "status", status));
    }

    private void insertOvertime(long employeeId, LocalDate today, LocalDate monthStart) {
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
                "workDate", today,
                "startAt", OffsetDateTime.parse("2026-07-05T18:00:00+07:00"),
                "endAt", OffsetDateTime.parse("2026-07-05T19:00:00+07:00"),
                "payrollMonth", monthStart
            ));
    }

    private void insertLeave(long employeeId, LocalDate today) {
        jdbc.update("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, total_days,
                quota_year, reason, quota_remaining_before, quota_remaining_after
            )
            VALUES (
                :employeeId, 'VACATION', :startDate, :endDate, 1,
                2026, 'Personal errand', 6, 5
            )
            """, Map.of("employeeId", employeeId, "startDate", today, "endDate", today));
    }

    private void insertAttendance(
            long employeeId,
            LocalDate today,
            OffsetDateTime checkIn,
            OffsetDateTime checkOut,
            int lateMinutes,
            int punchCount,
            boolean absent) {
        jdbc.update("""
            INSERT INTO hr.attendance_daily (
                employee_id, work_date, site_code, check_in, check_out,
                late_minutes, punch_count, is_absent
            )
            VALUES (
                :employeeId, :workDate, 'SHOWROOM', :checkIn, :checkOut,
                :lateMinutes, :punchCount, :absent
            )
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("workDate", today)
                .addValue("checkIn", checkIn)
                .addValue("checkOut", checkOut)
                .addValue("lateMinutes", lateMinutes)
                .addValue("punchCount", punchCount)
                .addValue("absent", absent));
    }

    private void insertTicket(String code, String status, long createdBy, LocalDate createdDate) {
        jdbc.update("""
            INSERT INTO sales.ticket (
                code, title, status, created_by, created_at, updated_at, closed_at
            )
            VALUES (
                :code, :title, :status, :createdBy, :createdAt, :createdAt,
                CASE WHEN :status = 'closed' THEN :createdAt ELSE NULL END
            )
            """, Map.of(
                "code", code,
                "title", code,
                "status", status,
                "createdBy", createdBy,
                "createdAt", createdDate.atStartOfDay().atOffset(BANGKOK_OFFSET)
            ));
    }

    private void insertCommission(long salesRepId, LocalDate payrollMonth) {
        Number invoiceId = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (
                invoice_number, invoice_date, gross_amount
            )
            VALUES ('INV-1', :invoiceDate, 1000)
            RETURNING invoice_id
            """, Map.of("invoiceDate", payrollMonth), Number.class);
        jdbc.update("""
            INSERT INTO sales.commission_record (
                invoice_id, sales_rep_id, submitted_by_id, payroll_month,
                actual_received, commissionable_base
            )
            VALUES (
                :invoiceId, :salesRepId, :salesRepId, :payrollMonth, 1000, 1000
            )
            """, Map.of(
                "invoiceId", invoiceId.longValue(),
                "salesRepId", salesRepId,
                "payrollMonth", payrollMonth
            ));
    }

    private void insertNotification(long employeeId, boolean read) {
        jdbc.update("""
            INSERT INTO sales.notification (employee_id, type, message, is_read)
            VALUES (:employeeId, 'INFO', 'Dashboard test', :read)
            """, Map.of("employeeId", employeeId, "read", read));
    }

    private static final java.time.ZoneOffset BANGKOK_OFFSET = java.time.ZoneOffset.ofHours(7);
}
