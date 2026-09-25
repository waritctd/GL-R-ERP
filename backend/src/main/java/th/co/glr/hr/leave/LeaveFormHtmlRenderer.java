package th.co.glr.hr.leave;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Builds a self-contained HTML document for the ใบลาหยุด (F-HR-020) leave form, laid out to mirror the
 * paper/Excel form, for headless-Chromium PDF rendering ({@link th.co.glr.hr.common.ChromiumPdfPrinter},
 * the same engine the direct-deal quotation uses). Owner request 2026-09-25: the attached form must
 * look like the reference form and carry the GL&R logo.
 *
 * <p>Why not render the reference {@code .xls} through {@code SheetHtmlRenderer} (as the quotation does
 * its POI sheet): that sheet's logo is a ~7.4&nbsp;MB EMF (Windows metafile) which Chromium cannot
 * render, and its fillable fields are dotted runs inside single text cells rather than structured
 * value cells, so autofill would be brittle and the logo would not appear. A hand-built HTML replica
 * renders the real PNG logo, embeds Sarabun (so Thai is correct regardless of host fonts), and fills
 * every field deterministically.
 *
 * <p><b>Layout:</b> a single A4 page — the bordered box fills the full printable area (footer pinned
 * to the bottom), and every field row is flex with {@code flex-wrap}, so a long value grows into the
 * available width and never pushes the box past the right margin (which had clipped the box's right
 * border). Everything is inlined as {@code data:} URIs (logo + Sarabun regular/bold) so Chromium
 * fetches nothing. Pure renderer: all values come off {@link LeaveFormData}; no query or leave-day
 * math here.
 */
@Component
public class LeaveFormHtmlRenderer {
    private static final String[] THAI_MONTHS_ABBR = {
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    };

    private final String fontRegularBase64 = base64("fonts/Sarabun-Regular.ttf");
    private final String fontBoldBase64 = base64("fonts/Sarabun-Bold.ttf");
    private final String logoDataUri = logoDataUri();

    public String render(LeaveFormData d) {
        String code = d.leaveTypeCode() == null ? "" : d.leaveTypeCode();
        boolean isPersonal = "PERSONAL".equals(code);
        boolean isSick = "SICK".equals(code);
        boolean isVacation = "VACATION".equals(code);
        boolean isOther = !(isPersonal || isSick || isVacation);

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"th\"><head><meta charset=\"utf-8\"><style>");
        h.append(css());
        h.append("</style></head><body><div class=\"page\">");

        // Header: logo + title + filed date
        h.append("<div class=\"hdr\">");
        if (!logoDataUri.isEmpty()) {
            h.append("<img class=\"logo\" src=\"").append(logoDataUri).append("\" alt=\"GL&amp;R\">");
        }
        h.append("<div class=\"title\">ใบลาหยุด</div>");
        h.append("<div class=\"filed\">วันที่ ").append(box(thaiDate(d.filedDate()))).append("</div>");
        h.append("</div>");

        h.append("<div class=\"body\">");

        // Identity
        h.append(row("ชื่อ-สกุล", fill(esc(d.employeeName())), "รหัสพนักงาน", fill(esc(d.employeeCode()))));
        h.append(row("ตำแหน่ง", fill(esc(d.positionTh())), "แผนก", fill(esc(d.departmentTh()))));
        h.append("<div class=\"line\"><span class=\"lbl\">ฝ่าย</span>").append(fill(esc(d.divisionTh()))).append("</div>");

        // Leave type
        h.append("<div class=\"line nogrow\"><span class=\"lbl\">มีความประสงค์ขอ</span>")
            .append("<span class=\"opt\">").append(cb(isPersonal)).append(" ลากิจ</span>")
            .append("<span class=\"opt\">").append(cb(isSick)).append(" ลาป่วย</span>")
            .append("<span class=\"opt\">").append(cb(isVacation)).append(" ลาพักร้อน</span>");
        if (isOther) {
            h.append("<span class=\"opt\">").append(cb(true)).append(" อื่นๆ</span>").append(fill(esc(d.leaveTypeNameTh())));
        }
        h.append("</div>");

        // Dates / times
        h.append("<div class=\"line nogrow\"><span class=\"lbl\">ระหว่างวันที่</span>").append(box(thaiDate(d.startDate())))
            .append("<span class=\"lbl\">ถึงวันที่</span>").append(box(thaiDate(d.endDate())))
            .append("<span class=\"lbl\">ตั้งแต่เวลา</span>").append(box(timeOrBlank(d.startTime())))
            .append("<span class=\"lbl\">ถึงเวลา</span>").append(box(timeOrBlank(d.endTime())))
            .append("</div>");
        h.append("<div class=\"line nogrow\"><span class=\"lbl\">รวมวันลา</span>").append(box(days(d.totalDays())))
            .append("<span class=\"unit\">วัน</span><span class=\"lbl\">รวมเวลา</span>").append(box(hours(d)))
            .append("<span class=\"unit\">ชั่วโมง</span></div>");

