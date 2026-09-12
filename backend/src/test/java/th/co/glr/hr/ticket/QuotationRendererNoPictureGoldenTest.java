package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

/**
 * GLA-75 (per-item pictures) regression gate: <b>a quotation whose items carry NO picture must
 * render exactly as it did before pictures existed.</b>
 *
 * <p>The golden files under {@code src/test/resources/quotation-golden/} were written by THIS test
 * against the renderer as it stood on {@code origin/develop} (61b44d60) BEFORE the picture code
 * was added — i.e. they are the old renderer's output, not the new one's. Each is a canonical
 * text dump of the rendered sheet: every row's height, every cell's type/value/style, every merged
 * region, every row break, the print area and print setup, and every picture's anchor + content
 * hash. The picture feature re-plumbed the row accounting that {@code fillItems},
 * {@code countEmittedRows} and {@code insertNoSplitPageBreaks} share; a mistake there moves a row,
 * a break or the footer, and shows up here as a one-line diff.
 *
 * <p>Five fixtures cover every layout branch: the native single page, the one-page-at-scale
 * branch, the paginated branch (with no-split breaks), the English form, and the legacy (non-v2)
 * wrappers' shape. Regenerate ONLY when a change to the no-picture output is intended:
 * {@code ./mvnw -Dtest=QuotationRendererNoPictureGoldenTest -Dquotation.golden.update=true test}.
 *
 * <p><b>Re-baselined 2026-09-12</b> for {@code single-page}/{@code one-page-scaled}/
 * {@code paginated}/{@code english} (not {@code legacy-shape}, which has no signature block): the
 * owner-reported A4 signature-row-width defect (3f80c23c, "ช่วยจัดตำแหน่ง ... ให้พอดีความกว้างหน้า
 * กระดาษ A4" — the four signature labels stopped ~65% across an otherwise full-width form) and the
 * related zero-sized-signature-anchor defect (same commit) deliberately change the signature
 * labels row, the names row, the date row, and the signature picture's anchor — nothing else. This
 * is an INTENTIONAL move of the pinned baseline forward to the post-fix renderer, not baseline
 * drift: it was confirmed by diffing every fixture's regenerated dump against its prior version and
 * finding changes confined to exactly those four regions in each. The XLS byte hashes below were
 * re-derived the same way from a real regenerated run (docker + backend/fonts/ licensed Thai
 * fonts), not copied from elsewhere.
 */
class QuotationRendererNoPictureGoldenTest {
    private static final String UPDATE_PROPERTY = "quotation.golden.update";
    private final QuotationRenderer renderer = new QuotationRenderer();

    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"})
    void aQuotationWithoutPicturesRendersExactlyAsBefore(String fixture) throws Exception {
        String dump = canonicalDump(renderer.toXls(model(fixture)));
        Path golden = Path.of("src/test/resources/quotation-golden", fixture + ".txt");
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.writeString(golden, dump, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(golden)).as("golden %s missing", golden).isTrue();
        String expected = Files.readString(golden, StandardCharsets.UTF_8);
        // The renderer's page-break and anchor arithmetic is LibreOffice's column unit, which is
        // the workbook font's widest digit AS THIS MACHINE'S fontconfig resolves it. The goldens
        // were written where the licensed Cordia New resolves (102 twips); a machine without it
        // (CI: the Thai fonts are gitignored, #666) legitimately computes different breaks. Skip
        // there rather than fail on an environment difference — the first line says which.
        Assumptions.assumeTrue(expected.lines().findFirst().orElse("").equals(dump.lines().findFirst().orElse("")),
            "golden written under a different LibreOffice column unit (" + expected.lines().findFirst().orElse("")
                + " vs " + dump.lines().findFirst().orElse("") + ") — skipping on this machine");
        assertThat(dump).as("no-picture render of fixture '%s' drifted from the pre-picture golden", fixture)
            .isEqualTo(expected);
    }

