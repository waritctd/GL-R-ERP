package th.co.glr.hr.leave;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;

/**
 * Auto-generated ใบลาหยุด (leave form F-HR-020) attached to the leave-submission email so HR receives
 * the same document staff used to fill and email by hand -- the whole point of the 2026-09 change is
 * that pressing "ยื่นคำขอลา" in the portal produces this form automatically instead.
 *
 * <p><b>Content-faithful, not a pixel copy (same ruling as {@link LeaveReportRenderer}, 2026-09-10).</b>
 * The paper F-HR-020 is a two-up bordered grid with underline fill-ins and signature boxes;
 * {@link PdfDocumentWriter} is a flowing text writer with no box/border primitive, so this prints one
 * A4-portrait form carrying every field of the original -- header, leave type, dates/times, reason,
 * the contact-during-leave block, this-year history, and the approval/HR-receipt sections left blank
 * for signature -- laid out for reading rather than tracing the grid. F-HR-020(01) is retained in the
 * footer so the document is still identifiable as that form.
 *
 * <p>This class only RENDERS. Every value comes off {@link LeaveFormData}; no query or leave-day math
 * happens here (see {@link LeaveService#buildLeaveForm}).
 */
@Component
public class LeaveFormRenderer {
    private static final String COMPANY_NAME = "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด";
    private static final String FORM_TITLE = "ใบลาหยุด";
    private static final String FORM_CODE = "F-HR-020(01)";
    private static final String FORM_NOTE =
        "หมายเหตุ การลาทุกประเภทจะต้องได้รับอนุมัติจากผู้มีอำนาจก่อนจึงจะหยุดงานได้";

    private static final String[] THAI_MONTHS_ABBR = {
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    };

    private static final float TITLE_SIZE = 16f;
    private static final float SUBTITLE_SIZE = 12f;
    private static final float BODY_SIZE = 11f;
    private static final float SMALL_SIZE = 9f;

    public byte[] toPdf(LeaveFormData data) {
        try (PdfDocumentWriter pdf = new PdfDocumentWriter()) {
            PDFont regular = pdf.loadFont(PdfDocumentWriter.FONT_REGULAR);
            PDFont bold = pdf.loadFont(PdfDocumentWriter.FONT_BOLD);
            float left = pdf.left();
            float right = pdf.right();

            pdf.textCenter(bold, TITLE_SIZE, COMPANY_NAME);
            pdf.textCenter(bold, SUBTITLE_SIZE, FORM_TITLE);
            pdf.textRight(regular, SMALL_SIZE, right, "วันที่ " + thaiDate(data.filedDate()));
            pdf.newLine(SMALL_SIZE);
            pdf.gap(6);
            pdf.rule(left, right);
            pdf.gap(12);

            // --- Employee identity -----------------------------------------------------------------
            line(pdf, regular, "ชื่อ-สกุล  " + nn(data.employeeName())
                + "          รหัสพนักงาน  " + nn(data.employeeCode()));
            line(pdf, regular, "ตำแหน่ง  " + nn(data.positionTh()) + "          แผนก  " + nn(data.departmentTh()));
            line(pdf, regular, "ฝ่าย  " + nn(data.divisionTh()));
            pdf.gap(6);

            // --- Leave type (checkbox row + free "อื่นๆ" for the non-form types) --------------------
            String code = data.leaveTypeCode() == null ? "" : data.leaveTypeCode();
            float typeGap = 150f;
            pdf.textAt(regular, BODY_SIZE, left, "มีความประสงค์ขอ");
            pdf.textAt(regular, BODY_SIZE, left + typeGap, checkbox("PERSONAL".equals(code)) + " ลากิจ");
            pdf.textAt(regular, BODY_SIZE, left + typeGap + 110f, checkbox("SICK".equals(code)) + " ลาป่วย");
            pdf.textAt(regular, BODY_SIZE, left + typeGap + 220f, checkbox("VACATION".equals(code)) + " ลาพักร้อน");
            pdf.newLine(BODY_SIZE);
            if (!isFormType(code)) {
                // MATERNITY / MILITARY / ORDINATION / LEAVE_WITHOUT_PAY etc. have no box on the paper
                // form; name the actual type so the document is never ambiguous about what was requested.
                line(pdf, regular, checkbox(true) + " อื่นๆ: " + nn(data.leaveTypeNameTh()));
            }
            pdf.gap(4);

            // --- Dates / times ---------------------------------------------------------------------
            line(pdf, regular, "ระหว่างวันที่ " + thaiDate(data.startDate())
                + "  ถึงวันที่ " + thaiDate(data.endDate()) + timeClause(data));
            line(pdf, regular, "รวมวันลา " + days(data.totalDays()) + " วัน" + hoursClause(data));
            pdf.gap(2);

            // --- Reason ----------------------------------------------------------------------------
            wrapped(pdf, regular, BODY_SIZE, "เหตุผลในการลา  " + nn(data.reason()));
            pdf.gap(6);

            // --- Contact during leave --------------------------------------------------------------
            wrapped(pdf, regular, BODY_SIZE, "ในระหว่างที่ลาสามารถติดต่อข้าพเจ้าได้ที่  " + contactLine(data));
            line(pdf, regular, "โทรศัพท์  " + nn(data.contactPhone()));
            pdf.gap(8);
            pdf.rule(left, right);
            pdf.gap(10);

            // --- History this year -----------------------------------------------------------------
            pdf.textAt(bold, BODY_SIZE, left, "ประวัติการลาในรอบปีนี้");
            pdf.newLine(BODY_SIZE);
            line(pdf, regular, "ลาหยุดงานมาแล้วรวม " + days(data.usedTotalDays()) + " วัน"
                + "   (ลากิจ " + days(data.usedPersonalDays()) + " วัน"
                + " · ลาป่วย " + days(data.usedSickDays()) + " วัน"
                + " · พักร้อน " + days(data.usedVacationDays()) + " วัน)");
            pdf.gap(4);
            line(pdf, regular, "ลงชื่อ ..................................................  พนักงานผู้ยื่นใบลา");
            pdf.textRight(regular, SMALL_SIZE, right, "( " + nn(data.employeeName()) + " )");
            pdf.newLine(SMALL_SIZE);
            pdf.gap(12);
            pdf.rule(left, right);
            pdf.gap(10);

            // --- Approval / HR receipt (blank for signature) ---------------------------------------
            pdf.textAt(bold, BODY_SIZE, left, "ความเห็นผู้บังคับบัญชา");
            pdf.newLine(BODY_SIZE);
            line(pdf, regular, checkbox(false) + " เห็นควรอนุมัติ        " + checkbox(false)
                + " เห็นควรไม่อนุมัติ เพราะ ......................................................");
            pdf.gap(2);
            line(pdf, regular, "ลงชื่อ ..................................................  ผู้อนุมัติ"
                + "        วันที่ ...........................");
            pdf.gap(8);
            pdf.textAt(bold, SMALL_SIZE, left, "สำหรับฝ่ายทรัพยากรบุคคลและธุรการ");
            pdf.newLine(SMALL_SIZE);
            line(pdf, regular, checkbox(false) + " อนุมัติ    " + checkbox(false)
                + " ไม่อนุมัติ        ได้รับเอกสารวันที่ ........................... เวลา ...................");
            line(pdf, regular, "ลงชื่อ ..................................................  ผู้รับ/ผู้บันทึก");

            pdf.gap(14);
            pdf.rule(left, right);
            pdf.gap(8);
            pdf.textAt(regular, SMALL_SIZE, left, FORM_NOTE);
            pdf.textRight(regular, SMALL_SIZE, right, FORM_CODE);

            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("Leave form PDF render failed: " + e.getMessage(), e);
        }
    }

