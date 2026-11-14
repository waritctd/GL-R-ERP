package th.co.glr.hr.leave;

import java.util.HashMap;
import java.util.Map;

/** Phase A0a (structured rejection outcome): the SINGLE place a LeaveRuleCode plus its params becomes the Thai
 * sentence stored in hr.leave_request.system_note and shown to the employee. leaveTypeNameTh ALREADY STARTS
 * WITH "ลา" -- never prefix it with another one. */
final class LeaveRuleMessages {
    private LeaveRuleMessages() {
    }

    // FINAL COPY -- WARN_UNPAID_* templates (V164, owner-approved CHANGE, wording signed off 2026-09-09). Do not
    // duplicate either string into a case below; reference the constant.
    private static final String WARN_UNPAID_TAIL =
        "แต่ยังส่งให้หัวหน้างานพิจารณาได้ หากอนุมัติ วันลา {days} วันจะไม่ได้รับค่าจ้าง";
    private static final String WARN_UNPAID_EXCESS_TAIL =
        "ส่วนที่เกิน {excessDays} วันจะไม่ได้รับค่าจ้าง";
    /** Appended (only) to the two SICK document gates -- tells the employee how to still be paid normally. */
    private static final String SICK_CERT_CLOSER =
        " แนบใบรับรองแพทย์เพื่อรับค่าจ้างตามปกติ";

    /** Renders code's Thai sentence, substituting every {key} placeholder in its template from params. See this
     * class's Javadoc for the param-formatting and missing-param contracts. section (§ code.sectionRef()) is
     * injected automatically for every code -- a caller never needs to pass it explicitly. */
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

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above. {probationEndsOn} arrives
            // pre-formatted via ThaiText.date -- see this class's Javadoc.
            case PROBATION_NOT_PASSED -> format(code, withSection,
                "การ{leaveTypeNameTh}ต้องผ่านทดลองงานก่อน (คาดว่าจะผ่านวันที่ {probationEndsOn} "
                    + "ระเบียบข้อ {section}) " + WARN_UNPAID_TAIL,
                "leaveTypeNameTh", "probationEndsOn", "section", "days");

            // WARN_UNPAID_EXCESS (V164): only {excessDays} -- see this class's WARN_UNPAID_TAIL block comment above for
            // why an EXCESS template no longer also takes {days}.
            case FIRST_YEAR_MAX_DAYS -> format(code, withSection,
                "การ{leaveTypeNameTh}ในช่วง 12 เดือนแรกของการทำงาน ลาได้รวมไม่เกิน {effectiveCap} "
                    + "วันต่อปี (ระเบียบข้อ {section}) คำขอนี้ทำให้เกินสิทธิ์ " + WARN_UNPAID_EXCESS_TAIL,
                "leaveTypeNameTh", "effectiveCap", "section", "excessDays");

            // WARN_UNPAID_EXCESS (V164) -- see FIRST_YEAR_MAX_DAYS above for the {excessDays}-only note.
            case WEDDING_MAX_DAYS -> format(code, withSection,
                "การลาเข้าพิธีสมรส (ของตนเองหรือบุตร) ลาได้ไม่เกิน {maxDays} วันต่อครั้ง (ระเบียบข้อ {section}) "
                    + "คำขอนี้ลา {totalDays} วัน " + WARN_UNPAID_EXCESS_TAIL,
                "maxDays", "section", "totalDays", "excessDays");

            // WARN_UNPAID_EXCESS (V164, DORMANT -- see LeaveRuleCode's class Javadoc): see FIRST_YEAR_MAX_DAYS above for
            // the {excessDays}-only note.
            case MAX_CONSECUTIVE_DAYS -> format(code, withSection,
                "การ{leaveTypeNameTh}ติดต่อกันได้ไม่เกิน {maxConsecutiveDays} วันต่อครั้ง (ระเบียบข้อ {section}) "
                    + WARN_UNPAID_EXCESS_TAIL,
                "leaveTypeNameTh", "maxConsecutiveDays", "section", "excessDays");

            case CONTIGUOUS_LEAVE_PAIR -> format(code, params,
                "ไม่อนุญาตให้{leaveTypeNameTh}ติดต่อกับ{pairedTypeNameTh}โดยไม่มีวันทำงานคั่นระหว่างกัน "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "pairedTypeNameTh");

            // WARN_UNPAID_ALL (V164) -- see this class's WARN_UNPAID_TAIL block comment above. {certificateDeadline}
            // arrives pre-formatted via ThaiText.date -- see this class's Javadoc.
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
                "การ{leaveTypeNameTh}ต้องยื่นล่วงหน้าอย่างน้อย {noticeDays} วันทำงาน (ระเบียบข้อ {section}) "
                    + "คำขอนี้ยื่นไม่ทันกำหนด " + WARN_UNPAID_TAIL,
                "leaveTypeNameTh", "noticeDays", "section", "days");

            case DEPARTMENT_COVERAGE -> format(code, params,
                "คำขอนี้จะทำให้ไม่มีพนักงานคนอื่นในแผนกมาทำงานในวันที่ {uncoveredDate} "
                    + "ต้องมีพนักงานในแผนกอย่างน้อย 1 คนมาทำงาน กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "uncoveredDate");
        };
    }

    /** Substitutes every requiredKeys placeholder ("{key"}) in template from params, in order. Throws
     * IllegalArgumentException -- naming code and the specific missing key -- the moment a required key has no
     * entry in params, before ever returning a string with an unfilled {placeholder} in it. */
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
