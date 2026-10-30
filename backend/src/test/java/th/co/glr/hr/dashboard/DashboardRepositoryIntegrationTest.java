package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
        markOperationalBuckets("PR-1", "ON_HOLD", "PARTIALLY_DELIVERED", today.minusDays(2), BigDecimal.valueOf(100));
        markOperationalBuckets("PR-2", "DORMANT", null, null, null);
        insertOverdueCreditDeal("PR-2", salesEmployee);
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

        // leaveScope (5th arg) is deliberately the SAME value as employeeScope (1st arg) in both
        // calls below, NOT a DashboardQueryScope.ownOrDirectReports(...) -- this fixture's
        // insertEmployee never populates reports_to_employee_id (see its helper below), so an
        // OWN_OR_DIRECT_REPORTS scope would just count 0 here regardless of correctness and prove
        // nothing. That is fine: this test's job is to confirm pendingApprovals(...) wires each
        // counter through to its SQL
        // under a GIVEN scope (ALL/DIVISION here still exercise whereEmployeeScope's shared
        // branches via countLeave, same as before this parameter existed) -- it is not a scope
        // authorization test. The reports-to boundary itself (a manager must NOT see a
        // same-division employee outside their direct reports) is covered by the dedicated
        // DashboardLeaveScopeIntegrationTest, whose fixture does populate reports_to_employee_id.
        PendingApprovalsSummaryDto allPending = repository.pendingApprovals(
            DashboardQueryScope.all(),
            new DashboardPendingVisibility(true, true, true, true, true),
            DashboardQueryScope.all(),
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
            DashboardQueryScope.self(salesEmployee),
            DashboardQueryScope.division(salesDivision)
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
        assertThat(tickets.onHold()).isEqualTo(1);
        assertThat(tickets.dormant()).isEqualTo(1);
        assertThat(tickets.paymentOverdue()).isEqualTo(1);
        assertThat(tickets.partiallyDelivered()).isEqualTo(1);

        NotificationSummaryDto notifications = repository.notifications(salesEmployee);
        assertThat(notifications.unread()).isEqualTo(1);
        assertThat(notifications.read()).isEqualTo(1);
        assertThat(notifications.total()).isEqualTo(2);

        assertThat(repository.headcount(DashboardQueryScope.self(salesEmployee)).active()).isNull();
    }

    @Test
    void cashReceivedByRepSplitsTheSameMonthsCashByTheDealsCreator() {
        long division = insertDivision("SA", "Sales");
        long repA = insertEmployee("EMP-001", division, true);
        long repB = insertEmployee("EMP-002", division, true);
        long unnamed = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES ('EMP-003', '', :d, TRUE) RETURNING employee_id
            """, Map.of("d", division), Number.class).longValue();
        insertTicket("PR-1", "quotation_issued", repA, LocalDate.of(2026, 6, 1));
        insertTicket("PR-2", "quotation_issued", repA, LocalDate.of(2026, 6, 1));
        insertTicket("PR-3", "quotation_issued", repB, LocalDate.of(2026, 6, 1));
        insertTicket("PR-4", "quotation_issued", unnamed, LocalDate.of(2026, 6, 1));
        // a different rep who happens to share EMP-001's name: kept as its own row
        long namesake = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES ('EMP-004', 'EMP-001', :d, TRUE) RETURNING employee_id
            """, Map.of("d", division), Number.class).longValue();
        insertTicket("PR-5", "quotation_issued", namesake, LocalDate.of(2026, 6, 1));
        insertReceipt("PR-5", "DEPOSIT", "50.00", "2026-07-05T09:00:00+07:00", repA);

        insertReceipt("PR-1", "DEPOSIT", "85418.10", "2026-07-02T10:00:00+07:00", repA);
        insertReceipt("PR-1", "ADJUSTMENT", "1000.00", "2026-07-20T10:00:00+07:00", repA);
        // two of repA's deals add up; 00:30 on 1 July in Bangkok is still July's
        insertReceipt("PR-2", "BALANCE", "2000.00", "2026-06-30T17:30:00Z", repA);
        insertReceipt("PR-3", "DEPOSIT", "10000.00", "2026-07-03T09:00:00+07:00", repB);
        insertReceipt("PR-4", "BALANCE", "700.00", "2026-07-04T09:00:00+07:00", repB);
        // outside the Bangkok month: last minute of June, first minute of August
        insertReceipt("PR-3", "BALANCE", "5000.00", "2026-06-30T23:59:59+07:00", repB);
        insertReceipt("PR-1", "BALANCE", "300.00", "2026-08-01T00:00:00+07:00", repA);

        List<CashByRepDto> rows = repository.cashReceivedByRep(LocalDate.of(2026, 7, 1));

        assertThat(rows).extracting(CashByRepDto::repName)
            .containsExactlyInAnyOrder("EMP-001", "EMP-001", "EMP-002", "ไม่ระบุผู้ขาย");
        assertThat(rows.stream().filter(r -> r.repName().equals("EMP-001")).map(CashByRepDto::amount))
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactlyInAnyOrder(new BigDecimal("86418.10"), new BigDecimal("50.00"));
        assertThat(amountOf(rows, "EMP-002")).isEqualByComparingTo("10000.00");
        assertThat(amountOf(rows, "ไม่ระบุผู้ขาย")).isEqualByComparingTo("700.00");
        // the rows are the whole month's cash (the service totals them for cashReceivedThisMonth)
        assertThat(rows.stream().map(CashByRepDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("97168.10");
        assertThat(repository.cashReceivedByRep(LocalDate.of(2026, 5, 1))).isEmpty();
    }

    private static BigDecimal amountOf(List<CashByRepDto> rows, String repName) {
        return rows.stream().filter(r -> r.repName().equals(repName)).findFirst().orElseThrow().amount();
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

    private void insertReceipt(String ticketCode, String kind, String amount, String receivedAt, long recordedBy) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by)
            VALUES ((SELECT ticket_id FROM sales.ticket WHERE code = :code), :kind, :amount, 'THB',
                    :receivedAt, :by)
            """, new MapSqlParameterSource()
                .addValue("code", ticketCode).addValue("kind", kind).addValue("amount", new BigDecimal(amount))
                .addValue("receivedAt", OffsetDateTime.parse(receivedAt)).addValue("by", recordedBy));
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

    private void markOperationalBuckets(String code, String lifecycle, String fulfillmentStatus,
                                        LocalDate dueDate, BigDecimal approvedLineAmount) {
        jdbc.update("""
            UPDATE sales.ticket
               SET lifecycle = :lifecycle,
                   fulfillment_status = :fulfillmentStatus,
                   due_date = :dueDate
             WHERE code = :code
            """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("lifecycle", lifecycle)
                .addValue("fulfillmentStatus", fulfillmentStatus)
                .addValue("dueDate", dueDate));
        if (approvedLineAmount != null) {
            jdbc.update("""
                INSERT INTO sales.ticket_item (ticket_id, brand, qty, approved_price)
                SELECT ticket_id, :brand, 1, :approvedPrice
                  FROM sales.ticket
                 WHERE code = :code
                """, new MapSqlParameterSource()
                    .addValue("code", code)
                    .addValue("brand", code + "-item")
                    .addValue("approvedPrice", approvedLineAmount));
        }
    }

    /** Finance's overdue rule: ACCEPTED quotation, CREDIT 30 days, fully delivered 31 days ago, nothing paid. */
    private void insertOverdueCreditDeal(String code, long deliveredBy) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, total_amount, doc_status,
                                         remainder_mode, credit_days)
            SELECT ticket_id, 'QT-' || code, :by, 100000, 'ACCEPTED', 'CREDIT', 30
              FROM sales.ticket WHERE code = :code
            """, Map.of("code", code, "by", deliveredBy));
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED' WHERE code = :code",
            Map.of("code", code));
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by)
            SELECT ticket_id, 'WAREHOUSE', now() - interval '31 days', :by FROM sales.ticket WHERE code = :code
            """, Map.of("code", code, "by", deliveredBy));
    }

    private void insertCommission(long salesRepId, LocalDate payrollMonth) {
        // V102 (chk_invoice_details_evidence_present) requires every row to show a real
        // attachment OR a recognized evidence_provenance -- this fixture has neither an
        // attachment nor any interest in ข้อ 15 evidence, so it supplies the same
        // PRE_ERP_PAPER_APPROVAL value V102's own inline backfill uses for exactly this shape
        // (attachment-less, dashboard/reporting-only test data), rather than leaving it NULL.
        Number invoiceId = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (
                invoice_number, invoice_date, gross_amount, evidence_provenance
            )
            VALUES ('INV-1', :invoiceDate, 1000, 'PRE_ERP_PAPER_APPROVAL')
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
            INSERT INTO hr.notification (employee_id, type, title, message, is_read)
            VALUES (:employeeId, 'INFO', 'Dashboard test', 'Dashboard test', :read)
            """, Map.of("employeeId", employeeId, "read", read));
    }

    private static final java.time.ZoneOffset BANGKOK_OFFSET = java.time.ZoneOffset.ofHours(7);
}
