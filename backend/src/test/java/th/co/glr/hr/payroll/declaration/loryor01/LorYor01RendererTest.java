package th.co.glr.hr.payroll.declaration.loryor01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.LorYor01Address;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.MaritalState;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.SpousalStatus;

/** Guards the one promise this feature makes: the generated ล.ย.01 is the Revenue Department's own document
 * with the employee's data in it — not a replica, not a shifted copy, and not a page with values in the wrong
 * boxes. */
class LorYor01RendererTest {

    /** Every datum in the right named box, บาท comma-grouped as the template's own script does. */
    @Test
    void theFieldMappingIsExactlyThis() {
        assertThat(LorYor01Renderer.textFieldValues(fullyPopulated())).containsExactly(
                entry("Text1.1", "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด"),
                entry("Text1.2", "1234567890123"),
                entry("Text1.3", "สมชาย"),
                entry("Text1.4", "ใจดีมีสุข"),
                entry("Text1.5", "อาคารเอ็มไพร์ทาวเวอร์"),
                entry("Text1.6", "1204"),
                entry("Text1.7", "12"),
                entry("Text1.8", "หมู่บ้านสีวลี"),
                entry("Text1.9", "195/8"),
                entry("Text1.10", "4"),
                entry("Text1.11", "ซอยสาทร 12"),
                entry("Text1.12", "แยกนราธิวาส"),
                entry("Text1.13", "ถนนสาทรใต้"),
                entry("Text1.14", "แขวงยานนาวา"),
                entry("Text1.15", "เขตสาทร"),
                entry("Text1.16", "กรุงเทพมหานคร"),
                entry("Text1.17", "10120"),
                entry("Text1.18", "3"),          // ข้อ 3 จำนวนบุตรรวม
                entry("Text1.19", "3"),
                entry("Text1.20", "90,000"),
                entry("Text1.22", "2"),
                entry("Text1.21", "120,000"),
                entry("Text1.23", "60,000"),  // ข้อ 4 ของผู้มีเงินได้ — บิดา+มารดา
                entry("Text1.24", "30,000"),  // ข้อ 4 ของคู่สมรส — บิดาเท่านั้น
                entry("Text1.25", "1"),          // ข้อ 5
                entry("Text1.26", "60,000"),
                entry("Text1.27", "15,000"),  // ข้อ 6
                entry("Text1.28", "100,000"), // ข้อ 7
                entry("Text1.29", "25,000"),  // ข้อ 8
                entry("Text1.30", "45,000"),  // ข้อ 9 — บาท only; the 50 satang print in their own cell
                entry("Text1.31", "300,000"), // ข้อ 10
                entry("Text1.32", "บลจ. กสิกรไทย จำกัด"),
                entry("Text1.35", "100,000"), // ข้อ 12 — ข้อ 11 (LTF) omitted, nothing declared
                entry("Text1.36", "9,000"),   // ข้อ 13
                entry("Text1.37", "20,000"),  // ข้อ 14
                entry("Text1.38", "มูลนิธิรามาธิบดี"),
                entry("Text1.39", "5,000"));
    }

    /** สตางค์ are printed into the สตางค์ column, not tacked onto the บาท figure. The amount box is two printed
     * cells. Splitting them is what makes the two printed columns mean what they say. */
    @Test
    void satangArePrintedSeparatelyFromBaht() {
        assertThat(LorYor01Renderer.satangOf(new BigDecimal("45000.50"))).isEqualTo("50");
        assertThat(LorYor01Renderer.satangOf(new BigDecimal("45000.05"))).isEqualTo("05");
        // A whole-baht amount still shows "00": the box is a currency field, and a blank satang cell beside a filled
        // baht cell reads as an unfinished form.
        assertThat(LorYor01Renderer.satangOf(new BigDecimal("90000"))).isEqualTo("00");
        assertThat(LorYor01Renderer.satangOf(BigDecimal.ZERO)).isEqualTo("00");
        // No amount at all means no mark — a blank row stays blank.
        assertThat(LorYor01Renderer.satangOf(null)).isNull();
    }

    // -----------------------------------------------------------------------------------------

    private static LorYor01FormData fullyPopulated() {
        return new LorYor01FormData(
                "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด",
                LocalDate.of(2026, 8, 8),
                "1234567890123",
                "สมชาย",
                "ใจดีมีสุข",
                new LorYor01Address("อาคารเอ็มไพร์ทาวเวอร์", "1204", "12", "หมู่บ้านสีวลี",
                        "195/8", "4", "ซอยสาทร 12", "แยกนราธิวาส", "ถนนสาทรใต้",
                        "แขวงยานนาวา", "เขตสาทร", "กรุงเทพมหานคร", "10120"),
                MaritalState.MARRIED,
                SpousalStatus.MARRIED_WHOLE_YEAR,
                Boolean.FALSE,
                3, 3, new BigDecimal("90000"), 2, new BigDecimal("120000"),
                true, true, new BigDecimal("60000"),
                true, false, new BigDecimal("30000"),
                1, new BigDecimal("60000"),
                true, true, false, true, new BigDecimal("15000"),
                new BigDecimal("100000"),
                new BigDecimal("25000"),
                new BigDecimal("45000.50"),
                new BigDecimal("300000"), "บลจ. กสิกรไทย จำกัด",
                null, null,
                new BigDecimal("100000"),
                new BigDecimal("9000"),
                new BigDecimal("20000"),
                "มูลนิธิรามาธิบดี", new BigDecimal("5000"));
    }
}
