package th.co.glr.hr.attendance;

import java.util.List;
import th.co.glr.hr.attendance.daily.UnmappedBadge;

record AttendanceUnmappedResponse(List<UnmappedBadge> badges) {
}
