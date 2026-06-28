package th.co.glr.hr.attendance;

import java.util.List;

public record AttendancePunchesResponse(
    List<AttendancePunchDto> punches
) {
}
