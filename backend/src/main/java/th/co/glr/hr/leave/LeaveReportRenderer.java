package th.co.glr.hr.leave;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;

/**
 * Printable leave-records report (รายงานสรุปใบลางาน, 2026-09) -- an employee's own leave history for
 * a chosen year (optionally narrowed to a month), or a manager's ONE grouped PDF covering every
 * direct report for the same period (owner ruling: one document, grouped per employee with running
 * row numbers restarting per employee -- NOT one PDF per person).
 *
 * <p><b>Readability over fidelity (owner ruling, 2026-09-10):</b> modelled on TigerSoft's
 * {@code TA TAR10_1}/{@code rptLeaveByEmp.rpt} as a CONTENT reference only -- which facts belong on
 * the page -- never as a visual template. TigerSoft's dense 13-column management print is cut down
 * to the columns a reader actually asks about (ประเภทการลา · ช่วงวันที่ · เวลา · รวม · สถานะ ·
 * หักเงิน · เหตุผล), which is what lets this fit A4 PORTRAIT on {@link PdfDocumentWriter} unchanged
 * -- no orientation parameter, no risk to the six other renderers that share that class.
 *
 * <p>This class only RENDERS. Every figure comes off the already-computed {@link
 * LeaveReportEmployeeDto}/{@link LeaveBalanceDto}/{@link LeaveRequestDto} -- no query, no leave-day
 * math happens here (see {@link LeaveService#ownLeaveReport}/{@link LeaveService#teamLeaveReport}
 * for the data assembly, and {@link LeaveDayMath#formatDuration} for the day-count -&gt; "1 วัน 4
 * ชม. 30 น." formatting, which is a PORT of the frontend's {@code leaveFormatting.js}, not a second
 * rule). Follows {@link th.co.glr.hr.payroll.PayslipRenderer}'s shape for that division of labour.
 */
@Component
public class LeaveReportRenderer {
    private static final String COMPANY_NAME = "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด";
    private static final String REPORT_TITLE = "รายงานสรุปใบลางาน";

    private static final String[] THAI_MONTHS_ABBR = {
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    };
    private static final String[] THAI_MONTHS_FULL = {
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    };

    // Mirrors frontend/src/utils/format.js's LEAVE_STATUS_LABELS wording verbatim, so the printed
    // page and the portal's StatusBadge never disagree on what a status is CALLED, the same
    // "cannot disagree" concern the quota block above already applies to the NUMBERS.
    private static final Map<String, String> STATUS_LABELS_TH = Map.of(
        "SUBMITTED", "รออนุมัติ",
        "APPROVED", "อนุมัติแล้ว",
        "REJECTED", "ปฏิเสธแล้ว",
        "CANCELLED", "ยกเลิกแล้ว",
        "AUTO_REJECTED", "โควตาไม่พอ"
    );

    private static final float TITLE_SIZE = 15f;
    private static final float SUBTITLE_SIZE = 12f;
    private static final float HEADING_SIZE = 11f;
    private static final float BODY_SIZE = 10f;
    private static final float SMALL_SIZE = 9f;

    // Quota table column boundaries, relative to the printable width -- entitlement/used/pending
    // right-aligned before คงเหลือ, which ends flush with the right margin.
    private static final float QUOTA_COL1_OFFSET = 230f;
    private static final float QUOTA_COL2_OFFSET = 320f;
    private static final float QUOTA_COL3_OFFSET = 405f;

    public byte[] toPdf(List<LeaveReportEmployeeDto> sections, int year, Integer month, boolean teamReport) {
        try (PdfDocumentWriter pdf = new PdfDocumentWriter()) {
            PDFont regular = pdf.loadFont(PdfDocumentWriter.FONT_REGULAR);
            PDFont bold = pdf.loadFont(PdfDocumentWriter.FONT_BOLD);

            pdf.textCenter(bold, TITLE_SIZE, COMPANY_NAME);
            pdf.textCenter(regular, SUBTITLE_SIZE,
                teamReport ? REPORT_TITLE + " — ทีมของฉัน" : REPORT_TITLE + " — ส่วนบุคคล");
            pdf.textCenter(regular, BODY_SIZE, "ช่วงเวลา: " + periodLabel(year, month));
            pdf.gap(14);

            if (sections.isEmpty()) {
                pdf.textCenter(regular, BODY_SIZE, "ไม่มีพนักงานในทีมของคุณในขณะนี้");
                return pdf.toBytes();
            }

            for (LeaveReportEmployeeDto section : sections) {
                renderEmployeeSection(pdf, regular, bold, section);
            }
            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("Leave report PDF render failed: " + e.getMessage(), e);
        }
    }

