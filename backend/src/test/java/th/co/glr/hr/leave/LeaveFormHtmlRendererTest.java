package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The ใบลา HTML replica (Chromium engine): assert the document is self-contained (embedded Sarabun +
 * logo, so Chromium fetches nothing), autofills the request's values, marks the right leave-type box,
 * and HTML-escapes free text. Pixel fidelity is a visual/manual concern; these pin the data contract.
 */
class LeaveFormHtmlRendererTest {
    private final LeaveFormHtmlRenderer renderer = new LeaveFormHtmlRenderer();

    private LeaveFormData sick() {
        return new LeaveFormData(
            "10050", "กวินนาถ อังกาบกิ่งแก้ว", "ก้อย",
            "จัดซื้อต่างประเทศ", "จัดซื้อ", "จัดซื้อ",
            "SICK", "ลาป่วย",
            LocalDate.parse("2026-09-08"),
            LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-07"),
            LocalTime.of(8, 30), LocalTime.of(17, 30),
            new BigDecimal("1.00"), "เจ็บขา",
            "12", "พระโขนง", "คลองเตย", "กรุงเทพ", "0917949655",
            new BigDecimal("11"), new BigDecimal("1"), new BigDecimal("7"), new BigDecimal("3"),
            null, null, null);
    }

    @Test
    void selfContainedWithEmbeddedFontAndLogo() {
        String html = renderer.render(sick());
        assertThat(html).contains("@font-face").contains("font-family:'Sarabun'");
        // Logo + fonts embedded as data URIs -> the document references no external URL.
        assertThat(html).contains("data:image/png;base64,");
        assertThat(html).contains("data:font/ttf;base64,");
        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(html).contains("ใบลาหยุด").contains("F-HR-020(01)");
    }

    @Test
    void autofillsTheRequestValues() {
        String html = renderer.render(sick());
        assertThat(html).contains("กวินนาถ อังกาบกิ่งแก้ว").contains("10050")
            .contains("จัดซื้อต่างประเทศ").contains("เจ็บขา")
            .contains("08:30").contains("17:30").contains("0917949655");
        // History figures present.
        assertThat(html).contains(">11<").contains(">7<");
    }

    private LeaveFormData approved() {
        return new LeaveFormData(
            "EMP009", "สมชาย ใจดี", "ชาย",
            "พนักงานขาย", "ทีมขาย", "ฝ่ายขาย",
            "PERSONAL", "ลากิจ",
            LocalDate.parse("2026-09-20"),
            LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-22"), null, null,
            new BigDecimal("1.00"), "ธุระ",
            null, null, null, null, "0800000000",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "APPROVED", "จินตนา หาญมนตรี", LocalDateTime.of(2026, 9, 21, 9, 15));
    }

    @Test
    void fillsApprovalBoxesWhenApproved() {
        String html = renderer.render(approved());
        // The ผู้อนุมัติ signature is the (dynamic) reports-to manager; the HR recorder is the fixed name.
        assertThat(html).contains("จินตนา หาญมนตรี").contains("ฟ้าใส อิฐรัตน์");
        assertThat(html).contains("2569").contains("09:15");
        // Three ticks on an APPROVED form: the leave type (ลากิจ) + เห็นควรอนุมัติ + อนุมัติ (HR).
        assertThat(html.split("✓", -1).length - 1).isEqualTo(3);
    }

    @Test
    void leavesApprovalBoxesBlankWhenPending() {
        // sick() has no decision -> only the leave-type box is ticked, approval boxes stay empty.
        assertThat(renderer.render(sick()).split("✓", -1).length - 1).isEqualTo(1);
    }

    @Test
    void marksTheMatchingLeaveTypeBoxOnly() {
        String html = renderer.render(sick());
        // Exactly one checked box (✓) for a SICK request.
        assertThat(html.split("✓", -1).length - 1).isEqualTo(1);
    }

    @Test
    void escapesFreeText() {
        LeaveFormData d = new LeaveFormData(
            "E1", "A <b>x</b>", "n", "p", "d", "dv", "PERSONAL", "ลากิจ",
            LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-07"),
            null, null, new BigDecimal("1"), "reason & <script>",
            null, null, null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, null);
        String html = renderer.render(d);
        assertThat(html).contains("reason &amp; &lt;script&gt;").doesNotContain("<script>");
        assertThat(html).contains("A &lt;b&gt;x&lt;/b&gt;");
    }
}
