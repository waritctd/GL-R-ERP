package th.co.glr.hr.leave;

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
 * locale logic of its own -- a date param (e.g. {@code probationEndsOn}) arrives as whatever
 * {@link Object#toString()} (or an explicit formatter) the caller in {@code LeaveService} already
 * chose, matching the original English message's own {@code + probationEndsOn} string-concat
 * behaviour exactly. This class only does string substitution.
 *
 * <p><b>Missing param fails loudly, not silently.</b> {@link #render} throws
 * {@link IllegalArgumentException} naming both the code and the missing key the instant a required
 * placeholder has no value -- a broken message must fail a test, never reach an employee as a raw
 * {@code {key}} placeholder.
 */
final class LeaveRuleMessages {
    private LeaveRuleMessages() {
    }

    /**
     * Renders {@code code}'s Thai sentence, substituting every {@code {key}} placeholder in its
     * template from {@code params}. See this class's Javadoc for the param-formatting and
     * missing-param contracts.
     */
    static String render(LeaveRuleCode code, Map<String, String> params) {
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

            case MIN_SERVICE_MONTHS -> format(code, params,
                "การ{leaveTypeNameTh}ต้องมีอายุงานครบอย่างน้อย {minServiceMonths} เดือน "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "minServiceMonths");

            case PROBATION_HIRE_DATE_MISSING -> format(code, params,
                "ไม่มีข้อมูลวันที่เริ่มงานในระบบ จึงไม่สามารถตรวจสอบสถานะการผ่านทดลองงานได้ "
                    + "กรุณาติดต่อฝ่ายบุคคลเพื่อบันทึกข้อมูลก่อนใช้สิทธิ์การ{leaveTypeNameTh}",
                "leaveTypeNameTh");

            case PROBATION_NOT_PASSED -> format(code, params,
                "การ{leaveTypeNameTh}ต้องผ่านทดลองงานก่อน (คาดว่าจะผ่านทดลองงานวันที่ {probationEndsOn}) "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "probationEndsOn");

            case FIRST_YEAR_MAX_DAYS -> format(code, params,
                "การ{leaveTypeNameTh}ในช่วง 12 เดือนแรกของการทำงาน ลาได้รวมไม่เกิน {effectiveCap} "
                    + "วันต่อปี กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "effectiveCap");

            case WEDDING_MAX_DAYS -> format(code, params,
                "การลาเข้าพิธีสมรส (ของตนเองหรือบุตร) ลาได้ไม่เกิน {maxDays} วันต่อครั้ง "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "maxDays");

            case MAX_CONSECUTIVE_DAYS -> format(code, params,
                "การ{leaveTypeNameTh}ติดต่อกันได้ไม่เกิน {maxConsecutiveDays} วันต่อครั้ง "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "maxConsecutiveDays");

            case CONTIGUOUS_LEAVE_PAIR -> format(code, params,
                "ไม่อนุญาตให้{leaveTypeNameTh}ติดต่อกับ{pairedTypeNameTh}โดยไม่มีวันทำงานคั่นระหว่างกัน "
                    + "กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "leaveTypeNameTh", "pairedTypeNameTh");

            case SICK_CERTIFICATE_WINDOW -> format(code, params,
                "ต้องยื่นใบรับรองแพทย์ภายใน {certificateWindowDays} วันทำการนับจากวันที่เริ่มลาป่วย "
                    + "(ภายในวันที่ {certificateDeadline}) กรุณาติดต่อฝ่ายบุคคลหากเป็นกรณียกเว้น",
                "certificateWindowDays", "certificateDeadline");

            case SICK_CERTIFICATE_REQUIRED -> format(code, params,
                "การลาป่วยต้องแนบใบรับรองแพทย์ "
                    + "กรุณาแนบใบรับรองแพทย์หรือติดต่อฝ่ายบุคคลเพื่อขอความช่วยเหลือ");

            case SICK_NO_CERT_TOLERANCE_EXHAUSTED -> format(code, params,
                "การลาป่วยโดยไม่มีใบรับรองแพทย์อนุโลมให้ลาได้ไม่เกิน {tolerance} ครั้งต่อเดือน "
                    + "และใช้สิทธิ์นี้ครบแล้วในเดือนนี้ กรุณาแนบใบรับรองแพทย์หรือติดต่อฝ่ายบุคคลเพื่อขอความ"
                    + "ช่วยเหลือ",
                "tolerance");

            case EMERGENCY_TOLERANCE_EXHAUSTED -> format(code, params,
                "การลากิจฉุกเฉินโดยไม่แจ้งล่วงหน้าอนุโลมให้ได้ไม่เกิน {allowance} ครั้งต่อเดือน "
                    + "และใช้สิทธิ์นี้ครบแล้วในเดือนนี้ กรุณาติดต่อหัวหน้างานหรือฝ่ายบุคคล",
                "allowance");

            case ADVANCE_NOTICE -> format(code, params,
                "คำขอลาต้องยื่นล่วงหน้าอย่างน้อย {noticeDays} วันก่อนวันเริ่มลา "
                    + "กรุณาติดต่อหัวหน้างานหรือฝ่ายบุคคลสำหรับการลากรณีเร่งด่วน",
                "noticeDays");

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