    private void line(PdfDocumentWriter pdf, PDFont font, String value) throws IOException {
        pdf.textAt(font, BODY_SIZE, pdf.left(), value);
        pdf.newLine(BODY_SIZE);
    }

    /** Wraps a long value to the printable width, paginating per line (see LeaveReportRenderer). */
    private void wrapped(PdfDocumentWriter pdf, PDFont font, float size, String value) throws IOException {
        float x = pdf.left();
        for (String piece : pdf.wrap(font, size, value, pdf.right() - x)) {
            pdf.ensureRoom(size * 1.5f);
            pdf.textAt(font, size, x, piece);
            pdf.newLine(size);
        }
    }

    private String checkbox(boolean on) {
        return on ? "[ X ]" : "[    ]";
    }

    private boolean isFormType(String code) {
        return "PERSONAL".equals(code) || "SICK".equals(code) || "VACATION".equals(code);
    }

    /** " ตั้งแต่เวลา hh:mm ถึงเวลา hh:mm" for a sub-day request; empty for a whole-day one. */
    private String timeClause(LeaveFormData data) {
        if (data.startTime() == null) {
            return "";
        }
        String clause = "  ตั้งแต่เวลา " + hhmm(data.startTime());
        if (data.endTime() != null) {
            clause += " ถึงเวลา " + hhmm(data.endTime());
        }
        return clause;
    }

    /** " รวมเวลา N ชั่วโมง" for a sub-day request; empty when no times were given. */
    private String hoursClause(LeaveFormData data) {
        if (data.startTime() == null || data.endTime() == null) {
            return "";
        }
        long minutes = Duration.between(data.startTime(), data.endTime()).toMinutes();
        if (minutes <= 0) {
            return "";
        }
        String hours = stripZeros(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60)));
        return "   รวมเวลา " + hours + " ชั่วโมง";
    }

    private String contactLine(LeaveFormData data) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, "เลขที่ ", data.contactHouseNo());
        appendPart(sb, "ตำบล/แขวง ", data.contactSubdistrict());
        appendPart(sb, "อำเภอ/เขต ", data.contactDistrict());
        appendPart(sb, "จังหวัด ", data.contactProvince());
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private void appendPart(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("  ");
        }
        sb.append(label).append(value.trim());
    }

    private String hhmm(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private String days(BigDecimal value) {
        return value == null ? "0" : stripZeros(value);
    }

    private String stripZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String thaiDate(LocalDate date) {
        if (date == null) {
            return "..........................";
        }
        return date.getDayOfMonth() + " " + THAI_MONTHS_ABBR[date.getMonthValue() - 1] + " " + (date.getYear() + 543);
    }

    private String nn(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