    private void renderEmployeeSection(
            PdfDocumentWriter pdf, PDFont regular, PDFont bold, LeaveReportEmployeeDto section) throws IOException {
        float left = pdf.left();
        float right = pdf.right();

        pdf.ensureRoom(100f);
        pdf.rule(left, right);
        pdf.gap(10);

        // --- Group header: code, name, division, วันเริ่มงาน ------------------------------------
        pdf.textAt(bold, HEADING_SIZE, left,
            nullSafe(section.employeeCode(), "-") + "   " + nullSafe(section.employeeName(), "-"));
        pdf.newLine(HEADING_SIZE);
        pdf.textAt(regular, SMALL_SIZE, left,
            "ฝ่าย: " + nullSafe(section.divisionNameTh(), "-")
                + "     วันเริ่มงาน: " + thaiDate(section.hireDate()));
        pdf.newLine(SMALL_SIZE);
        pdf.gap(8);

        // --- Quota summary block (#914 teamBalances/balanceFor numbers, verbatim) --------------
        pdf.textAt(bold, BODY_SIZE, left, "สรุปโควตาวันลา");
        pdf.newLine(BODY_SIZE);
        pdf.textAt(bold, SMALL_SIZE, left, "ประเภทการลา");
        pdf.textRight(bold, SMALL_SIZE, left + QUOTA_COL1_OFFSET, "สิทธิ์รวม");
        pdf.textRight(bold, SMALL_SIZE, left + QUOTA_COL2_OFFSET, "ใช้ไปแล้ว");
        pdf.textRight(bold, SMALL_SIZE, left + QUOTA_COL3_OFFSET, "รออนุมัติ");
        pdf.textRight(bold, SMALL_SIZE, right, "คงเหลือ");
        pdf.newLine(SMALL_SIZE);
        List<LeaveBalanceDto> quotaRows = section.balances().stream()
            .filter(LeaveReportRenderer::isMeaningfulQuotaRow)
            .toList();
        if (quotaRows.isEmpty()) {
            pdf.textAt(regular, SMALL_SIZE, left, "ไม่มีสิทธิ์วันลาในปีนี้");
            pdf.newLine(SMALL_SIZE);
        }
        for (LeaveBalanceDto balance : quotaRows) {
            pdf.ensureRoom(SMALL_SIZE * 1.5f);
            BigDecimal entitlement = nz(balance.annualQuotaDays()).add(nz(balance.carriedInDays()));
            pdf.textAt(regular, SMALL_SIZE, left, nullSafe(balance.leaveTypeNameTh(), balance.leaveTypeCode()));
            pdf.textRight(regular, SMALL_SIZE, left + QUOTA_COL1_OFFSET, LeaveDayMath.formatDuration(entitlement));
            pdf.textRight(regular, SMALL_SIZE, left + QUOTA_COL2_OFFSET,
                LeaveDayMath.formatDuration(balance.approvedDays()));
            pdf.textRight(regular, SMALL_SIZE, left + QUOTA_COL3_OFFSET,
                LeaveDayMath.formatDuration(balance.pendingDays()));
            pdf.textRight(regular, SMALL_SIZE, right, LeaveDayMath.formatDuration(balance.remainingDays()));
            pdf.newLine(SMALL_SIZE);
        }
        pdf.gap(10);

        // --- Leave rows, running row number restarting at 1 per employee -----------------------
        pdf.ensureRoom(BODY_SIZE * 1.5f);
        pdf.textAt(bold, BODY_SIZE, left, "รายการวันลา");
        pdf.newLine(BODY_SIZE);
        pdf.gap(2);
        if (section.requests().isEmpty()) {
            pdf.ensureRoom(SMALL_SIZE * 1.5f);
            pdf.textAt(regular, SMALL_SIZE, left, "ไม่มีการลาในช่วงเวลาที่เลือก");
            pdf.newLine(SMALL_SIZE);
        } else {
            int seq = 1;
            for (LeaveRequestDto request : section.requests()) {
                renderRequestRow(pdf, regular, seq++, request);
            }
        }
        pdf.gap(16);
    }

    private void renderRequestRow(PdfDocumentWriter pdf, PDFont regular, int seq, LeaveRequestDto request)
            throws IOException {
        float left = pdf.left();
        float right = pdf.right();

        pdf.ensureRoom(BODY_SIZE * 1.5f);
        String leftText = seq + ". " + nullSafe(request.leaveTypeNameTh(), request.leaveTypeCode())
            + "   " + dateRangeText(request.startDate(), request.endDate()) + timeSuffix(request);
        String rightText = LeaveDayMath.formatDuration(request.totalDays()) + "   " + statusLabel(request.status());
        pdf.textAt(regular, BODY_SIZE, left, leftText);
        pdf.textRight(regular, BODY_SIZE, right, rightText);
        pdf.newLine(BODY_SIZE);

        wrappedIndented(pdf, regular, SMALL_SIZE, 14f, "เหตุผล: " + nullSafe(request.reason(), "-"));

        String unpaidText = "หักเงิน: " + (nonZero(request.unpaidDays())
            ? "มี (" + LeaveDayMath.formatDuration(request.unpaidDays()) + ")"
            : "ไม่มี");
        String certText = request.attachmentId() != null ? "     ใบรับรองแพทย์: แนบแล้ว" : "";
        wrappedIndented(pdf, regular, SMALL_SIZE, 14f, unpaidText + certText);

        pdf.gap(6);
    }

