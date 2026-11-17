package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.attendance.schedule.HolidayRepository.HolidayRow;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/** Manual HR/CEO CRUD over hr.holiday — the write path this branch adds so HR no longer needs a developer to
 * add next year's holidays or a one-off company day. Every write is audited via auditService.record(...) with
 * entity = "holiday". */
@Service
public class HolidayAdminService {

    /** See the class javadoc for why 500 and not something closer to the longest real name (137). */
    static final int MAX_NAME_LENGTH = 500;

    private final HolidayRepository repository;
    private final AuditService auditService;

    public HolidayAdminService(HolidayRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<HolidayDto> list(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่เริ่มต้นและวันที่สิ้นสุด");
        }
        if (to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น");
        }
        return repository.listBetween(from, to).stream().map(HolidayDto::from).toList();
    }

    public HolidayDto create(UserPrincipal actor, LocalDate holidayDate, String nameTh) {
        if (holidayDate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่วันหยุด");
        }
        String trimmedName = requireValidName(nameTh);
        if (repository.existsForDate(holidayDate)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีข้อมูลวันหยุดสำหรับวันที่ " + holidayDate + " อยู่แล้ว กรุณาแก้ไขรายการเดิมแทนการสร้างใหม่");
        }

        repository.createManual(holidayDate, trimmedName);
        HolidayDto created = new HolidayDto(holidayDate, trimmedName, "COMPANY");
        auditService.record(actor, "CREATE_HOLIDAY", "holiday", holidayDate.toEpochDay(), null, created);
        return created;
    }

    public HolidayDto update(UserPrincipal actor, LocalDate holidayDate, String nameTh) {
        if (holidayDate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่วันหยุด");
        }
        String trimmedName = requireValidName(nameTh);
        HolidayRow before = repository.findByDate(holidayDate)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลวันหยุดสำหรับวันที่ " + holidayDate));

        boolean updated = repository.updateManual(holidayDate, trimmedName);
        if (!updated) {
            // Deleted between the findByDate above and this UPDATE (concurrent request) — 404 rather than reporting a
            // phantom success.
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลวันหยุดสำหรับวันที่ " + holidayDate);
        }
        HolidayDto after = new HolidayDto(holidayDate, trimmedName, "COMPANY");
        auditService.record(actor, "UPDATE_HOLIDAY", "holiday", holidayDate.toEpochDay(), HolidayDto.from(before), after);
        return after;
    }

    public void delete(UserPrincipal actor, LocalDate holidayDate) {
        if (holidayDate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่วันหยุด");
        }
        HolidayRow before = repository.findByDate(holidayDate)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลวันหยุดสำหรับวันที่ " + holidayDate));

        boolean deleted = repository.delete(holidayDate);
        if (!deleted) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลวันหยุดสำหรับวันที่ " + holidayDate);
        }
        auditService.record(actor, "DELETE_HOLIDAY", "holiday", holidayDate.toEpochDay(), HolidayDto.from(before), null);
    }

    /** Trims value, rejects blank (400, existing behaviour), and rejects anything over #MAX_NAME_LENGTH characters
     * (400) — the guard V129 removed at the database layer. Length is checked against the trimmed value, so
     * surrounding whitespace never counts against the limit or masks an otherwise-valid name. */
    private static String requireValidName(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุชื่อวันหยุด");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ชื่อวันหยุดยาวเกินไป (สูงสุด " + MAX_NAME_LENGTH + " ตัวอักษร)");
        }
        return trimmed;
    }
}
