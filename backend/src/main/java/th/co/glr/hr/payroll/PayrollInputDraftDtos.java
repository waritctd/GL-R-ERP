package th.co.glr.hr.payroll;

import java.time.LocalDate;
import java.util.List;

/**
 * Payroll input draft (2026-07-30): HR's in-progress, NOT-YET-PROCESSED payroll inputs, persisted
 * so a browser reload (or a same-account login from a different machine) restores exactly what
 * was typed, instead of only whatever happens to already be sitting in {@code hr.payroll_line}
 * (which for a fresh month is nothing at all). See {@code hr.payroll_input_draft} (V104),
 * {@link PayrollRepository#findInputDrafts}/{@link PayrollRepository#saveInputDrafts}, and
 * {@link PayrollService#getInputDraft}/{@link PayrollService#saveInputDraft}.
 *
 * <p>Reuses {@link PayrollEmployeeInputRequest} verbatim as the per-employee row shape for both
 * GET (read back) and PUT (save) -- it is already the exact set of per-run fields this page's
 * form edits, and is already what {@code /preview}/{@code /process} accept. The tax-allowance
 * fields on that record (spouseAllowance..politicalDonation, parentCareCount) are never part of a
 * draft -- they are the standing declaration edited via {@code /api/payroll/tax-allowances} -- and
 * always read back {@code null} here.
 *
 * <p>This table has NO bearing on payroll math: it is never read by {@link PayrollCalculator} or
 * by {@link PayrollService#preview}/{@link PayrollService#process}. It exists purely so the form
 * survives a reload before a month is processed; the explicit value HR submits to preview/process
 * is always what actually gets calculated and stored, exactly as before this change.
 */
public final class PayrollInputDraftDtos {
    private PayrollInputDraftDtos() {
    }

    public record PayrollInputDraftResponse(LocalDate payrollMonth, List<PayrollEmployeeInputRequest> drafts) {}
}