    /**
     * The stricter half: the XLS BYTES themselves. {@code toXls} is deterministic (two renders of
     * one model are byte-equal — probed on develop 61b44d60 before this pin was written), so the
     * SHA-256 of each fixture's XLS as rendered by the PRE-PICTURE renderer is pinned here. The
     * dump above says WHAT moved when this fails; this says whether ANYTHING did, including drawing
     * records and style tables the dump does not walk.
     *
     * <p>These hashes were produced on a scratch checkout of origin/develop 61b44d60, not by this
     * branch's code, so they cannot be a self-fulfilling render of the new renderer. Same
     * column-unit skip as above: the bytes depend on the fonts fontconfig resolves.
     */
    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"})
    void aQuotationWithoutPicturesRendersByteIdenticallyToBefore(String fixture) throws Exception {
        // Re-pinned 2026-09-12 for single-page/one-page-scaled/paginated/english: the owner-reported
        // A4 signature-row-width fix (3f80c23c) deliberately moves the signature labels/names/date
        // rows and the signature picture anchor -- see this class's own Javadoc. legacy-shape has
        // no signature block, so its hash is untouched from the pre-picture baseline.
        java.util.Map<String, String> preFeatureSha256 = java.util.Map.of(
            "single-page", "b1eb3532779ff32924fb47a924713a331dedbbad43a1e06aaf83045eec81b6a2",
            "one-page-scaled", "7bd39413de1df940753bccad20de0baff575cb73a403a0ad2f120cd9d611dd2b",
            "paginated", "50d979cb59c89188b9a113ba5d810d386cb2a31aac3666391aa8de9bb8f1b047",
            // Re-pinned on develop 80f2484e: #930 deliberately changed the English form's output.
            "english", "9e5d8eb285008332f3c128ec8743c21b8242c13aa50c19694c949fe6c04a04dd",
            "legacy-shape", "0eeb95aac63791149d8e230de304554126e91ee8ceca66c833cd8912fce4a6f2");
        byte[] xls = renderer.toXls(model(fixture));
        String expectedUnit = Files.readString(Path.of("src/test/resources/quotation-golden", fixture + ".txt"),
            StandardCharsets.UTF_8).lines().findFirst().orElse("");
        String actualUnit = canonicalDump(xls).lines().findFirst().orElse("");
        Assumptions.assumeTrue(expectedUnit.equals(actualUnit),
            "hashes pinned under a different LibreOffice column unit (" + expectedUnit + " vs " + actualUnit
                + ") — skipping on this machine");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xls)))
            .as("XLS bytes of no-picture fixture '%s' differ from the pre-picture renderer's", fixture)
            .isEqualTo(preFeatureSha256.get(fixture));
    }

    static QuotationRenderModel model(String fixture) {
        return switch (fixture) {
            case "single-page" -> v2(items(3, true), false);
            case "one-page-scaled" -> v2(items(6, true), false);
            case "paginated" -> v2(items(22, true), false);
            case "english" -> v2(items(2, false), true);
            case "legacy-shape" -> new QuotationRenderModel(
                LocalDate.of(2026, 7, 16), "QT-2026-0042", "P003", "D002", "Sales/สมชาย T.081",
                "Test Customer Co., Ltd.", "โทร. 02-000-0000", "Showroom", legacyItems(),
                List.of("1.x", "2.y", "3.z"), new Signatories(null, null, null, null, null), false);
            default -> throw new IllegalArgumentException(fixture);
        };
    }

    private static QuotationRenderModel v2(List<RenderItem> items, boolean english) {
        List<String> remarks = List.of(
            "1. วันที่รับจำนวน 10 กันยายน 2569", "2. มัดจำ 30% ของยอดรวม", "3. ส่วนที่เหลือเครดิต 30 วัน",
            "4. สินค้าจากไทย-สต็อก ส่งภายใน 30-45 วัน", "5. ราคานี้ยืนราคา 30 วัน",
            "6. หมายเหตุ", "7. โปรดตรวจสอบรายการ", "8. ขอบคุณที่ใช้บริการ");
        return new QuotationRenderModel(
            LocalDate.of(2026, 9, 10), "QT-2026-0099", "P003", "D002", "Sales/สมชาย ใจดี T.081-234-5678",
            "คุณลูกค้า   /   Test Customer Co., Ltd.   เลขที่ผู้เสียภาษี : 0105542000000",
            "โทร. 02-000-0000", "Showroom V2 Project", items, remarks,
            new Signatories("จินตนา", "จุฑาทิพ", "ผึ้ง", "สมหญิง ใจดี", signaturePng(), "image/png",
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)),
            true, english ? "EN" : "TH", english ? "USD" : "THB");
    }

    static List<RenderItem> items(int count, boolean headings) {
        List<RenderItem> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String heading = headings ? (i <= count / 2 ? "ห้องน้ำชั้น 1" : "ห้องครัว") : null;
            out.add(new RenderItem(heading,
                List.of("กระเบื้อง รุ่น Model " + i + " สี White ผิว Matte",
                    "ขนาด 60x60 cm. หนา 10 มม.",
                    "พื้นที่ 12.50 ตร.ม. ÷ 0.36 = 35 แผ่น + เผื่อเสีย 5% = 37 แผ่น (" + i + " กล่อง)"),
                BigDecimal.valueOf(37), "แผ่น", new BigDecimal("450.00"), i % 2 == 0 ? "10%" : "Net",
                new BigDecimal("405.00"), new BigDecimal("14985.00")));
        }
        return out;
    }

    private static List<RenderItem> legacyItems() {
        return List.of(
            new RenderItem(null, List.of("กระเบื้อง รุ่น Marble สี Grey ขนาด 60x60 cm."), BigDecimal.TEN, "แผ่น",
                new BigDecimal("580.00"), "Net", new BigDecimal("580.00"), new BigDecimal("5800.00")),
            new RenderItem(null, List.of("กระเบื้อง รุ่น Oak ขนาด 20x120 cm."), BigDecimal.ONE, "แผ่น",
                new BigDecimal("300.00"), "Net", new BigDecimal("300.00"), new BigDecimal("300.00")));
    }

    static byte[] signaturePng() {
        BufferedImage img = new BufferedImage(300, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 30, 120));
        g.setStroke(new BasicStroke(5f));
        g.drawLine(20, 80, 280, 40);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Everything about the rendered sheet that could move if the row accounting drifted. */
    static String canonicalDump(byte[] xls) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sh = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            PrintSetup ps = sh.getPrintSetup();
            sb.append("columnUnitTwips=").append(th.co.glr.hr.common.sheet.LibreOfficeMetrics.charWidthTwips(wb))
                .append('\n');
            sb.append("printArea=").append(wb.getPrintArea(wb.getSheetIndex(sh))).append('\n');
            sb.append("fitToPage=").append(sh.getFitToPage()).append(" fitW=").append(ps.getFitWidth())
                .append(" fitH=").append(ps.getFitHeight()).append(" scale=").append(ps.getScale())
                .append(" landscape=").append(ps.getLandscape()).append('\n');
            sb.append("repeatingRows=").append(sh.getRepeatingRows()).append('\n');
            sb.append("footer=").append(sh.getFooter().getCenter()).append('\n');
            sb.append("rowBreaks=").append(java.util.Arrays.toString(sh.getRowBreaks())).append('\n');
            for (int c = 0; c <= 10; c++) {
                sb.append("col ").append(c).append(" w=").append(sh.getColumnWidth(c)).append('\n');
            }
            for (int r = 0; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) {
                    continue;
                }
                sb.append("row ").append(r).append(" h=").append(row.getHeight())
                    .append(row.getZeroHeight() ? " hidden" : "").append('\n');
                for (Cell cell : row) {
                    CellStyle st = cell.getCellStyle();
                    sb.append("  c").append(cell.getColumnIndex()).append(' ').append(cell.getCellType())
                        .append(" style=").append(st.getIndex())
                        .append(" b=").append(st.getBorderTop()).append('/').append(st.getBorderBottom())
                        .append('/').append(st.getBorderLeft()).append('/').append(st.getBorderRight())
                        .append(" font=").append(st.getFontIndex()).append(" wrap=").append(st.getWrapText())
                        .append(" al=").append(st.getAlignment()).append(" : ");
                    if (cell.getCellType() == CellType.STRING) {
                        sb.append('"').append(cell.getStringCellValue()).append('"');
                    } else if (cell.getCellType() == CellType.NUMERIC) {
                        sb.append(cell.getNumericCellValue());
                    } else if (cell.getCellType() == CellType.FORMULA) {
                        sb.append('=').append(cell.getCellFormula());
                    }
                    sb.append('\n');
                }
            }
            List<String> merges = new ArrayList<>();
            for (CellRangeAddress m : sh.getMergedRegions()) {
                merges.add(m.formatAsString());
            }
            merges.sort(null);
            sb.append("merges=").append(merges).append('\n');
            HSSFSheet hssf = (HSSFSheet) sh;
            if (hssf.getDrawingPatriarch() != null) {
                for (var shape : hssf.getDrawingPatriarch().getChildren()) {
                    if (shape instanceof HSSFPicture pic) {
                        var a = pic.getClientAnchor();
                        sb.append("picture r").append(a.getRow1()).append('+').append(a.getDy1())
                            .append(" c").append(a.getCol1()).append('+').append(a.getDx1())
                            .append(" -> r").append(a.getRow2()).append('+').append(a.getDy2())
                            .append(" c").append(a.getCol2()).append('+').append(a.getDx2())
                            .append(" md5=").append(md5(pic.getPictureData().getData())).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }
}
