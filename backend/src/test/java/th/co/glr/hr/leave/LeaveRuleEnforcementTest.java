package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * V164 (owner-approved change, 2026-09-09): plain-unit coverage for the pieces of the WARN_UNPAID_*
 * redesign that do not need a real database or a mocked {@code LeaveRepository} -- the {@link
 * LeaveRuleCode#enforcement()} mapping itself, {@link LeaveService#isBlocking}'s BLOCK/WARN dispatch,
 * and {@link LeaveService#dominantUnpaidByRuleDays}'s dominance rule. {@code isBlocking}/{@code
 * dominantUnpaidByRuleDays} are package-private specifically so this class can exercise them in
 * isolation from the rest of {@link LeaveService#autoRejectNote}'s branches -- see
 * {@code LeaveRelationalRulesIntegrationTest}/a real-DB quota-non-consumption test for the end-to-end
 * proof that {@link LeaveService#submit} actually wires these into a SUBMITTED row correctly.
 */
class LeaveRuleEnforcementTest {

    // ─────────────────────────────────────────────────────────────────────
    // LeaveRuleCode#enforcement() mapping -- pinned against the task brief's explicit code lists, not
    // against LeaveRuleCode's own source, so this test would actually fail if a future edit silently
    // moved a code between buckets.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void tenCodesAreWarnUnpaidAllOrExcessAndSevenStayBlock() {
        List<LeaveRuleCode> warnUnpaidAll = List.of(
            LeaveRuleCode.ADVANCE_NOTICE,
            LeaveRuleCode.EMERGENCY_TOLERANCE_EXHAUSTED,
            LeaveRuleCode.SICK_CERTIFICATE_REQUIRED,
            LeaveRuleCode.SICK_NO_CERT_TOLERANCE_EXHAUSTED,
            LeaveRuleCode.SICK_CERTIFICATE_WINDOW,
            LeaveRuleCode.PROBATION_NOT_PASSED,
            LeaveRuleCode.MIN_SERVICE_MONTHS);
        List<LeaveRuleCode> warnUnpaidExcess = List.of(
            LeaveRuleCode.WEDDING_MAX_DAYS,
            LeaveRuleCode.FIRST_YEAR_MAX_DAYS,
            LeaveRuleCode.MAX_CONSECUTIVE_DAYS);
        List<LeaveRuleCode> staysBlocking = List.of(
            LeaveRuleCode.ONCE_PER_EMPLOYMENT,
            LeaveRuleCode.RESIGNATION_GATE,
            LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR,
            LeaveRuleCode.HIRE_DATE_MISSING_PRORATED,
            LeaveRuleCode.HIRE_DATE_MISSING_MIN_SERVICE,
            LeaveRuleCode.PROBATION_HIRE_DATE_MISSING,
            LeaveRuleCode.DEPARTMENT_COVERAGE);

        for (LeaveRuleCode code : warnUnpaidAll) {
            assertThat(code.enforcement()).as("%s", code).isEqualTo(LeaveRuleEnforcement.WARN_UNPAID_ALL);
        }
        for (LeaveRuleCode code : warnUnpaidExcess) {
            assertThat(code.enforcement()).as("%s", code).isEqualTo(LeaveRuleEnforcement.WARN_UNPAID_EXCESS);
        }
        for (LeaveRuleCode code : staysBlocking) {
            assertThat(code.enforcement()).as("%s", code).isEqualTo(LeaveRuleEnforcement.BLOCK);
        }

        // Exhaustiveness: the three lists above must partition EVERY LeaveRuleCode with no overlap
        // and no gap -- catches a code added later that nobody classified either way.
        assertThat(warnUnpaidAll.size() + warnUnpaidExcess.size() + staysBlocking.size())
            .isEqualTo(LeaveRuleCode.values().length);
        List<LeaveRuleCode> all = new ArrayList<>();
        all.addAll(warnUnpaidAll);
        all.addAll(warnUnpaidExcess);
        all.addAll(staysBlocking);
        assertThat(all).containsExactlyInAnyOrder(LeaveRuleCode.values());
    }

    // ─────────────────────────────────────────────────────────────────────
    // LeaveService#isBlocking dispatch
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void isBlockingReturnsTrueForABlockCodeAndDoesNotTouchWarnings() {
        LeaveRuleOutcome blockOutcome = LeaveRuleOutcome.of(LeaveRuleCode.DEPARTMENT_COVERAGE,
            Map.of("uncoveredDate", "2026-07-13"));
        List<LeaveRuleOutcome> warnings = new ArrayList<>();

        boolean blocking = LeaveService.isBlocking(blockOutcome, warnings);

        assertThat(blocking).isTrue();
        assertThat(warnings).isEmpty();
    }

    @Test
    void isBlockingReturnsFalseForAWarnCodeAndAppendsItToWarnings() {
        LeaveRuleOutcome warnOutcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE,
            Map.of("leaveTypeNameTh", "ลากิจ", "noticeDays", "3", "days", "1.00"));
        List<LeaveRuleOutcome> warnings = new ArrayList<>();

        boolean blocking = LeaveService.isBlocking(warnOutcome, warnings);

        assertThat(blocking).isFalse();
        assertThat(warnings).containsExactly(warnOutcome);
    }

    @Test
    void isBlockingReturnsFalseAndDoesNothingForANullOutcome() {
        List<LeaveRuleOutcome> warnings = new ArrayList<>();

        boolean blocking = LeaveService.isBlocking(null, warnings);

        assertThat(blocking).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    void isBlockingAccumulatesMultipleWarnCodesAcrossRepeatedCalls() {
        LeaveRuleOutcome adviceOutcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE,
            Map.of("leaveTypeNameTh", "ลากิจ", "noticeDays", "3", "days", "2.00"));
        LeaveRuleOutcome weddingOutcome = LeaveRuleOutcome.of(LeaveRuleCode.WEDDING_MAX_DAYS,
            Map.of("maxDays", "3", "totalDays", "4.00", "excessDays", "1.00"));
        List<LeaveRuleOutcome> warnings = new ArrayList<>();

        boolean firstBlocking = LeaveService.isBlocking(adviceOutcome, warnings);
        boolean secondBlocking = LeaveService.isBlocking(weddingOutcome, warnings);

        assertThat(firstBlocking).isFalse();
        assertThat(secondBlocking).isFalse();
        assertThat(warnings).containsExactly(adviceOutcome, weddingOutcome);
    }

    // ─────────────────────────────────────────────────────────────────────
    // LeaveService#dominantUnpaidByRuleDays -- owner ruling #3, "never sum an ALL with an EXCESS".
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void dominanceIsZeroWhenNoWarningsFired() {
        BigDecimal result = LeaveService.dominantUnpaidByRuleDays(
            List.of(), BigDecimal.ZERO, new BigDecimal("5.00"));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void dominanceIsTotalDaysWhenOneWarnUnpaidAllFired() {
        LeaveRuleOutcome allOutcome = LeaveRuleOutcome.of(LeaveRuleCode.MIN_SERVICE_MONTHS, Map.of(
            "leaveTypeNameTh", "ลาพักร้อน", "minServiceMonths", "12", "days", "3.00"));

        BigDecimal result = LeaveService.dominantUnpaidByRuleDays(
            List.of(allOutcome), BigDecimal.ZERO, new BigDecimal("3.00"));

        assertThat(result).isEqualByComparingTo("3.00");
    }

    @Test
    void dominanceIsTheMaxExcessNotTheSumWhenOnlyExcessCodesFired() {
        LeaveRuleOutcome weddingOutcome = LeaveRuleOutcome.of(LeaveRuleCode.WEDDING_MAX_DAYS,
            Map.of("maxDays", "3", "totalDays", "4.00", "excessDays", "1.00"));
        LeaveRuleOutcome firstYearOutcome = LeaveRuleOutcome.of(LeaveRuleCode.FIRST_YEAR_MAX_DAYS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "effectiveCap", "3", "excessDays", "2.50"));

        // maxExcessDays (2.50) is passed in as ALREADY the max the caller computed across its own
        // EXCESS check sites -- dominantUnpaidByRuleDays itself does not recompute per-warning
        // amounts (see its Javadoc), so this proves it returns exactly that max, not 1.00 + 2.50.
        BigDecimal result = LeaveService.dominantUnpaidByRuleDays(
            List.of(weddingOutcome, firstYearOutcome), new BigDecimal("2.50"), new BigDecimal("5.00"));

        assertThat(result).isEqualByComparingTo("2.50");
    }

    @Test
    void dominanceIsTotalDaysNeverSummedWithExcessWhenBothAnAllAndAnExcessFired() {
        // Owner ruling #3, verbatim: "Never sum an ALL with an EXCESS." An ALL always wins outright,
        // regardless of how large -- or small -- the accumulated EXCESS amount is.
        LeaveRuleOutcome allOutcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE,
            Map.of("leaveTypeNameTh", "ลากิจ", "noticeDays", "3", "days", "5.00"));
        LeaveRuleOutcome excessOutcome = LeaveRuleOutcome.of(LeaveRuleCode.WEDDING_MAX_DAYS,
            Map.of("maxDays", "3", "totalDays", "4.00", "excessDays", "1.00"));

        BigDecimal result = LeaveService.dominantUnpaidByRuleDays(
            List.of(allOutcome, excessOutcome), new BigDecimal("1.00"), new BigDecimal("5.00"));

        // NOT 6.00 (5.00 + 1.00) -- exactly totalDays.
        assertThat(result).isEqualByComparingTo("5.00");
    }
}
