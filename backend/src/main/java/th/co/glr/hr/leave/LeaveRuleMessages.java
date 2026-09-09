package th.co.glr.hr.leave;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase A0a (structured rejection outcome): the SINGLE place a {@link LeaveRuleCode} plus its
 * params becomes the Thai sentence stored in {@code hr.leave_request.system_note} and shown to the
 * employee. Every one of the 17 rejection reasons {@code LeaveService#autoRejectNote} can produce
 * has exactly one template here; {@link LeaveService} itself never builds a rejection sentence by
 * hand any more -- it builds a {@code (code, params)} pair and asks {@link LeaveRuleOutcome#of} to
 * render it through this class.
 *
 * <p><b>Vocabulary:</b> echoes the governing §5 announcement's own Thai terms (see V116/V120/V124/
 * V125's migration comments and {@code LeaveService#autoRejectNote}'s Javadoc for the source text):
 * ลาป่วย (SICK), ลากิจ (PERSONAL), ลาพักร้อน (VACATION), ลาอุปสมบท (ORDINATION), ใบรับรองแพทย์
 * (medical certificate), อายุงาน (service duration/tenure), ทดลองงาน (probation), ลากิจฉุกเฉิน
 * (emergency PERSONAL leave).
 *
 * <p><b>Closing clause mirrors the English original it replaces, site for site</b> -- not a single
 * generic "contact HR" tacked onto everything: a message that ended "Contact HR if this is an
 * exception." renders "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น"; one that ended "Contact your manager
 * or HR[...]" renders "กรุณาติดต่อหัวหน้างานหรือฝ่ายบุคคล[...]"; one that ended "Attach the
 * certificate or contact HR for help." renders "กรุณาแนบใบรับรองแพทย์หรือติดต่อฝ่ายบุคคลเพื่อขอความ
 * ช่วยเหลือ". See each {@code case} below for which one a given code carries forward.
 *
 * <p><b>{@code leaveTypeNameTh} ALREADY STARTS WITH "ลา" -- never prefix it with another one.</b>
 * Every seeded {@code hr.leave_type.name_th} is a complete noun phrase that already carries the
 * verb: ลาป่วย, ลากิจ, ลาพักร้อน, ลาคลอดบุตร, ลารับราชการทหาร, ลาอุปสมบท, ลาไม่รับค่าจ้าง (V13, and
 * unchanged by every rule migration since). So a template writes {@code "การ{leaveTypeNameTh}"} and
 * {@code "ไม่อนุญาตให้{leaveTypeNameTh}"} -- NOT {@code "การลา{leaveTypeNameTh}"}, which renders the
 * doubled, ungrammatical "การลาลาป่วย". This is easy to reintroduce because the English messages
 * this class replaced used {@code nameEn()} ("Sick leave"), which carries no such prefix and reads
 * naturally after "…". {@code LeaveRuleMessagesTest} pins it: no rendered message may contain "ลาลา".
 *
 * <p><b>Params are pre-formatted by the caller.</b> This class does no date formatting and no
 * locale logic of its own -- a date param (e.g. {@code probationEndsOn}, {@code certificateDeadline})
 * arrives already rendered through {@code th.co.glr.hr.common.ThaiText#date} ("12 กันยายน 2569"), the
 * same Thai-reader-facing formatting every notification-email date uses -- NEVER a raw
 * {@code LocalDate.toString()} ISO string ("2026-09-12") concatenated into an otherwise all-Thai
 * sentence. This class only does string substitution; it never formats a date itself.
 *
 * <p><b>Missing param fails loudly, not silently.</b> {@link #render} throws
 * {@link IllegalArgumentException} naming both the code and the missing key the instant a required
 * placeholder has no value -- a broken message must fail a test, never reach an employee as a raw
 * {@code {key}} placeholder.
 *
 * <p><b>Final copy rules (owner sign-off, 2026-09-09, V164):</b>
 * <ul>
 *   <li>No em/en dash anywhere in this class's prose -- an em/en dash in a sentence reads
 *       machine-generated. Use plain spaces and connecting words instead. (This does NOT apply to
 *       {@code ThaiText#dateRange}'s en dash between two dates in a date RANGE -- that is real
 *       typography for a range, not prose, and is untouched.) {@code LeaveRuleMessagesTest} pins
 *       this: no rendered message may contain U+2013 (EN DASH) or U+2014 (EM DASH).
 *   <li>No comma before a section reference -- Thai separates with a space, not a comma:
 *       {@code "(ระเบียบข้อ {section})"}, never {@code "(..., ระเบียบข้อ {section})"}.
 *   <li>Section references render {@code "(ระเบียบข้อ {section})"}, never the earlier draft's
 *       {@code "(§{section})"}.
 * </ul>
 */
final class LeaveRuleMessages {
    private LeaveRuleMessages() {
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // FINAL COPY -- WARN_UNPAID_* templates (V164, owner-approved CHANGE, wording signed off
    // 2026-09-09). Every code below whose LeaveRuleCode#enforcement() is WARN_UNPAID_ALL ends with
    // WARN_UNPAID_TAIL; every WARN_UNPAID_EXCESS code ends with WARN_UNPAID_EXCESS_TAIL -- two
    // DIFFERENT tails, because the two groups end differently: an ALL code dockes the WHOLE request
    // ({days} = total_days), while an EXCESS code docks only the amount OVER its own cap
    // ({excessDays}) and the request otherwise still goes through the ordinary quota/paid-cap
    // machinery. Do not duplicate either string into a case below; reference the constant. The two
    // SICK document gates (SICK_CERTIFICATE_REQUIRED, SICK_NO_CERT_TOLERANCE_EXHAUSTED) additionally
    // append SICK_CERT_CLOSER, telling the employee how to still get paid normally.
    //
    // {days} (WARN_UNPAID_TAIL) is the number of days that will be unpaid if this request is
    // approved -- always total_days for a WARN_UNPAID_ALL code (see LeaveService#autoRejectNote's
    // dominance-rule Javadoc for how unpaid_by_rule_days itself is computed). An EXCESS code's
    // template no longer takes a {days} param at all -- ONLY {excessDays} -- since {excessDays} IS
    // the figure WARN_UNPAID_EXCESS_TAIL needs; the earlier draft's "{days} carries the same value
    // as {excessDays}" duplication has been dropped from both the templates and their call sites.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    private static final String WARN_UNPAID_TAIL =
        "แต่ยังส่งให้หัวหน้างานพิจารณาได้ หากอนุมัติ วันลา {days} วันจะไม่ได้รับค่าจ้าง";
    private static final String WARN_UNPAID_EXCESS_TAIL =
        "ส่วนที่เกิน {excessDays} วันจะไม่ได้รับค่าจ้าง";
    /** Appended (only) to the two SICK document gates -- tells the employee how to still be paid normally. */
    private static final String SICK_CERT_CLOSER =
        " แนบใบรับรองแพทย์เพื่อรับค่าจ้างตามปกติ";

    /**
     * Renders {@code code}'s Thai sentence, substituting every {@code {key}} placeholder in its
     * template from {@code params}. See this class's Javadoc for the param-formatting and
     * missing-param contracts. {@code section} (§ code.sectionRef()) is injected automatically for
     * every code -- a caller never needs to pass it explicitly.
     */
    static String render(LeaveRuleCode code, Map<String, String> params) {
        Map<String, String> withSection = new HashMap<>(params);
        withSection.putIfAbsent("section", code.sectionRef());
        return switch (code) {
            case ONCE_PER_EMPLOYMENT -> format(code, params,
                "การ{leaveTypeNameTh}สามารถใช้สิทธิ์ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน "
                    + "และมีคำขอที่ใช้สิทธิ์นี้ไปแล้ว กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh");

            case RESIGNATION_GATE -> format(code, params,
                "ไม่อนุญาตให้{leaveTypeNameTh}หลังจากยื่นใบลาออกแล้ว เว้นแต่หัวหน้างานอนุมัติว่าได้ส่งมอบ"
                    + "งานที่ค้างอยู่เรียบร้อยแล้ว กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh");

            case HIRE_DATE_MISSING_PRORATED, HIRE_DATE_MISSING_MIN_SERVICE -> format(code, params,
                "ไม่มีข้อมูลวันที่เริ่มงานในระบบ จึงไม่สามารถตรวจสอบสิทธิ์การ{leaveTypeNameTh}ได้ "
                    + "กรุณาติดต่อฝ่ายบุคคลเพื่อบันทึกข้อมูลก่อนใช้สิทธิ์การลาประเภทนี้",
                "leaveTypeNameTh");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            case MIN_SERVICE_MONTHS -> format(code, withSection,
                "การ{leaveTypeNameTh}ต้องมีอายุงานครบอย่างน้อย {minServiceMonths} เดือน (ระเบียบข้อ {section}) "
                    + WARN_UNPAID_TAIL,
                "leaveTypeNameTh", "minServiceMonths", "section", "days");

            case PROBATION_HIRE_DATE_MISSING -> format(code, params,
                "ไม่มีข้อมูลวันที่เริ่มงานในระบบ จึงไม่สามารถตรวจสอบสถานะการผ่านทดลองงานได้ "
                    + "กรุณาติดต่อฝ่ายบุคคลเพื่อบันทึกข้อมูลก่อนใช้สิทธิ์การ{leaveTypeNameTh}",
                "leaveTypeNameTh");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            // {probationEndsOn} arrives pre-formatted via ThaiText.date -- see this class's Javadoc.
            case PROBATION_NOT_PASSED -> format(code, withSection,
                "การ{leaveTypeNameTh}ต้องผ่านทดลองงานก่อน (คาดว่าจะผ่านวันที่ {probationEndsOn} "
                    + "ระเบียบข้อ {section}) " + WARN_UNPAID_TAIL,
                "leaveTypeNameTh", "probationEndsOn", "section", "days");

            // WARN_UNPAID_EXCESS (V164): only {excessDays} -- see this class's WARN_UNPAID_TAIL
            // block comment above for why an EXCESS template no longer also takes {days}.
            case FIRST_YEAR_MAX_DAYS -> format(code, withSection,
                "การ{leaveTypeNameTh}ในช่วง 12 เดือนแรกของการทำงาน ลาได้รวมไม่เกิน {effectiveCap} "
                    + "วันต่อปี (ระเบียบข้อ {section}) คำขอนี้ทำให้เกินสิทธิ์ " + WARN_UNPAID_EXCESS_TAIL,
                "leaveTypeNameTh", "effectiveCap", "section", "excessDays");

            // WARN_UNPAID_EXCESS (V164) -- see FIRST_YEAR_MAX_DAYS above for the {excessDays}-only note.
            case WEDDING_MAX_DAYS -> format(code, withSection,
                "การลาเข้าพิธีสมรส (ของตนเองหรือบุตร) ลาได้ไม่เกิน {maxDays} วันต่อครั้ง (ระเบียบข้อ {section}) "
                    + "คำขอนี้ลา {totalDays} วัน " + WARN_UNPAID_EXCESS_TAIL,
                "maxDays", "section", "totalDays", "excessDays");

            // WARN_UNPAID_EXCESS (V164, DORMANT -- see LeaveRuleCode's class Javadoc): see
            // FIRST_YEAR_MAX_DAYS above for the {excessDays}-only note.
            case MAX_CONSECUTIVE_DAYS -> format(code, withSection,
                "การ{leaveTypeNameTh}ติดต่อกันได้ไม่เกิน {maxConsecutiveDays} วันต่อครั้ง (ระเบียบข้อ {section}) "
                    + WARN_UNPAID_EXCESS_TAIL,
                "leaveTypeNameTh", "maxConsecutiveDays", "section", "excessDays");

            case CONTIGUOUS_LEAVE_PAIR -> format(code, params,
                "ไม่อนุญาตให้{leaveTypeNameTh}ติดต่อกับ{pairedTypeNameTh}โดยไม่มีวันทำงานคั่นระหว่างกัน "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "pairedTypeNameTh");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            // {certificateDeadline} arrives pre-formatted via ThaiText.date -- see this class's Javadoc.
            case SICK_CERTIFICATE_WINDOW -> format(code, withSection,
                "ต้องยื่นใบรับรองแพทย์ภายใน {certificateWindowDays} วันทำการนับจากวันที่เริ่มลาป่วย "
                    + "(ภายในวันที่ {certificateDeadline} ระเบียบข้อ {section}) คำขอนี้ยื่นเกินกำหนด "
                    + WARN_UNPAID_TAIL,
                "certificateWindowDays", "certificateDeadline", "section", "days");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            case SICK_CERTIFICATE_REQUIRED -> format(code, withSection,
                "การลาป่วยต้องแนบใบรับรองแพทย์ (ระเบียบข้อ {section}) คำขอนี้ไม่มีใบรับรองแพทย์ "
                    + WARN_UNPAID_TAIL + SICK_CERT_CLOSER,
                "section", "days");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            case SICK_NO_CERT_TOLERANCE_EXHAUSTED -> format(code, withSection,
                "การลาป่วยโดยไม่มีใบรับรองแพทย์อนุโลมให้ได้ไม่เกิน {tolerance} ครั้งต่อเดือน "
                    + "และเดือนนี้ใช้ครบแล้ว (ระเบียบข้อ {section}) " + WARN_UNPAID_TAIL + SICK_CERT_CLOSER,
                "tolerance", "section", "days");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            case EMERGENCY_TOLERANCE_EXHAUSTED -> format(code, withSection,
                "การลากิจฉุกเฉินโดยไม่แจ้งล่วงหน้าอนุโลมให้ได้ไม่เกิน {allowance} ครั้งต่อเดือน "
                    + "และเดือนนี้ใช้ครบแล้ว (ระเบียบข้อ {section}) " + WARN_UNPAID_TAIL,
                "allowance", "section", "days");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above.
            case ADVANCE_NOTICE -> format(code, withSection,
                "การ{leaveTypeNameTh}ต้องยื่นล่วงหน้าอย่างน้อย {noticeDays} วัน (ระเบียบข้อ {section}) "
                    + "คำขอนี้ยื่นไม่ทันกำหนด " + WARN_UNPAID_TAIL,
                "leaveTypeNameTh", "noticeDays", "section", "days");

            case DEPARTMENT_COVERAGE -> format(code, params,
                "คำขอนี้จะทำให้ไม่มีพนักงานคนอื่นในแผนกมาทำงานในวันที่ {uncoveredDate} "
                    + "ต้องมีพนักงานในแผนกอย่างน้อย 1 คนมาทำงาน กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "uncoveredDate");
        };
    }

    /**
     * Substitutes every {@code requiredKeys} placeholder ({@code "{key}"}) in {@code template} from
     * {@code params}, in order. Throws {@link IllegalArgumentException} -- naming {@code code} and
     * the specific missing key -- the moment a required key has no entry in {@code params}, before
     * ever returning a string with an unfilled {@code {placeholder}} in it.
     */
    private static String format(LeaveRuleCode code, Map<String, String> params, String template, String... requiredKeys) {
        String rendered = template;
        for (String key : requiredKeys) {
            String value = params.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                    "LeaveRuleMessages.render(" + code + "): missing required param '" + key + "'");
            }
            rendered = rendered.replace("{" + key + "}", value);
        }
        return rendered;
    }
}
