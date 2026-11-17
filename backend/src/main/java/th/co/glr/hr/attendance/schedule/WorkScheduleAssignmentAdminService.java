package th.co.glr.hr.attendance.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository.AssignmentWindow;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

/** Manual HR/CEO write path over hr.work_schedule_assignment — the piece V115 shipped schema for but
 * deliberately no admin UI/API for (see WorkScheduleAssignmentRepository's class javadoc). */
@Service
public class WorkScheduleAssignmentAdminService {

    private final WorkScheduleAssignmentRepository repository;
    private final TieredWorkScheduleResolver resolver;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public WorkScheduleAssignmentAdminService(
            WorkScheduleAssignmentRepository repository,
            TieredWorkScheduleResolver resolver,
            AuditService auditService,
            AppProperties properties) {
        this(repository, resolver, auditService, Clock.system(parseZone(properties.getAttendance().getSchedule().getZone())));
    }

    /** Test-only seam: lets integration tests pin "today" instead of racing the real wall clock, exactly like
     * BotHolidayFetchService's Clock constructor. */
    WorkScheduleAssignmentAdminService(
            WorkScheduleAssignmentRepository repository,
            TieredWorkScheduleResolver resolver,
            AuditService auditService,
            Clock clock) {
        this.repository = repository;
        this.resolver = resolver;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<WorkScheduleSummaryDto> listSchedules() {
        return repository.listSchedules();
    }

    public List<WorkScheduleAssignmentDto> listAssignments() {
        return repository.listAssignments();
    }

    public WorkScheduleAssignmentDto create(UserPrincipal actor, WorkScheduleAssignmentCreateRequest request) {
        ScopeType scopeType = request.scopeType();
        if (scopeType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุประเภทขอบเขต (scope_type)");
        }
        Long scopeId = request.scopeId();
        if (scopeId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุรหัสของขอบเขตที่ต้องการกำหนดตารางเวลาทำงาน");
        }
        Integer workScheduleId = request.workScheduleId();
        if (workScheduleId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุตารางเวลาทำงานที่ต้องการกำหนด");
        }
        LocalDate effectiveFrom = request.effectiveFrom();
        if (effectiveFrom == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่เริ่มมีผล");
        }
        LocalDate effectiveTo = request.effectiveTo();
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มมีผล");
        }

        LocalDate today = LocalDate.now(clock);
        if (effectiveFrom.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่สามารถกำหนดวันที่เริ่มมีผลย้อนหลังได้ (ต้องไม่ก่อนวันที่ " + today
                    + ") เนื่องจากจะกระทบผลการคำนวณเวลาทำงานของวันที่ผ่านมาแล้ว");
        }

        if (!repository.scheduleExists(workScheduleId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบตารางเวลาทำงานที่ระบุ");
        }
        if (!repository.scopeExists(scopeType, scopeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, scopeNotFoundMessage(scopeType));
        }
        if (repository.hasConflictingSuccessor(scopeType, scopeId, effectiveFrom, effectiveTo)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีการกำหนดตารางเวลาทำงานที่มีผลในช่วงเวลาเดียวกันอยู่แล้วสำหรับขอบเขตนี้ "
                    + "กรุณาสิ้นสุดหรือแก้ไขรายการเดิมก่อน");
        }

        List<Integer> closedPredecessorIds = repository.closeOverlappingPredecessors(scopeType, scopeId, effectiveFrom);
        int assignmentId = repository.insertAssignment(scopeType, scopeId, workScheduleId, effectiveFrom, effectiveTo);
        resolver.invalidate();

        WorkScheduleAssignmentDto created = repository.findAssignmentById(assignmentId)
            .orElseThrow(() -> new IllegalStateException("assignment " + assignmentId + " vanished immediately after insert"));
        auditService.record(actor, "CREATE_WORK_SCHEDULE_ASSIGNMENT", "work_schedule_assignment",
            (long) assignmentId, null,
            Map.of("assignment", created, "closedPredecessorAssignmentIds", closedPredecessorIds));
        return created;
    }

    public WorkScheduleAssignmentDto end(UserPrincipal actor, int assignmentId, LocalDate requestedEffectiveTo) {
        AssignmentWindow existing = repository.findWindowById(assignmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการกำหนดตารางเวลาทำงานนี้"));

        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveTo = requestedEffectiveTo != null ? requestedEffectiveTo : today;
        if (effectiveTo.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่สามารถสิ้นสุดรายการย้อนหลังได้ (ต้องไม่ก่อนวันที่ " + today + ")");
        }
        if (effectiveTo.isBefore(existing.effectiveFrom())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มมีผลของรายการเดิม");
        }

        boolean updated = repository.endAssignment(assignmentId, effectiveTo);
        if (!updated) {
            // Deleted between findWindowById and this UPDATE (concurrent request) -- 404, not a phantom success.
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการกำหนดตารางเวลาทำงานนี้");
        }
        resolver.invalidate();

        WorkScheduleAssignmentDto after = repository.findAssignmentById(assignmentId)
            .orElseThrow(() -> new IllegalStateException("assignment " + assignmentId + " vanished immediately after end"));
        auditService.record(actor, "END_WORK_SCHEDULE_ASSIGNMENT", "work_schedule_assignment",
            (long) assignmentId,
            Map.of("effectiveTo", String.valueOf(existing.effectiveTo())),
            Map.of("effectiveTo", String.valueOf(effectiveTo)));
        return after;
    }

    private static String scopeNotFoundMessage(ScopeType type) {
        return switch (type) {
            case EMPLOYEE -> "ไม่พบพนักงานตามรหัสที่ระบุ";
            case DEPARTMENT -> "ไม่พบแผนกตามรหัสที่ระบุ";
            case DIVISION -> "ไม่พบฝ่ายตามรหัสที่ระบุ";
        };
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "app.attendance.schedule.zone is not a valid zone id: " + value, ex);
        }
    }
}
