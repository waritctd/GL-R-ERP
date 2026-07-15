package th.co.glr.hr.payroll;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationEmailService;

@Service
public class PayslipDistributionService {
    private static final Logger log = LoggerFactory.getLogger(PayslipDistributionService.class);
    private static final Set<String> DISTRIBUTE_ROLES = Set.of("hr");

    private final PayrollRepository payrollRepository;
    private final PayslipRenderer payslipRenderer;
    private final NotificationEmailService emailService;
    private final AuditService auditService;

    public PayslipDistributionService(
        PayrollRepository payrollRepository,
        PayslipRenderer payslipRenderer,
        NotificationEmailService emailService,
        AuditService auditService
    ) {
        this.payrollRepository = payrollRepository;
        this.payslipRenderer = payslipRenderer;
        this.emailService = emailService;
        this.auditService = auditService;
    }

    public PayslipDistributionResponse queueDistribution(long periodId, UserPrincipal actor) {
        requireRole(actor);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll period not found"));
        Set<Long> sentLineIds = payrollRepository.findSentPayslipLineIds(periodId);
        int totalLines = (int) period.lines().stream().filter(line -> line.id() != null).count();
        int alreadySent = (int) period.lines().stream()
            .filter(line -> line.id() != null && sentLineIds.contains(line.id()))
            .count();
        PayslipDistributionResponse response = new PayslipDistributionResponse(
            periodId,
            totalLines,
            alreadySent,
            totalLines - alreadySent
        );
        auditService.record(actor, "DISTRIBUTE_PAYSLIPS", "payroll_period", periodId, null, response);
        return response;
    }

    @Async
    public void sendPayslips(long periodId, UserPrincipal actor) {
        try {
            requireRole(actor);
            PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll period not found"));
            Set<Long> sentLineIds = payrollRepository.findSentPayslipLineIds(periodId);
            Map<Long, String> emails = payrollRepository.findEmployeeEmailsByIds(period.lines().stream()
                .map(PayrollLineDto::employeeId)
                .collect(Collectors.toSet()));

            for (PayrollLineDto line : period.lines()) {
                sendOne(period, line, sentLineIds, emails);
            }
        } catch (Exception exception) {
            log.error("Payslip distribution failed before completing: periodId={} actorId={} error={}",
                periodId, actor == null ? null : actor.id(), exception.getMessage());
        }
    }

    private void sendOne(PayrollPeriodDto period, PayrollLineDto line, Set<Long> sentLineIds, Map<Long, String> emails) {
        if (line.id() == null || sentLineIds.contains(line.id())) {
            return;
        }
        String to = emails.get(line.employeeId());
        if (to == null || to.isBlank()) {
            payrollRepository.markPayslipEmailFailed(period.id(), line, null, "Employee has no email address");
            log.info("Payslip email skipped: periodId={} lineId={} employeeId={} reason=no_email",
                period.id(), line.id(), line.employeeId());
            return;
        }
        if (!payrollRepository.markPayslipEmailPending(period.id(), line, to)) {
            return;
        }
        try {
            byte[] pdf = payslipRenderer.toPdf(line, period);
            String filename = "glr-payslip-" + period.payrollMonth() + "-" + safeFileToken(line.employeeCode()) + ".pdf";
            emailService.sendWithAttachment(to, subject(period), body(line, period), filename, pdf);
            payrollRepository.markPayslipEmailSent(period.id(), line, to);
            log.info("Payslip email sent: periodId={} lineId={} employeeId={} to={}",
                period.id(), line.id(), line.employeeId(), to);
        } catch (Exception exception) {
            payrollRepository.markPayslipEmailFailed(period.id(), line, to, exception.getMessage());
            log.error("Payslip email failed: periodId={} lineId={} employeeId={} to={} error={}",
                period.id(), line.id(), line.employeeId(), to, exception.getMessage());
        }
    }

    private void requireRole(UserPrincipal actor) {
        if (actor == null || !DISTRIBUTE_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private String subject(PayrollPeriodDto period) {
        return "[GL&R HR] Payslip " + period.payrollMonth();
    }

    private String body(PayrollLineDto line, PayrollPeriodDto period) {
        return """
            เรียน คุณ%s,

            แนบสลิปเงินเดือนรอบ %s สำหรับตรวจสอบ

            ขอแสดงความนับถือ
            ระบบบริหารงานบุคคล GL&R (GL&R HR Portal)
            """.formatted(line.employeeName(), period.payrollMonth());
    }

    private String safeFileToken(String value) {
        if (value == null || value.isBlank()) {
            return "employee";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
