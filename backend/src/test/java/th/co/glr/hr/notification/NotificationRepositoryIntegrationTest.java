package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

// Locks in that ticket-event notifications land in hr.notification (the table the notification bell
// and dashboard unread-count now read from) rather than the legacy sales.notification table, which
// nothing reads anymore. See docs/agent-handoffs/34_feat-notification-email-backbone.md.
class NotificationRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private NotificationRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new NotificationRepository(jdbc);
    }

    @Test
    void notifyEmployeeWritesToHrNotificationWithTicketLink() {
        long divisionId = insertDivision("SA", "Sales");
        long employeeId = insertEmployee("EMP-100", divisionId, true);

        repository.notifyEmployee(employeeId, 55L, "APPROVED", "Ticket PR-2026-0099 ได้รับการอนุมัติราคาแล้ว");

        List<NotificationDto> rows = repository.findByEmployeeId(employeeId);
        assertThat(rows).hasSize(1);
        NotificationDto row = rows.get(0);
        assertThat(row.type()).isEqualTo("APPROVED");
        assertThat(row.title()).isEqualTo("ราคาได้รับการอนุมัติ");
        assertThat(row.message()).isEqualTo("Ticket PR-2026-0099 ได้รับการอนุมัติราคาแล้ว");
        assertThat(row.link()).isEqualTo("/tickets/55");
        assertThat(countLegacySalesNotifications()).isZero();
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
