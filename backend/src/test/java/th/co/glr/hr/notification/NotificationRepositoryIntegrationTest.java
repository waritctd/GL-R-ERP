package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

// Locks in that ticket-event notifications land in hr.notification (the table the notification bell and
// dashboard unread-count now read from) rather than the legacy sales.notification table, which nothing reads
// anymore. See docs/agent-handoffs/34_feat-notification-email-backbone.md.
class NotificationRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private NotificationRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
    }

    @Test
    void notifyByRoleFansOutToActiveEmployeesInMatchingDivisionOnly() {
        long importDivision = insertDivision("PCIM", "Import");
        long salesDivision = insertDivision("SA", "Sales");
        long activeImportEmployee = insertEmployee("EMP-200", importDivision, true);
        long inactiveImportEmployee = insertEmployee("EMP-201", importDivision, false);
        long salesEmployee = insertEmployee("EMP-202", salesDivision, true);

        repository.notifyByRole("import", 77L, "SUBMITTED", "Ticket PR-2026-0100 รอการรับเรื่อง");

        assertThat(repository.findByEmployeeId(activeImportEmployee)).hasSize(1);
        assertThat(repository.findByEmployeeId(inactiveImportEmployee)).isEmpty();
        assertThat(repository.findByEmployeeId(salesEmployee)).isEmpty();
        assertThat(countLegacySalesNotifications()).isZero();
    }

    /** sales_manager is the one role here that is not a whole ฝ่าย: it is a strict SUBSET of sales, narrowed by
     * position. */
    @Test
    void notifyByRoleSalesManagerSelectsOnlyManagersWithinSalesAndNooneElse() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long importDivision = insertDivision("PCIM", "จัดซื้อต่างประเทศ");
        long manager = insertEmployee("EMP-400", salesDivision, true, "ผู้จัดการฝ่ายขาย");
        long assistantManager = insertEmployee("EMP-401", salesDivision, true, "ผู้ช่วยผู้จัดการฝ่ายขาย");
        long departedManager = insertEmployee("EMP-402", salesDivision, false, "ผู้จัดการฝ่ายขาย");
        long rep = insertEmployee("EMP-403", salesDivision, true, "พนักงานขาย");
        long repWithNoPosition = insertEmployee("EMP-404", salesDivision, true);
        long managerOfAnotherDivision = insertEmployee("EMP-405", importDivision, true, "ผู้จัดการฝ่ายจัดซื้อ");

        repository.notifyByRole("sales_manager", 99L, "STOCK_RESERVED", "ดีล PR-2026-0101 ประกาศสินค้าจากสต็อก");

        assertThat(repository.findByEmployeeId(manager)).hasSize(1);
        assertThat(repository.findByEmployeeId(manager).get(0).title())
            .isEqualTo("พนักงานขายประกาศสินค้าจากสต็อกเอง");
        assertThat(repository.findByEmployeeId(assistantManager))
            .describedAs("ผู้ช่วยผู้จัดการ contains ผู้จัดการ — DivisionAccessPolicy counts assistants too")
            .hasSize(1);
        assertThat(repository.findByEmployeeId(departedManager)).isEmpty();
        assertThat(repository.findByEmployeeId(rep)).isEmpty();
        assertThat(repository.findByEmployeeId(repWithNoPosition)).isEmpty();
        assertThat(repository.findByEmployeeId(managerOfAnotherDivision)).isEmpty();

        // The superset still behaves as before, NULL position included.
        repository.notifyByRole("sales", 99L, "SUBMITTED", "ทุกคนในฝ่ายขาย");
        assertThat(repository.findByEmployeeId(repWithNoPosition))
            .describedAs("LEFT JOIN, not JOIN: a null position_id must not drop anyone from the "
                + "division-only branches")
            .hasSize(1);
        assertThat(repository.findByEmployeeId(rep)).hasSize(1);
        assertThat(repository.findByEmployeeId(managerOfAnotherDivision)).isEmpty();
    }

    private Integer countLegacySalesNotifications() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM sales.notification", Integer.class);
    }

    private long insertDivision(String code, String name) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th)
            VALUES (:code, :name)
            RETURNING division_id
            """, Map.of("code", code, "name", name), Number.class);
        return id.longValue();
    }

    /** Leaves {@code position_id} NULL — the shape the division-only branches must still reach. */
    private long insertEmployee(String code, long divisionId, boolean active) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES (:code, :name, :divisionId, :active)
            RETURNING employee_id
            """, Map.of("code", code, "name", code, "divisionId", divisionId, "active", active), Number.class);
        return id.longValue();
    }

    private long insertEmployee(String code, long divisionId, boolean active, String positionTh) {
        long employeeId = insertEmployee(code, divisionId, active);
        jdbc.update("UPDATE hr.employee SET position_id = :positionId WHERE employee_id = :id",
            Map.of("positionId", ensurePosition(positionTh), "id", employeeId));
        return employeeId;
    }

    /** V30 seeds a bare "ผู้จัดการ"; the fuller ฝ่าย-specific titles used here are created on demand. */
    private long ensurePosition(String nameTh) {
        List<Long> existing = jdbc.queryForList(
            "SELECT position_id FROM hr.position WHERE name_th = :name LIMIT 1",
            Map.of("name", nameTh), Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return jdbc.queryForObject("""
            INSERT INTO hr.position (name_th, is_active) VALUES (:name, TRUE) RETURNING position_id
            """, Map.of("name", nameTh), Long.class);
    }
}
