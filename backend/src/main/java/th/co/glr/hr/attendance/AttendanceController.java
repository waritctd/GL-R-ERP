package th.co.glr.hr.attendance;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {
    static final String AGENT_TOKEN_HEADER = "X-GLR-Agent-Token";

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/punch")
    ResponseEntity<AttendancePunchResponse> receivePunch(
            @Valid @RequestBody AttendancePunchRequest request,
            @RequestHeader(value = AGENT_TOKEN_HEADER, required = false) String agentToken) {
        return ResponseEntity.ok(attendanceService.receivePunch(request, agentToken));
    }
}
