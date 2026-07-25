package th.co.glr.hr.leave;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.leave.LeaveResponses.LeaveBalancesResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveContactDefaultsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveDetailResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveEmployeeOptionsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveListResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveTypesResponse;

@RestController
@RequestMapping("/api/leave")
public class LeaveController {
    private final LeaveService leaveService;
    private final SessionContext sessions;

    public LeaveController(LeaveService leaveService, SessionContext sessions) {
        this.leaveService = leaveService;
        this.sessions = sessions;
    }

    @GetMapping
    LeaveListResponse list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "status", required = false) String status,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveListResponse(leaveService.list(user, fromDate, toDate, employeeId, status));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    LeaveDetailResponse submit(@Valid @RequestBody SubmitLeaveRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.submit(request, user));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LeaveDetailResponse submitMultipart(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam("leaveTypeCode") String leaveTypeCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam("reason") String reason,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @RequestParam(value = "contactHouseNo", required = false) String contactHouseNo,
            @RequestParam(value = "contactSubdistrict", required = false) String contactSubdistrict,
            @RequestParam(value = "contactDistrict", required = false) String contactDistrict,
            @RequestParam(value = "contactProvince", required = false) String contactProvince,
            @RequestParam(value = "contactPhone", required = false) String contactPhone,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, leaveTypeCode, startDate, endDate, reason,
            startTime, endTime, contactHouseNo, contactSubdistrict, contactDistrict, contactProvince, contactPhone);
        return new LeaveDetailResponse(leaveService.submit(request, attachment, user));
    }

    @GetMapping("/employees")
    LeaveEmployeeOptionsResponse employeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveEmployeeOptionsResponse(leaveService.employeeOptions(user));
    }

    @GetMapping("/types")
    LeaveTypesResponse leaveTypes(HttpSession session) {
        sessions.requireUser(session);
        return new LeaveTypesResponse(leaveService.leaveTypes());
    }

    // Paper-form (ใบลาหยุด F-HR-020) autofill for the contact-during-leave block, plus read-only
    // position/department/division -- reuses the /balances access predicate (own record, or HR/CEO,
    // or the employee's direct manager). See LeaveService#contactDefaults.
    @GetMapping("/contact-defaults")
    LeaveContactDefaultsResponse contactDefaults(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveContactDefaultsResponse(leaveService.contactDefaults(user, employeeId));
    }

    @GetMapping("/balances")
    LeaveBalancesResponse balances(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "year", required = false) Integer year,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveBalancesResponse(leaveService.balances(user, employeeId, year));
    }

    @PostMapping("/{id}/approve")
    LeaveDetailResponse approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    LeaveDetailResponse reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    LeaveDetailResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.cancel(id, request, user));
    }
}
