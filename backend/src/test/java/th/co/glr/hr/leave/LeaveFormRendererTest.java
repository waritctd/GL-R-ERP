package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The ใบลา F-HR-020 renderer is a pure PDFBox writer -- these are content-free smoke tests that it
 * produces a valid, non-trivial PDF for the shapes that actually reach it (whole-day, sub-day, a
 * non-form leave type, and missing optional fields), without a database. Field-level correctness is
 * covered where the data is assembled ({@link LeaveSubmissionMailer}); a byte-level assertion on Thai
 * glyphs in a compressed PDF stream would be brittle and prove little.
 */
class LeaveFormRendererTest {
    private final LeaveFormRenderer renderer = new LeaveFormRenderer();

    private LeaveFormData wholeDay() {
        return new LeaveFormData(
            "EMP001", "กวินนาถ อังกาบกิ่งแก้ว", "ก้อย",
            "จัดซื้อต่างประเทศ", "จัดซื้อ", "จัดซื้อ",
            "SICK", "ลาป่วย",
            LocalDate.parse("2026-09-08"),
            LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-07"),
            null, null,
            new BigDecimal("1.00"), "เจ็บขา ทำงานที่บ้าน",
            "12", "พระโขนง", "คลองเตย", "กรุงเทพ", "0917949655",
            new BigDecimal("11"), new BigDecimal("1"), new BigDecimal("7"), new BigDecimal("3"),
            null, null, null);
    }

    @Test
    void rendersAValidPdfForAWholeDayRequest() {
        byte[] pdf = renderer.toPdf(wholeDay());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void rendersASubDayRequestWithTimes() {
        LeaveFormData subDay = new LeaveFormData(
            "EMP002", "สมชาย ใจดี", "ชาย",
            "ช่าง", "ผลิต", "โรงงาน",
            "PERSONAL", "ลากิจ",
            LocalDate.parse("2026-09-10"),
            LocalDate.parse("2026-09-11"), LocalDate.parse("2026-09-11"),
            LocalTime.of(8, 30), LocalTime.of(12, 30),
            new BigDecimal("0.5"), "ธุระส่วนตัว",
            null, null, null, null, "0812223333",
            new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, null);

        byte[] pdf = renderer.toPdf(subDay);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void rendersAMultiDaySubDaySpan() {
        // 28 ก.ย. 13:30 -> 1 ต.ค. 17:30 = 3.5 วัน: the date line binds each time to its own date and
        // the total prints "3 วัน 4 ชม." rather than a bare 3.5 with a contradictory 4-hour clause.
        LeaveFormData span = new LeaveFormData(
            "GLR-1001", "วริศรา จันทเดช", "พลอย",
            "พนักงาน", "ทีมขาย", "ฝ่ายขาย",
            "SICK", "ลาป่วย",
            LocalDate.parse("2026-09-25"),
            LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-01"),
            LocalTime.of(13, 30), LocalTime.of(17, 30),
            new BigDecimal("3.50"), "เส้นประสาทอักเสบ",
            "-", null, null, null, "0629400047",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, null);

        byte[] pdf = renderer.toPdf(span);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void rendersAnApprovedFormWithTheFilledDecisionBoxes() {
        LeaveFormData approved = new LeaveFormData(
            "EMP009", "สมชาย ใจดี", "ชาย",
            "พนักงานขาย", "ทีมขาย", "ฝ่ายขาย",
            "PERSONAL", "ลากิจ",
            LocalDate.parse("2026-09-20"),
            LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-22"), null, null,
            new BigDecimal("1.00"), "ธุระ",
            null, null, null, null, "0800000000",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "APPROVED", "จินตนา หาญมนตรี", LocalDateTime.of(2026, 9, 21, 9, 15));

        byte[] pdf = renderer.toPdf(approved);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void rendersANonFormLeaveTypeAndToleratesMissingOptionalFields() {
        // MATERNITY has no checkbox on the paper form (rendered as "อื่นๆ"); nulls in the contact
        // block and history must not throw.
        LeaveFormData maternity = new LeaveFormData(
            "EMP003", "สมหญิง รักดี", null,
            null, null, null,
            "MATERNITY", "ลาคลอดบุตร",
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-30"),
            null, null,
            new BigDecimal("90"), null,
            null, null, null, null, null,
            null, null, null, null,
            null, null, null);

        byte[] pdf = renderer.toPdf(maternity);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }
}
