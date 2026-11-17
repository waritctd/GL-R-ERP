package th.co.glr.hr.payroll;

import java.time.LocalDate;
import java.util.List;

/** Payroll input draft: HR's in-progress, NOT-YET-PROCESSED payroll inputs, persisted so a browser reload
 * restores exactly what was typed, instead of only whatever happens to already be sitting in hr.payroll_line. */
public final class PayrollInputDraftDtos {
    private PayrollInputDraftDtos() {
    }

    public record PayrollInputDraftResponse(LocalDate payrollMonth, List<PayrollEmployeeInputRequest> drafts, String etag) {}
}
