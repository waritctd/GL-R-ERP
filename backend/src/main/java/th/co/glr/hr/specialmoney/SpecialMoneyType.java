package th.co.glr.hr.specialmoney;

/**
 * The welfare/special-money request types ("สวัสดิการ", policy signed 2018). This enum owns the
 * RULE SHAPES -- bucket, display label, whether evidence is required, and which cap/claim-window
 * /eligibility rule identity applies. The actual amounts are effective-dated data in
 * {@code hr.special_money_policy} (see {@link PolicyAmounts}), not here.
 *
 * <p>Mirrors {@code chk_smr_type} in {@code V66__special_money_request_schema.sql} -- keep both in
 * sync if a type is added or removed.
 */
public enum SpecialMoneyType {
    UNIFORM_ANNUAL(
        SpecialMoneyBucket.NON_TAXABLE,
        "ชุดฟอร์มประจำปี",
        true,
        CapRule.UNIFORM_ANNUAL,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    UNIFORM_NEW_STAFF(
        SpecialMoneyBucket.NON_TAXABLE,
        "ชุดฟอร์มพนักงานใหม่",
        true,
        CapRule.UNIFORM_NEW_STAFF,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    UNIFORM_PREPROBATION_KIT(
        SpecialMoneyBucket.NON_TAXABLE,
        "ชุดพนักงานก่อนผ่านทดลองงาน",
        true,
        CapRule.UNIFORM_PREPROBATION_KIT,
        ClaimWindow.NONE,
        EligibilityRule.PREPROBATION_SALES_SUPPORT),
    TRAVEL_PER_DIEM(
        SpecialMoneyBucket.PER_DIEM,
        "เบี้ยเลี้ยงเดินทาง",
        false,
        CapRule.PER_DIEM_RATE,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    TRAVEL_LODGING(
        SpecialMoneyBucket.PER_DIEM,
        "ค่าที่พัก",
        true,
        CapRule.DISCRETIONARY,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    MEDICAL(
        SpecialMoneyBucket.NON_TAXABLE,
        "ค่ารักษาพยาบาล",
        true,
        CapRule.MEDICAL_ANNUAL,
        ClaimWindow.ONE_MONTH_FROM_RECEIPT,
        EligibilityRule.STANDARD),
    AID_WEDDING(
        SpecialMoneyBucket.AID,
        "เงินช่วยเหลืองานแต่งงาน",
        true,
        CapRule.FIXED_AID,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    AID_ORDINATION(
        SpecialMoneyBucket.AID,
        "เงินช่วยเหลืองานบวช",
        true,
        CapRule.FIXED_AID,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    AID_CHILDBIRTH(
        SpecialMoneyBucket.AID,
        "เงินช่วยเหลือคลอดบุตร",
        true,
        CapRule.FIXED_AID,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    AID_FUNERAL(
        SpecialMoneyBucket.AID,
        "เงินช่วยเหลืองานศพ",
        true,
        CapRule.FIXED_AID,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    TRAINING(
        SpecialMoneyBucket.NON_TAXABLE,
        "สนับสนุนการฝึกอบรม",
        true,
        CapRule.DISCRETIONARY,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD),
    OTHER(
        SpecialMoneyBucket.AID,
        "อื่นๆ",
        true,
        CapRule.DISCRETIONARY,
        ClaimWindow.NONE,
        EligibilityRule.STANDARD);

    private final SpecialMoneyBucket payrollBucket;
    private final String thaiLabel;
    private final boolean evidenceRequired;
    private final CapRule capRule;
    private final ClaimWindow claimWindow;
    private final EligibilityRule eligibilityRule;

    SpecialMoneyType(
            SpecialMoneyBucket payrollBucket,
            String thaiLabel,
            boolean evidenceRequired,
            CapRule capRule,
            ClaimWindow claimWindow,
            EligibilityRule eligibilityRule) {
        this.payrollBucket = payrollBucket;
        this.thaiLabel = thaiLabel;
        this.evidenceRequired = evidenceRequired;
        this.capRule = capRule;
        this.claimWindow = claimWindow;
        this.eligibilityRule = eligibilityRule;
    }

    public SpecialMoneyBucket payrollBucket() {
        return payrollBucket;
    }

    public String thaiLabel() {
        return thaiLabel;
    }

    public boolean evidenceRequired() {
        return evidenceRequired;
    }

    public CapRule capRule() {
        return capRule;
    }

    public ClaimWindow claimWindow() {
        return claimWindow;
    }

    public EligibilityRule eligibilityRule() {
        return eligibilityRule;
    }

    /**
     * Resolves the stored {@code request_type} string (e.g. {@code "TRAVEL_PER_DIEM"}, written by
     * {@code SpecialMoneyRepository#create} as {@code type.name()}) to its Thai label (e.g.
     * {@code "เบี้ยเลี้ยงเดินทาง"}), for notification-email copy that must read as Thai, not as a raw
     * enum constant.
     *
     * <p>Falls back to the raw value for an unknown/legacy code rather than throwing -- unlike {@link
     * #valueOf(String)}, which throws {@link IllegalArgumentException} on anything it does not
     * recognise. A notification is a side effect of an already-committed business action (see {@code
     * SpecialMoneyService}'s submit/approve/reject/cancel, all of which call this only AFTER their own
     * write succeeded); a bad or since-retired {@code request_type} value must not turn a successful
     * submission into a failed one just because the email could not be worded perfectly.
     */
    public static String thaiLabelOf(String storedName) {
        if (storedName == null) {
            return null;
        }
        try {
            return SpecialMoneyType.valueOf(storedName).thaiLabel();
        } catch (IllegalArgumentException exception) {
            return storedName;
        }
    }
}
