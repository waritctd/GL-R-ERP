package th.co.glr.hr.attendance;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * The CEO/HR stand-up roster for one date: everyone in {@code employeeIds} is marked present with
 * no punches (WFH, §76 reporting only). Resubmitting for the same date reconciles the roster —
 * leaving someone off removes an earlier mark — see
 * {@code AttendanceDailyService#setWfhRoster}. An empty/omitted {@code employeeIds} clears the
 * whole roster for that date rather than being rejected, since "nobody attended" is a legitimate
 * answer.
 */
record AttendanceMarkPresentRequest(
    @JsonProperty("work_date")
    @NotNull
    LocalDate workDate,

    @JsonProperty("employee_ids")
    List<Long> employeeIds,

    @JsonProperty("notes")
    String notes
) {
    AttendanceMarkPresentRequest {
        if (employeeIds == null) {
            employeeIds = List.of();
        }
    }
}
