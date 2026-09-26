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
 *
 * <p><b>Re-baselined 2026-09-14</b> for ALL FIVE fixtures (Opus review of the Thai-address fix,
 * PR #966): {@code QuotationRenderer#PHONE_ROW} (B6) is now written with
 * {@code setStrShrinkToFit} instead of plain {@code setStr} — the merged B6:G6 cell clips overflow
 * rather than wrapping, and the line now regularly carries an address AND a phone number folded
 * together (see {@code DealQuotationRenderAdapter}), which a long address can overrun. Creating
 * that ONE extra {@code CellStyle} shifts every style index the renderer allocates AFTER it by
 * exactly +1 for the rest of that render — confirmed by diffing every regenerated fixture against
 * its prior version: every diff line is EITHER the B6 cell's own style index (a distinct style,
 * not the previously-reused shared one) OR a same-content cell whose style index moved by +1;
 * nothing else (no row height, merge, border, font, page break, or text) changed anywhere in any
 * of the five fixtures. None of these fixtures' phoneLine text itself changed (all five keep the
 * pre-existing "โทร. 02-000-0000" — no address in any of them), which is exactly why this is pure
 * style-table churn and not a content regression.
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
        //
        // Re-pinned AGAIN 2026-09-14 for ALL FIVE (this class's own Javadoc, "Re-baselined
        // 2026-09-14"): PHONE_ROW (B6) now writes with setStrShrinkToFit instead of plain setStr,
        // guarding against the address+phone line now printed there overrunning the merged cell.
        // Every one of these five diffs is a style-table index shift only (one new CellStyle
        // created earlier in the render bumps every later-created style's index by +1) -- no
        // fixture's phoneLine TEXT changed (none of these fixtures carry a customer address).
        //
        // Re-pinned AGAIN 2026-09-14, owner feedback #3 (pieces-per-box wrap fix): every fixture's
        // item calculation line ends "...= 37 แผ่น (N กล่อง)" -- e.g. 71 String.length() chars for
        // the "(1 กล่อง)" case but only 61 VISIBLE ones (see QuotationRenderer#visibleLength's
        // Javadoc), so it used to wrap into a head row ("...= 37 แผ่น") plus a continuation row
        // ("(N กล่อง)") under the OLD String.length()-based budget check (62), and now fits on ONE
        // row under the new visible-length check (still budget 62). Confirmed by diffing every
        // regenerated fixture (`git diff` on the .txt files)
        // against its prior version: EVERY diff is that one continuation row disappearing per item
        // (rows shift up by one per item), the resulting page-break/print-area/scale figures
        // moving to match the now-shorter sheet, and the style-table indices the removed rows'
        // styles shift by. legacy-shape is untouched (byte-identical) -- the legacy (non-v2) item
        // path never runs the width-aware wrap at all (DealQuotationRenderAdapter's `alwaysShowSeq`
        // is v2-only). No row's TEXT content changed beyond the two lines above merging into one;
        // no border, font, merge, or unrelated cell moved.
        // Re-pinned 2026-09-15 (Fix 5, "-1" revision-suffix column clipping): fitColumnToText now
        // widens column I to fit the ACTUAL เลขที่อ้างอิง/Ref. text at I4, not just the grand-total
        // figure #sizeMoneyColumns already sized it for -- every one of these fixtures' reference
        // numbers ("QT-2026-0099"/"QT-2026-0042") needed a hair more room than their (modest) grand
        // totals did, so column I widens by the same 66 units (3123 -> 3189) on all four; confirmed
        // by diffing the regenerated .txt goldens above (the ONLY line that changed, anywhere, in
        // any of the five fixtures, is that one "col 8 w=" line). paginated is excluded from this
        // list: its case is skipped by the Assumptions.assumeTrue column-unit guard above on this
        // machine already, independently of this fix.
        // Re-pinned 2026-09-27 (signature-block equal-length-lines + drift fix, owner feedback):
        // #writeSignatureBlock now gives every slot's underscore line the SAME character count
        // (was: pad each label to fill whatever pixel width its OWN slot had left, so the
        // shortest label got the longest line) and centres the label+line unit, the name and the
        // date within their slot instead of independently re-deriving each block's own nominal
        // target — see the class comment above QuotationRenderer#SIG_LABELS and
        // #appendAtTarget's own Javadoc for why the old per-row-independent centring drifted
        // left slot by slot. The regenerated .txt dumps for the four signature-bearing fixtures
        // differ from their prior version ONLY in the labels/names/dates row text (now carrying
        // leading/interstitial spaces from the per-slot centring) and the signature picture's
        // horizontal anchor (it still tracks runStart[SIG_APPROVER_INDEX]/runEnd[...], which
        // moved because the line it anchors to moved) -- confirmed by `git diff` on the golden
        // .txt files. legacy-shape has no signature block, so its hash is unchanged.
        // Re-pinned AGAIN 2026-09-27 (same task, second pass): #SIGNATURE_SPACE_TO_UNDERSCORE_SCALE
        // added after the first re-pin above -- a small empirically-measured correction for the
        // SPACE glyph's own AWT-vs-LibreOffice rendering gap (see that constant's own Javadoc),
        // which further reduces the residual name/date left-drift the first pass didn't fully
        // remove. Confirmed by diffing the regenerated .txt goldens against their first-pass
        // version: every changed line is the names/dates row text (one leading space more or
        // fewer per slot) or the signature picture's horizontal anchor (still tracking the same
        // runStart/runEnd, which shifted by the same small amount); legacy-shape (no signature
        // block) is byte-identical to the very first pre-feature baseline.
        java.util.Map<String, String> preFeatureSha256 = java.util.Map.of(
            "single-page", "7062aa6be41308fe6f4bd4cf2f67571b5b1c019fdfd4c3e131b8a241615fcdee",
            "one-page-scaled", "17ede12207020eec5f4c044148632310da69de7a040631bd5270a62d4e843df2",
            "paginated", "02350a0a3af5c5939f68b2325d825ed90dd1e062d12452f09f0f75feeb55489e",
            // Re-pinned on develop 80f2484e: #930 deliberately changed the English form's output.
            // Re-pinned again 2026-09-13 (owner ruling 2): QuotationRenderer#applyEnglishTotals now
            // strips every border from the emptied subtotal/VAT rows and hides them, so Grand Total
            // sits directly under the table box. The regenerated english.txt differs from its prior
            // version ONLY in those two rows (hidden, b=NONE) plus the style indices the new
            // borderless styles shift; the four Thai fixtures are byte-identical.
            "english", "10c1fe2783de61a994af2fec5ffe76ecdcac1b93a0019792f0edff56196a19c1",
            "legacy-shape", "a92e576f622abb6fd0c2a49051e5513ae162adadbfb4b21636f56b7484de28fe");
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
