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
        LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR,
        // V164 final copy (2026-09-09): ADVANCE_NOTICE's template now also opens with
        // "การ{leaveTypeNameTh}..." -- it did not carry the leave type's name at all before.
        LeaveRuleCode.ADVANCE_NOTICE
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

    /**
     * Owner ruling (final copy, 2026-09-09): no em/en dash anywhere in this class's rendered Thai
     * prose -- an em/en dash reads machine-generated. Checked against EVERY code's FULLY populated
     * render, not just the WARN_UNPAID_* ones, so a dash introduced anywhere in this class (not only
     * in the templates this phase touched) would fail this test.
     */
    @Test
    void noRenderedMessageContainsAnEmOrEnDash() {
        for (LeaveRuleCode code : LeaveRuleCode.values()) {
            String rendered = LeaveRuleMessages.render(code, FULL_PARAMS.get(code));

            assertThat(rendered).as("%s message must not contain an EN DASH", code).doesNotContain("–");
            assertThat(rendered).as("%s message must not contain an EM DASH", code).doesNotContain("—");
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
    void aCodeWithOnlyTheAutoInjectedSectionParamRendersFineWithJustDays() {
        // V164: SICK_CERTIFICATE_REQUIRED used to be the one code whose message embedded NO
        // substitution at all -- it now needs "days" (the WARN_UNPAID_TAIL figure), same as every
        // other WARN_UNPAID_* code. "section" (its other new placeholder) is auto-injected by
        // #render itself (see that method's Javadoc) -- a caller never supplies it, which is what
        // this test actually pins: an empty map is NOT enough any more (days is genuinely required),
        // but a map that supplies ONLY "days" (nothing for "section") still renders fine.
        String rendered = LeaveRuleMessages.render(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED, Map.of("days", "1.00"));
        assertThat(rendered).isNotBlank();
        assertThat(rendered).doesNotContain("{");
    }

    // ─────────────────────────────────────────────────────────────────────
    // LeaveRuleOutcome.of coverage
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void ofNormalizesANullParamsMapInsteadOfNpeingOnMapCopyOf() {
        // V164: every LeaveRuleCode requires at least one real param now (there is no longer a
        // zero-param code -- see #aCodeWithOnlyTheAutoInjectedSectionParamRendersFineWithJustDays'
        // comment), so `of` can no longer render SUCCESSFULLY from a null params map alone. This test
        // now proves the narrower, but still real, thing it always meant to: `of` null-checks BEFORE
        // calling Map.copyOf (which throws its own unhelpful NullPointerException on a null argument)
        // -- a null params map must still reach #render's OWN actionable IllegalArgumentException
        // (naming the missing key), never a raw NullPointerException from Map.copyOf itself.
        //
        // V164 final copy (2026-09-09): ADVANCE_NOTICE's template now ALSO needs "leaveTypeNameTh",
        // listed before "noticeDays" in its #format call -- so against a null/empty map, "#format"
        // reports THAT key first, not "noticeDays" any more. This test's actual point (a null map
        // reaches render's own IllegalArgumentException, never a raw NPE) is unaffected by which key
        // happens to be first; only the asserted key name changed.
        assertThatThrownBy(() -> LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ADVANCE_NOTICE")
            .hasMessageContaining("leaveTypeNameTh");
    }

    @Test
    void ofResolvesMessageThAndParamsForAFullyPopulatedCode() {
        // Companion to the null-normalization test above -- proves `of` still returns a genuinely
        // well-formed outcome (code/params/messageTh all populated) once every required param IS
        // supplied, non-null.
        LeaveRuleOutcome outcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE, Map.of(
            "leaveTypeNameTh", "ลากิจ", "noticeDays", "3", "days", "1.00"));

        assertThat(outcome.code()).isEqualTo(LeaveRuleCode.ADVANCE_NOTICE);
        assertThat(outcome.params()).containsEntry("noticeDays", "3").containsEntry("days", "1.00");
        assertThat(outcome.messageTh()).isNotBlank();
    }

    @Test
    void ofExposesAnImmutableParamsMap() {
        LeaveRuleOutcome outcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE, Map.of(
            "leaveTypeNameTh", "ลากิจ", "noticeDays", "3", "days", "1.00"));

        assertThatThrownBy(() -> outcome.params().put("noticeDays", "99"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofPropagatesTheMissingParamFailureFromRender() {
        // V164 final copy: "leaveTypeNameTh" is the first key ADVANCE_NOTICE's template now requires
        // -- see #ofNormalizesANullParamsMapInsteadOfNpeingOnMapCopyOf's comment above.
        assertThatThrownBy(() -> LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ADVANCE_NOTICE")
            .hasMessageContaining("leaveTypeNameTh");
    }

    private static Map<LeaveRuleCode, Map<String, String>> fullParamsForEveryCode() {
        Map<LeaveRuleCode, Map<String, String>> byCode = new EnumMap<>(LeaveRuleCode.class);
        byCode.put(LeaveRuleCode.ONCE_PER_EMPLOYMENT, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.RESIGNATION_GATE, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.HIRE_DATE_MISSING_PRORATED, Map.of("leaveTypeNameTh", "ลากิจ"));
        byCode.put(LeaveRuleCode.HIRE_DATE_MISSING_MIN_SERVICE, Map.of("leaveTypeNameTh", "ลากิจ"));
        // V164 (owner-approved change, 2026-09-09): every WARN_UNPAID_ALL/WARN_UNPAID_EXCESS code
        // below also needs "days" (the WARN_UNPAID_TAIL figure) -- and an EXCESS code additionally
        // needs "excessDays" -- see LeaveRuleMessages' WARN_UNPAID_TAIL block comment. "section" is
        // NOT listed here for any code -- #render auto-injects it (see that method's own Javadoc).
        byCode.put(LeaveRuleCode.MIN_SERVICE_MONTHS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "minServiceMonths", "12", "days", "1.00"));
        byCode.put(LeaveRuleCode.PROBATION_HIRE_DATE_MISSING, Map.of("leaveTypeNameTh", "ลากิจ"));
        // probationEndsOn/certificateDeadline below are pre-formatted the way LeaveService's real
        // call sites now format them -- ThaiText.date(...), e.g. "10 สิงหาคม 2569" -- NEVER a raw
        // LocalDate.toString() ISO string. See LeaveRuleMessages's class Javadoc (final copy rules).
        byCode.put(LeaveRuleCode.PROBATION_NOT_PASSED, Map.of(
            "leaveTypeNameTh", "ลากิจ", "probationEndsOn", "10 สิงหาคม 2569", "days", "1.00"));
        // V164 final copy: an EXCESS code's template takes ONLY "excessDays" now, not "days" too --
        // see LeaveRuleMessages's WARN_UNPAID_TAIL block comment for why the earlier draft's
        // "{days} carries the same value as {excessDays}" duplication was dropped.
        byCode.put(LeaveRuleCode.FIRST_YEAR_MAX_DAYS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "effectiveCap", "3", "excessDays", "1.00"));
        // V164 final copy: WEDDING_MAX_DAYS's template additionally states the request's own
        // "totalDays" alongside "excessDays"; it no longer takes a bare "days".
        byCode.put(LeaveRuleCode.WEDDING_MAX_DAYS, Map.of(
            "maxDays", "3", "totalDays", "4.00", "excessDays", "1.00"));
        byCode.put(LeaveRuleCode.MAX_CONSECUTIVE_DAYS, Map.of(
            "leaveTypeNameTh", "ลากิจ", "maxConsecutiveDays", "3", "excessDays", "1.00"));
        byCode.put(LeaveRuleCode.CONTIGUOUS_LEAVE_PAIR, Map.of(
            "leaveTypeNameTh", "ลากิจ", "pairedTypeNameTh", "ลาพักร้อน"));
        byCode.put(LeaveRuleCode.SICK_CERTIFICATE_WINDOW, Map.of(
            "certificateWindowDays", "3", "certificateDeadline", "4 มิถุนายน 2569", "days", "1.00"));
        byCode.put(LeaveRuleCode.SICK_CERTIFICATE_REQUIRED, Map.of("days", "1.00"));
        byCode.put(LeaveRuleCode.SICK_NO_CERT_TOLERANCE_EXHAUSTED, Map.of("tolerance", "3", "days", "1.00"));
        byCode.put(LeaveRuleCode.EMERGENCY_TOLERANCE_EXHAUSTED, Map.of("allowance", "3", "days", "1.00"));
        // V164 final copy: ADVANCE_NOTICE's template now also opens with "การ{leaveTypeNameTh}...",
        // so it needs "leaveTypeNameTh" too -- it did not before (see LeaveService#autoRejectNote's
        // call site, updated in the same change).
        byCode.put(LeaveRuleCode.ADVANCE_NOTICE, Map.of(
            "leaveTypeNameTh", "ลากิจ", "noticeDays", "1", "days", "1.00"));
        byCode.put(LeaveRuleCode.DEPARTMENT_COVERAGE, Map.of("uncoveredDate", "2026-07-13"));
        return byCode;
    }
}
