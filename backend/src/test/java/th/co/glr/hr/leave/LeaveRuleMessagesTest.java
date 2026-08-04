package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase A0a (structured rejection outcome): plain-unit coverage for {@link LeaveRuleMessages}, no
 * Postgres needed -- pure string-template rendering. Proves every one of the 17 {@link
 * LeaveRuleCode} values renders a complete Thai sentence (every placeholder substituted, none left
 * as a stray {@code {key}}), that the codes documented to carry the leave type's own Thai name
 * actually do, and that a genuinely missing param fails LOUDLY (an {@link IllegalArgumentException}
 * naming both the code and the key) rather than silently reaching an employee with a broken
 * message.
 */
class LeaveRuleMessagesTest {

    // The exact param sets LeaveService's call sites populate for each code -- see
    // LeaveRuleMessages#render's own switch and LeaveService#autoRejectNote's call sites. Kept here,
    // independently, rather than reflecting LeaveRuleMessages's internals, so this test would
    // actually fail if a future edit silently dropped a required substitution.
    private static final Map<LeaveRuleCode, Map<String, String>> FULL_PARAMS = fullParamsForEveryCode();

    // Every LeaveRuleCode whose template embeds the (possibly paired) leave type's own Thai name --
    // see LeaveRuleMessages#render.
    private static final Set<LeaveRuleCode> CODES_WITH_LEAVE_TYPE_NAME = EnumSet.of(
        LeaveRuleCode.ONCE_PER_EMPLOYMENT,
        LeaveRuleCode.RESIGNATION_GATE,
        LeaveRuleCode.HIRE_DATE_MISSING_PRORATED,
        LeaveRuleCode.HIRE_DATE_MISSING_MIN_SERVICE,
        LeaveRuleCode.MIN_SERVICE_MONTHS,
        LeaveRuleCode.PROBATION_HIRE_DATE_MISSING,
        LeaveRuleCode.PROBATION_NOT_PASSED,
        LeaveRuleCode.FIRST_YEAR_MAX_DAYS,
        LeaveRuleCode.MAX_CONSECUTIVE_DAYS,
        LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR
    );

    @Test
    void everyLeaveRuleCodeRendersACompleteMessageWithNoStrayPlaceholder() {
        for (LeaveRuleCode code : LeaveRuleCode.values()) {
            Map<String, String> params = FULL_PARAMS.get(code);
            assertThat(params).as("FULL_PARAMS must cover %s", code).isNotNull();

            String rendered = LeaveRuleMessages.render(code, params);

            assertThat(rendered).as("%s message", code).isNotBlank();
            assertThat(rendered).as("%s message must have every placeholder substituted", code)
                .doesNotContain("{");
        }
    }

    @Test
    void everyCodeThatCarriesTheLeaveTypeNameRendersItInThai() {
        for (LeaveRuleCode code : CODES_WITH_LEAVE_TYPE_NAME) {
            String rendered = LeaveRuleMessages.render(code, FULL_PARAMS.get(code));
            assertThat(rendered).as("%s message should contain the Thai leave type name", code)
                .contains("ลากิจ");
        }
    }

    // Every seeded hr.leave_type.name_th (V13, unchanged by every rule migration since). Each is a
    // COMPLETE noun phrase that already carries the verb "ลา" -- see LeaveRuleMessages's class
    // Javadoc.
    private static final Set<String> SEEDED_LEAVE_TYPE_NAMES_TH = Set.of(
        "ลาป่วย", "ลากิจ", "ลาพักร้อน", "ลาคลอดบุตร", "ลารับราชการทหาร", "ลาอุปสมบท", "ลาไม่รับค่าจ้าง");

    /**
     * Regression pin: a template that writes {@code "การลา{leaveTypeNameTh}"} renders the doubled,
     * ungrammatical "การลาลาป่วย", because every seeded {@code name_th} ALREADY begins with "ลา".
     * This shipped in the first cut of this class and the
     * {@link #everyCodeThatCarriesTheLeaveTypeNameRendersItInThai} assertion above did NOT catch it
     * -- {@code contains("ลากิจ")} is satisfied just as happily by "ลาลากิจ". Asserting the absence
     * of the doubling is what actually distinguishes the two, so it is asserted here directly,
     * against EVERY seeded name rather than the single one the other tests reuse.
     */
    @Test
    void noMessageDoublesTheLeadingLaOfALeaveTypeName() {
        for (LeaveRuleCode code : CODES_WITH_LEAVE_TYPE_NAME) {
            for (String nameTh : SEEDED_LEAVE_TYPE_NAMES_TH) {
                Map<String, String> params = new java.util.HashMap<>(FULL_PARAMS.get(code));
                params.put("leaveTypeNameTh", nameTh);
                if (params.containsKey("pairedTypeNameTh")) {
                    params.put("pairedTypeNameTh", nameTh);
                }

                String rendered = LeaveRuleMessages.render(code, params);

                assertThat(rendered)
                    .as("%s must not prefix '%s' with a second ลา", code, nameTh)
                    .doesNotContain("ลาลา");
                assertThat(rendered).as("%s must still name the leave type", code).contains(nameTh);
            }
        }
    }

