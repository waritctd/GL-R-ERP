package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importrequest.ImportSheetModels.ImportSheetException;
import th.co.glr.hr.importrequest.ImportSheetModels.ParsedSheet;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetBlock;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetLine;

class ImportSheetParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private static ParsedSheet parse(ImportSheetTestBook book) throws IOException {
        return ImportSheetParser.parse(new ByteArrayInputStream(book.bytes()), TODAY);
    }

    @Test
    void aContinuationRowNamingAnotherBrandMakesAMixedBlock_andACancelledGrInARemarkIsNoReceipt() throws IOException {
        SheetBlock mixed = parse(new ImportSheetTestBook()
            .row("IR NO.", 69461, "ยี่ห้อ", "Bode", "รายชื่อสินค้าและจำนวน", "x = 1 pcs")
            .row("ยี่ห้อ", "Vola", "รายชื่อสินค้าและจำนวน", "y = 2 pcs")).blocks().get(0);
        assertThat(mixed.brands()).containsExactly("Bode", "Vola");

        SheetBlock cancelled = parse(new ImportSheetTestBook()
            .row("IR NO.", 69462, "ยี่ห้อ", "Bode", "รายชื่อสินค้าและจำนวน", "x = 1 pcs", "วันที่ของถึงจริง", LocalDate.of(2026, 10, 1),
                "หมายเหตุ", "ยกเลิก GR-2601-0001")).blocks().get(0);
        assertThat(cancelled.facts().columns()).doesNotContainKey("gr_number");
        assertThat(cancelled.facts().step()).isNotEqualTo(ImportRequestStep.RECEIVED);
    }

    @Test
    void aBlockIsOneShipment_withContinuationLines_mergedCells_aBanner_andNoCostRead() throws IOException {
        ParsedSheet sheet = parse(new ImportSheetTestBook()
            .row("Proforma invoice no", "September (35/40)")
            .blank()
            .row("IR NO.", 69401, "ยี่ห้อ", "Bode", "Sale", "S7", "PROJECT", "บ้านทดสอบ", "รายชื่อสินค้าและจำนวน", "Stone Grey 60x60 = 1,200 pcs.",
                "Proforma invoice no", "PI-1\nPI-2", "ชื่อพาหนะ", "TEST VESSEL 12S", "วันที่สั่งของ", LocalDate.of(2026, 9, 1),
                "วันที่เรือลำแรกออกจริง", LocalDate.of(2026, 10, 1), "วันที่คาดว่าจะถึง ETA bkk", "14/11/2026 แหลมฉบัง",
                ImportSheetTestBook.COST, 999.5)
            .row("รายชื่อสินค้าและจำนวน", "Stone White 60x60 110 pcs", "PROJECT", "Shipment 2")
            .row("IR NO.", "69401-1", "รายชื่อสินค้าและจำนวน", "Test panels")
            .merge("Sale", 4, 6)
            .blank()
            .row("IR NO.", 69402, "ยี่ห้อ", "Vola", "รายชื่อสินค้าและจำนวน", "Tap = 3 set", "ชื่อพาหนะ", "Fedex : 100000000001",
                "วันที่ออกของ", LocalDate.of(2026, 10, 20), "สินค้าเข้าตรงตามInvoice(เลขที่ใบตรวจรับ)", "gr-2601-0099 ของเข้ามาครบตามอินวอยด์"));

        assertThat(sheet.blocks()).hasSize(2);
        SheetBlock first = sheet.blocks().get(0);
        assertThat(first.backfillKey()).isEqualTo("IR69401#1");
        assertThat(first.irs()).extracting(i -> i.docNumber()).containsExactly("IR69401", "IR69401-1");
        assertThat(first.irs().get(1).saleText()).isEqualTo("S7"); // from the merged cell
        assertThat(first.proformas()).containsExactly("PI-1", "PI-2");
        assertThat(first.facts().step()).isEqualTo(ImportRequestStep.IN_TRANSIT);
        assertThat(first.facts().transportMode()).isEqualTo("SEA");
        assertThat(first.facts().columns()).containsEntry("eta_on", LocalDate.of(2026, 11, 14)).containsEntry("port", "แหลมฉบัง");
        assertThat(first.facts().columns().toString()).doesNotContain("999");

        SheetLine a = first.irs().get(0).lines().get(0);
        assertThat(a.code()).isEqualTo("Stone Grey 60x60");
        assertThat(a.qty()).isEqualByComparingTo("1200");
        assertThat(a.unit()).isEqualTo("pcs");
        SheetLine b = first.irs().get(0).lines().get(1);
        assertThat(b.qty()).isEqualByComparingTo("110");
        assertThat(b.note()).isEqualTo("Shipment 2");
        SheetLine noQty = first.irs().get(1).lines().get(0);
        assertThat(noQty.qty()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(noQty.warning()).isNotNull();

        SheetBlock second = sheet.blocks().get(1);
        assertThat(second.facts().step()).isEqualTo(ImportRequestStep.RECEIVED);
        assertThat(second.facts().transportMode()).isEqualTo("AIR");
        assertThat(second.facts().columns()).containsEntry("gr_number", "GR-2601-0099")
            .containsEntry("complete_per_invoice", true).containsEntry("received_on", LocalDate.of(2026, 10, 20));
    }

    @Test
    void textDates_followTheYearRules_andUnreadableTextGoesToTheRoundNote() throws IOException {
        assertThat(ImportSheetParser.parseDateText("cf 13/11/26", TODAY)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(ImportSheetParser.parseDateText("5/12/2569", TODAY)).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(ImportSheetParser.parseDateText("1/9/260", TODAY)).isNull();
        assertThat(ImportSheetParser.parseDateText("31/2/2026", TODAY)).isNull();

        SheetBlock b = parse(new ImportSheetTestBook().row("IR NO.", 69403, "รายชื่อสินค้าและจำนวน", "Tile = 1 box",
            "วันที่ตกลงกันว่าของพร้อม", "รอผลิตปลายเดือน")).blocks().get(0);
        assertThat(b.facts().step()).isEqualTo(ImportRequestStep.CONTACTED);
        assertThat((String) b.facts().columns().get("note")).contains("รอผลิตปลายเดือน");
        assertThat(b.warnings()).isNotEmpty();
    }

    @Test
    void aReceivedRoundAlwaysHasADate_andABadNumberBlocksOnlyItsBlock() throws IOException {
        ParsedSheet sheet = parse(new ImportSheetTestBook()
            .row("IR NO.", 69404, "รายชื่อสินค้าและจำนวน", "Tile = 1 box", "สินค้าเข้าตรงตามInvoice(เลขที่ใบตรวจรับ)", "GR-2601-0098",
                "วันที่คาดว่าจะถึง ETA bkk", LocalDate.of(2026, 10, 3))
            .blank()
            .row("IR NO.", "6947A", "รายชื่อสินค้าและจำนวน", "Tile = 1 box"));
        assertThat(sheet.blocks().get(0).facts().columns()).containsEntry("received_on", LocalDate.of(2026, 10, 3));
        assertThat(sheet.blocks().get(0).blockedReason()).isNull();
        assertThat(sheet.blocks().get(1).blockedReason()).contains("6947A");
    }

    @Test
    void aSheetMissingAHeaderIsRefused() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("IR NO.");
            wb.write(out);
            assertThatThrownBy(() -> ImportSheetParser.parse(new ByteArrayInputStream(out.toByteArray()), TODAY))
                .isInstanceOf(ImportSheetException.class).hasMessageContaining("ยี่ห้อ");
        }
    }

    @Test
    void purposeSuggestions() {
        assertThat(ImportSheetPlanner.suggestPurpose("Samples showroom + Test")).isEqualTo("SHOWROOM");
        assertThat(ImportSheetPlanner.suggestPurpose("ตัวอย่างทีมขาย")).isEqualTo("SAMPLE");
        assertThat(ImportSheetPlanner.suggestPurpose("Aug-25 order")).isEqualTo("STOCK");
        assertThat(ImportSheetPlanner.suggestPurpose("บ้านคุณทดสอบ")).isEqualTo("CUSTOMER");
    }
}
