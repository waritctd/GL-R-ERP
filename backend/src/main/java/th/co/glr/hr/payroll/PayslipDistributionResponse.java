package th.co.glr.hr.payroll;

public record PayslipDistributionResponse(
    long periodId,
    int totalLines,
    int alreadySent,
    int queued
) {}
