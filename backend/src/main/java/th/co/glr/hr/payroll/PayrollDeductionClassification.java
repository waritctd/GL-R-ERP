package th.co.glr.hr.payroll;

import java.util.EnumMap;
import java.util.Map;

/**
 * The classification table issue #376 asks for: every {@link PayrollDeductionKind} the engine
 * supports, its {@link DeductionRuleClass} (who directs it), its {@link DeductionCapGroup} (which
 * limit applies), and the cited legal basis for both -- assigned independently so a deduction's
 * source of authority and its applicable limit are never conflated (the mistake #373 originally
 * made: assuming ม.76's cap governed กยศ. because both "sound statutory").
 *
 * <p><b>Anything uncertain is left {@code null} with a mandatory {@link Entry#needsReviewReason()}
 * rather than guessed at</b> -- the issue's own instruction: "Do not encode a limit without a cited
 * source ... anything uncertain stays unenforced with a needs review marker rather than being
 * guessed at." {@link #classify} never returns a partially-populated {@link Entry} silently; every
 * {@code null} field is paired with a reason a reviewer can read.
 *
 * <p><b>Nothing here enforces anything.</b> This is a read-only lookup table. In particular, {@link
 * DeductionCapGroup#LABOUR_ACT_SECTION_76} is assigned to {@link PayrollDeductionKind#WARNING_LETTER}
 * candidates in spirit only -- see that entry's own {@code needsReviewReason} -- and {@link
 * PayrollCalculator} applies NO ม.76 arithmetic anywhere. Only {@link
 * DeductionCapGroup#LED_GARNISHMENT} corresponds to a cap the engine actually enforces today (via
 * {@link PayrollGarnishmentType}), and this classification does not duplicate that machinery, only
 * names it.
 *
 * <p><b>{@link DeductionRuleClass#VOLUNTARY} has no assignee today.</b> GL&R runs no provident fund
 * (removed 2026-07-29, see {@code PayrollCalculator#retirementAllowance}'s own comment) and has no
 * other dedicated voluntary-deduction column -- a genuinely voluntary deduction, if one exists,
 * would currently be typed into {@link PayrollDeductionKind#OTHER_PRETAX}/{@link
 * PayrollDeductionKind#OTHER_POST_TAX}, which is exactly why those two stay wholly unclassified
 * below rather than being assigned {@code INTERNAL_RECOVERY} by default.
 */
public final class PayrollDeductionClassification {

    /**
     * @param ruleClass       who directs the deduction, or {@code null} if unconfirmed (see {@code needsReviewReason}).
     * @param capGroup        which statutory limit applies, or {@code null} if unconfirmed.
     * @param legalBasis      the cited statute/section this classification rests on. Always present, even for a
     *                        partially-{@code null} entry, so a reviewer knows what IS settled.
     * @param needsReviewReason the open question blocking a null field, quoting the issue's own open question where
     *                        applicable. {@code null} only when both {@code ruleClass} and {@code capGroup} are set.
     */
    public record Entry(DeductionRuleClass ruleClass, DeductionCapGroup capGroup, String legalBasis, String needsReviewReason) {
        public Entry {
            if (legalBasis == null || legalBasis.isBlank()) {
                throw new IllegalStateException("Every classification entry must cite a legal basis");
            }
            boolean incomplete = ruleClass == null || capGroup == null;
            if (incomplete && (needsReviewReason == null || needsReviewReason.isBlank())) {
                throw new IllegalStateException(
                    "A null ruleClass/capGroup must carry a needsReviewReason -- see issue #376: "
                        + "\"anything uncertain stays unenforced with a needs review marker rather than being guessed at\"");
            }
        }

        /** True once BOTH ruleClass and capGroup are confirmed -- nothing left to review. */
        public boolean fullyClassified() {
            return ruleClass != null && capGroup != null;
        }
    }

    private static final Map<PayrollDeductionKind, Entry> REGISTRY = new EnumMap<>(PayrollDeductionKind.class);

