package th.co.glr.hr.payroll;

public final class PayrollResponses {
    private PayrollResponses() {
    }

    public record PayrollPeriodResponse(PayrollPeriodDto period) {}
}
