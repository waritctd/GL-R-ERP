package th.co.glr.hr.specialmoney;

/** Which payroll bucket a SpecialMoneyType lands in once approved. Mirrors
 * hr.special_money_request.payroll_bucket / chk_smr_bucket in V66__special_money_request_schema.sql. */
public enum SpecialMoneyBucket {
    PER_DIEM,
    AID,
    NON_TAXABLE
}