        // Reason
        h.append("<div class=\"line\"><span class=\"lbl\">เหตุผลในการลา</span>").append(fill(esc(d.reason()))).append("</div>");

        // Contact during leave
        h.append("<div class=\"line\"><span class=\"lbl\">ระหว่างลาติดต่อได้ที่ เลขที่</span>").append(fill(esc(d.contactHouseNo())))
            .append("<span class=\"lbl\">ตำบล/แขวง</span>").append(fill(esc(d.contactSubdistrict())))
            .append("<span class=\"lbl\">อำเภอ/เขต</span>").append(fill(esc(d.contactDistrict()))).append("</div>");
        h.append("<div class=\"line\"><span class=\"lbl\">จังหวัด</span>").append(fill(esc(d.contactProvince())))
            .append("<span class=\"lbl\">โทรศัพท์</span>").append(fill(esc(d.contactPhone()))).append("</div>");

        // History + employee signature
        h.append("<div class=\"hist\">");
        h.append("<div class=\"histL\">");
        h.append("<div class=\"bold\">ประวัติการลาในรอบปีนี้</div>");
        h.append("<div>• ลาหยุดงานมาแล้วรวม ").append(box(days(d.usedTotalDays()))).append(" วัน</div>");
        h.append("<div>• ลากิจ ").append(box(days(d.usedPersonalDays()))).append(" วัน &nbsp; • ลาป่วย ")
            .append(box(days(d.usedSickDays()))).append(" วัน &nbsp; • พักร้อน ")
            .append(box(days(d.usedVacationDays()))).append(" วัน</div>");
        h.append("</div>");
        h.append("<div class=\"sig\">")
            .append("<div>ลงชื่อ ").append(dots()).append("</div>")
            .append("<div>( ").append(esc(nn(d.employeeName()))).append(" )</div>")
            .append("<div>พนักงานผู้ยื่นใบลา</div>")
            .append("</div>");
        h.append("</div>");

        // Supervisor opinion
        h.append("<div class=\"apprv\">");
        h.append("<div class=\"bold\">ความเห็นผู้บังคับบัญชา</div>");
        h.append("<div class=\"line\"><span class=\"opt\">").append(cb(false)).append(" เห็นควรอนุมัติ</span>")
            .append("<span class=\"opt\">").append(cb(false)).append(" เห็นควรไม่อนุมัติ เพราะ</span>").append(fill("")).append("</div>");
        h.append("<div class=\"line end\"><span class=\"lbl\">ลงชื่อ</span>").append(dots())
            .append("<span class=\"lbl\">ผู้อนุมัติ</span><span class=\"lbl\">วันที่</span>").append(dots()).append("</div>");
        h.append("</div>");

        // HR admin box
        h.append("<div class=\"apprv\">");
        h.append("<div class=\"bold\">สำหรับฝ่ายทรัพยากรบุคคลและธุรการ</div>");
        h.append("<div class=\"line\"><span class=\"opt\">").append(cb(false)).append(" อนุมัติ</span>")
            .append("<span class=\"opt\">").append(cb(false)).append(" ไม่อนุมัติ</span>")
            .append("<span class=\"lbl\">ได้รับเอกสารวันที่</span>").append(fill(""))
            .append("<span class=\"lbl\">เวลา</span>").append(fill("")).append("</div>");
        h.append("<div class=\"line\"><span class=\"lbl\">• บันทึกลงเครื่องฯ แล้วเมื่อ</span>").append(fill(""))
            .append("<span class=\"lbl\">ลงชื่อ</span>").append(fill("")).append("<span class=\"lbl\">ผู้รับ/ผู้บันทึก</span></div>");
        h.append("</div>");

        h.append("</div>"); // .body

        // Footer (pinned to the bottom of the A4 page)
        h.append("<div class=\"foot\"><span>หมายเหตุ การลาทุกประเภทจะต้องได้รับอนุมัติจากผู้มีอำนาจก่อนจึงจะหยุดงานได้</span>")
            .append("<span class=\"code\">F-HR-020(01)</span></div>");

