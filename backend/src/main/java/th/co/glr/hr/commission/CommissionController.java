package th.co.glr.hr.commission;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO')")
    public CommissionListResponse list(@RequestParam(required = false) String payrollMonth, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionListResponse(commissionService.list(parseMonth(payrollMonth), user));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO')")
    public CommissionDetailResponse submit(@Valid @RequestBody SubmitCommissionRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.submit(request, user));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO')")
    public CommissionDetailResponse submitMultipart(
        @RequestParam(value = "sourceTicketId", required = false) Long sourceTicketId,
        @RequestParam(value = "salesRepId", required = false) Long salesRepId,
        @RequestParam("invoiceNumber") String invoiceNumber,
        @RequestParam("invoiceDate") LocalDate invoiceDate,
        @RequestParam("grossAmount") java.math.BigDecimal grossAmount,
        @RequestParam(value = "bankFees", required = false) java.math.BigDecimal bankFees,
        @RequestParam(value = "suspenseVat", required = false) java.math.BigDecimal suspenseVat,
        @RequestParam(value = "transportFee", required = false) java.math.BigDecimal transportFee,
        @RequestParam(value = "cutFee", required = false) java.math.BigDecimal cutFee,
        @RequestParam(value = "shortfall", required = false) java.math.BigDecimal shortfall,
        @RequestParam("invoiceAttachment") MultipartFile invoiceAttachment,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            sourceTicketId,
            salesRepId,
            invoiceNumber,
            invoiceDate,
            grossAmount,
            bankFees,
            suspenseVat,
            transportFee,
            cutFee,
            shortfall
        );
        return new CommissionDetailResponse(commissionService.submit(request, invoiceAttachment, user));
    }

    @PatchMapping("/{id}/deductions")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO')")
    public CommissionDetailResponse updateDeductions(
        @PathVariable long id,
        @Valid @RequestBody UpdateCommissionDeductionsRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.updateDeductions(id, request, user));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO')")
    public CommissionDetailResponse approve(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.approve(id, user));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO')")
    public CommissionDetailResponse reject(
        @PathVariable long id,
        @Valid @RequestBody(required = false) ReviewCommissionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.reject(id, request, user));
    }

    @PostMapping("/{id}/clawback")
    @PreAuthorize("hasAnyRole('SALES_MANAGER','CEO')")
    public CommissionDetailResponse clawback(
        @PathVariable long id,
        @Valid @RequestBody CreateClawbackRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionDetailResponse(commissionService.createClawback(id, request, user));
    }

    @PostMapping("/simulator")
    @PreAuthorize("hasAnyRole('SALES','SALES_MANAGER','CEO')")
    public CommissionSimulationResponse simulator(
        @Valid @RequestBody CommissionSimulatorRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new CommissionSimulationResponse(commissionService.simulate(request, user));
    }

    @GetMapping("/payroll-ready")
    @PreAuthorize("hasAnyRole('HR')")
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
