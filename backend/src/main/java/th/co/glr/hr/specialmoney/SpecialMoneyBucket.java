package th.co.glr.hr.specialmoney;

/**
 * Which payroll bucket a {@link SpecialMoneyType} lands in once approved. Mirrors
 * {@code hr.special_money_request.payroll_bucket} / {@code chk_smr_bucket} in
 * {@code V66__special_money_request_schema.sql}.
 *
 * <p>Nothing in this slice wires these buckets into payroll -- that integration is gated on an
 * external sign-off and is out of scope here.
 */
public enum SpecialMoneyBucket {
    PER_DIEM,
    AID,
    NON_TAXABLE
}
