package th.co.glr.hr.attendance;

import java.util.List;

public record AttendanceDevicesResponse(
    List<AttendanceDeviceDto> devices
) {
}
