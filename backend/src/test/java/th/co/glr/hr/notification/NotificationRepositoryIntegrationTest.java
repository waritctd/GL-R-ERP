package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

// Locks in that notifications land in hr.notification (the table the notification bell and dashboard
// unread-count now read from) rather than the legacy sales.notification table, which nothing reads
// anymore. See docs/agent-handoffs/34_feat-notification-email-backbone.md.
//
// notifyEmployee/notifyByRole (insert + auto-derived title) were removed in favor of
// NotificationService.notify/notifyByRole, which insert AND optionally email through one shared path
// (see NotificationServiceTest for that orchestration). This repository now only resolves *who* to
// notify by role - findActiveEmployeeIdsByRole - plus the plain insert/read primitives.
class NotificationRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private NotificationRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new NotificationRepository(jdbc);
    }

    @Test
    void insertWritesToHrNotificationNotLegacySalesTable() {
        long divisionId = insertDivision("SA", "Sales");
        long employeeId = insertEmployee("EMP-100", divisionId, true);

        long id = repository.insert(employeeId, "APPROVED", "ราคาได้รับการอนุมัติ",
            "Ticket PR-2026-0099 ได้รับการอนุมัติราคาแล้ว", "/tickets/55");

        NotificationDto row = repository.findById(id).orElseThrow();
        assertThat(row.employeeId()).isEqualTo(employeeId);
        assertThat(row.type()).isEqualTo("APPROVED");
        assertThat(row.title()).isEqualTo("ราคาได้รับการอนุมัติ");
        assertThat(row.message()).isEqualTo("Ticket PR-2026-0099 ได้รับการอนุมัติราคาแล้ว");
        assertThat(row.link()).isEqualTo("/tickets/55");
        assertThat(repository.findByEmployeeId(employeeId)).containsExactly(row);
        assertThat(countLegacySalesNotifications()).isZero();
    }

    @Test
    void findActiveEmployeeIdsByRoleReturnsOnlyActiveEmployeesInMatchingDivision() {
        long importDivision = insertDivision("PCIM", "Import");
        long salesDivision = insertDivision("SA", "Sales");
        long activeImportEmployee = insertEmployee("EMP-200", importDivision, true);
        long inactiveImportEmployee = insertEmployee("EMP-201", importDivision, false);
        long salesEmployee = insertEmployee("EMP-202", salesDivision, true);

        List<Long> importIds = repository.findActiveEmployeeIdsByRole("import");

        assertThat(importIds).containsExactly(activeImportEmployee);
        assertThat(importIds).doesNotContain(inactiveImportEmployee, salesEmployee);
    }

    @Test
    void findActiveEmployeeIdsByRoleReturnsEmptyForUnmappedRole() {
        assertThat(repository.findActiveEmployeeIdsByRole("employee")).isEmpty();
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

    private long insertEmployee(String code, long divisionId, boolean active) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES (:code, :name, :divisionId, :active)
            RETURNING employee_id
            """, Map.of("code", code, "name", code, "divisionId", divisionId, "active", active), Number.class);
        return id.longValue();
    }
}
