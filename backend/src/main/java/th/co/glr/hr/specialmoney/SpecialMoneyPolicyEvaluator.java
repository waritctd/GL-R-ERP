package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure policy-decision function for special-money requests: no repositories, no clock, no I/O.
 * Every input the rules need is passed in by the caller, which is what makes this cheap to unit
 * test exhaustively (see {@code SpecialMoneyPolicyEvaluatorTest}).
 *
 * <p>Never throws for a rule failure -- every violation found is accumulated into {@link
 * PolicyDecision#violations()} rather than short-circuiting, so the caller sees the whole picture.
 */
@Component
public class SpecialMoneyPolicyEvaluator {

    /**
     * Company convention, NOT statute: Thai labour law defines no fixed "probation period". The
     * Labour Protection Act s.118(1) only sets a 120-day *severance-pay* threshold (below 120 days
     * of service, no severance is owed) -- it says nothing about when an employee is "confirmed".
     * This company treats 119 days since hire as "probation passed" absent an explicit confirm
     * date; do not cite this as a legal requirement.
     *
     * <p>NOT the company norm: real production {@code hr.employee.probation_days} is populated for
     * almost every active row and is mostly {@code 90}. This 119-day figure only ever applies to
     * the rare row where {@code probation_days} itself is NULL -- see {@link #hasPassedProbation}.
     *
     * <p>Public (not package-private) so {@code LeaveService}'s §5.2 PERSONAL-leave
     * "passed probation" gate (V116) can reference this exact constant instead of duplicating the
     * literal 119 -- the two eligibility rules resolve "passed probation" identically on purpose,
     * and referencing the same constant is what actually prevents them drifting apart, not just a
     * comment saying they should stay in sync.
     */
    public static final int DEFAULT_PROBATION_DAYS = 119;

    /**
     * Single definition of "has this employee passed probation", shared by {@code LeaveService}'s
     * §5.2 PERSONAL-leave gate and this evaluator's own {@link #evaluateStandardProbationEligibility}
     * -- putting the resolution here (rather than two copies agreeing by convention) is what
     * actually prevents the two rules drifting apart the way {@link #DEFAULT_PROBATION_DAYS} being
     * shared already prevented the fallback constant from drifting.
     *
     * <p>Owner ruling (2026-08-03): HR's recorded {@code hr.employee.confirm_date}, when present, is
     * authoritative -- read it rather than re-deriving probation from {@code hire_date +
     * probation_days}. The employee becomes eligible the day AFTER {@code confirm_date}: verified
     * against production, {@code confirm_date} counts the hire date as day 1, while the computed
     * {@code hire_date + probationDays} form below counts it as day 0, so the two disagree by
     * exactly one day if {@code confirm_date} itself were treated as already-passed. When {@code
     * confirm_date} is NULL, falls back to {@code hire_date + probationDays}, where {@code
     * probationDays} defaults to {@link #DEFAULT_PROBATION_DAYS} only when NULL on the employee row.
     *
     * <p>Fails closed (returns {@code false}) when both {@code confirmDate} and {@code hireDate} are
     * NULL -- the caller must treat that as "cannot verify", never as passed.
     *
     * @param asOf the date probation status is evaluated as of -- the request's start/event date for
     *     leave, or the submission date for special-money aid; not necessarily the clock's "today".
     */
    public static boolean hasPassedProbation(
            LocalDate hireDate, Integer probationDays, LocalDate confirmDate, LocalDate asOf) {
        if (confirmDate != null) {
            return asOf.isAfter(confirmDate);
        }
        if (hireDate == null) {
            return false;
        }
        int days = probationDays != null ? probationDays : DEFAULT_PROBATION_DAYS;
        return !hireDate.plusDays(days).isAfter(asOf);
    }

    static final String SALES_SUPPORT_DEPT_KEY = "sales_support_department_code";
    static final int PREPROBATION_KIT_MIN_TENURE_DAYS = 7;

    private static final Set<String> FUNERAL_ALLOWED_RELATIONS = Set.of("parent", "spouse", "child");

    public PolicyDecision evaluate(
            SpecialMoneyType type,
            SubmitSpecialMoneyRequest request,
            EmployeeEligibilitySnapshot employee,
            UsageSnapshot usage,
            PolicyAmounts amounts,
            Set<String> excludedProvinces) {

        List<String> violations = new ArrayList<>();

        if (!employee.isActive()) {
            violations.add("พนักงานไม่ได้อยู่ในสถานะทำงาน");
        }

        if (type.eligibilityRule() == EligibilityRule.PREPROBATION_SALES_SUPPORT) {
            evaluatePreprobationSalesSupportEligibility(employee, amounts, violations);
        } else {
            evaluateStandardProbationEligibility(employee, violations);
        }

        BigDecimal eligibleAmount =
            switch (type.capRule()) {
                case FIXED_AID -> evaluateFixedAid(type, request, employee, usage, amounts, violations);
                case MEDICAL_ANNUAL -> evaluateMedical(request, employee, usage, amounts, violations);
                case UNIFORM_ANNUAL -> evaluateUniformAnnual(request, usage, amounts, violations);
                case UNIFORM_NEW_STAFF ->
                    evaluateUniformNewStaff(request, employee, usage, amounts, violations);
                case UNIFORM_PREPROBATION_KIT -> evaluateUniformPreprobationKit(request, amounts, violations);
                case PER_DIEM_RATE -> evaluateTravelPerDiem(request, amounts, excludedProvinces, violations);
                case DISCRETIONARY -> request.requestedAmount();
            };

        return new PolicyDecision(eligibleAmount, type.payrollBucket(), amounts.version(), violations);
    }

    // ---------------------------------------------------------------------
    // Eligibility
    // ---------------------------------------------------------------------

    /**
     * The หมายเหตุ's eligibility conditions: (1) เป็นพนักงานประจำ, (2) ผ่านการทดลองงาน.
     *
     * <p><b>Condition (1) is intentionally folded into (2), on the owner's ruling of 2026-08-03:
     * passing probation is treated as sufficient evidence of being พนักงานประจำ.</b> {@code
     * hr.employment_status} exists and is ETL-populated, so a stricter gate is possible later, but
     * it is not applied today. This is a deliberate simplification, recorded here rather than left
     * as a silent gap — if the company starts employing contract staff who pass probation without
     * becoming ประจำ, this method is where that has to change.
     *
     * <p>(3) หัวหน้าฝ่ายอนุมัติ is not a policy rule and is not evaluated here — welfare approval is
     * CEO-only, enforced in {@code SpecialMoneyService}.
     */
    private void evaluateStandardProbationEligibility(EmployeeEligibilitySnapshot employee, List<String> violations) {
        boolean passedProbation = hasPassedProbation(
            employee.hireDate(), employee.probationDays(), employee.confirmDate(), employee.today());
        if (!passedProbation) {
            violations.add("พนักงานยังไม่พ้นทดลองงาน");
        }
    }

    private void evaluatePreprobationSalesSupportEligibility(
            EmployeeEligibilitySnapshot employee, PolicyAmounts amounts, List<String> violations) {
        // hr.department.source_code is a VARCHAR and need not be numeric, so this is compared as a
        // string against special_money_policy.text_value. The seed ships it empty on purpose: the
        // real code is only known in the production database, and the type stays disabled until
        // someone fills it in.
        String configuredCode = amounts.text(SALES_SUPPORT_DEPT_KEY);
        if (configuredCode == null || configuredCode.isBlank()) {
            violations.add(
                "ยังไม่เปิดใช้งาน UNIFORM_PREPROBATION_KIT: ยังไม่ได้ตั้งค่ารหัสแผนกสนับสนุนงานขาย"
                    + " (เป็นค่าตัวอย่างชั่วคราว)");
            return;
        }

        if (!configuredCode.trim().equals(
                employee.departmentSourceCode() == null ? null : employee.departmentSourceCode().trim())) {
            violations.add("พนักงานไม่ได้อยู่ในแผนกสนับสนุนงานขายตามที่ตั้งค่าไว้");
        }

        if (employee.hireDate() == null
            || employee.hireDate().plusDays(PREPROBATION_KIT_MIN_TENURE_DAYS).isAfter(employee.today())) {
            violations.add(
                "พนักงานยังทำงานไม่ครบ " + PREPROBATION_KIT_MIN_TENURE_DAYS + " วันตามที่กำหนด");
        }
    }

    // ---------------------------------------------------------------------
    // Cap rules
    // ---------------------------------------------------------------------

    private BigDecimal evaluateFixedAid(
            SpecialMoneyType type,
            SubmitSpecialMoneyRequest request,
            EmployeeEligibilitySnapshot employee,
            UsageSnapshot usage,
            PolicyAmounts amounts,
            List<String> violations) {
        BigDecimal cap = amounts.amountOrZero("cap");

        // There is deliberately NO claim deadline for aid. A three-months-from-event window used to
        // live here; it was never in the welfare document -- which states a deadline only for
        // ค่ารักษาพยาบาล (2.3, one month from the receipt) -- and it was refusing genuine claims.
        // Removed on the owner's ruling, 2026-08-03. Do not reintroduce one without a written rule.

        if (type == SpecialMoneyType.AID_WEDDING || type == SpecialMoneyType.AID_ORDINATION) {
            if (usage.activeCountLifetime(type) >= 1) {
                violations.add(type.name() + " เบิกได้เพียงครั้งเดียวตลอดการเป็นพนักงาน");
            }
        }

        if (type == SpecialMoneyType.AID_FUNERAL) {
            String relation = request.detailValue("relation");
            if (relation == null || !FUNERAL_ALLOWED_RELATIONS.contains(relation.toLowerCase())) {
                violations.add("ความสัมพันธ์นี้ไม่เข้าเงื่อนไขเงินช่วยเหลืองานศพ (เฉพาะบิดามารดา คู่สมรส หรือบุตรเท่านั้น)");
            }
        }

        return cap;
    }

    private BigDecimal evaluateMedical(
            SubmitSpecialMoneyRequest request,
            EmployeeEligibilitySnapshot employee,
            UsageSnapshot usage,
            PolicyAmounts amounts,
            List<String> violations) {
        BigDecimal cap = amounts.amountOrZero("cap");
        BigDecimal usedThisYear = usage.approvedAmountThisYear(SpecialMoneyType.MEDICAL);
        BigDecimal remaining = cap.subtract(usedThisYear).max(BigDecimal.ZERO);

        if (request.receiptDate() == null) {
            violations.add("คำขอเบิกค่ารักษาพยาบาลต้องระบุวันที่ในใบเสร็จ");
        } else if (employee.today() != null
            && employee.today().isAfter(request.receiptDate().plusMonths(1))) {
            violations.add("ใบเสร็จค่ารักษาพยาบาลมีอายุเกินหนึ่งเดือนแล้ว");
        }

        if (request.requestedAmount() != null && request.requestedAmount().compareTo(remaining) > 0) {
            violations.add("จำนวนที่ขอเบิกเกินวงเงินคงเหลือของปีนี้");
            return remaining;
        }
        return request.requestedAmount();
    }

    /**
     * §2.1.1 — one claim per year, filed in June.
     *
     * <p>The May receipt date applies to the SELF_BUY route ONLY. The document ties May to the
     * self-buy case ("ซื้อ...ในเดือนพฤษภาคม...แล้วนำใบเสร็จมาเบิก...ในเดือนมิถุนายน") and says nothing
     * about when the tailor bills for the ร้านคุณลำพอง route. Requiring May of both used to refuse a
     * perfectly valid tailored claim receipted in June.
     */
    private BigDecimal evaluateUniformAnnual(
            SubmitSpecialMoneyRequest request,
            UsageSnapshot usage,
            PolicyAmounts amounts,
            List<String> violations) {
        boolean selfBuy = "SELF_BUY".equalsIgnoreCase(request.detailValue("uniformMode"));

        if (selfBuy) {
            if (request.receiptDate() == null) {
                violations.add("คำขอเบิกเครื่องแบบประจำปี (ซื้อเอง) ต้องระบุวันที่ในใบเสร็จ");
            } else if (request.receiptDate().getMonth() != Month.MAY) {
                violations.add("ใบเสร็จการซื้อเครื่องแบบเองต้องลงวันที่ในเดือนพฤษภาคม");
            }
        }

        if (request.eventDate() == null) {
            violations.add("คำขอเบิกเครื่องแบบประจำปีต้องระบุวันที่เบิก");
        } else if (request.eventDate().getMonth() != Month.JUNE) {
            violations.add("การเบิกเครื่องแบบประจำปีต้องยื่นภายในเดือนมิถุนายน");
        }

        // "ให้พนักงานเบิกชุดฟอร์มได้ปีละ 1 ครั้ง". The June-only window alone does not enforce this --
        // it leaves the whole of June open for a second claim.
        if (usage.activeCountThisYear(SpecialMoneyType.UNIFORM_ANNUAL) >= 1) {
            violations.add("เบิกเครื่องแบบประจำปีได้ปีละ 1 ครั้งเท่านั้น");
        }

        int maxPieces = amounts.intAmountOrZero("max_pieces");
        int shirtCount = parseIntOrZero(request.detailValue("shirtCount"));
        int trouserCount = parseIntOrZero(request.detailValue("trouserCount"));
        // The 4-piece limit is the document's, not the self-buy route's: "เสื้อหรือกางเกงหรือกระโปรง
        // ก็ได้รวมกัน 4 ชิ้น" is stated for the tailored route and repeated for self-buy. It used to
        // be checked in SELF_BUY only, so a tailored claim for 10 pieces under ฿1,300 passed.
        if (shirtCount + trouserCount > maxPieces) {
            violations.add("เบิกเครื่องแบบประจำปีได้ไม่เกิน " + maxPieces + " ชิ้น");
        }

        if (selfBuy) {
            BigDecimal shirtRate = amounts.amountOrZero("per_piece_shirt");
            BigDecimal trouserRate = amounts.amountOrZero("per_piece_trouser");
            int cappedShirts = Math.min(shirtCount, maxPieces);
            int cappedTrousers = Math.min(trouserCount, Math.max(0, maxPieces - cappedShirts));

            return shirtRate
                .multiply(BigDecimal.valueOf(cappedShirts))
                .add(trouserRate.multiply(BigDecimal.valueOf(cappedTrousers)));
        }

        // Default / TAILORED mode: flat cap against the tailored allowance.
        BigDecimal cap = amounts.amountOrZero("cap");
        if (request.requestedAmount() != null && request.requestedAmount().compareTo(cap) > 0) {
            violations.add("คำขอตัดเครื่องแบบประจำปีเกินวงเงินที่กำหนด");
            return cap;
        }
        return request.requestedAmount();
    }

    /**
     * §2.1.2 — "เริ่มตัดชุดฟอร์มให้ตัดได้ 3 ชุด (หรือ 6 ชิ้น) สำหรับปีแรกที่เข้าทำงาน".
     *
     * <p>Both limits are now enforced. This used to pass the requested amount straight through with
     * no checks at all: the seeded {@code max_pieces = 6} was ignored, and nothing tied the claim to
     * the first year of employment, so it was reachable forever and for any number of pieces.
     *
     * <p>No baht figure is given for new staff anywhere in the document, so the amount stays
     * uncapped rather than borrowing §2.1.1's rates — but it is no longer unguarded either: the CEO
     * must now justify approving more than was requested (see {@code SpecialMoneyService}).
     */
    private BigDecimal evaluateUniformNewStaff(
            SubmitSpecialMoneyRequest request,
            EmployeeEligibilitySnapshot employee,
            UsageSnapshot usage,
            PolicyAmounts amounts,
            List<String> violations) {
        int maxPieces = amounts.intAmountOrZero("max_pieces");
        int pieces = parseIntOrZero(request.detailValue("shirtCount"))
            + parseIntOrZero(request.detailValue("trouserCount"));
        if (pieces > maxPieces) {
            violations.add("ชุดฟอร์มพนักงานใหม่เบิกได้ไม่เกิน " + maxPieces + " ชิ้น");
        }

        LocalDate claimDate = request.eventDate() != null ? request.eventDate() : employee.today();
        if (employee.hireDate() == null) {
            violations.add("ไม่พบวันที่เริ่มงานของพนักงาน จึงตรวจสอบสิทธิ์ชุดฟอร์มพนักงานใหม่ไม่ได้");
        } else if (claimDate != null && claimDate.isAfter(employee.hireDate().plusYears(1))) {
            violations.add("ชุดฟอร์มพนักงานใหม่เบิกได้เฉพาะปีแรกที่เข้าทำงานเท่านั้น");
        }

        if (usage.activeCountLifetime(SpecialMoneyType.UNIFORM_NEW_STAFF) >= 1) {
            violations.add("ชุดฟอร์มพนักงานใหม่เบิกได้เพียงครั้งเดียว");
        }

        return request.requestedAmount();
    }

    /**
     * §2.1.3 — the fixed pre-probation kit: เสื้อยืด ×3, กางเกง ×3, รองเท้า ×1, and สายรัดหลัง ×1.
     *
     * <p>สายรัดหลัง is conditional in the document — "กรณีของเดิมที่มีไม่เพียงพอ" — so it is added only
     * when the request says one is needed. It used to be added unconditionally, which over-stated
     * every kit by the belt's ฿700 whether or not one was issued.
     */
    private BigDecimal evaluateUniformPreprobationKit(
            SubmitSpecialMoneyRequest request, PolicyAmounts amounts, List<String> violations) {
        BigDecimal total =
            amounts
                .amountOrZero("tshirt")
                .multiply(amounts.amountOrZero("tshirt_qty"))
                .add(amounts.amountOrZero("trouser").multiply(amounts.amountOrZero("trouser_qty")))
                .add(amounts.amountOrZero("shoes").multiply(amounts.amountOrZero("shoes_qty")));

        if (Boolean.parseBoolean(request.detailValue("needsBackSupport"))) {
            total = total.add(amounts.amountOrZero("belt").multiply(amounts.amountOrZero("belt_qty")));
        }
        return total;
    }

    private BigDecimal evaluateTravelPerDiem(
            SubmitSpecialMoneyRequest request,
            PolicyAmounts amounts,
            Set<String> excludedProvinces,
            List<String> violations) {
        long days = 1;
        if (request.eventDate() != null && request.eventEndDate() != null) {
            days = ChronoUnit.DAYS.between(request.eventDate(), request.eventEndDate()) + 1;
            if (days < 1) {
                violations.add("วันที่สิ้นสุดเหตุการณ์อยู่ก่อนวันที่เริ่มต้นเหตุการณ์");
                days = 1;
            }
        }

        String destination = request.detailValue("destination");
        boolean overseas = "OVERSEAS".equalsIgnoreCase(destination);

        if (!overseas) {
            String province = request.detailValue("province");
            if (province != null && excludedProvinces.contains(province)) {
                violations.add("จังหวัด " + province + " ไม่เข้าเงื่อนไขเบี้ยเลี้ยง (อยู่ในรายการเดินทางในพื้นที่)");
                return BigDecimal.ZERO;
            }

            String role = request.detailValue("role");
            BigDecimal rate;
            if ("driver".equalsIgnoreCase(role)) {
                rate = amounts.amountOrZero("rate_driver");
            } else if ("loader".equalsIgnoreCase(role)) {
                rate = amounts.amountOrZero("rate_loader");
            } else {
                violations.add("ไม่รองรับตำแหน่งงานสำหรับเบี้ยเลี้ยงเดินทางในประเทศ: " + role);
                rate = BigDecimal.ZERO;
            }
            return rate.multiply(BigDecimal.valueOf(days));
        }

        String region = request.detailValue("region");
        BigDecimal rate =
            "ASIA".equalsIgnoreCase(region) ? amounts.amountOrZero("rate_asia") : amounts.amountOrZero("rate_other");
        return rate.multiply(BigDecimal.valueOf(days));
    }

    private static int parseIntOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
