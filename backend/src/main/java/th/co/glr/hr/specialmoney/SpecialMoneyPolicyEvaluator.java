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
     * <p>Public (not package-private) so {@code LeaveService}'s §5.2 PERSONAL-leave
     * "passed probation" gate (V116) can reference this exact constant instead of duplicating the
     * literal 119 -- the two eligibility rules resolve "passed probation" identically on purpose,
     * and referencing the same constant is what actually prevents them drifting apart, not just a
     * comment saying they should stay in sync.
     */
    public static final int DEFAULT_PROBATION_DAYS = 119;

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
                case UNIFORM_ANNUAL -> evaluateUniformAnnual(request, amounts, violations);
                case UNIFORM_NEW_STAFF ->
                    // No per-piece rate shape was specified for this type beyond the max_pieces=6
                    // seed -- see class Javadoc / handoff for this gap. Passed through uncapped
                    // rather than inventing a rate.
                    request.requestedAmount();
                case UNIFORM_PREPROBATION_KIT -> evaluateUniformPreprobationKit(request, amounts, violations);
                case PER_DIEM_RATE -> evaluateTravelPerDiem(request, amounts, excludedProvinces, violations);
                case DISCRETIONARY -> request.requestedAmount();
            };

        return new PolicyDecision(eligibleAmount, type.payrollBucket(), amounts.version(), violations);
    }

    // ---------------------------------------------------------------------
    // Eligibility
    // ---------------------------------------------------------------------

    private void evaluateStandardProbationEligibility(EmployeeEligibilitySnapshot employee, List<String> violations) {
        boolean passedProbation;
        if (employee.confirmDate() != null) {
            passedProbation = !employee.confirmDate().isAfter(employee.today());
        } else {
            int probationDays =
                employee.probationDays() != null ? employee.probationDays() : DEFAULT_PROBATION_DAYS;
            passedProbation =
                employee.hireDate() != null
                    && !employee.hireDate().plusDays(probationDays).isAfter(employee.today());
        }
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

        // NOT in the source welfare-policy document -- three months from event_date is an
        // assumption pending confirmation. See ClaimWindow.THREE_MONTHS_FROM_EVENT.
        if (request.eventDate() != null && employee.today() != null) {
            LocalDate windowEnd = request.eventDate().plusMonths(3);
            if (employee.today().isAfter(windowEnd)) {
                violations.add("เลยกำหนดระยะเวลาเบิก (3 เดือนนับจากวันที่เกิดเหตุ) แล้ว");
            }
        }

        if (type == SpecialMoneyType.AID_WEDDING || type == SpecialMoneyType.AID_ORDINATION) {
            if (usage.approvedCountLifetime(type) >= 1) {
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

    private BigDecimal evaluateUniformAnnual(
            SubmitSpecialMoneyRequest request, PolicyAmounts amounts, List<String> violations) {
        if (request.receiptDate() == null) {
            violations.add("คำขอเบิกเครื่องแบบประจำปีต้องระบุวันที่ในใบเสร็จ");
        } else if (request.receiptDate().getMonth() != Month.MAY) {
            violations.add("ใบเสร็จเครื่องแบบประจำปีต้องลงวันที่ในเดือนพฤษภาคม");
        }

        if (request.eventDate() == null) {
            violations.add("คำขอเบิกเครื่องแบบประจำปีต้องระบุวันที่เบิก");
        } else if (request.eventDate().getMonth() != Month.JUNE) {
            violations.add("การเบิกเครื่องแบบประจำปีต้องยื่นภายในเดือนมิถุนายน");
        }

        String mode = request.detailValue("uniformMode");
        if ("SELF_BUY".equalsIgnoreCase(mode)) {
            int maxPieces = amounts.intAmountOrZero("max_pieces");
            BigDecimal shirtRate = amounts.amountOrZero("per_piece_shirt");
            BigDecimal trouserRate = amounts.amountOrZero("per_piece_trouser");
            int shirtCount = parseIntOrZero(request.detailValue("shirtCount"));
            int trouserCount = parseIntOrZero(request.detailValue("trouserCount"));

            if (shirtCount + trouserCount > maxPieces) {
                violations.add("การซื้อเครื่องแบบเองเกินจำนวนสูงสุด " + maxPieces + " ชิ้น");
            }
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

    private BigDecimal evaluateUniformPreprobationKit(
            SubmitSpecialMoneyRequest request, PolicyAmounts amounts, List<String> violations) {
        BigDecimal total =
            amounts
                .amountOrZero("tshirt")
                .multiply(amounts.amountOrZero("tshirt_qty"))
                .add(amounts.amountOrZero("trouser").multiply(amounts.amountOrZero("trouser_qty")))
                .add(amounts.amountOrZero("shoes").multiply(amounts.amountOrZero("shoes_qty")))
                .add(amounts.amountOrZero("belt").multiply(amounts.amountOrZero("belt_qty")));
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
