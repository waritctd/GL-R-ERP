package th.co.glr.hr.payroll;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.payroll.PayrollResponses.PayrollPeriodResponse;

@RestController
@RequestMapping("/api/payroll")
public class PayrollController {
    private final PayrollService payrollService;
    private final SessionContext sessions;

    public PayrollController(PayrollService payrollService, SessionContext sessions) {
        this.payrollService = payrollService;
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public PayrollPeriodResponse currentOrPreview(@RequestParam String payrollMonth, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PayrollPeriodResponse(payrollService.currentOrPreview(parseMonth(payrollMonth), user));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public PayrollPeriodResponse preview(@Valid @RequestBody ProcessPayrollRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PayrollPeriodResponse(payrollService.preview(normalizedRequest(request), user));
    }

    @PostMapping("/process")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public PayrollPeriodResponse process(@Valid @RequestBody ProcessPayrollRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PayrollPeriodResponse(payrollService.process(normalizedRequest(request), user));
    }

    @GetMapping("/{periodId}/bank-export")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<String> bankExport(@PathVariable long periodId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        String body = payrollService.bankExport(periodId, user);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("glr-payroll-" + periodId + ".txt")
                .build()
                .toString())
            .contentType(MediaType.TEXT_PLAIN)
            .body(body);
    }

    private ProcessPayrollRequest normalizedRequest(ProcessPayrollRequest request) {
        return new ProcessPayrollRequest(request.payrollMonth().withDayOfMonth(1), request.inputs());
    }

    private LocalDate parseMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "payrollMonth is required");
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
