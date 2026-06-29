package th.co.glr.hr.commission;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionResponses.CommissionDetailResponse;
import th.co.glr.hr.commission.CommissionResponses.CommissionListResponse;
import th.co.glr.hr.commission.CommissionResponses.CommissionSimulationResponse;
import th.co.glr.hr.commission.CommissionResponses.PayrollSummaryResponse;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api/commissions")
public class CommissionController {
    private final CommissionService commissionService;
    private final SessionContext sessions;

    public CommissionController(CommissionService commissionService, SessionContext sessions) {
        this.commissionService = commissionService;
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO','ADMIN')")
    public CommissionListResponse list(@RequestParam(required = false) String payrollMonth, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionListResponse(commissionService.list(parseMonth(payrollMonth), user));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO','ADMIN')")
    public CommissionDetailResponse submit(@Valid @RequestBody SubmitCommissionRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.submit(request, user));
    }

    @PatchMapping("/{id}/deductions")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO','ADMIN')")
    public CommissionDetailResponse updateDeductions(
        @PathVariable long id,
        @Valid @RequestBody UpdateCommissionDeductionsRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.updateDeductions(id, request, user));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO','ADMIN')")
    public CommissionDetailResponse approve(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.approve(id, user));
    }

    @PostMapping("/{id}/clawback")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO','ADMIN')")
    public CommissionDetailResponse clawback(
        @PathVariable long id,
        @Valid @RequestBody CreateClawbackRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.createClawback(id, request, user));
    }

    @PostMapping("/simulator")
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO','ADMIN')")
    public CommissionSimulationResponse simulator(
        @Valid @RequestBody CommissionSimulatorRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionSimulationResponse(commissionService.simulate(request, user));
    }

    @GetMapping("/payroll-ready")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public PayrollSummaryResponse payrollReady(@RequestParam(required = false) String payrollMonth, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PayrollSummaryResponse(commissionService.payrollReadySummary(parseMonth(payrollMonth), user));
    }

    private LocalDate parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.length() == 7) {
                return YearMonth.parse(trimmed).atDay(1);
            }
            return LocalDate.parse(trimmed).withDayOfMonth(1);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid payroll month");
        }
    }
}