    static {
        REGISTRY.put(PayrollDeductionKind.WITHHOLDING_TAX, new Entry(
            DeductionRuleClass.STATUTORY_AUTHORITY_DIRECTED,
            DeductionCapGroup.NONE,
            "ประมวลรัษฎากร (income tax withholding), ป.96/2543 -- ม.76 item (1) territory "
                + "(\"ภาษีเงินได้ ... หรือชำระเงินอื่นตามที่มีกฎหมายบัญญัติไว้\"), which item (1) carves out "
                + "from the 10%/20% cap that binds only items (2)-(5).",
            null));

        REGISTRY.put(PayrollDeductionKind.SOCIAL_SECURITY, new Entry(
            DeductionRuleClass.STATUTORY_AUTHORITY_DIRECTED,
            DeductionCapGroup.NONE,
            "พระราชบัญญัติประกันสังคม พ.ศ. 2533 มาตรา 47 -- the employer deducts and remits the "
                + "employee's own 5% contribution. Item (1) territory, same reasoning as withholding tax: "
                + "a payment required by a law OTHER than the Labour Protection Act.",
            null));

        REGISTRY.put(PayrollDeductionKind.STUDENT_LOAN, new Entry(
            DeductionRuleClass.STATUTORY_AUTHORITY_DIRECTED,
            DeductionCapGroup.NONE,
            "พระราชบัญญัติกองทุนเงินให้กู้ยืมเพื่อการศึกษา พ.ศ. 2560 มาตรา 51 -- the employer, on receiving "
                + "the fund's notification, is obliged to deduct and remit the amount the fund itself "
                + "specifies; the employer neither sets the amount nor may vary it "
                + "(\"นายจ้างมีหน้าที่นำส่งเท่านั้น ไม่สามารถกำหนดจำนวนเงินหักได้เอง\"). "
                + "CORRECTED per issue #373/#376: ม.76's 10%/20% cap binds ONLY items (2)-(5) "
                + "(union fees / cooperative debts / employee-caused damages / savings funds); "
                + "กยศ. is compelled under ม.51 of separate legislation, item (1) territory, and must "
                + "NOT carry LABOUR_ACT_SECTION_76.",
            null));

        REGISTRY.put(PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT, new Entry(
            DeductionRuleClass.COURT_OR_LED_ORDER,
            DeductionCapGroup.LED_GARNISHMENT,
            "ประมวลกฎหมายวิธีพิจารณาความแพ่ง (ป.วิ.พ.) มาตรา 302 -- ALREADY ENFORCED via "
                + "PayrollGarnishmentType / PayrollCalculator#garnishmentDeduction (SALARY 30% / "
                + "BONUS 50% / OVERTIME_OR_DILIGENCE 30% / SEVERANCE floor ฿300,000, SALARY floor "
                + "net >= ฿20,000). This classification reuses that machinery; it does not add a "
                + "second, competing implementation (issue #376's explicit instruction).",
            null));

        REGISTRY.put(PayrollDeductionKind.WARNING_LETTER, new Entry(
            DeductionRuleClass.INTERNAL_RECOVERY,
            null,
            "The employer recovering its own money (a documented incident under a formal warning), "
                + "not remitting to a third party -- INTERNAL_RECOVERY is confident. But ม.76 item (3) "
                + "(ค่าเสียหายจากการกระทำผิดของลูกจ้าง) MAY require the 10%/20% cap AND written consent "
                + "if the underlying incident is genuinely employee-caused damage -- this field is a "
                + "free-typed HR catch-all with no sub-typing recorded, so which rows are actually "
                + "\"ค่าเสียหาย\" under item (3) is unconfirmed.",
            "Open question (issue #376's own indicative table): \"หักตามใบเตือน / ลูกค้าคืนสินค้า -> "
                + "INTERNAL_RECOVERY, cap_group needs review.\" Left unenforced pending confirmation "
                + "of which recorded instances are ม.76(3) damages versus a different internal-recovery reason."));

        REGISTRY.put(PayrollDeductionKind.CUSTOMER_RETURN, new Entry(
            DeductionRuleClass.INTERNAL_RECOVERY,
            null,
            "Recovering an unearned/reversed commission after a customer return -- the employer's own "
                + "money, not a third-party remittance, so INTERNAL_RECOVERY is confident. Whether "
                + "ม.76(3)'s cap applies is doubtful (no employee fault is alleged -- this is a "
                + "commission reversal, not damages), but not confirmed either way.",
            "Open question (issue #376's own indicative table, same row as WARNING_LETTER): "
                + "cap_group needs review before any ม.76 arithmetic could apply."));

        REGISTRY.put(PayrollDeductionKind.OTHER_PRETAX, new Entry(
            null,
            null,
            "Free-typed HR catch-all (หักอื่นๆ ก่อนภาษี) with NO sub-typing recorded anywhere in this "
                + "system -- it could be a voluntary deduction, an internal recovery, a savings-fund "
                + "contribution, or something else entirely.",
            "Classifying either dimension without knowing what HR actually types into this field "
                + "would be guessing. Per issue #376: \"anything uncertain stays unenforced with a "
                + "needs review marker rather than being guessed at.\" Left fully unclassified."));

        REGISTRY.put(PayrollDeductionKind.OTHER_POST_TAX, new Entry(
            null,
            null,
            "Free-typed HR catch-all (หักอื่นๆ หลังภาษี), the post-tax analogue of OTHER_PRETAX -- "
                + "same reasoning, same absence of sub-typing.",
            "Same open question as OTHER_PRETAX. Left fully unclassified rather than guessed at."));
    }

    private PayrollDeductionClassification() {
    }

    public static Entry classify(PayrollDeductionKind kind) {
        Entry entry = REGISTRY.get(kind);
        if (entry == null) {
            throw new IllegalStateException("No classification entry recorded for " + kind
                + " -- every PayrollDeductionKind must be classified (or explicitly marked needs-review), see issue #376");
        }
        return entry;
    }

    public static Map<PayrollDeductionKind, Entry> all() {
        return Map.copyOf(REGISTRY);
    }
}