    /** Wraps {@code value} to the printable width less {@code indent} and draws it left-indented,
     * paginating per line -- {@link PdfDocumentWriter#text} cannot do this itself (it always starts
     * at the bare left margin), so wrap+draw is composed by hand from {@code wrap}/{@code textAt}. */
    private void wrappedIndented(PdfDocumentWriter pdf, PDFont font, float size, float indent, String value)
            throws IOException {
        float x = pdf.left() + indent;
        float maxWidth = pdf.right() - x;
        for (String line : pdf.wrap(font, size, value, maxWidth)) {
            pdf.ensureRoom(size * 1.5f);
            pdf.textAt(font, size, x, line);
            pdf.newLine(size);
        }
    }

    private String periodLabel(int year, Integer month) {
        int buddhistYear = year + 543;
        if (month == null) {
            return "ปี " + buddhistYear;
        }
        return THAI_MONTHS_FULL[month - 1] + " " + buddhistYear;
    }

    private String dateRangeText(LocalDate start, LocalDate end) {
        if (start == null) {
            return "-";
        }
        if (end == null || start.equals(end)) {
            return thaiDate(start);
        }
        return thaiDate(start) + " - " + thaiDate(end);
    }

    // Whole-day requests (startTime == null) print no time at all -- see the brief's content table
    // ("เวลา ... omit entirely for whole-day").
    private String timeSuffix(LeaveRequestDto request) {
        if (request.startTime() == null) {
            return "";
        }
        String startText = request.startTime().toString();
        String startHhMm = startText.length() >= 5 ? startText.substring(0, 5) : startText;
        if (request.endTime() == null) {
            return " (" + startHhMm + " น.)";
        }
        String endText = request.endTime().toString();
        String endHhMm = endText.length() >= 5 ? endText.substring(0, 5) : endText;
        return " (" + startHhMm + "-" + endHhMm + " น.)";
    }

    private String statusLabel(String status) {
        return STATUS_LABELS_TH.getOrDefault(status, nullSafe(status, "-"));
    }

    private String thaiDate(LocalDate date) {
        if (date == null) {
            return "-";
        }
        return date.getDayOfMonth() + " " + THAI_MONTHS_ABBR[date.getMonthValue() - 1] + " " + (date.getYear() + 543);
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean nonZero(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Whether a quota row earns its line on the page. A row is dropped ONLY when it is completely
     * inert for this employee in this period -- no entitlement, nothing carried in, nothing used,
     * nothing pending -- which is a row that can tell the reader nothing at all (LEAVE_WITHOUT_PAY
     * is the standing example: a 0-day statutory quota that prints "0 วัน" four times).
     *
     * <p>Deliberately NOT filtered: a type the employee holds an entitlement for but has not used.
     * "ลาพักร้อน · 12 วัน · เหลือ 12 วัน" is the single most useful line on the page for a reader who
     * has taken none of it, and dropping unused entitlements would turn a leave STATEMENT into a
     * mere list of what already happened.
     *
     * <p>Also deliberately NOT filtered: event-driven types the reader may never qualify for
     * (MATERNITY/MILITARY/ORDINATION). Suppressing those needs a business rule this renderer has no
     * right to invent -- MATERNITY would have to key off gender, which the leave module does not
     * gate on ANYWHERE else and which adoption/surrogacy cases make wrong anyway. No existing
     * leave_type column separates them either: {@code once_per_employment} is TRUE for ORDINATION
     * alone, and {@code requires_attachment} is TRUE for SICK as well, so neither flag (nor the
     * pair) identifies that set. A display filter that hid an entitlement the employee legally holds
     * would be a policy decision wearing a formatting disguise. If HR wants them hidden, that is an
     * owner ruling plus a leave_type column, not a predicate here.
     */
    private static boolean isMeaningfulQuotaRow(LeaveBalanceDto balance) {
        return isPositive(balance.annualQuotaDays())
            || isPositive(balance.carriedInDays())
            || isPositive(balance.approvedDays())
            || isPositive(balance.pendingDays());
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
