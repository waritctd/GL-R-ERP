package th.co.glr.hr.specialmoney;

/** One row of the {@code GET /api/special-money/types} catalog the submit form drives off of. */
public record SpecialMoneyTypeOption(
    String requestType,
    String thaiLabel,
    String payrollBucket,
    boolean evidenceRequired) {
}