    @Test
    void contiguousLeavePairRendersBothTheOwnAndPairedThaiNames() {
        String rendered = LeaveRuleMessages.render(LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR, Map.of(
            "leaveTypeNameTh", "ลากิจ",
            "pairedTypeNameTh", "ลาพักร้อน"));

        assertThat(rendered).contains("ลากิจ").contains("ลาพักร้อน");
    }

    @Test
    void aMissingRequiredParamThrowsNamingTheCodeAndTheKey() {
        // MIN_SERVICE_MONTHS requires both leaveTypeNameTh and minServiceMonths -- omit the second.
        Map<String, String> incomplete = Map.of("leaveTypeNameTh", "ลาพักร้อน");

        assertThatThrownBy(() -> LeaveRuleMessages.render(LeaveRuleCode.MIN_SERVICE_MONTHS, incomplete))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MIN_SERVICE_MONTHS")
            .hasMessageContaining("minServiceMonths");
    }

    @Test
    void aCompletelyEmptyParamsMapThrowsForACodeThatRequiresParams() {
        assertThatThrownBy(() -> LeaveRuleMessages.render(LeaveRuleCode.DEPARTMENT_COVERAGE, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DEPARTMENT_COVERAGE")
            .hasMessageContaining("uncoveredDate");
    }

    @Test
    void aCodeWithNoRequiredParamsRendersFineWithAnEmptyMap() {
        // SICK_CERTIFICATE_REQUIRED is the one code whose message embeds no substitution at all.
        String rendered = LeaveRuleMessages.render(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED, Map.of());
        assertThat(rendered).isNotBlank();
        assertThat(rendered).doesNotContain("{");
    }

    // ─────────────────────────────────────────────────────────────────────
    // LeaveRuleOutcome.of coverage
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void ofResolvesMessageThAndNormalizesANullParamsMapToEmpty() {
        LeaveRuleOutcome outcome = LeaveRuleOutcome.of(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED, null);

        assertThat(outcome.code()).isEqualTo(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED);
        assertThat(outcome.params()).isEmpty();
        assertThat(outcome.messageTh()).isNotBlank();
    }

    @Test
    void ofExposesAnImmutableParamsMap() {
        LeaveRuleOutcome outcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE, Map.of("noticeDays", "3"));

        assertThatThrownBy(() -> outcome.params().put("noticeDays", "99"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofPropagatesTheMissingParamFailureFromRender() {
        assertThatThrownBy(() -> LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ADVANCE_NOTICE")
            .hasMessageContaining("noticeDays");
    }

    private static Map<LeaveRuleCode, Map<String, String>> fullParamsForEveryCode() {
        Map<LeaveRuleCode, Map<String, String>> byCode = new EnumMap<>(LeaveRuleCode.class);
        byCode.put(LeaveRuleCode.ONCE_PER_EMPLOYMENT, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.RESIGNATION_GATE, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.HIRE_DATE_MISSING_PRORATED, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.HIRE_DATE_MISSING_MIN_SERVICE, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.MIN_SERVICE_MONTHS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "minServiceMonths", "12"));
        byCode.put(LeaveRuleCode.PROBATION_HIRE_DATE_MISSING, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.PROBATION_NOT_PASSED, Map.of(
            "leaveTypeNameTh", "ลากิจ", "probationEndsOn", "2026-08-10"));
        byCode.put(LeaveRuleCode.FIRST_YEAR_MAX_DAYS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "effectiveCap", "3"));
        byCode.put(LeaveRuleCode.WEDDING_MAX_DAYS, Map.of("maxDays", "3"));
        byCode.put(LeaveRuleCode.MAX_CONSECUTIVE_DAYS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "maxConsecutiveDays", "3"));
        byCode.put(LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR, Map.of(
            "leaveTypeNameTh", "ลากิจ", "pairedTypeNameTh", "ลาพักร้อน"));
        byCode.put(LeaveRuleCode.SICK_CERTIFICATE_WINDOW, Map.of(
            "certificateWindowDays", "3", "certificateDeadline", "2026-06-04"));
        byCode.put(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED, Map.of());
        byCode.put(LeaveRuleCode.SICK_NO_CERT_TOLERANCE_EXHAUSTED, Map.of("tolerance", "3"));
        byCode.put(LeaveRuleCode.EMERGENCY_TOLERANCE_EXHAUSTED, Map.of("allowance", "3"));
        byCode.put(LeaveRuleCode.ADVANCE_NOTICE, Map.of("noticeDays", "1"));
        byCode.put(LeaveRuleCode.DEPARTMENT_COVERAGE, Map.of("uncoveredDate", "2026-07-13"));
        return byCode;
    }
}
