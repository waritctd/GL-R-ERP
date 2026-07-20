package th.co.glr.hr.specialmoney;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyDetailResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyEmployeeOptionsResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyListResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyTypesResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyUsageResponse;

/** Modelled on {@code th.co.glr.hr.overtime.OvertimeController}: session-resolved principal, no
 * {@code @PreAuthorize} -- every authorization decision lives in {@link SpecialMoneyService}. */
@RestController
@RequestMapping("/api/special-money")
public class SpecialMoneyController {
    private final SpecialMoneyService specialMoneyService;
    private final SessionContext sessions;

    public SpecialMoneyController(SpecialMoneyService specialMoneyService, SessionContext sessions) {
        this.specialMoneyService = specialMoneyService;
        this.sessions = sessions;
    }

    @GetMapping
    SpecialMoneyListResponse list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String requestType,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyListResponse(
            specialMoneyService.list(user, fromDate, toDate, employeeId, status, requestType));
    }

    @PostMapping
    SpecialMoneyDetailResponse submit(@Valid @RequestBody SubmitSpecialMoneyHttpRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(
            specialMoneyService.submit(request.requestType(), request.toDomain(), user));
    }

    @GetMapping("/employees")
    SpecialMoneyEmployeeOptionsResponse employeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyEmployeeOptionsResponse(specialMoneyService.employeeOptions(user));
    }

    @GetMapping("/usage")
    SpecialMoneyUsageResponse usage(
            @RequestParam(value = "employeeId") long employeeId,
            @RequestParam(value = "year", required = false) Integer year,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        int effectiveYear = year != null ? year : Year.now().getValue();
        return new SpecialMoneyUsageResponse(specialMoneyService.usage(employeeId, effectiveYear, user));
    }

    @PostMapping("/{id}/approve")
    SpecialMoneyDetailResponse approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewSpecialMoneyRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(specialMoneyService.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    SpecialMoneyDetailResponse reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewSpecialMoneyRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(specialMoneyService.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    SpecialMoneyDetailResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewSpecialMoneyRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(specialMoneyService.cancel(id, request, user));
    }

    @GetMapping("/types")
    SpecialMoneyTypesResponse types() {
        List<SpecialMoneyTypeOption> options = Arrays.stream(SpecialMoneyType.values())
            .map(type -> new SpecialMoneyTypeOption(
                type.name(), type.thaiLabel(), type.payrollBucket().name(), type.evidenceRequired()))
            .toList();
        return new SpecialMoneyTypesResponse(options);
    }
}