        h.append("</div></body></html>");
        return h.toString();
    }

    private String css() {
        return "@font-face{font-family:'Sarabun';font-weight:400;src:url(data:font/ttf;base64," + fontRegularBase64 + ") format('truetype');}"
            + "@font-face{font-family:'Sarabun';font-weight:700;src:url(data:font/ttf;base64," + fontBoldBase64 + ") format('truetype');}"
            // A4 WIDTH with a fixed height sized to the form (~161 mm of content + 8 mm margins), so the
            // PDF is a single page snug to the content with no awkward blank A4 tail. A valid two-length
            // size is required — `size: <w> auto` is invalid CSS and silently falls back to the default
            // page. Chromium honours this because ChromiumPdfPrinter prints with preferCSSPageSize=true.
            + "@page{size:210mm 180mm;margin:8mm;}"
            + "*{box-sizing:border-box;}"
            + "body{margin:0;font-family:'Sarabun',sans-serif;font-size:12.5px;color:#000;}"
            // The bordered box wraps its content (no forced full-page height).
            + ".page{border:1.5px solid #000;padding:12px 16px;}"
            + ".hdr{display:flex;align-items:center;gap:14px;border-bottom:1.5px solid #000;padding-bottom:8px;margin-bottom:10px;}"
            + ".logo{height:48px;width:auto;}"
            + ".title{flex:1;text-align:center;font-weight:700;font-size:23px;letter-spacing:1px;}"
            + ".filed{font-size:12px;white-space:nowrap;}"
            // Every field row is a wrapping flex line, so a long value grows into the free width and
            // NEVER overflows the box (which used to clip the right border).
            + ".line{display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 8px;margin:7px 0;}"
            + ".row{display:flex;flex-wrap:wrap;gap:4px 20px;margin:7px 0;}"
            + ".cell{flex:1 1 240px;display:flex;align-items:baseline;gap:6px;min-width:0;}"
            + ".lbl{font-weight:600;white-space:nowrap;}"
            + ".opt{white-space:nowrap;}"
            + ".unit{white-space:nowrap;}"
            // A fill grows to consume the remaining width of its line; underlined dotted.
            + ".v{flex:1 1 90px;border-bottom:1px dotted #000;min-height:1.25em;padding:0 6px;text-align:center;}"
            + ".cell .v{flex:1 1 60px;}"
            // Fixed-value boxes (dates/times/counts) stay compact and never grow/shrink.
            + ".vb{flex:0 0 auto;border-bottom:1px solid #000;min-width:78px;padding:0 8px;text-align:center;font-weight:600;white-space:nowrap;}"
            // A blank signature/date underline: fixed-ish, may shrink but not grow.
            + ".dots{flex:0 1 180px;border-bottom:1px dotted #000;min-height:1.25em;}"
            + ".line.end{justify-content:flex-end;}"
            + ".cb{display:inline-block;width:13px;height:13px;border:1.2px solid #000;text-align:center;line-height:11px;font-size:11px;}"
            + ".hist{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:flex-end;gap:12px;margin-top:10px;border-top:1px solid #000;padding-top:8px;}"
            + ".histL div{margin:3px 0;}"
            + ".histL .vb{min-width:56px;}"
            + ".bold{font-weight:700;margin-bottom:2px;}"
            + ".sig{flex:0 1 auto;min-width:220px;text-align:center;}"
            + ".sig>div{margin:2px 0;}"
            + ".apprv{margin-top:10px;border:1px solid #000;padding:8px 12px;}"
            // Footer flows right after the content (page height tracks content — see @page).
            + ".foot{margin-top:10px;display:flex;justify-content:space-between;gap:10px;font-size:11px;border-top:1.5px solid #000;padding-top:6px;}"
            + ".foot .code{font-weight:700;white-space:nowrap;}";
    }

    private String row(String l1, String v1, String l2, String v2) {
        return "<div class=\"row\"><span class=\"cell\"><span class=\"lbl\">" + l1 + "</span>" + v1 + "</span>"
            + "<span class=\"cell\"><span class=\"lbl\">" + l2 + "</span>" + v2 + "</span></div>";
    }

    private String fill(String value) {
        return "<span class=\"v\">" + (value == null || value.isBlank() ? "&nbsp;" : value) + "</span>";
    }

    private String box(String value) {
        return "<span class=\"vb\">" + (value == null || value.isBlank() ? "&nbsp;" : value) + "</span>";
    }

    private String dots() {
        return "<span class=\"dots\">&nbsp;</span>";
    }

    private String cb(boolean checked) {
        return "<span class=\"cb\">" + (checked ? "✓" : "&nbsp;") + "</span>";
    }

    private String timeOrBlank(LocalTime t) {
        return t == null ? "" : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private String hours(LeaveFormData d) {
        if (d.startTime() == null || d.endTime() == null) {
            return "";
        }
        long minutes = Duration.between(d.startTime(), d.endTime()).toMinutes();
        if (minutes <= 0) {
            return "";
        }
        return stripZeros(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60)));
    }

    private String days(BigDecimal value) {
        return value == null ? "0" : stripZeros(value);
    }

    private String stripZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String thaiDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.getDayOfMonth() + " " + THAI_MONTHS_ABBR[date.getMonthValue() - 1] + " " + (date.getYear() + 543);
    }

    private String nn(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private String esc(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String logoDataUri() {
        byte[] bytes = readResource("static/brand/glr-logo.png");
        return bytes.length == 0 ? "" : "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String base64(String resource) {
        return Base64.getEncoder().encodeToString(readResource(resource));
    }

    private byte[] readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
