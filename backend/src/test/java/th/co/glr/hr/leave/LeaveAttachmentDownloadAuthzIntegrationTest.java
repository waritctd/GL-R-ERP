package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * GET /api/leave/attachments/{attachmentId} (Phase A0b): real-Postgres coverage of {@link
 * LeaveService#resolveAttachmentForDownload} -- the authorization decision that gates reading back a
 * leave attachment (e.g. a SICK medical certificate). Before this phase, a leave attachment was
 * upload-only: nothing could read one back, so this is a NEW authorization surface, not a refactor
 * of an existing one.
 *
 * <p>Mockito cannot reach this: {@link LeaveAttachmentRepository#findAttachmentLocation} and {@link
 * LeaveRepository#findById} are both real dynamic SQL joined against real {@code hr.employee}/{@code
 * hr.leave_request} rows -- a mocked repository would happily return whatever the test told it to,
 * proving nothing about whether the real JOIN/WHERE actually enforces the boundary. Only a real
 * database proves the decision survives into the query.
 *
 * <p>Written WRONG-WAY-ROUND, per CLAUDE.md's rule for authorization changes: every 403 case here
 * asserts what the caller CANNOT reach, not merely that the intended caller can reach their own (the
 * HR/requester/direct-manager cases below are regression guards, not the point of this class).
 */
class LeaveAttachmentDownloadAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveService leaveService;

    private long requester;
    private long requesterManager;
    private long sameDepartmentPeer;
    private long unrelatedEmployee;
    private long otherEmployeeManager;
    private long attachmentId;

    @BeforeEach
    void wireRealCollaboratorsAndSeed() {
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        LeaveAttachmentRepository leaveAttachmentRepository = new LeaveAttachmentRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            leaveAttachmentRepository,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class));

        long department = insertDepartment("Warehouse");
        requesterManager = insertEmployee("MGR-REQ", department, null, null, true);
        requester = insertEmployee("REQ-001", department, null, requesterManager, true);
        // Same department as the requester, but reports to nobody in particular -- proves
        // department membership ALONE grants no access, only the direct-manager relationship does.
        sameDepartmentPeer = insertEmployee("PEER-001", department, null, null, true);
        unrelatedEmployee = insertEmployee("UNRELATED-001", null, null, null, true);
        otherEmployeeManager = insertEmployee("MGR-OTHER", null, null, null, true);
        // Seeded only so otherEmployeeManager genuinely IS someone's direct manager -- just not the
        // requester's -- proving "a manager, but of the wrong employee" is still refused.
        insertEmployee("OTHER-001", null, null, otherEmployeeManager, true);

        long leaveRequestId = insertLeaveRequest(requester);
        attachmentId = insertAttachment(leaveRequestId, requester);
    }

    // --- wrong-way-round: what each caller CANNOT reach ----------------------------------------

    @Test
    void sameDepartmentPeerNotTheManagerIsForbidden() {
        assertForbidden(sameDepartmentPeer);
    }

    @Test
    void unrelatedEmployeeIsForbidden() {
        assertForbidden(unrelatedEmployee);
    }

    @Test
    void managerOfADifferentEmployeeIsForbiddenForThisAttachment() {
        assertForbidden(otherEmployeeManager);
    }

    @Test
    void unknownAttachmentIdIsNotFoundNeverForbidden() {
        // 404, not 403 -- so this endpoint cannot be used to probe which attachment ids exist.
        assertThatThrownBy(() -> leaveService.resolveAttachmentForDownload(999_999_999L, employee(requester)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- V134 storage-durability fix: availability (410) must never leak past authz (403) --------
    //
    // This is the existence-leak guard the ordering in LeaveService#resolveAttachmentForDownload
    // exists for: if availability were checked before authorization, an unauthorized caller could
    // tell a MISSING attachment (which would 410 for someone permitted) apart from one that simply
    // doesn't exist (404) -- without ever being allowed to know the id exists. Both cases below
    // exercise the SAME MISSING row so the fixture cannot silently supply only the state that
    // happens to pass.

    @Test
    void authorizedCallerAgainstAMissingAttachmentGetsGoneNotServerError() {
        long missingAttachmentId = insertAttachmentWithState(insertLeaveRequest(requester), requester, "MISSING");

        assertThatThrownBy(() -> leaveService.resolveAttachmentForDownload(missingAttachmentId, employee(requester)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(HttpStatus.GONE);
    }

    @Test
    void unauthorizedCallerAgainstAMissingAttachmentStillGetsForbiddenNeverGone() {
        long missingAttachmentId = insertAttachmentWithState(insertLeaveRequest(requester), requester, "MISSING");

        // THE point of this test: an unauthorized caller must see the exact same 403 they would see
        // against a perfectly healthy attachment -- never 410, which would only be reachable by
        // someone authorized and would therefore leak that this id exists and is specifically MISSING.
        assertThatThrownBy(() -> leaveService.resolveAttachmentForDownload(missingAttachmentId, employee(unrelatedEmployee)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void authorizedCallerAgainstADatabaseBackedAttachmentSucceeds() {
        long leaveRequestId = insertLeaveRequest(requester);
        long databaseAttachmentId = insertAttachmentWithState(leaveRequestId, requester, "DATABASE");

        LeaveAttachmentRepository.AttachmentLocation location =
            leaveService.resolveAttachmentForDownload(databaseAttachmentId, employee(requester));
        assertThat(location.storageState()).isEqualTo("DATABASE");
        assertThat(location.fileName()).isEqualTo("certificate.pdf");
    }

    // --- regression guards: NOT the point of this class, but must not silently break -----------

    @Test
    void hrCanDownload() {
        LeaveAttachmentRepository.AttachmentLocation location =
            leaveService.resolveAttachmentForDownload(attachmentId, hr());
        assertThat(location.fileName()).isEqualTo("certificate.pdf");
    }

    @Test
    void theRequesterThemselvesCanDownloadTheirOwnAttachment() {
        LeaveAttachmentRepository.AttachmentLocation location =
            leaveService.resolveAttachmentForDownload(attachmentId, employee(requester));
        assertThat(location.fileName()).isEqualTo("certificate.pdf");
    }

    @Test
    void theRequestersOwnDirectManagerCanDownload() {
        LeaveAttachmentRepository.AttachmentLocation location =
            leaveService.resolveAttachmentForDownload(attachmentId, employee(requesterManager));
        assertThat(location.fileName()).isEqualTo("certificate.pdf");
    }

    private void assertForbidden(long actorEmployeeId) {
        assertThatThrownBy(() -> leaveService.resolveAttachmentForDownload(attachmentId, employee(actorEmployeeId)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- helpers ------------------------------------------------------------

    private long insertDepartment(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (name_th, is_active) VALUES (:name, TRUE) RETURNING department_id
            """, Map.of("name", nameTh), Long.class);
    }

    private long insertEmployee(String code, Long departmentId, Long divisionId, Long reportsToId, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("departmentId", departmentId);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsToId);
        params.put("active", active);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th, department_id, division_id,
                 reports_to_employee_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :departmentId, :divisionId, :reportsTo, :active, DATE '2020-01-01')
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertLeaveRequest(long employeeId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.leave_request
                (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                 quota_remaining_before, quota_remaining_after)
            VALUES (:employeeId, 'SICK', DATE '2026-08-10', DATE '2026-08-10', 1.00, 2026,
                    'Integration test leave', 3.00, 2.00)
            RETURNING leave_request_id
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);
    }

    // V134: the shared fixture attachment is DATABASE-backed (a real blob row, not a disk path
    // pointing at a file this test never creates) -- matching what every NEW leave attachment
    // actually looks like post-migration, and avoiding a spurious 410 from the new availability
    // check for every regression test below that expects a plain successful download.
    private long insertAttachment(long leaveRequestId, long uploadedBy) {
        return insertAttachmentWithState(leaveRequestId, uploadedBy, "DATABASE");
    }

    /**
     * V134: inserts a leave attachment already in the given {@code storage_state}. For {@code
     * DATABASE}, also inserts the matching {@code hr.file_attachment_blob} row -- a real
     * DATABASE-state row can never exist without one, and a fixture that skipped it would prove
     * nothing about the real write path.
     */
    private long insertAttachmentWithState(long leaveRequestId, long uploadedBy, String storageState) {
        long newAttachmentId = jdbc.queryForObject("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by, storage_state)
            VALUES ('leave', :ownerId, 'certificate.pdf', 'leave/dummy-key.pdf', 'application/pdf', 1024,
                    :uploadedBy, :storageState)
            RETURNING attachment_id
            """, new MapSqlParameterSource()
                .addValue("ownerId", leaveRequestId)
                .addValue("uploadedBy", uploadedBy)
                .addValue("storageState", storageState),
            Long.class);
        if ("DATABASE".equals(storageState)) {
            jdbc.update("""
                INSERT INTO hr.file_attachment_blob (attachment_id, content) VALUES (:id, :content)
                """, new MapSqlParameterSource()
                    .addValue("id", newAttachmentId)
                    .addValue("content", "หลักฐานประกอบ".getBytes()));
        }
        return newAttachmentId;
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }
}
