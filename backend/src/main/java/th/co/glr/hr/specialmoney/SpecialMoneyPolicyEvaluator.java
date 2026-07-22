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
     */
    static final int DEFAULT_PROBATION_DAYS = 119;

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
            violations.add("employee is not active");
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
            violations.add("employee has not completed probation");
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
                "UNIFORM_PREPROBATION_KIT is not yet enabled: sales-support department code is not"
                    + " configured (placeholder seed value)");
            return;
        }

        if (!configuredCode.trim().equals(
                employee.departmentSourceCode() == null ? null : employee.departmentSourceCode().trim())) {
            violations.add("employee is not in the configured sales-support department");
        }

        if (employee.hireDate() == null
            || employee.hireDate().plusDays(PREPROBATION_KIT_MIN_TENURE_DAYS).isAfter(employee.today())) {
            violations.add(
                "employee has not reached the minimum " + PREPROBATION_KIT_MIN_TENURE_DAYS + " days of tenure");
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
                violations.add("claim window (3 months from event date) has expired");
            }
        }

        if (type == SpecialMoneyType.AID_WEDDING || type == SpecialMoneyType.AID_ORDINATION) {
            if (usage.approvedCountLifetime(type) >= 1) {
                violations.add(type.name() + " may only be claimed once per lifetime");
            }
        }

        if (type == SpecialMoneyType.AID_FUNERAL) {
            String relation = request.detailValue("relation");
            if (relation == null || !FUNERAL_ALLOWED_RELATIONS.contains(relation.toLowerCase())) {
                violations.add("relation is not eligible for funeral aid (parent, spouse, or child only)");
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
            violations.add("medical requests require a receipt date");
        } else if (employee.today() != null
            && employee.today().isAfter(request.receiptDate().plusMonths(1))) {
            violations.add("medical receipt is older than one month");
        }

        if (request.requestedAmount() != null && request.requestedAmount().compareTo(remaining) > 0) {
            violations.add("medical request exceeds the remaining annual balance");
            return remaining;
        }
        return request.requestedAmount();
    }

    private BigDecimal evaluateUniformAnnual(
            SubmitSpecialMoneyRequest request, PolicyAmounts amounts, List<String> violations) {
        if (request.receiptDate() == null) {
            violations.add("uniform annual requests require a receipt date");
        } else if (request.receiptDate().getMonth() != Month.MAY) {
            violations.add("uniform annual receipt must be dated in May");
        }

        if (request.eventDate() == null) {
            violations.add("uniform annual requests require an event/claim date");
        } else if (request.eventDate().getMonth() != Month.JUNE) {
            violations.add("uniform annual claim must be submitted in June");
        }

        String mode = request.detailValue("uniformMode");
        if ("SELF_BUY".equalsIgnoreCase(mode)) {
            int maxPieces = amounts.intAmountOrZero("max_pieces");
            BigDecimal shirtRate = amounts.amountOrZero("per_piece_shirt");
            BigDecimal trouserRate = amounts.amountOrZero("per_piece_trouser");
            int shirtCount = parseIntOrZero(request.detailValue("shirtCount"));
            int trouserCount = parseIntOrZero(request.detailValue("trouserCount"));

            if (shirtCount + trouserCount > maxPieces) {
                violations.add("uniform self-buy exceeds the maximum of " + maxPieces + " pieces");
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
            violations.add("uniform annual tailored request exceeds the cap");
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
                violations.add("event_end_date is before event_date");
                days = 1;
            }
        }

        String destination = request.detailValue("destination");
        boolean overseas = "OVERSEAS".equalsIgnoreCase(destination);

        if (!overseas) {
            String province = request.detailValue("province");
            if (province != null && excludedProvinces.contains(province)) {
                violations.add("per-diem does not apply to excluded (local commuting) province: " + province);
                return BigDecimal.ZERO;
            }

            String role = request.detailValue("role");
            BigDecimal rate;
            if ("driver".equalsIgnoreCase(role)) {
                rate = amounts.amountOrZero("rate_driver");
            } else if ("loader".equalsIgnoreCase(role)) {
                rate = amounts.amountOrZero("rate_loader");
            } else {
                violations.add("unknown per-diem role for domestic travel: " + role);
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
